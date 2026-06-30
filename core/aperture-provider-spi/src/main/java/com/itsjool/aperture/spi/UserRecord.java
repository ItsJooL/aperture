package com.itsjool.aperture.spi;

import java.util.Map;

public record UserRecord(
        String id,
        String username,
        String tenantId,
        String status,
        boolean superAdmin,
        Map<String, Object> profile,
        Map<String, Object> securityAttributes) {
    public UserRecord {
        profile = JsonAttributes.copy(profile);
        securityAttributes = JsonAttributes.copy(securityAttributes);
    }
}
