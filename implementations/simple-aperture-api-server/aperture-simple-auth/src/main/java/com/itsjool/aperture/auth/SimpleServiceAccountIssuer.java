package com.itsjool.aperture.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.spi.ServiceAccountIssuer;
import com.itsjool.aperture.spi.ServiceAccountRequest;
import com.itsjool.aperture.spi.ServiceAccountToken;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

public class SimpleServiceAccountIssuer implements ServiceAccountIssuer {
    private static final String DUMMY_SECRET_HASH =
            "$argon2id$v=19$m=16384,t=2,p=1$vAUAlhjPv3j6+FOJPXB7lg$5JcDsYY6SlCBWdcy8Id51Ew9XWgAtVlGh3jbqqLi5vM";

    private final JdbcTemplate jdbcTemplate;
    private final JwtTokenService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final java.security.SecureRandom secureRandom;
    private final ObjectMapper mapper;

    public SimpleServiceAccountIssuer(
            JdbcTemplate jdbcTemplate,
            JwtTokenService jwtService,
            PasswordEncoder passwordEncoder,
            Clock clock,
            java.security.SecureRandom secureRandom,
            ObjectMapper mapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.secureRandom = secureRandom;
        this.mapper = mapper;
    }

    @Override
    public Optional<ServiceAccountToken> issue(ServiceAccountRequest request) {
        String clientId = request == null || request.clientId() == null ? "" : request.clientId();
        String suppliedSecret = request == null || request.clientSecret() == null ? "" : request.clientSecret();
        Map<String, Object> account = findAccount(clientId).orElse(null);
        String storedHash = account == null ? null : (String) account.get("client_secret_hash");
        boolean secretMatches = passwordEncoder.matches(
                suppliedSecret, storedHash == null ? DUMMY_SECRET_HASH : storedHash);

        if (account == null || storedHash == null || !secretMatches || !eligible(account)) {
            return Optional.empty();
        }

        String accountId = (String) account.get("id");
        String tenantId = (String) account.get("tenant_id");
        List<RoleAssignment> assignments = jdbcTemplate.query("""
                SELECT DISTINCT assignments.role_name, assignments.expires_at
                FROM aperture_service_account_roles assignments
                JOIN aperture_roles declared
                  ON declared.tenant_id = assignments.tenant_id
                 AND declared.role_name = assignments.role_name
                WHERE assignments.tenant_id = ?
                  AND assignments.service_account_id = ?
                  AND (assignments.expires_at IS NULL OR assignments.expires_at > ?)
                ORDER BY assignments.role_name
                """, (resultSet, rowNumber) -> new RoleAssignment(
                        resultSet.getString("role_name"),
                        nullableExpiry(resultSet.getObject("expires_at"))),
                tenantId, accountId, Timestamp.from(clock.instant()));
        List<String> roles = assignments.stream().map(RoleAssignment::roleName).toList();
        Instant notAfter = assignments.stream()
                .map(RoleAssignment::expiresAt)
                .filter(java.util.Objects::nonNull)
                .reduce(expiry(account.get("expires_at")),
                        (earliest, candidate) -> candidate.isBefore(earliest) ? candidate : earliest);

        Map<String, Object> securityAttributes = Map.of();
        String secAttrsJson = (String) account.get("security_attributes");
        if (secAttrsJson != null && !secAttrsJson.isBlank()) {
            try {
                securityAttributes = mapper.readValue(secAttrsJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
        }

        var authenticated = new AuthenticatedAccount(
                accountId, clientId, tenantId, roles, com.itsjool.aperture.spi.PrincipalKind.SERVICE_ACCOUNT, Map.of(), securityAttributes, false, false, false);
        return Optional.of(new ServiceAccountToken(
                clientId, jwtService.generateToken(authenticated, notAfter)));
    }

    @Override
    public void disableCredentials(String clientId) {
        jdbcTemplate.update(
                "UPDATE aperture_service_accounts SET status = 'REVOKED' WHERE client_id = ?",
                clientId);
    }

    @Override
    public IssuedCredentials issueCredentials() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = passwordEncoder.encode(secret);

        return new IssuedCredentials(secret, hash);
    }

    private Optional<Map<String, Object>> findAccount(String clientId) {
        try {
            return Optional.of(jdbcTemplate.queryForMap("""
                    SELECT account.id, account.client_id, account.client_secret_hash,
                           account.tenant_id, account.status, account.expires_at,
                           COALESCE(account.security_attributes::text, '{}') AS security_attributes,
                           tenant.status AS tenant_status
                    FROM aperture_service_accounts account
                    LEFT JOIN aperture_tenants tenant ON tenant.id = account.tenant_id
                    WHERE account.client_id = ?
                    """, clientId));
        } catch (EmptyResultDataAccessException notFound) {
            return Optional.empty();
        }
    }

    private boolean eligible(Map<String, Object> account) {
        return "ACTIVE".equalsIgnoreCase((String) account.get("status"))
                && "ACTIVE".equalsIgnoreCase((String) account.get("tenant_status"))
                && expiry(account.get("expires_at")).isAfter(clock.instant());
    }

    private static Instant expiry(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return Instant.MIN;
    }

    private static Instant nullableExpiry(Object value) {
        return value == null ? null : expiry(value);
    }

    record RoleAssignment(String roleName, Instant expiresAt) {}
}
