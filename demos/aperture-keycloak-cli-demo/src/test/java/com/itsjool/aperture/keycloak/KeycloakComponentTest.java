package com.itsjool.aperture.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class KeycloakComponentTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("aperture")
            .withUsername("aperture")
            .withPassword("password");

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.0.0")
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCommand("start-dev", "--import-realm")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("aperture-realm.json"),
                "/opt/keycloak/data/import/aperture-realm.json")
            // /realms/aperture returns 200 once Keycloak has finished importing the realm
            .waitingFor(Wait.forHttp("/realms/aperture")
                .forPort(8080)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("aperture.hooks.base-url", () -> "http://127.0.0.1:19999");
        registry.add("keycloak.jwks-uri", () ->
            "http://localhost:" + keycloak.getMappedPort(8080) + "/realms/aperture/protocol/openid-connect/certs");
    }

    @Autowired
    private MockMvc mockMvc;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String keycloakTokenUrl() {
        return "http://localhost:" + keycloak.getMappedPort(8080) + "/realms/aperture/protocol/openid-connect/token";
    }

    private String getKeycloakToken(String username, String password) throws IOException, InterruptedException {
        String form = "grant_type=password&client_id=aperture-api&username=" + username + "&password=" + password;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(keycloakTokenUrl()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Keycloak token request returned " + resp.statusCode() + ": " + resp.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = MAPPER.readValue(resp.body(), Map.class);
        return "Bearer " + body.get("access_token");
    }

    private ResultActions elide(MockHttpServletRequestBuilder builder) throws Exception {
        var result = mockMvc.perform(builder).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            return mockMvc.perform(asyncDispatch(result));
        }
        return mockMvc.perform(builder);
    }

    @Test
    void adminCanCreateProduct() throws Exception {
        String token = getKeycloakToken("admin@keycloak-demo.com", "Admin123!");
        elide(post("/api/v1/products")
                .header("Authorization", token)
                .contentType("application/vnd.api+json")
                .content("{\"data\":{\"type\":\"products\",\"attributes\":{\"name\":\"Test Product\",\"price\":9.99,\"sku\":\"TEST-001\"}}}"))
                .andExpect(status().isCreated());
    }

    @Test
    void userCannotCreateProduct() throws Exception {
        String token = getKeycloakToken("user@keycloak-demo.com", "User123!");
        elide(post("/api/v1/products")
                .header("Authorization", token)
                .contentType("application/vnd.api+json")
                .content("{\"data\":{\"type\":\"products\",\"attributes\":{\"name\":\"Forbidden\",\"price\":1.0,\"sku\":\"NOPE-001\"}}}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void userCanReadProducts() throws Exception {
        String token = getKeycloakToken("user@keycloak-demo.com", "User123!");
        elide(get("/api/v1/products")
                .header("Authorization", token)
                .accept("application/vnd.api+json"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidToken_returns401() throws Exception {
        elide(get("/api/v1/products")
                .header("Authorization", "Bearer not-a-valid-jwt")
                .accept("application/vnd.api+json"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noToken_returns401() throws Exception {
        elide(get("/api/v1/products")
                .accept("application/vnd.api+json"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void keycloakRolesMappedCorrectly() throws Exception {
        String adminToken = getKeycloakToken("admin@keycloak-demo.com", "Admin123!");
        String userToken  = getKeycloakToken("user@keycloak-demo.com",  "User123!");

        // Admin can create
        elide(post("/api/v1/products")
                .header("Authorization", adminToken)
                .contentType("application/vnd.api+json")
                .content("{\"data\":{\"type\":\"products\",\"attributes\":{\"name\":\"Role Check\",\"price\":1.0,\"sku\":\"ROLE-001\"}}}"))
                .andExpect(status().isCreated());

        // User cannot create
        elide(post("/api/v1/products")
                .header("Authorization", userToken)
                .contentType("application/vnd.api+json")
                .content("{\"data\":{\"type\":\"products\",\"attributes\":{\"name\":\"Deny\",\"price\":1.0,\"sku\":\"DENY-001\"}}}"))
                .andExpect(status().isForbidden());
    }
}
