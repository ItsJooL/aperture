package com.itsjool.aperture.singletenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SingleTenantComponentTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("aperture")
            .withUsername("aperture")
            .withPassword("password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("aperture.hooks.base-url", () -> "http://127.0.0.1:19999");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SHADOW_TENANT_ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String SUPERADMIN_USERNAME = "superadmin@aperture.local";
    private static final String SUPERADMIN_PASSWORD = "test-superadmin-pass";
    private static final String ADMIN_USERNAME = "admin@notes-demo.local";
    private static final String ADMIN_PASSWORD = "test-admin-pass";
    private static final String READONLY_USERNAME = "readonly@notes-demo.local";
    private static final String READONLY_PASSWORD = "test-readonly-pass";

    @BeforeEach
    void seedUsers() {
        jdbcTemplate.update("DELETE FROM aperture_notes WHERE true");

        // Framework super-admin (no tenant, super_admin=true)
        jdbcTemplate.update(
            "INSERT INTO aperture_users (id, username, password_hash, tenant_id, status, super_admin) " +
            "VALUES (?, ?, ?, NULL, 'ACTIVE', true) ON CONFLICT (id) DO NOTHING",
            "00000000-0000-0000-0000-000000000001", SUPERADMIN_USERNAME,
            passwordEncoder.encode(SUPERADMIN_PASSWORD));

        // Shadow tenant for domain role resolution. In NONE mode the REST API for tenant
        // management returns 404, but the underlying framework tables still exist. We create
        // a tenant here only so that AuthUserService can resolve roles for domain test users.
        jdbcTemplate.update(
            "INSERT INTO aperture_tenants (id, name, status) VALUES (?, ?, 'ACTIVE') ON CONFLICT (id) DO NOTHING",
            SHADOW_TENANT_ID, "shadow-test-tenant");

        jdbcTemplate.update(
            "INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES (?, ?, 'Admin') ON CONFLICT DO NOTHING",
            "bbbbbbbb-0000-0000-0000-000000000001", SHADOW_TENANT_ID);
        jdbcTemplate.update(
            "INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES (?, ?, 'ReadOnly') ON CONFLICT DO NOTHING",
            "bbbbbbbb-0000-0000-0000-000000000002", SHADOW_TENANT_ID);

        jdbcTemplate.update(
            "INSERT INTO aperture_users (id, username, password_hash, tenant_id, status, super_admin) " +
            "VALUES (?, ?, ?, ?, 'ACTIVE', false) ON CONFLICT (id) DO NOTHING",
            "cccccccc-0000-0000-0000-000000000001", ADMIN_USERNAME,
            passwordEncoder.encode(ADMIN_PASSWORD), SHADOW_TENANT_ID);
        jdbcTemplate.update(
            "INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) VALUES (?, ?, 'Admin') ON CONFLICT DO NOTHING",
            SHADOW_TENANT_ID, "cccccccc-0000-0000-0000-000000000001");

        jdbcTemplate.update(
            "INSERT INTO aperture_users (id, username, password_hash, tenant_id, status, super_admin) " +
            "VALUES (?, ?, ?, ?, 'ACTIVE', false) ON CONFLICT (id) DO NOTHING",
            "cccccccc-0000-0000-0000-000000000002", READONLY_USERNAME,
            passwordEncoder.encode(READONLY_PASSWORD), SHADOW_TENANT_ID);
        jdbcTemplate.update(
            "INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) VALUES (?, ?, 'ReadOnly') ON CONFLICT DO NOTHING",
            SHADOW_TENANT_ID, "cccccccc-0000-0000-0000-000000000002");
    }

    // Elide controllers are async — the initial MockMvc result is always 200 with async
    // started; this helper dispatches and returns the actual response.
    private ResultActions elide(MockHttpServletRequestBuilder builder) throws Exception {
        var result = mockMvc.perform(builder).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            return mockMvc.perform(asyncDispatch(result));
        }
        return mockMvc.perform(builder);
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + MAPPER.readTree(body).get("accessToken").asText();
    }

    @Test
    void bootstrapAdminCanLogin() throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + SUPERADMIN_USERNAME + "\",\"password\":\"" + SUPERADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(MAPPER.readTree(body).get("accessToken")).isNotNull();
    }

    @Test
    void adminCanCreateNote() throws Exception {
        String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        elide(post("/api/v1/notes")
                .header("Authorization", token)
                .contentType("application/vnd.api+json")
                .content("{\"data\":{\"type\":\"notes\",\"attributes\":{\"title\":\"Hello NONE mode\",\"content\":\"No tenants required\"}}}"))
                .andExpect(status().isCreated());
    }

    @Test
    void readOnlyUserCannotCreateNote() throws Exception {
        String token = login(READONLY_USERNAME, READONLY_PASSWORD);
        elide(post("/api/v1/notes")
                .header("Authorization", token)
                .contentType("application/vnd.api+json")
                .content("{\"data\":{\"type\":\"notes\",\"attributes\":{\"title\":\"Forbidden\",\"content\":\"ReadOnly cannot write\"}}}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantsEndpointReturns404() throws Exception {
        String token = login(SUPERADMIN_USERNAME, SUPERADMIN_PASSWORD);
        mockMvc.perform(get("/manage/tenants")
                .header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void noteSchemaHasNoTenantIdColumn() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns " +
            "WHERE table_name = 'aperture_notes' AND column_name = 'aperture_tenant_id'",
            Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void optimisticLocking_staleEtagReturns412() throws Exception {
        String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String vnd = "application/vnd.api+json";

        // Create a note
        String createResp = elide(post("/api/v1/notes")
                .header("Authorization", token)
                .contentType(vnd)
                .content("{\"data\":{\"type\":\"notes\",\"attributes\":{\"title\":\"Lock test\",\"content\":\"v0\"}}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String noteId = MAPPER.readTree(createResp).at("/data/id").asText();

        // Fetch ETag (version "0") via GET
        String etag = elide(get("/api/v1/notes/" + noteId)
                .header("Authorization", token)
                .accept(vnd))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        assertThat(etag).isNotNull();

        // First PATCH with correct ETag succeeds
        String body = "{\"data\":{\"id\":\"" + noteId + "\",\"type\":\"notes\",\"attributes\":{\"title\":\"v1\"}}}";
        elide(patch("/api/v1/notes/" + noteId)
                .header("Authorization", token)
                .header("If-Match", etag)
                .contentType(vnd)
                .content(body))
                .andExpect(status().is2xxSuccessful());

        // Second PATCH with stale ETag → 412 Precondition Failed
        elide(patch("/api/v1/notes/" + noteId)
                .header("Authorization", token)
                .header("If-Match", etag)
                .contentType(vnd)
                .content(body))
                .andExpect(status().isPreconditionFailed());
    }
}
