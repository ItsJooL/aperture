package com.itsjool.aperture.engine.model;
import java.util.List;

public record McpEntityConfig(
    boolean enabled,
    List<String> tools
) {}
