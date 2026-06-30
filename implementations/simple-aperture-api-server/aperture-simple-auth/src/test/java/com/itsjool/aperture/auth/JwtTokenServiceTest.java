package com.itsjool.aperture.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {
    private static final String SECRET = "test-secret-that-is-at-least-thirty-two-bytes-long";
    private static final Instant NOW = Instant.parse("2026-06-19T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void issuesCompleteUserClaimsFromAuthenticatedAccount() {
        JwtTokenService service = service(SECRET, "aperture", "aperture-api", Duration.ofMinutes(15), CLOCK);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("department", "finance");
        attributes.put("quota", 7);
        attributes.put("nullable", null);
        AuthenticatedAccount account = new AuthenticatedAccount(
                "user-1", "viewer@example.com", "tenant-1", List.of("Viewer", "Auditor"), com.itsjool.aperture.spi.PrincipalKind.USER, attributes, java.util.Map.of(), false, false, false);

        Claims claims = service.validateToken(service.generateToken(account));

        assertThat(claims.getSubject()).isEqualTo("user-1");
        assertThat(claims.get("username", String.class)).isEqualTo("viewer@example.com");
        assertThat(claims.getIssuer()).isEqualTo("aperture");
        assertThat(claims.getAudience()).containsExactly("aperture-api");
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.get("tenantId", String.class)).isEqualTo("tenant-1");
        assertThat(claims.get("roles", List.class)).containsExactly("Viewer", "Auditor");
        assertThat(claims.get("profile", Map.class)).containsExactlyEntriesOf(attributes);
        assertThat(claims.get("accountKind", String.class)).isEqualTo("USER");
    }

    @Test
    void issuesUniqueIdsAndNullableTenantForServiceAccount() {
        JwtTokenService service = service(SECRET, "aperture", "aperture-api", Duration.ofMinutes(15), CLOCK);
        AuthenticatedAccount account = new AuthenticatedAccount(
                "client-1", "client-1", null, List.of("SuperAdmin"), com.itsjool.aperture.spi.PrincipalKind.SERVICE_ACCOUNT, Map.of(), java.util.Map.of(), false, false, false);

        Claims first = service.validateToken(service.generateToken(account));
        Claims second = service.validateToken(service.generateToken(account));

        assertThat(first.getId()).isNotBlank().isNotEqualTo(second.getId());
        assertThat(first.get("tenantId")).isNull();
        assertThat(first.get("accountKind", String.class)).isEqualTo("SERVICE_ACCOUNT");
    }

    @Test
    void capsExpirationAtAbsoluteNotAfterBoundary() {
        JwtTokenService service = service(SECRET, "aperture", "aperture-api", Duration.ofMinutes(15), CLOCK);
        AuthenticatedAccount account = new AuthenticatedAccount(
                "client-1", "client-1", "tenant-1", List.of("Viewer"), com.itsjool.aperture.spi.PrincipalKind.SERVICE_ACCOUNT, Map.of(), java.util.Map.of(), false, false, false);

        Claims bounded = service.validateToken(service.generateToken(account, NOW.plusSeconds(45)));
        Claims configured = service.validateToken(service.generateToken(account, NOW.plus(Duration.ofHours(1))));

        assertThat(bounded.getExpiration().toInstant()).isEqualTo(NOW.plusSeconds(45));
        assertThat(configured.getExpiration().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void rejectsNotAfterAtOrBeforeCurrentClock() {
        JwtTokenService service = service(SECRET, "aperture", "aperture-api", Duration.ofMinutes(15), CLOCK);
        AuthenticatedAccount account = new AuthenticatedAccount(
                "client-1", "client-1", "tenant-1", List.of(), com.itsjool.aperture.spi.PrincipalKind.SERVICE_ACCOUNT, Map.of(), java.util.Map.of(), false, false, false);

        assertThatThrownBy(() -> service.generateToken(account, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.generateToken(account, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongSignatureIssuerAudienceAndExpiry() {
        AuthenticatedAccount account = new AuthenticatedAccount(
                "user-1", "user@example.com", null, List.of("Viewer"), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), java.util.Map.of(), false, false, false);
        JwtTokenService valid = service(SECRET, "aperture", "aperture-api", Duration.ofMinutes(15), CLOCK);
        String token = valid.generateToken(account);

        assertThatThrownBy(() -> service("another-test-secret-that-is-at-least-thirty-two-bytes", "aperture", "aperture-api", Duration.ofMinutes(15), CLOCK).validateToken(token))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service(SECRET, "other", "aperture-api", Duration.ofMinutes(15), CLOCK).validateToken(token))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service(SECRET, "aperture", "other-api", Duration.ofMinutes(15), CLOCK).validateToken(token))
                .isInstanceOf(JwtException.class);
        Clock expiredClock = Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
        assertThatThrownBy(() -> service(SECRET, "aperture", "aperture-api", Duration.ofMinutes(15), expiredClock).validateToken(token))
                .isInstanceOf(JwtException.class);
    }

    private static JwtTokenService service(String secret, String issuer, String audience,
            Duration duration, Clock clock) {
        return new JwtTokenService(secret, issuer, audience, duration, clock);
    }
}
