package com.itsjool.aperture.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ApiKeyCreateCommand(
        String tenantId,
        String userId,
        String name,
        Instant expiresAt,
        boolean nonExpiring,
        List<String> domainRoles,
        Map<String, Object> securityAttributes) {

    public ApiKeyCreateCommand {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("API key name must not be blank");
        }
        if (nonExpiring) {
            expiresAt = null;
        }
        domainRoles = domainRoles == null ? List.of() : List.copyOf(domainRoles);
        securityAttributes = JsonAttributes.copy(securityAttributes);
    }
}
