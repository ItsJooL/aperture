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
        var actions = mockMvc.perform(builder);
        var result = actions.andReturn();
        if (result.getRequest().isAsyncStarted()) {
            return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result));
        }
        return actions;
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

    private String loginAsAgentAdmin() throws Exception {
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"agent-admin@mcp-demo.local\",\"password\":\"changeme-local-only\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("accessToken").isMissingNode()).isFalse();
        return "Bearer " + root.path("accessToken").asText();
    }

    private String createAgentApiKey() throws Exception {
        return createAgentApiKey("Admin", "Component test MCP key");
    }

    private String createAgentApiKey(String domainRole, String keyName) throws Exception {
        String token = loginAsAgentAdmin();
        var result = mockMvc.perform(post("/auth/me/api-keys")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "domainRoles": ["%s"],
                                  "securityAttributes": {}
                                }
                                """.formatted(keyName, domainRole)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("secret").isMissingNode()).isFalse();
        return root.path("secret").asText();
    }

    private JsonNode listMcpTools(String apiKey) throws Exception {
        String listToolsBody = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """;
        var result = elide(post("/mcp")
                        .header("X-API-Key", apiKey)
                        .header("Accept", MCP_ACCEPT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(listToolsBody))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
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
    void bootstrapCreatesAgentAdmin_andPersonalApiKeyCanCallMcp() throws Exception {
        String apiKey = createAgentApiKey();

        String listToolsBody = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """;
        var result = elide(post("/mcp")
                        .header("X-API-Key", apiKey)
                        .header("Accept", MCP_ACCEPT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(listToolsBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("result").path("tools").isArray()).isTrue();
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
    void mcpListTools_exposesProjectAndTaskFullCrud() throws Exception {
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

        // Task's required ManyToOne `project` field is now writable via JSON:API relationships
        // (see McpElideAdapter#relationshipRef / #buildBody), so Task also exposes full CRUD.
        assertThat(toolNames).contains(
                "list_tasks", "get_task", "create_task", "update_task", "delete_task");
    }

    @Test
    void mcpToolsList_adminApiKeySeesAllTenTools() throws Exception {
        // Principal-scoped tools/list (plan 016 phase 2): an Admin-role API key — not the
        // superadmin bypass used by mcpListTools_exposesProjectAndTaskFullCrud above — must still
        // see the full generated tool surface, since Admin's permissions grant full CRUD on both
        // Project and Task.
        String apiKey = createAgentApiKey("Admin", "Admin tools/list test key");

        JsonNode root = listMcpTools(apiKey);
        List<String> toolNames = new ArrayList<>();
        root.path("result").path("tools").forEach(tool -> toolNames.add(tool.path("name").asText()));

        assertThat(toolNames).containsExactlyInAnyOrder(
                "list_projects", "get_project", "create_project", "update_project", "delete_project",
                "list_tasks", "get_task", "create_task", "update_task", "delete_task");
    }

    @Test
    void mcpToolsList_readOnlyApiKeySeesOnlyReadTools() throws Exception {
        // ReadOnly's permissions only grant "read" on both entities, so it must see exactly the
        // four read tools and none of the write ones — proving tools/list is actually scoped to
        // the calling principal's roles, not just returning the full generated surface.
        String apiKey = createAgentApiKey("ReadOnly", "ReadOnly tools/list test key");

        JsonNode root = listMcpTools(apiKey);
        List<String> toolNames = new ArrayList<>();
        root.path("result").path("tools").forEach(tool -> toolNames.add(tool.path("name").asText()));

        assertThat(toolNames).containsExactlyInAnyOrder("list_projects", "get_project", "list_tasks", "get_task");
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
    void mcpCreateTask_withProjectId_persistsRelationshipLink() throws Exception {
        String token = loginAsSuperadmin();

        JsonNode projectResult = callMcpTool(token, 3, "create_project",
                "{\"name\":\"Task Relationship Project\"}");
        assertThat(projectResult.has("error")).as("Unexpected MCP error: " + projectResult).isFalse();

        var projectListResult = elide(get("/api/v1/projects")
                        .header("Authorization", token)
                        .accept(VNDAPI))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode projectList = MAPPER.readTree(projectListResult.getResponse().getContentAsString());
        String projectId = null;
        for (JsonNode project : projectList.path("data")) {
            if ("Task Relationship Project".equals(project.path("attributes").path("name").asText())) {
                projectId = project.path("id").asText();
                break;
            }
        }
        assertThat(projectId).as("Expected the project created via MCP to be findable").isNotNull();

        JsonNode taskResult = callMcpTool(token, 4, "create_task",
                "{\"title\":\"Wire up the relationship\",\"project_id\":\"" + projectId + "\"}");
        assertThat(taskResult.has("error")).as("Unexpected MCP error: " + taskResult).isFalse();

        var taskListResult = elide(get("/api/v1/tasks")
                        .header("Authorization", token)
                        .accept(VNDAPI))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode taskList = MAPPER.readTree(taskListResult.getResponse().getContentAsString());

        boolean linked = false;
        for (JsonNode task : taskList.path("data")) {
            if ("Wire up the relationship".equals(task.path("attributes").path("title").asText())) {
                assertThat(task.path("relationships").path("project").path("data").path("id").asText())
                        .isEqualTo(projectId);
                linked = true;
                break;
            }
        }
        assertThat(linked).as("Expected the task created via MCP to be linked to its project").isTrue();
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
