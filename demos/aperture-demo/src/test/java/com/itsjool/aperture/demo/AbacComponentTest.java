package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

public class AbacComponentTest extends DemoApplicationTestSupport {
    @Test
    public void financeViewerCanReadInvoices() throws Exception {
        performElideRequest(get("/api/v1/invoices")
                .header("Authorization", loginAs("viewer@acme.com", "password"))
                .contentType(MediaType.valueOf("application/vnd.api+json")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].type").value("invoices"));
    }

    @Test
    public void salesViewerCannotSpoofTrustedDepartment() throws Exception {
        performElideRequest(get("/api/v1/invoices")
                .header("Authorization", loginAs("sales-viewer@acme.com", "password"))
                .header("X-Department", "finance")
                .contentType(MediaType.valueOf("application/vnd.api+json")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
