package com.itsjool.aperture.generation.target;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.generation.spi.ApertureGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import com.itsjool.aperture.generation.spi.ApertureGenerationTarget;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

public class RuntimeMetadataGenerationTarget implements ApertureGenerationTarget {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "runtime-metadata";
    }

    @Override
    public boolean enabled(ApertureGenerationRequest request) {
        return true;
    }

    @Override
    public void generate(ApertureGenerationRequest request, ApertureGenerationContext context) throws Exception {
        var model = request.model();
        var activeVersions = request.activeVersions();

        TreeSet<String> declaredRoles = new TreeSet<>();
        declaredRoles.add("TenantAdmin");
        declaredRoles.add("SuperAdmin");
        model.roleDefinitions().forEach(def -> declaredRoles.addAll(def.roles().keySet()));

        TreeSet<String> lockingEntities = new TreeSet<>();
        for (EntityDef entity : model.entities()) {
            if (entity.optimisticLocking()) {
                String plural = plural(entity);
                lockingEntities.add(plural);
            }
        }

        TreeSet<String> tenantScopedApiResources = new TreeSet<>();
        for (EntityDef entity : model.entities()) {
            if (entity.tenantScoped()) {
                tenantScopedApiResources.add(plural(entity));
            }
        }

        LinkedHashSet<String> allowedMethods = computeAllowedHttpMethods(model);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("activeVersions", activeVersions);
        metadata.put("defaultRoles", model.frameworkConfig().defaultRoles());
        metadata.put("declaredRoles", declaredRoles);
        metadata.put("lockingEntities", lockingEntities);
        metadata.put("tenancyMode", model.frameworkConfig().tenancyMode().name().toLowerCase());
        metadata.put("allowedHttpMethods", allowedMethods);
        metadata.put("tenantScopedApiResources", tenantScopedApiResources);

        TreeSet<String> securityAttributeKeys = new TreeSet<>();
        Map<String, Object> securityAttributeDefinitions = new TreeMap<>();
        model.principalAttributeDefinitions().stream()
            .filter(def -> def.spec() != null && def.spec().securityAttributes() != null)
            .forEach(def -> def.spec().securityAttributes().forEach((name, attribute) -> {
                securityAttributeKeys.add(name);
                Map<String, Object> definition = new LinkedHashMap<>();
                definition.put("type", attribute.type());
                definition.put("allowedValues", attribute.allowedValues());
                definition.put("personalKeyDelegation", attribute.personalKeyDelegation());
                definition.put("serviceAccountAssignable", attribute.serviceAccountAssignable());
                securityAttributeDefinitions.put(name, definition);
            }));
        metadata.put("securityAttributeKeys", securityAttributeKeys);
        metadata.put("securityAttributeDefinitions", securityAttributeDefinitions);

        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
        context.writeResource(Path.of("aperture/aperture-runtime-metadata.json"), json);
    }

    private LinkedHashSet<String> computeAllowedHttpMethods(com.itsjool.aperture.engine.model.ResolvedDomainModel model) {
        LinkedHashSet<String> methods = new LinkedHashSet<>();
        methods.add("OPTIONS");
        for (EntityDef entity : model.entities()) {
            if (entity.permissions() != null) {
                entity.permissions().values().stream()
                    .flatMap(java.util.Collection::stream)
                    .forEach(op -> operationToMethod(op).ifPresent(methods::add));
            }
            if (entity.publicOperations() != null) {
                entity.publicOperations().forEach(op -> operationToMethod(op).ifPresent(methods::add));
            }
        }
        return methods;
    }

    private Optional<String> operationToMethod(String operation) {
        return switch (operation.toLowerCase()) {
            case "read"   -> Optional.of("GET");
            case "create" -> Optional.of("POST");
            case "update" -> Optional.of("PATCH");
            case "delete" -> Optional.of("DELETE");
            default       -> Optional.empty();
        };
    }

    private String plural(EntityDef entity) {
        return entity.plural() != null ? entity.plural().toLowerCase() : entity.name().toLowerCase() + "s";
    }
}
