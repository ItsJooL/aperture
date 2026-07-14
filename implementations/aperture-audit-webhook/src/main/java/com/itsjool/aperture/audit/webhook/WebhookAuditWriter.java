package com.itsjool.aperture.audit.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.itsjool.aperture.spi.AuditEvent;
import com.itsjool.aperture.spi.AuditWriter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class WebhookAuditWriter implements AuditWriter {
    private static final Logger log = LoggerFactory.getLogger(WebhookAuditWriter.class);

    private final URI endpoint;
    private final int batchSize;
    private final Duration flushInterval;
    private final BlockingQueue<AuditEvent> queue;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService worker;
    private volatile boolean running = true;

    public WebhookAuditWriter(URI endpoint) {
        this(endpoint, 100, Duration.ofSeconds(1), 10_000);
    }

    public WebhookAuditWriter(URI endpoint, int batchSize, Duration flushInterval, int queueCapacity) {
        this(endpoint, batchSize, flushInterval, queueCapacity, HttpClient.newHttpClient(), defaultObjectMapper());
    }

    // AuditEvent.occurredAt is a java.time.Instant. Without JavaTimeModule registered, Jackson
    // cannot serialize it at all (postBatch would throw for every batch, dropping events rather
    // than shipping them). This writer is constructed directly by callers (e.g.
    // AuditDemoAuditConfiguration) rather than through Spring/Elide's injector, so — unlike
    // AuditBridge's equivalent fix — there is no autowiring path to hand it a JavaTimeModule-aware
    // ObjectMapper; it must be self-sufficient regardless of the caller.
    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    WebhookAuditWriter(URI endpoint, int batchSize, Duration flushInterval, int queueCapacity, HttpClient httpClient, ObjectMapper objectMapper) {
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint is required");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (flushInterval == null || flushInterval.isNegative() || flushInterval.isZero()) {
            throw new IllegalArgumentException("flushInterval must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.endpoint = endpoint;
        this.batchSize = batchSize;
        this.flushInterval = flushInterval;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "WebhookAuditWriter-Worker");
            thread.setDaemon(true);
            return thread;
        });
        this.worker.submit(this::processQueue);
    }

    @Override
    public void write(AuditEvent event) {
        if (!running) {
            log.warn("Dropping audit event because webhook audit writer is shutting down");
            return;
        }
        if (!queue.offer(event)) {
            log.warn("Dropping audit event because webhook audit queue is full");
        }
    }

    private void processQueue() {
        List<AuditEvent> batch = new ArrayList<>(batchSize);
        long lastFlushNanos = System.nanoTime();

        while (running || !queue.isEmpty() || !batch.isEmpty()) {
            try {
                AuditEvent event = queue.poll(flushInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (event != null) {
                    batch.add(event);
                }

                boolean intervalElapsed = Duration.ofNanos(System.nanoTime() - lastFlushNanos).compareTo(flushInterval) >= 0;
                boolean shouldFlush = !batch.isEmpty()
                        && (batch.size() >= batchSize || intervalElapsed || (!running && queue.isEmpty()));

                if (shouldFlush) {
                    postBatch(batch);
                    batch.clear();
                    lastFlushNanos = System.nanoTime();
                }
            } catch (InterruptedException e) {
                if (running) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void postBatch(List<AuditEvent> batch) {
        try {
            String payload = objectMapper.writeValueAsString(batch.stream()
                    .map(this::toPayload)
                    .toList());
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Webhook audit endpoint returned status {}", response.statusCode());
            }
        } catch (IOException e) {
            log.warn("Failed to post audit events to webhook endpoint", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, Object> toPayload(AuditEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", event.userId());
        payload.put("tenantId", event.tenantId());
        payload.put("entity", event.entity());
        payload.put("entityId", event.entityId());
        payload.put("operation", event.operation());
        payload.put("details", parseDetails(event.detailsJson()));
        // Serialized by the JavaTimeModule-registered ObjectMapper as an ISO-8601 UTC string (the
        // "...Z" suffix), parseable natively by essentially every mainstream language's standard
        // library — the whole point of occurredAt for a cross-language SIEM/webhook consumer.
        payload.put("occurredAt", event.occurredAt());
        return payload;
    }

    private Object parseDetails(String detailsJson) {
        try {
            return objectMapper.readTree(detailsJson);
        } catch (JsonProcessingException e) {
            return detailsJson;
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        // Graceful drain: shutdown() (not shutdownNow()) lets the worker finish flushing the queued
        // batch instead of interrupting an in-flight POST. Wait longer than the per-request HTTP
        // timeout (10s) so that a final batch already on the wire can complete before we give up.
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warn("Webhook audit writer did not drain within timeout; some events may be unsent");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
