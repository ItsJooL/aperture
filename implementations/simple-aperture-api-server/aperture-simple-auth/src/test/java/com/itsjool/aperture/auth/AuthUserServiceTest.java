package com.itsjool.aperture.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthUserServiceTest {
    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String PASSWORD = "correct-password";
    private static final String PASSWORD_HASH =
            Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD);

    @Test
    void registerUserCreatesActiveUser() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.encode(PASSWORD)).thenReturn(PASSWORD_HASH);
        AuthUserService service = new AuthUserService(jdbc, passwords, new ObjectMapper());

        service.registerUser("viewer@example.com", PASSWORD, TENANT_ID);

        verify(jdbc).update(
                argThat(sql -> sql.contains("(id, username, password_hash, tenant_id, status)")),
                anyString(), eq("viewer@example.com"), eq(PASSWORD_HASH), eq(TENANT_ID), eq("ACTIVE"));
    }

    @Test
    void unknownUserStillPerformsOnePasswordCheck() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(jdbc.queryForMap(anyString(), eq("missing@example.com")))
                .thenThrow(new EmptyResultDataAccessException(1));
        AuthUserService service = new AuthUserService(jdbc, passwords, new ObjectMapper());

        assertThat(service.authenticate("missing@example.com", PASSWORD)).isEmpty();

        verify(passwords).matches(eq(PASSWORD), anyString());
    }

    @Test
    void inactiveUserStillPerformsOnePasswordCheck() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(jdbc.queryForMap(anyString(), eq("disabled@example.com"))).thenReturn(userRow(
                "disabled@example.com", TENANT_ID, false, "DISABLED", PASSWORD_HASH, "{}"));
        AuthUserService service = new AuthUserService(jdbc, passwords, new ObjectMapper());

        assertThat(service.authenticate("disabled@example.com", PASSWORD)).isEmpty();

        verify(passwords).matches(PASSWORD, PASSWORD_HASH);
    }

    @Test
    void authenticatesViewerWithJsonAttributes() {
        Fixture fixture = activeTenantUser("viewer@example.com", List.of("Viewer"),
                "{\"department\":\"finance\",\"limit\":25}");

        AuthenticatedAccount account = fixture.service.authenticate("viewer@example.com", PASSWORD).orElseThrow();

        assertThat(account.userId()).isEqualTo(USER_ID);
        assertThat(account.username()).isEqualTo("viewer@example.com");
        assertThat(account.tenantId()).isEqualTo(TENANT_ID);
        assertThat(account.roles()).containsExactly("Viewer");
        assertThat(account.profile()).containsEntry("department", "finance").containsEntry("limit", 25);
        assertThat(account.kind() == com.itsjool.aperture.spi.PrincipalKind.SERVICE_ACCOUNT).isFalse();
    }

    @Test
    void loadsActiveAccountByStoredIdWithoutCheckingPassword() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(jdbc.queryForMap(anyString(), eq(USER_ID))).thenReturn(userRow(
                "viewer@example.com", TENANT_ID, false, "ACTIVE", PASSWORD_HASH,
                "{\"department\":\"finance\"}"));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(TENANT_ID))).thenReturn("ACTIVE");
        when(jdbc.queryForList(anyString(), eq(String.class), eq(USER_ID), eq(TENANT_ID)))
                .thenReturn(List.of("Viewer"));
        AuthUserService service = new AuthUserService(jdbc, passwords, new ObjectMapper());

        AuthenticatedAccount account = service.loadActiveById(USER_ID).orElseThrow();

        assertThat(account.userId()).isEqualTo(USER_ID);
        assertThat(account.roles()).containsExactly("Viewer");
        assertThat(account.profile()).containsEntry("department", "finance");
        verify(passwords, org.mockito.Mockito.never()).matches(anyString(), anyString());
    }

    @Test
    void storedIdReloadRejectsInactiveAccount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(USER_ID))).thenReturn(userRow(
                "viewer@example.com", TENANT_ID, false, "DISABLED", PASSWORD_HASH, "{}"));

        assertThat(new AuthUserService(jdbc).loadActiveById(USER_ID)).isEmpty();
    }

    @Test
    void authenticatesAccountantAndTenantAdminWithExactRoles() {
        Fixture fixture = activeTenantUser("accountant@example.com",
                List.of("Accountant", "TenantAdmin"), "{}");

        assertThat(fixture.service.authenticate("accountant@example.com", PASSWORD).orElseThrow().roles())
                .containsExactly("Accountant", "TenantAdmin");
    }

    @Test
    void superAdministratorGetsOnlyBuiltInRole() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq("root@example.com"))).thenReturn(Map.of(
                "id", USER_ID, "username", "root@example.com", "password_hash", PASSWORD_HASH,
                "status", "ACTIVE", "super_admin", true, "profile", "{}", "security_attributes", "{}"));

        AuthenticatedAccount account = new AuthUserService(jdbc)
                .authenticate("root@example.com", PASSWORD).orElseThrow();

        assertThat(account.tenantId()).isNull();
        assertThat(account.roles()).isEmpty();
        assertThat(account.isSuperAdmin()).isTrue();
    }

    @Test
    void superAdministratorWithTenantIsRejected() {
        AuthUserService service = serviceForUser(
                "root@example.com", TENANT_ID, true, "ACTIVE", "{}");

        assertThat(service.authenticate("root@example.com", PASSWORD)).isEmpty();
    }

    @Test
    void tenantUserWithoutTenantIsRejected() {
        AuthUserService service = serviceForUser(
                "viewer@example.com", null, false, "ACTIVE", "{}");

        assertThat(service.authenticate("viewer@example.com", PASSWORD)).isEmpty();
    }

    @Test
    void jsonNullAttributeIsAcceptedAndAttributesAreImmutable() {
        Fixture fixture = activeTenantUser(
                "viewer@example.com", List.of("Viewer"), "{\"nullable\":null,\"region\":\"eu\"}");

        Map<String, Object> attributes = fixture.service
                .authenticate("viewer@example.com", PASSWORD).orElseThrow().profile();

        assertThat(attributes).containsEntry("nullable", null).containsEntry("region", "eu");
        assertThatThrownBy(() -> attributes.put("region", "us"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void wrongPasswordReturnsEmpty() {
        Fixture fixture = activeTenantUser("viewer@example.com", List.of("Viewer"), "{}");

        assertThat(fixture.service.authenticate("viewer@example.com", "wrong")).isEmpty();
    }

    @Test
    void disabledUserReturnsEmpty() {
        Fixture fixture = tenantUser("disabled@example.com", "DISABLED", "ACTIVE", List.of("Viewer"), "{}");

        assertThat(fixture.service.authenticate("disabled@example.com", PASSWORD)).isEmpty();
    }

    @Test
    void suspendedTenantReturnsEmpty() {
        Fixture fixture = tenantUser("viewer@example.com", "ACTIVE", "SUSPENDED", List.of("Viewer"), "{}");

        assertThat(fixture.service.authenticate("viewer@example.com", PASSWORD)).isEmpty();
    }

    @Test
    void rolesQueryIsScopedToUsersTenant() {
        Fixture fixture = activeTenantUser("viewer@example.com", List.of("Viewer"), "{}");

        assertThat(fixture.service.authenticate("viewer@example.com", PASSWORD).orElseThrow().roles())
                .containsExactly("Viewer");
        verify(fixture.jdbc).queryForList(anyString(), eq(String.class), eq(USER_ID), eq(TENANT_ID));
    }

    @Test
    void databaseErrorsPropagate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unavailable");
        when(jdbc.queryForMap(anyString(), eq("viewer@example.com"))).thenThrow(failure);

        assertThatThrownBy(() -> new AuthUserService(jdbc).authenticate("viewer@example.com", PASSWORD))
                .isSameAs(failure);
    }

    private static Fixture activeTenantUser(String username, List<String> roles, String attributes) {
        return tenantUser(username, "ACTIVE", "ACTIVE", roles, attributes);
    }

    private static Fixture tenantUser(
            String username, String userStatus, String tenantStatus, List<String> roles, String attributes) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(username))).thenReturn(userRow(
                username, TENANT_ID, false, userStatus, PASSWORD_HASH, attributes));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(TENANT_ID))).thenReturn(tenantStatus);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(USER_ID), eq(TENANT_ID))).thenReturn(roles);
        return new Fixture(new AuthUserService(jdbc), jdbc);
    }

    private static AuthUserService serviceForUser(
            String username, String tenantId, boolean superAdmin, String status, String attributes) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(username))).thenReturn(userRow(
                username, tenantId, superAdmin, status, PASSWORD_HASH, attributes));
        return new AuthUserService(jdbc);
    }

    private static Map<String, Object> userRow(
            String username, String tenantId, boolean superAdmin, String status,
            String passwordHash, String profile) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", USER_ID);
        row.put("username", username);
        row.put("password_hash", passwordHash);
        row.put("tenant_id", tenantId);
        row.put("status", status);
        row.put("super_admin", superAdmin);
        row.put("profile", profile);
        row.put("security_attributes", "{}");
        return row;
    }

    private record Fixture(AuthUserService service, JdbcTemplate jdbc) {}
}
