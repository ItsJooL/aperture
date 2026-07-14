package com.itsjool.aperture.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yahoo.elide.spring.controllers.JsonApiController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.Callable;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class McpElideAdapterTest {

    private ObjectMapper objectMapper;
    private McpElideAdapter adapter;
    @Mock
    private JsonApiController jsonApiController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        adapter = new McpElideAdapter(jsonApiController, objectMapper);
    }

    @Test
    void buildBodyEscapesQuotesAndBackslashesInValues() throws Exception {
        String json = adapter.buildBody("product", null, Map.of(
            "name", "foo\"bar",
            "desc", "test\\value"
        ));

        var root = objectMapper.readTree(json);
        assertThat(root).isNotNull();

        var data = root.get("data");
        assertThat(data.get("type").asText()).isEqualTo("product");

        var attrs = data.get("attributes");
        assertThat(attrs.get("name").asText()).isEqualTo("foo\"bar");
        assertThat(attrs.get("desc").asText()).isEqualTo("test\\value");
    }

    @Test
    void buildBodyWithId() throws Exception {
        String json = adapter.buildBody("customer", "123", Map.of("email", "test@example.com"));

        var root = objectMapper.readTree(json);
        var data = root.get("data");
        assertThat(data.get("type").asText()).isEqualTo("customer");
        assertThat(data.get("id").asText()).isEqualTo("123");
        assertThat(data.get("attributes").get("email").asText()).isEqualTo("test@example.com");
    }

    @Test
    void buildBodyFilteredNullValues() throws Exception {
        var attributes = new java.util.HashMap<String, Object>();
        attributes.put("price", 99.99);
        attributes.put("notes", null);

        String json = adapter.buildBody("order", null, attributes);

        var root = objectMapper.readTree(json);
        var attrs = root.get("data").get("attributes");
        assertThat(attrs.get("price").asDouble()).isEqualTo(99.99);
        assertThat(attrs.has("notes")).isFalse();
    }

    @Test
    void buildBodyWithRelationships_splitsAttributesAndRelationships() throws Exception {
        Object projectRef = adapter.relationshipRef("projects", "project-123");
        String json = adapter.buildBody("tasks", null,
                Map.of("title", "Write the report"),
                Map.of("project", projectRef));

        var root = objectMapper.readTree(json);
        var data = root.get("data");
        assertThat(data.get("type").asText()).isEqualTo("tasks");
        assertThat(data.get("attributes").get("title").asText()).isEqualTo("Write the report");

        var relationship = data.get("relationships").get("project").get("data");
        assertThat(relationship.get("type").asText()).isEqualTo("projects");
        assertThat(relationship.get("id").asText()).isEqualTo("project-123");
    }

    @Test
    void buildBodyWithRelationships_nullRelationshipIdOmitted() throws Exception {
        Object nullRef = adapter.relationshipRef("owners", null);
        assertThat(nullRef).isNull();

        var relationships = new java.util.HashMap<String, Object>();
        relationships.put("owner", nullRef);
        String json = adapter.buildBody("tasks", null, Map.of("title", "Untitled"), relationships);

        var root = objectMapper.readTree(json);
        var data = root.get("data");
        assertThat(data.has("relationships")).isFalse();
    }

    @Test
    void buildBodyThreeArgOverload_delegatesToNoRelationships() throws Exception {
        String json = adapter.buildBody("projects", "abc", Map.of("name", "Demo"));

        var root = objectMapper.readTree(json);
        var data = root.get("data");
        assertThat(data.get("attributes").get("name").asText()).isEqualTo("Demo");
        assertThat(data.has("relationships")).isFalse();
    }

    @Test
    void relationshipRef_nonNullId_returnsTypeAndId() {
        Object ref = adapter.relationshipRef("projects", "abc-123");

        assertThat(ref).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> refMap = (Map<String, Object>) ref;
        assertThat(refMap.get("type")).isEqualTo("projects");
        assertThat(refMap.get("id")).isEqualTo("abc-123");
    }

    @Test
    void getCopiesScopeHeadersToSyntheticElideRequest() throws Exception {
        MockHttpServletRequest realRequest = new MockHttpServletRequest("POST", "/mcp");
        realRequest.addHeader("Authorization", "Bearer token");
        realRequest.addHeader("X-Aperture-Scope-Project", "project-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(realRequest));
        ArgumentCaptor<HttpServletRequest> requestCaptor = ArgumentCaptor.forClass(HttpServletRequest.class);
        when(jsonApiController.elideGet(any(), any(MultiValueMap.class), requestCaptor.capture()))
            .thenReturn((Callable<ResponseEntity<String>>) () -> ResponseEntity.ok("{\"data\":[]}"));

        try {
            adapter.get("/api/v1/tasks", null, null, null);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        HttpServletRequest synthetic = requestCaptor.getValue();
        assertThat(synthetic.getHeader("Authorization")).isEqualTo("Bearer token");
        assertThat(synthetic.getHeader("X-Aperture-Scope-Project")).isEqualTo("project-123");
    }

}
