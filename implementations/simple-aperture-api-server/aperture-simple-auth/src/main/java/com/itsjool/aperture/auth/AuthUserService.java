package com.itsjool.aperture.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

public class AuthUserService {
    private static final String DUMMY_PASSWORD_HASH =
            "$argon2id$v=19$m=16384,t=2,p=1$vAUAlhjPv3j6+FOJPXB7lg$5JcDsYY6SlCBWdcy8Id51Ew9XWgAtVlGh3jbqqLi5vM";
    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public AuthUserService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
    }

    public AuthUserService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this(jdbcTemplate, passwordEncoder, new ObjectMapper());
    }

    AuthUserService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    public void registerUser(String username, String rawPassword, String tenantId) {
        String hash = passwordEncoder.encode(rawPassword);
        jdbcTemplate.update(
            "INSERT INTO aperture_users (id, username, password_hash, tenant_id, status) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), username, hash, tenantId, "ACTIVE"
        );
    }

    public Optional<AuthenticatedAccount> authenticate(String username, String rawPassword) {
        Map<String, Object> user = findUser("""
                    SELECT id, username, password_hash, tenant_id, status, super_admin, profile, security_attributes, force_password_change
                    FROM aperture_users
                    WHERE username = ? AND deleted_at IS NULL
                    """, username).orElse(null);

        String passwordHash = user == null ? DUMMY_PASSWORD_HASH : (String) user.get("password_hash");
        String hashToCheck = passwordHash == null ? DUMMY_PASSWORD_HASH : passwordHash;
        boolean passwordMatches = passwordEncoder.matches(rawPassword == null ? "" : rawPassword, hashToCheck);

        if (user == null || passwordHash == null || !passwordMatches) {
            return Optional.empty();
        }

        return assembleActiveAccount(user);
    }

    public Optional<AuthenticatedAccount> loadActiveById(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return findUser("""
                SELECT id, username, tenant_id, status, super_admin, profile, security_attributes, force_password_change
                FROM aperture_users
                WHERE id = ? AND deleted_at IS NULL
                """, userId).flatMap(this::assembleActiveAccount);
    }

    public void changePassword(String userId, String currentRawPassword, String newRawPassword) {
        Map<String, Object> user;
        try {
            user = jdbcTemplate.queryForMap(
                "SELECT id, password_hash FROM aperture_users WHERE id = ?", userId);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("User not found");
        }
        String storedHash = (String) user.get("password_hash");
        if (!passwordEncoder.matches(currentRawPassword == null ? "" : currentRawPassword, storedHash)) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        String newHash = passwordEncoder.encode(newRawPassword);
        jdbcTemplate.update(
            "UPDATE aperture_users SET password_hash = ?, force_password_change = false WHERE id = ?",
            newHash, userId);
    }

    private Optional<Map<String, Object>> findUser(String sql, String identifier) {
        try {
            return Optional.of(jdbcTemplate.queryForMap(sql, identifier));
        } catch (EmptyResultDataAccessException notFound) {
            return Optional.empty();
        }
    }

    private Optional<AuthenticatedAccount> assembleActiveAccount(Map<String, Object> user) {
        if (!"ACTIVE".equalsIgnoreCase((String) user.get("status"))) {
            return Optional.empty();
        }

        String userId = (String) user.get("id");
        String tenantId = (String) user.get("tenant_id");
        boolean superAdmin = Boolean.TRUE.equals(user.get("super_admin"));
        if (superAdmin != (tenantId == null)) {
            return Optional.empty();
        }

        boolean forcePasswordChange = Boolean.TRUE.equals(user.get("force_password_change"));

        List<String> roles;
        boolean isTenantAdminFlag = false;
        if (superAdmin) {
            roles = List.of();
        } else {
            String tenantStatus;
            try {
                tenantStatus = jdbcTemplate.queryForObject(
                        "SELECT status FROM aperture_tenants WHERE id = ? AND deleted_at IS NULL", String.class, tenantId);
            } catch (EmptyResultDataAccessException notFound) {
                return Optional.empty();
            }
            if (!"ACTIVE".equalsIgnoreCase(tenantStatus)) {
                return Optional.empty();
            }
            roles = new ArrayList<>(jdbcTemplate.queryForList("""
                    SELECT role_name
                    FROM aperture_user_roles
                    WHERE user_id = ? AND tenant_id = ?
                    ORDER BY role_name
                    """, String.class, userId, tenantId));
                    
            List<Integer> isTenantAdminResult = jdbcTemplate.queryForList("""
                    SELECT 1 FROM aperture_tenant_admins
                    WHERE tenant_id = ? AND user_id = ?
                    """, Integer.class, tenantId, userId);
            isTenantAdminFlag = !isTenantAdminResult.isEmpty();
        }

        return Optional.of(new AuthenticatedAccount(
                userId,
                (String) user.get("username"),
                tenantId,
                roles,
                com.itsjool.aperture.spi.PrincipalKind.USER,
                parseAttributes(user.get("profile")),
                parseAttributes(user.get("security_attributes")),
                isTenantAdminFlag,
                superAdmin,
                forcePasswordChange));
    }

    private Map<String, Object> parseAttributes(Object attrObj) {
        if (attrObj == null) return Map.of();
        try {
            return objectMapper.readValue(attrObj.toString(), ATTRIBUTES_TYPE);
        } catch (JsonProcessingException invalidAttributes) {
            throw new IllegalStateException("Stored user attributes are not valid JSON", invalidAttributes);
        }
    }
}
