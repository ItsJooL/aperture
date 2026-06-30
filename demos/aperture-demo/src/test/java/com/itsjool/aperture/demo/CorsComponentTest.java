package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
    "aperture.cors.enabled=true",
    "aperture.cors.allowed-origins=http://localhost:9999"
})
class CorsComponentTest extends DemoApplicationTestSupport {

    @Test
    void preflight_fromAllowedOrigin_returns200WithCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/v3/customers")
                .header("Origin", "http://localhost:9999")
                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:9999"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().exists("Access-Control-Allow-Methods"));
    }

    @Test
    void preflight_fromDisallowedOrigin_noCorsHeadersOnResponse() throws Exception {
        mockMvc.perform(options("/api/v3/customers")
                .header("Origin", "http://evil.example.com")
                .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void realGet_fromAllowedOrigin_includesCorsHeader() throws Exception {
        mockMvc.perform(get("/api/v3/customers")
                .header("Origin", "http://localhost:9999")
                .header("Authorization", getSuperAdminToken()))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:9999"));
    }
}
