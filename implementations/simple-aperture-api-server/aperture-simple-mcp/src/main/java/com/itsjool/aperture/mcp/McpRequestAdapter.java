package com.itsjool.aperture.mcp;

import java.util.Map;

/**
 * Runtime seam between generated MCP tool classes and the underlying JSON:API implementation.
 *
 * <p>Generated {@code @Tool} methods (see {@code McpToolGenerator} in {@code aperture-mcp})
 * depend on this interface rather than a concrete class, so a consumer can swap in their own
 * adapter (e.g. to call a different Elide setup, add caching, or route through a gateway) simply
 * by declaring their own {@code @Bean} of this type — {@link ApertureMcpAutoConfiguration}'s
 * default {@code McpElideAdapter} bean is {@code @ConditionalOnMissingBean} and steps aside.
 * This mirrors the audit epic's {@code AuditWriter} SPI seam.
 */
public interface McpRequestAdapter {

    /** Issues a filtered, paginated JSON:API GET against {@code path}. */
    String get(String path, String filter, Integer page, Integer pageSize);

    /** Issues a plain JSON:API GET against {@code path} (e.g. a single-resource lookup). */
    String get(String path);

    /** Issues a JSON:API POST (create) with the given request {@code body}. */
    String post(String path, String body);

    /** Issues a JSON:API PATCH (partial update) with the given request {@code body}. */
    String patch(String path, String body);

    /** Issues a JSON:API DELETE against {@code path}. */
    String delete(String path);

    /**
     * Builds a JSON:API request body with only {@code data.attributes} (no relationships).
     * Equivalent to {@code buildBody(type, id, attributes, Map.of())}.
     */
    String buildBody(String type, String id, Map<String, Object> attributes);

    /**
     * Builds a full JSON:API request body: {@code data.attributes} from {@code attributes}, and
     * {@code data.relationships.<field>.data} from {@code relationships}. Each entry in
     * {@code relationships} is expected to be the (possibly {@code null}) result of
     * {@link #relationshipRef}; {@code null} values are omitted entirely, which is what makes a
     * relationship optional on partial updates — if the caller didn't supply an id for a given
     * relationship field, it's simply left out of the request rather than sent as an explicit
     * null.
     */
    String buildBody(String type, String id, Map<String, Object> attributes, Map<String, Object> relationships);

    /**
     * Builds a single JSON:API relationship reference ({@code {"type": resourceType, "id": id}})
     * for use as a value in the {@code relationships} map passed to {@link #buildBody}. Returns
     * {@code null} when {@code id} is {@code null}, so generated tool code can call this
     * unconditionally (even when a relationship parameter wasn't supplied) and rely on
     * {@link #buildBody} to omit it.
     */
    Object relationshipRef(String resourceType, String id);
}
