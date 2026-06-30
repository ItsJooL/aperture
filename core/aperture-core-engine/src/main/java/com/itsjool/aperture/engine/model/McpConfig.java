package com.itsjool.aperture.engine.model;
import java.util.List;

public record McpConfig(
    boolean enabled,
    String transport,
    List<String> tools
) {}
