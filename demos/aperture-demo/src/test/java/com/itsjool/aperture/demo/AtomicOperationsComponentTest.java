package com.itsjool.aperture.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AtomicOperationsComponentTest extends DemoApplicationTestSupport {

    private static final MediaType ATOMIC_MEDIA_TYPE =
            MediaType.valueOf("application/vnd.api+json;ext=\"https://jsonapi.org/ext/atomic\"");

    private static final String CUSTOMER_1   = "00000000-0000-0000-0000-000000000001";
    private static final String SUPPLIER_1   = "50000000-0000-0000-0000-000000000001";
    private static final String SUPPLIER_2   = "50000000-0000-0000-0000-000000000002";

    @BeforeEach
    void drainStaleRequestsAndResetOverrides() throws InterruptedException {
        clearHookOverrides();
        okhttp3.mockwebserver.RecordedRequest stale;
        while ((stale = mockWebServer.takeRequest(50, TimeUnit.MILLISECONDS)) != null) { /* discard */ }
    }

    @Test
    void atomicCreate_invoiceWithLineItems_allOrNothing() throws Exception {
        String token = getAcmeAdminToken();
        String productId = createProduct(token);
        Integer invoicesBefore  = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_invoices", Integer.class);
        Integer lineItemsBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class);

        performElideRequest(post("/api/v1/operations")
                .header("Authorization", token)
                .contentType(ATOMIC_MEDIA_TYPE)
                .accept(ATOMIC_MEDIA_TYPE)
                .content("{\"atomic:operations\": [" +
                    "{\"op\":\"add\",\"data\":{\"type\":\"invoices\",\"lid\":\"new-inv\"," +
                        "\"attributes\":{\"amount\":1500,\"status\":\"DRAFT\"}," +
                        "\"relationships\":{\"customer\":{\"data\":{\"type\":\"customers\",\"id\":\"" + CUSTOMER_1 + "\"}}}}}," +
                    "{\"op\":\"add\",\"data\":{\"type\":\"lineitems\",\"lid\":\"li-1\"," +
                        "\"attributes\":{\"quantity\":1,\"unit_price\":999.00}," +
                        "\"relationships\":{\"invoice\":{\"data\":{\"type\":\"invoices\",\"lid\":\"new-inv\"}}," +
                            "\"billable\":{\"data\":{\"type\":\"products\",\"id\":\"" + productId + "\"}}}}}," +
                    "{\"op\":\"add\",\"data\":{\"type\":\"lineitems\",\"lid\":\"li-2\"," +
                        "\"attributes\":{\"quantity\":1,\"unit_price\":501.00}," +
                        "\"relationships\":{\"invoice\":{\"data\":{\"type\":\"invoices\",\"lid\":\"new-inv\"}}," +
                            "\"billable\":{\"data\":{\"type\":\"products\",\"id\":\"" + productId + "\"}}}}}" +
                    "]}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_invoices", Integer.class))
                .as("atomic batch must have created one new invoice").isEqualTo(invoicesBefore + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class))
                .as("atomic batch must have created two line items").isEqualTo(lineItemsBefore + 2);
    }

    @Test
    void atomicCreate_invoiceWithOneofBillables_allOrNothing() throws Exception {
        String token = getAcmeAdminToken();
        String productId = createProduct(token);
        String servicePackageId = createServicePackage(token);
        Integer invoicesBefore  = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_invoices", Integer.class);
        Integer lineItemsBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class);

        performElideRequest(post("/api/v1/operations")
                .header("Authorization", token)
                .contentType(ATOMIC_MEDIA_TYPE)
                .accept(ATOMIC_MEDIA_TYPE)
                .content("{\"atomic:operations\": [" +
                    "{\"op\":\"add\",\"data\":{\"type\":\"invoices\",\"lid\":\"new-inv\"," +
                        "\"attributes\":{\"amount\":1249,\"status\":\"DRAFT\"}," +
                        "\"relationships\":{\"customer\":{\"data\":{\"type\":\"customers\",\"id\":\"" + CUSTOMER_1 + "\"}}}}}," +
                    "{\"op\":\"add\",\"data\":{\"type\":\"lineitems\",\"lid\":\"li-1\"," +
                        "\"attributes\":{\"description\":\"Atomic oneof product\",\"quantity\":1,\"unit_price\":499.00,\"price\":499.00}," +
                        "\"relationships\":{\"invoice\":{\"data\":{\"type\":\"invoices\",\"lid\":\"new-inv\"}}," +
                            "\"product\":{\"data\":{\"type\":\"products\",\"id\":\"" + productId + "\"}}," +
                            "\"billable\":{\"data\":{\"type\":\"products\",\"id\":\"" + productId + "\"}}}}}," +
                    "{\"op\":\"add\",\"data\":{\"type\":\"lineitems\",\"lid\":\"li-2\"," +
                        "\"attributes\":{\"description\":\"Atomic oneof service\",\"quantity\":1,\"unit_price\":750.00,\"price\":750.00}," +
                        "\"relationships\":{\"invoice\":{\"data\":{\"type\":\"invoices\",\"lid\":\"new-inv\"}}," +
                            "\"billable\":{\"data\":{\"type\":\"servicepackages\",\"id\":\"" + servicePackageId + "\"}}}}}" +
                    "]}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_invoices", Integer.class))
                .as("atomic oneof batch must have created one new invoice").isEqualTo(invoicesBefore + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class))
                .as("atomic oneof batch must have created two line items").isEqualTo(lineItemsBefore + 2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM aperture_lineitems WHERE billable_type IN ('Product', 'ServicePackage')",
                Integer.class))
                .as("atomic oneof batch must persist both billable discriminator values")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void atomicCreate_missingRequiredOneOfRejectsAndRollsBackEntireBatch() throws Exception {
        Integer invoicesBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_invoices", Integer.class);
        Integer lineItemsBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class);

        performElideRequest(post("/api/v1/operations")
                .header("Authorization", getAcmeAdminToken())
                .contentType(ATOMIC_MEDIA_TYPE)
                .accept(ATOMIC_MEDIA_TYPE)
                .content("{\"atomic:operations\": [" +
                    "{\"op\":\"add\",\"data\":{\"type\":\"invoices\",\"lid\":\"required-oneof-inv\"," +
                        "\"attributes\":{\"amount\":125,\"status\":\"DRAFT\"}," +
                        "\"relationships\":{\"customer\":{\"data\":{\"type\":\"customers\",\"id\":\"" + CUSTOMER_1 + "\"}}}}}," +
                    "{\"op\":\"add\",\"data\":{\"type\":\"lineitems\",\"lid\":\"missing-required-billable\"," +
                        "\"attributes\":{\"quantity\":1,\"unit_price\":125.00}," +
                        "\"relationships\":{\"invoice\":{\"data\":{\"type\":\"invoices\",\"lid\":\"required-oneof-inv\"}}}}}" +
                    "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].detail").value("lineitems.billable is required"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_invoices", Integer.class))
                .as("missing required oneof must roll back the valid invoice operation")
                .isEqualTo(invoicesBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_lineitems", Integer.class))
                .as("missing required oneof must not persist the invalid line item")
                .isEqualTo(lineItemsBefore);
    }

    @Test
    void atomicCreate_oneInvalidOperation_entireBatchRollsBack() throws Exception {
        Integer suppliersBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_suppliers", Integer.class);

        performElideRequest(post("/api/v1/operations")
                .header("Authorization", getAcmeAdminToken())
                .contentType(ATOMIC_MEDIA_TYPE)
                .accept(ATOMIC_MEDIA_TYPE)
                .content("{\"atomic:operations\": [" +
                    "{\"op\":\"add\",\"data\":{\"type\":\"suppliers\"," +
                        "\"attributes\":{\"company_name\":\"Valid Supplier\"}}}," +
                    "{\"op\":\"add\",\"data\":{\"type\":\"suppliers\"," +
                        "\"attributes\":{}}}" + // missing required company_name
                    "]}"))
                .andExpect(status().is4xxClientError());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_suppliers", Integer.class))
                .as("failed atomic batch must roll back all operations — supplier count must be unchanged")
                .isEqualTo(suppliersBefore);
    }

    @Test
    void atomicUpdate_multipleEntities_allOrNothing() throws Exception {
        performElideRequest(post("/api/v1/operations")
                .header("Authorization", getAcmeAdminToken())
                .contentType(ATOMIC_MEDIA_TYPE)
                .accept(ATOMIC_MEDIA_TYPE)
                .content("{\"atomic:operations\": [" +
                    "{\"op\":\"update\",\"data\":{\"type\":\"suppliers\",\"id\":\"" + SUPPLIER_1 + "\"," +
                        "\"attributes\":{\"company_name\":\"Updated Supplies A\"}}}," +
                    "{\"op\":\"update\",\"data\":{\"type\":\"suppliers\",\"id\":\"" + SUPPLIER_2 + "\"," +
                        "\"attributes\":{\"company_name\":\"Updated Supplies B\"}}}" +
                    "]}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT company_name FROM aperture_suppliers WHERE id = ?", String.class,
                java.util.UUID.fromString(SUPPLIER_1)))
                .as("first supplier name must be updated atomically").isEqualTo("Updated Supplies A");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT company_name FROM aperture_suppliers WHERE id = ?", String.class,
                java.util.UUID.fromString(SUPPLIER_2)))
                .as("second supplier name must be updated atomically").isEqualTo("Updated Supplies B");
    }

    @Test
    void atomicCreate_rbacBlocks_batchRollsBack() throws Exception {
        Integer suppliersBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_suppliers", Integer.class);

        performElideRequest(post("/api/v1/operations")
                .header("Authorization", getAcmeViewerToken())
                .contentType(ATOMIC_MEDIA_TYPE)
                .accept(ATOMIC_MEDIA_TYPE)
                .content("{\"atomic:operations\": [" +
                    "{\"op\":\"add\",\"data\":{\"type\":\"suppliers\"," +
                        "\"attributes\":{\"company_name\":\"Unauthorized Supplier\"}}}" +
                    "]}"))
                .andExpect(status().is4xxClientError()); // Elide wraps RBAC denials as 400 in atomic batch responses

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_suppliers", Integer.class))
                .as("RBAC-blocked atomic batch must not persist any data").isEqualTo(suppliersBefore);
    }

    private String createProduct(String token) throws Exception {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        var result = performElideRequest(post("/api/v1/products")
                .header("Authorization", token)
                .contentType(MediaType.valueOf("application/vnd.api+json"))
                .content("""
                    {"data":{"type":"products","attributes":{
                      "name":"Atomic Oneof Product %s",
                      "sku":"AOO-PROD-%s",
                      "description":"Product member for atomic oneof test",
                      "unit_price":499.00,
                      "category":"SOFTWARE",
                      "active":true
                    }}}
                    """.formatted(suffix, suffix)))
            .andExpect(status().isCreated())
            .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText();
    }

    private String createServicePackage(String token) throws Exception {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        var result = performElideRequest(post("/api/v1/servicepackages")
                .header("Authorization", token)
                .contentType(MediaType.valueOf("application/vnd.api+json"))
                .content("""
                    {"data":{"type":"servicepackages","attributes":{
                      "name":"Atomic Oneof Service %s",
                      "sku":"AOO-SVC-%s",
                      "description":"Service member for atomic oneof test",
                      "unit_price":750.00,
                      "active":true
                    }}}
                    """.formatted(suffix, suffix)))
            .andExpect(status().isCreated())
            .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText();
    }
}
