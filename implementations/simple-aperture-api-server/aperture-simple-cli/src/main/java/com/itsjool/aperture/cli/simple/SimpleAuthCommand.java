package com.itsjool.aperture.cli.simple;

/**
 * Root command group for simple-auth server auth operations.
 * This class is instantiated at CLI generation time and registered as a subcommand.
 *
 * The actual Picocli annotations live in the generated CLI project (not here),
 * because the generated project has the picocli dependency on its classpath and
 * the annotation processor writes native-image metadata there.
 *
 * This class carries the command metadata as plain Java; the CLI generator
 * wraps it in a generated Picocli @Command when it writes the auth command group.
 */
public class SimpleAuthCommand {
    public static final String AUTH_PATH = "/auth";

    public String loginPath() { return AUTH_PATH + "/login"; }
    public String refreshPath() { return AUTH_PATH + "/refresh"; }
    public String logoutPath() { return AUTH_PATH + "/logout"; }
    public String mePath() { return AUTH_PATH + "/me"; }
    public String tokenPath() { return AUTH_PATH + "/token"; }
    public String apiKeysPath() { return AUTH_PATH + "/me/api-keys"; }
}
