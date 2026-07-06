package com.itsjool.aperture.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata;
import com.itsjool.aperture.runtime.config.TenancyMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class NoneTenancyModeComponentTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("aperture")
            .withUsername("aperture")
            .withPassword("password");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("aperture.hooks.base-url", () -> "http://127.0.0.1:8080");
        registry.add("aperture.rate-limit.backend", () -> "valkey");
        registry.add("aperture.rate-limit.valkey.host", () -> DemoApplicationTestSupport.valkey.getHost());
        registry.add("aperture.rate-limit.valkey.port", () -> DemoApplicationTestSupport.valkey.getMappedPort(6379));
    }

    @MockBean
    private ApertureRuntimeMetadata apertureRuntimeMetadata;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String superAdminId;
    private String superAdminUsername;
    private static final String SUPER_ADMIN_PASSWORD = "none-mode-super-pass";

    @BeforeEach
    void setUp() {
        when(apertureRuntimeMetadata.tenancyMode()).thenReturn(TenancyMode.NONE);
        when(apertureRuntimeMetadata.activeVersions()).thenReturn(List.of("1", "2"));
        when(apertureRuntimeMetadata.defaultRoles()).thenReturn(List.of("TenantAdmin", "Accountant", "Viewer"));
        when(apertureRuntimeMetadata.declaredRoles()).thenReturn(List.of("TenantAdmin", "Accountant", "Viewer"));
        when(apertureRuntimeMetadata.lockingEntities()).thenReturn(Set.of());
        when(apertureRuntimeMetadata.roleCatalog()).thenReturn(
                new com.itsjool.aperture.spi.RoleCatalog(
                        List.of("TenantAdmin", "Accountant", "Viewer"),
                        List.of("TenantAdmin", "Accountant", "Viewer")));

        superAdminId = UUID.randomUUID().toString();
        superAdminUsername = "none-superadmin-" + superAdminId.substring(0, 8) + "@example.com";
        String hash = passwordEncoder.encode(SUPER_ADMIN_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO aperture_users (id, username, password_hash, tenant_id, status, super_admin) VALUES (?, ?, ?, NULL, 'ACTIVE', true)",
                superAdminId, superAdminUsername, hash);
    }

    private void cleanupUser(String userId) {
        jdbcTemplate.update("DELETE FROM aperture_refresh_tokens WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM aperture_users WHERE id = ?", userId);
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = MAPPER.readTree(body);
        return "Bearer " + root.get("accessToken").asText();
    }

    @Test
    void getTenantsReturns404InNoneMode() throws Exception {
        try {
            String token = loginAndGetToken(superAdminUsername, SUPER_ADMIN_PASSWORD);
            mockMvc.perform(get("/manage/tenants")
                    .header("Authorization", token))
                    .andExpect(status().isNotFound());
        } finally {
            cleanupUser(superAdminId);
        }
    }

    @Test
    void postTenantsReturns404InNoneMode() throws Exception {
        try {
            String token = loginAndGetToken(superAdminUsername, SUPER_ADMIN_PASSWORD);
            mockMvc.perform(post("/manage/tenants")
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tenantName\":\"test\",\"initialAdminUsername\":\"admin\",\"initialAdminPassword\":\"password\"}"))
                    .andExpect(status().isNotFound());
        } finally {
            cleanupUser(superAdminId);
        }
    }

    @Test
    void superAdminUserLoginWorksInNoneMode() throws Exception {
        try {
            mockMvc.perform(post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + superAdminUsername + "\",\"password\":\"" + SUPER_ADMIN_PASSWORD + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").exists());
        } finally {
            cleanupUser(superAdminId);
        }
    }
}
