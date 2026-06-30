package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

public class ValidationComponentTest extends DemoApplicationTestSupport {
    @Test
    public void testTier1Validation() throws Exception {
        // Missing required 'amount' on Invoice
        performElideRequest(post("/api/v1/invoices")
                .header("Authorization", getAcmeAccountantToken())
                .contentType(MediaType.valueOf("application/vnd.api+json"))
                .content("{\"data\": {\"type\": \"invoices\", \"attributes\": {\"status\":\"DRAFT\"}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].detail").exists());
    }

    @Test
    public void testTier2Validation() throws Exception {
        // Invalid foreign key or logical violation
        performElideRequest(post("/api/v1/invoices")
                .header("Authorization", getAcmeAccountantToken())
                .contentType(MediaType.valueOf("application/vnd.api+json"))
                .content("{\"data\": {\"type\": \"invoices\", \"attributes\": {\"amount\": 100, \"status\": \"DRAFT\"}, \"relationships\": {\"customer\": {\"data\": {\"type\": \"customers\", \"id\": \"99999999-9999-9999-9999-999999999999\"}}}}}"))
                .andExpect(status().isNotFound()) // Or bad request? Elide usually returns 404 when related entity is not found
                .andExpect(jsonPath("$.errors[0].detail").exists());
    }
}
