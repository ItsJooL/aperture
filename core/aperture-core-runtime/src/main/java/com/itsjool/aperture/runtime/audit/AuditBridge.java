package com.itsjool.aperture.runtime.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.spi.AuditEvent;
import com.itsjool.aperture.spi.AuditWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.ChangeSpec;
import com.itsjool.aperture.spi.AperturePrincipal;
import java.util.Optional;
import java.util.UUID;
import jakarta.annotation.PreDestroy;

public class AuditBridge implements LifeCycleHook<Object> {
    private static final Logger log = LoggerFactory.getLogger(AuditBridge.class);
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public AuditBridge(AuditWriter auditWriter) {
        this(auditWriter, new ObjectMapper());
    }

    public AuditBridge(AuditWriter auditWriter, ObjectMapper objectMapper) {
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AuditBridge-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public AuditBridge() {
        this(com.itsjool.aperture.runtime.util.SpringContextHelper.getBean(AuditWriter.class));
    }

    @Override
    public void execute(Operation operation, TransactionPhase phase, Object elideEntity, RequestScope requestScope, Optional<ChangeSpec> changes) {
        if (auditWriter == null || phase != TransactionPhase.POSTCOMMIT) {
            return;
        }

        String userId = "system";
        String tenantId = "system";
        if (requestScope != null && requestScope.getUser() != null && requestScope.getUser().getPrincipal() instanceof AperturePrincipal) {
            AperturePrincipal principal = (AperturePrincipal) requestScope.getUser().getPrincipal();
            userId = principal.userId();
            tenantId = principal.tenantId();
        }

        String entityName = elideEntity.getClass().getSimpleName();
        entityName = entityName.replaceAll("V\\d+$", "");

        String entityId = "unknown";
        try {
            java.lang.reflect.Method getId = elideEntity.getClass().getMethod("getId");
            Object idVal = getId.invoke(elideEntity);
            if (idVal != null) entityId = idVal.toString();
        } catch (Exception e) {
            // Ignore
        }

        String opName = operation.name();
        String details = "{}";
        if (changes.isPresent()) {
            details = buildDetailsJson(changes.get());
        }

        AuditEvent event = new AuditEvent(
            userId,
            tenantId,
            entityName,
            entityId,
            opName,
            details
        );

        // Fire-and-forget async execution to decouple from request thread
        executor.submit(() -> {
            try {
                auditWriter.write(event);
            } catch (Exception e) {
                log.error("Failed to write audit event asynchronously", e);
            }
        });
    }

    private String buildDetailsJson(ChangeSpec changeSpec) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fieldPath", changeSpec.getFieldName());
        details.put("before", changeSpec.getOriginal());
        details.put("after", changeSpec.getModified());
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            // Broad catch: relationship ChangeSpec values (lazy-loaded entity references/collections)
            // can fail serialization in ways other than JsonProcessingException (e.g.
            // LazyInitializationException). Degrade to the fallback rather than escaping the
            // lifecycle hook, but keep the failure attributable to the field that caused it.
            log.warn("Failed to serialize audit change details; writing fallback details JSON", e);
            String fieldPath = changeSpec.getFieldName() != null ? changeSpec.getFieldName() : "";
            return "{\"detailsSerializationFailed\":true,\"fieldPath\":\"" + fieldPath.replace("\"", "\\\"") + "\"}";
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
