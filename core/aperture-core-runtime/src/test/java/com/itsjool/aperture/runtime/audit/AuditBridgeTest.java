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

import static org.assertj.core.api.Assertions.assertThat;
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
    void versionSuffixStripping() {
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

        // Bounded poll-until instead of a fixed sleep + hard verify: on a loaded CI box the
        // async executor may not have drained all four writes within a fixed short sleep.
        awaitAssertion(() -> verify(auditWriter, times(4)).write(any()));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter, times(4)).write(captor.capture());

        var events = captor.getAllValues();

        // V1 suffix should be stripped
        assertThat(events.get(0).entity()).isEqualTo("Product");

        // V3 suffix should be stripped
        assertThat(events.get(1).entity()).isEqualTo("Customer");

        // V10 suffix should be stripped
        assertThat(events.get(2).entity()).isEqualTo("Order");

        // Name without suffix should be unchanged
        assertThat(events.get(3).entity()).isEqualTo("Item");
    }

    @Test
    void shutdownTerminatesExecutor() throws Exception {
        java.lang.reflect.Field executorField = AuditBridge.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        java.util.concurrent.ExecutorService executor = (java.util.concurrent.ExecutorService) executorField.get(auditBridge);

        assertThat(executor.isTerminated()).isFalse();
        auditBridge.shutdown();
        assertThat(executor.isTerminated()).isTrue();
    }

    @Test
    void changeSpecDetailsContainFieldPathBeforeAndAfterValues() throws Exception {
        ChangeSpec changeSpec = new ChangeSpec(null, "status", "draft", "approved");

        auditBridge.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, new Item("audit-1"), null, Optional.of(changeSpec));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter, timeout(500)).write(captor.capture());

        JsonNode details = OBJECT_MAPPER.readTree(captor.getValue().detailsJson());
        assertThat(details.get("fieldPath").asText()).isEqualTo("status");
        assertThat(details.get("before").asText()).isEqualTo("draft");
        assertThat(details.get("after").asText()).isEqualTo("approved");
    }

    @Test
    void changeSpecDetailsAreValidJsonWhenOriginalValueIsNull() throws Exception {
        ChangeSpec changeSpec = new ChangeSpec(null, "status", null, "draft");

        auditBridge.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, new Item("audit-2"), null, Optional.of(changeSpec));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter, timeout(500)).write(captor.capture());

        JsonNode details = OBJECT_MAPPER.readTree(captor.getValue().detailsJson());
        assertThat(details.get("before").isNull()).isTrue();
        assertThat(details.get("after").asText()).isEqualTo("draft");
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
        assertThat(detailsJson)
            .as("LocalDateTime before/after values must not trigger the serialization-failure fallback")
            .doesNotContain("detailsSerializationFailed");

        JsonNode details = OBJECT_MAPPER.readTree(detailsJson);
        assertThat(details.get("fieldPath").asText()).isEqualTo("occurredAt");
        // Compare by parsing back to LocalDateTime rather than string form: LocalDateTime.toString()
        // elides zero seconds ("09:30") while Jackson's ISO serializer emits them ("09:30:00").
        // Both are valid ISO_LOCAL_DATE_TIME, so round-tripping is the format-agnostic check.
        assertThat(LocalDateTime.parse(details.get("before").asText())).isEqualTo(before);
        assertThat(LocalDateTime.parse(details.get("after").asText())).isEqualTo(after);
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
        assertThat(details.get("detailsSerializationFailed").asBoolean()).isTrue();
        assertThat(details.get("fieldPath").asText()).isEqualTo("occurredAt");
    }

    /**
     * Retries {@code assertion} until it stops throwing {@link AssertionError} or a two-second
     * deadline elapses, then rethrows the last failure. Mirrors the bounded poll-until pattern
     * already used by the sibling async audit tests ({@code JdbcAuditWriterTest#waitUntil},
     * {@code WebhookAuditWriterTest#waitForPayload}) instead of a fixed sleep followed by a hard
     * verify, which is flaky under load (the async executor may not have drained yet).
     */
    private void awaitAssertion(Runnable assertion) {
        long deadline = System.currentTimeMillis() + 2_000;
        AssertionError lastFailure;
        do {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                lastFailure = e;
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw lastFailure;
                }
            }
        } while (System.currentTimeMillis() < deadline);
        throw lastFailure;
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
