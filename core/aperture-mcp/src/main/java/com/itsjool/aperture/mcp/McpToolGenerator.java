package com.itsjool.aperture.mcp;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.McpConfig;
import com.itsjool.aperture.engine.model.McpEntityConfig;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class McpToolGenerator {

    private static final String PKG = "com.itsjool.aperture.generated.mcp";
    private static final List<String> ALL_OPS = List.of("list", "get", "create", "update", "delete");

    private static final ClassName ADAPTER  = ClassName.get("com.itsjool.aperture.mcp", "McpRequestAdapter");
    private static final ClassName TOOL     = ClassName.get("org.springframework.ai.tool.annotation", "Tool");
    private static final ClassName TOOL_PARAM = ClassName.get("org.springframework.ai.tool.annotation", "ToolParam");
    private static final ClassName COMPONENT  = ClassName.get("org.springframework.stereotype", "Component");
    private static final ClassName CONDITIONAL = ClassName.get("org.springframework.boot.autoconfigure.condition", "ConditionalOnProperty");
    private static final ClassName STRING   = ClassName.get(String.class);
    private static final ClassName INTEGER  = ClassName.get(Integer.class);
    private static final ClassName HASH_MAP = ClassName.get("java.util", "HashMap");
    private static final ClassName MAP      = ClassName.get("java.util", "Map");

    private static final ClassName OBS_REGISTRY = ClassName.get("io.micrometer.observation", "ObservationRegistry");
    private static final ClassName OBSERVATION  = ClassName.get("io.micrometer.observation", "Observation");

    /**
     * Backward-compatible overload for callers that don't need cross-entity relationship
     * resolution (e.g. entities with no ManyToOne fields). ManyToOne fields generated this way
     * fall back to the default plural rule ({@link #resourceTypeOf}) for their target's resource
     * type, since there is no registry to consult.
     */
    public String generateForEntity(EntityDef entity, McpConfig globalConfig,
                                    McpEntityConfig entityConfig, String version) {
        return generateForEntity(entity, globalConfig, entityConfig, version, Map.of());
    }

    /**
     * @param resourceTypesByEntity entity name -&gt; JSON:API resource type (plural, lowercased),
     *                              used to resolve the target type of ManyToOne relationship
     *                              fields. Entities missing from the map fall back to the default
     *                              plural rule via {@link #resourceTypeOf}.
     */
    public String generateForEntity(EntityDef entity, McpConfig globalConfig,
                                    McpEntityConfig entityConfig, String version,
                                    Map<String, String> resourceTypesByEntity) {
        List<String> tools = effectiveTools(globalConfig, entityConfig);
        String entityName  = entity.name();
        String pluralName  = resourceTypeOf(entityName, entity.plural());
        String className   = entityName + "V" + version + "McpTools";
        String apiPath     = "/api/v" + version + "/" + pluralName;
        Map<String, String> resourceTypes = resourceTypesByEntity != null ? resourceTypesByEntity : Map.of();

        TypeSpec.Builder cls = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(COMPONENT)
            .addAnnotation(AnnotationSpec.builder(CONDITIONAL)
                .addMember("value", "$S", "aperture.mcp.enabled")
                .build())
            .addField(FieldSpec.builder(ADAPTER, "adapter", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(OBS_REGISTRY, "observationRegistry", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ADAPTER, "adapter")
                .addParameter(OBS_REGISTRY, "observationRegistry")
                .addStatement("this.adapter = adapter")
                .addStatement("this.observationRegistry = observationRegistry")
                .build());

        if (tools.contains("list"))   cls.addMethod(listMethod(entity, pluralName, apiPath));
        if (tools.contains("get"))    cls.addMethod(getMethod(entity, apiPath));
        if (tools.contains("create")) cls.addMethod(createMethod(entity, pluralName, apiPath, resourceTypes));
        if (tools.contains("update")) cls.addMethod(updateMethod(entity, pluralName, apiPath, resourceTypes));
        if (tools.contains("delete")) cls.addMethod(deleteMethod(entity, apiPath));

        return JavaFile.builder(PKG, cls.build()).build().toString();
    }

    /**
     * The single shared plural rule: an explicit plural (lowercased) wins, otherwise the entity
     * name is lowercased and suffixed with "s". Used both to resolve an entity's own JSON:API
     * resource type and to resolve a ManyToOne field's relationship target type, so the two can
     * never drift apart.
     */
    public static String resourceTypeOf(String entityName, String pluralOverride) {
        return pluralOverride != null ? pluralOverride.toLowerCase() : entityName.toLowerCase() + "s";
    }

    private MethodSpec listMethod(EntityDef entity, String pluralName, String apiPath) {
        return MethodSpec.methodBuilder("list" + cap(pluralName))
            .addModifiers(Modifier.PUBLIC)
            .returns(STRING)
            .addAnnotation(toolAnnotation("list_" + pluralName.toLowerCase(), toolDescription("list", entity)))
            .addParameter(toolParam(STRING, "filter", "RSQL filter expression, e.g. name=='Acme*'"))
            .addParameter(toolParam(INTEGER, "page", "Zero-based page number (default: 0)"))
            .addParameter(toolParam(INTEGER, "pageSize", "Number of results per page (default: 20, max: 100)"))
            .addStatement("return $T.createNotStarted($S, observationRegistry).lowCardinalityKeyValue($S, $S).lowCardinalityKeyValue($S, $S).observe(() -> adapter.get($S, filter, page, pageSize))",
                OBSERVATION, "aperture.mcp.tool_call", "tool.name", "list_" + pluralName.toLowerCase(), "server.name", "aperture-mcp", apiPath)
            .build();
    }

    private MethodSpec getMethod(EntityDef entity, String apiPath) {
        return MethodSpec.methodBuilder("get" + entity.name())
            .addModifiers(Modifier.PUBLIC)
            .returns(STRING)
            .addAnnotation(toolAnnotation("get_" + entity.name().toLowerCase(), toolDescription("get", entity)))
            .addParameter(toolParam(STRING, "id", "UUID of the " + entity.name().toLowerCase() + " to retrieve"))
            .addStatement("return $T.createNotStarted($S, observationRegistry).lowCardinalityKeyValue($S, $S).lowCardinalityKeyValue($S, $S).observe(() -> adapter.get($S + id))",
                OBSERVATION, "aperture.mcp.tool_call", "tool.name", "get_" + entity.name().toLowerCase(), "server.name", "aperture-mcp", apiPath + "/")
            .build();
    }

    private MethodSpec createMethod(EntityDef entity, String pluralName, String apiPath,
                                     Map<String, String> resourceTypesByEntity) {
        MethodSpec.Builder m = MethodSpec.methodBuilder("create" + entity.name())
            .addModifiers(Modifier.PUBLIC)
            .returns(STRING)
            .addAnnotation(toolAnnotation("create_" + entity.name().toLowerCase(), toolDescription("create", entity)));

        List<ParamMapping> mappings = new ArrayList<>();
        List<RelationshipMapping> relationships = new ArrayList<>();
        for (var e : entity.fields().entrySet()) {
            if (isOneOf(e.getValue())) {
                String typeParam = e.getKey() + "_type";
                String idParam = e.getKey() + "_id";
                m.addParameter(toolParam(STRING, typeParam, "JSON:API resource type for " + e.getKey()));
                m.addParameter(toolParam(STRING, idParam, "UUID of the related " + e.getKey()));
                relationships.add(RelationshipMapping.oneOf(typeParam, idParam, e.getKey()));
                continue;
            }
            if (!isParameter(e.getValue())) continue;
            String pName = paramName(e.getKey(), e.getValue());
            m.addParameter(toolParam(STRING, pName, fieldDescription(e.getKey(), e.getValue())));
            if (isRelationship(e.getValue())) {
                relationships.add(RelationshipMapping.fixed(pName, e.getKey(),
                    targetResourceType(e.getValue(), resourceTypesByEntity)));
            } else {
                mappings.add(new ParamMapping(pName, e.getKey()));
            }
        }

        m.addStatement("$T<$T, $T> attrs = new $T<>()", MAP, STRING, ClassName.get(Object.class), HASH_MAP);
        for (ParamMapping mapping : mappings) {
            m.addStatement("attrs.put($S, $L)", mapping.fieldName(), mapping.paramName());
        }
        m.addStatement("$T<$T, $T> rels = new $T<>()", MAP, STRING, ClassName.get(Object.class), HASH_MAP);
        for (RelationshipMapping rel : relationships) {
            if (rel.oneOf()) {
                m.addStatement("rels.put($S, adapter.relationshipRef($L, $L))",
                    rel.fieldName(), rel.typeParamName(), rel.idParamName());
            } else {
                m.addStatement("rels.put($S, adapter.relationshipRef($S, $L))",
                    rel.fieldName(), rel.targetResourceType(), rel.idParamName());
            }
        }
        m.addStatement("return $T.createNotStarted($S, observationRegistry).lowCardinalityKeyValue($S, $S).lowCardinalityKeyValue($S, $S).observe(() -> adapter.post($S, adapter.buildBody($S, null, attrs, rels)))",
            OBSERVATION, "aperture.mcp.tool_call", "tool.name", "create_" + entity.name().toLowerCase(), "server.name", "aperture-mcp", apiPath, pluralName);
        return m.build();
    }

    private MethodSpec updateMethod(EntityDef entity, String pluralName, String apiPath,
                                     Map<String, String> resourceTypesByEntity) {
        MethodSpec.Builder m = MethodSpec.methodBuilder("update" + entity.name())
            .addModifiers(Modifier.PUBLIC)
            .returns(STRING)
            .addAnnotation(toolAnnotation("update_" + entity.name().toLowerCase(), toolDescription("update", entity)))
            .addParameter(toolParam(STRING, "id", "UUID of the " + entity.name().toLowerCase() + " to update"));

        List<ParamMapping> mappings = new ArrayList<>();
        List<RelationshipMapping> relationships = new ArrayList<>();
        for (var e : entity.fields().entrySet()) {
            if (isOneOf(e.getValue())) {
                String typeParam = e.getKey() + "_type";
                String idParam = e.getKey() + "_id";
                m.addParameter(toolParam(STRING, typeParam, "JSON:API resource type for " + e.getKey()));
                m.addParameter(toolParam(STRING, idParam, "UUID of the related " + e.getKey()));
                relationships.add(RelationshipMapping.oneOf(typeParam, idParam, e.getKey()));
                continue;
            }
            if (!isParameter(e.getValue())) continue;
            String pName = paramName(e.getKey(), e.getValue());
            String desc = fieldDescription(e.getKey(), e.getValue()).replace(" (required)", "");
            m.addParameter(toolParam(STRING, pName, desc));
            if (isRelationship(e.getValue())) {
                relationships.add(RelationshipMapping.fixed(pName, e.getKey(),
                    targetResourceType(e.getValue(), resourceTypesByEntity)));
            } else {
                mappings.add(new ParamMapping(pName, e.getKey()));
            }
        }

        m.addStatement("$T<$T, $T> attrs = new $T<>()", MAP, STRING, ClassName.get(Object.class), HASH_MAP);
        for (ParamMapping mapping : mappings) {
            m.beginControlFlow("if ($L != null)", mapping.paramName())
                .addStatement("attrs.put($S, $L)", mapping.fieldName(), mapping.paramName())
                .endControlFlow();
        }
        m.addStatement("$T<$T, $T> rels = new $T<>()", MAP, STRING, ClassName.get(Object.class), HASH_MAP);
        for (RelationshipMapping rel : relationships) {
            // A null id means the caller omitted this relationship param on a partial update —
            // skip it entirely so buildBody doesn't touch the existing linked record.
            if (rel.oneOf()) {
                m.beginControlFlow("if ($L != null && $L != null)", rel.typeParamName(), rel.idParamName())
                    .addStatement("rels.put($S, adapter.relationshipRef($L, $L))",
                        rel.fieldName(), rel.typeParamName(), rel.idParamName())
                    .endControlFlow();
            } else {
                m.beginControlFlow("if ($L != null)", rel.idParamName())
                    .addStatement("rels.put($S, adapter.relationshipRef($S, $L))",
                        rel.fieldName(), rel.targetResourceType(), rel.idParamName())
                    .endControlFlow();
            }
        }
        m.addStatement("return $T.createNotStarted($S, observationRegistry).lowCardinalityKeyValue($S, $S).lowCardinalityKeyValue($S, $S).observe(() -> adapter.patch($S + id, adapter.buildBody($S, id, attrs, rels)))",
            OBSERVATION, "aperture.mcp.tool_call", "tool.name", "update_" + entity.name().toLowerCase(), "server.name", "aperture-mcp", apiPath + "/", pluralName);
        return m.build();
    }

    private boolean isRelationship(FieldDef field) {
        return "ManyToOne".equals(field.relation());
    }

    private boolean isOneOf(FieldDef field) {
        return "oneof".equals(field.type());
    }

    private String targetResourceType(FieldDef field, Map<String, String> resourceTypesByEntity) {
        String targetEntity = field.targetClass();
        String resolved = resourceTypesByEntity != null ? resourceTypesByEntity.get(targetEntity) : null;
        return resolved != null ? resolved : resourceTypeOf(targetEntity, null);
    }

    private record ParamMapping(String paramName, String fieldName) {}

    private record RelationshipMapping(String typeParamName, String idParamName, String fieldName,
                                       String targetResourceType, boolean oneOf) {
        static RelationshipMapping fixed(String idParamName, String fieldName, String targetResourceType) {
            return new RelationshipMapping(null, idParamName, fieldName, targetResourceType, false);
        }

        static RelationshipMapping oneOf(String typeParamName, String idParamName, String fieldName) {
            return new RelationshipMapping(typeParamName, idParamName, fieldName, null, true);
        }
    }

    private MethodSpec deleteMethod(EntityDef entity, String apiPath) {
        return MethodSpec.methodBuilder("delete" + entity.name())
            .addModifiers(Modifier.PUBLIC)
            .returns(STRING)
            .addAnnotation(toolAnnotation("delete_" + entity.name().toLowerCase(), toolDescription("delete", entity)))
            .addParameter(toolParam(STRING, "id", "UUID of the " + entity.name().toLowerCase() + " to delete"))
            .addStatement("return $T.createNotStarted($S, observationRegistry).lowCardinalityKeyValue($S, $S).lowCardinalityKeyValue($S, $S).observe(() -> adapter.delete($S + id))",
                OBSERVATION, "aperture.mcp.tool_call", "tool.name", "delete_" + entity.name().toLowerCase(), "server.name", "aperture-mcp", apiPath + "/")
            .build();
    }

    private AnnotationSpec toolAnnotation(String name, String description) {
        return AnnotationSpec.builder(TOOL)
            .addMember("name", "$S", name)
            .addMember("description", "$S", description)
            .build();
    }

    private ParameterSpec toolParam(ClassName type, String name, String description) {
        return ParameterSpec.builder(type, name)
            .addAnnotation(AnnotationSpec.builder(TOOL_PARAM)
                .addMember("description", "$S", description)
                .build())
            .build();
    }

    private String toolDescription(String operation, EntityDef entity) {
        String blurb  = entity.description() != null ? entity.description()
            : "A " + entity.name() + " within the current tenant";
        String plural = entity.plural() != null ? entity.plural() : entity.name().toLowerCase() + "s";
        return switch (operation) {
            case "list"   -> "List " + plural + " for the current tenant. " + blurb + ". Supports RSQL filtering and pagination.";
            case "get"    -> "Retrieve a single " + entity.name() + " by its UUID. " + blurb + ".";
            case "create" -> "Create a new " + entity.name() + ". " + blurb + ".";
            case "update" -> "Update an existing " + entity.name() + ". Only provided fields are changed.";
            case "delete" -> "Delete a " + entity.name() + " by its UUID.";
            default       -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
    }

    private String fieldDescription(String fieldName, FieldDef field) {
        String base = field.description() != null ? field.description()
            : "ManyToOne".equals(field.relation()) ? "UUID of the related " + field.targetClass()
            : fieldName;
        return field.required() ? base + " (required)" : base;
    }

    private boolean isParameter(FieldDef field) {
        return field.relation() == null || "ManyToOne".equals(field.relation());
    }

    private String paramName(String fieldName, FieldDef field) {
        return "ManyToOne".equals(field.relation()) ? fieldName + "_id" : fieldName;
    }

    private List<String> effectiveTools(McpConfig global, McpEntityConfig entity) {
        if (entity != null && entity.tools() != null) return entity.tools();
        if (global != null && global.tools() != null && !global.tools().isEmpty()) return global.tools();
        return ALL_OPS;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
