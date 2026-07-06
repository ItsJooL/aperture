package com.itsjool.aperture.cli.simple;

import com.itsjool.aperture.cli.spi.AuthPaths;
import com.itsjool.aperture.cli.spi.CliAuthExtension;

/**
 * Auth CLI extension for the simple-auth server implementation.
 * Provides login, refresh, logout, me, service-account token, and API key management.
 */
public class SimpleAuthCliExtension implements CliAuthExtension {

    @Override
    public String id() {
        return "simple-auth";
    }

    @Override
    public AuthPaths authPaths() {
        SimpleAuthCommand command = new SimpleAuthCommand();
        return new AuthPaths(
            command.loginPath(),
            command.refreshPath(),
            command.logoutPath(),
            command.mePath(),
            command.tokenPath(),
            command.apiKeysPath()
        );
    }
}
