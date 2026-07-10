package com.itsjool.aperture.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Spring configuration for the MCP server.
 *
 * <p>There is deliberately no {@code transport} property here. The manifest's
 * {@code spec.mcp.transport} states intent and is validated during generation, but the transport
 * the server actually speaks is set by {@code spring.ai.mcp.server.protocol}. Binding a second,
 * inert {@code aperture.mcp.transport} key only invited operators to set something that did
 * nothing.
 *
 * @param toolListScope whether {@code tools/list} is scoped to the calling principal's roles and
 *                       principal-only ABAC policies ({@link ToolListScope#PRINCIPAL}, the
 *                       default — plan 016 phase 2), or lists every generated tool regardless of
 *                       caller ({@link ToolListScope#STATIC}, the pre-phase-2 behavior). This is
 *                       presentation only: {@code tools/call} is always authorized for real by
 *                       Elide, regardless of this setting. See {@code McpToolListFilter}'s javadoc.
 */
@ConfigurationProperties("aperture.mcp")
public record ApertureMcpProperties(boolean enabled, @DefaultValue("PRINCIPAL") ToolListScope toolListScope) {

    public enum ToolListScope { PRINCIPAL, STATIC }
}
