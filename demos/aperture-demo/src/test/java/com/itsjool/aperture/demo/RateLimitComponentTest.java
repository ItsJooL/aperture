package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
    "aperture.rate-limit.ip.capacity=2",
    "aperture.rate-limit.ip.refillTokens=2",
    "aperture.rate-limit.ip.windowSeconds=60"
})
public class RateLimitComponentTest extends DemoApplicationTestSupport {
    @Test
    public void testRateLimit() throws Exception {
        String token = getAcmeAccountantToken();

        performElideRequest(get("/api/v1/customers").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "2"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));

        performElideRequest(get("/api/v1/customers").header("Authorization", token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "2"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("X-RateLimit-Reset"));
    }
}
