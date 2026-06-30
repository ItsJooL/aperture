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

public class JdbcAuditWriter implements AuditWriter {
    private final JdbcTemplate jdbcTemplate;
    private final BlockingQueue<AuditEvent> queue = new LinkedBlockingQueue<>(10000);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    public JdbcAuditWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.worker.submit(this::processQueue);
    }

    @Override
    public void write(AuditEvent event) {
        queue.offer(event);
    }
    
    private void processQueue() {
        while (running || !queue.isEmpty()) {
            try {
                List<AuditEvent> batch = new ArrayList<>();
                AuditEvent first = queue.poll(1, TimeUnit.SECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, 99);
                    
                    jdbcTemplate.batchUpdate(
                        "INSERT INTO aperture_audit_log (id, user_id, tenant_id, entity, entity_id, operation, timestamp, details) VALUES (?, ?, ?, ?, ?, ?, NOW(), ?::jsonb)",
                        batch,
                        batch.size(),
                        (ps, event) -> {
                            ps.setString(1, UUID.randomUUID().toString());
                            ps.setString(2, event.userId());
                            ps.setString(3, event.tenantId());
                            ps.setString(4, event.entity());
                            ps.setString(5, event.entityId());
                            ps.setString(6, event.operation());
                            ps.setString(7, event.detailsJson());
                        }
                    );
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
