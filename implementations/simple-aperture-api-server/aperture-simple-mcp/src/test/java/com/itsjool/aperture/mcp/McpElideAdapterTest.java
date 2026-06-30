package com.itsjool.aperture.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yahoo.elide.spring.controllers.JsonApiController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

}
