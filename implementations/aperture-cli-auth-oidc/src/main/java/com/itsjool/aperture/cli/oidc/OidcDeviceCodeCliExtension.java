package com.itsjool.aperture.cli.oidc;

import com.itsjool.aperture.cli.spi.AuthPaths;
import com.itsjool.aperture.cli.spi.CliAuthExtension;

/**
 * Emits an OIDC device-code auth command for generated CLIs.
 */
public class OidcDeviceCodeCliExtension implements CliAuthExtension {

    @Override
    public String id() {
        return "oidc-device-code";
    }

    @Override
    public AuthPaths authPaths() {
        return new AuthPaths("/unused-login", "/unused-refresh", "/unused-logout",
            "/unused-me", "/unused-token", "/unused-api-keys");
    }

    @Override
    public String authCommandClassName() {
        return "AuthCommand";
    }

    @Override
    public String authCommandSource(String binaryName) {
        return """
            package com.itsjool.aperture.cli.cmd;

            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.itsjool.aperture.cli.ApertureCli;
            import com.itsjool.aperture.cli.GlobalOptions;
            import com.itsjool.aperture.cli.config.ConfigStore;
            import com.itsjool.aperture.cli.config.ProfileConfig;
            import picocli.CommandLine;
            import picocli.CommandLine.Command;
            import picocli.CommandLine.Option;

            import java.net.URI;
            import java.net.URLEncoder;
            import java.net.http.HttpClient;
            import java.net.http.HttpRequest;
            import java.net.http.HttpResponse;
            import java.nio.charset.StandardCharsets;
            import java.time.Instant;
            import java.util.LinkedHashMap;
            import java.util.Map;

            @Command(name = "auth", description = "OIDC authentication commands", mixinStandardHelpOptions = true,
                subcommands = {
                    AuthCommand.LoginCommand.class,
                    AuthCommand.RefreshCommand.class,
                    AuthCommand.LogoutCommand.class,
                    AuthCommand.MeCommand.class
                })
            public class AuthCommand implements Runnable {
                @CommandLine.ParentCommand ApertureCli root;
                private static final ObjectMapper MAPPER = new ObjectMapper();
                private static final HttpClient HTTP = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

                @Override public void run() { CommandLine.usage(this, System.out); }

                private record RawResponse(int status, String body) {}
                private record OidcSettings(String issuer, String clientId) {}

                private static JsonNode discovery(String issuer) throws Exception {
                    String normalized = trimTrailingSlash(issuer);
                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(normalized + "/.well-known/openid-configuration"))
                        .GET()
                        .build();
                    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() >= 400) {
                        throw new IllegalStateException("OIDC discovery failed (HTTP " + resp.statusCode() + "): " + resp.body());
                    }
                    return MAPPER.readTree(resp.body());
                }

                private static RawResponse postForm(String url, Map<String, String> form) throws Exception {
                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody(form)))
                        .build();
                    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                    return new RawResponse(resp.statusCode(), resp.body());
                }

                private static JsonNode getJson(String url, String token) throws Exception {
                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
                    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() >= 400) {
                        throw new IllegalStateException("Request failed (HTTP " + resp.statusCode() + "): " + resp.body());
                    }
                    return MAPPER.readTree(resp.body());
                }

                private static String formBody(Map<String, String> form) {
                    StringBuilder body = new StringBuilder();
                    form.forEach((key, value) -> {
                        if (body.length() > 0) body.append("&");
                        body.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
                        body.append("=");
                        body.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                    });
                    return body.toString();
                }

                private static String trimTrailingSlash(String value) {
                    if (value == null) return null;
                    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
                }

                @SuppressWarnings("unchecked")
                private static Map<String, Object> profileMap(Map<String, Object> config, GlobalOptions global) {
                    String activeProfile = activeProfileName(config, global);
                    Map<String, Object> profiles = (Map<String, Object>) config.computeIfAbsent("profiles", k -> new LinkedHashMap<>());
                    return (Map<String, Object>) profiles.computeIfAbsent(activeProfile, k -> new LinkedHashMap<>());
                }

                private static String activeProfileName(Map<String, Object> config, GlobalOptions global) {
                    return global.profile != null ? global.profile : (String) config.getOrDefault("activeProfile", "default");
                }

                @SuppressWarnings("unchecked")
                private static OidcSettings resolveSettings(Map<String, Object> profileMap, String issuer, String clientId) {
                    Map<String, Object> oidc = (Map<String, Object>) profileMap.getOrDefault("oidc", new LinkedHashMap<>());
                    String resolvedIssuer = issuer != null && !issuer.isBlank() ? issuer : (String) oidc.get("issuer");
                    String resolvedClientId = clientId != null && !clientId.isBlank() ? clientId : (String) oidc.get("clientId");
                    if (resolvedIssuer == null || resolvedIssuer.isBlank() || resolvedClientId == null || resolvedClientId.isBlank()) {
                        throw new IllegalStateException("Missing OIDC settings. Run: @BINARY@ auth login --issuer <url> --client-id <id>");
                    }
                    return new OidcSettings(trimTrailingSlash(resolvedIssuer), resolvedClientId);
                }

                @SuppressWarnings("unchecked")
                private static void storeTokens(Map<String, Object> profileMap, String issuer, String clientId,
                                                JsonNode tokenResponse) {
                    Map<String, Object> existingAuth = (Map<String, Object>) profileMap.getOrDefault("auth", new LinkedHashMap<>());
                    Map<String, Object> auth = new LinkedHashMap<>();
                    auth.put("kind", "bearer");
                    auth.put("accessToken", tokenResponse.path("access_token").asText(null));
                    if (tokenResponse.hasNonNull("refresh_token")) {
                        auth.put("refreshToken", tokenResponse.path("refresh_token").asText());
                    } else if (existingAuth.get("refreshToken") != null) {
                        auth.put("refreshToken", existingAuth.get("refreshToken"));
                    }
                    profileMap.put("auth", auth);
                    Map<String, Object> oidc = (Map<String, Object>) profileMap.computeIfAbsent("oidc", k -> new LinkedHashMap<>());
                    oidc.put("issuer", issuer);
                    oidc.put("clientId", clientId);
                }

                private static JsonNode parseTokenOrThrow(RawResponse response, String action) throws Exception {
                    JsonNode body = MAPPER.readTree(response.body());
                    if (response.status() >= 400) {
                        String error = body.path("error").asText(body.toString());
                        String description = body.path("error_description").asText("");
                        throw new IllegalStateException(action + " failed: " + error
                            + (description.isBlank() ? "" : " - " + description));
                    }
                    return body;
                }

                @Command(name = "login", description = "Log in with OIDC device code", mixinStandardHelpOptions = true)
                static class LoginCommand implements Runnable {
                    @CommandLine.ParentCommand AuthCommand parent;
                    @Option(names = "--issuer", description = "OIDC issuer URL") String issuer;
                    @Option(names = "--client-id", description = "OIDC public client id") String clientId;

                    @Override public void run() {
                        try {
                            var config = ConfigStore.load();
                            Map<String, Object> profileMap = profileMap(config, parent.root.global);
                            OidcSettings settings = resolveSettings(profileMap, issuer, clientId);
                            JsonNode discovery = discovery(settings.issuer());
                            String deviceEndpoint = discovery.path("device_authorization_endpoint").asText(null);
                            String tokenEndpoint = discovery.path("token_endpoint").asText(null);
                            if (deviceEndpoint == null || tokenEndpoint == null) {
                                throw new IllegalStateException("Issuer discovery did not advertise device_authorization_endpoint and token_endpoint");
                            }
                            RawResponse deviceResp = postForm(deviceEndpoint, Map.of(
                                "client_id", settings.clientId(),
                                "scope", "openid"));
                            JsonNode device = parseTokenOrThrow(deviceResp, "Device authorization");
                            String verification = device.path("verification_uri_complete").asText(device.path("verification_uri").asText());
                            String userCode = device.path("user_code").asText();
                            String deviceCode = device.path("device_code").asText();
                            long intervalSeconds = Math.max(1, device.path("interval").asLong(5));
                            long expiresIn = Math.max(1, device.path("expires_in").asLong(300));
                            Instant deadline = Instant.now().plusSeconds(expiresIn);

                            System.out.println("Open " + verification);
                            System.out.println("and enter code: " + userCode);
                            System.out.print("Waiting for approval...");

                            while (Instant.now().isBefore(deadline)) {
                                Thread.sleep(intervalSeconds * 1000L);
                                RawResponse tokenResp = postForm(tokenEndpoint, Map.of(
                                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                                    "client_id", settings.clientId(),
                                    "device_code", deviceCode));
                                JsonNode token = MAPPER.readTree(tokenResp.body());
                                if (tokenResp.status() < 400) {
                                    storeTokens(profileMap, settings.issuer(), settings.clientId(), token);
                                    ConfigStore.save(config);
                                    long accessExpiresIn = token.path("expires_in").asLong(0);
                                    System.out.println(" logged in" + (accessExpiresIn > 0 ? " (expires in " + accessExpiresIn + "s)" : ""));
                                    return;
                                }
                                String error = token.path("error").asText();
                                if ("authorization_pending".equals(error)) {
                                    System.out.print(".");
                                } else if ("slow_down".equals(error)) {
                                    intervalSeconds += 5;
                                    System.out.print(".");
                                } else if ("access_denied".equals(error) || "expired_token".equals(error)) {
                                    throw new IllegalStateException("Login failed: " + error);
                                } else {
                                    throw new IllegalStateException("Login failed: " + token);
                                }
                            }
                            throw new IllegalStateException("Login failed: expired_token");
                        } catch (Exception e) {
                            System.err.println(e.getMessage() != null ? e.getMessage() : e.toString());
                            System.exit(1);
                        }
                    }
                }

                @Command(name = "refresh", description = "Refresh the OIDC access token")
                static class RefreshCommand implements Runnable {
                    @CommandLine.ParentCommand AuthCommand parent;
                    @Override public void run() {
                        try {
                            var config = ConfigStore.load();
                            Map<String, Object> profileMap = profileMap(config, parent.root.global);
                            OidcSettings settings = resolveSettings(profileMap, null, null);
                            ProfileConfig profile = ConfigStore.activeProfile(config, parent.root.global.profile);
                            if (profile.auth == null || profile.auth.refreshToken == null || profile.auth.refreshToken.isBlank()) {
                                throw new IllegalStateException("No refresh token found. Run: @BINARY@ auth login");
                            }
                            JsonNode discovery = discovery(settings.issuer());
                            RawResponse refreshResp = postForm(discovery.path("token_endpoint").asText(), Map.of(
                                "grant_type", "refresh_token",
                                "client_id", settings.clientId(),
                                "refresh_token", profile.auth.refreshToken));
                            JsonNode token = parseTokenOrThrow(refreshResp, "Refresh");
                            storeTokens(profileMap, settings.issuer(), settings.clientId(), token);
                            ConfigStore.save(config);
                            System.out.println("Token refreshed.");
                        } catch (Exception e) {
                            System.err.println(e.getMessage() != null ? e.getMessage() : e.toString());
                            System.exit(1);
                        }
                    }
                }

                @Command(name = "logout", description = "Clear local OIDC credentials")
                static class LogoutCommand implements Runnable {
                    @CommandLine.ParentCommand AuthCommand parent;
                    @Override public void run() {
                        try {
                            var config = ConfigStore.load();
                            Map<String, Object> profileMap = profileMap(config, parent.root.global);
                            OidcSettings settings = null;
                            try { settings = resolveSettings(profileMap, null, null); } catch (Exception ignored) {}
                            ProfileConfig profile = ConfigStore.activeProfile(config, parent.root.global.profile);
                            if (settings != null && profile.auth != null && profile.auth.refreshToken != null) {
                                try {
                                    JsonNode discovery = discovery(settings.issuer());
                                    if (discovery.hasNonNull("revocation_endpoint")) {
                                        postForm(discovery.path("revocation_endpoint").asText(), Map.of(
                                            "client_id", settings.clientId(),
                                            "token", profile.auth.refreshToken));
                                    }
                                } catch (Exception ignored) {
                                    System.err.println("Token revocation failed; clearing local credentials.");
                                }
                            }
                            profileMap.remove("auth");
                            ConfigStore.save(config);
                            System.out.println("Logged out.");
                        } catch (Exception e) {
                            System.err.println(e.getMessage() != null ? e.getMessage() : e.toString());
                            System.exit(1);
                        }
                    }
                }

                @Command(name = "me", description = "Show current OIDC userinfo")
                static class MeCommand implements Runnable {
                    @CommandLine.ParentCommand AuthCommand parent;
                    @Override public void run() {
                        try {
                            var config = ConfigStore.load();
                            Map<String, Object> profileMap = profileMap(config, parent.root.global);
                            OidcSettings settings = resolveSettings(profileMap, null, null);
                            ProfileConfig profile = ConfigStore.activeProfile(config, parent.root.global.profile);
                            if (profile.auth == null || profile.auth.accessToken == null || profile.auth.accessToken.isBlank()) {
                                throw new IllegalStateException("Not logged in. Run: @BINARY@ auth login");
                            }
                            JsonNode discovery = discovery(settings.issuer());
                            String userinfo = discovery.path("userinfo_endpoint").asText(null);
                            if (userinfo == null || userinfo.isBlank()) {
                                throw new IllegalStateException("Issuer discovery did not advertise userinfo_endpoint");
                            }
                            OutputFormatter.print(getJson(userinfo, profile.auth.accessToken), parent.root.global.format);
                        } catch (Exception e) {
                            System.err.println(e.getMessage() != null ? e.getMessage() : e.toString());
                            System.exit(1);
                        }
                    }
                }
            }
            """.replace("@BINARY@", binaryName);
    }
}
