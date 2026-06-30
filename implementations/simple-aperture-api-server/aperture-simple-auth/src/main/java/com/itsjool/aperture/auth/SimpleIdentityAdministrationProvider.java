package com.itsjool.aperture.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.spi.IdentityAdministrationConflictException;
import com.itsjool.aperture.spi.IdentityAdministrationNotFoundException;
import com.itsjool.aperture.spi.IdentityAdministrationProvider;
import com.itsjool.aperture.spi.IdentityAdministrationValidationException;
import com.itsjool.aperture.spi.InviteCreateCommand;
import com.itsjool.aperture.spi.InviteCreationResult;
import com.itsjool.aperture.spi.InviteRecord;
import com.itsjool.aperture.spi.PagedResult;
import com.itsjool.aperture.spi.PersonalApiKeySettings;
import com.itsjool.aperture.spi.RoleCatalog;
import com.itsjool.aperture.spi.SecurityAttributeDefinition;
import com.itsjool.aperture.spi.TenantProvisioningCommand;
import com.itsjool.aperture.spi.TenantProvisioningResult;
import com.itsjool.aperture.spi.TenantRecord;
import com.itsjool.aperture.spi.UserCreateCommand;
import com.itsjool.aperture.spi.UserListQuery;
import com.itsjool.aperture.spi.UserRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SimpleIdentityAdministrationProvider implements IdentityAdministrationProvider {



    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final RoleCatalog catalog;
    private final ObjectMapper mapper;
    private final ApiKeyService apiKeyService;
    private final com.itsjool.aperture.spi.ServiceAccountIssuer serviceAccountIssuer;
    private final Map<String, SecurityAttributeDefinition> securityAttributes;

    public SimpleIdentityAdministrationProvider(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transactions,
            PasswordEncoder passwords,
            RoleCatalog catalog,
            ObjectMapper mapper,
            ApiKeyService apiKeyService,
            com.itsjool.aperture.spi.ServiceAccountIssuer serviceAccountIssuer,
            Map<String, SecurityAttributeDefinition> securityAttributes) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.passwords = passwords;
        this.catalog = catalog;
        this.mapper = mapper;
        this.apiKeyService = apiKeyService;
        this.serviceAccountIssuer = serviceAccountIssuer;
        this.securityAttributes = securityAttributes != null ? Map.copyOf(securityAttributes) : Map.of();
    }

    public SimpleIdentityAdministrationProvider(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transactions,
            PasswordEncoder passwords,
            RoleCatalog catalog,
            ObjectMapper mapper,
            ApiKeyService apiKeyService,
            com.itsjool.aperture.spi.ServiceAccountIssuer serviceAccountIssuer,
            java.util.Set<String> securityKeys) {
        this(jdbc, transactions, passwords, catalog, mapper, apiKeyService, serviceAccountIssuer,
                definitionsFromKeys(securityKeys));
    }

    @Override
    public TenantProvisioningResult provisionTenant(TenantProvisioningCommand command) {
        if (command.tenantId() == null || command.tenantId().isBlank() ||
                command.tenantName() == null || command.tenantName().isBlank() ||
                command.initialAdminUserId() == null || command.initialAdminUserId().isBlank() ||
                command.initialAdminUsername() == null || command.initialAdminUsername().isBlank() ||
                command.initialAdminPassword() == null || command.initialAdminPassword().isBlank()) {
            throw new IllegalArgumentException("tenant name and other fields must not be empty");
        }

        validateSecurityAttributes(command.initialAdminSecurityAttributes(), AssignmentTarget.USER);
        String hash = passwords.encode(command.initialAdminPassword());
        String profileJson, securityAttrsJson;
        try {
            profileJson = mapper.writeValueAsString(command.initialAdminProfile());
            securityAttrsJson = mapper.writeValueAsString(command.initialAdminSecurityAttributes());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid attributes", e);
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return transactions.execute(status -> {

                    List<Map<String, Object>> tenants = jdbc.queryForList(
                            "SELECT * FROM aperture_tenants WHERE id = :id AND name = :name AND deleted_at IS NULL",
                            new MapSqlParameterSource("id", command.tenantId()).addValue("name", command.tenantName()));
                    List<Map<String, Object>> users = jdbc.queryForList(
                            "SELECT * FROM aperture_users WHERE id = :id AND username = :username AND tenant_id = :tenantId AND deleted_at IS NULL",
                            new MapSqlParameterSource("id", command.initialAdminUserId())
                                    .addValue("username", command.initialAdminUsername())
                                    .addValue("tenantId", command.tenantId()));

                    List<String> defaultRoles = catalog.defaultRoles();
                    if (!tenants.isEmpty() && !users.isEmpty()) {
                        List<String> assignedRoles = jdbc.queryForList(
                                "SELECT role_name FROM aperture_user_roles WHERE tenant_id = :tenantId AND user_id = :userId",
                                new MapSqlParameterSource("tenantId", command.tenantId())
                                        .addValue("userId", command.initialAdminUserId()), String.class);
                        if (assignedRoles.containsAll(defaultRoles)) {
                            Object profileObj = users.get(0).get("profile");
                            Object secAttrsObj = users.get(0).get("security_attributes");
                            Map<String, Object> dbProfile = Map.of();
                            Map<String, Object> dbSecAttrs = Map.of();
                            try {
                                if (profileObj != null) dbProfile = mapper.readValue(profileObj.toString(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                                if (secAttrsObj != null) dbSecAttrs = mapper.readValue(secAttrsObj.toString(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                            } catch (JsonProcessingException e) {
                                throw new IllegalStateException("Failed to parse attributes from DB", e);
                            }
                            return new TenantProvisioningResult(
                                    new TenantRecord(command.tenantId(), command.tenantName(), "ACTIVE"),
                                    new UserRecord(command.initialAdminUserId(), command.initialAdminUsername(), command.tenantId(), "ACTIVE", false, dbProfile, dbSecAttrs),
                                    catalog.declaredRoles()
                            );
                        }
                    }

                    jdbc.update("INSERT INTO aperture_tenants (id, name, status) " +
                                    "VALUES (:id, :name, 'ACTIVE')",
                            new MapSqlParameterSource()
                                    .addValue("id", command.tenantId())
                                    .addValue("name", command.tenantName()));

                    List<String> declaredRoles = catalog.declaredRoles();

                    MapSqlParameterSource[] declaredRoleParams = declaredRoles.stream()
                            .map(roleName -> new MapSqlParameterSource()
                                    .addValue("id", java.util.UUID.randomUUID().toString())
                                    .addValue("tenantId", command.tenantId())
                                    .addValue("roleName", roleName))
                            .toArray(MapSqlParameterSource[]::new);
                    jdbc.batchUpdate("INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES (:id, :tenantId, :roleName)", declaredRoleParams);

                    jdbc.update("INSERT INTO aperture_users (id, username, password_hash, tenant_id, status, super_admin, profile, security_attributes) " +
                                    "VALUES (:id, :username, :hash, :tenantId, 'ACTIVE', false, CAST(:profile AS JSONB), CAST(:securityAttributes AS JSONB))",
                            new MapSqlParameterSource()
                                    .addValue("id", command.initialAdminUserId())
                                    .addValue("username", command.initialAdminUsername())
                                    .addValue("hash", hash)
                                    .addValue("tenantId", command.tenantId())
                                    .addValue("profile", profileJson)
                                    .addValue("securityAttributes", securityAttrsJson));

                    MapSqlParameterSource[] defaultRoleParams = defaultRoles.stream()
                            .map(roleName -> new MapSqlParameterSource()
                                    .addValue("tenantId", command.tenantId())
                                    .addValue("userId", command.initialAdminUserId())
                                    .addValue("roleName", roleName))
                            .toArray(MapSqlParameterSource[]::new);
                    if (defaultRoleParams.length > 0) {
                        jdbc.batchUpdate("INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) VALUES (:tenantId, :userId, :roleName)", defaultRoleParams);
                    }

                    jdbc.update("INSERT INTO aperture_tenant_admins (tenant_id, user_id, created_at) VALUES (:tenantId, :userId, :createdAt) ON CONFLICT DO NOTHING",
                            new MapSqlParameterSource("tenantId", command.tenantId())
                                    .addValue("userId", command.initialAdminUserId())
                                    .addValue("createdAt", java.sql.Timestamp.from(java.time.Instant.now())));

                    return new TenantProvisioningResult(
                            new TenantRecord(command.tenantId(), command.tenantName(), "ACTIVE"),
                            new UserRecord(command.initialAdminUserId(), command.initialAdminUsername(), command.tenantId(), "ACTIVE", false, command.initialAdminProfile(), command.initialAdminSecurityAttributes()),
                            declaredRoles
                    );
                });
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (attempt == 0) {
                    continue;
                }
                throw new IdentityAdministrationConflictException("Conflict provisioning tenant " + command.tenantId() + " or user id / username: " + command.initialAdminUserId() + " / " + command.initialAdminUsername(), e);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    @Override
    public java.util.Optional<TenantRecord> getTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId required");
        return jdbc.query("SELECT id, name, status FROM aperture_tenants WHERE id = :id AND deleted_at IS NULL",
                new MapSqlParameterSource("id", tenantId),
                (rs, rowNum) -> new TenantRecord(rs.getString("id"), rs.getString("name"), rs.getString("status")))
                .stream().findFirst();
    }

    @Override
    public PagedResult<TenantRecord> listTenants(int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("offset", page * size)
                .addValue("limit", size);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM aperture_tenants WHERE deleted_at IS NULL", params, Long.class);
        List<TenantRecord> items = jdbc.query(
                "SELECT id, name, status FROM aperture_tenants WHERE deleted_at IS NULL ORDER BY name LIMIT :limit OFFSET :offset",
                params, (rs, rowNum) -> new TenantRecord(rs.getString("id"), rs.getString("name"), rs.getString("status")));
        return new PagedResult<>(items, page, size, total != null ? total : 0);
    }

    @Override
    public void updateTenantStatus(String tenantId, String status) {
        if (tenantId == null || tenantId.isBlank() || status == null || status.isBlank()) {
            throw new IllegalArgumentException("tenantId and status required");
        }
        transactions.execute(txStatus -> {
            try {
                int updated = jdbc.update("UPDATE aperture_tenants SET status = :status WHERE id = :id AND deleted_at IS NULL",
                        new MapSqlParameterSource("id", tenantId).addValue("status", status));
                if (updated == 0) {
                    throw new IdentityAdministrationNotFoundException("Tenant not found: " + tenantId);
                }
                return null;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                throw new IdentityAdministrationValidationException("Invalid tenant status: " + status, e);
            }
        });
    }

    @Override
    public void deleteTenant(String tenantId) {
        int updated = jdbc.update(
                "UPDATE aperture_tenants SET deleted_at = NOW() WHERE id = :id AND deleted_at IS NULL",
                new MapSqlParameterSource("id", tenantId));
        if (updated == 0) throw new IdentityAdministrationNotFoundException("Tenant not found");
    }

    @Override
    public UserRecord createUser(UserCreateCommand command) {
        if (command.tenantId() == null || command.tenantId().isBlank() ||
            command.userId() == null || command.userId().isBlank() ||
            command.username() == null || command.username().isBlank() ||
            command.password() == null || command.password().isBlank()) {
            throw new IllegalArgumentException("tenantId, userId, username, password required and must not be empty");
        }
        validateSecurityAttributes(command.securityAttributes(), AssignmentTarget.USER);
        String hash = passwords.encode(command.password());
        String profileJson, securityAttrsJson;
        try {
            profileJson = mapper.writeValueAsString(command.profile());
            securityAttrsJson = mapper.writeValueAsString(command.securityAttributes());
        } catch (JsonProcessingException e) {
            throw new IdentityAdministrationValidationException("Invalid attributes", e);
        }
        return transactions.execute(status -> {
            try {
                jdbc.update("INSERT INTO aperture_users (id, username, password_hash, tenant_id, status, super_admin, profile, security_attributes, force_password_change) " +
                                "VALUES (:id, :username, :hash, :tenantId, 'ACTIVE', false, CAST(:profile AS JSONB), CAST(:securityAttributes AS JSONB), :forcePasswordChange)",
                        new MapSqlParameterSource()
                                .addValue("id", command.userId())
                                .addValue("username", command.username())
                                .addValue("hash", hash)
                                .addValue("tenantId", command.tenantId())
                                .addValue("profile", profileJson)
                                .addValue("securityAttributes", securityAttrsJson)
                                .addValue("forcePasswordChange", command.forcePasswordChange()));
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                throw new IdentityAdministrationConflictException("Conflict provisioning user", e);
            }
            return new UserRecord(command.userId(), command.username(), command.tenantId(), "ACTIVE", false, command.profile(), command.securityAttributes());
        });
    }

    @Override
    public PagedResult<UserRecord> listUsers(UserListQuery query) {
        String searchPattern = query.search() != null && !query.search().isBlank()
                ? "%" + query.search().toLowerCase() + "%" : null;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", query.tenantId())
                .addValue("offset", query.page() * query.size())
                .addValue("limit", query.size());

        String whereClause = "WHERE tenant_id = :tenantId AND deleted_at IS NULL";
        if (searchPattern != null) {
            whereClause += " AND LOWER(username) LIKE :search";
            params.addValue("search", searchPattern);
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM aperture_users " + whereClause, params, Long.class);

        List<UserRecord> items = jdbc.query(
                "SELECT id, username, status, super_admin, profile, security_attributes FROM aperture_users "
                + whereClause + " ORDER BY username LIMIT :limit OFFSET :offset",
                params, (rs, rowNum) -> mapUserRecord(rs, query.tenantId()));

        return new PagedResult<>(items, query.page(), query.size(), total != null ? total : 0);
    }

    @Override
    public java.util.Optional<UserRecord> getUser(String tenantId, String userId) {
        return jdbc.query("SELECT id, username, status, super_admin, profile, security_attributes FROM aperture_users WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL",
                new MapSqlParameterSource("tenantId", tenantId).addValue("id", userId),
                (rs, rowNum) -> mapUserRecord(rs, tenantId)).stream().findFirst();
    }

    @Override
    public void updateUserStatus(String tenantId, String userId, String status) {
        transactions.execute(txStatus -> {
            try {
                int updated = jdbc.update("UPDATE aperture_users SET status = :status WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL",
                        new MapSqlParameterSource("tenantId", tenantId).addValue("id", userId).addValue("status", status));
                if (updated == 0) {
                    throw new IdentityAdministrationNotFoundException("User not found");
                }
                return null;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                throw new IdentityAdministrationValidationException("Invalid user status: " + status, e);
            }
        });
    }

    @Override
    public void updateUser(String tenantId, String userId, com.itsjool.aperture.spi.UserUpdateCommand command) {
        transactions.execute(txStatus -> {
            command.status().ifPresent(status -> {
                try {
                    int updated = jdbc.update("UPDATE aperture_users SET status = :status WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL",
                            new MapSqlParameterSource("tenantId", tenantId).addValue("id", userId).addValue("status", status));
                    if (updated == 0) {
                        throw new IdentityAdministrationNotFoundException("User not found");
                    }
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    throw new IdentityAdministrationValidationException("Invalid user status: " + status, e);
                }
            });

            command.profile().ifPresent(profile -> {
                try {
                    String profileJson = mapper.writeValueAsString(profile);
                    int updated = jdbc.update(
                            "UPDATE aperture_users SET profile = jsonb_strip_nulls(COALESCE(profile, '{}'::jsonb) || CAST(:profile AS JSONB)) " +
                            "WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL",
                            new MapSqlParameterSource("tenantId", tenantId).addValue("id", userId).addValue("profile", profileJson));
                    if (updated == 0) {
                        throw new IdentityAdministrationNotFoundException("User not found");
                    }
                } catch (JsonProcessingException e) {
                    throw new IdentityAdministrationValidationException("Invalid profile", e);
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    throw new IdentityAdministrationValidationException("Invalid profile data", e);
                }
            });

            command.securityAttributes().ifPresent(securityAttrs -> {
                validateSecurityAttributes(securityAttrs, AssignmentTarget.USER);
                try {
                    String securityAttrsJson = mapper.writeValueAsString(securityAttrs);
                    int updated = jdbc.update(
                            "UPDATE aperture_users SET security_attributes = jsonb_strip_nulls(COALESCE(security_attributes, '{}'::jsonb) || CAST(:securityAttributes AS JSONB)) " +
                            "WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL",
                            new MapSqlParameterSource("tenantId", tenantId).addValue("id", userId).addValue("securityAttributes", securityAttrsJson));
                    if (updated == 0) {
                        throw new IdentityAdministrationNotFoundException("User not found");
                    }
                } catch (JsonProcessingException e) {
                    throw new IdentityAdministrationValidationException("Invalid security attributes", e);
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    throw new IdentityAdministrationValidationException("Invalid security attributes data", e);
                }
            });
            return null;
        });
    }

    @Override
    public void assignTenantAdmin(String tenantId, String userId) {
        transactions.execute(status -> {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM aperture_users WHERE tenant_id = :tenantId AND id = :userId",
                    new MapSqlParameterSource("tenantId", tenantId).addValue("userId", userId),
                    Integer.class);
            if (count == null || count == 0) {
                throw new IdentityAdministrationNotFoundException("Service account not found");
            }
            jdbc.update("INSERT INTO aperture_tenant_admins (tenant_id, user_id, created_at) VALUES (:tenantId, :userId, :createdAt) ON CONFLICT DO NOTHING",
                    new MapSqlParameterSource("tenantId", tenantId)
                            .addValue("userId", userId)
                            .addValue("createdAt", java.sql.Timestamp.from(java.time.Instant.now())));
            return null;
        });
    }

    @Override
    public void removeTenantAdmin(String tenantId, String userId) {
        int updated = jdbc.update("DELETE FROM aperture_tenant_admins WHERE tenant_id = :tenantId AND user_id = :userId",
                new MapSqlParameterSource("tenantId", tenantId).addValue("userId", userId));
        if (updated == 0) {
            throw new IdentityAdministrationNotFoundException("Tenant admin not found");
        }
    }

    @Override
    public boolean isTenantAdmin(String tenantId, String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM aperture_tenant_admins WHERE tenant_id = :tenantId AND user_id = :userId",
                new MapSqlParameterSource("tenantId", tenantId).addValue("userId", userId),
                Integer.class);
        return count != null && count > 0;
    }

    @Override
    public void deleteUser(String tenantId, String userId) {
        int updated = jdbc.update(
                "UPDATE aperture_users SET deleted_at = NOW() WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL",
                new MapSqlParameterSource("tenantId", tenantId).addValue("id", userId));
        if (updated == 0) throw new IdentityAdministrationNotFoundException("User not found");
    }

    @Override
    public void replaceUserRoles(String tenantId, String userId, List<String> roleNames) {
        if (roleNames == null) {
            throw new IllegalArgumentException("roleNames required");
        }
        List<String> uniqueRoleNames = roleNames.stream().distinct().toList();

        List<String> declared = catalog.declaredRoles();
        for (String role : uniqueRoleNames) {
            if (!declared.contains(role)) {
                throw new IdentityAdministrationValidationException("Role not in catalog: " + role);
            }
        }
        transactions.execute(status -> {
            int users = jdbc.queryForObject("SELECT count(*) FROM aperture_users WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL",
                    new MapSqlParameterSource("tenantId", tenantId).addValue("id", userId), Integer.class);
            if (users == 0) throw new IdentityAdministrationNotFoundException("User not found");

            jdbc.update("DELETE FROM aperture_user_roles WHERE tenant_id = :tenantId AND user_id = :id",
                    new MapSqlParameterSource("tenantId", tenantId).addValue("id", userId));

            if (!uniqueRoleNames.isEmpty()) {
                MapSqlParameterSource[] params = uniqueRoleNames.stream()
                        .map(roleName -> new MapSqlParameterSource()
                                .addValue("tenantId", tenantId)
                                .addValue("userId", userId)
                                .addValue("roleName", roleName))
                        .toArray(MapSqlParameterSource[]::new);
                try {
                    jdbc.batchUpdate("INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) VALUES (:tenantId, :userId, :roleName)", params);
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    throw new IdentityAdministrationConflictException("Conflict assigning roles", e);
                }
            }
            return null;
        });
    }

    @Override
    public com.itsjool.aperture.spi.ServiceAccountCreationResult createServiceAccount(com.itsjool.aperture.spi.ServiceAccountCreateCommand command) {
        if (command.tenantId() == null || command.accountId() == null || command.clientId() == null) {
            throw new IllegalArgumentException("tenantId, accountId, clientId required");
        }

        List<String> declared = catalog.declaredRoles();
        List<String> uniqueRoleNames = command.roleNames() == null ? null : command.roleNames().stream().distinct().toList();
        if (uniqueRoleNames != null) {
            for (String role : uniqueRoleNames) {
                if (!declared.contains(role)) {
                    throw new IdentityAdministrationValidationException("Role not in catalog: " + role);
                }
            }
        }

        validateSecurityAttributes(command.securityAttributes(), AssignmentTarget.SERVICE_ACCOUNT);
        var issued = serviceAccountIssuer.issueCredentials();

        String securityAttrsJson = "{}";
        try {
            if (command.securityAttributes() != null && !command.securityAttributes().isEmpty()) {
                securityAttrsJson = mapper.writeValueAsString(command.securityAttributes());
            }
        } catch (JsonProcessingException e) {
            throw new IdentityAdministrationValidationException("Invalid security attributes", e);
        }

        final String finalSecurityAttrsJson = securityAttrsJson;
        return transactions.execute(status -> {
            try {
                jdbc.update("INSERT INTO aperture_service_accounts (id, client_id, client_secret_hash, tenant_id, status, expires_at, security_attributes) " +
                                "VALUES (:accountId, :clientId, :hash, :tenantId, 'ACTIVE', :expiresAt, CAST(:securityAttrs AS JSONB))",
                        new MapSqlParameterSource()
                                .addValue("accountId", command.accountId())
                                .addValue("clientId", command.clientId())
                                .addValue("hash", issued.hash())
                                .addValue("tenantId", command.tenantId())
                                .addValue("securityAttrs", finalSecurityAttrsJson)
                                .addValue("expiresAt", command.expiresAt() != null ? java.sql.Timestamp.from(command.expiresAt()) : java.sql.Timestamp.from(java.time.Instant.now().plus(365, java.time.temporal.ChronoUnit.DAYS))));

                if (uniqueRoleNames != null && !uniqueRoleNames.isEmpty()) {
                    MapSqlParameterSource[] params = uniqueRoleNames.stream()
                            .map(roleName -> new MapSqlParameterSource()
                                    .addValue("tenantId", command.tenantId())
                                    .addValue("accountId", command.accountId())
                                    .addValue("roleName", roleName))
                            .toArray(MapSqlParameterSource[]::new);
                    jdbc.batchUpdate("INSERT INTO aperture_service_account_roles (tenant_id, service_account_id, role_name) VALUES (:tenantId, :accountId, :roleName)", params);
                }

                var record = new com.itsjool.aperture.spi.ServiceAccountRecord(command.accountId(), command.tenantId(), command.clientId(), "ACTIVE", command.expiresAt());
                return new com.itsjool.aperture.spi.ServiceAccountCreationResult(record, issued.secret());
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                throw new IdentityAdministrationConflictException("Conflict provisioning service account", e);
            }
        });
    }

    @Override
    public List<com.itsjool.aperture.spi.ServiceAccountRecord> listServiceAccounts(String tenantId) {
        return jdbc.query("SELECT id, client_id, status, expires_at FROM aperture_service_accounts WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", tenantId),
                (rs, rowNum) -> {
                    java.sql.Timestamp expires = rs.getTimestamp("expires_at");
                    return new com.itsjool.aperture.spi.ServiceAccountRecord(rs.getString("id"), tenantId, rs.getString("client_id"), rs.getString("status"), expires != null ? expires.toInstant() : null);
                });
    }

    @Override
    public java.util.Optional<com.itsjool.aperture.spi.ServiceAccountRecord> getServiceAccount(String tenantId, String accountId) {
        return jdbc.query("SELECT id, client_id, status, expires_at FROM aperture_service_accounts WHERE tenant_id = :tenantId AND id = :id",
                new MapSqlParameterSource("tenantId", tenantId).addValue("id", accountId),
                (rs, rowNum) -> {
                    java.sql.Timestamp expires = rs.getTimestamp("expires_at");
                    return new com.itsjool.aperture.spi.ServiceAccountRecord(rs.getString("id"), tenantId, rs.getString("client_id"), rs.getString("status"), expires != null ? expires.toInstant() : null);
                }).stream().findFirst();
    }

    @Override
    public void disableServiceAccount(String tenantId, String accountId) {
        transactions.execute(txStatus -> {
            int updated = jdbc.update("UPDATE aperture_service_accounts SET status = 'REVOKED' WHERE tenant_id = :tenantId AND id = :id",
                    new MapSqlParameterSource("tenantId", tenantId).addValue("id", accountId));
            if (updated == 0) {
                throw new IdentityAdministrationNotFoundException("User not found");
            }
            return null;
        });
    }

    @Override
    public com.itsjool.aperture.spi.ServiceAccountSecretRotationResult rotateServiceAccountSecret(String tenantId, String accountId) {
        var issued = serviceAccountIssuer.issueCredentials();
        return transactions.execute(status -> {
            int updated = jdbc.update("UPDATE aperture_service_accounts SET client_secret_hash = :hash WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'",
                    new MapSqlParameterSource("tenantId", tenantId).addValue("id", accountId).addValue("hash", issued.hash()));
            if (updated == 0) {
                throw new IdentityAdministrationNotFoundException("Active service account not found");
            }
            var account = getServiceAccount(tenantId, accountId).orElseThrow();
            return new com.itsjool.aperture.spi.ServiceAccountSecretRotationResult(account, issued.secret());
        });
    }

    @Override
    public com.itsjool.aperture.spi.ApiKeyCreationResult createApiKey(com.itsjool.aperture.spi.ApiKeyCreateCommand command) {
        if (apiKeyService == null) throw new UnsupportedOperationException("ApiKeyService not injected");

        return transactions.execute(status -> {
            var count = jdbc.queryForObject("SELECT count(*) FROM aperture_users WHERE tenant_id = :tid AND id = :id AND deleted_at IS NULL",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("tid", command.tenantId()).addValue("id", command.userId()), Integer.class);
            if (count == null || count == 0) {
                throw new IdentityAdministrationNotFoundException("User not found");
            }

            validateSecurityAttributes(command.securityAttributes(), AssignmentTarget.PERSONAL_API_KEY);
            PersonalApiKeySettings keySettings = getPersonalApiKeySettings(command.tenantId());
            boolean enabled = Boolean.TRUE.equals(keySettings.enabled());
            boolean allowNonExpiring = Boolean.TRUE.equals(keySettings.allowNonExpiring());

            if (!enabled) {
                throw new IdentityAdministrationValidationException("Personal API keys are disabled for this tenant");
            }
            if (command.nonExpiring() && !allowNonExpiring) {
                throw new IdentityAdministrationValidationException("Non-expiring personal API keys are not permitted for this tenant");
            }
            Instant effectiveExpiresAt = resolvePersonalApiKeyExpiry(command, keySettings);
            try {
                var issued = apiKeyService.issueWithPrincipal(command.tenantId(), command.userId(), command.name(),
                        effectiveExpiresAt, command.domainRoles(), command.securityAttributes());
                var records = jdbc.query("SELECT created_at, status FROM aperture_personal_api_keys WHERE id = :id",
                        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", issued.id()),
                        (rs, rowNum) -> new com.itsjool.aperture.spi.ApiKeyRecord(issued.id(), command.tenantId(), command.userId(), rs.getString("status"), rs.getTimestamp("created_at").toInstant(), effectiveExpiresAt, null));
                if (records.isEmpty()) throw new IllegalStateException("Key was not created");
                return new com.itsjool.aperture.spi.ApiKeyCreationResult(records.get(0), issued.rawKey());
            } catch (IllegalArgumentException e) {
                throw new IdentityAdministrationValidationException("Invalid user for API key issuance", e);
            }
        });
    }

    @Override
    public List<com.itsjool.aperture.spi.ApiKeyRecord> listApiKeys(String tenantId) {
        return jdbc.query("SELECT id, user_id, status, created_at, expires_at, last_used_at FROM aperture_personal_api_keys WHERE tenant_id = :tenantId AND status != 'DELETED' ORDER BY created_at DESC",
                new MapSqlParameterSource("tenantId", tenantId),
                (rs, rowNum) -> {
                    java.sql.Timestamp expires = rs.getTimestamp("expires_at");
                    java.sql.Timestamp lastUsed = rs.getTimestamp("last_used_at");
                    return new com.itsjool.aperture.spi.ApiKeyRecord(rs.getString("id"), tenantId, rs.getString("user_id"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(), expires != null ? expires.toInstant() : null, lastUsed != null ? lastUsed.toInstant() : null);
                });
    }

    @Override
    public List<com.itsjool.aperture.spi.ApiKeyRecord> listApiKeysByUser(String tenantId, String userId) {
        return jdbc.query("""
                SELECT id, user_id, status, created_at, expires_at, last_used_at
                FROM aperture_personal_api_keys
                WHERE tenant_id = :tenantId AND user_id = :userId AND status != 'DELETED'
                ORDER BY created_at DESC
                """,
                new MapSqlParameterSource("tenantId", tenantId).addValue("userId", userId),
                (rs, rowNum) -> {
                    java.sql.Timestamp expires = rs.getTimestamp("expires_at");
                    java.sql.Timestamp lastUsed = rs.getTimestamp("last_used_at");
                    return new com.itsjool.aperture.spi.ApiKeyRecord(rs.getString("id"), tenantId, rs.getString("user_id"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(), expires != null ? expires.toInstant() : null, lastUsed != null ? lastUsed.toInstant() : null);
                });
    }

    @Override
    public java.util.Optional<com.itsjool.aperture.spi.ApiKeyRecord> getApiKey(String tenantId, String keyId) {
        return jdbc.query("SELECT id, user_id, status, created_at, expires_at, last_used_at FROM aperture_personal_api_keys WHERE tenant_id = :tenantId AND id = :id",
                new MapSqlParameterSource("tenantId", tenantId).addValue("id", keyId),
                (rs, rowNum) -> {
                    java.sql.Timestamp expires = rs.getTimestamp("expires_at");
                    java.sql.Timestamp lastUsed = rs.getTimestamp("last_used_at");
                    return new com.itsjool.aperture.spi.ApiKeyRecord(rs.getString("id"), tenantId, rs.getString("user_id"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(), expires != null ? expires.toInstant() : null, lastUsed != null ? lastUsed.toInstant() : null);
                }).stream().findFirst();
    }

    @Override
    public void disableApiKey(String tenantId, String keyId) {
        transactions.execute(txStatus -> {
            int updated = jdbc.update(
                    "UPDATE aperture_personal_api_keys SET status = 'DISABLED', revoked_at = NOW() WHERE tenant_id = :tenantId AND id = :id",
                    new MapSqlParameterSource("tenantId", tenantId).addValue("id", keyId));
            if (updated == 0) {
                throw new IdentityAdministrationNotFoundException("API key not found");
            }
            return null;
        });
    }

    @Override
    public InviteCreationResult createInvite(InviteCreateCommand command) {
        String inviteId = UUID.randomUUID().toString();
        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String tokenHash = sha256Hex(rawToken);
        Instant expiresAt = Instant.now().plus(command.expiresInHours(), ChronoUnit.HOURS);

        String roleNamesJson;
        try {
            roleNamesJson = mapper.writeValueAsString(command.roleNames());
        } catch (JsonProcessingException e) {
            throw new IdentityAdministrationValidationException("Invalid role names", e);
        }

        String profileJson = "{}";
        String securityAttrsJson = "{}";
        try {
            validateSecurityAttributes(command.securityAttributes(), AssignmentTarget.USER);
            if (command.profile() != null) profileJson = mapper.writeValueAsString(command.profile());
            if (command.securityAttributes() != null) securityAttrsJson = mapper.writeValueAsString(command.securityAttributes());
        } catch (JsonProcessingException e) {
            throw new IdentityAdministrationValidationException("Invalid attributes", e);
        }

        jdbc.update(
                "INSERT INTO aperture_invites (id, tenant_id, token_hash, created_by, role_names, profile, security_attributes, expires_at, status) " +
                "VALUES (:id, :tenantId, :tokenHash, :createdBy, CAST(:roleNames AS JSONB), CAST(:profile AS JSONB), CAST(:securityAttributes AS JSONB), :expiresAt, 'PENDING')",
                new MapSqlParameterSource()
                        .addValue("id", inviteId)
                        .addValue("tenantId", command.tenantId())
                        .addValue("tokenHash", tokenHash)
                        .addValue("createdBy", command.createdBy())
                        .addValue("roleNames", roleNamesJson)
                        .addValue("profile", profileJson)
                        .addValue("securityAttributes", securityAttrsJson)
                        .addValue("expiresAt", Timestamp.from(expiresAt)));

        return new InviteCreationResult(inviteId, rawToken, expiresAt);
    }

    @Override
    public List<InviteRecord> listInvites(String tenantId) {
        return jdbc.query(
                "SELECT id, tenant_id, status, expires_at, accepted_at, role_names, profile, security_attributes FROM aperture_invites " +
                "WHERE tenant_id = :tenantId AND status = 'PENDING' ORDER BY expires_at",
                new MapSqlParameterSource("tenantId", tenantId),
                (rs, rowNum) -> {
                    List<String> roles = parseRoleNames(rs.getString("role_names"));
                    Timestamp acceptedAt = rs.getTimestamp("accepted_at");
                    Map<String, Object> profile = null;
                    Map<String, Object> securityAttributes = null;
                    try {
                        if (rs.getString("profile") != null) profile = mapper.readValue(rs.getString("profile"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        if (rs.getString("security_attributes") != null) securityAttributes = mapper.readValue(rs.getString("security_attributes"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    } catch (JsonProcessingException e) {}
                    return new InviteRecord(rs.getString("id"), rs.getString("tenant_id"),
                            rs.getString("status"), rs.getTimestamp("expires_at").toInstant(),
                            acceptedAt != null ? acceptedAt.toInstant() : null, roles, profile, securityAttributes);
                });
    }

    @Override
    public void revokeInvite(String tenantId, String inviteId) {
        int updated = jdbc.update(
                "UPDATE aperture_invites SET status = 'REVOKED' WHERE tenant_id = :tenantId AND id = :id AND status = 'PENDING'",
                new MapSqlParameterSource("tenantId", tenantId).addValue("id", inviteId));
        if (updated == 0) throw new IdentityAdministrationNotFoundException("Invite not found or not pending");
    }

    @Override
    public UserRecord acceptInvite(String rawToken, String username, String rawPassword) {
        String tokenHash = sha256Hex(rawToken);
        return transactions.execute(status -> {
            List<Map<String, Object>> invites = jdbc.queryForList(
                    "SELECT id, tenant_id, status, expires_at, role_names, profile, security_attributes FROM aperture_invites WHERE token_hash = :hash",
                    new MapSqlParameterSource("hash", tokenHash));
            if (invites.isEmpty()) throw new IdentityAdministrationNotFoundException("Invite not found");

            Map<String, Object> invite = invites.get(0);
            String inviteStatus = (String) invite.get("status");
            Instant expiresAt = ((Timestamp) invite.get("expires_at")).toInstant();

            if (!"PENDING".equals(inviteStatus)) throw new IdentityAdministrationValidationException("Invite is no longer pending");
            if (Instant.now().isAfter(expiresAt)) throw new IdentityAdministrationValidationException("Invite has expired");

            String tenantId = (String) invite.get("tenant_id");
            List<String> roleNames = parseRoleNames(invite.get("role_names").toString());
            String inviteId = (String) invite.get("id");

            String userId = UUID.randomUUID().toString();
            String hash = passwords.encode(rawPassword);

            String inviteProfile = invite.get("profile") != null ? invite.get("profile").toString() : "{}";
            String inviteSecAttrs = invite.get("security_attributes") != null ? invite.get("security_attributes").toString() : "{}";

            try {
                jdbc.update(
                        "INSERT INTO aperture_users (id, username, password_hash, tenant_id, status, super_admin, profile, security_attributes) " +
                        "VALUES (:id, :username, :hash, :tenantId, 'ACTIVE', false, CAST(:profile AS JSONB), CAST(:secAttrs AS JSONB))",
                        new MapSqlParameterSource()
                                .addValue("id", userId).addValue("username", username)
                                .addValue("hash", hash).addValue("tenantId", tenantId)
                                .addValue("profile", inviteProfile)
                                .addValue("secAttrs", inviteSecAttrs));
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                throw new IdentityAdministrationConflictException("Username already taken", e);
            }

            if (!roleNames.isEmpty()) {
                MapSqlParameterSource[] roleParams = roleNames.stream()
                        .map(role -> new MapSqlParameterSource()
                                .addValue("tenantId", tenantId).addValue("userId", userId).addValue("roleName", role))
                        .toArray(MapSqlParameterSource[]::new);
                jdbc.batchUpdate("INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) VALUES (:tenantId, :userId, :roleName)", roleParams);
            }

            jdbc.update(
                    "UPDATE aperture_invites SET status = 'USED', accepted_at = NOW() WHERE id = :id",
                    new MapSqlParameterSource("id", inviteId));

            return new UserRecord(userId, username, tenantId, "ACTIVE", false, Map.of(), Map.of());
        });
    }

    @Override
    public PersonalApiKeySettings getGlobalPersonalApiKeySettings() {
        Map<String, Object> full = readGlobalSettings();
        PersonalApiKeySettings global = parsePersonalApiKeySettings(full.get("personalApiKeys"));
        return PersonalApiKeySettings.DEFAULTS.merge(global);
    }

    @Override
    public void updateGlobalPersonalApiKeySettings(PersonalApiKeySettings settings) {
        try {
            PersonalApiKeySettings validated = PersonalApiKeySettings.DEFAULTS.merge(settings);
            Map<String, Object> existing = readGlobalSettings();
            Map<String, Object> merged = new java.util.LinkedHashMap<>(existing);
            merged.put("personalApiKeys", settingsMap(validated));
            String json = mapper.writeValueAsString(merged);
            jdbc.update("""
                INSERT INTO aperture_global_settings (id, settings, updated_at)
                VALUES ('global', CAST(:settings AS JSONB), NOW())
                ON CONFLICT (id) DO UPDATE SET settings = CAST(:settings AS JSONB), updated_at = NOW()
                """, new MapSqlParameterSource("settings", json));
        } catch (JsonProcessingException e) {
            throw new IdentityAdministrationValidationException("Invalid settings", e);
        }
    }

    @Override
    public PersonalApiKeySettings getPersonalApiKeySettings(String tenantId) {
        Map<String, Object> full = readTenantSettings(tenantId);
        PersonalApiKeySettings tenant = parsePersonalApiKeySettings(full.get("personalApiKeys"));
        return getGlobalPersonalApiKeySettings().merge(tenant);
    }

    @Override
    public void updatePersonalApiKeySettings(String tenantId, PersonalApiKeySettings settings) {
        try {
            PersonalApiKeySettings validated = PersonalApiKeySettings.DEFAULTS.merge(settings);
            Map<String, Object> existing = readTenantSettings(tenantId);
            Map<String, Object> merged = new java.util.LinkedHashMap<>(existing);
            merged.put("personalApiKeys", settingsMap(validated));
            String json = mapper.writeValueAsString(merged);
            jdbc.update("""
                INSERT INTO aperture_tenant_settings (tenant_id, settings, updated_at)
                VALUES (:tenantId, CAST(:settings AS JSONB), NOW())
                ON CONFLICT (tenant_id) DO UPDATE SET settings = CAST(:settings AS JSONB), updated_at = NOW()
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("settings", json));
        } catch (JsonProcessingException e) {
            throw new IdentityAdministrationValidationException("Invalid settings", e);
        }
    }

    private Map<String, Object> readTenantSettings(String tenantId) {
        try {
            List<String> rows = jdbc.query(
                "SELECT COALESCE(settings, '{}'::jsonb)::text FROM aperture_tenant_settings WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", tenantId),
                (rs, rowNum) -> rs.getString(1));
            if (rows.isEmpty()) return Map.of();
            return mapper.readValue(rows.get(0), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IdentityAdministrationValidationException("Invalid personal API key settings", e);
        }
    }

    private Map<String, Object> readGlobalSettings() {
        try {
            List<String> rows = jdbc.query(
                "SELECT COALESCE(settings, '{}'::jsonb)::text FROM aperture_global_settings WHERE id = 'global'",
                new MapSqlParameterSource(),
                (rs, rowNum) -> rs.getString(1));
            if (rows.isEmpty()) return Map.of();
            return mapper.readValue(rows.get(0), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IdentityAdministrationValidationException("Invalid global personal API key settings", e);
        }
    }

    private Map<String, Object> settingsMap(PersonalApiKeySettings settings) {
        return Map.of(
                "enabled", settings.enabled(),
                "allowNonExpiring", settings.allowNonExpiring(),
                "defaultTtlDays", settings.defaultTtlDays(),
                "maxTtlDays", settings.maxTtlDays());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        if (obj instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    private PersonalApiKeySettings parsePersonalApiKeySettings(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IdentityAdministrationValidationException("Invalid personal API key settings");
        }
        return new PersonalApiKeySettings(
                readBoolean(raw, "enabled"),
                readBoolean(raw, "allowNonExpiring"),
                readInteger(raw, "defaultTtlDays"),
                readInteger(raw, "maxTtlDays"));
    }

    private Boolean readBoolean(Map<?, ?> settings, String key) {
        Object value = settings.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IdentityAdministrationValidationException("Invalid personal API key settings: " + key + " must be boolean");
    }

    private Integer readInteger(Map<?, ?> settings, String key) {
        Object value = settings.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            int intValue = n.intValue();
            if (n.doubleValue() != intValue) {
                throw new IdentityAdministrationValidationException("Invalid personal API key settings: " + key + " must be an integer");
            }
            return intValue;
        }
        throw new IdentityAdministrationValidationException("Invalid personal API key settings: " + key + " must be an integer");
    }

    private Instant resolvePersonalApiKeyExpiry(com.itsjool.aperture.spi.ApiKeyCreateCommand command,
            PersonalApiKeySettings settings) {
        Instant now = Instant.now();
        if (command.nonExpiring()) {
            return null;
        }
        Instant effective = command.expiresAt() != null
                ? command.expiresAt()
                : now.plus(settings.defaultTtlDays(), ChronoUnit.DAYS);
        if (!effective.isAfter(now)) {
            throw new IdentityAdministrationValidationException("Personal API key expiry must be in the future");
        }
        Instant maximum = now.plus(settings.maxTtlDays(), ChronoUnit.DAYS);
        if (effective.isAfter(maximum)) {
            throw new IdentityAdministrationValidationException("Personal API key expiry exceeds tenant policy");
        }
        return effective;
    }

    private void validateSecurityAttributes(Map<String, Object> attributes, AssignmentTarget target) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            SecurityAttributeDefinition definition = securityAttributes.get(entry.getKey());
            if (definition == null) {
                throw new IdentityAdministrationValidationException("Unknown security attribute: " + entry.getKey());
            }
            if (target == AssignmentTarget.SERVICE_ACCOUNT && !Boolean.TRUE.equals(definition.serviceAccountAssignable())) {
                throw new IdentityAdministrationValidationException("Security attribute is not assignable to service accounts: " + entry.getKey());
            }
            if (target == AssignmentTarget.PERSONAL_API_KEY && !"exact".equals(definition.personalKeyDelegation())) {
                throw new IdentityAdministrationValidationException("Security attribute is not delegable to personal API keys: " + entry.getKey());
            }
            validateSecurityAttributeValue(entry.getKey(), entry.getValue(), definition);
        }
    }

    private void validateSecurityAttributeValue(String key, Object value, SecurityAttributeDefinition definition) {
        if (value == null) {
            return;
        }
        boolean validType = switch (definition.type()) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "string[]" -> value instanceof List<?> values && values.stream().allMatch(String.class::isInstance);
            default -> throw new IdentityAdministrationValidationException("Unsupported security attribute type for " + key + ": " + definition.type());
        };
        if (!validType) {
            throw new IdentityAdministrationValidationException("Invalid type for security attribute: " + key);
        }
        if (!definition.allowedValues().isEmpty()) {
            if (value instanceof List<?> values) {
                for (Object item : values) {
                    if (!definition.allowedValues().contains(String.valueOf(item))) {
                        throw new IdentityAdministrationValidationException("Invalid value for security attribute: " + key);
                    }
                }
            } else if (!definition.allowedValues().contains(String.valueOf(value))) {
                throw new IdentityAdministrationValidationException("Invalid value for security attribute: " + key);
            }
        }
    }

    private static Map<String, SecurityAttributeDefinition> definitionsFromKeys(java.util.Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, SecurityAttributeDefinition> definitions = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            definitions.put(key, new SecurityAttributeDefinition("string", List.of(), "exact", false));
        }
        return definitions;
    }

    private enum AssignmentTarget {
        USER,
        SERVICE_ACCOUNT,
        PERSONAL_API_KEY
    }

    private UserRecord mapUserRecord(ResultSet rs, String tenantId) throws SQLException {
        String profileStr = rs.getString("profile");
        String secAttrsStr = rs.getString("security_attributes");
        Map<String, Object> profile = new java.util.LinkedHashMap<>();
        Map<String, Object> securityAttributes = new java.util.LinkedHashMap<>();
        try {
            if (profileStr != null) profile.putAll(mapper.readValue(profileStr, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            if (secAttrsStr != null) securityAttributes.putAll(mapper.readValue(secAttrsStr, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse attributes from DB", e);
        }
        return new UserRecord(rs.getString("id"), rs.getString("username"), tenantId,
                rs.getString("status"), rs.getBoolean("super_admin"), profile, securityAttributes);
    }

    @SuppressWarnings("unchecked")
    private List<String> parseRoleNames(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
