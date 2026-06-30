package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class OptimisticLockingComponentTest extends DemoApplicationTestSupport {

    private static final MediaType VNDAPI = MediaType.valueOf("application/vnd.api+json");
    private static final String CUSTOMER_1 = "00000000-0000-0000-0000-000000000001";
    private static final String CUSTOMER_3 = "00000000-0000-0000-0000-000000000003";
    private static final String SUPPLIER_1 = "50000000-0000-0000-0000-000000000001";

    @Test
    void getReturnsEtagForLockedEntity() throws Exception {
        String token = getAcmeAccountantToken();
        var result = performElideRequest(get("/api/v1/customers/" + CUSTOMER_1)
                .header("Authorization", token)
                .accept(VNDAPI))
                .andExpect(status().isOk())
                .andReturn();
        String etag = result.getResponse().getHeader("ETag");
        assertThat(etag).as("ETag must be present on GET for locked entity").isNotNull();
        assertThat(etag).matches("\"[0-9]+\"");
    }

    @Test
    void missingIfMatchReturns428ForLockedEntity() throws Exception {
        String token = getAcmeAccountantToken();
        performElideRequest(patch("/api/v1/customers/" + CUSTOMER_1)
                .header("Authorization", token)
                .contentType(VNDAPI)
                .accept(VNDAPI)
                .content("{\"data\":{\"type\":\"customers\",\"id\":\"" + CUSTOMER_1 + "\",\"attributes\":{\"name\":\"No Match Corp\"}}}"))
                .andExpect(status().is(428));
    }

    @Test
    void malformedIfMatchReturns400ForLockedEntity() throws Exception {
        String token = getAcmeAccountantToken();
        performElideRequest(patch("/api/v1/customers/" + CUSTOMER_1)
                .header("Authorization", token)
                .header("If-Match", "not-a-version")
                .contentType(VNDAPI)
                .accept(VNDAPI)
                .content("{\"data\":{\"type\":\"customers\",\"id\":\"" + CUSTOMER_1 + "\",\"attributes\":{\"name\":\"Bad ETag Corp\"}}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staleEtagReturns412() throws Exception {
        String token = getAcmeAccountantToken();
        String etag = fetchEtag("/api/v1/customers/" + CUSTOMER_1, token);

        performElideRequest(patch("/api/v1/customers/" + CUSTOMER_1)
                .header("Authorization", token)
                .header("If-Match", etag)
                .contentType(VNDAPI)
                .accept(VNDAPI)
                .content("{\"data\":{\"type\":\"customers\",\"id\":\"" + CUSTOMER_1 + "\",\"attributes\":{\"name\":\"Updated Name\"}}}"))
                .andExpect(status().is2xxSuccessful());

        performElideRequest(patch("/api/v1/customers/" + CUSTOMER_1)
                .header("Authorization", token)
                .header("If-Match", etag)
                .contentType(VNDAPI)
                .accept(VNDAPI)
                .content("{\"data\":{\"type\":\"customers\",\"id\":\"" + CUSTOMER_1 + "\",\"attributes\":{\"name\":\"Stale Name\"}}}"))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void correctSequenceIncrementsDatabaseVersion() throws Exception {
        String token = getAcmeAccountantToken();
        String etag = fetchEtag("/api/v1/customers/" + CUSTOMER_3, token);
        assertThat(etag).isEqualTo("\"0\"");

        performElideRequest(patch("/api/v1/customers/" + CUSTOMER_3)
                .header("Authorization", token)
                .header("If-Match", etag)
                .contentType(VNDAPI)
                .accept(VNDAPI)
                .content("{\"data\":{\"type\":\"customers\",\"id\":\"" + CUSTOMER_3 + "\",\"attributes\":{\"name\":\"Version Checked\"}}}"))
                .andExpect(status().is2xxSuccessful());

        Integer dbVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM aperture_customers WHERE id = ?::uuid", Integer.class, CUSTOMER_3);
        assertThat(dbVersion).as("DB version must increment to 1 after successful PATCH").isEqualTo(1);
    }

    @Test
    void nonLockedEntityDoesNotRequireIfMatch() throws Exception {
        String token = getAcmeAdminToken();
        var result = performElideRequest(patch("/api/v1/suppliers/" + SUPPLIER_1)
                .header("Authorization", token)
                .contentType(VNDAPI)
                .accept(VNDAPI)
                .content("{\"data\":{\"type\":\"suppliers\",\"id\":\"" + SUPPLIER_1 + "\",\"attributes\":{\"company_name\":\"Updated Supplies Co\"}}}"))
                .andReturn();
        int status = result.getResponse().getStatus();
        assertThat(status).as("non-locked entity PATCH without If-Match must not return 428").isNotEqualTo(428);
    }

    @Test
    void concurrentModificationOnlyOneWins() throws Exception {
        String token = getAcmeAccountantToken();
        // Both clients read the same ETag before either commits
        String etag = fetchEtag("/api/v1/customers/" + CUSTOMER_1, token);
        assertThat(etag).isEqualTo("\"0\"");

        // Client A commits a change directly at the DB level (simulates the winner's HTTP request
        // completing while Client B's request is in-flight with the same ETag)
        jdbcTemplate.update("UPDATE aperture_customers SET version = version + 1, name = 'Winner' WHERE id = ?::uuid", CUSTOMER_1);

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger loserStatus = new AtomicInteger(0);

        // Client B runs on a separate thread, sends the same ETag that is now stale
        Thread clientB = new Thread(() -> {
            try {
                var result = performElideRequest(patch("/api/v1/customers/" + CUSTOMER_1)
                        .header("Authorization", token)
                        .header("If-Match", etag)
                        .contentType(VNDAPI)
                        .accept(VNDAPI)
                        .content("{\"data\":{\"type\":\"customers\",\"id\":\"" + CUSTOMER_1
                                + "\",\"attributes\":{\"name\":\"Loser\"}}}"))
                        .andReturn();
                loserStatus.set(result.getResponse().getStatus());
            } catch (Exception e) {
                loserStatus.set(500);
            } finally {
                done.countDown();
            }
        }, "client-b-thread");

        clientB.start();
        assertThat(done.await(15, TimeUnit.SECONDS)).as("Client B must complete within 15 seconds").isTrue();

        assertThat(loserStatus.get()).as("Client B must receive 412 Precondition Failed").isEqualTo(412);

        Integer dbVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM aperture_customers WHERE id = ?::uuid", Integer.class, CUSTOMER_1);
        assertThat(dbVersion).as("version must remain at 1 — only Client A's commit succeeded").isEqualTo(1);
    }

    // ----

    private String fetchEtag(String path, String token) throws Exception {
        var result = performElideRequest(get(path)
                .header("Authorization", token)
                .accept(VNDAPI))
                .andExpect(status().isOk())
                .andReturn();
        String etag = result.getResponse().getHeader("ETag");
        assertThat(etag).as("ETag must be present on GET " + path).isNotNull();
        return etag;
    }
}
