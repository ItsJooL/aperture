package com.itsjool.aperture.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class RefreshTokenServiceIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private RefreshTokenService tokens;

    @BeforeAll
    static void migrate() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection();
             Liquibase liquibase = new Liquibase(
                     "db/changelog/aperture-framework-tables.xml",
                     new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            liquibase.update();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM aperture_refresh_tokens");
        jdbc.update("DELETE FROM aperture_tenant_admins");
        jdbc.update("DELETE FROM aperture_users");
        jdbc.update("DELETE FROM aperture_tenants");
        jdbc.update("INSERT INTO aperture_tenants (id, name, status) VALUES ('tenant-1', 'Tenant 1', 'ACTIVE')");
        jdbc.update("""
                INSERT INTO aperture_users (id, username, tenant_id, status)
                VALUES ('user-1', 'one@example.com', 'tenant-1', 'ACTIVE'),
                       ('user-2', 'two@example.com', 'tenant-1', 'ACTIVE')
                """);
        clock = new MutableClock(Instant.parse("2026-06-19T10:00:00Z"));
        tokens = service(clock);
    }

    @Test
    void issueStoresOnlyLowercaseSha256HashAndReturnsOpaque256BitToken() {
        String raw = tokens.issue("user-1");

        assertThat(raw).matches("[A-Za-z0-9_-]{43}");
        assertThat(jdbc.queryForObject(
                "SELECT token_hash FROM aperture_refresh_tokens", String.class))
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(raw);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM aperture_refresh_tokens WHERE token_hash = ?", Integer.class, raw))
                .isZero();
    }

    @Test
    void replayRevokesTheSuccessfullyCreatedSuccessor() {
        String raw = tokens.issue("user-2");

        RefreshTokenService.Rotation result = tokens.rotate(raw);

        assertThat(result.userId()).isEqualTo("user-2");
        assertThat(result.refreshToken()).isNotEqualTo(raw);
        assertThatThrownBy(() -> tokens.rotate(raw))
                .isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> tokens.rotate(result.refreshToken()))
                .isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM aperture_refresh_tokens WHERE revoked_at IS NOT NULL", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void replayRevocationSurvivesAnOuterTransactionRollback() {
        String first = tokens.issue("user-1");
        String successor = tokens.rotate(first).refreshToken();
        var outer = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        outer.executeWithoutResult(status -> {
            assertThatThrownBy(() -> tokens.rotate(first))
                    .isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class);
            status.setRollbackOnly();
        });

        assertThatThrownBy(() -> tokens.rotate(successor))
                .isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class);
    }

    @Test
    void failedTrustedContinuationLeavesOriginalTokenUsable() {
        String raw = tokens.issue("user-1");

        assertThatThrownBy(() -> tokens.rotate(raw, userId -> {
            throw new IllegalStateException("JWT unavailable");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(tokens.rotate(raw).userId()).isEqualTo("user-1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aperture_refresh_tokens", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void rejectedStoredAccountLeavesOriginalTokenUsable() {
        String raw = tokens.issue("user-1");

        assertThatThrownBy(() -> tokens.rotate(raw, userId -> {
            throw new RefreshTokenService.InvalidRefreshTokenException();
        })).isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class);

        assertThat(tokens.rotate(raw).userId()).isEqualTo("user-1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aperture_refresh_tokens", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void expiredTokenCannotBeRotated() {
        String raw = tokens.issue("user-1");
        clock.advance(Duration.ofDays(31));

        assertThatThrownBy(() -> tokens.rotate(raw))
                .isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class);
    }

    @Test
    void revokingAnyFamilyMemberRevokesPredecessorsAndSuccessors() {
        String first = tokens.issue("user-1");
        String second = tokens.rotate(first).refreshToken();
        String third = tokens.rotate(second).refreshToken();

        tokens.revokeFamily(second);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM aperture_refresh_tokens WHERE revoked_at IS NOT NULL", Integer.class))
                .isEqualTo(3);
        for (String member : List.of(first, second, third)) {
            assertThatThrownBy(() -> tokens.rotate(member))
                    .isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class);
        }
    }

    @Test
    void unknownTokenCannotBeRevoked() {
        assertThatThrownBy(() -> tokens.revokeFamily("not-a-token"))
                .isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class);
    }

    @Test
    void exactlyOneConcurrentRotationSucceeds() throws Exception {
        String raw = tokens.issue("user-1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> rotateAfterBarrier(raw, ready, start));
            Future<Boolean> second = executor.submit(() -> rotateAfterBarrier(raw, ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aperture_refresh_tokens", Integer.class))
                .isEqualTo(2);
    }

    private boolean rotateAfterBarrier(String raw, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            tokens.rotate(raw);
            return true;
        } catch (RefreshTokenService.InvalidRefreshTokenException rejected) {
            return false;
        }
    }

    private RefreshTokenService service(Clock serviceClock) {
        var transactionManager = new DataSourceTransactionManager(dataSource);
        return new RefreshTokenService(jdbc, new TransactionTemplate(transactionManager), serviceClock,
                Duration.ofDays(30), new SecureRandom());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
