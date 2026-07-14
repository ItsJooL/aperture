package com.itsjool.aperture.runtime.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.runtime.config.ApertureAuditProperties;
import com.itsjool.aperture.spi.AuditEvent;
import com.itsjool.aperture.spi.AuditWriter;
import com.itsjool.aperture.spi.Encrypted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.yahoo.elide.core.dictionary.EntityDictionary;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.type.ClassType;
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
    private static final String REDACTED_SENTINEL = "[REDACTED]";

    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final EntityDictionary entityDictionary;
    private final ExecutorService executor;

    public AuditBridge(AuditWriter auditWriter) {
        this(auditWriter, new ObjectMapper());
    }

    public AuditBridge(AuditWriter auditWriter, ObjectMapper objectMapper) {
        // No entity metadata available through this constructor (used for direct/manual
        // construction, e.g. tests) — an empty dictionary means the redaction lookup in
        // buildDetailsJson always misses and falls back to "not redacted", same as before this
        // field existed. Production wiring goes through the 3-arg constructor below.
        this(auditWriter, objectMapper, EntityDictionary.builder().build());
    }

    // Elide instantiates lifecycle hooks bound via a class token (@LifeCycleHookBinding(hook =
    // AuditBridge.class)) through its own Injector's createBean(Class), not by looking up the
    // @Bean auditBridge(...) defined in ApertureSimpleAutoConfiguration by type. With multiple
    // constructors and none marked @Autowired, Spring's constructor resolution for createBean(Class)
    // cannot pick a unique autowire candidate and falls back to the no-arg constructor, whose
    // ObjectMapper has no JavaTimeModule. Marking this constructor @Autowired makes it the
    // deterministic choice, so the live hook instance gets the Spring-managed, JavaTimeModule-aware
    // ObjectMapper instead of a plain `new ObjectMapper()`. This is the same constructor commit
    // 848c50a fixed as 2-arg (AuditWriter, ObjectMapper); it is extended here to 3 args rather than
    // adding a second @Autowired-marked constructor, so exactly one constructor remains the
    // deterministic autowire candidate — see AuditDemoComponentTest's
    // elideInjectorConstructedAuditBridgeSerializesJavaTimeValuesWithoutFallback for the regression
    // test that exercises createBean(AuditBridge.class) directly against a real Spring context.
    @Autowired
    public AuditBridge(AuditWriter auditWriter, ObjectMapper objectMapper, EntityDictionary entityDictionary) {
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.entityDictionary = entityDictionary;
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
        // Captured here, at the moment the postcommit hook fires (i.e. when the audited
        // transaction actually committed) — not later, at whatever point auditWriter.write(event)
        // below eventually drains/persists it. The pipeline is asynchronous (this method
        // fire-and-forgets to executor; WebhookAuditWriter separately batches and flushes on an
        // interval), so those two instants can diverge; occurredAt must reflect the former.
        Instant occurredAt = Instant.now();
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
            details = buildDetailsJson(changes.get(), elideEntity.getClass(), entityName);
        }

        AuditEvent event = new AuditEvent(
            userId,
            tenantId,
            entityName,
            entityId,
            opName,
            details,
            occurredAt
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

    private String buildDetailsJson(ChangeSpec changeSpec, Class<?> entityClass, String entityName) {
        Map<String, Object> details = new LinkedHashMap<>();
        String fieldName = changeSpec.getFieldName();
        Object before = changeSpec.getOriginal();
        Object after = changeSpec.getModified();

        if (isRedacted(entityClass, entityName, fieldName)) {
            before = REDACTED_SENTINEL;
            after = REDACTED_SENTINEL;
        }

        details.put("fieldPath", fieldName);
        details.put("before", before);
        details.put("after", after);
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

    // Queried live, per call — not cached at construction time, and not cached across calls —
    // mirroring ApertureObservationFilter's EntityDictionary usage: bean-creation order between
    // the dictionary (populated by the DataStore as @Include-annotated entities are bound) and this
    // bridge isn't guaranteed, so only a fresh read through the live reference is guaranteed to see
    // the fully-populated dictionary once the app is actually serving traffic.
    private boolean isRedacted(Class<?> entityClass, String entityName, String fieldName) {
        if (fieldName == null) {
            return false;
        }

        ApertureAuditProperties.Redaction redaction = redactionConfig();
        if (redaction != null && !redaction.isEnabled()) {
            return false;
        }

        boolean encrypted;
        try {
            encrypted = entityDictionary.attributeOrRelationAnnotationExists(
                ClassType.of(entityClass), fieldName, Encrypted.class);
        } catch (Exception e) {
            // entityClass isn't bound in the dictionary (e.g. a non-Elide test double passed
            // directly to execute()) or another dictionary lookup failure. Treat this the same as
            // "no @Encrypted signal available" rather than letting an unrelated metadata gap break
            // audit-event serialization for every field on every entity.
            return false;
        }
        if (!encrypted) {
            return false;
        }

        return redaction == null || !isExempt(redaction, entityName, fieldName);
    }

    private boolean isExempt(ApertureAuditProperties.Redaction redaction, String entityName, String fieldName) {
        for (ApertureAuditProperties.Exemption exemption : redaction.getExemptions()) {
            if (entityName.equals(exemption.getEntity()) && fieldName.equals(exemption.getField())) {
                return true;
            }
        }
        return false;
    }

    // Not constructor-injected: AuditBridge is instantiated two different ways (Elide's own
    // createBean(Class) via the 3-arg @Autowired constructor above, and direct/manual construction
    // in tests via the 1- and 2-arg constructors), and adding a 4th constructor parameter here
    // would either require duplicating redaction config through every constructor or growing the
    // @Autowired constructor beyond the 3 args this fix's determinism guarantee was verified
    // against. SpringContextHelper.getBean is the same escape hatch the pre-existing no-arg
    // constructor already relies on for AuditWriter — unlike that call site, this one is on every
    // changed-field path, not just a one-off fallback constructor, so a missing bean or a
    // not-yet-initialized (or altogether absent, as in plain-unit-test construction) Spring context
    // must not throw out of the audit hook. Both failure modes (no ApplicationContext at all, or an
    // ApplicationContext without an ApertureAuditProperties bean registered) are treated as
    // "redaction enabled, no exemptions" — the secure default.
    private ApertureAuditProperties.Redaction redactionConfig() {
        try {
            ApertureAuditProperties properties =
                com.itsjool.aperture.runtime.util.SpringContextHelper.getBean(ApertureAuditProperties.class);
            return properties != null ? properties.getRedaction() : null;
        } catch (RuntimeException e) {
            return null;
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
