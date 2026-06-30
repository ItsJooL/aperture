package com.itsjool.aperture.auth;

import com.itsjool.aperture.spi.PrincipalKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AuthenticatedAccount(
        String userId,
        String username,
        String tenantId,
        List<String> roles,
        PrincipalKind kind,
        Map<String, Object> profile,
        Map<String, Object> securityAttributes,
        boolean isTenantAdmin,
        boolean isSuperAdmin,
        boolean forcePasswordChange) {

    public AuthenticatedAccount {
        roles = List.copyOf(roles);
        profile = Collections.unmodifiableMap(new LinkedHashMap<>(profile));
        securityAttributes = Collections.unmodifiableMap(new LinkedHashMap<>(securityAttributes));
    }
}
