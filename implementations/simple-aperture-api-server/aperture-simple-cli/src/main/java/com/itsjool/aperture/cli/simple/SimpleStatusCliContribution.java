package com.itsjool.aperture.cli.simple;

import com.itsjool.aperture.cli.spi.CliCommandContribution;

/**
 * Demo {@link CliCommandContribution}: adds a top-level {@code status} command to the generated
 * CLI that reports server health and the active profile's auth state. Registered alongside
 * {@link SimpleAuthCliExtension} in the demo app's {@code <cli><extensions>} config — the two
 * are separate classes here to show that a project can mix independent
 * {@code CliAuthExtension} and {@code CliCommandContribution} implementations (a single class
 * implementing both is also supported).
 *
 * <p>The emitted source hits {@code /actuator/health}, which {@code AuthFilter} permits
 * unauthenticated (see {@code /actuator/**} in the simple-auth server's security config), so
 * the status check works even for a profile that has never logged in.
 */
public class SimpleStatusCliContribution implements CliCommandContribution {

    @Override
    public String id() {
        return "simple-status";
    }

    @Override
    public String commandClassName() {
        return "StatusCommand";
    }

    @Override
    public String commandSource(String binaryName) {
        return """
            package com.itsjool.aperture.cli.cmd;

            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.itsjool.aperture.cli.ApertureCli;
            import com.itsjool.aperture.cli.config.ConfigStore;
            import com.itsjool.aperture.cli.config.ProfileConfig;
            import picocli.CommandLine;
            import picocli.CommandLine.Command;

            import java.net.URI;
            import java.net.http.HttpClient;
            import java.net.http.HttpRequest;
            import java.net.http.HttpResponse;
            import java.time.Duration;

            @Command(name = "status", description = "Show server health and auth status", mixinStandardHelpOptions = true)
            public class StatusCommand implements Runnable {
                @CommandLine.ParentCommand ApertureCli root;

                @Override
                public void run() {
                    try {
                        var config = ConfigStore.load();
                        String activeProfileName = (String) config.getOrDefault("activeProfile", "default");
                        ProfileConfig profile = ConfigStore.activeProfile(config, root.global.profile);
                        if (root.global.server != null) profile.server = root.global.server;

                        if (profile.server == null || profile.server.isBlank()) {
                            System.err.println("No server configured. Use --server or: @BINARY@ config set-server <url>");
                            System.exit(1);
                            return;
                        }

                        System.out.println("Server:          " + profile.server);
                        System.out.println("Health:          " + fetchHealth(profile.server));
                        System.out.println("Active profile:  " + activeProfileName);

                        boolean loggedIn = profile.auth != null
                            && (profile.auth.accessToken != null || profile.auth.apiKey != null);
                        String authKind = loggedIn && profile.auth.kind != null ? profile.auth.kind : "(none)";
                        System.out.println("Auth:            " + authKind);
                        System.out.println("Authenticated:   " + (loggedIn ? "yes" : "no"));
                    } catch (Exception e) {
                        System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString());
                        System.exit(1);
                    }
                }

                /** GETs {@code <server>/actuator/health} with a plain, unauthenticated HTTP request —
                 *  the endpoint is public (AuthFilter permits /actuator/**), so this works for any
                 *  profile, logged in or not. Exits the process with a clear message if the server
                 *  can't be reached or returns an error status. */
                private static String fetchHealth(String server) {
                    try {
                        HttpClient client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .build();
                        HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(server + "/actuator/health"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();
                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() >= 400) {
                            System.err.println("Health check failed: HTTP " + response.statusCode());
                            System.exit(1);
                        }
                        JsonNode node = new ObjectMapper().readTree(response.body());
                        return node.path("status").asText("UNKNOWN");
                    } catch (Exception e) {
                        System.err.println("Server unreachable: "
                            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                        System.exit(1);
                        throw new IllegalStateException(e); // unreachable — System.exit above never returns
                    }
                }
            }
            """.replace("@BINARY@", binaryName);
    }
}
