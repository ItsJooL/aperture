package com.itsjool.aperture.engine.model;
import java.util.List;

/**
 * Per-entity MCP configuration. {@code enabled} is deliberately a boxed {@link Boolean}, not a
 * primitive: {@code null} means "unset" (i.e. the entity inherits participation in MCP), while
 * {@code Boolean.FALSE} explicitly excludes the entity. A primitive {@code boolean} cannot
 * represent that middle state, which previously made an entity block with only {@code tools} set
 * silently parse to {@code enabled=false} and vanish from MCP entirely.
 */
public record McpEntityConfig(
    Boolean enabled,
    List<String> tools
) {}
