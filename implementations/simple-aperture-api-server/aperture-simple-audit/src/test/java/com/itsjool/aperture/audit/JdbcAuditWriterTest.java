package com.itsjool.aperture.audit;

import com.itsjool.aperture.spi.AuditEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the observability surface of {@link JdbcAuditWriter}: an "aperture.audit.write"
 * observation per flushed event, a queue-size gauge, and a dropped-event counter — all registered
 * under the exact names the writer's source uses (see JdbcAuditWriter#write / #processQueue).
 */
class JdbcAuditWriterTest {

    private JdbcAuditWriter writer;

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown();
        }
    }

    @Test
    void recordsAuditWriteObservationWithOkOutcomeOnSuccessfulFlush() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TestObservationRegistry observationRegistry = TestObservationRegistry.create();
        writer = new JdbcAuditWriter(jdbcTemplate, meterRegistry, observationRegistry);

        writer.write(new AuditEvent("user-1", "tenant-1", "Invoice", "invoice-1", "UPDATE",
            "{\"fieldPath\":\"status\",\"before\":\"open\",\"after\":\"closed\"}"));

        waitUntil(() -> TestObservationRegistryAssert.assertThat(observationRegistry)
            .hasSingleObservationThat()
            .hasNameEqualTo("aperture.audit.write")
            .hasBeenStarted()
            .hasBeenStopped()
            .hasLowCardinalityKeyValue("entity", "Invoice")
            .hasLowCardinalityKeyValue("operation", "UPDATE")
            .hasLowCardinalityKeyValue("outcome", "ok"));
    }

    @Test
    void registersQueueSizeGaugeAndDroppedCounterUnderExactNames() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        writer = new JdbcAuditWriter(jdbcTemplate, meterRegistry, TestObservationRegistry.create());

        assertThat(meterRegistry.find("aperture.audit.queue.size").gauge())
            .as("queue-size gauge should be registered on construction")
            .isNotNull();
        assertThat(meterRegistry.find("aperture.audit.dropped").counter())
            .as("dropped-event counter should be registered on construction")
            .isNotNull();
        assertThat(meterRegistry.get("aperture.audit.dropped").counter().count()).isEqualTo(0.0);
    }

    private void waitUntil(Runnable assertion) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        AssertionError lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                lastFailure = e;
                Thread.sleep(25);
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
    }
}
