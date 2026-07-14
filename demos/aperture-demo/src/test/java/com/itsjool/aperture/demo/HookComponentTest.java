package com.itsjool.aperture.demo;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class HookComponentTest extends DemoApplicationTestSupport {

    private static final MediaType VNDAPI = MediaType.valueOf("application/vnd.api+json");

    @BeforeEach
    void drainStaleRequests() throws InterruptedException {
        // Drain any leftover MockWebServer requests from previous tests
        okhttp3.mockwebserver.RecordedRequest stale;
        while ((stale = mockWebServer.takeRequest(50, TimeUnit.MILLISECONDS)) != null) { /* discard */ }
    }

    @AfterEach
    void resetHooks() {
        clearHookOverrides();
    }

    @Test
    void precommitVetoRejectsInvoiceAndLeavesNoDB() throws Exception {
        overrideHookResponse("/hooks/validate-invoice", 400);

        Integer countBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_invoices", Integer.class);

        performElideRequest(post("/api/v1/invoices")
                .header("Authorization", getAcmeAccountantToken())
                .contentType(VNDAPI)
                .content("{\"data\": {\"type\": \"invoices\", \"attributes\": {\"amount\": 100, \"status\": \"DRAFT\"}, \"relationships\": {\"customer\": {\"data\": {\"type\": \"customers\", \"id\": \"00000000-0000-0000-0000-000000000001\"}}}}}"))
                .andExpect(status().is4xxClientError());

        Integer countAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_invoices", Integer.class);
        assertThat(countAfter).as("vetoed pre-commit hook must not persist the invoice").isEqualTo(countBefore);

        okhttp3.mockwebserver.RecordedRequest hookRequest = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(hookRequest).as("MockWebServer must have received the validate-invoice hook callback").isNotNull();
        assertThat(hookRequest.getPath()).isEqualTo("/hooks/validate-invoice");
        assertThat(hookRequest.getHeader("Authorization")).as("hook must never receive user credentials").isNull();
    }

    @Test
    void precommitSuccessCreatesInvoice() throws Exception {
        // MockWebServer returns 200 by default — hook succeeds, invoice is created
        Integer countBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_invoices", Integer.class);

        performElideRequest(post("/api/v1/invoices")
                .header("Authorization", getAcmeAccountantToken())
                .contentType(VNDAPI)
                .content("{\"data\": {\"type\": \"invoices\", \"attributes\": {\"amount\": 200, \"status\": \"DRAFT\"}, \"relationships\": {\"customer\": {\"data\": {\"type\": \"customers\", \"id\": \"00000000-0000-0000-0000-000000000001\"}}}}}"))
                .andExpect(status().isCreated());

        Integer countAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_invoices", Integer.class);
        assertThat(countAfter).as("successful hook must allow invoice to persist").isGreaterThan(countBefore);

        okhttp3.mockwebserver.RecordedRequest hookRequest = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(hookRequest).as("MockWebServer must have received the validate-invoice hook callback").isNotNull();
        assertThat(hookRequest.getPath()).isEqualTo("/hooks/validate-invoice");
    }

    @Test
    void hookDoesNotForwardUserCredentials() throws Exception {
        performElideRequest(post("/api/v1/invoices")
                .header("Authorization", getAcmeAccountantToken())
                .contentType(VNDAPI)
                .content("{\"data\": {\"type\": \"invoices\", \"attributes\": {\"amount\": 300, \"status\": \"DRAFT\"}, \"relationships\": {\"customer\": {\"data\": {\"type\": \"customers\", \"id\": \"00000000-0000-0000-0000-000000000001\"}}}}}"))
                .andExpect(status().isCreated());

        okhttp3.mockwebserver.RecordedRequest hookRequest = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(hookRequest).as("MockWebServer must have received the hook callback").isNotNull();
        assertThat(hookRequest.getHeader("Authorization")).as("hooks must never receive the caller's credentials").isNull();
        assertThat(hookRequest.getHeader("X-Hook-Secret")).as("hook request must include the signing header").isNotNull();
    }

    @Test
    void postcommitAsyncDoesNotBlockAndFiresCallback() throws Exception {
        long start = System.currentTimeMillis();

        performElideRequest(post("/api/v1/suppliers")
                .header("Authorization", getAcmeAdminToken())
                .contentType(VNDAPI)
                .content("{\"data\": {\"type\": \"suppliers\", \"attributes\": {\"company_name\": \"Hook Test Supplier\"}}}"))
                .andExpect(status().isCreated());

        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).as("async hook must not block the API response for more than 3s").isLessThan(3000);

        // Poll for the async hook callback (fires after HTTP response returns)
        okhttp3.mockwebserver.RecordedRequest hookRequest = pollForHookRequest("/hooks/notify-supplier", 5);
        assertThat(hookRequest).as("trigger hook must fire a real HTTP callback to MockWebServer").isNotNull();
        assertThat(hookRequest.getHeader("Authorization")).as("async hook must not forward user credentials").isNull();
    }

    @Test
    void mutateHookModifiesEntityAttribute() throws Exception {
        // Mutate hook returns attribute overrides — entity should be persisted with enriched values
        String enrichBody = "{\"data\":{\"attributes\":{\"name\":\"ENRICHED NAME\"}}}";
        overrideHookResponseWithBody("/hooks/enrich-customer", 200, enrichBody);

        performElideRequest(post("/api/v3/customers")
                .header("Authorization", getAcmeAdminToken())
                .contentType(VNDAPI)
                .content("{\"data\": {\"type\": \"customers\", \"attributes\": {\"name\": \"original\", \"email\": \"enrich@example.com\"}}}"))
                .andExpect(status().isCreated());

        okhttp3.mockwebserver.RecordedRequest hookRequest = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(hookRequest).as("enrich hook must be called").isNotNull();
        assertThat(hookRequest.getPath()).isEqualTo("/hooks/enrich-customer");

        // email is encrypted in the DB — query by the enriched name instead
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM aperture_customers WHERE name = ?",
                Integer.class, "ENRICHED NAME");
        assertThat(count).as("mutate hook must have overridden the name attribute").isEqualTo(1);
    }

    @Test
    void guardHookRejectsBeforePersistence() throws Exception {
        // CheckLineItem is a guard hook (PRESECURITY, on: [create]) on LineItem — a rejection
        // must happen before the row is ever written, unlike validate/mutate which run PRECOMMIT.
        // Seed a valid required billable pointer directly so OneOf request validation succeeds
        // without introducing unrelated product-hook callbacks into MockWebServer.
        UUID productId = UUID.randomUUID();
        String suffix = productId.toString().substring(0, 8);
        jdbcTemplate.update("""
            INSERT INTO aperture_products
                (id, aperture_tenant_id, name, sku, unit_price, active, version)
            VALUES (?, ?::uuid, ?, ?, ?, true, 0)
            """, productId, "00000000-0000-0000-0000-000000000001",
            "Guard hook product " + suffix, "GUARD-" + suffix, 10.00);
        overrideHookResponse("/hooks/check-line-item", 403);

        Integer countBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class);

        performElideRequest(post("/api/v1/lineitems")
                .header("Authorization", getAcmeAccountantToken())
                .contentType(VNDAPI)
                .content("""
                    {"data":{"type":"lineitems","attributes":{
                      "quantity":5,
                      "unit_price":10.00
                    },"relationships":{
                      "billable":{"data":{"type":"products","id":"%s"}}
                    }}}
                    """.formatted(productId)))
                .andExpect(status().is4xxClientError());

        Integer countAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class);
        assertThat(countAfter).as("guard rejection must leave the line item count unchanged").isEqualTo(countBefore);

        okhttp3.mockwebserver.RecordedRequest hookRequest = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(hookRequest).as("MockWebServer must have received the check-line-item guard callback").isNotNull();
        assertThat(hookRequest.getPath()).isEqualTo("/hooks/check-line-item");
        assertThat(hookRequest.getHeader("Authorization")).as("guard hook must never receive user credentials").isNull();
    }

    @Test
    void hookReceivesShallowPayloadNotEmpty() throws Exception {
        // Hook payload must include scalar fields (amount) and FK as customer_id — not empty {}
        performElideRequest(post("/api/v1/invoices")
                .header("Authorization", getAcmeAccountantToken())
                .contentType(VNDAPI)
                .content("{\"data\": {\"type\": \"invoices\", \"attributes\": {\"amount\": 999, \"status\": \"DRAFT\"}, \"relationships\": {\"customer\": {\"data\": {\"type\": \"customers\", \"id\": \"00000000-0000-0000-0000-000000000001\"}}}}}"))
                .andExpect(status().isCreated());

        okhttp3.mockwebserver.RecordedRequest hookRequest = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(hookRequest).isNotNull();
        String hookBody = hookRequest.getBody().readUtf8();
        assertThat(hookBody).as("hook payload must not be empty {}").isNotEqualTo("{}");
        assertThat(hookBody).as("hook payload must contain the amount field").contains("amount");
        assertThat(hookBody).as("hook payload must contain customer FK as customer_id").contains("customer_id");
    }

    private okhttp3.mockwebserver.RecordedRequest pollForHookRequest(String path, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            okhttp3.mockwebserver.RecordedRequest req = mockWebServer.takeRequest(200, TimeUnit.MILLISECONDS);
            if (req == null) continue;
            if (path.equals(req.getPath())) return req;
            // discard requests to other hook paths and keep polling
        }
        return null;
    }
}
