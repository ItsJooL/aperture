package com.itsjool.aperture.engine.validator;

import com.itsjool.aperture.engine.hook.HookSemanticException;
import com.itsjool.aperture.engine.hook.HookSemanticsResolver;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.HookDef;
import com.itsjool.aperture.engine.model.RoleDefinitionDef;
import com.itsjool.aperture.engine.model.FrameworkConfigDef;
import com.itsjool.aperture.engine.model.FieldKind;
import com.itsjool.aperture.engine.model.EntityOperations;
import com.itsjool.aperture.engine.model.McpConfig;
import com.itsjool.aperture.engine.model.McpEntityConfig;
import com.itsjool.aperture.engine.model.AbacPolicyDef;
import com.itsjool.aperture.engine.model.OneOfDef;
import com.itsjool.aperture.engine.model.PrincipalAttributeDefinitionDef;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

public class DomainModelValidator {

    private static final Set<String> VALID_OPERATIONS = Set.of("read", "create", "update", "delete");
    private static final List<String> VALID_MCP_TOOLS = EntityOperations.MCP_TOOLS;
    private static final Set<String> VALID_ABAC_VARIABLES = Set.of("user", "record", "input");
    private static final Pattern SECURITY_ATTRIBUTE_PATTERN = Pattern.compile("#user\\.securityAttributes(?:\\[['\"]([^'\"]+)['\"]\\]|\\.([a-zA-Z0-9_]+))");
    private final HookSemanticsResolver hookSemanticsResolver = new HookSemanticsResolver();

    public void validate(ResolvedDomainModel model, Map<Object, String> locationMap) {
        Set<String> knownEntityNames = new HashSet<>();
        Map<String, EntityDef> entitiesByName = new LinkedHashMap<>();
        for (EntityDef e : model.entities()) {
            knownEntityNames.add(e.name());
            entitiesByName.put(e.name(), e);
        }

        Set<String> knownOneOfNames = new HashSet<>();
        Map<String, Boolean> oneOfTenantScopes = new LinkedHashMap<>();
        Map<String, String> memberOwners = new LinkedHashMap<>();
        for (OneOfDef oneOf : model.oneOfs()) {
            String loc = locationMap.getOrDefault(oneOf, "unknown file");
            if (oneOf.name() == null || oneOf.name().isBlank()) {
                throw new ManifestValidationException("OneOf in " + loc + " must declare metadata.name");
            }
            if (!knownOneOfNames.add(oneOf.name())) {
                throw new ManifestValidationException("Duplicate oneof name in " + loc + ": " + oneOf.name());
            }
            if (oneOf.members().size() < 2) {
                throw new ManifestValidationException(
                    "OneOf in " + loc + " (" + oneOf.name() + ") must declare at least two members");
            }

            Boolean tenantScoped = null;
            for (String memberName : oneOf.members()) {
                EntityDef member = entitiesByName.get(memberName);
                if (member == null) {
                    throw new ManifestValidationException(
                        "Unknown oneof member in " + loc + " (OneOf " + oneOf.name()
                            + "): '" + memberName + "' is not declared as an entity in this domain model");
                }
                String previousOwner = memberOwners.putIfAbsent(memberName, oneOf.name());
                if (previousOwner != null) {
                    throw new ManifestValidationException(
                        "OneOf member in " + loc + " (OneOf " + oneOf.name() + "): '" + memberName
                            + "' is already a member of OneOf " + previousOwner);
                }
                if (tenantScoped == null) {
                    tenantScoped = member.tenantScoped();
                } else if (tenantScoped != member.tenantScoped()) {
                    throw new ManifestValidationException(
                        "OneOf members must use the same tenant shape in " + loc
                            + " (OneOf " + oneOf.name() + ")");
                }
            }
            if (tenantScoped != null) {
                oneOfTenantScopes.put(oneOf.name(), tenantScoped);
            }
        }

        Set<String> declaredRoles = new HashSet<>();

        for (RoleDefinitionDef def : model.roleDefinitions()) {
            if (def.roles() != null) {
                for (String roleName : def.roles().keySet()) {
                    if ("SuperAdmin".equals(roleName) || "TenantAdmin".equals(roleName)) {
                        String loc = locationMap.getOrDefault(def, "unknown file");
                        throw new ManifestValidationException("Reserved role name in " + loc + " (RoleDefinition): " + roleName);
                    }
                    if (!declaredRoles.add(roleName)) {
                        String loc = locationMap.getOrDefault(def, "unknown file");
                        throw new ManifestValidationException("Duplicate role name in " + loc + " (RoleDefinition): " + roleName);
                    }
                }
            }
        }

        if (model.frameworkConfig() != null && model.frameworkConfig().defaultRoles() != null) {
            for (String defaultRole : model.frameworkConfig().defaultRoles()) {
                if ("SuperAdmin".equals(defaultRole) || "TenantAdmin".equals(defaultRole)) {
                    String loc = locationMap.getOrDefault(model.frameworkConfig(), "unknown file");
                    throw new ManifestValidationException("Reserved role name cannot be used in defaultRoles in " + loc + " (FrameworkConfig): " + defaultRole);
                }
                if (!declaredRoles.contains(defaultRole)) {
                    String loc = locationMap.getOrDefault(model.frameworkConfig(), "unknown file");
                    throw new ManifestValidationException("Unknown default role referenced in " + loc + " (FrameworkConfig): " + defaultRole);
                }
            }
        }

        if (model.frameworkConfig() != null && model.frameworkConfig().mcp() != null) {
            String loc = locationMap.getOrDefault(model.frameworkConfig(), "unknown file");
            validateFrameworkMcpConfig(model.frameworkConfig().mcp(), loc);
        }
        
        Set<String> declaredPolicies = new HashSet<>();
        Set<String> declaredSecurityAttributes = new HashSet<>();
        
        if (model.principalAttributeDefinitions() != null) {
            for (PrincipalAttributeDefinitionDef def : model.principalAttributeDefinitions()) {
                if (def.spec() != null && def.spec().securityAttributes() != null) {
                    declaredSecurityAttributes.addAll(def.spec().securityAttributes().keySet());
                }
            }
        }

        if (model.abacPolicies() != null) {
            for (AbacPolicyDef def : model.abacPolicies()) {
                String loc = locationMap.getOrDefault(def, "unknown file");
                if (!declaredPolicies.add(def.name())) {
                    throw new ManifestValidationException("Duplicate policy name in " + loc + ": " + def.name());
                }
                String expression = def.expression();
                if (expression != null) {
                    if (expression.contains("#user.attributes")) {
                        throw new ManifestValidationException("Deprecated #user.attributes syntax used in " + loc + " (AbacPolicy " + def.name() + "). Use #user.securityAttributes instead.");
                    }
                    java.util.regex.Matcher matcher = SECURITY_ATTRIBUTE_PATTERN.matcher(expression);
                    while (matcher.find()) {
                        String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                        if (!declaredSecurityAttributes.contains(key)) {
                            throw new ManifestValidationException("Undeclared security attribute '" + key + "' used in " + loc + " (AbacPolicy " + def.name() + ")");
                        }
                    }
                }
            }
        }

        Set<String> usedPolicies = new HashSet<>();

        List<String> frameworkMcpCeiling = (model.frameworkConfig() != null && model.frameworkConfig().mcp() != null)
            ? model.frameworkConfig().mcp().tools() : null;

        for (EntityDef entity : model.entities()) {
            String loc = locationMap.getOrDefault(entity, "unknown file");

            if (entity.mcpConfig() != null) {
                validateEntityMcpConfig(entity, frameworkMcpCeiling, loc);
            }

            Set<String> publicOps = new HashSet<>();
            if (entity.publicOperations() != null) {
                for (String op : entity.publicOperations()) {
                    if (!VALID_OPERATIONS.contains(op.toLowerCase())) {
                        throw new ManifestValidationException("Malformed public operation in " + loc + " (Entity " + entity.name() + "): " + op);
                    }
                    publicOps.add(op.toLowerCase());
                }
            }
            
            Set<String> protectedOps = new HashSet<>();

            if (entity.permissions() != null) {
                for (Map.Entry<String, List<String>> entry : entity.permissions().entrySet()) {
                    String roleName = entry.getKey();
                    if ("SuperAdmin".equals(roleName) || "TenantAdmin".equals(roleName)) {
                        throw new ManifestValidationException("Reserved role name cannot be used in permissions in " + loc + " (Entity " + entity.name() + "): " + roleName);
                    }
                    if (!declaredRoles.contains(roleName)) {
                        throw new ManifestValidationException("Unknown role referenced by an operation in " + loc + " (Entity " + entity.name() + "): " + roleName);
                    }
                    List<String> ops = entry.getValue();
                    if (ops == null || ops.isEmpty()) {
                        throw new ManifestValidationException("A permission block that resolves to no role in " + loc + " (Entity " + entity.name() + " role " + roleName + ")");
                    }
                    for (String op : ops) {
                        String lowerOp = op.toLowerCase();
                        if (!VALID_OPERATIONS.contains(lowerOp)) {
                            throw new ManifestValidationException("Malformed permission operation in " + loc + " (Entity " + entity.name() + "): " + op);
                        }
                        if (publicOps.contains(lowerOp)) {
                            throw new ManifestValidationException("Operation " + op + " is public and cannot have role/policy rules in " + loc + " (Entity " + entity.name() + ")");
                        }
                        protectedOps.add(lowerOp);
                    }
                }
            }
            
            if (entity.policies() != null) {
                for (Map.Entry<String, List<String>> entry : entity.policies().entrySet()) {
                    String policyName = entry.getKey();
                    if (!declaredPolicies.contains(policyName)) {
                        throw new ManifestValidationException("Unknown policy referenced by an operation in " + loc + " (Entity " + entity.name() + "): " + policyName);
                    }
                    usedPolicies.add(policyName);
                    List<String> ops = entry.getValue();
                    if (ops == null || ops.isEmpty()) {
                        throw new ManifestValidationException("A policy block that resolves to no operations in " + loc + " (Entity " + entity.name() + " policy " + policyName + ")");
                    }
                    
                    // Validate variables for the policy
                    String expression = null;
                    if (model.abacPolicies() != null) {
                        for (AbacPolicyDef def : model.abacPolicies()) {
                            if (def.name().equals(policyName)) {
                                expression = def.expression();
                                break;
                            }
                        }
                    }
                    Set<String> variables = expression != null ? SpelVariableExtractor.getVariables(expression) : new HashSet<>();
                    Set<String> unknownVariables = new java.util.TreeSet<>(variables);
                    unknownVariables.removeAll(VALID_ABAC_VARIABLES);
                    if (!unknownVariables.isEmpty()) {
                        throw new ManifestValidationException(
                                "Unknown ABAC variable in " + loc + " (Entity " + entity.name()
                                        + " policy " + policyName + "): " + String.join(", ", unknownVariables));
                    }
                    
                    for (String op : ops) {
                        String lowerOp = op.toLowerCase();
                        if (!VALID_OPERATIONS.contains(lowerOp)) {
                            throw new ManifestValidationException("Malformed policy operation in " + loc + " (Entity " + entity.name() + "): " + op);
                        }
                        if (publicOps.contains(lowerOp)) {
                            throw new ManifestValidationException("Operation " + op + " is public and cannot have role/policy rules in " + loc + " (Entity " + entity.name() + ")");
                        }
                        protectedOps.add(lowerOp);
                        
                        // Check incompatible variables
                        if (lowerOp.equals("create")) {
                            if (variables.contains("record")) {
                                throw new ManifestValidationException("Incompatible rule phases in " + loc + " (Entity " + entity.name() + " policy " + policyName + "): 'record' is unavailable in create phase");
                            }
                        } else {
                            if (variables.contains("input")) {
                                throw new ManifestValidationException("Incompatible rule phases in " + loc + " (Entity " + entity.name() + " policy " + policyName + "): 'input' is unavailable in " + lowerOp + " phase");
                            }
                        }
                    }
                }
            }
            
            if (entity.abacRules() != null && !entity.abacRules().isEmpty()) {
                throw new ManifestValidationException("Inline abacRules are deprecated. Migrate them to named AbacPolicy documents in " + loc + " (Entity " + entity.name() + ")");
            }

            if (entity.fields() != null) {
                for (Map.Entry<String, FieldDef> fieldEntry : entity.fields().entrySet()) {
                    String fieldName = fieldEntry.getKey();
                    FieldDef field = fieldEntry.getValue();
                    if (FieldKind.from(field) == FieldKind.ONEOF) {
                        if (field.targetClass() == null || !knownOneOfNames.contains(field.targetClass())) {
                            throw new ManifestValidationException(
                                "Unknown oneof target in " + loc + " (Entity " + entity.name()
                                    + " field " + fieldName + "): '" + field.targetClass()
                                    + "' is not declared as a OneOf in this domain model");
                        }
                        Boolean targetTenantScoped = oneOfTenantScopes.get(field.targetClass());
                        if (!entity.tenantScoped() && Boolean.TRUE.equals(targetTenantScoped)) {
                            throw new ManifestValidationException(
                                "Global entity " + entity.name()
                                    + " cannot reference tenant-scoped OneOf " + field.targetClass()
                                    + " through oneof field " + entity.name() + "." + fieldName);
                        }
                        if (field.relation() != null && !field.relation().isBlank()) {
                            throw new ManifestValidationException(
                                "Invalid oneof field " + entity.name() + "." + fieldName + " in " + loc
                                    + ": this oneof field is a to-one selection and does not support relation '"
                                    + field.relation() + "'");
                        }
                        if (field.mappedBy() != null && !field.mappedBy().isBlank()) {
                            throw new ManifestValidationException(
                                "Invalid oneof field " + entity.name() + "." + fieldName + " in " + loc
                                    + ": this oneof field does not support mappedBy");
                        }
                    } else if (field.targetClass() != null && !knownEntityNames.contains(field.targetClass())) {
                        throw new ManifestValidationException(
                            "Unknown relationship target in " + loc + " (Entity " + entity.name()
                            + " field " + fieldName + "): '" + field.targetClass()
                            + "' is not declared as an entity in this domain model");
                    }
                }
            }

            if (entity.scopedBy() != null) {
                FieldDef scopeField = entity.fields() != null ? entity.fields().get(entity.scopedBy()) : null;
                if (scopeField == null) {
                    throw new ManifestValidationException(
                        "Unknown scopedBy field in " + loc + " (Entity " + entity.name()
                        + "): '" + entity.scopedBy() + "' is not declared as a field on this entity");
                }
                if (!"ref".equals(scopeField.type()) || !"ManyToOne".equalsIgnoreCase(scopeField.relation())) {
                    throw new ManifestValidationException(
                        "Invalid scopedBy field in " + loc + " (Entity " + entity.name()
                        + "): '" + entity.scopedBy() + "' must be a ManyToOne relationship field");
                }
            }

            if (entity.hooks() != null) {
                for (Map.Entry<String, HookDef> hookEntry : entity.hooks().entrySet()) {
                    String hookName = hookEntry.getKey();
                    HookDef hookDef = hookEntry.getValue();
                    try {
                        hookSemanticsResolver.resolve(entity.name(), hookName, hookDef);
                    } catch (HookSemanticException e) {
                        throw new ManifestValidationException("In " + loc + ": " + e.getMessage());
                    }
                }
            }
        }
        
        for (String policy : declaredPolicies) {
            if (!usedPolicies.contains(policy)) {
                // Find location of unused policy
                String loc = "unknown file";
                if (model.abacPolicies() != null) {
                    for (AbacPolicyDef def : model.abacPolicies()) {
                        if (def.name().equals(policy)) {
                            loc = locationMap.getOrDefault(def, "unknown file");
                            break;
                        }
                    }
                }
                throw new ManifestValidationException("Unattached policy in " + loc + ": " + policy);
            }
        }
    }

    private void validateFrameworkMcpConfig(McpConfig config, String loc) {
        validateMcpTools(config.tools(), loc, "FrameworkConfig");
        if (config.transport() != null && !config.transport().isBlank()
                && !"stateless".equalsIgnoreCase(config.transport())) {
            throw new ManifestValidationException(
                "Invalid MCP transport in " + loc + " (FrameworkConfig): " + config.transport()
                    + ". Supported transports: stateless");
        }
    }

    private void validateEntityMcpConfig(EntityDef entity, List<String> frameworkMcpCeiling, String loc) {
        McpEntityConfig config = entity.mcpConfig();
        String entityName = entity.name();
        validateMcpTools(config.tools(), loc, "Entity " + entityName);

        if (config.tools() != null && !config.tools().isEmpty()) {
            // enabled: false together with a non-empty tools list is contradictory: silently
            // picking one of the two is exactly the ambiguity this plan closes.
            if (Boolean.FALSE.equals(config.enabled())) {
                throw new ManifestValidationException(
                    "Entity " + entityName + " declares mcp.enabled: false together with a "
                        + "non-empty mcp.tools list in " + loc + ". This is contradictory: remove "
                        + "the tools list, or remove enabled: false.");
            }

            Set<String> derived = EntityOperations.derivedMcpTools(entity);
            // The ceiling comes straight from the manifest, where tool names are case-insensitive;
            // it is compared below against a lower-cased tool name, so it must be normalized too.
            Set<String> ceiling = (frameworkMcpCeiling != null && !frameworkMcpCeiling.isEmpty())
                ? EntityOperations.normalizedOps(frameworkMcpCeiling) : new HashSet<>(EntityOperations.MCP_TOOLS);

            for (String tool : config.tools()) {
                String normalized = tool.toLowerCase();
                if (!derived.contains(normalized)) {
                    throw new ManifestValidationException(
                        "Entity " + entityName + " declares MCP tool '" + tool + "' in " + loc
                            + " but no role, policy, or publicOperations grants the operation "
                            + "that tool requires. Configuration can only restrict the tools an "
                            + "entity's own access rules already permit, never widen them.");
                }
                if (!ceiling.contains(normalized)) {
                    throw new ManifestValidationException(
                        "Entity " + entityName + " declares MCP tool '" + tool + "' in " + loc
                            + " but the framework MCP ceiling (spec.mcp.tools) does not include "
                            + "it. An entity's tools can only narrow the framework ceiling, never "
                            + "exceed it.");
                }
            }
        }
    }

    private void validateMcpTools(List<String> tools, String loc, String context) {
        if (tools == null) return;
        for (String tool : tools) {
            String normalized = tool != null ? tool.toLowerCase() : "";
            if (!VALID_MCP_TOOLS.contains(normalized)) {
                throw new ManifestValidationException(
                    "Invalid MCP tool in " + loc + " (" + context + "): " + tool
                        + ". Supported tools: " + String.join(", ", VALID_MCP_TOOLS));
            }
        }
    }
}
