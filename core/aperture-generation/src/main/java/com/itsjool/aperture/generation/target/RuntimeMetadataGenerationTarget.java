package com.itsjool.aperture.generation.target;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.FieldKind;
import com.itsjool.aperture.engine.model.OneOfDef;
import com.itsjool.aperture.generation.spi.ApertureGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import com.itsjool.aperture.generation.spi.ApertureGenerationTarget;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
        metadata.put("defaultRoles", model.apertureConfig().defaultRoles());
        metadata.put("declaredRoles", declaredRoles);
        metadata.put("lockingEntities", lockingEntities);
        metadata.put("tenancyMode", model.apertureConfig().tenancyMode().name().toLowerCase());
        metadata.put("allowedHttpMethods", allowedMethods);
        metadata.put("tenantScopedApiResources", tenantScopedApiResources);
        metadata.put("oneOfs", oneOfMetadata(model));

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

    private Map<String, Object> oneOfMetadata(com.itsjool.aperture.engine.model.ResolvedDomainModel model) {
        Map<String, EntityDef> entitiesByName = new TreeMap<>();
        for (EntityDef entity : model.entities()) {
            entitiesByName.put(entity.name(), entity);
        }

        Map<String, Object> oneOfs = new TreeMap<>();
        model.oneOfs().stream()
            .sorted(Comparator.comparing(OneOfDef::name))
            .forEach(oneOf -> {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("name", oneOf.name());
                metadata.put("members", List.copyOf(oneOf.members()));
                metadata.put("memberResourceTypes", oneOf.members().stream()
                    .map(entitiesByName::get)
                    .map(this::plural)
                    .toList());
                metadata.put("fields", oneOfFields(model, oneOf.name()));
                oneOfs.put(oneOf.name(), metadata);
            });
        return oneOfs;
    }

    private List<Map<String, Object>> oneOfFields(
            com.itsjool.aperture.engine.model.ResolvedDomainModel model, String oneOfName) {
        List<Map<String, Object>> fields = new ArrayList<>();
        model.entities().stream()
            .sorted(Comparator.comparing(EntityDef::name))
            .forEach(entity -> {
                if (entity.fields() == null) {
                    return;
                }
                entity.fields().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .filter(entry -> isOneOfTarget(entry.getValue(), oneOfName))
                    .forEach(entry -> fields.add(Map.of(
                        "resource", plural(entity),
                        "field", entry.getKey(),
                        "required", entry.getValue().required())));
            });
        return fields;
    }

    private boolean isOneOfTarget(FieldDef field, String oneOfName) {
        return FieldKind.from(field) == FieldKind.ONEOF && oneOfName.equals(field.targetClass());
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
