package com.itsjool.aperture.spi;
public record RateLimitRule(int capacity, int burst, int windowSeconds) {}
