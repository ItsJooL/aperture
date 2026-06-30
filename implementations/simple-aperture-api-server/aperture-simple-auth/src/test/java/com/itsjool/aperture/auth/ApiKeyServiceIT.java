package com.itsjool.aperture.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ApiKeyServiceIT {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private ApiKeyService keys;

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
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM aperture_personal_api_keys");
        jdbc.update("DELETE FROM aperture_user_roles");
        jdbc.update("DELETE FROM aperture_tenant_admins");
        jdbc.update("DELETE FROM aperture_users");
        jdbc.update("DELETE FROM aperture_roles");
        jdbc.update("DELETE FROM aperture_tenants");
        jdbc.update("INSERT INTO aperture_tenants (id, name, status) VALUES "
                + "('tenant-1', 'Tenant 1', 'ACTIVE'), ('tenant-2', 'Tenant 2', 'ACTIVE')");
        jdbc.update("INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES "
                + "('role-1', 'tenant-1', 'Viewer'), ('role-2', 'tenant-1', 'Auditor'), "
                + "('role-3', 'tenant-2', 'Viewer')");
        jdbc.update("""
                INSERT INTO aperture_users
                    (id, username, password_hash, tenant_id, status, super_admin, force_password_change)
                VALUES ('user-1', 'user1@tenant1.com', 'unused', 'tenant-1', 'ACTIVE', false, false),
                       ('user-2', 'user2@tenant2.com', 'unused', 'tenant-2', 'ACTIVE', false, false)
                """);
        jdbc.update("INSERT INTO aperture_user_roles "
                + "(tenant_id, user_id, role_name) "
                + "VALUES ('tenant-1', 'user-1', 'Viewer')");
        clock = new MutableClock(Instant.parse("2026-06-19T10:00:00Z"));
        keys = service();
    }

    @Test
    void issueStoresOnlyHashAndAuthenticationReturnsCurrentAccount() {
        ApiKeyService.IssuedApiKey issued = keys.issueWithPrincipal("tenant-1", "user-1", "test-key", null, List.of("Viewer"), null);

        assertThat(issued.rawKey()).matches("[A-Za-z0-9_-]{43}");
        assertThat(issued.id()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT key_hash FROM aperture_personal_api_keys WHERE id = ?",
                String.class, issued.id())).matches("[0-9a-f]{64}").isNotEqualTo(issued.rawKey());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aperture_personal_api_keys WHERE key_hash = ?",
                Integer.class, issued.rawKey())).isZero();

        AuthenticatedAccount account = keys.authenticate(issued.rawKey()).orElseThrow();
        assertThat(account).isEqualTo(new AuthenticatedAccount("user-1", "user1@tenant1.com", "tenant-1",
                List.of("Viewer"), com.itsjool.aperture.spi.PrincipalKind.PERSONAL_API_KEY, java.util.Map.of(), java.util.Map.of(), false, false, false));
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM aperture_personal_api_keys WHERE id = ?",
                Timestamp.class, issued.id()).toInstant()).isEqualTo(clock.instant());
    }

    @Test
    void reloadsOnlyCurrentUnexpiredRolesOnEveryAuthentication() {
        ApiKeyService.IssuedApiKey issued = keys.issueWithPrincipal("tenant-1", "user-1", "test-key", null, List.of("Viewer"), null);
        assertThat(keys.authenticate(issued.rawKey()).orElseThrow().roles()).containsExactly("Viewer");

        // Change user role from Viewer to Auditor
        jdbc.update("DELETE FROM aperture_user_roles WHERE role_name = 'Viewer'");
        jdbc.update("INSERT INTO aperture_user_roles "
                + "(tenant_id, user_id, role_name) VALUES "
                + "('tenant-1', 'user-1', 'Auditor')");

        // Fail closed: since API key requested Viewer but user no longer has it, authentication returns empty
        assertThat(keys.authenticate(issued.rawKey())).isEmpty();
    }

    @Test
    void expiryAtCurrentInstantIsInvalidForKeyAccountAndTenantMustRemainActive() {
        ApiKeyService.IssuedApiKey issued = keys.issueWithPrincipal(
                "tenant-1", "user-1", "test-key", clock.instant().plusSeconds(1), List.of("Viewer"), null);
        clock.advance(Duration.ofSeconds(1));
        assertThat(keys.authenticate(issued.rawKey())).isEmpty();

        ApiKeyService.IssuedApiKey accountKey = keys.issueWithPrincipal(
                "tenant-2", "user-2", "test-key", clock.instant().plusSeconds(60), null, null);

        jdbc.update("UPDATE aperture_users SET status = 'DISABLED' WHERE id = 'user-2'");
        assertThat(keys.authenticate(accountKey.rawKey())).isEmpty();

        jdbc.update("UPDATE aperture_users SET status = 'ACTIVE' WHERE id = 'user-2'");
        jdbc.update("UPDATE aperture_tenants SET status = 'DISABLED' WHERE id = 'tenant-2'");
        assertThat(keys.authenticate(accountKey.rawKey())).isEmpty();
    }

    @Test
    void disabledKeyCannotAuthenticateAndFailedAttemptsDoNotUpdateLastUsed() {
        ApiKeyService.IssuedApiKey issued = keys.issueWithPrincipal("tenant-1", "user-1", "test-key", null, List.of(), null);
        assertThat(keys.authenticate("not-a-generated-key")).isEmpty();
        assertThat(keys.authenticate(null)).isEmpty();
        assertThat(keys.authenticate(" ")).isEmpty();

        keys.disable(issued.id());
        assertThat(keys.authenticate(issued.rawKey())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM aperture_personal_api_keys WHERE id = ?",
                String.class, issued.id())).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM aperture_personal_api_keys WHERE id = ?",
                Timestamp.class, issued.id())).isNull();
    }

    @Test
    void disableCommittedBeforeAuthenticationLockAcquisitionWins() throws Exception {
        ApiKeyService.IssuedApiKey issued = keys.issueWithPrincipal("tenant-1", "user-1", "test-key", null, List.of(), null);
        CountDownLatch authenticationStarted = new CountDownLatch(1);

        try (Connection disablingConnection = dataSource.getConnection();
             var executor = Executors.newSingleThreadExecutor()) {
            disablingConnection.setAutoCommit(false);
            try (var statement = disablingConnection.prepareStatement(
                    "UPDATE aperture_personal_api_keys SET status = 'DISABLED' WHERE id = ?")) {
                statement.setString(1, issued.id());
                assertThat(statement.executeUpdate()).isOne();
            }

            Future<Optional<AuthenticatedAccount>> authentication = executor.submit(() -> {
                authenticationStarted.countDown();
                return keys.authenticate(issued.rawKey());
            });
            assertThat(authenticationStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> authentication.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            disablingConnection.commit();
            assertThat(authentication.get(5, TimeUnit.SECONDS)).isEmpty();
        }

        assertThat(jdbc.queryForObject("SELECT last_used_at FROM aperture_personal_api_keys WHERE id = ?",
                Timestamp.class, issued.id())).isNull();
    }

    @Test
    void issuanceRejectsIneligibleOrMismatchedAccountsAndInvalidExpiry() {
        assertThatThrownBy(() -> keys.issueWithPrincipal("tenant-1", "user-2", "test-key", null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keys.issueWithPrincipal("tenant-1", "user-1", "test-key", clock.instant(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);

        jdbc.update("UPDATE aperture_users SET status = 'DISABLED' WHERE id = 'user-1'");
        assertThatThrownBy(() -> keys.issueWithPrincipal("tenant-1", "user-1", "test-key", null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aperture_personal_api_keys", Integer.class)).isZero();
    }

    @Test
    void compositeForeignKeyPreventsCrossTenantKeyRows() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO aperture_personal_api_keys
                    (id, key_hash, tenant_id, user_id, name, status, created_at, domain_roles, security_attributes)
                VALUES ('bad', ?, 'tenant-1', 'user-2', 'Name', 'ACTIVE', ?, '{}'::jsonb, '{}'::jsonb)
                """, "0".repeat(64), Timestamp.from(clock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void issuanceStoresNameAndRejectsDelegationOutsideCurrentUserGrants() {
        jdbc.update("""
                UPDATE aperture_users
                SET security_attributes = '{"department":"finance","region":"eu"}'::jsonb
                WHERE tenant_id = 'tenant-1' AND id = 'user-1'
                """);

        ApiKeyService.IssuedApiKey issued = keys.issueWithPrincipal(
                "tenant-1", "user-1", "Finance exports", null,
                List.of("Viewer"), java.util.Map.of("department", "finance"));

        assertThat(jdbc.queryForObject(
                "SELECT name FROM aperture_personal_api_keys WHERE id = ?",
                String.class, issued.id())).isEqualTo("Finance exports");
        AuthenticatedAccount account = keys.authenticate(issued.rawKey()).orElseThrow();
        assertThat(account.roles()).containsExactly("Viewer");
        assertThat(account.securityAttributes()).containsEntry("department", "finance")
                .doesNotContainKey("region");

        assertThatThrownBy(() -> keys.issueWithPrincipal(
                "tenant-1", "user-1", "Escalated role", null,
                List.of("Auditor"), java.util.Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Delegated roles");
        assertThatThrownBy(() -> keys.issueWithPrincipal(
                "tenant-1", "user-1", "Escalated attribute", null,
                List.of(), java.util.Map.of("department", "sales")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Delegated security attributes");
    }

    private ApiKeyService service() {
        var manager = new DataSourceTransactionManager(dataSource);
        return new ApiKeyService(jdbc, new TransactionTemplate(manager), clock, new SecureRandom(),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
