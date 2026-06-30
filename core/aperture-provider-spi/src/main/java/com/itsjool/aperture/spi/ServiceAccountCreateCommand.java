package com.itsjool.aperture.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ServiceAccountCreateCommand(
        String tenantId,
        String accountId,
        String clientId,
        List<String> roleNames,
        Instant expiresAt,
        Map<String, Object> securityAttributes) {
    public ServiceAccountCreateCommand {
        securityAttributes = securityAttributes != null ? Map.copyOf(securityAttributes) : Map.of();
    }
}
