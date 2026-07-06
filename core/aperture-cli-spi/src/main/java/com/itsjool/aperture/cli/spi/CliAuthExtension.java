package com.itsjool.aperture.cli.spi;

/**
 * Extension point for CLI auth commands (login, refresh, logout, token exchange).
 * Implementations are configured explicitly via Maven plugin {@code <extensions>} config —
 * ServiceLoader-based discovery is not used.
 *
 * Implementations must provide an artifact on the Maven plugin classpath and declare themselves
 * in the plugin configuration.
 */
public interface CliAuthExtension {
    /**
     * Unique identifier for this auth extension (e.g. "simple-auth", "keycloak").
     */
    String id();

    /**
     * Endpoint paths consumed by the built-in username/password Picocli auth command group.
     * Ignored when {@link #authCommandSource(String)} returns non-null.
     */
    AuthPaths authPaths();

    /**
     * Complete Java source for a full replacement auth command group.
     *
     * <p>Returning non-null source tells the generator to write this source into the generated
     * CLI project instead of the built-in {@code SimpleAuthCommand}. The source must declare
     * package {@code com.itsjool.aperture.cli.cmd}, define the class named by
     * {@link #authCommandClassName()}, and provide a Picocli command group named {@code auth}.
     */
    default String authCommandSource(String binaryName) {
        return null;
    }

    /**
     * Simple Java class name declared by {@link #authCommandSource(String)}.
     * Only consulted when {@code authCommandSource(...)} returns non-null.
     */
    default String authCommandClassName() {
        return "AuthCommand";
    }
}
