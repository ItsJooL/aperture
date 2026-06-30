package com.itsjool.aperture.keycloak;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakCredentialValidatorTest {

    /**
     * Build a KeycloakCredentialValidator with a dummy JWKS URI.
     * We only call extractAttributes(), which doesn't touch the decoder.
     */
    private static KeycloakCredentialValidator validatorUnderTest() {
        return new KeycloakCredentialValidator("https://test.invalid/protocol/openid-connect/certs", "");
    }

    private static KeycloakCredentialValidator validatorUnderTest(String mappings) {
        return new KeycloakCredentialValidator("https://test.invalid/protocol/openid-connect/certs", mappings);
    }

    private static Jwt buildJwt() {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claims(c -> {
                    // structural — must be filtered out
                    c.put("sub", "u1");
                    c.put("iss", "https://keycloak.example.com/realms/test");
                    c.put("aud", "account");
                    c.put("exp", Instant.now().plusSeconds(3600));
                    c.put("iat", Instant.now());
                    c.put("realm_access", Map.of("roles", java.util.List.of("user")));
                    c.put("scope", "openid profile email");
                    c.put("azp", "my-client");
                    c.put("session_state", "abc123");
                    c.put("acr", "1");
                    // business / profile — must be kept
                    c.put("name", "Alice Smith");
                    c.put("given_name", "Alice");
                    c.put("family_name", "Smith");
                    c.put("email", "alice@test.com");
                    c.put("preferred_username", "alice");
                    c.put("region", "eu");
                })
                .build();
    }

    @Test
    void extractAttributes_includesProfileClaims() {
        KeycloakCredentialValidator validator = validatorUnderTest();
        Jwt jwt = buildJwt();

        Map<String, Object> attrs = validator.extractAttributes(jwt);

        assertThat(attrs).containsKey("name");
        assertThat(attrs).containsKey("given_name");
        assertThat(attrs).containsKey("family_name");
        assertThat(attrs).containsKey("email");
        assertThat(attrs).containsKey("preferred_username");
        assertThat(attrs).doesNotContainKey("region");
    }

    @Test
    void extractAttributes_ignoresClaimsOutsideProfileAndSecurityMapping() {
        KeycloakCredentialValidator validator = validatorUnderTest();
        Jwt jwt = buildJwt();

        Map<String, Object> attrs = validator.extractAttributes(jwt);

        assertThat(attrs).doesNotContainKey("sub");
        assertThat(attrs).doesNotContainKey("iss");
        assertThat(attrs).doesNotContainKey("aud");
        assertThat(attrs).doesNotContainKey("exp");
        assertThat(attrs).doesNotContainKey("iat");
        assertThat(attrs).doesNotContainKey("realm_access");
        assertThat(attrs).doesNotContainKey("scope");
        assertThat(attrs).doesNotContainKey("azp");
        assertThat(attrs).doesNotContainKey("session_state");
        assertThat(attrs).doesNotContainKey("acr");
        assertThat(attrs).doesNotContainKey("region");
    }

    @Test
    void extractAttributes_mapsOnlyTrustedSecurityClaims() {
        KeycloakCredentialValidator validator = validatorUnderTest("kc_region:region");
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claims(c -> {
                    c.put("exp", Instant.now().plusSeconds(3600));
                    c.put("iat", Instant.now());
                    c.put("kc_region", "eu");
                    c.put("untrusted_department", "finance");
                })
                .build();

        Map<String, Object> attrs = validator.extractAttributes(jwt);

        assertThat(attrs).containsEntry("region", "eu");
        assertThat(attrs).doesNotContainKey("kc_region");
        assertThat(attrs).doesNotContainKey("untrusted_department");
    }

    @Test
    void extractAttributes_doesNotContainNullValues() {
        KeycloakCredentialValidator validator = validatorUnderTest();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claims(c -> {
                    c.put("exp", Instant.now().plusSeconds(3600));
                    c.put("iat", Instant.now());
                    c.put("email", "bob@test.com");
                    // null value — must be excluded
                    c.put("picture", null);
                })
                .build();

        Map<String, Object> attrs = validator.extractAttributes(jwt);

        assertThat(attrs).containsKey("email");
        assertThat(attrs).doesNotContainKey("picture");
    }
}
