package com.itsjool.aperture.spi;
public record RateLimitDecision(boolean allowed, int remaining, long retryAfterSeconds) {}
