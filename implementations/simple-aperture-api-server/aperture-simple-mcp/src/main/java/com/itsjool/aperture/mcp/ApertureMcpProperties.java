package com.itsjool.aperture.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring configuration for the MCP server.
 *
 * <p>There is deliberately no {@code transport} property here. The manifest's
 * {@code spec.mcp.transport} states intent and is validated during generation, but the transport
 * the server actually speaks is set by {@code spring.ai.mcp.server.protocol}. Binding a second,
 * inert {@code aperture.mcp.transport} key only invited operators to set something that did
 * nothing.
 */
@ConfigurationProperties("aperture.mcp")
public record ApertureMcpProperties(boolean enabled) {}
