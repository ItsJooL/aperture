package com.itsjool.aperture.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

public class OneOfComponentTest extends DemoApplicationTestSupport {
    private static final MediaType JSON_API = MediaType.valueOf("application/vnd.api+json");

    @BeforeEach
    void drainStaleRequestsAndResetOverrides() throws InterruptedException {
        clearHookOverrides();
        okhttp3.mockwebserver.RecordedRequest stale;
        while ((stale = mockWebServer.takeRequest(50, TimeUnit.MILLISECONDS)) != null) { /* discard */ }
    }

    @Test
    void createsLineItemsSelectingDifferentBillableMembers() throws Exception {
        String token = getAcmeAdminToken();
        String productId = createProduct(token);
        String servicePackageId = createServicePackage(token);
        String subscriptionPlanId = createSubscriptionPlan(token);

        String productLineId = createLineItem(token, "products", productId, "API support block", 199);
        String serviceLineId = createLineItem(token, "servicepackages", servicePackageId, "Priority onboarding", 750);
        String planLineId = createLineItem(token, "subscriptionplans", subscriptionPlanId, "Developer plan", 49);

        assertBillableRelationship(token, productLineId, "products", productId);
        assertBillableRelationship(token, serviceLineId, "servicepackages", servicePackageId);
        assertBillableRelationship(token, planLineId, "subscriptionplans", subscriptionPlanId);
        assertBillableColumns(productLineId, "Product", productId);
        assertBillableColumns(serviceLineId, "ServicePackage", servicePackageId);
        assertBillableColumns(planLineId, "SubscriptionPlan", subscriptionPlanId);
    }

    @Test
    void rejectsBillableTypeOutsideDeclaredOneOfMembers() throws Exception {
        String token = getAcmeAdminToken();
        Integer lineItemsBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class);

        performElideRequest(post("/api/v1/lineitems")
                .header("Authorization", token)
                .contentType(JSON_API)
                .content("""
                    {"data":{"type":"lineitems","attributes":{
                      "description":"Invalid billable",
                      "quantity":1,
                      "unit_price":99
                    },"relationships":{
                      "billable":{"data":{"type":"customers","id":"00000000-0000-0000-0000-000000000001"}}
                    }}}
                    """))
            .andExpect(status().is4xxClientError());

        Integer lineItemsAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class);
        assertThat(lineItemsAfter).isEqualTo(lineItemsBefore);
    }

    @Test
    void requiredBillableRejectsMissingAndNullSelectionOnCreate() throws Exception {
        String token = getAcmeAdminToken();
        Integer lineItemsBefore = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM aperture_lineitems", Integer.class);

        performElideRequest(post("/api/v1/lineitems")
                .header("Authorization", token)
                .contentType(JSON_API)
                .content("""
                    {"data":{"type":"lineitems","attributes":{
                      "description":"Missing billable","quantity":1,"unit_price":10
                    }}}
                    """))
            .andExpect(status().is4xxClientError());

        performElideRequest(post("/api/v1/lineitems")
                .header("Authorization", token)
                .contentType(JSON_API)
                .content("""
                    {"data":{"type":"lineitems","attributes":{
                      "description":"Null billable","quantity":1,"unit_price":10
                    },"relationships":{"billable":{"data":null}}}}
                    """))
            .andExpect(status().is4xxClientError());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class))
            .isEqualTo(lineItemsBefore);
    }

    @Test
    void omitsOneofRelationshipFromGraphqlV1Schema() throws Exception {
        String body = performElideRequest(post("/graphql/v1")
                .header("Authorization", getAcmeAdminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ __type(name: \\"Lineitems\\") { fields { name } } }"}
                    """))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode fields = MAPPER.readTree(body).path("data").path("__type").path("fields");
        assertThat(fields).extracting(field -> field.path("name").asText())
            .contains("product")
            .doesNotContain("billable");
    }

    @Test
    void includesTheConcreteBillableResource() throws Exception {
        String token = getAcmeAdminToken();
        String productId = createProduct(token);
        String lineItemId = createLineItem(token, "products", productId, "Included support block", 199);

        performElideRequest(get("/api/v1/lineitems/" + lineItemId + "?include=billable")
                .header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.relationships.billable.data.type").value("products"))
            .andExpect(jsonPath("$.data.relationships.billable.data.id").value(productId))
            .andExpect(jsonPath("$.included[0].type").value("products"))
            .andExpect(jsonPath("$.included[0].id").value(productId))
            .andExpect(jsonPath("$.included[0].attributes.name").value(startsWith("Oneof API support block ")));
    }

    @Test
    void updatesTheSelectedMemberThroughTheRelationshipEndpoint() throws Exception {
        String token = getAcmeAdminToken();
        String productId = createProduct(token);
        String servicePackageId = createServicePackage(token);
        String lineItemId = createLineItem(token, "products", productId, "Replaceable billable", 199);

        performElideRequest(patch("/api/v1/lineitems/" + lineItemId + "/relationships/billable")
                .header("Authorization", token)
                .contentType(JSON_API)
                .content("""
                    {"data":{"type":"servicepackages","id":"%s"}}
                    """.formatted(servicePackageId)))
            .andExpect(status().isNoContent());

        assertBillableRelationship(token, lineItemId, "servicepackages", servicePackageId);
        assertBillableColumns(lineItemId, "ServicePackage", servicePackageId);
    }

    @Test
    void rejectsUndeclaredMemberThroughTheRelationshipEndpoint() throws Exception {
        String token = getAcmeAdminToken();
        String productId = createProduct(token);
        String lineItemId = createLineItem(token, "products", productId, "Protected billable", 199);

        performElideRequest(patch("/api/v1/lineitems/" + lineItemId + "/relationships/billable")
                .header("Authorization", token)
                .contentType(JSON_API)
                .content("""
                    {"data":{"type":"customers","id":"00000000-0000-0000-0000-000000000001"}}
                    """))
            .andExpect(status().isBadRequest());

        assertBillableColumns(lineItemId, "Product", productId);
    }

    @Test
    void rejectsOneOfMemberOwnedByAnotherTenant() throws Exception {
        String globexProductId = createProduct(getGlobexAdminToken());
        String acmeToken = getAcmeAdminToken();
        Integer lineItemsBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class);

        performElideRequest(post("/api/v1/lineitems")
                .header("Authorization", acmeToken)
                .contentType(JSON_API)
                .content("""
                    {"data":{"type":"lineitems","attributes":{
                      "description":"Cross-tenant billable",
                      "quantity":1,
                      "unit_price":199
                    },"relationships":{
                      "billable":{"data":{"type":"products","id":"%s"}}
                    }}}
                    """.formatted(globexProductId)))
            .andExpect(status().is4xxClientError());

        Integer lineItemsAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class);
        assertThat(lineItemsAfter).isEqualTo(lineItemsBefore);
    }

    private String createProduct(String token) throws Exception {
        String suffix = uniqueSuffix();
        var result = performElideRequest(post("/api/v1/products")
                .header("Authorization", token)
                .contentType(JSON_API)
                .content("""
                    {"data":{"type":"products","attributes":{
                      "name":"Oneof API support block %s",
                      "sku":"OOF-%s",
                      "unit_price":199.00,
                      "active":true
                    }}}
                    """.formatted(suffix, suffix)))
            .andExpect(status().isCreated())
            .andReturn();
        return idFrom(result.getResponse().getContentAsString());
    }

    private String createServicePackage(String token) throws Exception {
        String suffix = uniqueSuffix();
        var result = performElideRequest(post("/api/v1/servicepackages")
                .header("Authorization", token)
                .contentType(JSON_API)
                .content("""
                    {"data":{"type":"servicepackages","attributes":{
                      "name":"Oneof Priority Onboarding %s",
                      "sku":"OOF-%s",
                      "description":"Guided launch package",
                      "unit_price":750.00,
                      "active":true
                    }}}
                    """.formatted(suffix, suffix)))
            .andExpect(status().isCreated())
            .andReturn();
        return idFrom(result.getResponse().getContentAsString());
    }

    private String createSubscriptionPlan(String token) throws Exception {
        String suffix = uniqueSuffix();
        var result = performElideRequest(post("/api/v1/subscriptionplans")
                .header("Authorization", token)
                .contentType(JSON_API)
                .content("""
                    {"data":{"type":"subscriptionplans","attributes":{
                      "name":"Oneof Developer Plan %s",
                      "sku":"OOF-%s",
                      "description":"Monthly API access",
                      "unit_price":49.00,
                      "billing_interval":"MONTHLY",
                      "active":true
                    }}}
                    """.formatted(suffix, suffix)))
            .andExpect(status().isCreated())
            .andReturn();
        return idFrom(result.getResponse().getContentAsString());
    }

    private String createLineItem(String token, String billableType, String billableId,
                                  String description, int unitPrice) throws Exception {
        var result = performElideRequest(post("/api/v1/lineitems")
                .header("Authorization", token)
                .contentType(JSON_API)
                .content("""
                    {"data":{"type":"lineitems","attributes":{
                      "description":"%s",
                      "quantity":1,
                      "unit_price":%d
                    },"relationships":{
                      "billable":{"data":{"type":"%s","id":"%s"}}
                    }}}
                    """.formatted(description, unitPrice, billableType, billableId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.relationships.billable.data.type").value(billableType))
            .andExpect(jsonPath("$.data.relationships.billable.data.id").value(billableId))
            .andReturn();
        return idFrom(result.getResponse().getContentAsString());
    }

    private void assertBillableRelationship(String token, String lineItemId,
                                            String expectedType, String expectedId) throws Exception {
        performElideRequest(get("/api/v1/lineitems/" + lineItemId + "/relationships/billable")
                .header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value(expectedType))
            .andExpect(jsonPath("$.data.id").value(expectedId));
    }

    private void assertBillableColumns(String lineItemId, String expectedType, String expectedId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT billable_type, billable_id FROM aperture_lineitems WHERE id = ?::uuid",
            lineItemId);

        assertThat(row.get("billable_type")).isEqualTo(expectedType);
        assertThat(row.get("billable_id")).isEqualTo(UUID.fromString(expectedId));
    }

    private String idFrom(String responseBody) throws Exception {
        JsonNode root = MAPPER.readTree(responseBody);
        return root.path("data").path("id").asText();
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

}
