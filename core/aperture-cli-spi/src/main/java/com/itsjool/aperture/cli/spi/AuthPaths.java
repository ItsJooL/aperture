package com.itsjool.aperture.cli.spi;

/**
 * Endpoint paths used by generated auth commands.
 */
public record AuthPaths(
    String login,
    String refresh,
    String logout,
    String me,
    String token,
    String apiKeys
) {}
