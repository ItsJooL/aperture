package com.itsjool.aperture.runtime.filter;

/**
 * Names of {@link jakarta.servlet.ServletRequest} attributes that carry observability context
 * across the request lifecycle, decoupled from {@link com.itsjool.aperture.runtime.tenant.TenantContextHolder}
 * and similar {@code ThreadLocal}s that are cleared before the outer HTTP-request
 * {@code Observation} stops.
 *
 * <p>Request attributes live for the full lifetime of the request (they are not scoped to a
 * single filter's {@code try/finally}), so anything stashed here survives until
 * {@code ServerHttpObservationFilter} reads it when the observation stops — including for
 * async/POSTCOMMIT work that runs after the original filter chain has already unwound.
 */
public final class ApertureRequestAttributes {

    /** Effective tenant id for the request, set by {@link AuthFilter} once resolved. */
    public static final String TENANT_ID = "aperture.tenant.id";

    /**
     * The {@link io.micrometer.observation.Observation} that was current on this thread when
     * {@link AuthFilter} ran (i.e. the in-flight {@code http.server.requests} observation opened
     * by Spring's {@code ServerHttpObservationFilter}). Hook execution — which Elide can trigger
     * off the request's observation scope — uses this to link its {@code aperture.hook}
     * observation back to the originating HTTP request instead of starting a detached root span.
     */
    public static final String PARENT_OBSERVATION = "aperture.parent.observation";

    /**
     * The JSON-RPC {@code method} of an {@code /mcp} request body, stashed by
     * {@code McpSanitizationFilter} once it parses the request (it already reads and replays the
     * body for its own sanitization work). {@code McpToolListFilter} reads this instead of
     * shape-detecting the response, so only an actual {@code tools/list} request pays the cost of
     * being buffered and rewritten — every other {@code /mcp} response, notably {@code tools/call},
     * streams through unbuffered.
     */
    public static final String MCP_JSONRPC_METHOD = "aperture.mcp.jsonrpc.method";

    private ApertureRequestAttributes() {
    }
}
