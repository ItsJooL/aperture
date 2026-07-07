package com.itsjool.aperture.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.itsjool.aperture.spi.RateLimitDecision;
import com.itsjool.aperture.spi.RateLimitKey;
import com.itsjool.aperture.spi.RateLimitRule;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.local.LocalBucket;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

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

    /**
     * Regression test for the refill-rate bugfix: the bucket's refill amount per window must come
     * from {@code rule.burst()} (refillTokens), NOT {@code rule.capacity()}. Rather than waiting on
     * real time to observe a refill (which would be flaky), this inspects the actual {@link Bandwidth}
     * bucket4j constructed for the key - the same object {@link InMemoryRateLimitProvider#evaluate}
     * builds via {@code Refill.greedy(rule.burst(), ...)} - and asserts its refill-tokens field reflects
     * refillTokens rather than capacity. Capacity and refillTokens are deliberately configured to
     * different values so the two cannot be confused.
     */
    @Test
    void refillUsesConfiguredRefillTokensNotCapacity() throws Exception {
        InMemoryRateLimitProvider provider = new InMemoryRateLimitProvider();
        RateLimitKey key = new RateLimitKey("ip", "203.0.113.5");
        RateLimitRule rule = new RateLimitRule(100, 10, 60); // capacity != burst (refillTokens)

        provider.evaluate(key, rule); // triggers bucket creation for the key

        Bandwidth bandwidth = bandwidthFor(provider, key);

        assertThat(bandwidth.getCapacity()).isEqualTo(rule.capacity());
        assertThat(bandwidth.getRefillTokens()).isEqualTo(rule.burst());
        assertThat(bandwidth.getRefillTokens()).isNotEqualTo(bandwidth.getCapacity());
    }

    @SuppressWarnings("unchecked")
    private static Bandwidth bandwidthFor(InMemoryRateLimitProvider provider, RateLimitKey key) throws Exception {
        Field bucketsField = InMemoryRateLimitProvider.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        Cache<RateLimitKey, Bucket> buckets = (Cache<RateLimitKey, Bucket>) bucketsField.get(provider);
        Bucket bucket = buckets.getIfPresent(key);
        assertThat(bucket).as("bucket should have been created for key %s", key).isNotNull();
        LocalBucket localBucket = (LocalBucket) bucket;
        return localBucket.getConfiguration().getBandwidths()[0];
    }
}
