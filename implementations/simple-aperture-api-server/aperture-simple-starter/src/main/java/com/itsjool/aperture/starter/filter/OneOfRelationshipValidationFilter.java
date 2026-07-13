package com.itsjool.aperture.starter.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OneOfRelationshipValidationFilter extends OncePerRequestFilter {

    private static final Pattern API_ENTITY_PATH = Pattern.compile("^/api/v[0-9]+/([^/]+)(?:/[^/]+)?$");
    private static final Pattern API_OPERATIONS_PATH = Pattern.compile("^/api/v[0-9]+/operations$");

    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, Set<String>>> allowedTypesByResourceAndField;

    public OneOfRelationshipValidationFilter(ApertureRuntimeMetadata metadata, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.allowedTypesByResourceAndField = buildAllowedTypes(metadata);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!shouldValidate(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        if (body.length == 0) {
            filterChain.doFilter(new CachedBodyRequest(request, body), response);
            return;
        }

        String violation;
        try {
            violation = validateBody(request, body);
        } catch (JsonProcessingException malformedJson) {
            writeJsonApiError(response, "Malformed JSON request body");
            return;
        }
        if (violation != null) {
            writeJsonApiError(response, violation);
            return;
        }

        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean shouldValidate(HttpServletRequest request) {
        if (allowedTypesByResourceAndField.isEmpty()) {
            return false;
        }
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PATCH".equalsIgnoreCase(method)) {
            return false;
        }
        String uri = request.getRequestURI();
        return API_ENTITY_PATH.matcher(uri).matches() || API_OPERATIONS_PATH.matcher(uri).matches();
    }

    private String validateBody(HttpServletRequest request, byte[] body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        if (API_OPERATIONS_PATH.matcher(request.getRequestURI()).matches()) {
            JsonNode operations = root.path("atomic:operations");
            if (operations.isArray()) {
                for (JsonNode operation : operations) {
                    String violation = validateResource(operation.path("data"), null);
                    if (violation != null) {
                        return violation;
                    }
                }
            }
            return null;
        }

        String fallbackResource = resourceFromPath(request.getRequestURI());
        return validateResource(root.path("data"), fallbackResource);
    }

    private String validateResource(JsonNode resourceNode, String fallbackResource) {
        if (!resourceNode.isObject()) {
            return null;
        }
        String resource = textOrNull(resourceNode.path("type"));
        if (resource == null || resource.isBlank()) {
            resource = fallbackResource;
        }
        if (resource == null || resource.isBlank()) {
            return null;
        }

        Map<String, Set<String>> fields = allowedTypesByResourceAndField.get(resource);
        if (fields == null || fields.isEmpty()) {
            return null;
        }

        JsonNode relationships = resourceNode.path("relationships");
        if (!relationships.isObject()) {
            return null;
        }
        var fieldNames = relationships.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            Set<String> allowedTypes = fields.get(fieldName);
            if (allowedTypes == null) {
                continue;
            }
            JsonNode relationshipData = relationships.path(fieldName).path("data");
            if (relationshipData.isMissingNode() || relationshipData.isNull()) {
                continue;
            }
            if (!relationshipData.isObject()) {
                return resource + "." + fieldName + " must be a single resource identifier";
            }
            String actualType = textOrNull(relationshipData.path("type"));
            if (actualType == null || !allowedTypes.contains(actualType)) {
                return resource + "." + fieldName + " accepts one of " + allowedTypes
                    + " but received " + (actualType == null ? "a resource without type" : actualType);
            }
        }
        return null;
    }

    private String resourceFromPath(String uri) {
        Matcher matcher = API_ENTITY_PATH.matcher(uri);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1);
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static Map<String, Map<String, Set<String>>> buildAllowedTypes(ApertureRuntimeMetadata metadata) {
        Map<String, Map<String, Set<String>>> result = new LinkedHashMap<>();
        metadata.oneOfs().values().forEach(oneOf -> oneOf.fields().forEach(field -> {
            int dot = field.indexOf('.');
            if (dot <= 0 || dot == field.length() - 1) {
                return;
            }
            String resource = field.substring(0, dot);
            String fieldName = field.substring(dot + 1);
            result.computeIfAbsent(resource, ignored -> new LinkedHashMap<>())
                .put(fieldName, new LinkedHashSet<>(oneOf.memberResourceTypes()));
        }));
        return result;
    }

    private static void writeJsonApiError(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/vnd.api+json");
        response.getWriter().write("{\"errors\":[{\"status\":\"400\",\"title\":\"Invalid one-of relationship\",\"detail\":\""
            + escapeJson(detail) + "\"}]}");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() != null
                ? Charset.forName(getCharacterEncoding())
                : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
