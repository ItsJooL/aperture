package com.itsjool.aperture.spi;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record RoleCatalog(List<String> defaultRoles, List<String> declaredRoles) {

    public RoleCatalog {
        defaultRoles = validated("defaultRoles", defaultRoles, false);
        declaredRoles = validated("declaredRoles", declaredRoles, false);
        if (!declaredRoles.containsAll(defaultRoles)) {
            throw new IllegalArgumentException("defaultRoles must all be declared");
        }
    }

    private static List<String> validated(String name, List<String> values, boolean allowEmpty) {
        if (values == null || (!allowEmpty && values.isEmpty())) {
            throw new IllegalArgumentException(name + " must not be missing or empty");
        }
        List<String> copy = List.copyOf(values);
        if (copy.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(name + " must not contain blank values");
        }
        Set<String> unique = new HashSet<>(copy);
        if (unique.size() != copy.size()) {
            throw new IllegalArgumentException(name + " must not contain duplicate values");
        }
        return copy;
    }
}
