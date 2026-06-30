package com.itsjool.aperture.keycloak;

import com.itsjool.aperture.spi.CredentialValidator;
import com.itsjool.aperture.spi.ValidationResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class KeycloakCredentialValidator implements CredentialValidator {

    private static final Set<String> PROFILE_CLAIMS = Set.of(
            "name", "given_name", "family_name", "email", "preferred_username", "picture");

    private final NimbusJwtDecoder jwtDecoder;
    private final Map<String, String> securityClaimMappings;

    public KeycloakCredentialValidator(@Value("${keycloak.jwks-uri}") String jwksUri,
            @Value("${keycloak.security-claim-mappings:}") String securityClaimMappings) {
        this.jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        this.securityClaimMappings = parseMappings(securityClaimMappings);
    }

    @Override
    public ValidationResult validate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return ValidationResult.failure("Missing bearer token");
        }
        try {
            Jwt jwt = jwtDecoder.decode(header.substring(7));
            return new KeycloakValidationResult(jwt.getSubject(), extractRoles(jwt), extractAttributes(jwt));
        } catch (JwtException e) {
            return ValidationResult.failure("Invalid Keycloak token");
        }
    }

    private List<String> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) return List.of();
        Object rolesObj = realmAccess.get("roles");
        if (rolesObj instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    Map<String, Object> extractAttributes(Jwt jwt) {
        Map<String, Object> attrs = new java.util.LinkedHashMap<>();
        PROFILE_CLAIMS.forEach(claim -> {
            Object value = jwt.getClaim(claim);
            if (value != null) {
                attrs.put(claim, value);
            }
        });
        securityClaimMappings.forEach((claim, attribute) -> {
            Object value = jwt.getClaim(claim);
            if (value != null) {
                attrs.put(attribute, value);
            }
        });
        return attrs;
    }

    private static Map<String, String> parseMappings(String mappings) {
        if (mappings == null || mappings.isBlank()) {
            return Map.of();
        }
        return java.util.Arrays.stream(mappings.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(entry -> entry.split(":", 2))
                .filter(parts -> parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank())
                .collect(Collectors.toUnmodifiableMap(parts -> parts[0].trim(), parts -> parts[1].trim()));
    }
}
