package com.itsjool.aperture.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("aperture.mcp")
public record ApertureMcpProperties(boolean enabled, String transport) {}
