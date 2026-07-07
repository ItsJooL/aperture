package com.itsjool.aperture.runtime.audit;

import com.itsjool.aperture.spi.AuditEvent;
import com.itsjool.aperture.spi.AuditWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditBridgeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private AuditBridge auditBridge;
    @Mock
    private AuditWriter auditWriter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        auditBridge = new AuditBridge(auditWriter);
    }

    @Test
    void versionSuffixStripping() throws InterruptedException {
        // Create mock entities with different version suffixes
        ProductV1 entityV1 = new ProductV1("123");
        CustomerV3 entityV3 = new CustomerV3("456");
        OrderV10 entityV10 = new OrderV10("789");
        Item entityNoSuffix = new Item("999");

        // Execute audits for each entity
        auditBridge.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, entityV1, null, Optional.empty());
        auditBridge.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, entityV3, null, Optional.empty());
        auditBridge.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, entityV10, null, Optional.empty());
        auditBridge.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, entityNoSuffix, null, Optional.empty());

        // Give async executor time to process
        Thread.sleep(100);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter, times(4)).write(captor.capture());

        var events = captor.getAllValues();

        // V1 suffix should be stripped
        assertEquals("Product", events.get(0).entity());

        // V3 suffix should be stripped
        assertEquals("Customer", events.get(1).entity());

        // V10 suffix should be stripped
        assertEquals("Order", events.get(2).entity());

        // Name without suffix should be unchanged
        assertEquals("Item", events.get(3).entity());
    }

    @Test
    void shutdownTerminatesExecutor() throws Exception {
        java.lang.reflect.Field executorField = AuditBridge.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        java.util.concurrent.ExecutorService executor = (java.util.concurrent.ExecutorService) executorField.get(auditBridge);

        assertFalse(executor.isTerminated());
        auditBridge.shutdown();
        assertTrue(executor.isTerminated());
    }

    @Test
    void changeSpecDetailsContainFieldPathBeforeAndAfterValues() throws Exception {
        ChangeSpec changeSpec = new ChangeSpec(null, "status", "draft", "approved");

        auditBridge.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, new Item("audit-1"), null, Optional.of(changeSpec));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter, timeout(500)).write(captor.capture());

        JsonNode details = OBJECT_MAPPER.readTree(captor.getValue().detailsJson());
        assertEquals("status", details.get("fieldPath").asText());
        assertEquals("draft", details.get("before").asText());
        assertEquals("approved", details.get("after").asText());
    }

    @Test
    void changeSpecDetailsAreValidJsonWhenOriginalValueIsNull() throws Exception {
        ChangeSpec changeSpec = new ChangeSpec(null, "status", null, "draft");

        auditBridge.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, new Item("audit-2"), null, Optional.of(changeSpec));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter, timeout(500)).write(captor.capture());

        JsonNode details = OBJECT_MAPPER.readTree(captor.getValue().detailsJson());
        assertTrue(details.get("before").isNull());
        assertEquals("draft", details.get("after").asText());
    }

    @Test
    void changeSpecDetailsSerializeJavaTimeValuesWhenObjectMapperHasJavaTimeModule() throws Exception {
        // Generated entities map manifest "datetime" fields to java.time.LocalDateTime. A bare
        // `new ObjectMapper()` (the AuditBridge(AuditWriter) 1-arg constructor's default) cannot
        // serialize that type and silently falls back to {"detailsSerializationFailed":true},
        // losing before/after values for every date/time field update. Production wiring must
        // instead supply an ObjectMapper with JavaTimeModule registered (e.g. Spring's own
        // ObjectMapper bean) via the 2-arg constructor. This test drives that exact scenario.
        ObjectMapper javaTimeAwareMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        AuditBridge bridge = new AuditBridge(auditWriter, javaTimeAwareMapper);

        LocalDateTime before = LocalDateTime.of(2026, 1, 1, 9, 30, 0);
        LocalDateTime after = LocalDateTime.of(2026, 7, 7, 14, 45, 0);
        ChangeSpec changeSpec = new ChangeSpec(null, "occurredAt", before, after);

        bridge.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, new Item("audit-3"), null, Optional.of(changeSpec));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter, timeout(500)).write(captor.capture());

        String detailsJson = captor.getValue().detailsJson();
        assertFalse(detailsJson.contains("detailsSerializationFailed"),
            "LocalDateTime before/after values must not trigger the serialization-failure fallback");

        JsonNode details = OBJECT_MAPPER.readTree(detailsJson);
        assertEquals("occurredAt", details.get("fieldPath").asText());
        // Compare by parsing back to LocalDateTime rather than string form: LocalDateTime.toString()
        // elides zero seconds ("09:30") while Jackson's ISO serializer emits them ("09:30:00").
        // Both are valid ISO_LOCAL_DATE_TIME, so round-tripping is the format-agnostic check.
        assertEquals(before, LocalDateTime.parse(details.get("before").asText()));
        assertEquals(after, LocalDateTime.parse(details.get("after").asText()));
    }

    @Test
    void changeSpecDetailsFallBackWhenObjectMapperLacksJavaTimeModule() throws Exception {
        // Documents the failure mode FIX 1 addresses: a vanilla ObjectMapper (no JavaTimeModule)
        // cannot serialize LocalDateTime and must degrade to the fallback JSON rather than
        // throwing out of the lifecycle hook. Production must avoid this path by injecting a
        // JavaTimeModule-aware ObjectMapper (see the test above).
        LocalDateTime before = LocalDateTime.of(2026, 1, 1, 9, 30, 0);
        LocalDateTime after = LocalDateTime.of(2026, 7, 7, 14, 45, 0);
        ChangeSpec changeSpec = new ChangeSpec(null, "occurredAt", before, after);

        auditBridge.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, new Item("audit-4"), null, Optional.of(changeSpec));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter, timeout(500)).write(captor.capture());

        String detailsJson = captor.getValue().detailsJson();
        JsonNode details = OBJECT_MAPPER.readTree(detailsJson);
        assertTrue(details.get("detailsSerializationFailed").asBoolean());
        assertEquals("occurredAt", details.get("fieldPath").asText());
    }

    // Test entity classes for version suffix stripping
    static class ProductV1 {
        private String id;

        ProductV1(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    static class CustomerV3 {
        private String id;

        CustomerV3(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    static class OrderV10 {
        private String id;

        OrderV10(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    static class Item {
        private String id;

        Item(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }
}
