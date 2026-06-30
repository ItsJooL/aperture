package com.itsjool.aperture.starter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.liquibase.change-log=classpath:/db/changelog/aperture-framework-tables.xml",
        "aperture.auth.refresh-duration=PT1H"
})
@Testcontainers
class IdentityAdministrationComponentTest {

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext configurableApplicationContext) {
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("aperture.auth.jwt.secret", () -> "very-secret-key-that-is-at-least-32-bytes-long");
        registry.add("aperture.auth.jwt.issuer", () -> "aperture-test");
        registry.add("aperture.auth.jwt.audience", () -> "aperture-api");
        registry.add("aperture.auth.jwt.access-duration", () -> "PT5M");
    }

    @SpringBootApplication(scanBasePackages = "com.itsjool.aperture")
    static class TestApp {}

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM aperture_refresh_tokens");
        jdbcTemplate.execute("DELETE FROM aperture_user_roles");
        jdbcTemplate.execute("DELETE FROM aperture_personal_api_keys");
        jdbcTemplate.execute("DELETE FROM aperture_service_account_roles");
        jdbcTemplate.execute("DELETE FROM aperture_service_accounts");
        jdbcTemplate.execute("DELETE FROM aperture_roles");
        jdbcTemplate.execute("DELETE FROM aperture_tenant_admins");
        jdbcTemplate.execute("DELETE FROM aperture_users");
        jdbcTemplate.execute("DELETE FROM aperture_tenants");
        
        // Ensure SuperAdmin exists
        String hash = passwordEncoder.encode("superpassword");
        jdbcTemplate.update("INSERT INTO aperture_users (id, username, password_hash, tenant_id, status, super_admin) VALUES (?, ?, ?, NULL, 'ACTIVE', true)",
                UUID.randomUUID().toString(), "superadmin", hash);
    }

    private String getToken(String username, String password) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/auth/login",
                Map.of("username", username, "password", password),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("accessToken");
    }

    @Test
    void testListTenantsUnauthenticated() {
        ResponseEntity<String> response = restTemplate.getForEntity("/manage/tenants", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void testProvisionAndListTenantsAsSuperAdmin() {
        String token = getToken("superadmin", "superpassword");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        
        // List tenants
        ResponseEntity<Map> listResponse = restTemplate.exchange(
                "/manage/tenants", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((java.util.List<?>) listResponse.getBody().get("items")).isEmpty();

        // Provision tenant
        Map<String, Object> req = Map.of(
                "tenantName", "Test Tenant",
                "initialAdminUsername", "tenantadmin",
                "initialAdminPassword", "tenantpassword"
        );
        ResponseEntity<Map> provisionResponse = restTemplate.exchange(
                "/manage/tenants", HttpMethod.POST, new HttpEntity<>(req, headers), Map.class);
        assertThat(provisionResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Verify tenant is listed
        listResponse = restTemplate.exchange(
                "/manage/tenants", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat((java.util.List<?>) listResponse.getBody().get("items")).hasSize(1);
    }

    @Test
    void testTenantAdminAccessControl() {
        String superAdminToken = getToken("superadmin", "superpassword");
        HttpHeaders superHeaders = new HttpHeaders();
        superHeaders.setBearerAuth(superAdminToken);
        
        // Provision tenant 1
        Map<String, Object> req1 = Map.of(
                "tenantName", "Tenant 1",
                "initialAdminUsername", "t1admin",
                "initialAdminPassword", "t1password"
        );
        ResponseEntity<Map> res1 = restTemplate.exchange(
                "/manage/tenants", HttpMethod.POST, new HttpEntity<>(req1, superHeaders), Map.class);
        String tenant1Id = (String) ((Map) res1.getBody().get("tenant")).get("id");

        // Provision tenant 2
        Map<String, Object> req2 = Map.of(
                "tenantName", "Tenant 2",
                "initialAdminUsername", "t2admin",
                "initialAdminPassword", "t2password"
        );
        ResponseEntity<Map> res2 = restTemplate.exchange(
                "/manage/tenants", HttpMethod.POST, new HttpEntity<>(req2, superHeaders), Map.class);
        String tenant2Id = (String) ((Map) res2.getBody().get("tenant")).get("id");

        // Login as t1admin
        String t1Token = getToken("t1admin", "t1password");
        HttpHeaders t1Headers = new HttpHeaders();
        t1Headers.setBearerAuth(t1Token);

        // t1admin accesses t1 users -> OK
        ResponseEntity<Map> usersRes = restTemplate.exchange(
                "/manage/tenants/" + tenant1Id + "/users", HttpMethod.GET, new HttpEntity<>(t1Headers), Map.class);
        assertThat(usersRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        // t1admin accesses t2 users -> 403 FORBIDDEN
        ResponseEntity<java.util.Map> t2UsersRes = restTemplate.exchange(
                "/manage/tenants/" + tenant2Id + "/users", HttpMethod.GET, new HttpEntity<>(t1Headers), java.util.Map.class);
        assertThat(t2UsersRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        
        // t1admin accesses t1 service-accounts -> OK
        ResponseEntity<java.util.List> saRes = restTemplate.exchange(
                "/manage/tenants/" + tenant1Id + "/service-accounts", HttpMethod.GET, new HttpEntity<>(t1Headers), java.util.List.class);
        assertThat(saRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        // t1admin accesses t2 service-accounts -> 403 FORBIDDEN
        ResponseEntity<java.util.Map> t2SaRes = restTemplate.exchange(
                "/manage/tenants/" + tenant2Id + "/service-accounts", HttpMethod.GET, new HttpEntity<>(t1Headers), java.util.Map.class);
        assertThat(t2SaRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test
    void testMutationsReturnUpdatedRecords() {
        String superAdminToken = getToken("superadmin", "superpassword");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(superAdminToken);

        HttpHeaders mutationHeaders = new HttpHeaders();
        mutationHeaders.setBearerAuth(superAdminToken);
        mutationHeaders.set("If-Match", "\"1\"");
        
        // 1. Provision a tenant
        Map<String, Object> req = Map.of(
                "tenantName", "Mutation Tenant",
                "initialAdminUsername", "mutadmin",
                "initialAdminPassword", "mutpassword"
        );
        ResponseEntity<Map> res = restTemplate.exchange(
                "/manage/tenants", HttpMethod.POST, new HttpEntity<>(req, headers), Map.class);
        String tenantId = (String) ((Map) res.getBody().get("tenant")).get("id");

        // 2. Create user
        Map<String, Object> userReq = Map.of(
                "username", "mutuser",
                "password", "mutuserpass"
        );
        ResponseEntity<Map> userRes = restTemplate.exchange(
                "/manage/tenants/" + tenantId + "/users", HttpMethod.POST, new HttpEntity<>(userReq, headers), Map.class);
        String userId = (String) userRes.getBody().get("id");

        // 3. Test replaceUserRoles
        java.util.List<String> roles = java.util.List.of("Viewer");
        ResponseEntity<Map> roleRes = restTemplate.exchange(
                "/manage/tenants/" + tenantId + "/users/" + userId + "/roles", HttpMethod.PUT, new HttpEntity<>(Map.of("roleNames", roles), mutationHeaders), Map.class);
        assertThat(roleRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(roleRes.getBody().get("id")).isEqualTo(userId);

        // 4. Create Service Account
        Map<String, Object> saReq = Map.of(
                "clientId", "test-client",
                "roleNames", java.util.List.of("Viewer")
        );
        ResponseEntity<Map> saRes = restTemplate.exchange(
                "/manage/tenants/" + tenantId + "/service-accounts", HttpMethod.POST, new HttpEntity<>(saReq, headers), Map.class);
        String saId = (String) ((Map) saRes.getBody().get("record")).get("id");

        // 5. Admins can inspect personal API key metadata but cannot create raw user keys.
        ResponseEntity<java.util.List> personalKeysRes = restTemplate.exchange(
                "/manage/tenants/" + tenantId + "/personal-api-keys", HttpMethod.GET, new HttpEntity<>(mutationHeaders), java.util.List.class);
        assertThat(personalKeysRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 6. Test disableServiceAccount
        ResponseEntity<Map> disableSaRes = restTemplate.exchange(
                "/manage/tenants/" + tenantId + "/service-accounts/" + saId + "/disable", HttpMethod.POST, new HttpEntity<>(null, mutationHeaders), Map.class);
        assertThat(disableSaRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disableSaRes.getBody().get("id")).isEqualTo(saId);
        assertThat(disableSaRes.getBody().get("status")).isEqualTo("REVOKED");
    }
}
