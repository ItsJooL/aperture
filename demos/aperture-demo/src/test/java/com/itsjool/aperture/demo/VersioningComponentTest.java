package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

public class VersioningComponentTest extends DemoApplicationTestSupport {
    @Test
    public void testVersionsExist() throws Exception {
        String token = getAcmeAccountantToken();
        performElideRequest(get("/api/v1/customers").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(header().exists("Sunset")); // Version 1 is sunset
        performElideRequest(get("/api/v2/customers").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(header().doesNotExist("Sunset"));
    }

    @Test
    public void testApiVersioningCorrectFields() throws Exception {
        String token = getAcmeAccountantToken();
        performElideRequest(get("/api/v1/customers/00000000-0000-0000-0000-000000000001")
                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.phone_number").doesNotExist());

        performElideRequest(get("/api/v2/customers/00000000-0000-0000-0000-000000000001")
                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.phone_number").exists());
    }
}
