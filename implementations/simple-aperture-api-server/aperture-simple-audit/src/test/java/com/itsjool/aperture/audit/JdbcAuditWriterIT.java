package com.itsjool.aperture.audit;

import com.itsjool.aperture.spi.AuditEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres regression coverage for the {@code aperture_audit_log.timestamp} column's
 * {@code TIMESTAMP} → {@code TIMESTAMPTZ} migration (plan 033 / finding 1I). Runs against a real
 * database via Testcontainers, applying the actual {@code aperture-framework-tables.xml}
 * changelog, because the bug class this guards against — a timezone-naive column silently
 * misreading when write and read sessions disagree on {@code TimeZone} — cannot be demonstrated
 * against a mocked {@code JdbcTemplate} (see {@link JdbcAuditWriterTest} for the mocked
 * observability-surface unit tests).
 */
@Testcontainers
class JdbcAuditWriterIT {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;

    private JdbcTemplate jdbcTemplate;
    private JdbcAuditWriter writer;

    @BeforeAll
    static void migrate() throws Exception {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection();
             Liquibase liquibase = new Liquibase("db/changelog/aperture-framework-tables.xml",
                 new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            liquibase.update();
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("DELETE FROM aperture_audit_log");
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown();
        }
    }

    @Test
    void occurredAtRoundTripsThroughTimestamptzColumnRegardlessOfReadingSessionTimezone() throws Exception {
        writer = new JdbcAuditWriter(jdbcTemplate, new SimpleMeterRegistry(), TestObservationRegistry.create());

        // Fixed mid-January instant: America/New_York is plain EST (UTC-5) with no DST ambiguity.
        Instant occurredAt = Instant.parse("2026-01-15T12:00:00Z");
        writer.write(new AuditEvent("user-1", "tenant-1", "Invoice", "invoice-1", "UPDATE",
            "{\"fieldPath\":\"status\",\"before\":\"open\",\"after\":\"closed\"}", occurredAt));

        waitUntil(() -> assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM aperture_audit_log WHERE entity_id = 'invoice-1'", Integer.class))
            .isEqualTo(1));

        // Read back over a SEPARATE connection whose session TimeZone is deliberately NOT UTC.
        // TIMESTAMPTZ stores an absolute instant internally (server-side, regardless of session
        // TimeZone), so the round-tripped instant must be exact no matter what timezone the reading
        // session runs under — that is the whole point of the TIMESTAMP -> TIMESTAMPTZ fix.
        try (Connection nonUtcConnection = dataSource.getConnection();
             Statement statement = nonUtcConnection.createStatement()) {
            statement.execute("SET TIME ZONE 'America/New_York'");
            try (ResultSet rs = statement.executeQuery(
                "SELECT \"timestamp\" FROM aperture_audit_log WHERE entity_id = 'invoice-1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp(1).toInstant()).isEqualTo(occurredAt);
            }
        }
    }

    /**
     * The "horror story" this migration guards against, reproduced directly: a timezone-naive
     * {@code TIMESTAMP} column has no timezone of its own, so assigning it a {@code TIMESTAMPTZ}
     * value (e.g. {@code NOW()}, exactly what the pre-migration {@code JdbcAuditWriter} used)
     * implicitly converts through whichever session's {@code TimeZone} GUC is active at write time.
     * If a later read (or, as here, the same {@code AT TIME ZONE 'UTC'} expression this migration's
     * changeset uses) assumes a different origin timezone than the one that actually wrote the row,
     * the reconstructed instant is silently wrong by exactly the mismatched offset — with no error,
     * no warning, just an incorrect value. This is precisely why the migration changeset's own
     * comment requires confirming the writing session actually was UTC before trusting its
     * {@code AT TIME ZONE 'UTC'} cast as a no-op on existing data (verified separately for this
     * project's actual Postgres containers, which default to UTC).
     */
    @Test
    void naiveTimestampColumnWouldHaveMisreadUnderAMismatchedSessionTimezone() throws Exception {
        jdbcTemplate.execute("CREATE TABLE naive_timestamp_probe (id INT PRIMARY KEY, occurred_at TIMESTAMP)");
        try {
            Instant occurredAt = Instant.parse("2026-01-15T12:00:00Z");

            // Write exactly like the pre-migration JdbcAuditWriter did: a server-side NOW()-style
            // value assigned into a naive column, from a session whose TimeZone is NOT UTC.
            try (Connection writingConnection = dataSource.getConnection();
                 Statement statement = writingConnection.createStatement()) {
                statement.execute("SET TIME ZONE 'America/New_York'");
                statement.execute(
                    "INSERT INTO naive_timestamp_probe (id, occurred_at) VALUES "
                        + "(1, TIMESTAMPTZ '2026-01-15T12:00:00Z')");
            }

            // Read back applying this migration's own AT TIME ZONE 'UTC' cast, from a UTC session —
            // the exact expression the Liquibase changeset uses to interpret existing naive rows.
            try (Connection readingConnection = dataSource.getConnection();
                 Statement statement = readingConnection.createStatement()) {
                statement.execute("SET TIME ZONE 'UTC'");
                try (ResultSet rs = statement.executeQuery(
                    "SELECT occurred_at AT TIME ZONE 'UTC' FROM naive_timestamp_probe WHERE id = 1")) {
                    assertThat(rs.next()).isTrue();
                    Instant misread = rs.getTimestamp(1).toInstant();

                    assertThat(misread)
                        .as("a naive TIMESTAMP column silently misreads once write and interpretation "
                            + "sessions disagree on timezone — exactly the bug TIMESTAMPTZ fixes")
                        .isNotEqualTo(occurredAt);
                    assertThat(Duration.between(occurredAt, misread).abs()).isEqualTo(Duration.ofHours(5));
                }
            }
        } finally {
            jdbcTemplate.execute("DROP TABLE naive_timestamp_probe");
        }
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
