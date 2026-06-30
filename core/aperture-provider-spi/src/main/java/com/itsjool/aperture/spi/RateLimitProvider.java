package com.itsjool.aperture.spi;
public interface RateLimitProvider { RateLimitDecision evaluate(RateLimitKey key, RateLimitRule rule); }
