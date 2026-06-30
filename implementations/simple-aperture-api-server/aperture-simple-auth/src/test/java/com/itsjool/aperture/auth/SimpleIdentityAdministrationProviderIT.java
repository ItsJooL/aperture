package com.itsjool.aperture.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.spi.IdentityAdministrationConflictException;
import com.itsjool.aperture.spi.RoleCatalog;
import com.itsjool.aperture.spi.TenantProvisioningCommand;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class SimpleIdentityAdministrationProviderIT {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private PasswordEncoder passwords;
    private SimpleIdentityAdministrationProvider provider;

    @BeforeAll
    static void migrate() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection();
             Liquibase liquibase = new Liquibase(
                     "db/changelog/aperture-framework-tables.xml",
                     new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            liquibase.update();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TRIGGER IF EXISTS fail_provisioning_user ON aperture_users");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_provisioning_user()");
        jdbc.update("DELETE FROM aperture_refresh_tokens");
        jdbc.update("DELETE FROM aperture_user_roles");
        jdbc.update("DELETE FROM aperture_tenant_admins");
        jdbc.update("DELETE FROM aperture_personal_api_keys");
        jdbc.update("DELETE FROM aperture_users");
        jdbc.update("DELETE FROM aperture_service_account_roles");
        jdbc.update("DELETE FROM aperture_service_accounts");
        jdbc.update("DELETE FROM aperture_roles");
        jdbc.update("DELETE FROM aperture_global_settings");
        jdbc.update("DELETE FROM aperture_tenant_settings");
        jdbc.update("DELETE FROM aperture_tenants");
        passwords = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        provider = provider();
    }

    @Test
    void atomicallyCreatesTenantAllDeclaredRolesActiveAdminAndAssignment() {
        var result = provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));

        assertThat(result.tenant().status()).isEqualTo("ACTIVE");
        assertThat(result.initialAdmin().status()).isEqualTo("ACTIVE");
        assertThat(result.initialAdmin().superAdmin()).isFalse();
        assertThat(result.initialAdmin().profile())
                .containsEntry("department", "operations").containsEntry("nullable", null);
        assertThat(result.roles()).containsExactly("Accountant", "TenantAdmin", "Viewer");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aperture_tenants", Integer.class)).isOne();
        assertThat(jdbc.queryForList(
                "SELECT role_name FROM aperture_roles WHERE tenant_id = 'tenant-1' ORDER BY role_name",
                String.class)).containsExactly("Accountant", "TenantAdmin", "Viewer");
        assertThat(jdbc.queryForList(
                "SELECT role_name FROM aperture_user_roles WHERE tenant_id = 'tenant-1' AND user_id = 'admin-1' ORDER BY role_name",
                String.class)).containsExactly("TenantAdmin", "Viewer");
        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM aperture_users WHERE id = 'admin-1'", String.class);
        assertThat(hash).doesNotContain("correct horse battery staple");
        assertThat(passwords.matches("correct horse battery staple", hash)).isTrue();
        String department = jdbc.queryForObject(
                "SELECT profile->>'department' FROM aperture_users WHERE id = 'admin-1'", String.class);
        assertThat(department).isEqualTo("operations");
        assertThat(jdbc.queryForObject(
                "SELECT profile ? 'nullable' FROM aperture_users WHERE id = 'admin-1'", Boolean.class))
                .isTrue();
    }

    @Test
    void exactRepeatReturnsExistingLogicalResultWithoutDuplicates() {
        TenantProvisioningCommand command = command("tenant-1", "Tenant One", "admin-1", "admin");
        var first = provider.provisionTenant(command);
        var second = provider.provisionTenant(command);

        assertThat(second).isEqualTo(first);
        assertCounts(1, 3, 1, 2);
    }

    @Test
    void rejectsConflictingRepeatAndDuplicateUsernameWithoutPartialState() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));

        assertThatThrownBy(() -> provider.provisionTenant(
                command("tenant-1", "Changed", "admin-1", "admin")))
                .isInstanceOf(IdentityAdministrationConflictException.class)
                .hasMessageContaining("tenant-1");
        assertThatThrownBy(() -> provider.provisionTenant(
                command("tenant-2", "Tenant Two", "admin-2", "admin")))
                .isInstanceOf(IdentityAdministrationConflictException.class)
                .hasMessageContaining("username");
        assertCounts(1, 3, 1, 2);
    }

    @Test
    void rejectsDuplicateAdminIdAcrossTenantsWithoutCrossTenantAssignment() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));

        assertThatThrownBy(() -> provider.provisionTenant(
                command("tenant-2", "Tenant Two", "admin-1", "other-admin")))
                .isInstanceOf(IdentityAdministrationConflictException.class)
                .hasMessageContaining("user id");
        assertCounts(1, 3, 1, 2);
    }

    @Test
    void validatesAllInputBeforeWriting() {
        var invalid = new TenantProvisioningCommand(
                "tenant-invalid", " ", "admin-invalid", "admin", "secret", Map.of(), Map.of());

        assertThatThrownBy(() -> provider.provisionTenant(invalid))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant name");
        assertCounts(0, 0, 0, 0);
    }

    @Test
    void rejectsUnknownBootstrapRolesBeforePersistence() {
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(() -> new SimpleIdentityAdministrationProvider(
                new NamedParameterJdbcTemplate(dataSource), transactions, passwords,
                new RoleCatalog(List.of("TenantAdmin", "UnknownRole"), List.of("TenantAdmin", "Viewer")),
                new ObjectMapper(), null, null, java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultRoles must all be declared");
        assertCounts(0, 0, 0, 0);
    }

    @Test
    void rollsBackTenantAndRolesWhenDatabaseFailsBeforeAdminInsert() {
        jdbc.execute("""
                CREATE FUNCTION fail_provisioning_user() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'forced provisioning failure'; END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_provisioning_user BEFORE INSERT ON aperture_users
                FOR EACH ROW EXECUTE FUNCTION fail_provisioning_user()
                """);

        assertThatThrownBy(() -> provider.provisionTenant(
                command("tenant-rollback", "Rollback", "admin-rollback", "rollback-admin")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertCounts(0, 0, 0, 0);
    }

    @Test
    void concurrentIdenticalCommandsProduceOneLogicalProvisioning() throws Exception {
        TenantProvisioningCommand command = command("tenant-race", "Race", "admin-race", "race-admin");
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> provision = () -> provider.provisionTenant(command);
            var futures = executor.invokeAll(List.of(provision, provision));
            assertThat(futures.get(0).get()).isEqualTo(futures.get(1).get());
        }
        assertCounts(1, 3, 1, 2);
    }

    @Test
    void getTenantReturnsEmptyForUnknown() {
        assertThat(provider.getTenant("unknown")).isEmpty();
    }

    @Test
    void getTenantReturnsProvisionedTenant() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        var tenant = provider.getTenant("tenant-1");
        assertThat(tenant).isPresent();
        assertThat(tenant.get().id()).isEqualTo("tenant-1");
        assertThat(tenant.get().name()).isEqualTo("Tenant One");
        assertThat(tenant.get().status()).isEqualTo("ACTIVE");
    }

    @Test
    void listTenantsReturnsAllTenants() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        provider.provisionTenant(command("tenant-2", "Tenant Two", "admin-2", "admin2"));
        var result = provider.listTenants(0, 20);
        assertThat(result.items()).hasSize(2);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting("id").containsExactlyInAnyOrder("tenant-1", "tenant-2");
    }

    @Test
    void updateTenantStatusChangesStatusAndRequiresTenantId() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        provider.updateTenantStatus("tenant-1", "SUSPENDED");
        assertThat(provider.getTenant("tenant-1").get().status()).isEqualTo("SUSPENDED");

        assertThatThrownBy(() -> provider.updateTenantStatus("unknown", "ACTIVE"))
                .isInstanceOf(com.itsjool.aperture.spi.IdentityAdministrationNotFoundException.class);
    }


    @Test
    void createUserAddsUserToTenant() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        var cmd = new com.itsjool.aperture.spi.UserCreateCommand("tenant-1", "user-1", "testuser", "secret", Map.of("attr1", "val1"), Map.of(), false);
        var user = provider.createUser(cmd);
        assertThat(user.id()).isEqualTo("user-1");
        assertThat(user.username()).isEqualTo("testuser");
        assertThat(user.tenantId()).isEqualTo("tenant-1");
        assertThat(user.status()).isEqualTo("ACTIVE");
        assertThat(user.profile()).containsEntry("attr1", "val1");
    }

    @Test
    void listUsersReturnsTenantUsers() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        provider.createUser(new com.itsjool.aperture.spi.UserCreateCommand("tenant-1", "user-1", "testuser", "secret", Map.of(), Map.of(), false));
        var result = provider.listUsers(new com.itsjool.aperture.spi.UserListQuery("tenant-1", null, 0, 20));
        assertThat(result.items()).hasSize(2); // initial admin + new user
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting("id").containsExactlyInAnyOrder("admin-1", "user-1");
    }

    @Test
    void updateUserStatusChangesStatus() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        provider.updateUserStatus("tenant-1", "admin-1", "SUSPENDED");
        var result = provider.listUsers(new com.itsjool.aperture.spi.UserListQuery("tenant-1", null, 0, 20));
        assertThat(result.items()).extracting("status").containsOnly("SUSPENDED");
    }

    @Test
    void replaceUserRolesReplacesAllRoles() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        provider.replaceUserRoles("tenant-1", "admin-1", List.of("Accountant"));
        assertThat(jdbc.queryForList(
                "SELECT role_name FROM aperture_user_roles WHERE tenant_id = 'tenant-1' AND user_id = 'admin-1' ORDER BY role_name",
                String.class)).containsExactly("Accountant");
    }

    @Test
    void userOperations() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        var cmd = new com.itsjool.aperture.spi.ServiceAccountCreateCommand("tenant-1", "user-1", "client-1", List.of("Accountant"), null, Map.of());
        var result = provider.createServiceAccount(cmd);
        assertThat(result.record().id()).isEqualTo("user-1");
        assertThat(result.record().clientId()).isEqualTo("client-1");
        assertThat(result.record().status()).isEqualTo("ACTIVE");
        assertThat(result.secret()).isNotBlank();

        var list = provider.listServiceAccounts("tenant-1");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo("user-1");

        provider.disableServiceAccount("tenant-1", "user-1");
        assertThat(provider.listServiceAccounts("tenant-1").get(0).status()).isEqualTo("REVOKED");
    }

    @Test
    void apiKeyOperations() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        var cmd = new com.itsjool.aperture.spi.ApiKeyCreateCommand("tenant-1", "admin-1", "test-key", null, false, List.of(), Map.of());
        var result = provider.createApiKey(cmd);
        assertThat(result.record().userId()).isEqualTo("admin-1");
        assertThat(result.record().status()).isEqualTo("ACTIVE");
        assertThat(result.secret()).isNotBlank();

        var list = provider.listApiKeys("tenant-1");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo(result.record().id());

        provider.disableApiKey("tenant-1", result.record().id());
        assertThat(provider.listApiKeys("tenant-1").get(0).status()).isEqualTo("DISABLED");
    }

    @Test
    void tenantDisabledPersonalApiKeySettingsRejectCreation() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        provider.updatePersonalApiKeySettings("tenant-1",
                new com.itsjool.aperture.spi.PersonalApiKeySettings(false, null, null, null));

        var cmd = new com.itsjool.aperture.spi.ApiKeyCreateCommand("tenant-1", "admin-1", "test-key", null, false, List.of(), Map.of());

        assertThatThrownBy(() -> provider.createApiKey(cmd))
                .isInstanceOf(com.itsjool.aperture.spi.IdentityAdministrationValidationException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void globalDisabledPersonalApiKeySettingsRejectCreation() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        provider.updateGlobalPersonalApiKeySettings(
                new com.itsjool.aperture.spi.PersonalApiKeySettings(false, null, null, null));

        var cmd = new com.itsjool.aperture.spi.ApiKeyCreateCommand("tenant-1", "admin-1", "test-key", null, false, List.of(), Map.of());

        assertThatThrownBy(() -> provider.createApiKey(cmd))
                .isInstanceOf(com.itsjool.aperture.spi.IdentityAdministrationValidationException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void defaultExpiryComesFromTenantPersonalApiKeySettings() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        provider.updatePersonalApiKeySettings("tenant-1",
                new com.itsjool.aperture.spi.PersonalApiKeySettings(null, false, 30, 90));

        var cmd = new com.itsjool.aperture.spi.ApiKeyCreateCommand("tenant-1", "admin-1", "test-key", null, false, List.of(), Map.of());
        var result = provider.createApiKey(cmd);

        assertThat(result.record().expiresAt()).isNotNull();
        assertThat(java.time.Duration.between(java.time.Instant.now(), result.record().expiresAt()).toDays())
                .isBetween(29L, 30L);
    }

    @Test
    void nonExpiringPersonalApiKeysRequireTenantPolicy() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        provider.updatePersonalApiKeySettings("tenant-1",
                new com.itsjool.aperture.spi.PersonalApiKeySettings(null, false, null, null));

        var cmd = new com.itsjool.aperture.spi.ApiKeyCreateCommand("tenant-1", "admin-1", "test-key", null, true, List.of(), Map.of());

        assertThatThrownBy(() -> provider.createApiKey(cmd))
                .isInstanceOf(com.itsjool.aperture.spi.IdentityAdministrationValidationException.class)
                .hasMessageContaining("Non-expiring");
    }

    @Test
    void corruptPersonalApiKeySettingsFailClosed() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        jdbc.update("""
                INSERT INTO aperture_tenant_settings (tenant_id, settings, updated_at)
                VALUES ('tenant-1', '{"personalApiKeys":{"enabled":"sometimes"}}'::jsonb, NOW())
                ON CONFLICT (tenant_id) DO UPDATE SET settings = EXCLUDED.settings, updated_at = NOW()
                """);

        var cmd = new com.itsjool.aperture.spi.ApiKeyCreateCommand("tenant-1", "admin-1", "test-key", null, false, List.of(), Map.of());

        assertThatThrownBy(() -> provider.createApiKey(cmd))
                .isInstanceOf(com.itsjool.aperture.spi.IdentityAdministrationValidationException.class)
                .hasMessageContaining("settings");
    }

    @Test
    void crossTenantCollisionsForUpdatesAndDisables() {
        provider.provisionTenant(command("tenant-1", "Tenant One", "admin-1", "admin"));
        provider.provisionTenant(command("tenant-2", "Tenant Two", "admin-2", "admin2"));

        provider.createUser(new com.itsjool.aperture.spi.UserCreateCommand("tenant-1", "user-1", "testuser", "secret", Map.of(), Map.of(), false));
        var saResult = provider.createServiceAccount(new com.itsjool.aperture.spi.ServiceAccountCreateCommand("tenant-1", "user-1", "client-1", List.of(), null, Map.of()));
        var apiKeyResult = provider.createApiKey(new com.itsjool.aperture.spi.ApiKeyCreateCommand("tenant-1", "user-1", "test-key", null, false, List.of(), Map.of()));

        assertThatThrownBy(() -> provider.updateUserStatus("tenant-2", "user-1", "SUSPENDED"))
                .isInstanceOf(com.itsjool.aperture.spi.IdentityAdministrationNotFoundException.class);
        assertThatThrownBy(() -> provider.replaceUserRoles("tenant-2", "user-1", List.of()))
                .isInstanceOf(com.itsjool.aperture.spi.IdentityAdministrationNotFoundException.class);
        assertThatThrownBy(() -> provider.disableServiceAccount("tenant-2", "user-1"))
                .isInstanceOf(com.itsjool.aperture.spi.IdentityAdministrationNotFoundException.class);
        assertThatThrownBy(() -> provider.disableApiKey("tenant-2", apiKeyResult.record().id()))
                .isInstanceOf(com.itsjool.aperture.spi.IdentityAdministrationNotFoundException.class);
        assertThatThrownBy(() -> provider.createApiKey(new com.itsjool.aperture.spi.ApiKeyCreateCommand("tenant-2", "user-1", "test-key", null, true, List.of(), Map.of())))
                .isInstanceOf(com.itsjool.aperture.spi.IdentityAdministrationNotFoundException.class);

        // Ensure they are not modified
        assertThat(provider.listUsers(new com.itsjool.aperture.spi.UserListQuery("tenant-1", null, 0, 20)).items().stream().filter(u -> u.id().equals("user-1")).findFirst().get().status()).isEqualTo("ACTIVE");
        assertThat(provider.listServiceAccounts("tenant-1").get(0).status()).isEqualTo("ACTIVE");
        assertThat(provider.listApiKeys("tenant-1").get(0).status()).isEqualTo("ACTIVE");
    }

    private SimpleIdentityAdministrationProvider provider() {
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var clock = java.time.Clock.systemUTC();
        var secureRandom = new java.security.SecureRandom();
        var apiKeyService = new ApiKeyService(jdbc, transactions, clock, secureRandom, new ObjectMapper());
        var userIssuer = new SimpleServiceAccountIssuer(jdbc, org.mockito.Mockito.mock(JwtTokenService.class), passwords, clock, secureRandom, new ObjectMapper());
        return new SimpleIdentityAdministrationProvider(
                new NamedParameterJdbcTemplate(dataSource), transactions, passwords,
                new RoleCatalog(List.of("TenantAdmin", "Viewer"),
                        List.of("Accountant", "TenantAdmin", "Viewer")),
                new ObjectMapper(), apiKeyService, userIssuer, java.util.Set.of());
    }

    private static TenantProvisioningCommand command(
            String tenantId, String tenantName, String userId, String username) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("department", "operations");
        attributes.put("nullable", null);
        return new TenantProvisioningCommand(tenantId, tenantName, userId, username,
                "correct horse battery staple", attributes, Map.of());
    }

    private void assertCounts(int tenants, int roles, int users, int assignments) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aperture_tenants", Integer.class))
                .isEqualTo(tenants);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aperture_roles", Integer.class))
                .isEqualTo(roles);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aperture_users", Integer.class))
                .isEqualTo(users);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aperture_user_roles", Integer.class))
                .isEqualTo(assignments);
    }
}
