package com.itsjool.aperture.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.runtime.filter.ApertureRequestAttributes;
import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.aperture.spi.PrincipalKind;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link McpToolListFilter} against the real generated shape mirrored by the
 * {@code com.itsjool.aperture.generated.mcp.McpToolRegistry} test fixture (see
 * {@link McpToolRegistryBridgeTest}).
 */
class McpToolListFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Every tool name present in the test fixture registry, in a plain tools/list-shaped body.
    private static final List<String> ALL_TOOL_NAMES = List.of(
        "list_projects", "get_project", "create_project", "update_project", "delete_project",
        "list_tasks", "get_task", "create_task", "update_task", "delete_task",
        "list_invoices", "list_public_reports", "list_customers");

    private static String toolsListBody(List<String> names) {
        try {
            var tools = new ArrayList<Map<String, Object>>();
            for (String name : names) {
                tools.add(Map.of("name", name, "description", "d", "inputSchema", Map.of()));
            }
            return MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "result", Map.of("tools", tools)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static AperturePrincipal principal(Set<String> roles, boolean superAdmin) {
        return principal(roles, superAdmin, false);
    }

    private static AperturePrincipal principal(Set<String> roles, boolean superAdmin, boolean tenantAdmin) {
        return new AperturePrincipal("user-1", "tenant-1", roles, PrincipalKind.USER,
            Map.of(), Map.of(), Set.of(), superAdmin, tenantAdmin);
    }

    private static AperturePrincipal principal(Set<String> roles, Map<String, Object> securityAttributes) {
        return new AperturePrincipal("user-1", "tenant-1", roles, PrincipalKind.USER,
            Map.of(), securityAttributes, Set.of(), false, false);
    }

    /** Chain that simply writes a canned tools/list JSON body, as if from the real MCP handler. */
    private static FilterChain writesBody(String body, String contentType) {
        return (req, res) -> {
            res.setContentType(contentType);
            res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        };
    }

    private static List<String> toolNames(MockHttpServletResponse response) throws Exception {
        JsonNode root = MAPPER.readTree(response.getContentAsByteArray());
        List<String> names = new ArrayList<>();
        root.path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    /** Defaults the stashed JSON-RPC method to {@code tools/list}, the case most tests exercise. */
    private MockHttpServletRequest mcpPostRequest(AperturePrincipal principal) {
        return mcpPostRequest(principal, "tools/list");
    }

    /**
     * @param jsonRpcMethod the value {@code McpSanitizationFilter} would have stashed under
     *                      {@link ApertureRequestAttributes#MCP_JSONRPC_METHOD}; {@code null}
     *                      simulates a caller that skips that filter entirely (attribute absent).
     */
    private MockHttpServletRequest mcpPostRequest(AperturePrincipal principal, String jsonRpcMethod) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setAttribute("aperturePrincipal", principal);
        if (jsonRpcMethod != null) {
            request.setAttribute(ApertureRequestAttributes.MCP_JSONRPC_METHOD, jsonRpcMethod);
        }
        return request;
    }

    @Test
    void adminSeesAllTenEntityTools_butNotThePolicyGatedInvoiceTool() throws Exception {
        // Admin has no "finance" security attribute, so list_invoices' principal-only policy
        // evaluates false and it stays hidden — the tool surface is not simply "has any role".
        AperturePrincipal admin = principal(Set.of("Admin"), false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new McpToolListFilter().doFilter(mcpPostRequest(admin), response, writesBody(toolsListBody(ALL_TOOL_NAMES), "application/json"));

        List<String> names = toolNames(response);
        assertThat(names).containsExactlyInAnyOrder(
            "list_projects", "get_project", "create_project", "update_project", "delete_project",
            "list_tasks", "get_task", "create_task", "update_task", "delete_task",
            "list_public_reports");
        assertThat(names).doesNotContain("list_invoices");
        // Admin's role never appears in Customer's permissions (Accountant/Viewer only), and this
        // principal isn't a TenantAdmin, so list_customers must stay hidden too.
        assertThat(names).doesNotContain("list_customers");
    }

    @Test
    void tenantAdminSeesTenantScopedToolsEvenWithoutAMatchingRole() throws Exception {
        // This is the exact regression the aperture-demo Customer entity hit: DomainModelValidator
        // forbids "TenantAdmin" from ever appearing in an entity's declared permissions, and
        // SimplePrincipalMapper never puts it in AperturePrincipal.roles() either — it only ever
        // surfaces as the tenantAdmin() flag. CodeGenerator still grants TenantAdmin every
        // operation on a tenant-scoped entity via TenantAdminCheck, so the filter must too, or a
        // TenantAdmin caller whose own declared roles don't happen to overlap sees nothing.
        AperturePrincipal tenantAdmin = principal(Set.of(), false, true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new McpToolListFilter().doFilter(mcpPostRequest(tenantAdmin), response,
            writesBody(toolsListBody(List.of("list_customers", "list_invoices")), "application/json"));

        List<String> names = toolNames(response);
        assertThat(names).contains("list_customers");
        // list_invoices is NOT tenantAdminBypass in the fixture, so the bypass must not leak
        // beyond the entities that actually grant it — this principal has no matching role
        // either, and no "finance" security attribute, so it must still be excluded.
        assertThat(names).doesNotContain("list_invoices");
    }

    @Test
    void tenantAdminFlagAloneDoesNotBypassEntitiesWithoutTheBypassFlag() throws Exception {
        // Guards against a shortcut implementation that treats tenantAdmin() like a second
        // superAdmin() (bypassing everything): the bypass must be conditioned on the tool's own
        // McpToolAccess.tenantAdminBypass(), not granted unconditionally to every tool.
        AperturePrincipal tenantAdmin = principal(Set.of(), false, true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new McpToolListFilter().doFilter(mcpPostRequest(tenantAdmin), response,
            writesBody(toolsListBody(List.of("delete_project")), "application/json"));

        assertThat(toolNames(response)).doesNotContain("delete_project");
    }

    @Test
    void readOnlySeesOnlyReadToolsAndThePublicTool() throws Exception {
        AperturePrincipal readOnly = principal(Set.of("ReadOnly"), false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new McpToolListFilter().doFilter(mcpPostRequest(readOnly), response, writesBody(toolsListBody(ALL_TOOL_NAMES), "application/json"));

        List<String> names = toolNames(response);
        assertThat(names).containsExactlyInAnyOrder(
            "list_projects", "get_project", "list_tasks", "get_task", "list_public_reports");
    }

    @Test
    void principalOnlyPolicyThatEvaluatesTrue_admitsTheTool() throws Exception {
        AperturePrincipal financeReadOnly = principal(Set.of("ReadOnly"), Map.of("department", "finance"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new McpToolListFilter().doFilter(mcpPostRequest(financeReadOnly), response, writesBody(toolsListBody(ALL_TOOL_NAMES), "application/json"));

        assertThat(toolNames(response)).contains("list_invoices");
    }

    @Test
    void superAdminBypassesFilteringEntirely() throws Exception {
        AperturePrincipal superAdmin = principal(Set.of(), true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new McpToolListFilter().doFilter(mcpPostRequest(superAdmin), response, writesBody(toolsListBody(ALL_TOOL_NAMES), "application/json"));

        assertThat(toolNames(response)).containsExactlyInAnyOrderElementsOf(ALL_TOOL_NAMES);
    }

    @Test
    void unregisteredToolNameIsAlwaysRetained() throws Exception {
        // e.g. an McpToolContribution tool: not registry-governed, always listed regardless of role.
        AperturePrincipal readOnly = principal(Set.of("ReadOnly"), false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new McpToolListFilter().doFilter(mcpPostRequest(readOnly), response,
            writesBody(toolsListBody(List.of("some_contributed_tool")), "application/json"));

        assertThat(toolNames(response)).containsExactly("some_contributed_tool");
    }

    @Test
    void sseFramedResponse_isFilteredAndReFramed() throws Exception {
        AperturePrincipal readOnly = principal(Set.of("ReadOnly"), false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String sseBody = "data: " + toolsListBody(List.of("list_projects", "delete_project")) + "\n\n";

        new McpToolListFilter().doFilter(mcpPostRequest(readOnly), response, writesBody(sseBody, "text/event-stream"));

        String rewritten = response.getContentAsString();
        assertThat(rewritten).startsWith("data: ");
        assertThat(rewritten).contains("list_projects").doesNotContain("delete_project");
    }

    @Test
    void unparseableBody_isPassedThroughUnmodifiedWithoutThrowing() throws Exception {
        AperturePrincipal readOnly = principal(Set.of("ReadOnly"), false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String garbage = "not json at all";

        new McpToolListFilter().doFilter(mcpPostRequest(readOnly), response, writesBody(garbage, "application/json"));

        assertThat(response.getContentAsString()).isEqualTo(garbage);
    }

    @Test
    void nonMcpRequest_isNotTouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String body = toolsListBody(ALL_TOOL_NAMES);

        new McpToolListFilter().doFilter(request, response, writesBody(body, "application/json"));

        assertThat(response.getContentAsString()).isEqualTo(body);
    }

    @Test
    void toolsCallResponse_isPassedThroughUnwrappedWithoutBuffering() throws Exception {
        // The core fix in plan 029: a tools/call response must never be buffered/rewrapped, only
        // decided by the request's stashed JSON-RPC method (not response-body shape-detection).
        AperturePrincipal readOnly = principal(Set.of("ReadOnly"), false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String callResultBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[]}}";
        List<ServletResponse> seenByChain = new ArrayList<>();
        FilterChain chain = (req, res) -> {
            seenByChain.add(res);
            res.getOutputStream().write(callResultBody.getBytes(StandardCharsets.UTF_8));
        };

        new McpToolListFilter().doFilter(mcpPostRequest(readOnly, "tools/call"), response, chain);

        assertThat(seenByChain).hasSize(1);
        assertThat(seenByChain.get(0))
            .as("the response handed to the rest of the chain must be the original, not a BufferingResponseWrapper")
            .isSameAs(response);
        assertThat(response.containsHeader("Content-Length"))
            .as("this filter must not set Content-Length for a response it never buffered")
            .isFalse();
        assertThat(response.getContentAsString()).isEqualTo(callResultBody);
    }

    @Test
    void missingJsonRpcMethodAttribute_isPassedThroughUnwrappedAsIfNotToolsList() throws Exception {
        // Simulates a future caller whose filter chain configuration skips McpSanitizationFilter:
        // with no attribute stashed, this filter must fail safe to "not tools/list", not throw and
        // not fall back to buffering.
        AperturePrincipal readOnly = principal(Set.of("ReadOnly"), false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String callResultBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[]}}";
        List<ServletResponse> seenByChain = new ArrayList<>();
        FilterChain chain = (req, res) -> {
            seenByChain.add(res);
            res.getOutputStream().write(callResultBody.getBytes(StandardCharsets.UTF_8));
        };

        new McpToolListFilter().doFilter(mcpPostRequest(readOnly, null), response, chain);

        assertThat(seenByChain.get(0)).isSameAs(response);
        assertThat(response.containsHeader("Content-Length")).isFalse();
        assertThat(response.getContentAsString()).isEqualTo(callResultBody);
    }

    @Test
    void largeToolsCallPayload_streamsThroughByteForByteWithoutTruncation() throws Exception {
        // Proves the hot-path claim in plan 029's "Why this matters": a list_tasks-sized tools/call
        // response is not buffered into heap, and removing the buffer wrap does not truncate or
        // otherwise mutate a large body.
        AperturePrincipal readOnly = principal(Set.of("ReadOnly"), false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        var tasks = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 5_000; i++) {
            tasks.add(Map.of(
                "id", String.valueOf(i),
                "title", "Task number " + i,
                "description", "x".repeat(200)));
        }
        String largeBody = MAPPER.writeValueAsString(
            Map.of("jsonrpc", "2.0", "id", 1, "result", Map.of("content", tasks)));
        assertThat(largeBody.length()).isGreaterThan(1_000_000);

        new McpToolListFilter().doFilter(
            mcpPostRequest(readOnly, "tools/call"), response, writesBody(largeBody, "application/json"));

        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(largeBody);
    }
}
