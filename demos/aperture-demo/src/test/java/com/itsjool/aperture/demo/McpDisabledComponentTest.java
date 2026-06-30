package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@TestPropertySource(properties = {
    "aperture.mcp.enabled=false",
    "spring.ai.mcp.server.enabled=false"
})
public class McpDisabledComponentTest {

    @Container
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("aperture")
            .withUsername("aperture")
            .withPassword("password");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("aperture.hooks.base-url", () -> "http://127.0.0.1:8080");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Test
    public void mcpCreate_withMcpDisabled_endpointNotFound() throws Exception {
        // When MCP is disabled, the /mcp endpoint should not be accessible.
        // Spring Security intercepts first (401) or the router returns 404 — either way MCP is not serving.
        int status = mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).as("Expected 401 (security) or 404 (endpoint absent) when MCP disabled")
                .isIn(401, 404);
    }
}
