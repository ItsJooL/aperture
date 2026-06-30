package com.itsjool.aperture.auth;

import com.itsjool.aperture.spi.CredentialValidator;
import com.itsjool.aperture.spi.ValidationResult;
import jakarta.servlet.http.HttpServletRequest;
import io.jsonwebtoken.Claims;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SimpleCredentialValidator implements CredentialValidator {
    private final JwtTokenService jwtService;
    private final ApiKeyService apiKeys;

    public SimpleCredentialValidator(JwtTokenService jwtService, ApiKeyService apiKeys) {
        this.jwtService = jwtService;
        this.apiKeys = apiKeys;
    }

    @Override
    public ValidationResult validate(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        String apiKey = request.getHeader("X-API-Key");
        boolean bearerPresent = authHeader != null && authHeader.startsWith("Bearer ");
        if (bearerPresent && apiKey != null) {
            return ValidationResult.failure("Ambiguous credentials");
        }
        if (apiKey != null) {
            return apiKeys.authenticate(apiKey)
                    .<ValidationResult>map(TrustedAccountValidationResult::from)
                    .orElseGet(() -> ValidationResult.failure("Invalid API key"));
        }
        if (bearerPresent && authHeader.length() > 7) {
            String token = authHeader.substring(7);
            try {
                Claims claims = jwtService.validateToken(token);
                Map<String, Object> profileAttrs = attributes(claims.get("profile"));
                Map<String, Object> secAttrs = attributes(claims.get("securityAttributes"));
                @SuppressWarnings("unchecked")
                Set<String> scopes = claims.get("scope") instanceof List<?> list
                        ? Set.copyOf(list.stream().map(Object::toString).toList())
                        : Set.of();
                // isSuperAdmin/isTenantAdmin are read from signed JWT claims and trusted for the
                // token's lifetime without a per-request DB re-check. This is a deliberate
                // trade-off: access tokens are short-lived (default 15 min) and signed with
                // HMAC-SHA256, so a demoted admin retains authority until expiry at most.
                // API keys are fail-closed on every use (see ApiKeyService.authenticateInTransaction).
                // If immediate revocation of platform authorities is required, reduce the access
                // token TTL or add a per-request DB re-check in AuthFilter for /manage/** paths.
                boolean isSuperAdmin = Boolean.TRUE.equals(claims.get("isSuperAdmin"));
                return new TrustedAccountValidationResult(
                        requireString(claims.getSubject(), "sub"),
                        nullableString(claims.get("tenantId"), "tenantId"),
                        stringList(claims.get("roles"), "roles"),
                        profileAttrs,
                        secAttrs,
                        accountKind(claims.get("accountKind")),
                        Boolean.TRUE.equals(claims.get("isTenantAdmin")),
                        scopes,
                        isSuperAdmin);
            } catch (Exception e) {
                return ValidationResult.failure("Invalid bearer token");
            }
        }
        return ValidationResult.failure("Missing bearer token");
    }

    public record TrustedAccountValidationResult(
            String subject,
            String tenantId,
            List<String> roles,
            Map<String, Object> profile,
            Map<String, Object> securityAttributes,
            com.itsjool.aperture.spi.PrincipalKind kind,
            boolean isTenantAdmin,
            Set<String> scopes,
            boolean isSuperAdmin) implements ValidationResult {
        public TrustedAccountValidationResult {
            subject = requireString(subject, "sub");
            roles = List.copyOf(roles);
            profile = immutableAttributes(profile);
            securityAttributes = immutableAttributes(securityAttributes);
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }

        private static TrustedAccountValidationResult from(AuthenticatedAccount account) {
            return new TrustedAccountValidationResult(account.userId(), account.tenantId(), account.roles(),
                    account.profile(), account.securityAttributes(), account.kind(), account.isTenantAdmin(),
                    Set.of(), account.isSuperAdmin());
        }

        @Override public boolean isValid() { return true; }
        @Override public String tokenSubject() { return subject; }
        @Override public String errorMessage() { return null; }
    }

    private static String requireString(Object value, String claim) {
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("Invalid " + claim + " claim");
        }
        return string;
    }

    private static String nullableString(Object value, String claim) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("Invalid " + claim + " claim");
        }
        return string;
    }

    private static List<String> stringList(Object value, String claim) {
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("Invalid " + claim + " claim");
        }
        return list.stream().map(String.class::cast).toList();
    }

    private static Map<String, Object> attributes(Object value) {
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException("Invalid attributes claim");
        }
        return immutableAttributes(map);
    }

    private static com.itsjool.aperture.spi.PrincipalKind accountKind(Object value) {
        if (value instanceof String s) {
            try {
                return com.itsjool.aperture.spi.PrincipalKind.valueOf(s);
            } catch (IllegalArgumentException e) {
                // fallthrough
            }
        }
        throw new IllegalArgumentException("Invalid accountKind claim");
    }

    private static Map<String, Object> immutableAttributes(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String string)) {
                throw new IllegalArgumentException("Invalid attributes claim");
            }
            result.put(string, immutableJsonValue(value));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableJsonValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?> map) {
            return immutableAttributes(map);
        }
        throw new IllegalArgumentException("Invalid attributes claim");
    }
}
