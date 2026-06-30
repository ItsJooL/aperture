package com.itsjool.aperture.spi;

import java.time.Instant;

public record ServiceAccountRecord(
        String id,
        String tenantId,
        String clientId,
        String status,
        Instant expiresAt) {
}
