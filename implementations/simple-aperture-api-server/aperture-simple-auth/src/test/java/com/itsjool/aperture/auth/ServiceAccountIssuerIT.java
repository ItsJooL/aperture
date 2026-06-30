package com.itsjool.aperture.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.itsjool.aperture.spi.ServiceAccountRequest;
import io.jsonwebtoken.Claims;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ServiceAccountIssuerIT {
    private static final Instant NOW = Instant.parse("2026-06-19T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String JWT_SECRET = "test-secret-at-least-thirty-two-bytes-long";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private SimpleServiceAccountIssuer issuer;
    private JwtTokenService jwt;

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
        jdbc.update("DELETE FROM aperture_personal_api_keys");
        jdbc.update("DELETE FROM aperture_service_account_roles");
        jdbc.update("DELETE FROM aperture_service_accounts");
        jdbc.update("DELETE FROM aperture_roles");
        jdbc.update("DELETE FROM aperture_tenants");
        jdbc.update("INSERT INTO aperture_tenants (id, name, status) VALUES "
                + "('tenant-1', 'Tenant 1', 'ACTIVE'), ('tenant-2', 'Tenant 2', 'ACTIVE')");
        jdbc.update("INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES "
                + "('r1', 'tenant-1', 'Auditor'), ('r2', 'tenant-1', 'Viewer'), "
                + "('r3', 'tenant-1', 'Expired'), ('r4', 'tenant-2', 'OtherTenant')");
        var passwords = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        jdbc.update("""
                INSERT INTO aperture_service_accounts
                    (id, client_id, client_secret_hash, tenant_id, status, expires_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?), (?, ?, ?, ?, 'ACTIVE', ?)
                """, "account-1", "client-1", passwords.encode("secret"), "tenant-1",
                Timestamp.from(NOW.plusSeconds(600)),
                "account-2", "client-2", passwords.encode("other"), "tenant-2",
                Timestamp.from(NOW.plusSeconds(600)));
        jdbc.update("""
                INSERT INTO aperture_service_account_roles
                    (tenant_id, service_account_id, role_name, expires_at)
                VALUES ('tenant-1', 'account-1', 'Viewer', NULL),
                       ('tenant-1', 'account-1', 'Auditor', ?),
                       ('tenant-1', 'account-1', 'Expired', ?),
                       ('tenant-2', 'account-2', 'OtherTenant', NULL)
                """, Timestamp.from(NOW.plusSeconds(60)), Timestamp.from(NOW));
        jwt = new JwtTokenService(JWT_SECRET, "aperture", "aperture-api", Duration.ofMinutes(5), CLOCK);
        issuer = new SimpleServiceAccountIssuer(jdbc, jwt, passwords, CLOCK, new java.security.SecureRandom(), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void includedRoleExpiryBoundsJwtFromExactDatabaseAuthority() {
        var result = issuer.issue(new ServiceAccountRequest("client-1", "secret")).orElseThrow();
        Claims claims = jwt.validateToken(result.accessToken());

        assertThat(result.clientId()).isEqualTo("client-1");
        assertThat(claims.getSubject()).isEqualTo("account-1");
        assertThat(claims.get("username")).isEqualTo("client-1");
        assertThat(claims.get("tenantId")).isEqualTo("tenant-1");
        assertThat(claims.get("roles", List.class)).containsExactly("Auditor", "Viewer");
        assertThat(claims.get("profile")).isEqualTo(java.util.Map.of());
        assertThat(claims.get("securityAttributes")).isEqualTo(java.util.Map.of());
        assertThat(claims.get("accountKind")).isEqualTo("SERVICE_ACCOUNT");
        assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void accountExpiryBoundsJwtWhenEarlierThanRoleAndConfiguredDuration() {
        jdbc.update("UPDATE aperture_service_accounts SET expires_at = ? WHERE client_id = 'client-1'",
                Timestamp.from(NOW.plusSeconds(30)));
        jdbc.update("UPDATE aperture_service_account_roles SET expires_at = NULL "
                + "WHERE tenant_id = 'tenant-1' AND service_account_id = 'account-1'");

        var result = issuer.issue(new ServiceAccountRequest("client-1", "secret")).orElseThrow();

        assertThat(jwt.validateToken(result.accessToken()).getExpiration().toInstant())
                .isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void rejectsWrongUnknownDisabledExpiredAndSuspendedTenantCredentials() {
        assertThat(issuer.issue(new ServiceAccountRequest("client-1", "wrong"))).isEmpty();
        assertThat(issuer.issue(new ServiceAccountRequest("missing", "secret"))).isEmpty();

        jdbc.update("UPDATE aperture_service_accounts SET status = 'DISABLED' WHERE client_id = 'client-1'");
        assertThat(issuer.issue(new ServiceAccountRequest("client-1", "secret"))).isEmpty();
        jdbc.update("UPDATE aperture_service_accounts SET status = 'ACTIVE', expires_at = ? "
                + "WHERE client_id = 'client-1'", Timestamp.from(NOW));
        assertThat(issuer.issue(new ServiceAccountRequest("client-1", "secret"))).isEmpty();
        jdbc.update("UPDATE aperture_service_accounts SET expires_at = ? WHERE client_id = 'client-1'",
                Timestamp.from(NOW.plusSeconds(60)));
        jdbc.update("UPDATE aperture_tenants SET status = 'SUSPENDED' WHERE id = 'tenant-1'");
        assertThat(issuer.issue(new ServiceAccountRequest("client-1", "secret"))).isEmpty();
    }

    @Test
    void disableCredentialsPersistsRevokedStatusAndPreventsFutureIssuance() {
        issuer.disableCredentials("client-1");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM aperture_service_accounts WHERE client_id = 'client-1'", String.class))
                .isEqualTo("REVOKED");
        assertThat(issuer.issue(new ServiceAccountRequest("client-1", "secret"))).isEmpty();
    }
}
