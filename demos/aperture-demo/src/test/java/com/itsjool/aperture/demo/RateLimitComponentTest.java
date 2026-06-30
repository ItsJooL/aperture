package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

public class RateLimitComponentTest extends DemoApplicationTestSupport {
    @Test
    public void testRateLimit() throws Exception {
        String token = getAcmeAccountantToken();
        for(int i = 0; i < 120; i++) {
            performElideRequest(get("/api/v1/customers").header("Authorization", token));
        }
        performElideRequest(get("/api/v1/customers").header("Authorization", token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("X-RateLimit-Reset"));
    }
}
