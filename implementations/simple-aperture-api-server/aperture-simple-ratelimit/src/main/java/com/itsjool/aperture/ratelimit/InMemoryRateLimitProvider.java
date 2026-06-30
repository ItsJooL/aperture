package com.itsjool.aperture.ratelimit;

import com.itsjool.aperture.spi.RateLimitProvider;
import com.itsjool.aperture.spi.RateLimitDecision;
import com.itsjool.aperture.spi.RateLimitKey;
import com.itsjool.aperture.spi.RateLimitRule;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class InMemoryRateLimitProvider implements RateLimitProvider {
    private final Cache<RateLimitKey, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(100_000)
            .build();

    @Override
    public RateLimitDecision evaluate(RateLimitKey key, RateLimitRule rule) {
        Bucket bucket = buckets.get(key, k -> Bucket.builder()
                .addLimit(io.github.bucket4j.Bandwidth.classic(rule.capacity(), io.github.bucket4j.Refill.greedy(rule.capacity(), Duration.ofSeconds(rule.windowSeconds()))))
                .build());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return new RateLimitDecision(
                probe.isConsumed(),
                (int) probe.getRemainingTokens(),
                probe.isConsumed() ? 0 : probe.getNanosToWaitForRefill() / 1_000_000_000
        );
    }
}
