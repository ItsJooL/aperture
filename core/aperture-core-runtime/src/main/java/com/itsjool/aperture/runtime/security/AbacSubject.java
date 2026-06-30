package com.itsjool.aperture.runtime.security;

import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/**
 * Immutable core subject object exposed to SpEL expressions as {@code #user}.
 * Allowed properties:
 * - identityId: String ID of the authenticated user or client.
 * - tenantId: String ID of the subject's tenant.
 * - roles: Set of String roles.
 * - securityAttributes: Map of custom security attributes from server-side state.
 * - credentialType: String indicating if this is a user or service account.
 */
public record AbacSubject(
        String identityId,
        String tenantId,
        Set<String> roles,
        Map<String, Object> securityAttributes,
        String credentialType
) {
    public AbacSubject {
        roles = roles == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
        securityAttributes = securityAttributes == null ? Map.of() : immutableMap(securityAttributes);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> copy.put(key, immutableValue(nestedValue)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> set) {
            LinkedHashSet<Object> copy = new LinkedHashSet<>();
            set.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableSet(copy);
        }
        return value;
    }
}
