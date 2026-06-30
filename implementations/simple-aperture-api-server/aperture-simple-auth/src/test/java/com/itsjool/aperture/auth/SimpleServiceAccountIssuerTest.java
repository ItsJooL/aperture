package com.itsjool.aperture.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itsjool.aperture.spi.ServiceAccountRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class SimpleServiceAccountIssuerTest {
    private static final Instant NOW = Instant.parse("2026-06-19T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private PasswordEncoder passwords;
    private JwtTokenService jwt;
    private SimpleServiceAccountIssuer issuer;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        passwords = mock(PasswordEncoder.class);
        jwt = mock(JwtTokenService.class);
        issuer = new SimpleServiceAccountIssuer(jdbc, jwt, passwords, CLOCK, new java.security.SecureRandom(), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void issuesJwtFromStoredActiveAccountTenantAndUnexpiredDeclaredRoles() {
        when(jdbc.queryForMap(anyString(), eq("client-1"))).thenReturn(Map.of(
                "id", "account-1",
                "client_id", "client-1",
                "client_secret_hash", "argon-hash",
                "tenant_id", "tenant-1",
                "status", "ACTIVE",
                "expires_at", java.sql.Timestamp.from(NOW.plusSeconds(60)),
                "tenant_status", "ACTIVE"));
        when(passwords.matches("secret", "argon-hash")).thenReturn(true);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<SimpleServiceAccountIssuer.RoleAssignment>>any(),
                eq("tenant-1"), eq("account-1"), eq(java.sql.Timestamp.from(NOW))))
                .thenReturn(List.of(
                        new SimpleServiceAccountIssuer.RoleAssignment("Auditor", NOW.plusSeconds(30)),
                        new SimpleServiceAccountIssuer.RoleAssignment("Viewer", null)));
        when(jwt.generateToken(any(), eq(NOW.plusSeconds(30)))).thenReturn("signed-access-token");

        var token = issuer.issue(new ServiceAccountRequest("client-1", "secret")).orElseThrow();

        assertThat(token.clientId()).isEqualTo("client-1");
        assertThat(token.accessToken()).isEqualTo("signed-access-token");
        ArgumentCaptor<AuthenticatedAccount> account = ArgumentCaptor.forClass(AuthenticatedAccount.class);
        verify(jwt).generateToken(account.capture(), eq(NOW.plusSeconds(30)));
        assertThat(account.getValue()).isEqualTo(new AuthenticatedAccount(
                "account-1", "client-1", "tenant-1", List.of("Auditor", "Viewer"), com.itsjool.aperture.spi.PrincipalKind.SERVICE_ACCOUNT, Map.of(), java.util.Map.of(), false, false, false));
    }

    @Test
    void accountExpiryBoundsJwtWhenEarlierThanIncludedRoleExpiry() {
        when(jdbc.queryForMap(anyString(), eq("client-1"))).thenReturn(Map.of(
                "id", "account-1",
                "client_id", "client-1",
                "client_secret_hash", "argon-hash",
                "tenant_id", "tenant-1",
                "status", "ACTIVE",
                "expires_at", java.sql.Timestamp.from(NOW.plusSeconds(20)),
                "tenant_status", "ACTIVE"));
        when(passwords.matches("secret", "argon-hash")).thenReturn(true);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<SimpleServiceAccountIssuer.RoleAssignment>>any(),
                eq("tenant-1"), eq("account-1"), eq(java.sql.Timestamp.from(NOW))))
                .thenReturn(List.of(
                        new SimpleServiceAccountIssuer.RoleAssignment("Viewer", NOW.plusSeconds(30))));
        when(jwt.generateToken(any(), eq(NOW.plusSeconds(20)))).thenReturn("bounded-token");

        assertThat(issuer.issue(new ServiceAccountRequest("client-1", "secret")))
                .hasValueSatisfying(token -> assertThat(token.accessToken()).isEqualTo("bounded-token"));
        verify(jwt).generateToken(any(), eq(NOW.plusSeconds(20)));
    }

    @Test
    void rejectsWrongSecretAndIneligibleAccountAfterOneHashVerification() {
        when(jdbc.queryForMap(anyString(), eq("client-1"))).thenReturn(Map.of(
                "id", "account-1", "client_id", "client-1", "client_secret_hash", "argon-hash",
                "tenant_id", "tenant-1", "status", "REVOKED",
                "expires_at", java.sql.Timestamp.from(NOW.plusSeconds(60)), "tenant_status", "ACTIVE"));
        when(passwords.matches("wrong", "argon-hash")).thenReturn(false);

        assertThat(issuer.issue(new ServiceAccountRequest("client-1", "wrong"))).isEmpty();
        verify(passwords).matches("wrong", "argon-hash");
    }

    @Test
    void unknownClientUsesDummyArgonHashAndRejects() {
        when(jdbc.queryForMap(anyString(), eq("missing"))).thenThrow(new EmptyResultDataAccessException(1));
        when(passwords.matches(eq("secret"), anyString())).thenReturn(false);

        assertThat(issuer.issue(new ServiceAccountRequest("missing", "secret"))).isEmpty();
        verify(passwords).matches(eq("secret"), anyString());
    }

    @Test
    void accountWhoseTenantIsMissingStillVerifiesStoredHashAndRejects() {
        Map<String, Object> account = new HashMap<>();
        account.put("id", "account-1");
        account.put("client_id", "client-1");
        account.put("client_secret_hash", "argon-hash");
        account.put("tenant_id", "missing-tenant");
        account.put("status", "ACTIVE");
        account.put("expires_at", java.sql.Timestamp.from(NOW.plusSeconds(60)));
        account.put("tenant_status", null);
        when(jdbc.queryForMap(anyString(), eq("client-1"))).thenReturn(account);
        when(passwords.matches("secret", "argon-hash")).thenReturn(true);

        assertThat(issuer.issue(new ServiceAccountRequest("client-1", "secret"))).isEmpty();
        verify(passwords).matches("secret", "argon-hash");
    }

    @Test
    void databaseFailuresPropagate() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(jdbc.queryForMap(anyString(), eq("client-1"))).thenThrow(failure);

        assertThatThrownBy(() -> issuer.issue(new ServiceAccountRequest("client-1", "secret")))
                .isSameAs(failure);
    }

    @Test
    void disableCredentialsTransitionsMatchingAccountAndBlocksFutureIssuance() {
        when(jdbc.update(anyString(), eq("client-1"))).thenReturn(1);
        issuer.disableCredentials("client-1");
        verify(jdbc).update(anyString(), eq("client-1"));
    }
}
