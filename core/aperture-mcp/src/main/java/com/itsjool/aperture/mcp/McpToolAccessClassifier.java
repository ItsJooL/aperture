package com.itsjool.aperture.mcp;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.AbacPolicyDef;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.EntityOperations;
import com.itsjool.aperture.engine.validator.SpelVariableExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Statically classifies each MCP tool an entity effectively exposes with the access rule that
 * governs it, for the generated {@code McpToolRegistry} (plan 016 phase 2): which roles grant the
 * underlying manifest operation, whether the operation is public, and which of the entity's ABAC
 * policies are decidable with no row in hand.
 *
 * <p><b>The crux: a row-scoped policy must never end up in
 * {@link ToolAccess#principalOnlyPolicyExpressions()}.</b> {@code AbacPolicyEvaluator.evaluate}
 * (in {@code aperture-core-runtime}) fails closed on any evaluation exception. An expression that
 * references {@code #record} or {@code #input} cannot be evaluated at {@code tools/list} time —
 * there is no row in hand yet — so naively including it here and later evaluating it with a
 * {@code null} record would silently and incorrectly hide the tool from every non-superadmin
 * caller. Such policies are simply omitted from the principal-only list: their tool stays listed,
 * unfiltered by that policy. Elide still enforces it for real on the actual {@code tools/call}; this
 * classification only ever affects what {@code tools/list} shows, never what a call is allowed to do.
 *
 * <p><b>TenantAdmin is a second bypass, distinct from {@code roles()}.</b>
 * {@code DomainModelValidator} forbids the reserved names {@code TenantAdmin}/{@code SuperAdmin}
 * from ever appearing in an entity's {@code permissions}, and {@code SimplePrincipalMapper} never
 * puts them in {@code AperturePrincipal.roles()} either — they surface only as the
 * {@code AperturePrincipal.tenantAdmin()}/{@code superAdmin()} booleans. But {@code CodeGenerator}
 * still grants a {@code TenantAdmin} principal every operation on a tenant-scoped (or
 * {@code scopedBy}) entity via {@code TenantAdminCheck}, exactly like {@code SuperAdminCheck}
 * bypasses everything. A classifier that only ever populated {@link ToolAccess#roles()} from
 * {@code permissions} would therefore hide every tool of every tenant-scoped entity from a
 * TenantAdmin caller whose own roles don't happen to also appear in that entity's {@code
 * permissions} — {@link ToolAccess#tenantAdminBypass()} exists so the runtime filter can grant the
 * same bypass {@code tools/list} that {@code tools/call} already grants for real.
 */
public final class McpToolAccessClassifier {

    /**
     * @param entity                          the entity name the tool belongs to
     * @param operation                       the manifest operation ({@code read}, {@code create},
     *                                        {@code update}, or {@code delete}) the tool maps to
     * @param publicOperation                 whether the operation is granted to every caller via
     *                                        the entity's {@code publicOperations}
     * @param roles                           role names whose {@code permissions} grant the operation
     * @param principalOnlyPolicyExpressions  SpEL expressions of policies granting the operation
     *                                        that reference only {@code #user} (never {@code #record}
     *                                        or {@code #input}) — decidable with no row in hand
     * @param tenantAdminBypass               mirrors exactly the condition under which
     *                                        {@code CodeGenerator} adds {@code TenantAdminCheck} as
     *                                        a bypass: {@code (tenancyMode == POOL && entity.tenantScoped())
     *                                        || entity.scopedBy() != null}. See the class javadoc.
     */
    public record ToolAccess(
        String entity,
        String operation,
        boolean publicOperation,
        Set<String> roles,
        List<String> principalOnlyPolicyExpressions,
        boolean tenantAdminBypass
    ) {}

    private McpToolAccessClassifier() {}

    /**
     * @param entity          the entity whose access rules are being classified
     * @param effectiveTools  the tool names ({@code list}/{@code get}/{@code create}/{@code update}/
     *                        {@code delete}) already computed by {@link McpToolGenerator#effectiveTools}
     *                        for {@code entity} — only these get a registry entry, since only these
     *                        actually become {@code @Tool} methods
     * @param abacPolicies    the manifest's full, global named-policy list, used to resolve each
     *                        policy name {@code entity.policies()} references to its SpEL expression
     * @param tenancyMode     the framework's tenancy mode, needed to reproduce
     *                        {@code CodeGenerator}'s exact {@code TenantAdminCheck} condition (see
     *                        the class javadoc)
     * @return the generated tool name (e.g. {@code "list_projects"}) mapped to its {@link ToolAccess}
     */
    public static Map<String, ToolAccess> classify(EntityDef entity, List<String> effectiveTools,
                                                     List<AbacPolicyDef> abacPolicies, TenancyMode tenancyMode) {
        Map<String, String> expressionsByPolicyName = new HashMap<>();
        if (abacPolicies != null) {
            for (AbacPolicyDef def : abacPolicies) {
                expressionsByPolicyName.put(def.name(), def.expression());
            }
        }
        Set<String> publicOps = EntityOperations.normalizedOps(entity.publicOperations());
        // Mirrors CodeGenerator's filterChecks-non-empty condition exactly: that's precisely when
        // it adds TenantAdminCheck as a bypass alongside role/policy checks.
        boolean tenantAdminBypass = (tenancyMode == TenancyMode.POOL && entity.tenantScoped())
            || entity.scopedBy() != null;

        Map<String, ToolAccess> result = new LinkedHashMap<>();
        for (String tool : effectiveTools) {
            String op = manifestOperation(tool);
            boolean isPublic = publicOps.contains(op);
            Set<String> roles = rolesGranting(entity.permissions(), op);
            List<String> principalOnly = principalOnlyPolicyExpressions(entity.policies(), op, expressionsByPolicyName);
            String toolName = McpToolGenerator.toolName(tool, entity);
            result.put(toolName, new ToolAccess(entity.name(), op, isPublic, roles, principalOnly, tenantAdminBypass));
        }
        return result;
    }

    private static String manifestOperation(String tool) {
        return switch (tool) {
            case "list", "get" -> "read";
            case "create"      -> "create";
            case "update"      -> "update";
            case "delete"      -> "delete";
            default -> throw new IllegalArgumentException("Unknown MCP tool: " + tool);
        };
    }

    private static Set<String> rolesGranting(Map<String, List<String>> permissions, String op) {
        Set<String> roles = new LinkedHashSet<>();
        if (permissions != null) {
            for (Map.Entry<String, List<String>> entry : permissions.entrySet()) {
                if (grants(entry.getValue(), op)) roles.add(entry.getKey());
            }
        }
        return roles;
    }

    private static List<String> principalOnlyPolicyExpressions(Map<String, List<String>> policies, String op,
                                                                 Map<String, String> expressionsByPolicyName) {
        List<String> result = new ArrayList<>();
        if (policies != null) {
            for (Map.Entry<String, List<String>> entry : policies.entrySet()) {
                if (!grants(entry.getValue(), op)) continue;
                String expression = expressionsByPolicyName.get(entry.getKey());
                if (expression != null && isPrincipalOnly(expression)) {
                    result.add(expression);
                }
            }
        }
        return result;
    }

    private static boolean grants(List<String> ops, String op) {
        if (ops == null) return false;
        for (String candidate : ops) {
            if (candidate != null && candidate.equalsIgnoreCase(op)) return true;
        }
        return false;
    }

    /**
     * True iff {@code expression} references only {@code #user} (or no variables at all) — i.e. it
     * is decidable with no row in hand. False for anything referencing {@code #record} or
     * {@code #input}; see the class javadoc for why that must never be included upstream.
     */
    private static boolean isPrincipalOnly(String expression) {
        Set<String> variables = SpelVariableExtractor.getVariables(expression);
        return !variables.contains("record") && !variables.contains("input");
    }
}
