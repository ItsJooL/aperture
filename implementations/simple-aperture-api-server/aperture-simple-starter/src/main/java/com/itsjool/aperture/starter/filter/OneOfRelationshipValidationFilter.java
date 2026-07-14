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

    static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;
    private static final Pattern API_ENTITY_PATH = Pattern.compile("^/api/v[0-9]+/([^/]+)(?:/[^/]+)?$");
    private static final Pattern API_RELATIONSHIP_PATH = Pattern.compile(
        "^/api/v[0-9]+/([^/]+)/[^/]+/relationships/([^/]+)$");
    private static final Pattern API_OPERATIONS_PATH = Pattern.compile("^/api/v[0-9]+/operations$");

    private final ObjectMapper objectMapper;
    private final int maxRequestBodyBytes;
    private final Map<String, Map<String, Set<String>>> allowedTypesByResourceAndField;
    private final Map<String, Set<String>> requiredFieldsByResource;

    public OneOfRelationshipValidationFilter(ApertureRuntimeMetadata metadata, ObjectMapper objectMapper) {
        this(metadata, objectMapper, MAX_REQUEST_BODY_BYTES);
    }

    public OneOfRelationshipValidationFilter(ApertureRuntimeMetadata metadata, ObjectMapper objectMapper,
                                             int maxRequestBodyBytes) {
        if (maxRequestBodyBytes < 1 || maxRequestBodyBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxRequestBodyBytes must be between 1 and "
                + (Integer.MAX_VALUE - 1));
        }
        this.objectMapper = objectMapper;
        this.maxRequestBodyBytes = maxRequestBodyBytes;
        this.allowedTypesByResourceAndField = buildAllowedTypes(metadata);
        this.requiredFieldsByResource = buildRequiredFields(metadata);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!shouldValidate(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (request.getContentLengthLong() > maxRequestBodyBytes) {
            writeJsonApiError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "Request body too large", requestBodyLimitDetail());
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxRequestBodyBytes + 1);
        if (body.length > maxRequestBodyBytes) {
            writeJsonApiError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "Request body too large", requestBodyLimitDetail());
            return;
        }
        if (body.length == 0) {
            filterChain.doFilter(new CachedBodyRequest(request, body), response);
            return;
        }

        String violation;
        try {
            violation = validateBody(request, body);
        } catch (JsonProcessingException malformedJson) {
            writeJsonApiError(response, HttpServletResponse.SC_BAD_REQUEST,
                "Invalid one-of relationship", "Malformed JSON request body");
            return;
        }
        if (violation != null) {
            writeJsonApiError(response, HttpServletResponse.SC_BAD_REQUEST,
                "Invalid one-of relationship", violation);
            return;
        }

        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private String requestBodyLimitDetail() {
        return "Request body exceeds the configured validation limit of " + maxRequestBodyBytes + " bytes";
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
        return API_ENTITY_PATH.matcher(uri).matches()
            || API_RELATIONSHIP_PATH.matcher(uri).matches()
            || API_OPERATIONS_PATH.matcher(uri).matches();
    }

    private String validateBody(HttpServletRequest request, byte[] body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        if (API_OPERATIONS_PATH.matcher(request.getRequestURI()).matches()) {
            JsonNode operations = root.path("atomic:operations");
            if (operations.isArray()) {
                for (JsonNode operation : operations) {
                    String violation = validateResource(operation.path("data"), null,
                        isResourceAdd(operation));
                    if (violation != null) {
                        return violation;
                    }
                }
            }
            return null;
        }

        Matcher relationshipMatcher = API_RELATIONSHIP_PATH.matcher(request.getRequestURI());
        if (relationshipMatcher.matches()) {
            return validateRelationshipData(
                relationshipMatcher.group(1), relationshipMatcher.group(2), root.path("data"));
        }

        String fallbackResource = resourceFromPath(request.getRequestURI());
        return validateResource(root.path("data"), fallbackResource,
            "POST".equalsIgnoreCase(request.getMethod()));
    }

    private String validateResource(JsonNode resourceNode, String fallbackResource, boolean create) {
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
        if (create) {
            for (String requiredField : requiredFieldsByResource.getOrDefault(resource, Set.of())) {
                JsonNode relationship = relationships.path(requiredField);
                if (!relationship.isObject() || relationship.path("data").isMissingNode()
                        || relationship.path("data").isNull()) {
                    return resource + "." + requiredField + " is required";
                }
            }
        }
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
            String violation = validateRelationshipData(resource, fieldName, relationshipData);
            if (violation != null) {
                return violation;
            }
        }
        return null;
    }

    private String validateRelationshipData(String resource, String fieldName, JsonNode relationshipData) {
        Map<String, Set<String>> fields = allowedTypesByResourceAndField.get(resource);
        Set<String> allowedTypes = fields == null ? null : fields.get(fieldName);
        if (allowedTypes == null || relationshipData.isMissingNode() || relationshipData.isNull()) {
            if (allowedTypes != null && requiredFieldsByResource.getOrDefault(resource, Set.of()).contains(fieldName)) {
                return resource + "." + fieldName + " is required";
            }
            return null;
        }
        if (!relationshipData.isObject()) {
            return resource + "." + fieldName + " must be a single resource identifier";
        }
        String actualType = textOrNull(relationshipData.path("type"));
        if (actualType == null || !allowedTypes.contains(actualType)) {
            return resource + "." + fieldName + " accepts one of " + allowedTypes
                + " but received " + (actualType == null ? "a resource without type" : actualType);
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
            result.computeIfAbsent(field.resource(), ignored -> new LinkedHashMap<>())
                .put(field.field(), new LinkedHashSet<>(oneOf.memberResourceTypes()));
        }));
        return result;
    }

    private static Map<String, Set<String>> buildRequiredFields(ApertureRuntimeMetadata metadata) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        metadata.oneOfs().values().forEach(oneOf -> oneOf.fields().stream()
            .filter(ApertureRuntimeMetadata.OneOfFieldMetadata::required)
            .forEach(field -> result.computeIfAbsent(field.resource(), ignored -> new LinkedHashSet<>())
                .add(field.field())));
        return result;
    }

    private static boolean isResourceAdd(JsonNode operation) {
        if (!"add".equals(textOrNull(operation.path("op")))) {
            return false;
        }
        JsonNode ref = operation.path("ref");
        if (ref.isObject() && !ref.path("relationship").isMissingNode()) {
            return false;
        }
        String href = textOrNull(operation.path("href"));
        return href == null || !href.contains("/relationships/");
    }

    private void writeJsonApiError(HttpServletResponse response, int status, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/vnd.api+json");
        objectMapper.writeValue(response.getOutputStream(), Map.of(
            "errors", java.util.List.of(Map.of(
                "status", Integer.toString(status),
                "title", title,
                "detail", detail))));
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
