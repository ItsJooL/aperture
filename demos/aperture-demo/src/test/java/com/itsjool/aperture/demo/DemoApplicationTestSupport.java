package com.itsjool.aperture.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@Sql(scripts = {
    "classpath:init/tenants.sql",
    "classpath:init/service-accounts.sql",
    "classpath:init/users.sql",
    "classpath:init/domain.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class DemoApplicationTestSupport {

    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("aperture")
            .withUsername("aperture")
            .withPassword("password");

    @SuppressWarnings("resource")
    protected static final GenericContainer<?> valkey = new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.1.0"))
            .withExposedPorts(6379)
            .withCommand("valkey-server", "--save", "", "--appendonly", "no");

    protected static final okhttp3.mockwebserver.MockWebServer mockWebServer = new okhttp3.mockwebserver.MockWebServer();

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> HOOK_RESPONSE_OVERRIDES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.ConcurrentHashMap<String, String> HOOK_BODY_OVERRIDES =
            new java.util.concurrent.ConcurrentHashMap<>();

    protected static void overrideHookResponse(String path, int statusCode) {
        HOOK_RESPONSE_OVERRIDES.put(path, statusCode);
    }

    protected static void overrideHookResponseWithBody(String path, int statusCode, String body) {
        HOOK_RESPONSE_OVERRIDES.put(path, statusCode);
        HOOK_BODY_OVERRIDES.put(path, body);
    }

    protected static void clearHookOverrides() {
        HOOK_RESPONSE_OVERRIDES.clear();
        HOOK_BODY_OVERRIDES.clear();
    }

    static {
        try {
            mockWebServer.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        mockWebServer.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                String path = request.getPath();
                Integer override = HOOK_RESPONSE_OVERRIDES.get(path);
                int status = (override != null) ? override : 200;
                String body = HOOK_BODY_OVERRIDES.getOrDefault(path, "");
                return new okhttp3.mockwebserver.MockResponse()
                        .setResponseCode(status)
                        .setBody(body)
                        .addHeader("Content-Type", "application/json");
            }
        });

        postgres.start();
        valkey.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("aperture.hooks.base-url", () -> "http://127.0.0.1:" + mockWebServer.getPort());
        registry.add("aperture.rate-limit.backend", () -> "valkey");
        registry.add("aperture.rate-limit.valkey.host", valkey::getHost);
        registry.add("aperture.rate-limit.valkey.port", () -> valkey.getMappedPort(6379));
    }

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected String loginAs(String username, String password) {
        try {
            var result = mockMvc.perform(
                MockMvcRequestBuilders.post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
            String body = result.getResponse().getContentAsString();
            int status = result.getResponse().getStatus();
            if (status != 200) {
                throw new RuntimeException("Login endpoint returned status " + status + ": " + body);
            }
            JsonNode root = MAPPER.readTree(body);
            if (root.get("accessToken") == null) {
                throw new RuntimeException("Missing accessToken in response: " + body);
            }
            return "Bearer " + root.get("accessToken").asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Login failed for " + username + ": " + e.getMessage(), e);
        }
    }

    protected String getSuperAdminToken() {
        return loginAs("superadmin@framework.local", "password");
    }

    protected String getAcmeAdminToken() {
        return loginAs("admin@acme.com", "password");
    }

    protected String getGlobexAdminToken() {
        return loginAs("admin@globex.com", "password");
    }

    protected String getAcmeAccountantToken() {
        return loginAs("accountant@acme.com", "password");
    }

    protected String getAcmeViewerToken() {
        return loginAs("viewer@acme.com", "password");
    }

    protected org.springframework.test.web.servlet.ResultActions performElideRequest(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder requestBuilder) throws Exception {
        var actions = mockMvc.perform(requestBuilder);
        var result = actions.andReturn();
        if (result.getRequest().isAsyncStarted()) {
            Object asyncResult = result.getAsyncResult();
            System.out.println("DEBUG APERTURE: Async Result Type = " + (asyncResult != null ? asyncResult.getClass().getName() : "null"));
            System.out.println("DEBUG APERTURE: Async Result = " + asyncResult);
            var asyncActions = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result));
            return asyncActions;
        }
        return actions;
    }
}
