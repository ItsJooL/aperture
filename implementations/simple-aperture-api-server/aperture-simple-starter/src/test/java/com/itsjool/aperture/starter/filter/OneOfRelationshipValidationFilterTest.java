package com.itsjool.aperture.starter.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata;
import com.itsjool.aperture.runtime.config.TenancyMode;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;

class OneOfRelationshipValidationFilterTest {

    private final OneOfRelationshipValidationFilter filter =
        new OneOfRelationshipValidationFilter(metadata(), new ObjectMapper());

    @Test
    void rejectsDirectJsonApiWriteWithUndeclaredOneOfMember() throws Exception {
        MockHttpServletRequest request = jsonApiPost("/api/v1/lineitems", """
            {"data":{"type":"lineitems","relationships":{
              "billable":{"data":{"type":"customers","id":"00000000-0000-0000-0000-000000000001"}}
            }}}
            """);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo("application/vnd.api+json");
        assertThat(response.getContentAsString()).contains("lineitems.billable");
        assertThat(response.getContentAsString()).contains("products");
        assertThat(response.getContentAsString()).contains("servicepackages");
    }

    @Test
    void allowsDeclaredOneOfMemberAndReplaysRequestBody() throws Exception {
        String body = """
            {"data":{"type":"lineitems","relationships":{
              "billable":{"data":{"type":"servicepackages","id":"00000000-0000-0000-0000-000000000001"}}
            }}}
            """;
        MockHttpServletRequest request = jsonApiPost("/api/v1/lineitems", body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> downstreamBody = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
            downstreamBody.set(StreamUtils.copyToString(servletRequest.getInputStream(), StandardCharsets.UTF_8)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(downstreamBody.get()).isEqualTo(body);
    }

    @Test
    void rejectsAtomicOperationWithUndeclaredOneOfMember() throws Exception {
        MockHttpServletRequest request = jsonApiPost("/api/v1/operations", """
            {"atomic:operations":[{"op":"add","data":{"type":"lineitems","relationships":{
              "billable":{"data":{"type":"customers","id":"00000000-0000-0000-0000-000000000001"}}
            }}}]}
            """);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("lineitems.billable");
    }

    @Test
    void rejectsAtomicAddWhenRequiredOneOfRelationshipIsMissing() throws Exception {
        MockHttpServletRequest request = jsonApiPost("/api/v1/operations", """
            {"atomic:operations":[{"op":"add","data":{"type":"lineitems","attributes":{
              "description":"missing billable"
            }}}]}
            """);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("lineitems.billable is required");
    }

    @Test
    void rejectsDirectCreateWhenRequiredOneOfRelationshipIsNull() throws Exception {
        MockHttpServletRequest request = jsonApiPost("/api/v1/lineitems", """
            {"data":{"type":"lineitems","relationships":{"billable":{"data":null}}}}
            """);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("lineitems.billable is required");
    }

    @Test
    void rejectsRelationshipEndpointWriteWithUndeclaredOneOfMember() throws Exception {
        MockHttpServletRequest request = jsonApiRequest("PATCH",
            "/api/v1/lineitems/00000000-0000-0000-0000-000000000002/relationships/billable", """
                {"data":{"type":"customers","id":"00000000-0000-0000-0000-000000000001"}}
                """);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("lineitems.billable");
        assertThat(response.getContentAsString()).contains("customers");
    }

    @Test
    void malformedJsonApiWriteReturnsClientError() throws Exception {
        MockHttpServletRequest request = jsonApiPost("/api/v1/lineitems", "{");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("Malformed JSON request body");
    }

    @Test
    void rejectedMemberTypeIsSerializedAsValidJson() throws Exception {
        MockHttpServletRequest request = jsonApiPost("/api/v1/lineitems", """
            {"data":{"type":"lineitems","relationships":{
              "billable":{"data":{"type":"customers\\ninvalid","id":"1"}}
            }}}
            """);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(new ObjectMapper().readTree(response.getContentAsByteArray())
            .path("errors").path(0).path("detail").asText()).contains("customers\ninvalid");
    }

    @Test
    void rejectsOversizedRequestBeforeBufferingItForDownstream() throws Exception {
        String padding = "x".repeat(OneOfRelationshipValidationFilter.MAX_REQUEST_BODY_BYTES + 1);
        MockHttpServletRequest request = jsonApiPost("/api/v1/lineitems", padding);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(new ObjectMapper().readTree(response.getContentAsByteArray())
            .path("errors").path(0).path("title").asText()).isEqualTo("Request body too large");
    }

    @Test
    void appliesConfiguredRequestBodyLimit() throws Exception {
        OneOfRelationshipValidationFilter smallBodyFilter =
            new OneOfRelationshipValidationFilter(metadata(), new ObjectMapper(), 16);
        MockHttpServletRequest request = jsonApiPost("/api/v1/lineitems", "x".repeat(17));
        MockHttpServletResponse response = new MockHttpServletResponse();

        smallBodyFilter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("configured validation limit of 16 bytes");
    }

    @Test
    void ignoresNonApiWrites() throws ServletException, IOException {
        MockHttpServletRequest request = jsonApiPost("/auth/login", """
            {"relationships":{"billable":{"data":{"type":"customers","id":"1"}}}}
            """);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest jsonApiPost(String uri, String body) {
        return jsonApiRequest("POST", uri, body);
    }

    private static MockHttpServletRequest jsonApiRequest(String method, String uri, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContentType("application/vnd.api+json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static ApertureRuntimeMetadata metadata() {
        return new ApertureRuntimeMetadata(
            List.of("1"),
            List.of("TenantAdmin"),
            List.of("TenantAdmin"),
            Set.of(),
            TenancyMode.POOL,
            null,
            null,
            Map.of(),
            Set.of("lineitems"),
            Map.of("Billable", new ApertureRuntimeMetadata.OneOfMetadata(
                "Billable",
                List.of("Product", "ServicePackage"),
                List.of("products", "servicepackages"),
                List.of(new ApertureRuntimeMetadata.OneOfFieldMetadata("lineitems", "billable", true)))));
    }
}
