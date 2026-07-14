package com.itsjool.aperture.audit;

import com.itsjool.aperture.spi.AuditWriter;
import com.itsjool.aperture.spi.AuditEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

public class JdbcAuditWriter implements AuditWriter {
    private record QueuedEvent(AuditEvent event, Observation parentObservation) {}
    private final JdbcTemplate jdbcTemplate;
    private final BlockingQueue<QueuedEvent> queue = new LinkedBlockingQueue<>(10000);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;
    private final ObservationRegistry observationRegistry;
    private final Counter droppedCounter;

    public JdbcAuditWriter(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.observationRegistry = observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP;
        if (meterRegistry != null) {
            Gauge.builder("aperture.audit.queue.size", queue, BlockingQueue::size)
                .description("Number of audit events pending database write")
                .register(meterRegistry);
            this.droppedCounter = meterRegistry.counter("aperture.audit.dropped");
        } else {
            this.droppedCounter = null;
        }
        this.worker.submit(this::processQueue);
    }

    @Override
    public void write(AuditEvent event) {
        if (!queue.offer(new QueuedEvent(event, observationRegistry.getCurrentObservation()))) {
            if (droppedCounter != null) droppedCounter.increment();
        }
    }
    
    private void processQueue() {
        while (running || !queue.isEmpty()) {
            try {
                List<QueuedEvent> batch = new ArrayList<>();
                QueuedEvent first = queue.poll(1, TimeUnit.SECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, 99);
                    
                    List<Observation> observations = new ArrayList<>();
                    for (QueuedEvent qe : batch) {
                        Observation obs = Observation.createNotStarted("aperture.audit.write", observationRegistry)
                            .lowCardinalityKeyValue("entity", qe.event().entity())
                            .lowCardinalityKeyValue("operation", qe.event().operation());
                        if (qe.parentObservation() != null) {
                            obs.parentObservation(qe.parentObservation());
                        }
                        obs.start();
                        observations.add(obs);
                    }

                    try {
                        jdbcTemplate.batchUpdate(
                            "INSERT INTO aperture_audit_log (id, user_id, tenant_id, entity, entity_id, operation, timestamp, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                            batch,
                            batch.size(),
                            (ps, qe) -> {
                                ps.setString(1, UUID.randomUUID().toString());
                                ps.setString(2, qe.event().userId());
                                ps.setString(3, qe.event().tenantId());
                                ps.setString(4, qe.event().entity());
                                ps.setString(5, qe.event().entityId());
                                ps.setString(6, qe.event().operation());
                                // event.occurredAt() — the moment the audited change actually
                                // happened (captured by AuditBridge at hook-fire/commit time) — not
                                // NOW(), which would instead stamp the moment this batch drained
                                // off the queue, lagging behind by up to the flush interval.
                                ps.setTimestamp(7, java.sql.Timestamp.from(qe.event().occurredAt()));
                                ps.setString(8, qe.event().detailsJson());
                            }
                        );
                        for (Observation obs : observations) {
                            obs.lowCardinalityKeyValue("outcome", "ok");
                            obs.stop();
                        }
                    } catch (Exception e) {
                        for (Observation obs : observations) {
                            obs.error(e);
                            obs.lowCardinalityKeyValue("outcome", "error");
                            obs.stop();
                        }
                        throw e;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Audit batch write failed: " + e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        worker.shutdown();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
