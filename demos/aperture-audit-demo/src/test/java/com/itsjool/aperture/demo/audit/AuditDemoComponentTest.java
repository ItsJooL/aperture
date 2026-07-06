package com.itsjool.aperture.demo.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Exercises the full audit story end-to-end: a real mutation through Elide produces a
 * real before/after {@code details} payload (Part A), and the demo's composite
 * {@code AuditWriter} (Part B) delivers that same event to both the JDBC audit log and
 * an HTTP "SIEM" sink — a plain {@link HttpServer} stub standing in for the WireMock
 * container the docker-compose stack uses, so this test needs no Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class AuditDemoComponentTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("aperture")
            .withUsername("aperture")
            .withPassword("password");

    private static HttpServer siemStub;
    private static final List<String> siemRequestBodies = new CopyOnWriteArrayList<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SUPERADMIN_USERNAME = "superadmin@framework.local";
    private static final String SUPERADMIN_PASSWORD = "changeme-local-only";

    @BeforeAll
    static void startSiemStub() throws IOException {
        siemStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        siemStub.createContext("/siem/audit", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            siemRequestBodies.add(new String(bytes, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        siemStub.start();
    }

    @AfterAll
    static void stopSiemStub() {
        if (siemStub != null) {
            siemStub.stop(0);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("aperture.audit.webhook.url",
                () -> "http://localhost:" + siemStub.getAddress().getPort() + "/siem/audit");
        registry.add("aperture.audit.webhook.flush-interval", () -> "PT0.1S");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Elide controllers are async — the initial MockMvc result is always 200 with async
    // started; this helper dispatches and returns the actual response (same pattern as
    // the other demos' component tests).
    private ResultActions elide(MockHttpServletRequestBuilder builder) throws Exception {
        var result = mockMvc.perform(builder).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            return mockMvc.perform(asyncDispatch(result));
        }
        return mockMvc.perform(builder);
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + SUPERADMIN_USERNAME + "\",\"password\":\"" + SUPERADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + MAPPER.readTree(body).get("accessToken").asText();
    }

    private JsonNode detailsOf(JsonNode auditRow) throws Exception {
        JsonNode details = auditRow.get("details");
        // jdbcTemplate.queryForList maps a jsonb column to a PGobject, which Jackson
        // serializes as {"type":"jsonb","value":"<json string>"} rather than inlining it.
        if (details.has("value") && details.get("value").isTextual()) {
            return MAPPER.readTree(details.get("value").asText());
        }
        return details;
    }

    @Test
    void updateMutationCapturesRealBeforeAfterInBothJdbcAuditLogAndSiemSink() throws Exception {
        String token = login();
        String vnd = "application/vnd.api+json";

        String createBody = """
                {"data":{"type":"products","attributes":{
                  "name":"Audit Widget","sku":"AUDIT-CT-001","supplier_secret":"contract floor 42","price":19.99
                }}}""";
        String createResponse = elide(post("/api/v1/products")
                        .header("Authorization", token)
                        .contentType(vnd)
                        .accept(vnd)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String productId = MAPPER.readTree(createResponse).at("/data/id").asText();
        assertThat(productId).isNotBlank();

        // Encrypted field: ciphertext at rest, plaintext through the API (see README PII note).
        String rawSupplierSecret = jdbcTemplate.queryForObject(
                "SELECT supplier_secret FROM aperture_products WHERE id = ?::uuid", String.class, productId);
        assertThat(rawSupplierSecret).isNotBlank().doesNotContain("contract floor 42");

        String updateBody = "{\"data\":{\"type\":\"products\",\"id\":\"" + productId
                + "\",\"attributes\":{\"name\":\"Audit Widget Updated\"}}}";
        elide(patch("/api/v1/products/" + productId)
                        .header("Authorization", token)
                        .contentType(vnd)
                        .accept(vnd)
                        .content(updateBody))
                .andExpect(status().is2xxSuccessful());

        // Poll the JDBC audit log for the UPDATE event with real before/after values.
        JsonNode jdbcDetails = null;
        for (int i = 0; i < 30 && jdbcDetails == null; i++) {
            String auditBody = elide(get("/manage/audit?entity=Product&entityId=" + productId)
                            .header("Authorization", token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            for (JsonNode row : MAPPER.readTree(auditBody)) {
                if ("UPDATE".equals(row.path("operation").asText())) {
                    JsonNode details = detailsOf(row);
                    if ("name".equals(details.path("fieldPath").asText())
                            && "Audit Widget".equals(details.path("before").asText())
                            && "Audit Widget Updated".equals(details.path("after").asText())) {
                        jdbcDetails = details;
                    }
                }
            }
            if (jdbcDetails == null) {
                Thread.sleep(200);
            }
        }
        assertThat(jdbcDetails).as("JDBC audit log should contain the UPDATE before/after details").isNotNull();

        // Poll the SIEM stub for the same event, proving the composite writer fanned out.
        JsonNode siemDetails = null;
        for (int i = 0; i < 30 && siemDetails == null; i++) {
            for (String rawBody : siemRequestBodies) {
                for (JsonNode event : MAPPER.readTree(rawBody)) {
                    if ("Product".equals(event.path("entity").asText())
                            && "UPDATE".equals(event.path("operation").asText())
                            && productId.equals(event.path("entityId").asText())) {
                        JsonNode details = event.path("details");
                        if ("name".equals(details.path("fieldPath").asText())
                                && "Audit Widget Updated".equals(details.path("after").asText())) {
                            siemDetails = details;
                        }
                    }
                }
            }
            if (siemDetails == null) {
                Thread.sleep(200);
            }
        }
        assertThat(siemDetails).as("SIEM sink should receive the same UPDATE before/after details").isNotNull();
    }
}
