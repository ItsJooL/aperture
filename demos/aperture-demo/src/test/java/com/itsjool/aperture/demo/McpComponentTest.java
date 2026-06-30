package com.itsjool.aperture.demo;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class McpComponentTest extends DemoApplicationTestSupport {

    private static final String MCP_ENDPOINT = "/mcp";

    // MCP SDK 0.17.0 WebMvcStatelessServerTransport requires Accept to include BOTH
    // application/json AND text/event-stream, otherwise it returns 400 immediately.
    private static final String MCP_ACCEPT = "application/json, text/event-stream";

    private static final String LIST_TOOLS_REQUEST = """
            {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}
            """;

    private String callToolRequest(String toolName, String argsJson) {
        return """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(toolName, argsJson);
    }

    @Test
    public void mcpListTools_includesCustomerTools() throws Exception {
        String token = getAcmeAdminToken();

        String responseBody = mockMvc.perform(post(MCP_ENDPOINT)
                        .header("Authorization", token)
                        .header("Accept", MCP_ACCEPT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LIST_TOOLS_REQUEST))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode tools = root.path("result").path("tools");
        assertThat(tools.isArray()).isTrue();

        boolean found = false;
        for (JsonNode tool : tools) {
            if ("list_customers".equals(tool.path("name").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Expected list_customers tool in tools/list response: " + responseBody).isTrue();
    }

    @Test
    public void mcpListCustomers_asTenantAdmin_returnsData() throws Exception {
        String token = getAcmeAdminToken();

        String responseBody = mockMvc.perform(post(MCP_ENDPOINT)
                        .header("Authorization", token)
                        .header("Accept", MCP_ACCEPT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callToolRequest("list_customers", "{\"filter\":null,\"page\":null,\"pageSize\":null}")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = MAPPER.readTree(responseBody);
        assertThat(root.has("result")).as("Expected result in MCP tool call response: " + responseBody).isTrue();
        assertThat(root.has("error")).as("Unexpected error in response: " + responseBody).isFalse();

        JsonNode content = root.path("result").path("content");
        if (content.isArray() && content.size() > 0) {
            String text = content.get(0).path("text").asText("");
            assertThat(text).isNotBlank();
        }
    }

    @Test
    public void mcpCreateCustomer_asTenantAdmin_persistsEntity() throws Exception {
        String token = getAcmeAdminToken();

        String responseBody = mockMvc.perform(post(MCP_ENDPOINT)
                        .header("Authorization", token)
                        .header("Accept", MCP_ACCEPT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callToolRequest("create_customer",
                                "{\"name\":\"MCP Test Corp\",\"email\":\"mcp@test.com\",\"phone_number\":null}")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = MAPPER.readTree(responseBody);
        assertThat(root.has("error")).as("Unexpected MCP error: " + responseBody).isFalse();
        assertThat(root.has("result")).as("Expected result in response: " + responseBody).isTrue();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aperture_customers WHERE name = ?",
                Integer.class, "MCP Test Corp");
        assertThat(count).isEqualTo(1);
    }

    @Test
    public void mcpCreateCustomer_asViewer_isRejected() throws Exception {
        String token = getAcmeViewerToken();

        String responseBody = mockMvc.perform(post(MCP_ENDPOINT)
                        .header("Authorization", token)
                        .header("Accept", MCP_ACCEPT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callToolRequest("create_customer",
                                "{\"name\":\"Viewer Corp\",\"email\":\"viewer@test.com\",\"phone_number\":null}")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = MAPPER.readTree(responseBody);
        assertThat(root.has("result")).isTrue();
        JsonNode content = root.path("result").path("content");
        if (content.isArray() && content.size() > 0) {
            String text = content.get(0).path("text").asText("");
            assertThat(text).containsAnyOf("error", "Error", "ForbiddenAccessException", "403",
                    "errors", "FORBIDDEN");
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aperture_customers WHERE name = ?",
                Integer.class, "Viewer Corp");
        assertThat(count).isZero();
    }
}
