package com.itsjool.aperture.spi;

import java.time.Instant;

public record ApiKeyRecord(
        String id,
        String tenantId,
        String userId,
        String status,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt) {

}
