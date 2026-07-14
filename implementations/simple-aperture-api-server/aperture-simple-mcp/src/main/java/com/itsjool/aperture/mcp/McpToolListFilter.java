package com.itsjool.aperture.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itsjool.aperture.runtime.filter.ApertureRequestAttributes;
import com.itsjool.aperture.runtime.security.AbacPolicyEvaluator;
import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.apertureautoconfigure.mcp.ApertureMcpAutoConfiguration;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scopes {@code tools/list} responses to what the calling principal's roles and principal-only
 * ABAC policies actually permit (plan 016 phase 2).
 *
 * <p><b>This is not an authorization boundary.</b> {@code tools/call} is still authorized for real
 * by Elide on every invocation, completely independent of this filter. This filter only shapes
 * what names appear in a {@code tools/list} response — it exists so an agent isn't tempted with
 * tools its principal could never successfully call, not to enforce anything. A bug here can leak
 * a tool <em>name</em> to a caller who shouldn't see it; it can never leak data, and it can never
 * let a call through that Elide would otherwise reject.
 *
 * <p>Only a {@code tools/list} request pays for buffering and rewriting the response. The request's
 * JSON-RPC {@code method} is read from {@link com.itsjool.aperture.runtime.filter.ApertureRequestAttributes#MCP_JSONRPC_METHOD},
 * stashed by {@link McpSanitizationFilter} (one filter earlier, {@code @Order(-100)} vs this
 * filter's {@code @Order(-90)}) when it parses the request body for its own sanitization — for
 * free, since that filter already reads and replays the body. Every other {@code /mcp} response,
 * notably {@code tools/call} (the frequent, potentially large call), streams straight through
 * unbuffered. When the response is rewritten, it handles both a plain JSON body and an SSE-framed
 * one ({@code data: {...} \n\n}); the demo's stateless streamable-http server was observed
 * returning plain JSON even with an {@code Accept: text/event-stream} header, but both are handled
 * defensively. A body that can't be parsed as either is passed through unmodified, with a log
 * line — never dropped.
 *
 * <p>Not a {@code @Component}, for the same reason as {@link McpElideAdapter} and
 * {@link McpSanitizationFilter}: applications component-scan {@code com.itsjool.aperture}, so a
 * stereotype here would install this filter into every application regardless of configuration.
 * {@link ApertureMcpAutoConfiguration} registers it as a {@code @Bean}, conditional on
 * {@code aperture.mcp.tool-list-scope} being {@code PRINCIPAL} (the default) rather than
 * {@code STATIC}.
 */
@Order(-90)
public class McpToolListFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(McpToolListFilter.class);
    private static final String PRINCIPAL_ATTRIBUTE = "aperturePrincipal";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpelExpressionParser expressionParser = new SpelExpressionParser();
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        if (!"/mcp".equals(httpRequest.getRequestURI()) || !"POST".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String jsonRpcMethod = (String) request.getAttribute(ApertureRequestAttributes.MCP_JSONRPC_METHOD);
        if (!"tools/list".equals(jsonRpcMethod)) {
            // Not a tools/list request (or McpSanitizationFilter didn't run ahead of this filter
            // to stash the method) — nothing for this filter to do. Stream the response straight
            // through instead of buffering it into heap just to shape-detect it, which matters
            // most for tools/call, the frequent, potentially large call.
            chain.doFilter(request, response);
            return;
        }

        HttpServletResponse httpResponse = (HttpServletResponse) response;
        BufferingResponseWrapper wrapper = new BufferingResponseWrapper(httpResponse);
        chain.doFilter(request, wrapper);

        byte[] original = wrapper.bufferedBytes();
        byte[] rewritten = rewriteIfToolsList(original, httpRequest);
        httpResponse.setContentLength(rewritten.length);
        httpResponse.getOutputStream().write(rewritten);
    }

    private byte[] rewriteIfToolsList(byte[] body, HttpServletRequest request) {
        if (body.length == 0) return body;

        String text = new String(body, StandardCharsets.UTF_8);
        String trimmed = text.strip();
        boolean sse = trimmed.startsWith("data:");
        String jsonPart = sse ? trimmed.substring(trimmed.indexOf(':') + 1).strip() : trimmed;

        try {
            JsonNode root = objectMapper.readTree(jsonPart);
            JsonNode toolsNode = root.path("result").path("tools");
            if (!toolsNode.isArray()) {
                // Not a tools/list response (e.g. a tools/call result, or an error) — nothing to do.
                return body;
            }

            Object principalAttr = request.getAttribute(PRINCIPAL_ATTRIBUTE);
            if (!(principalAttr instanceof AperturePrincipal principal) || principal.superAdmin()) {
                return body;
            }

            Map<String, McpToolAccess> registry = McpToolRegistryBridge.load();
            ArrayNode filtered = objectMapper.createArrayNode();
            for (JsonNode tool : toolsNode) {
                String name = tool.path("name").isTextual() ? tool.path("name").asText() : null;
                if (name == null || retain(name, registry, principal)) {
                    filtered.add(tool);
                }
            }
            ((ObjectNode) root.get("result")).set("tools", filtered);

            String rewrittenJson = objectMapper.writeValueAsString(root);
            String rewrittenText = sse ? "data: " + rewrittenJson + "\n\n" : rewrittenJson;
            return rewrittenText.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Could not parse an MCP response body to apply principal-scoped tools/list "
                    + "filtering; passing it through unfiltered", e);
            return body;
        }
    }

    /**
     * Retains a tool iff it is {@code publicOperation}; or the principal is a TenantAdmin and the
     * tool's entity grants TenantAdmin an unconditional bypass ({@link McpToolAccess#tenantAdminBypass()} —
     * see {@code McpToolAccessClassifier}'s javadoc (in {@code aperture-mcp}) for why TenantAdmin
     * needs its own check distinct from {@code roles()}); or the principal has a role the registry
     * grants the operation to <em>and</em> every principal-only policy on it evaluates true. A
     * tool absent from {@code registry} is not registry-governed (e.g. an {@code McpToolContribution}
     * tool) and is always retained.
     */
    private boolean retain(String toolName, Map<String, McpToolAccess> registry, AperturePrincipal principal) {
        McpToolAccess access = registry.get(toolName);
        if (access == null) return true;
        if (access.publicOperation()) return true;
        if (access.tenantAdminBypass() && principal.tenantAdmin()) return true;

        Set<String> principalRoles = principal.roles() != null ? principal.roles() : Set.of();
        if (Collections.disjoint(principalRoles, access.roles())) return false;

        for (String expressionText : access.principalOnlyPolicyExpressions()) {
            Expression expression = expressionCache.computeIfAbsent(expressionText, expressionParser::parseExpression);
            if (!AbacPolicyEvaluator.evaluate(expression, null, null, principal)) {
                return false;
            }
        }
        return true;
    }

    /** Buffers everything written downstream so it can be inspected and possibly rewritten. */
    private static final class BufferingResponseWrapper extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private ServletOutputStream servletOutputStream;
        private PrintWriter writer;

        BufferingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (servletOutputStream == null) {
                servletOutputStream = new ServletOutputStream() {
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener writeListener) { }
                    @Override public void write(int b) { buffer.write(b); }
                    @Override public void write(byte[] b, int off, int len) { buffer.write(b, off, len); }
                };
            }
            return servletOutputStream;
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), StandardCharsets.UTF_8), true);
            }
            return writer;
        }

        byte[] bufferedBytes() {
            if (writer != null) writer.flush();
            return buffer.toByteArray();
        }
    }
}
