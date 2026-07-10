package com.itsjool.aperture.generated.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test fixture mirroring the exact shape {@code McpToolRegistryGenerator} (in {@code aperture-mcp})
 * emits into a real generated project. {@link com.itsjool.aperture.mcp.McpToolRegistryBridge} and
 * {@link com.itsjool.aperture.mcp.McpToolListFilter} have no compile-time dependency on generated
 * code, so this fixture is what proves their reflective bridge actually works against the real
 * shape rather than an idealized one.
 */
public final class McpToolRegistry {

    public record McpToolAccess(
        String entity,
        String operation,
        boolean publicOperation,
        Set<String> roles,
        List<String> principalOnlyPolicyExpressions,
        boolean tenantAdminBypass
    ) {}

    public static final Map<String, McpToolAccess> TOOLS = Map.ofEntries(
        Map.entry("list_projects", new McpToolAccess("Project", "read", false, Set.of("Admin", "Assistant", "ReadOnly"), List.of(), false)),
        Map.entry("get_project", new McpToolAccess("Project", "read", false, Set.of("Admin", "Assistant", "ReadOnly"), List.of(), false)),
        Map.entry("create_project", new McpToolAccess("Project", "create", false, Set.of("Admin", "Assistant"), List.of(), false)),
        Map.entry("update_project", new McpToolAccess("Project", "update", false, Set.of("Admin", "Assistant"), List.of(), false)),
        Map.entry("delete_project", new McpToolAccess("Project", "delete", false, Set.of("Admin"), List.of(), false)),
        Map.entry("list_tasks", new McpToolAccess("Task", "read", false, Set.of("Admin", "Assistant", "ReadOnly"), List.of(), false)),
        Map.entry("get_task", new McpToolAccess("Task", "read", false, Set.of("Admin", "Assistant", "ReadOnly"), List.of(), false)),
        Map.entry("create_task", new McpToolAccess("Task", "create", false, Set.of("Admin", "Assistant"), List.of(), false)),
        Map.entry("update_task", new McpToolAccess("Task", "update", false, Set.of("Admin", "Assistant"), List.of(), false)),
        Map.entry("delete_task", new McpToolAccess("Task", "delete", false, Set.of("Admin"), List.of(), false)),
        // Every role can reach this operation, but a finance-only #user-only policy further
        // restricts it, for filter tests that need a principal-only-policy case.
        Map.entry("list_invoices", new McpToolAccess("Invoice", "read", false, Set.of("Admin", "Assistant", "ReadOnly"),
            List.of("#user.securityAttributes['department'] == 'finance'"), false)),
        // A public tool, listed for anyone regardless of role.
        Map.entry("list_public_reports", new McpToolAccess("Report", "read", true, Set.of(), List.of(), false)),
        // A tenant-scoped entity whose declared roles (Accountant/Viewer) never include the
        // reserved TenantAdmin name (DomainModelValidator forbids that), yet CodeGenerator still
        // grants TenantAdmin unconditional access via TenantAdminCheck. This pins the regression:
        // an entity with role-based permissions in tenant-scoped mode, listed by a caller whose
        // roles() don't intersect but whose tenantAdmin() flag does — the tool must stay listed.
        Map.entry("list_customers", new McpToolAccess("Customer", "read", false, Set.of("Accountant", "Viewer"), List.of(), true))
    );

    private McpToolRegistry() {}
}
