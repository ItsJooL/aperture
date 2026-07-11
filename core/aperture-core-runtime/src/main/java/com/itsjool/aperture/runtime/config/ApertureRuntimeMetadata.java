package com.itsjool.aperture.runtime.config;

import com.itsjool.aperture.spi.RoleCatalog;
import com.itsjool.aperture.spi.SecurityAttributeDefinition;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ApertureRuntimeMetadata(
        List<String> activeVersions, List<String> defaultRoles, List<String> declaredRoles,
        Set<String> lockingEntities, TenancyMode tenancyMode, Set<String> allowedHttpMethods,
        Set<String> securityAttributeKeys, Map<String, SecurityAttributeDefinition> securityAttributeDefinitions,
        Set<String> tenantScopedApiResources, Map<String, OneOfMetadata> oneOfs) {
    public ApertureRuntimeMetadata(List<String> activeVersions, List<String> defaultRoles, List<String> declaredRoles,
            Set<String> lockingEntities, TenancyMode tenancyMode, Set<String> allowedHttpMethods,
            Set<String> securityAttributeKeys) {
        this(activeVersions, defaultRoles, declaredRoles, lockingEntities, tenancyMode, allowedHttpMethods,
                securityAttributeKeys, Map.of(), null, Map.of());
    }

    public ApertureRuntimeMetadata(List<String> activeVersions, List<String> defaultRoles, List<String> declaredRoles,
            Set<String> lockingEntities, TenancyMode tenancyMode, Set<String> allowedHttpMethods,
            Set<String> securityAttributeKeys, Map<String, SecurityAttributeDefinition> securityAttributeDefinitions) {
        this(activeVersions, defaultRoles, declaredRoles, lockingEntities, tenancyMode, allowedHttpMethods,
                securityAttributeKeys, securityAttributeDefinitions, null, Map.of());
    }

    public ApertureRuntimeMetadata(List<String> activeVersions, List<String> defaultRoles, List<String> declaredRoles,
            Set<String> lockingEntities, TenancyMode tenancyMode, Set<String> allowedHttpMethods,
            Set<String> securityAttributeKeys, Map<String, SecurityAttributeDefinition> securityAttributeDefinitions,
            Set<String> tenantScopedApiResources) {
        this(activeVersions, defaultRoles, declaredRoles, lockingEntities, tenancyMode, allowedHttpMethods,
                securityAttributeKeys, securityAttributeDefinitions, tenantScopedApiResources, Map.of());
    }

    public ApertureRuntimeMetadata {
        activeVersions = validateVersions(activeVersions);
        RoleCatalog catalog = new RoleCatalog(defaultRoles, declaredRoles);
        defaultRoles = catalog.defaultRoles();
        declaredRoles = catalog.declaredRoles();
        lockingEntities = lockingEntities != null ? Set.copyOf(lockingEntities) : Set.of();
        if (tenancyMode == null) tenancyMode = TenancyMode.POOL;
        allowedHttpMethods = allowedHttpMethods != null
            ? Set.copyOf(allowedHttpMethods)
            : Set.of("GET", "POST", "PATCH", "DELETE", "OPTIONS");
        securityAttributeDefinitions = securityAttributeDefinitions != null ? Map.copyOf(securityAttributeDefinitions) : Map.of();
        securityAttributeKeys = !securityAttributeDefinitions.isEmpty()
                ? Set.copyOf(securityAttributeDefinitions.keySet())
                : (securityAttributeKeys != null ? Set.copyOf(securityAttributeKeys) : Set.of());
        tenantScopedApiResources = tenantScopedApiResources != null ? Set.copyOf(tenantScopedApiResources) : Set.of();
        oneOfs = oneOfs != null ? Map.copyOf(oneOfs) : Map.of();
    }

    public RoleCatalog roleCatalog() {
        return new RoleCatalog(defaultRoles, declaredRoles);
    }

    private static List<String> validateVersions(List<String> versions) {
        if (versions == null || versions.isEmpty()) {
            throw new IllegalArgumentException("activeVersions must not be missing or empty");
        }
        List<String> copy = List.copyOf(versions);
        if (copy.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("activeVersions must not contain blank values");
        }
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("activeVersions must not contain duplicate values");
        }
        return copy;
    }

    public record OneOfMetadata(
            String name, List<String> members, List<String> memberResourceTypes, Set<String> fields) {
        public OneOfMetadata {
            members = members != null ? List.copyOf(members) : List.of();
            memberResourceTypes = memberResourceTypes != null ? List.copyOf(memberResourceTypes) : List.of();
            fields = fields != null ? Set.copyOf(fields) : Set.of();
        }
    }
}
