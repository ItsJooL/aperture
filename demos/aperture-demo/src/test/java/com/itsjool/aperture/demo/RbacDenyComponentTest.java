package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class RbacDenyComponentTest extends DemoApplicationTestSupport {

    private static final MediaType VNDAPI = MediaType.valueOf("application/vnd.api+json");
    private static final String CUSTOMER_1 = "00000000-0000-0000-0000-000000000001";

    /**
     * Viewer has only [read] on Customer — a POST must return 403 and no row
     * may be inserted.
     */
    @Test
    public void viewerCannotCreateCustomer() throws Exception {
        String viewerToken = getAcmeViewerToken();

        int countBefore = countAcmeCustomers();

        performElideRequest(post("/api/v1/customers")
                .header("Authorization", viewerToken)
                .contentType(VNDAPI)
                .accept(VNDAPI)
                .content("{\"data\":{\"type\":\"customers\",\"attributes\":{\"name\":\"Denied Corp\",\"email\":\"denied@corp.com\"}}}"))
                .andExpect(status().isForbidden());

        int countAfter = countAcmeCustomers();
        org.assertj.core.api.Assertions.assertThat(countAfter)
                .as("row count must not change after forbidden POST")
                .isEqualTo(countBefore);
    }

    /**
     * Accountant has [create, read, update] on Customer — no delete.
     * Customer has optimisticLocking: true, so we must fetch the ETag first
     * and include it as If-Match so the RBAC layer is reached (not short-circuited
     * by the 412 precondition check). The response must be 403 and the row must
     * still exist.
     */
    @Test
    public void accountantCannotDeleteCustomer() throws Exception {
        String accountantToken = getAcmeAccountantToken();

        // Fetch the current ETag so the optimistic-locking guard lets the request through
        String etag = fetchEtag("/api/v1/customers/" + CUSTOMER_1, accountantToken);

        performElideRequest(delete("/api/v1/customers/" + CUSTOMER_1)
                .header("Authorization", accountantToken)
                .header("If-Match", etag)
                .accept(VNDAPI))
                .andExpect(status().isForbidden());

        int remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM aperture_customers WHERE id = ?::uuid AND deleted_at IS NULL",
                Integer.class, CUSTOMER_1);
        org.assertj.core.api.Assertions.assertThat(remaining)
                .as("customer row must survive forbidden DELETE")
                .isEqualTo(1);
    }

    /**
     * A request with no Authorization header must be rejected with 401.
     */
    @Test
    public void unauthenticatedRequestIsRejected() throws Exception {
        performElideRequest(get("/api/v1/customers")
                .accept(VNDAPI))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Viewer cannot delete (403, row survives); TenantAdmin can delete (2xx,
     * soft-delete sets deleted_at). @Sql resets the fixture before each test so
     * the deletion here does not affect sibling tests.
     *
     * Customer has optimisticLocking: true, so we must include a valid If-Match
     * header for the RBAC check to be reached.
     *
     * Note: SuperAdmin is a framework-level role only — it does not
     * grant access to tenant domain resources. TenantAdmin is the correct
     * privileged actor for tenant-scoped CRUD.
     */
    @Test
    public void deleteByTenantAdminSucceedsWhereViewerFails() throws Exception {
        String viewerToken = getAcmeViewerToken();
        String adminToken = getAcmeAdminToken();

        // Fetch ETag as the viewer (they have read permission, so this succeeds)
        String etag = fetchEtag("/api/v1/customers/" + CUSTOMER_1, viewerToken);

        // Step 1 — viewer must be refused even with a valid ETag
        performElideRequest(delete("/api/v1/customers/" + CUSTOMER_1)
                .header("Authorization", viewerToken)
                .header("If-Match", etag)
                .accept(VNDAPI))
                .andExpect(status().isForbidden());

        // Row must still be present (not soft-deleted) after viewer's attempt
        int afterViewerAttempt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM aperture_customers WHERE id = ?::uuid AND deleted_at IS NULL",
                Integer.class, CUSTOMER_1);
        org.assertj.core.api.Assertions.assertThat(afterViewerAttempt)
                .as("row must survive viewer's forbidden DELETE")
                .isEqualTo(1);

        // Step 2 — TenantAdmin succeeds (same ETag is valid: viewer's 403 did not mutate the row)
        performElideRequest(delete("/api/v1/customers/" + CUSTOMER_1)
                .header("Authorization", adminToken)
                .header("If-Match", etag)
                .accept(VNDAPI))
                .andExpect(status().is2xxSuccessful());
    }

    // ---------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------

    /**
     * GET a single resource and return the ETag header value. Throws if the
     * response is not 200 or if no ETag is returned.
     */
    private String fetchEtag(String path, String token) throws Exception {
        var result = performElideRequest(get(path)
                .header("Authorization", token)
                .accept(VNDAPI))
                .andExpect(status().isOk())
                .andReturn();
        String etag = result.getResponse().getHeader("ETag");
        org.assertj.core.api.Assertions.assertThat(etag)
                .as("ETag must be present on GET " + path)
                .isNotNull();
        return etag;
    }

    private int countAcmeCustomers() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM aperture_customers WHERE aperture_tenant_id = ?::uuid",
                Integer.class, "00000000-0000-0000-0000-000000000001");
        return n == null ? 0 : n;
    }
}
