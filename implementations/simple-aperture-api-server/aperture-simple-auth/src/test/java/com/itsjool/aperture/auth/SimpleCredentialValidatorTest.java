package com.itsjool.aperture.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class SimpleCredentialValidatorTest {
    private static final String SECRET = "test-secret-that-is-at-least-thirty-two-bytes-long";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-19T10:15:30Z"), ZoneOffset.UTC);
    private final JwtTokenService tokens = new JwtTokenService(
            SECRET, "aperture", "aperture-api", Duration.ofMinutes(15), CLOCK);
    private final ApiKeyService apiKeys = mock(ApiKeyService.class);
    private final SimpleCredentialValidator validator = new SimpleCredentialValidator(tokens, apiKeys);

    @Test
    void createsTypedImmutableResultFromValidatedBearerClaims() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("nullable", null);
        attributes.put("region", "ie");
        AuthenticatedAccount account = new AuthenticatedAccount(
                "user-1", "user@example.com", "tenant-1", List.of("Viewer"), com.itsjool.aperture.spi.PrincipalKind.USER, attributes, java.util.Map.of(), false, false, false);

        var result = validator.validate(request("Bearer " + tokens.generateToken(account), null));

        assertThat(result).isInstanceOf(SimpleCredentialValidator.TrustedAccountValidationResult.class);
        var jwt = (SimpleCredentialValidator.TrustedAccountValidationResult) result;
        assertThat(jwt.isValid()).isTrue();
        assertThat(jwt.subject()).isEqualTo("user-1");
        assertThat(jwt.tenantId()).isEqualTo("tenant-1");
        assertThat(jwt.roles()).containsExactly("Viewer").isUnmodifiable();
        assertThat(jwt.profile()).containsExactlyEntriesOf(attributes).isUnmodifiable();
        assertThat(jwt.kind() == com.itsjool.aperture.spi.PrincipalKind.SERVICE_ACCOUNT).isFalse();
    }

    @Test
    void onlyAcceptsNonblankBearerToken() {
        assertThat(validator.validate(request(null, null)).isValid()).isFalse();
        assertThat(validator.validate(request("Basic abc", null)).isValid()).isFalse();
        assertThat(validator.validate(request("bearer abc", null)).isValid()).isFalse();
        assertThat(validator.validate(request("Bearer ", null)).isValid()).isFalse();
    }

    @Test
    void hidesInternalParserFailureMessages() {
        var result = validator.validate(request("Bearer not-a-jwt", null));

        assertThat(result.isValid()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("Invalid bearer token");
        assertThat(result.errorMessage()).doesNotContain("JWT", "period", "compact");
    }

    @Test
    void rejectsMalformedTypedClaims() {
        String token = Jwts.builder()
                .setSubject("user-1")
                .setIssuer("aperture")
                .setAudience("aperture-api")
                .setIssuedAt(Date.from(CLOCK.instant()))
                .setExpiration(Date.from(CLOCK.instant().plus(Duration.ofMinutes(15))))
                .claim("roles", List.of(7))
                .claim("attributes", Map.of())
                .claim("accountKind", "user")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        var result = validator.validate(request("Bearer " + token, null));

        assertThat(result.isValid()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("Invalid bearer token");
    }

    @Test
    void authenticatesCanonicalApiKeyAsExactTrustedAccount() {
        AuthenticatedAccount account = new AuthenticatedAccount(
                "account-1", "client-1", "tenant-1", List.of("Auditor"), com.itsjool.aperture.spi.PrincipalKind.SERVICE_ACCOUNT, Map.of(), java.util.Map.of(), false, false, false);
        when(apiKeys.authenticate("opaque-key")).thenReturn(Optional.of(account));

        var result = validator.validate(request(null, "opaque-key"));

        assertThat(result).isEqualTo(new SimpleCredentialValidator.TrustedAccountValidationResult(
                "account-1", "tenant-1", List.of("Auditor"), Map.of(), java.util.Map.of(), com.itsjool.aperture.spi.PrincipalKind.SERVICE_ACCOUNT, false, java.util.Set.of(), false));
    }

    @Test
    void rejectsAmbiguousBearerAndApiKeyWithoutAuthenticatingEither() {
        var result = validator.validate(request("Bearer token", "opaque-key"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("Ambiguous credentials");
        verifyNoInteractions(apiKeys);
    }

    @Test
    void returnsGenericFailureForUnknownApiKeyButPropagatesDatabaseErrors() {
        when(apiKeys.authenticate("unknown-key")).thenReturn(Optional.empty());
        assertThat(validator.validate(request(null, "unknown-key")).errorMessage())
                .isEqualTo("Invalid API key");

        when(apiKeys.authenticate("database-key"))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(() -> validator.validate(request(null, "database-key")))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private static HttpServletRequest request(String authorization, String apiKey) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(authorization);
        when(request.getHeader("X-API-Key")).thenReturn(apiKey);
        return request;
    }
}
