package com.itsjool.aperture.engine.model;

public record CliConfig(String binaryName) {
    public CliConfig {
        if (binaryName == null || binaryName.isBlank()) binaryName = "aperture";
    }
}
