package com.itsjool.aperture.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolRegistryBridgeTest {

    @Test
    void loadsTheRealGeneratedShapeReflectively() {
        Map<String, McpToolAccess> tools = McpToolRegistryBridge.load("com.itsjool.aperture.generated.mcp.McpToolRegistry");

        assertThat(tools).containsKey("list_projects");
        McpToolAccess listProjects = tools.get("list_projects");
        assertThat(listProjects.entity()).isEqualTo("Project");
        assertThat(listProjects.operation()).isEqualTo("read");
        assertThat(listProjects.publicOperation()).isFalse();
        assertThat(listProjects.roles()).containsExactlyInAnyOrder("Admin", "Assistant", "ReadOnly");
        assertThat(listProjects.tenantAdminBypass()).isFalse();

        McpToolAccess listCustomers = tools.get("list_customers");
        assertThat(listCustomers.tenantAdminBypass()).isTrue();

        McpToolAccess listInvoices = tools.get("list_invoices");
        assertThat(listInvoices.principalOnlyPolicyExpressions())
            .containsExactly("#user.securityAttributes['department'] == 'finance'");

        McpToolAccess publicReports = tools.get("list_public_reports");
        assertThat(publicReports.publicOperation()).isTrue();
    }

    @Test
    void missingRegistryClass_returnsEmptyMapRatherThanThrowing() {
        Map<String, McpToolAccess> tools = McpToolRegistryBridge.load("com.itsjool.aperture.generated.mcp.NoSuchRegistry");

        assertThat(tools).isEmpty();
    }
}
