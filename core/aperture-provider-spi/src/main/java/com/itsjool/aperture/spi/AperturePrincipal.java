package com.itsjool.aperture.spi;
import java.util.Map;
import java.util.Set;
public record AperturePrincipal(
        String userId,
        String tenantId,
        Set<String> roles,
        PrincipalKind kind,
        Map<String, Object> profile,
        Map<String, Object> securityAttributes,
        Set<String> scopes,
        boolean superAdmin,
        boolean tenantAdmin) implements java.security.Principal {

    /** Backward-compat constructor: no scopes, no platform authority flags. */
    public AperturePrincipal(String userId, String tenantId, Set<String> roles, PrincipalKind kind,
            Map<String, Object> profile, Map<String, Object> securityAttributes) {
        this(userId, tenantId, roles, kind, profile, securityAttributes, Set.of(), false, false);
    }

    @Override
    public String getName() {
        return userId;
    }
}
