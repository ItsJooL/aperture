package com.itsjool.aperture.ratelimit;

import com.itsjool.aperture.runtime.config.ApertureRateLimitProperties;
import com.itsjool.aperture.spi.RateLimitDecision;
import com.itsjool.aperture.spi.RateLimitKey;
import com.itsjool.aperture.spi.RateLimitProvider;
import com.itsjool.aperture.spi.RateLimitRule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.redisson.Redisson;
import org.redisson.api.FunctionMode;
import org.redisson.api.FunctionResult;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.io.Closeable;
import java.util.List;

/**
 * Valkey/Redis-backed {@link RateLimitProvider}. The token-bucket algorithm runs entirely server-side
 * as a Redis Function ({@code FUNCTION LOAD} / {@code FCALL}) - this class only owns the client
 * transport and the plumbing around a single {@code consume} call.
 *
 * <h2>Client: Redisson, not a hand-rolled RESP client</h2>
 * Previously this class hand-rolled a RESP2 wire client (one {@code Socket}, one {@code synchronized}
 * lock, manual framing). Plan 031 replaces that with Redisson. Two things were confirmed
 * <b>empirically</b> against the project's pinned {@code valkey/valkey:9.1.0} image before writing this
 * class (see {@code dev-notes/plans/031-valkey-redisson-migration.md} Step 1):
 * <ul>
 *   <li>Redisson's {@link org.redisson.api.RFunction} API supports {@code FUNCTION LOAD}/{@code FCALL}
 *       ("Redis Functions") directly - no protocol incompatibility, no HELLO/RESP3 handshake mismatch.
 *       So the Lua function-library shape is kept as-is (loaded via {@code loadAndReplace}, invoked via
 *       {@code call(FunctionMode.WRITE, ...)}); there was no need to fall back to the EVALSHA-based
 *       plain-script mechanism the plan allowed for as a second choice.</li>
 *   <li>Redisson's {@code load()}/{@code loadAndReplace(name, code)} synthesize the
 *       {@code #!lua name=...} shebang line themselves from the {@code name} argument - the Lua source
 *       passed in must NOT include it (including it twice makes Valkey reject the load with
 *       "unexpected symbol near '#'").</li>
 * </ul>
 *
 * <h2>Lazy connection (fixes finding 1F)</h2>
 * {@link Config#setLazyInitialization(boolean)} is set to {@code true}, and the function library is
 * loaded lazily on first use (see {@link #ensureFunctionLoaded()}) rather than from the constructor.
 * So the constructor performs no I/O at all and never throws when Valkey is unreachable at boot; the
 * first real {@code evaluate()} call is what triggers connection establishment (and the library load),
 * and a failure there is just an ordinary exception that {@code RateLimitFilter.evaluateOrFailOpen()}
 * catches and fails open on - identical to any other runtime Valkey outage.
 *
 * <h2>Pooling / concurrency (fixes finding 1G)</h2>
 * Redisson's built-in connection pool replaces the single socket + single lock the old client used.
 * The only synchronization left in this class ({@link #ensureFunctionLoaded()}'s monitor) guards the
 * one-time function-library load, not the per-request {@code FCALL} round trip - once the library is
 * loaded, concurrent {@code evaluate()} calls proceed independently over the pool with no shared lock.
 *
 * <h2>Codec</h2>
 * The client is configured with a plain {@link StringCodec} rather than Redisson's default
 * {@code Kryo5Codec}. Empirically both round-tripped the key/args correctly for this use case, but
 * {@code StringCodec} is used anyway to keep the on-the-wire Valkey key format an explicit, obviously
 * plain string (matching the pre-migration behavior byte-for-byte) rather than relying on default-codec
 * behavior that could change across Redisson versions.
 */
public class ValkeyRateLimitProvider implements RateLimitProvider, Closeable {

    private final RedissonClient redissonClient;
    private final String libraryName;
    private final String functionName;
    private final String functionSource;
    private final String keyPrefix;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    private final Object loadLock = new Object();
    private volatile boolean functionLoaded = false;

    public ValkeyRateLimitProvider(ApertureRateLimitProperties.Valkey valkeyProperties) {
        this(valkeyProperties, null, null);
    }

    public ValkeyRateLimitProvider(ApertureRateLimitProperties.Valkey valkeyProperties, MeterRegistry meterRegistry) {
        this(valkeyProperties, meterRegistry, null);
    }

    public ValkeyRateLimitProvider(ApertureRateLimitProperties.Valkey valkeyProperties, MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.keyPrefix = valkeyProperties.getKeyPrefix();
        this.libraryName = valkeyProperties.getLibraryName();
        this.functionName = valkeyProperties.getFunctionName();
        this.functionSource = buildFunctionSource(this.functionName);
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP;

        // No I/O here: lazyInitialization defers the actual TCP connect (and the function-library
        // load, see ensureFunctionLoaded()) until the first evaluate() call. This is the 1F fix - a
        // Valkey outage at boot must not fail bean creation / app startup.
        Config config = new Config();
        config.setLazyInitialization(true);
        config.setCodec(new StringCodec());
        config.useSingleServer().setAddress("redis://" + valkeyProperties.getHost() + ":" + valkeyProperties.getPort());
        this.redissonClient = Redisson.create(config);
    }

    @Override
    public RateLimitDecision evaluate(RateLimitKey key, RateLimitRule rule) {
        if (rule.capacity() <= 0) {
            RateLimitDecision decision = new RateLimitDecision(false, 0, Math.max(rule.windowSeconds(), 1));
            recordRejectionIfNeeded(key, decision);
            return decision;
        }

        Observation observation = Observation.createNotStarted("aperture.ratelimit.valkey", observationRegistry)
                .lowCardinalityKeyValue("type", key.type());
        observation.start();
        try (Observation.Scope scope = observation.openScope()) {
            ensureFunctionLoaded();

            List<?> values = redissonClient.getFunction().call(
                    FunctionMode.WRITE,
                    functionName,
                    FunctionResult.LIST,
                    List.of(redisKey(key)),
                    Integer.toString(rule.capacity()),
                    Integer.toString(rule.burst()),
                    Integer.toString(rule.windowSeconds()));

            long allowed = asLong(values, 0);
            int remaining = (int) asLong(values, 1);
            long retryAfterSeconds = asLong(values, 2);
            RateLimitDecision decision = new RateLimitDecision(allowed == 1L, Math.max(remaining, 0), Math.max(retryAfterSeconds, 0L));
            observation.lowCardinalityKeyValue("outcome", decision.allowed() ? "allowed" : "rejected");
            recordRejectionIfNeeded(key, decision);
            return decision;
        } catch (RuntimeException e) {
            observation.error(e);
            observation.lowCardinalityKeyValue("outcome", "error");
            throw e;
        } finally {
            observation.stop();
        }
    }

    /**
     * Mirrors {@code InMemoryRateLimitProvider}'s {@code aperture.ratelimit.rejections} counter
     * (same name, same {@code type} tag) so a rejection is equally observable regardless of which
     * {@link RateLimitProvider} backend is configured.
     */
    private void recordRejectionIfNeeded(RateLimitKey key, RateLimitDecision decision) {
        if (!decision.allowed() && meterRegistry != null) {
            meterRegistry.counter("aperture.ratelimit.rejections", "type", key.type()).increment();
        }
    }

    @Override
    public void close() {
        redissonClient.shutdown();
    }

    /**
     * Loads the token-bucket function library on first use (not from the constructor - see class
     * Javadoc). Double-checked locking guards only this one-time load: once {@link #functionLoaded} is
     * {@code true}, every subsequent {@code evaluate()} call takes the lock-free fast path straight
     * through to {@code FCALL}. If the load fails (e.g. Valkey unreachable), {@link #functionLoaded}
     * stays {@code false} so the next call retries the load rather than being permanently stuck.
     */
    private void ensureFunctionLoaded() {
        if (functionLoaded) {
            return;
        }
        synchronized (loadLock) {
            if (functionLoaded) {
                return;
            }
            redissonClient.getFunction().loadAndReplace(libraryName, functionSource);
            functionLoaded = true;
        }
    }

    private static String buildFunctionSource(String functionName) {
        return """
                redis.register_function('%s', function(keys, args)
                  local key = keys[1]
                  local capacity = tonumber(args[1])
                  local refill_tokens = tonumber(args[2])
                  local window_seconds = tonumber(args[3])

                  if capacity == nil or capacity <= 0 then
                    return {0, 0, window_seconds or 1}
                  end

                  if window_seconds == nil or window_seconds <= 0 then
                    window_seconds = 1
                  end

                  if refill_tokens == nil or refill_tokens <= 0 then
                    refill_tokens = capacity
                  end

                  local now = redis.call('TIME')
                  local now_seconds = tonumber(now[1]) + (tonumber(now[2]) / 1000000.0)
                  local state = redis.call('HMGET', key, 'tokens', 'last_refill')
                  local tokens = tonumber(state[1])
                  local last_refill = tonumber(state[2])

                  if tokens == nil then
                    tokens = capacity
                  end

                  if last_refill == nil then
                    last_refill = now_seconds
                  end

                  local refill_rate = refill_tokens / window_seconds
                  local elapsed = now_seconds - last_refill
                  if elapsed > 0 then
                    tokens = math.min(capacity, tokens + (elapsed * refill_rate))
                  end

                  local allowed = 0
                  local retry_after = 0
                  if tokens >= 1 then
                    allowed = 1
                    tokens = tokens - 1
                  else
                    local deficit = 1 - tokens
                    retry_after = math.ceil(deficit / refill_rate)
                  end

                  local remaining = math.floor(math.max(tokens, 0))
                  redis.call('HSET', key, 'tokens', tokens, 'last_refill', now_seconds)
                  redis.call('PEXPIRE', key, math.max(window_seconds * 2000, 1000))
                  return {allowed, remaining, retry_after}
                end)
                """.formatted(functionName);
    }

    private String redisKey(RateLimitKey key) {
        return keyPrefix + key.type() + ":" + key.value();
    }

    private static long asLong(List<?> values, int index) {
        Object value = values.get(index);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            return Long.parseLong(string);
        }
        throw new IllegalStateException("Unexpected Valkey response value: " + value);
    }
}
