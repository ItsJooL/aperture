package com.itsjool.aperture.mcpdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the MCP demo: superadmin bootstrap, JSON:API CRUD on the
 * no-tenancy Project/Task entities, and the generated MCP tool surface exposed at
 * {@code /mcp}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class McpDemoComponentTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("aperture")
            .withUsername("aperture")
            .withPassword("password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType VNDAPI = MediaType.valueOf("application/vnd.api+json");

    // MCP SDK 0.17.0's WebMvcStatelessServerTransport requires Accept to include BOTH
    // application/json AND text/event-stream, otherwise it returns 400 immediately.
    private static final String MCP_ACCEPT = "application/json, text/event-stream";

    @Autowired
    private MockMvc mockMvc;

    private ResultActions elide(MockHttpServletRequestBuilder builder) throws Exception {
        var result = mockMvc.perform(builder).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result));
        }
        return mockMvc.perform(builder);
    }

    private String loginAsSuperadmin() throws Exception {
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"superadmin@framework.local\",\"password\":\"changeme-local-only\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("accessToken").isMissingNode()).isFalse();
        return "Bearer " + root.path("accessToken").asText();
    }

    private JsonNode callMcpTool(String token, int id, String toolName, String argumentsJson) throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(id, toolName, argumentsJson);
        var result = elide(post("/mcp")
                        .header("Authorization", token)
                        .header("Accept", MCP_ACCEPT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void bootstrapCreatesSuperadmin_andLoginSucceeds() throws Exception {
        String token = loginAsSuperadmin();
        assertThat(token).startsWith("Bearer ");
    }

    @Test
    void jsonApi_createAndListProject() throws Exception {
        String token = loginAsSuperadmin();

        var createResult = elide(post("/api/v1/projects")
                        .header("Authorization", token)
                        .contentType(VNDAPI)
                        .accept(VNDAPI)
                        .content("{\"data\":{\"type\":\"projects\",\"attributes\":{\"name\":\"Component Test Project\"}}}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode created = MAPPER.readTree(createResult.getResponse().getContentAsString());
        assertThat(created.path("data").path("id").isMissingNode()).isFalse();

        elide(get("/api/v1/projects")
                        .header("Authorization", token)
                        .accept(VNDAPI))
                .andExpect(status().isOk());
    }

    @Test
    void mcpListTools_exposesProjectFullCrudAndTaskReadOnly() throws Exception {
        String token = loginAsSuperadmin();

        String listToolsBody = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """;
        var result = elide(post("/mcp")
                        .header("Authorization", token)
                        .header("Accept", MCP_ACCEPT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(listToolsBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());
        JsonNode tools = root.path("result").path("tools");
        assertThat(tools.isArray()).isTrue();

        List<String> toolNames = new ArrayList<>();
        tools.forEach(tool -> toolNames.add(tool.path("name").asText()));

        // Project has no entity-level mcp override, so it inherits the framework default
        // tool set (list, get, create, update, delete) from manifests/framework/config.yaml.
        assertThat(toolNames).contains(
                "list_projects", "get_project", "create_project", "update_project", "delete_project");

        // Task restricts its mcp tools to [list, get] only.
        assertThat(toolNames).contains("list_tasks", "get_task");
        assertThat(toolNames).doesNotContain("create_task", "update_task", "delete_task");
    }

    @Test
    void mcpCreateProject_persistsEntityVisibleViaJsonApi() throws Exception {
        String token = loginAsSuperadmin();

        JsonNode callResult = callMcpTool(token, 2, "create_project",
                "{\"name\":\"Created From MCP Tool\",\"description\":\"via generated tool\"}");
        assertThat(callResult.has("error")).as("Unexpected MCP error: " + callResult).isFalse();
        assertThat(callResult.has("result")).isTrue();

        var listResult = elide(get("/api/v1/projects")
                        .header("Authorization", token)
                        .accept(VNDAPI))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listRoot = MAPPER.readTree(listResult.getResponse().getContentAsString());

        boolean found = false;
        for (JsonNode project : listRoot.path("data")) {
            if ("Created From MCP Tool".equals(project.path("attributes").path("name").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Expected project created via MCP to be visible over JSON:API").isTrue();
    }

    @Test
    void mcp_withoutToken_returnsUnauthorized() throws Exception {
        String listToolsBody = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """;
        elide(post("/mcp")
                        .header("Accept", MCP_ACCEPT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(listToolsBody))
                .andExpect(status().isUnauthorized());
    }
}
