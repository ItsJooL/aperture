package com.itsjool.aperture.ratelimit;

import com.itsjool.aperture.runtime.config.ApertureRateLimitProperties;
import com.itsjool.aperture.spi.RateLimitDecision;
import com.itsjool.aperture.spi.RateLimitKey;
import com.itsjool.aperture.spi.RateLimitRule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.FunctionLibrary;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * First direct unit-test coverage for {@link ValkeyRateLimitProvider} (Plan 031) - the class had zero
 * prior tests. Runs against a real Testcontainers Valkey instance pinned to the same image
 * ({@code valkey/valkey:9.1.0}) used by {@code demos/aperture-demo}'s
 * {@code DemoApplicationTestSupport}, so these tests exercise the real Redis-Functions round trip, not
 * a mock.
 */
@Testcontainers
class ValkeyRateLimitProviderTest {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> valkey = new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.1.0"))
            .withExposedPorts(6379)
            .withCommand("valkey-server", "--save", "", "--appendonly", "no");

    static {
        valkey.start();
    }

    private static ApertureRateLimitProperties.Valkey valkeyProperties() {
        ApertureRateLimitProperties.Valkey properties = new ApertureRateLimitProperties.Valkey();
        properties.setHost(valkey.getHost());
        properties.setPort(valkey.getMappedPort(6379));
        return properties;
    }

    @Test
    void allowsUpToCapacityThenDeniesWithZeroRemaining() {
        ValkeyRateLimitProvider provider = new ValkeyRateLimitProvider(valkeyProperties(), new SimpleMeterRegistry());
        RateLimitKey key = new RateLimitKey("ip", "192.0.2.1");
        // Long window relative to the number of calls in this test so real elapsed time can't refill
        // a meaningful fraction of a token and flip the boundary assertion.
        RateLimitRule rule = new RateLimitRule(3, 3, 3600);

        RateLimitDecision first = provider.evaluate(key, rule);
        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(2);

        RateLimitDecision second = provider.evaluate(key, rule);
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isEqualTo(1);

        RateLimitDecision third = provider.evaluate(key, rule);
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isEqualTo(0);

        RateLimitDecision fourth = provider.evaluate(key, rule);
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.remaining()).isEqualTo(0);
        // retry_after = ceil((1 - tokens) / (burst / windowSeconds)) = ceil(1 / (3/3600)) = 1200
        assertThat(fourth.retryAfterSeconds()).isEqualTo(1200L);
    }

    /**
     * Proves the refill rate the Lua function actually applies comes from {@code burst}
     * (refillTokens), not {@code capacity} - same contract {@code InMemoryRateLimitProvider}'s
     * equivalent regression test enforces, just observed here through real wall-clock refill instead
     * of inspecting an in-process bucket object (the token bucket lives server-side in Valkey).
     * capacity is deliberately far larger than burst so a capacity-driven refill (bug) and a
     * burst-driven refill (correct) are trivially distinguishable even with test timing jitter.
     */
    @Test
    void refillRateIsDrivenByBurstNotCapacity() throws InterruptedException {
        ValkeyRateLimitProvider provider = new ValkeyRateLimitProvider(valkeyProperties(), new SimpleMeterRegistry());
        RateLimitKey key = new RateLimitKey("ip", "192.0.2.2");
        // capacity is kept small so the exhausting loop itself (its own real wall-clock time) can't
        // refill a meaningful fraction of a token before the "exhausted" assertion below - otherwise
        // this test would be flaky regardless of which refill rate is used.
        int capacity = 50;
        int burst = 2;
        int windowSeconds = 2; // refill_rate = burst / windowSeconds = 1 token/sec
        RateLimitRule rule = new RateLimitRule(capacity, burst, windowSeconds);

        for (int i = 0; i < capacity; i++) {
            RateLimitDecision decision = provider.evaluate(key, rule);
            assertThat(decision.allowed()).as("call %d of %d should still be within capacity", i + 1, capacity).isTrue();
        }
        RateLimitDecision exhausted = provider.evaluate(key, rule);
        assertThat(exhausted.allowed()).isFalse();

        Thread.sleep(2200); // ~2.2s * 1 token/sec =~ 2 tokens refilled

        RateLimitDecision afterRefill = provider.evaluate(key, rule);
        assertThat(afterRefill.allowed()).isTrue();
        // A capacity-driven refill bug would refill ~2.2s * (capacity/windowSeconds) = 55 tokens
        // (capped at capacity=50, i.e. remaining ~49 after consuming 1); the correct burst-driven
        // refill is ~2.2 tokens (remaining ~1 after consuming 1). A generous bound absorbs test
        // scheduling jitter while staying nowhere near the capacity-driven magnitude.
        assertThat(afterRefill.remaining()).isBetween(0, 5);
    }

    /**
     * The whole point of fixing 1G: N concurrent evaluate() calls must no longer serialize behind a
     * single lock/socket. Compares concurrent wall-clock time against N times the average solo-call
     * latency (the old single-lock client's approximate cost) rather than asserting an absolute
     * threshold, so this isn't sensitive to how fast the test host/Docker happens to be.
     */
    @Test
    void concurrentEvaluateCallsAreNotSerializedBehindASingleLock() throws Exception {
        ValkeyRateLimitProvider provider = new ValkeyRateLimitProvider(valkeyProperties(), new SimpleMeterRegistry());
        RateLimitRule rule = new RateLimitRule(1_000_000, 1_000_000, 3600);

        // Warm up: absorb the one-time function-library load and connection-pool priming cost before
        // timing anything, so it doesn't get misattributed to either measurement below.
        provider.evaluate(new RateLimitKey("ip", "warmup"), rule);

        int soloSamples = 10;
        long soloTotalNanos = 0;
        for (int i = 0; i < soloSamples; i++) {
            long start = System.nanoTime();
            provider.evaluate(new RateLimitKey("ip", "solo-" + i), rule);
            soloTotalNanos += System.nanoTime() - start;
        }
        long avgSoloNanos = soloTotalNanos / soloSamples;

        int concurrency = 30;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                provider.evaluate(new RateLimitKey("ip", "concurrent-" + idx), rule);
            }));
        }
        ready.await();
        long concurrentStart = System.nanoTime();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        long concurrentNanos = System.nanoTime() - concurrentStart;
        pool.shutdownNow();

        long naiveSerializedEstimateNanos = avgSoloNanos * concurrency;

        assertThat(concurrentNanos)
                .as("concurrent time for %d calls (%.2fms) vs. a naive-serialized estimate of %.2fms (avg solo call %.2fms)",
                        concurrency, concurrentNanos / 1_000_000.0, naiveSerializedEstimateNanos / 1_000_000.0, avgSoloNanos / 1_000_000.0)
                .isLessThan(naiveSerializedEstimateNanos / 2);
    }

    /**
     * 1F fix: constructing the provider must not touch the network at all (Config.lazyInitialization),
     * so a Valkey outage at boot cannot fail Spring bean creation / app startup. The first real
     * evaluate() call is what discovers the outage - and it must throw (not hang, not silently return
     * a bogus decision) so that RateLimitFilter.evaluateOrFailOpen's catch/log/count/fail-open contract
     * (core/aperture-core-runtime/.../filter/RateLimitFilter.java) works exactly as it does for any
     * other runtime Valkey failure.
     */
    @Test
    void constructorDoesNotThrowWhenValkeyUnreachableAndEvaluateFailsOpen() {
        @SuppressWarnings("resource")
        GenericContainer<?> transientValkey = new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.1.0"))
                .withExposedPorts(6379)
                .withCommand("valkey-server", "--save", "", "--appendonly", "no");
        transientValkey.start();
        String host = transientValkey.getHost();
        int port = transientValkey.getMappedPort(6379);
        transientValkey.stop(); // port mapping torn down: host:port is now unreachable

        ApertureRateLimitProperties.Valkey properties = new ApertureRateLimitProperties.Valkey();
        properties.setHost(host);
        properties.setPort(port);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        ValkeyRateLimitProvider provider = assertDoesNotThrow(
                () -> new ValkeyRateLimitProvider(properties, meterRegistry),
                "constructor must not perform I/O / must not throw when Valkey is unreachable at boot");

        RateLimitKey key = new RateLimitKey("ip", "203.0.113.42");
        RateLimitRule rule = new RateLimitRule(10, 10, 60);

        // Mirrors RateLimitFilter.evaluateOrFailOpen's exact try/catch contract.
        RateLimitDecision decision;
        try {
            provider.evaluate(key, rule);
            decision = null;
            fail("expected evaluate() to throw when Valkey is unreachable, so the caller can fail open");
        } catch (RuntimeException e) {
            meterRegistry.counter("aperture.ratelimit.failopen", "type", key.type()).increment();
            decision = new RateLimitDecision(true, rule.capacity(), 0);
        }

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
        assertThat(meterRegistry.get("aperture.ratelimit.failopen").tags("type", "ip").counter().count()).isEqualTo(1.0);
    }

    /**
     * Pre-existing bug fix folded into this migration: {@code libraryName}/{@code functionName} were
     * declared on {@link ApertureRateLimitProperties.Valkey} but the old client ignored them in favor
     * of hardcoded constants. Confirms a non-default value is both functionally honored (evaluate()
     * still works) and actually what gets loaded into Valkey (inspected via an independent connection),
     * not just accepted and silently discarded.
     */
    @Test
    void libraryNameAndFunctionNamePropertiesAreHonored() {
        ApertureRateLimitProperties.Valkey properties = valkeyProperties();
        properties.setLibraryName("custom_rate_limit_lib");
        properties.setFunctionName("custom_consume_fn");

        ValkeyRateLimitProvider provider = new ValkeyRateLimitProvider(properties, new SimpleMeterRegistry());
        RateLimitKey key = new RateLimitKey("ip", "198.51.100.77");
        RateLimitRule rule = new RateLimitRule(5, 5, 60);

        RateLimitDecision decision = provider.evaluate(key, rule);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(4);

        Config inspectConfig = new Config();
        inspectConfig.setCodec(new StringCodec());
        inspectConfig.useSingleServer().setAddress("redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379));
        RedissonClient inspectClient = Redisson.create(inspectConfig);
        try {
            List<FunctionLibrary> libraries = inspectClient.getFunction().list();
            assertThat(libraries).anySatisfy(library -> {
                assertThat(library.getName()).isEqualTo("custom_rate_limit_lib");
                assertThat(library.getFunctions()).anySatisfy(function ->
                        assertThat(function.getName()).isEqualTo("custom_consume_fn"));
            });
        } finally {
            inspectClient.shutdown();
        }
    }

    @Test
    void rejectedRequestIncrementsRejectionCounterTaggedByKeyType() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ValkeyRateLimitProvider provider = new ValkeyRateLimitProvider(valkeyProperties(), meterRegistry);
        RateLimitKey key = new RateLimitKey("ip", "198.51.100.200");
        RateLimitRule rule = new RateLimitRule(1, 1, 3600);

        RateLimitDecision first = provider.evaluate(key, rule);
        assertThat(first.allowed()).isTrue();

        RateLimitDecision second = provider.evaluate(key, rule);
        assertThat(second.allowed()).isFalse();

        assertThat(meterRegistry.get("aperture.ratelimit.rejections").tags("type", "ip").counter().count())
                .isEqualTo(1.0);
    }
}
