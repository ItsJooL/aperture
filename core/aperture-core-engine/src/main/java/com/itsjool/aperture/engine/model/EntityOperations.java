package com.itsjool.aperture.engine.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single predicate for "does any declared role, policy, or public operation grant this
 * manifest operation on this entity" — and the shared vocabulary of operation/tool names built
 * on top of it.
 *
 * <p>This is consulted by both {@code CodeGenerator} (to decide when a REST permission
 * expression collapses to {@code Prefab.Role.None}) and by the MCP tool generator/validator (to
 * decide which MCP tools an entity's own access rules permit). Both call sites must use this one
 * method so the REST permission surface and the MCP tool surface can never drift apart.
 *
 * <p><b>Superadmin is deliberately not considered here.</b> {@code SuperAdminCheck} bypasses
 * every access rule, so if it counted toward reachability every entity would derive full CRUD
 * and the derivation would be worthless. Reachability is computed solely from what the manifest
 * author actually declared: {@code permissions}, {@code policies}, and {@code publicOperations}.
 */
public final class EntityOperations {

    /** The four manifest-level CRUD operations. */
    public static final List<String> MANIFEST_OPERATIONS = List.of("read", "create", "update", "delete");

    /** The five MCP tool names — {@code read} splits into {@code list} and {@code get}. */
    public static final List<String> MCP_TOOLS = List.of("list", "get", "create", "update", "delete");

    private static final Map<String, List<String>> MANIFEST_OPERATION_TO_MCP_TOOLS = Map.of(
        "read", List.of("list", "get"),
        "create", List.of("create"),
        "update", List.of("update"),
        "delete", List.of("delete")
    );

    private EntityOperations() {}

    /**
     * The subset of {@link #MANIFEST_OPERATIONS} that any declared role, policy, or public
     * operation grants on {@code entity}. An entity with no {@code permissions}, {@code
     * policies}, or {@code publicOperations} at all (legal — the schema requires only {@code
     * fields}) returns the empty set, matching the fact that {@code CodeGenerator} would emit
     * {@code Prefab.Role.None} for every operation on that entity.
     */
    public static Set<String> reachableOperations(EntityDef entity) {
        Set<String> publicOps = normalizedOps(entity.publicOperations());
        Set<String> roleGrantedOps = grantedOps(entity.permissions());
        Set<String> policyGrantedOps = grantedOps(entity.policies());

        Set<String> reachable = new LinkedHashSet<>();
        for (String op : MANIFEST_OPERATIONS) {
            if (publicOps.contains(op) || roleGrantedOps.contains(op) || policyGrantedOps.contains(op)) {
                reachable.add(op);
            }
        }
        return reachable;
    }

    /**
     * The MCP tools {@link #reachableOperations(EntityDef)} permits, i.e. the {@code derived(entity)}
     * set from plan 016: what the entity's own access rules already permit, before any framework
     * ceiling or entity-level narrowing is applied.
     */
    public static Set<String> derivedMcpTools(EntityDef entity) {
        Set<String> tools = new LinkedHashSet<>();
        for (String op : reachableOperations(entity)) {
            tools.addAll(MANIFEST_OPERATION_TO_MCP_TOOLS.get(op));
        }
        return tools;
    }

    /**
     * Lower-cases a manifest-supplied operation or tool list into a set for comparison.
     *
     * <p>Manifest validation accepts any casing (see {@code DomainModelValidator.validateMcpTools}),
     * so {@code tools: [List]} and {@code tools: [list]} are both legal. Every site that compares a
     * manifest-supplied name against {@link #MCP_TOOLS} or {@link #MANIFEST_OPERATIONS} must
     * normalize through here — comparing raw strings at one site and normalized strings at another
     * is precisely the kind of split decision this class exists to prevent.
     */
    public static Set<String> normalizedOps(List<String> ops) {
        Set<String> result = new LinkedHashSet<>();
        if (ops != null) {
            for (String op : ops) {
                if (op != null) result.add(op.toLowerCase());
            }
        }
        return result;
    }

    private static Set<String> grantedOps(Map<String, List<String>> grants) {
        Set<String> result = new LinkedHashSet<>();
        if (grants != null) {
            for (List<String> ops : grants.values()) {
                if (ops != null) {
                    for (String op : ops) {
                        result.add(op.toLowerCase());
                    }
                }
            }
        }
        return result;
    }
}
