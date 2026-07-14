package com.itsjool.aperture.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itsjool.apertureautoconfigure.mcp.ApertureMcpAutoConfiguration;
import com.yahoo.elide.spring.controllers.JsonApiController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Vector;

/**
 * Default {@link McpRequestAdapter}, dispatching straight into Elide's {@code JsonApiController}.
 *
 * <p>Deliberately not a {@code @Component}: applications component-scan {@code com.itsjool.aperture},
 * so a stereotype here would register this adapter regardless of {@code aperture.mcp.enabled}, and
 * it would sit alongside any consumer-provided {@link McpRequestAdapter}, making injection into the
 * generated tool classes ambiguous. {@link ApertureMcpAutoConfiguration} owns this bean instead,
 * conditional on both.
 */
public class McpElideAdapter implements McpRequestAdapter {

    private final JsonApiController jsonApiController;
    private final ObjectMapper objectMapper;

    public McpElideAdapter(JsonApiController jsonApiController, ObjectMapper objectMapper) {
        this.jsonApiController = jsonApiController;
        this.objectMapper = objectMapper;
    }

    @Override
    public String get(String path, String filter, Integer page, Integer pageSize) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (filter != null && !filter.isBlank()) params.add("filter", filter);
        if (page != null) params.add("page[number]", String.valueOf(page));
        if (pageSize != null) params.add("page[size]", String.valueOf(pageSize));
        HttpServletRequest req = syntheticRequest("GET", path);
        try {
            ResponseEntity<String> resp = jsonApiController.elideGet(jsonApiHeaders(), params, req).call();
            return resp.getBody();
        } catch (Exception e) {
            return errorBody(e);
        }
    }

    @Override
    public String get(String path) {
        return get(path, null, null, null);
    }

    @Override
    public String post(String path, String body) {
        HttpServletRequest req = syntheticRequest("POST", path);
        try {
            ResponseEntity<String> resp = jsonApiController.elidePost(jsonApiHeaders(), new LinkedMultiValueMap<>(), body, req).call();
            return resp.getBody();
        } catch (Exception e) {
            return errorBody(e);
        }
    }

    @Override
    public String patch(String path, String body) {
        HttpServletRequest req = syntheticRequest("PATCH", path);
        try {
            ResponseEntity<String> resp = jsonApiController.elidePatch(jsonApiHeaders(), new LinkedMultiValueMap<>(), body, req).call();
            return resp.getBody();
        } catch (Exception e) {
            return errorBody(e);
        }
    }

    @Override
    public String delete(String path) {
        HttpServletRequest req = syntheticRequest("DELETE", path);
        try {
            ResponseEntity<String> resp = jsonApiController.elideDelete(jsonApiHeaders(), new LinkedMultiValueMap<>(), req).call();
            return resp.getBody();
        } catch (Exception e) {
            return errorBody(e);
        }
    }

    @Override
    public String buildBody(String type, String id, Map<String, Object> attributes) {
        return buildBody(type, id, attributes, Map.of());
    }

    /**
     * Builds a full JSON:API request body: {@code data.attributes} from {@code attributes}, and
     * {@code data.relationships.<field>.data} from {@code relationships}. Entries in
     * {@code relationships} are expected to come from {@link #relationshipRef}; a {@code null}
     * value (i.e. a relationship whose id was null) is omitted entirely, so a partial update that
     * doesn't touch a given relationship simply leaves it out of the request.
     */
    @Override
    public String buildBody(String type, String id, Map<String, Object> attributes, Map<String, Object> relationships) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("type", type);
        if (id != null) data.put("id", id);
        ObjectNode attrs = objectMapper.createObjectNode();
        attributes.forEach((k, v) -> {
            if (v != null) attrs.putPOJO(k, v);
        });
        data.set("attributes", attrs);
        ObjectNode rels = objectMapper.createObjectNode();
        relationships.forEach((field, ref) -> {
            if (ref == null) return;
            ObjectNode relationship = objectMapper.createObjectNode();
            relationship.set("data", objectMapper.valueToTree(ref));
            rels.set(field, relationship);
        });
        if (!rels.isEmpty()) data.set("relationships", rels);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("data", data);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"data\":{\"type\":\"" + type + "\"}}";
        }
    }

    /**
     * Builds a single JSON:API relationship reference ({@code {"type": resourceType, "id": id}})
     * for use as a value in the {@code relationships} map passed to {@link #buildBody}. Returns
     * {@code null} when {@code id} is {@code null}, so generated tool code can call this
     * unconditionally and rely on {@link #buildBody} to omit it.
     */
    @Override
    public Object relationshipRef(String resourceType, String id) {
        if (id == null) return null;
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("type", resourceType);
        ref.put("id", id);
        return ref;
    }

    // Builds a synthetic request with the target path, copying the request context
    // Elide checks need: principal, Authorization, and scopedBy headers.
    private HttpServletRequest syntheticRequest(String method, String path) {
        SyntheticRequest req = new SyntheticRequest(method, path);
        req.setContentType("application/vnd.api+json");
        req.addHeader("Accept", "application/vnd.api+json");
        req.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, path);
        try {
            HttpServletRequest real = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
            Object principal = real.getAttribute("aperturePrincipal");
            if (principal != null) req.setAttribute("aperturePrincipal", principal);
            String auth = real.getHeader("Authorization");
            if (auth != null) req.addHeader("Authorization", auth);
            Enumeration<String> headerNames = real.getHeaderNames();
            while (headerNames != null && headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                if (headerName.regionMatches(true, 0, "X-Aperture-Scope-", 0, "X-Aperture-Scope-".length())) {
                    Enumeration<String> values = real.getHeaders(headerName);
                    while (values != null && values.hasMoreElements()) {
                        req.addHeader(headerName, values.nextElement());
                    }
                }
            }
        } catch (IllegalStateException ignored) {
            // no active request context (e.g. unit tests)
        }
        return req.asHttpServletRequest();
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

    private static final class SyntheticRequest implements InvocationHandler {
        private final String method;
        private final String path;
        private final Map<String, Object> attributes = new HashMap<>();
        private final Map<String, Vector<String>> headers = new LinkedHashMap<>();
        private String contentType;

        private SyntheticRequest(String method, String path) {
            this.method = method;
            this.path = path;
        }

        void setContentType(String contentType) {
            this.contentType = contentType;
            addHeader("Content-Type", contentType);
        }

        void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        void addHeader(String name, String value) {
            headers.computeIfAbsent(name, ignored -> new Vector<>()).add(value);
        }

        HttpServletRequest asHttpServletRequest() {
            return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class },
                this);
        }

        @Override
        public Object invoke(Object proxy, Method invoked, Object[] args) {
            String name = invoked.getName();
            if ("getMethod".equals(name)) return method;
            if ("getRequestURI".equals(name)) return path;
            if ("getServletPath".equals(name)) return path;
            if ("getPathInfo".equals(name)) return path;
            if ("getRequestURL".equals(name)) return new StringBuffer(path);
            if ("getContentType".equals(name)) return contentType;
            if ("getAttribute".equals(name)) return attributes.get((String) args[0]);
            if ("setAttribute".equals(name)) {
                attributes.put((String) args[0], args[1]);
                return null;
            }
            if ("getHeader".equals(name)) {
                Vector<String> values = headerValues((String) args[0]);
                return values == null || values.isEmpty() ? null : values.firstElement();
            }
            if ("getHeaders".equals(name)) {
                Vector<String> values = headerValues((String) args[0]);
                return values == null ? Collections.emptyEnumeration() : values.elements();
            }
            if ("getHeaderNames".equals(name)) return new Vector<>(headers.keySet()).elements();
            if ("getIntHeader".equals(name)) {
                Vector<String> values = headerValues((String) args[0]);
                if (values == null || values.isEmpty()) return -1;
                try {
                    return Integer.parseInt(values.firstElement());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
            if ("toString".equals(name)) return method + " " + path;
            return defaultValue(invoked.getReturnType());
        }

        private Vector<String> headerValues(String requestedName) {
            for (Map.Entry<String, Vector<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(requestedName)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (boolean.class.equals(type)) return false;
            if (byte.class.equals(type)) return (byte) 0;
            if (short.class.equals(type)) return (short) 0;
            if (int.class.equals(type)) return 0;
            if (long.class.equals(type)) return 0L;
            if (float.class.equals(type)) return 0f;
            if (double.class.equals(type)) return 0d;
            if (char.class.equals(type)) return '\0';
            return null;
        }
    }
}
