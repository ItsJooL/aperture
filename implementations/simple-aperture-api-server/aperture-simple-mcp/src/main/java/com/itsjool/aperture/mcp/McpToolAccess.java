package com.itsjool.aperture.mcp;

import java.util.List;
import java.util.Set;

/**
 * Runtime-side mirror of a single entry from the generated
 * {@code com.itsjool.aperture.generated.mcp.McpToolRegistry} (plan 016 phase 2), read reflectively
 * by {@link McpToolRegistryBridge} since {@code aperture-simple-mcp} has no compile-time dependency
 * on generated project code.
 *
 * <p>Presentation-only, like the registry it mirrors: consulted solely by {@link McpToolListFilter}
 * to decide what {@code tools/list} shows. {@code tools/call} is authorized for real by Elide,
 * regardless of this data.
 */
record McpToolAccess(
    String entity,
    String operation,
    boolean publicOperation,
    Set<String> roles,
    List<String> principalOnlyPolicyExpressions,
    boolean tenantAdminBypass
) {}
