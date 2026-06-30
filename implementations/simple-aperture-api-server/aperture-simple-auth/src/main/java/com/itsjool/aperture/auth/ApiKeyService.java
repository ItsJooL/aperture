package com.itsjool.aperture.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class ApiKeyService {
    private static final int KEY_BYTES = 32;
    private static final Pattern GENERATED_KEY = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final ObjectMapper mapper;

    public ApiKeyService(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            SecureRandom secureRandom, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public IssuedApiKey issueWithPrincipal(String tenantId, String userId, String name, Instant expiresAt,
            List<String> domainRoles, Map<String, Object> securityAttributes) {
        requireText(tenantId, "tenantId");
        requireText(userId, "userId");
        requireText(name, "name");
        Instant now = clock.instant();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be after the current time");
        }

        return transactions.execute(status -> {
            List<Integer> eligible = jdbc.query("""
                    SELECT 1
                    FROM aperture_users account
                    JOIN aperture_tenants tenant ON tenant.id = account.tenant_id
                    WHERE account.tenant_id = ?
                      AND account.id = ?
                      AND account.status = 'ACTIVE'
                      AND account.deleted_at IS NULL
                      AND tenant.status = 'ACTIVE'
                    FOR SHARE OF account, tenant
                    """, (rs, rowNum) -> rs.getInt(1), tenantId, userId);
            if (eligible.isEmpty()) {
                throw new IllegalArgumentException("User is not eligible for an API key");
            }

            List<String> userRoles = jdbc.query("""
                    SELECT role_name
                    FROM aperture_user_roles
                    WHERE tenant_id = ? AND user_id = ?
                    """, (rs, rowNum) -> rs.getString(1), tenantId, userId);

            List<String> finalRoles = domainRoles != null ? domainRoles : List.of();
            if (!userRoles.containsAll(finalRoles)) {
                throw new IllegalArgumentException("Delegated roles must be a subset of user roles");
            }
            Map<String, Object> finalAttributes = securityAttributes != null ? securityAttributes : Map.of();
            Map<String, Object> userAttributes = jdbc.queryForObject("""
                    SELECT COALESCE(security_attributes, '{}'::jsonb)::text
                    FROM aperture_users
                    WHERE tenant_id = ? AND id = ?
                    """, (rs, rowNum) -> readMap(rs.getString(1)), tenantId, userId);
            for (Map.Entry<String, Object> entry : finalAttributes.entrySet()) {
                if (!Objects.equals(userAttributes.get(entry.getKey()), entry.getValue())) {
                    throw new IllegalArgumentException("Delegated security attributes must be exact current user attributes");
                }
            }

            byte[] bytes = new byte[KEY_BYTES];
            secureRandom.nextBytes(bytes);
            String rawKey = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String id = UUID.randomUUID().toString();

            String rolesJson = null;
            String attrsJson = null;
            try {
                rolesJson = mapper.writeValueAsString(finalRoles);
                attrsJson = mapper.writeValueAsString(finalAttributes);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid roles or attributes JSON", e);
            }

            jdbc.update("""
                    INSERT INTO aperture_personal_api_keys
                        (id, key_hash, tenant_id, user_id, name, status, created_at, expires_at, domain_roles, security_attributes)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, CAST(? AS JSONB), CAST(? AS JSONB))
                    """, id, hash(rawKey), tenantId, userId, name, Timestamp.from(now),
                    expiresAt == null ? null : Timestamp.from(expiresAt),
                    rolesJson, attrsJson);
            return new IssuedApiKey(id, rawKey);
        });
    }

    public Optional<AuthenticatedAccount> authenticate(String rawKey) {
        if (rawKey == null || !GENERATED_KEY.matcher(rawKey).matches()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return transactions.execute(status -> authenticateInTransaction(hash(rawKey), now));
    }

    public boolean disable(String keyId) {
        requireText(keyId, "keyId");
        return jdbc.update("UPDATE aperture_personal_api_keys SET status = 'DISABLED', revoked_at = ? WHERE id = ?",
                Timestamp.from(clock.instant()), keyId) == 1;
    }

    private Optional<AuthenticatedAccount> authenticateInTransaction(String keyHash, Instant now) {
        List<KeyAccount> matches = jdbc.query("""
                SELECT key.id, key.tenant_id, key.user_id, account.username,
                       key.domain_roles::text AS key_roles, key.security_attributes::text AS key_attributes,
                       account.security_attributes::text AS user_attributes
                FROM aperture_personal_api_keys key
                JOIN aperture_users account
                  ON account.tenant_id = key.tenant_id AND account.id = key.user_id
                JOIN aperture_tenants tenant ON tenant.id = key.tenant_id
                WHERE key.key_hash = ?
                  AND key.status = 'ACTIVE'
                  AND (key.expires_at IS NULL OR key.expires_at > ?)
                  AND account.status = 'ACTIVE'
                  AND account.deleted_at IS NULL
                  AND tenant.status = 'ACTIVE'
                FOR UPDATE OF key, account, tenant
                """, (rs, rowNum) -> new KeyAccount(
                        rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("user_id"), rs.getString("username"),
                        rs.getString("key_roles"), rs.getString("key_attributes"),
                        rs.getString("user_attributes")),
                keyHash, Timestamp.from(now));
        if (matches.isEmpty()) {
            return Optional.empty();
        }

        KeyAccount match = matches.getFirst();

        List<String> userRoles = jdbc.query("""
                SELECT role_name
                FROM aperture_user_roles
                WHERE tenant_id = ? AND user_id = ?
                """, (rs, rowNum) -> rs.getString(1), match.tenantId(), match.userId());

        List<String> keyRoles = List.of();
        if (match.keyRoles() != null) {
            try {
                keyRoles = mapper.readValue(match.keyRoles(), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                // ignore
            }
        }

        // Fail closed: if any delegated role is no longer held by the user, reject the key
        for (String role : keyRoles) {
            if (!userRoles.contains(role)) {
                return Optional.empty();
            }
        }
        List<String> roles = keyRoles;

        Map<String, Object> userAttributes = Map.of();
        if (match.userAttributes() != null) {
            try {
                userAttributes = mapper.readValue(match.userAttributes(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                // ignore
            }
        }

        Map<String, Object> keyAttributes = Map.of();
        if (match.keyAttributes() != null) {
            try {
                keyAttributes = mapper.readValue(match.keyAttributes(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                // ignore
            }
        }

        // Fail closed: if any delegated security attribute no longer matches the user's current value, reject the key
        for (Map.Entry<String, Object> entry : keyAttributes.entrySet()) {
            if (!Objects.equals(userAttributes.get(entry.getKey()), entry.getValue())) {
                return Optional.empty();
            }
        }
        Map<String, Object> attributes = keyAttributes;

        int updated = jdbc.update("""
                UPDATE aperture_personal_api_keys
                SET last_used_at = ?
                WHERE id = ?
                  AND status = 'ACTIVE'
                  AND (expires_at IS NULL OR expires_at > ?)
                """, Timestamp.from(now), match.keyId(), Timestamp.from(now));
        if (updated != 1) {
            return Optional.empty();
        }
        // Personal API keys never carry platform authorities; isTenantAdmin and isSuperAdmin are always false
        return Optional.of(new AuthenticatedAccount(match.userId(), match.username(),
                match.tenantId(), roles, com.itsjool.aperture.spi.PrincipalKind.PERSONAL_API_KEY, Map.of(), attributes, false, false, false));
    }

    private static String hash(String rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid security attributes JSON", e);
        }
    }

    public record IssuedApiKey(String id, String rawKey) {
        public IssuedApiKey {
            requireText(id, "id");
            requireText(rawKey, "rawKey");
        }
    }

    private record KeyAccount(String keyId, String tenantId, String userId, String username,
                               String keyRoles, String keyAttributes, String userAttributes) {}
}
