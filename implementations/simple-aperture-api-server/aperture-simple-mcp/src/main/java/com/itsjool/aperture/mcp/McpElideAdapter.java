package com.itsjool.aperture.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yahoo.elide.spring.controllers.JsonApiController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.HashMap;
import java.util.Map;

@Component
public class McpElideAdapter {

    private final JsonApiController jsonApiController;
    private final ObjectMapper objectMapper;

    public McpElideAdapter(JsonApiController jsonApiController, ObjectMapper objectMapper) {
        this.jsonApiController = jsonApiController;
        this.objectMapper = objectMapper;
    }

    public String get(String path, String filter, Integer page, Integer pageSize) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (filter != null && !filter.isBlank()) params.add("filter", filter);
        if (page != null) params.add("page[number]", String.valueOf(page));
        if (pageSize != null) params.add("page[size]", String.valueOf(pageSize));
        MockHttpServletRequest req = syntheticRequest("GET", path);
        try {
            ResponseEntity<String> resp = jsonApiController.elideGet(jsonApiHeaders(), params, req).call();
            return resp.getBody();
        } catch (Exception e) {
            return errorBody(e);
        }
    }

    public String get(String path) {
        return get(path, null, null, null);
    }

    public String post(String path, String body) {
        MockHttpServletRequest req = syntheticRequest("POST", path);
        try {
            ResponseEntity<String> resp = jsonApiController.elidePost(jsonApiHeaders(), new LinkedMultiValueMap<>(), body, req).call();
            return resp.getBody();
        } catch (Exception e) {
            return errorBody(e);
        }
    }

    public String patch(String path, String body) {
        MockHttpServletRequest req = syntheticRequest("PATCH", path);
        try {
            ResponseEntity<String> resp = jsonApiController.elidePatch(jsonApiHeaders(), new LinkedMultiValueMap<>(), body, req).call();
            return resp.getBody();
        } catch (Exception e) {
            return errorBody(e);
        }
    }

    public String delete(String path) {
        MockHttpServletRequest req = syntheticRequest("DELETE", path);
        try {
            ResponseEntity<String> resp = jsonApiController.elideDelete(jsonApiHeaders(), new LinkedMultiValueMap<>(), req).call();
            return resp.getBody();
        } catch (Exception e) {
            return errorBody(e);
        }
    }

    public String buildBody(String type, String id, Map<String, Object> attributes) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("type", type);
        if (id != null) data.put("id", id);
        ObjectNode attrs = objectMapper.createObjectNode();
        attributes.forEach((k, v) -> {
            if (v != null) attrs.putPOJO(k, v);
        });
        data.set("attributes", attrs);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("data", data);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"data\":{\"type\":\"" + type + "\"}}";
        }
    }

    // Builds a synthetic request with the target path, copying the aperturePrincipal
    // attribute from the current request so Elide sees the authenticated user.
    private MockHttpServletRequest syntheticRequest(String method, String path) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setContentType("application/vnd.api+json");
        req.addHeader("Accept", "application/vnd.api+json");
        // JsonApiController.getPath() reads PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE to derive
        // the resource path; without it the attribute is null and replaceFirst() throws NPE.
        req.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, path);
        try {
            HttpServletRequest real = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
            Object principal = real.getAttribute("aperturePrincipal");
            if (principal != null) req.setAttribute("aperturePrincipal", principal);
            String auth = real.getHeader("Authorization");
            if (auth != null) req.addHeader("Authorization", auth);
        } catch (IllegalStateException ignored) {
            // no active request context (e.g. unit tests)
        }
        return req;
    }

    private HttpHeaders jsonApiHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Content-Type", "application/vnd.api+json");
        h.set("Accept", "application/vnd.api+json");
        return h;
    }

    private String errorBody(Exception e) {
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        try {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("detail", detail);
            ObjectNode root = objectMapper.createObjectNode();
            root.putArray("errors").add(error);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            return "{\"errors\":[{\"detail\":\"Internal error\"}]}";
        }
    }
}
