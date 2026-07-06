package com.itsjool.aperture.ratelimit;

import com.itsjool.aperture.runtime.config.ApertureRateLimitProperties;
import com.itsjool.aperture.spi.RateLimitDecision;
import com.itsjool.aperture.spi.RateLimitKey;
import com.itsjool.aperture.spi.RateLimitProvider;
import com.itsjool.aperture.spi.RateLimitRule;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ValkeyRateLimitProvider implements RateLimitProvider, Closeable {
    private static final String FUNCTION_NAME = "consume";
    private static final String LIBRARY_NAME = "aperture_rate_limit";

    private final RespClient client;
    private final String keyPrefix;

    public ValkeyRateLimitProvider(ApertureRateLimitProperties.Valkey valkeyProperties) {
        this.keyPrefix = valkeyProperties.getKeyPrefix();
        this.client = new RespClient(valkeyProperties.getHost(), valkeyProperties.getPort());
        loadFunctionLibrary();
    }

    @Override
    public RateLimitDecision evaluate(RateLimitKey key, RateLimitRule rule) {
        if (rule.capacity() <= 0) {
            return new RateLimitDecision(false, 0, Math.max(rule.windowSeconds(), 1));
        }

        Object response = client.command(
                "FCALL",
                FUNCTION_NAME,
                "1",
                redisKey(key),
                Integer.toString(rule.capacity()),
                Integer.toString(rule.burst()),
                Integer.toString(rule.windowSeconds()));

        List<?> values = asList(response);
        long allowed = asLong(values, 0);
        int remaining = (int) asLong(values, 1);
        long retryAfterSeconds = asLong(values, 2);
        return new RateLimitDecision(allowed == 1L, Math.max(remaining, 0), Math.max(retryAfterSeconds, 0L));
    }

    @Override
    public void close() {
        client.close();
    }

    private void loadFunctionLibrary() {
        String functionSource = """
                #!lua name=%s
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
                """.formatted(LIBRARY_NAME, FUNCTION_NAME);

        client.command("FUNCTION", "LOAD", "REPLACE", functionSource);
    }

    private String redisKey(RateLimitKey key) {
        return keyPrefix + key.type() + ":" + key.value();
    }

    @SuppressWarnings("unchecked")
    private static List<?> asList(Object response) {
        if (response instanceof List<?>) {
            return (List<?>) response;
        }
        throw new IllegalStateException("Unexpected Valkey response: " + response);
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

    private static final class RespClient implements Closeable {
        private final String host;
        private final int port;
        private final Object lock = new Object();
        private Socket socket;
        private InputStream input;
        private OutputStream output;

        private RespClient(String host, int port) {
            this.host = host;
            this.port = port;
        }

        private Object command(String... parts) {
            synchronized (lock) {
                try {
                    ensureConnected();
                    writeCommand(parts);
                    return readValue();
                } catch (IOException first) {
                    reconnect();
                    try {
                        writeCommand(parts);
                        return readValue();
                    } catch (IOException second) {
                        throw new IllegalStateException("Valkey command failed after reconnect", second);
                    }
                }
            }
        }

        private void ensureConnected() throws IOException {
            if (socket != null && socket.isConnected() && !socket.isClosed()) {
                return;
            }
            reconnect();
        }

        private void reconnect() {
            closeQuietly();
            try {
                Socket newSocket = new Socket();
                newSocket.connect(new InetSocketAddress(host, port), (int) Duration.ofSeconds(2).toMillis());
                newSocket.setSoTimeout((int) Duration.ofSeconds(2).toMillis());
                socket = newSocket;
                input = newSocket.getInputStream();
                output = newSocket.getOutputStream();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to connect to Valkey at " + host + ":" + port, e);
            }
        }

        private void writeCommand(String... parts) throws IOException {
            output.write(("*" + parts.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(bytes);
                output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            output.flush();
        }

        private Object readValue() throws IOException {
            int type = input.read();
            if (type == -1) {
                throw new EOFException("Valkey connection closed");
            }

            return switch (type) {
                case '+' -> readLine();
                case '-' -> throw new IOException("Valkey error: " + readLine());
                case ':' -> Long.parseLong(readLine());
                case '$' -> readBulkString();
                case '*' -> readArray();
                default -> throw new IOException("Unexpected RESP type: " + (char) type);
            };
        }

        private List<Object> readArray() throws IOException {
            int length = Integer.parseInt(readLine());
            if (length < 0) {
                return null;
            }
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                values.add(readValue());
            }
            return values;
        }

        private String readBulkString() throws IOException {
            int length = Integer.parseInt(readLine());
            if (length < 0) {
                return null;
            }
            byte[] buffer = input.readNBytes(length);
            if (buffer.length != length) {
                throw new EOFException("Unexpected end of bulk string");
            }
            expectCrlf();
            return new String(buffer, StandardCharsets.UTF_8);
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int current = input.read();
                if (current == -1) {
                    throw new EOFException("Unexpected end of RESP line");
                }
                if (previous == '\r' && current == '\n') {
                    break;
                }
                if (previous != -1) {
                    buffer.write(previous);
                }
                previous = current;
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }

        private void expectCrlf() throws IOException {
            int first = input.read();
            int second = input.read();
            if (first != '\r' || second != '\n') {
                throw new EOFException("Expected CRLF after bulk string");
            }
        }

        @Override
        public void close() {
            closeQuietly();
        }

        private void closeQuietly() {
            try {
                if (input != null) {
                    input.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (output != null) {
                    output.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
            socket = null;
            input = null;
            output = null;
        }
    }
}
