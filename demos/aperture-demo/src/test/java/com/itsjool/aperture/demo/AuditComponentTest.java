package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AuditComponentTest extends DemoApplicationTestSupport {

    private static final MediaType VNDAPI = MediaType.valueOf("application/vnd.api+json");

    @Test
    void committedMutationProducesAuditRecord() throws Exception {
        String token = getAcmeAdminToken();

        performElideRequest(post("/api/v1/customers")
                .header("Authorization", token)
                .contentType(VNDAPI)
                .content("{\"data\": {\"type\": \"customers\", \"attributes\": {\"name\": \"Audit Test Corp\", \"email\": \"audit@test.com\"}}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").exists());

        // Poll until the async audit write completes (no sleeps)
        pollUntilAuditRecord("Customer", "CREATE", 5);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM aperture_audit_log WHERE entity = 'Customer' AND operation = 'CREATE'",
                Integer.class);
        assertThat(count).as("committed customer creation must produce an audit record").isGreaterThan(0);
    }

    @Test
    void tenantAdminQueriesOnlyOwnTenantAudit() throws Exception {
        // Create a customer under Acme to ensure there's an audit record
        performElideRequest(post("/api/v1/customers")
                .header("Authorization", getAcmeAdminToken())
                .contentType(VNDAPI)
                .content("{\"data\": {\"type\": \"customers\", \"attributes\": {\"name\": \"RBAC Test Corp\", \"email\": \"rbac@test.com\"}}}"))
                .andExpect(status().isCreated());

        pollUntilAuditRecord("Customer", "CREATE", 5);

        // Acme admin can query audit
        mockMvc.perform(get("/manage/audit")
                .header("Authorization", getAcmeAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Acme admin cannot query Globex's audit (tenantId is auto-scoped)
        // They get their own results, not Globex's
        var acmeResult = mockMvc.perform(get("/manage/audit")
                .header("Authorization", getAcmeAdminToken()))
                .andReturn();
        String acmeBody = acmeResult.getResponse().getContentAsString();
        // Acme admin's results should not contain Globex tenant_id entries
        assertThat(acmeBody).doesNotContain("globex");
    }

    @Test
    void frameworkSuperAdminQueriesAllTenants() throws Exception {
        mockMvc.perform(get("/manage/audit")
                .header("Authorization", getSuperAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void ordinaryActorIsDeniedAuditAccess() throws Exception {
        mockMvc.perform(get("/manage/audit")
                .header("Authorization", getAcmeViewerToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedRequestIsDenied() throws Exception {
        mockMvc.perform(get("/manage/audit"))
                .andExpect(status().is4xxClientError());
    }

    // --- helpers ---

    private void pollUntilAuditRecord(String entity, String operation, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM aperture_audit_log WHERE entity = ? AND operation = ?",
                    Integer.class, entity, operation);
            if (count != null && count > 0) return;
            Thread.sleep(100);
        }
        // Let the assertion in the test body fail with a clear message
    }
}
