package com.itsjool.aperture.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflective bridge to the generated {@code com.itsjool.aperture.generated.mcp.McpToolRegistry}
 * (plan 016 phase 2).
 *
 * <p>{@code aperture-simple-mcp} cannot depend on generated-project code at compile time —
 * generation runs during the build's Maven plugin execution, before the generated project's own
 * JVM exists (the same constraint documented on {@link McpElideAdapter} and
 * {@code McpToolContribution}). So this loads {@code McpToolRegistry.TOOLS} via reflection,
 * exactly like {@link ApertureMcpAutoConfiguration}'s {@code ToolCallbackProvider} discovers
 * generated tool classes reflectively instead of by static type.
 *
 * <p>A missing registry class (no entity ever emitted an MCP tool — e.g. an app that only uses
 * {@code McpToolContribution} tools) is treated as an empty map: every tool name is then simply
 * absent from the registry, and {@link McpToolListFilter} always retains a tool it has no access
 * rule for, so nothing is hidden.
 */
final class McpToolRegistryBridge {

    private static final String DEFAULT_REGISTRY_CLASS = "com.itsjool.aperture.generated.mcp.McpToolRegistry";
    private static final Logger log = LoggerFactory.getLogger(McpToolRegistryBridge.class);

    private McpToolRegistryBridge() {}

    static Map<String, McpToolAccess> load() {
        return load(DEFAULT_REGISTRY_CLASS);
    }

    /** Package-visible overload so tests can point at a class name that is guaranteed absent. */
    static Map<String, McpToolAccess> load(String registryClassName) {
        try {
            Class<?> registryClass = Class.forName(registryClassName);
            Field toolsField = registryClass.getField("TOOLS");
            Map<?, ?> raw = (Map<?, ?>) toolsField.get(null);
            Map<String, McpToolAccess> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                result.put((String) entry.getKey(), toAccess(entry.getValue()));
            }
            return result;
        } catch (ClassNotFoundException e) {
            // Normal: no entity ever emitted an MCP tool. Nothing to filter against.
            return Map.of();
        } catch (Exception e) {
            log.warn("Could not read the generated MCP tool registry ({}); tools/list filtering "
                    + "will pass every tool through unfiltered for this request", registryClassName, e);
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static McpToolAccess toAccess(Object generatedRecord) throws Exception {
        Class<?> cls = generatedRecord.getClass();
        String entity = (String) accessor(cls, "entity").invoke(generatedRecord);
        String operation = (String) accessor(cls, "operation").invoke(generatedRecord);
        boolean publicOperation = (boolean) accessor(cls, "publicOperation").invoke(generatedRecord);
        Set<String> roles = (Set<String>) accessor(cls, "roles").invoke(generatedRecord);
        List<String> principalOnlyPolicyExpressions =
            (List<String>) accessor(cls, "principalOnlyPolicyExpressions").invoke(generatedRecord);
        boolean tenantAdminBypass = (boolean) accessor(cls, "tenantAdminBypass").invoke(generatedRecord);
        return new McpToolAccess(entity, operation, publicOperation, roles, principalOnlyPolicyExpressions, tenantAdminBypass);
    }

    private static Method accessor(Class<?> cls, String name) throws NoSuchMethodException {
        return cls.getMethod(name);
    }
}
