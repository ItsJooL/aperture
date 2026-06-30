package com.itsjool.aperture.ratelimit;

import com.itsjool.aperture.spi.RateLimitDecision;
import com.itsjool.aperture.spi.RateLimitKey;
import com.itsjool.aperture.spi.RateLimitRule;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimitProviderTest {
    @Test
    void testTokenBucketAllowAndDeny() {
        InMemoryRateLimitProvider provider = new InMemoryRateLimitProvider();
        RateLimitKey key = new RateLimitKey("ip", "127.0.0.1");
        RateLimitRule rule = new RateLimitRule(2, 60, 60); // Assuming 3 ints from record: capacity, burst, windowSeconds
        
        RateLimitDecision d1 = provider.evaluate(key, rule);
        assertThat(d1.allowed()).isTrue();
        assertThat(d1.remaining()).isEqualTo(1); // the record uses remaining
        
        RateLimitDecision d2 = provider.evaluate(key, rule);
        assertThat(d2.allowed()).isTrue();
        assertThat(d2.remaining()).isEqualTo(0);
        
        RateLimitDecision d3 = provider.evaluate(key, rule);
        assertThat(d3.allowed()).isFalse();
        assertThat(d3.remaining()).isEqualTo(0);
    }
}
