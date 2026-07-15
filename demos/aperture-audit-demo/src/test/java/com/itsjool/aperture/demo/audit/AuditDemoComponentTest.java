package com.itsjool.aperture.demo.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.runtime.audit.AuditBridge;
import com.sun.net.httpserver.HttpServer;
import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.ChangeSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
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
import java.time.LocalDateTime;
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

    private static final String SUPERADMIN_USERNAME = "superadmin@aperture.local";
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

    @Autowired
    private AutowireCapableBeanFactory beanFactory;

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

        // Change both a plain field (name) and the encrypted field (supplier_secret) in the same
        // PATCH: Elide's per-field UPDATE lifecycle hook fires once per changed field, so this
        // produces two separate audit rows below — one proving real before/after values still flow
        // for ordinary fields, the other proving the encrypted field's values arrive redacted.
        String updateBody = "{\"data\":{\"type\":\"products\",\"id\":\"" + productId
                + "\",\"attributes\":{\"name\":\"Audit Widget Updated\",\"supplier_secret\":\"contract floor 42 v2\"}}}";
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

        // Poll the JDBC audit log for the SAME PATCH's supplier_secret field change: it must show
        // the redaction sentinel end-to-end, not the plaintext "contract floor 42 v2" (or the prior
        // value) — proving default encrypted-field redaction (plan 033 / finding 1J) actually reaches
        // both the JDBC audit log and the downstream SIEM sink, not just one of them.
        JsonNode jdbcSecretDetails = null;
        for (int i = 0; i < 30 && jdbcSecretDetails == null; i++) {
            String auditBody = elide(get("/manage/audit?entity=Product&entityId=" + productId)
                            .header("Authorization", token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            for (JsonNode row : MAPPER.readTree(auditBody)) {
                if ("UPDATE".equals(row.path("operation").asText())) {
                    JsonNode details = detailsOf(row);
                    if ("supplier_secret".equals(details.path("fieldPath").asText())) {
                        jdbcSecretDetails = details;
                    }
                }
            }
            if (jdbcSecretDetails == null) {
                Thread.sleep(200);
            }
        }
        assertThat(jdbcSecretDetails)
                .as("JDBC audit log should contain the encrypted-field UPDATE row")
                .isNotNull();
        assertThat(jdbcSecretDetails.path("before").asText()).isEqualTo("[REDACTED]");
        assertThat(jdbcSecretDetails.path("after").asText()).isEqualTo("[REDACTED]");

        JsonNode siemSecretDetails = null;
        for (int i = 0; i < 30 && siemSecretDetails == null; i++) {
            for (String rawBody : siemRequestBodies) {
                for (JsonNode event : MAPPER.readTree(rawBody)) {
                    if ("Product".equals(event.path("entity").asText())
                            && "UPDATE".equals(event.path("operation").asText())
                            && productId.equals(event.path("entityId").asText())) {
                        JsonNode details = event.path("details");
                        if ("supplier_secret".equals(details.path("fieldPath").asText())) {
                            siemSecretDetails = details;
                        }
                    }
                }
            }
            if (siemSecretDetails == null) {
                Thread.sleep(200);
            }
        }
        assertThat(siemSecretDetails)
                .as("SIEM sink should also receive the encrypted-field UPDATE row redacted, not plaintext")
                .isNotNull();
        assertThat(siemSecretDetails.path("before").asText()).isEqualTo("[REDACTED]");
        assertThat(siemSecretDetails.path("after").asText()).isEqualTo("[REDACTED]");
    }

    /**
     * Regression test for the fix to {@code AuditBridge}'s constructor selection.
     *
     * <p>Elide instantiates the {@code AuditBridge} lifecycle hook from a class token
     * ({@code @LifeCycleHookBinding(hook = AuditBridge.class)}) through its own {@code Injector},
     * not by looking up the {@code @Bean auditBridge(...)} defined in
     * {@code ApertureSimpleAutoConfiguration} by type. Decompiling {@code elide-spring-boot-autoconfigure}
     * 7.1.17 confirms {@code Injector.instantiate(Class)} delegates directly to Spring's
     * {@code AutowireCapableBeanFactory#createBean(Class)}. Before this fix, none of
     * {@code AuditBridge}'s three constructors was marked {@code @Autowired}, so that
     * {@code createBean(Class)} call could not resolve a unique autowire candidate and fell back to
     * the no-arg constructor, which chains to a plain {@code new ObjectMapper()} with no
     * {@code JavaTimeModule} — silently degrading every {@code java.time} before/after value to
     * {@code {"detailsSerializationFailed":true}}. {@code AuditBridgeTest} documents that failure
     * mode but only ever constructs {@code AuditBridge} directly, bypassing Spring/Elide's injector
     * entirely, so it could never have caught this.
     *
     * <p>This test calls {@code AutowireCapableBeanFactory#createBean(AuditBridge.class)} directly —
     * the exact API Elide's injector calls — against this test's real Spring context, so the
     * resulting instance is wired exactly as Elide would wire it in production (with Boot's
     * JavaTimeModule-aware {@code ObjectMapper} bean available for autowiring).
     *
     * <p>Note on the approach: the review that flagged this bug suggested driving it end-to-end by
     * PATCHing a datetime/soft-delete field through the real HTTP stack. That path is blocked by a
     * separate, unrelated gap confirmed empirically while writing this test: Elide's JSON:API
     * attribute deserialization has no registered coercion from a JSON string to
     * {@code java.time.LocalDateTime} in this framework as currently wired — PATCHing a manifest
     * {@code datetime} field throws {@code IllegalArgumentException: Can not set
     * java.time.LocalDateTime field ... to java.lang.String} before the request ever reaches
     * {@code AuditBridge}. The soft-delete {@code deletedAt} field doesn't route around this either:
     * it is a synthetic field added outside the manifest fields loop in {@code CodeGenerator}, so it
     * never receives the per-field {@code UPDATE} {@code @LifeCycleHookBinding} that manifest fields
     * get, meaning patching it wouldn't reach any audit hook at all. Both are separate, pre-existing
     * gaps outside the scope of this fix, so this test exercises the real bug mechanism directly
     * instead.
     */
    @Test
    void elideInjectorConstructedAuditBridgeSerializesJavaTimeValuesWithoutFallback() throws Exception {
        AuditBridge bridge = beanFactory.createBean(AuditBridge.class);

        LocalDateTime before = LocalDateTime.of(2026, 1, 1, 9, 0, 0);
        LocalDateTime after = LocalDateTime.of(2026, 7, 7, 14, 45, 0);
        String entityId = "elide-injector-" + java.util.UUID.randomUUID();
        ChangeSpec changeSpec = new ChangeSpec(null, "lastRestockedAt", before, after);

        bridge.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, new AuditableEntityStub(entityId), null,
                java.util.Optional.of(changeSpec));

        // Poll the JDBC audit log directly for the UPDATE event this synthetic hook invocation
        // wrote. This deliberately bypasses the HTTP surface (unlike the test above): this test
        // doesn't need to exercise the rate-limited `/manage/audit` endpoint, and sharing that
        // endpoint's IP-keyed rate-limit bucket with the polling loop above (same Spring context,
        // same MockMvc-simulated IP, @DirtiesContext(AFTER_CLASS) keeps state across both tests in
        // this class) is exactly what caused this test to intermittently 429 when it first went
        // through /manage/audit like the other test does.
        JsonNode details = null;
        for (int i = 0; i < 30 && details == null; i++) {
            List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT details FROM aperture_audit_log WHERE entity = ? AND entity_id = ? AND operation = 'UPDATE'",
                    "AuditableEntityStub", entityId);
            if (!rows.isEmpty()) {
                details = detailsOf(MAPPER.valueToTree(rows.get(0)));
            }
            if (details == null) {
                Thread.sleep(200);
            }
        }
        assertThat(details)
                .as("JDBC audit log should contain the UPDATE event written via Elide's real "
                        + "injector-constructed AuditBridge")
                .isNotNull();
        assertThat(details.has("detailsSerializationFailed"))
                .as("java.time before/after values must not degrade to the serialization-failure fallback")
                .isFalse();
        assertThat(LocalDateTime.parse(details.path("before").asText())).isEqualTo(before);
        assertThat(LocalDateTime.parse(details.path("after").asText())).isEqualTo(after);
    }

    // Must be public: AuditBridge looks up getId() via reflection
    // (elideEntity.getClass().getMethod("getId")) without calling setAccessible(true), so a
    // non-public declaring class in a different package would make the invocation throw
    // IllegalAccessException (silently caught by AuditBridge, leaving entityId as "unknown").
    public static final class AuditableEntityStub {
        private final String id;

        public AuditableEntityStub(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }
}
