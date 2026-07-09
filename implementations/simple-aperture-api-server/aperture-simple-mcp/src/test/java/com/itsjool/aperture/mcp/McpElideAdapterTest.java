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

import static org.junit.jupiter.api.Assertions.*;
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

        // Parse the JSON to ensure it's valid
        var root = objectMapper.readTree(json);
        assertNotNull(root);

        // Verify structure
        var data = root.get("data");
        assertEquals("product", data.get("type").asText());

        // Verify attributes
        var attrs = data.get("attributes");
        assertEquals("foo\"bar", attrs.get("name").asText());
        assertEquals("test\\value", attrs.get("desc").asText());
    }

    @Test
    void buildBodyWithId() throws Exception {
        String json = adapter.buildBody("customer", "123", Map.of("email", "test@example.com"));

        var root = objectMapper.readTree(json);
        var data = root.get("data");
        assertEquals("customer", data.get("type").asText());
        assertEquals("123", data.get("id").asText());
        assertEquals("test@example.com", data.get("attributes").get("email").asText());
    }

    @Test
    void buildBodyFilteredNullValues() throws Exception {
        var attrs_map = new java.util.HashMap<String, Object>();
        attrs_map.put("price", 99.99);
        attrs_map.put("notes", null);

        String json = adapter.buildBody("order", null, attrs_map);

        var root = objectMapper.readTree(json);
        var attrs = root.get("data").get("attributes");
        assertEquals(99.99, attrs.get("price").asDouble());
        assertFalse(attrs.has("notes"));
    }

    @Test
    void buildBodyWithRelationships_splitsAttributesAndRelationships() throws Exception {
        Object projectRef = adapter.relationshipRef("projects", "project-123");
        String json = adapter.buildBody("tasks", null,
                Map.of("title", "Write the report"),
                Map.of("project", projectRef));

        var root = objectMapper.readTree(json);
        var data = root.get("data");
        assertEquals("tasks", data.get("type").asText());
        assertEquals("Write the report", data.get("attributes").get("title").asText());

        var relationship = data.get("relationships").get("project").get("data");
        assertEquals("projects", relationship.get("type").asText());
        assertEquals("project-123", relationship.get("id").asText());
    }

    @Test
    void buildBodyWithRelationships_nullRelationshipIdOmitted() throws Exception {
        Object nullRef = adapter.relationshipRef("owners", null);
        assertNull(nullRef);

        var relationships = new java.util.HashMap<String, Object>();
        relationships.put("owner", nullRef);
        String json = adapter.buildBody("tasks", null, Map.of("title", "Untitled"), relationships);

        var root = objectMapper.readTree(json);
        var data = root.get("data");
        assertFalse(data.has("relationships"));
    }

    @Test
    void buildBodyThreeArgOverload_delegatesToNoRelationships() throws Exception {
        String json = adapter.buildBody("projects", "abc", Map.of("name", "Demo"));

        var root = objectMapper.readTree(json);
        var data = root.get("data");
        assertEquals("Demo", data.get("attributes").get("name").asText());
        assertFalse(data.has("relationships"));
    }

    @Test
    void relationshipRef_nonNullId_returnsTypeAndId() {
        Object ref = adapter.relationshipRef("projects", "abc-123");

        assertTrue(ref instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> refMap = (Map<String, Object>) ref;
        assertEquals("projects", refMap.get("type"));
        assertEquals("abc-123", refMap.get("id"));
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
        assertEquals("Bearer token", synthetic.getHeader("Authorization"));
        assertEquals("project-123", synthetic.getHeader("X-Aperture-Scope-Project"));
    }

}
