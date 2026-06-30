package com.itsjool.aperture.demo;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

public class TenantIsolationComponentTest extends DemoApplicationTestSupport {

    @Test
    public void testIsolation() throws Exception {
        String acmeToken = loginAs("admin@acme.com", "password");
        performElideRequest(get("/api/v1/customers")
                .header("Authorization", acmeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));

        performElideRequest(get("/api/v1/customers")
                .header("Authorization", getGlobexAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attributes.name").value("Globex Inc"));
    }

    @Test
    public void acmeAdminCannotReadGlobexCustomerById() throws Exception {
        String acmeToken = loginAs("admin@acme.com", "password");
        // Globex customer ID — tenant filter should make it invisible to acme
        performElideRequest(get("/api/v1/customers/00000000-0000-0000-0000-000000000002")
                .header("Authorization", acmeToken)
                .accept(MediaType.parseMediaType("application/vnd.api+json")))
                .andExpect(status().isNotFound());
    }

    @Test
    public void globexAdminCannotDeleteAcmeCustomer() throws Exception {
        String globexToken = loginAs("admin@globex.com", "password");
        // Acme Corp customer ID — tenant filter should prevent cross-tenant delete.
        // Optimistic locking requires If-Match; supplying a plausible ETag so the
        // locking guard is bypassed and the tenant-scoped 404 is the terminal response.
        performElideRequest(delete("/api/v1/customers/00000000-0000-0000-0000-000000000001")
                .header("Authorization", globexToken)
                .header("If-Match", "\"0\"")
                .accept(MediaType.parseMediaType("application/vnd.api+json")))
                .andExpect(status().isNotFound());

        // Verify the acme customer still exists and is not soft-deleted
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM aperture_customers WHERE id = '00000000-0000-0000-0000-000000000001' AND deleted_at IS NULL",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    public void crossTenantWriteDoesNotLeakTenantInfo() throws Exception {
        // Step 1: acme admin creates a new customer
        String acmeToken = loginAs("admin@acme.com", "password");
        String createBody = "{\"data\":{\"type\":\"customers\",\"attributes\":{\"name\":\"Spoof Attempt\",\"email\":\"spoof@evil.com\"}}}";
        var createResult = performElideRequest(post("/api/v1/customers")
                .header("Authorization", acmeToken)
                .accept(MediaType.parseMediaType("application/vnd.api+json"))
                .contentType(MediaType.parseMediaType("application/vnd.api+json"))
                .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();

        // Extract the created customer ID from the response
        String responseBody = createResult.getResponse().getContentAsString();
        JsonNode root = MAPPER.readTree(responseBody);
        String createdId = root.path("data").path("id").asText();
        assertThat(createdId).isNotBlank();

        // Step 2: globex admin cannot see acme's newly created customer
        String globexToken = loginAs("admin@globex.com", "password");
        performElideRequest(get("/api/v1/customers/" + createdId)
                .header("Authorization", globexToken)
                .accept(MediaType.parseMediaType("application/vnd.api+json")))
                .andExpect(status().isNotFound());
    }
}
