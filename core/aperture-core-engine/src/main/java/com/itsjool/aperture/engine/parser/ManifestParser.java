package com.itsjool.aperture.engine.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.SpelParseException;
import com.itsjool.aperture.engine.model.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ManifestParser {
    private final ObjectMapper mapper;
    private final JsonSchema schema;
    private final ExpressionParser spelParser;

    public ManifestParser() {
        this.mapper = new ObjectMapper(new YAMLFactory());
        this.mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schema = factory.getSchema(getClass().getResourceAsStream("/aperture-manifest-schema.json"));
        this.spelParser = new SpelExpressionParser();
    }

    public ResolvedDomainModel parseDirectory(File dir) {
        List<EntityDef> entities = new ArrayList<>();
        List<MigrationDef> migrations = new ArrayList<>();
        List<RoleDefinitionDef> roleDefinitions = new ArrayList<>();
        List<AbacPolicyDef> abacPolicies = new ArrayList<>();
        List<ApiVersionConfigDef> apiVersionConfigs = new ArrayList<>();
        List<PrincipalAttributeDefinitionDef> principalAttributeDefinitions = new ArrayList<>();
        FrameworkConfigDef config = null;
        java.util.Map<Object, String> locationMap = new java.util.IdentityHashMap<>();

        List<File> files = new ArrayList<>();
        try {
            if (dir.exists()) {
                Files.walk(dir.toPath())
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> files.add(p.toFile()));
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read directory", e);
        }

            if (files.isEmpty()) {
                return new ResolvedDomainModel(entities, migrations, new FrameworkConfigDef(List.of(), null, null, null),
                    roleDefinitions, abacPolicies, apiVersionConfigs, principalAttributeDefinitions);
            }

        for (File f : files) {
            try {
                JsonNode rawNode = mapper.readTree(f);
                Set<String> visitedFiles = new java.util.HashSet<>();
                visitedFiles.add(f.getCanonicalPath());
                JsonNode resolvedNode = resolveRefs(rawNode, f.getParentFile(), visitedFiles);
                
                if (!resolvedNode.has("kind")) {
                    continue;
                }
                
                Set<ValidationMessage> errors = schema.validate(resolvedNode);
                if (!errors.isEmpty()) {
                    throw new RuntimeException("Schema validation failed in " + f.getPath() + ": " + errors);
                }

                ManifestEnvelope env = mapper.treeToValue(resolvedNode, ManifestEnvelope.class);
                String kind = env.kind();
                JsonNode specNode = env.spec();
                String name = env.metadata() != null ? env.metadata().name() : null;

                if ("Entity".equals(kind)) {
                    EntityDef def = mapper.treeToValue(specNode, EntityDef.class);
                    EntityDef newDef = new EntityDef(name, def.plural(), def.description(), def.mcpConfig(), def.optimisticLocking(), def.softDelete(), def.tenantScoped(), def.fields(), def.permissions(), def.policies(), def.publicOperations(), def.abacRules(), def.hooks(), def.scopedBy());
                    entities.add(newDef);
                    locationMap.put(newDef, f.getPath());
                } else if ("Migration".equals(kind)) {
                    String sql = specNode.path("sql").asText(null);
                    String rollback = specNode.path("rollback").asText(null);
                    if (rollback == null || rollback.isBlank()) {
                        throw new com.itsjool.aperture.engine.validator.ManifestValidationException("Migration " + name + " must declare rollback SQL");
                    }
                    String positionAfter = specNode.path("position").path("after").asText(null);
                    migrations.add(new MigrationDef(name, sql, rollback, positionAfter));
                } else if ("FrameworkConfig".equals(kind)) {
                    config = mapper.treeToValue(specNode, FrameworkConfigDef.class);
                    locationMap.put(config, f.getPath());
                } else if ("RoleDefinition".equals(kind)) {
                    RoleDefinitionDef def = mapper.treeToValue(specNode, RoleDefinitionDef.class);
                    roleDefinitions.add(def);
                    locationMap.put(def, f.getPath());
                } else if ("AbacPolicy".equals(kind)) {
                    AbacPolicyDef def = mapper.treeToValue(specNode, AbacPolicyDef.class);
                    if (def.expression() != null) {
                        try {
                            spelParser.parseExpression(def.expression());
                        } catch (SpelParseException e) {
                            throw new RuntimeException("Invalid SpEL expression in AbacPolicy " + name, e);
                        }
                    }
                    abacPolicies.add(new AbacPolicyDef(name, def.expression()));
                } else if ("ApiVersionConfig".equals(kind)) {
                    apiVersionConfigs.add(mapper.treeToValue(specNode, ApiVersionConfigDef.class));
                } else if ("PrincipalAttributeDefinition".equals(kind)) {
                    PrincipalAttributeDefinitionDef def = mapper.treeToValue(resolvedNode, PrincipalAttributeDefinitionDef.class);
                    principalAttributeDefinitions.add(def);
                    locationMap.put(def, f.getPath());
                } else {
                    throw new RuntimeException("Unknown kind: " + kind + " in file " + f.getPath());
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed parsing " + f.getPath(), e);
            }
        }
        
        ResolvedDomainModel model = new ResolvedDomainModel(entities, migrations,
            config != null ? config : new FrameworkConfigDef(List.of(), null, null, null),
            roleDefinitions, abacPolicies, apiVersionConfigs, principalAttributeDefinitions);
            
        new com.itsjool.aperture.engine.validator.DomainModelValidator().validate(model, locationMap);
        
        return model;
    }

    private JsonNode resolveRefs(JsonNode node, File currentDir, Set<String> visitedFiles) throws Exception {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (obj.has("ref")) {
                File refFile = new File(currentDir, obj.get("ref").asText());
                String canonicalPath = refFile.getCanonicalPath();
                if (!visitedFiles.add(canonicalPath)) {
                    throw new RuntimeException("Circular reference detected: " + canonicalPath);
                }
                JsonNode refContent = mapper.readTree(refFile);
                JsonNode resolvedRefContent = resolveRefs(refContent, refFile.getParentFile(), visitedFiles);
                ObjectNode resolved = obj.deepCopy();
                resolved.remove("ref");
                if (resolvedRefContent.isObject()) {
                    resolved.setAll((ObjectNode) resolvedRefContent);
                }
                visitedFiles.remove(canonicalPath);
                return resolved;
            }
            ObjectNode resolved = mapper.createObjectNode();
            obj.fields().forEachRemaining(entry -> {
                try {
                    resolved.set(entry.getKey(), resolveRefs(entry.getValue(), currentDir, visitedFiles));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            return resolved;
        } else if (node.isArray()) {
            ArrayNode arr = mapper.createArrayNode();
            for (JsonNode child : node) {
                arr.add(resolveRefs(child, currentDir, visitedFiles));
            }
            return arr;
        }
        return node;
    }
}
