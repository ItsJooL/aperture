package com.itsjool.aperture.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

/**
 * Focused negative-path tests for {@link ApiKeyService}. The positive-path integration tests
 * live in {@link ApiKeyServiceIT}; this file specifically targets the fail-closed behaviours
 * added during the auth-model-repair work.
 */
@Testcontainers
class ApiKeyServiceTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private ApiKeyService keys;

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-29T09:00:00Z"), ZoneOffset.UTC);

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
        jdbc.update("INSERT INTO aperture_tenants (id, name, status) VALUES ('tenant-1', 'Tenant One', 'ACTIVE')");
        jdbc.update("INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES "
                + "('role-v', 'tenant-1', 'Viewer'), ('role-a', 'tenant-1', 'Auditor')");
        jdbc.update("""
                INSERT INTO aperture_users
                    (id, username, password_hash, tenant_id, status, super_admin, force_password_change)
                VALUES ('user-1', 'alice@example.com', 'unused', 'tenant-1', 'ACTIVE', false, false)
                """);
        jdbc.update("INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) "
                + "VALUES ('tenant-1', 'user-1', 'Viewer')");

        var manager = new DataSourceTransactionManager(dataSource);
        keys = new ApiKeyService(jdbc, new TransactionTemplate(manager), FIXED_CLOCK, new SecureRandom(),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /**
     * Test 1: fail-closed on roles — if a delegated role is revoked from the user after the
     * key was issued, subsequent calls to {@code authenticate()} must return empty.
     */
    @Test
    void delegatedRoleRevokedAfterIssuanceCausesAuthenticationToReturnEmpty() {
        ApiKeyService.IssuedApiKey issued =
                keys.issueWithPrincipal("tenant-1", "user-1", "my-key", null, List.of("Viewer"), null);

        // Verify it works before the role is revoked
        assertThat(keys.authenticate(issued.rawKey())).isPresent();

        // Revoke the "Viewer" role from the user
        jdbc.update("DELETE FROM aperture_user_roles WHERE user_id = 'user-1' AND role_name = 'Viewer'");

        // Fail closed: the key must no longer authenticate
        assertThat(keys.authenticate(issued.rawKey())).isEmpty();
    }

    /**
     * Test 2: fail-closed on attributes — if the user's security attribute value changes after
     * the key was issued, subsequent calls to {@code authenticate()} must return empty.
     */
    @Test
    void delegatedSecurityAttributeChangedAfterIssuanceCausesAuthenticationToReturnEmpty() {
        jdbc.update("""
                UPDATE aperture_users
                   SET security_attributes = '{"department":"finance"}'::jsonb
                 WHERE id = 'user-1'
                """);

        ApiKeyService.IssuedApiKey issued = keys.issueWithPrincipal(
                "tenant-1", "user-1", "finance-key", null,
                List.of("Viewer"), Map.of("department", "finance"));

        // Verify it works before the attribute changes
        assertThat(keys.authenticate(issued.rawKey())).isPresent();

        // Change the user's department — attribute value no longer matches what the key captured
        jdbc.update("""
                UPDATE aperture_users
                   SET security_attributes = '{"department":"hr"}'::jsonb
                 WHERE id = 'user-1'
                """);

        // Fail closed: the key must no longer authenticate
        assertThat(keys.authenticate(issued.rawKey())).isEmpty();
    }

    /**
     * Test 3: personal API keys never carry platform authorities — even when the owning user
     * is listed in {@code aperture_tenant_admins}, the resulting {@link AuthenticatedAccount}
     * must have {@code isTenantAdmin == false}.
     */
    @Test
    void personalApiKeyForTenantAdminUserHasIsTenantAdminFalse() {
        // Register the user as a tenant admin in the platform table
        jdbc.update("INSERT INTO aperture_tenant_admins (tenant_id, user_id, created_at) VALUES ('tenant-1', 'user-1', NOW())");

        ApiKeyService.IssuedApiKey issued =
                keys.issueWithPrincipal("tenant-1", "user-1", "admin-key", null, List.of("Viewer"), null);

        AuthenticatedAccount account = keys.authenticate(issued.rawKey()).orElseThrow();
        assertThat(account.isTenantAdmin()).isFalse();
        assertThat(account.isSuperAdmin()).isFalse();
    }

    /**
     * Test 4: {@code disable()} must write a non-null {@code revoked_at} timestamp to the DB.
     */
    @Test
    void disableSetsRevokedAtInDb() {
        ApiKeyService.IssuedApiKey issued =
                keys.issueWithPrincipal("tenant-1", "user-1", "revoke-me", null, List.of(), null);

        // Confirm revoked_at is null before disabling
        assertThat(jdbc.queryForObject(
                "SELECT revoked_at FROM aperture_personal_api_keys WHERE id = ?",
                Timestamp.class, issued.id())).isNull();

        boolean disabled = keys.disable(issued.id());

        assertThat(disabled).isTrue();
        Timestamp revokedAt = jdbc.queryForObject(
                "SELECT revoked_at FROM aperture_personal_api_keys WHERE id = ?",
                Timestamp.class, issued.id());
        assertThat(revokedAt).isNotNull();
        assertThat(revokedAt.toInstant()).isEqualTo(FIXED_CLOCK.instant());
    }

    /**
     * Test 5: {@code issueWithPrincipal()} must throw {@link IllegalArgumentException} when
     * the requested role is not currently held by the user — privilege escalation via API key
     * issuance must be impossible.
     */
    @Test
    void issuingKeyWithRoleNotHeldByUserThrowsIllegalArgumentException() {
        // user-1 holds only "Viewer"; requesting "Auditor" must be rejected
        assertThatThrownBy(() ->
                keys.issueWithPrincipal("tenant-1", "user-1", "escalated-key", null,
                        List.of("Auditor"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Delegated roles");
    }
}
