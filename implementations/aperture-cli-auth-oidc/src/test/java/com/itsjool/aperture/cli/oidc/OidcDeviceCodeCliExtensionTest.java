package com.itsjool.aperture.cli.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

class OidcDeviceCodeCliExtensionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void emitsFullAuthCommandSourceForOidcDeviceCodeFlow() {
        OidcDeviceCodeCliExtension extension = new OidcDeviceCodeCliExtension();

        String source = extension.authCommandSource("apkc");

        assertThat(extension.id()).isEqualTo("oidc-device-code");
        assertThat(extension.authCommandClassName()).isEqualTo("AuthCommand");
        assertThat(source)
            .contains("package com.itsjool.aperture.cli.cmd;")
            .contains("public class AuthCommand implements Runnable")
            .contains("@Command(name = \"auth\"")
            .contains("@Command(name = \"login\"")
            .contains("@Command(name = \"refresh\"")
            .contains("@Command(name = \"logout\"")
            .contains("@Command(name = \"me\"")
            .contains("urn:ietf:params:oauth:grant-type:device_code")
            .contains("authorization_pending")
            .contains("slow_down")
            .contains("access_denied")
            .contains("expired_token")
            .contains("verification_uri_complete")
            .contains("\"openid\"")
            .contains("device_authorization_endpoint")
            .contains("token_endpoint")
            .contains("userinfo_endpoint")
            .contains("revocation_endpoint")
            .contains("refresh_token")
            .contains("readTree(")
            .doesNotContain("readValue(");
    }

    @Test
    void emittedAuthCommandSourceCompilesAgainstGeneratedCliApi() {
        assertThatCode(() -> {
            OidcDeviceCodeCliExtension extension = new OidcDeviceCodeCliExtension();
            Path sourceRoot = Files.createDirectories(tempDir.resolve("src"));
            write(sourceRoot, "com/itsjool/aperture/cli/cmd/AuthCommand.java", extension.authCommandSource("apkc"));
            write(sourceRoot, "com/itsjool/aperture/cli/ApertureCli.java", """
                package com.itsjool.aperture.cli;
                public class ApertureCli {
                    public GlobalOptions global = new GlobalOptions();
                }
                """);
            write(sourceRoot, "com/itsjool/aperture/cli/GlobalOptions.java", """
                package com.itsjool.aperture.cli;
                public class GlobalOptions {
                    public String profile;
                    public String format = "table";
                }
                """);
            write(sourceRoot, "com/itsjool/aperture/cli/config/ProfileConfig.java", """
                package com.itsjool.aperture.cli.config;
                public class ProfileConfig {
                    public AuthConfig auth;
                    public static class AuthConfig {
                        public String accessToken;
                        public String refreshToken;
                    }
                }
                """);
            write(sourceRoot, "com/itsjool/aperture/cli/config/ConfigStore.java", """
                package com.itsjool.aperture.cli.config;
                import java.util.LinkedHashMap;
                import java.util.Map;
                public class ConfigStore {
                    public static Map<String, Object> load() { return new LinkedHashMap<>(); }
                    public static void save(Map<String, Object> config) {}
                    public static ProfileConfig activeProfile(Map<String, Object> config, String profileOverride) {
                        return new ProfileConfig();
                    }
                }
                """);
            write(sourceRoot, "com/itsjool/aperture/cli/cmd/OutputFormatter.java", """
                package com.itsjool.aperture.cli.cmd;
                import com.fasterxml.jackson.databind.JsonNode;
                public class OutputFormatter {
                    public static void print(JsonNode node, String format) {}
                }
                """);

            var sources = Files.walk(sourceRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .map(Path::toString)
                .toList();
            Path classes = Files.createDirectories(tempDir.resolve("classes"));
            var args = new java.util.ArrayList<String>();
            args.add("-d");
            args.add(classes.toString());
            args.add("-cp");
            args.add(System.getProperty("java.class.path"));
            args.addAll(sources);
            int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
            assertThat(result).isZero();
        }).doesNotThrowAnyException();
    }

    @Test
    void generatedAuthCommandRunsDeviceLoginRefreshMeAndLogoutAgainstOidcProvider() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger userinfoCalls = new AtomicInteger();
        AtomicInteger revocationCalls = new AtomicInteger();
        try (StubOidcProvider oidc = StubOidcProvider.start(tokenCalls, userinfoCalls, revocationCalls)) {
            GeneratedOidcCli cli = compileGeneratedCli();
            Map<String, Object> config = new LinkedHashMap<>();

            assertThat(cli.run(config, "auth", "login", "--issuer", oidc.issuer(), "--client-id", "apkc")).isZero();
            assertThat(cli.lastStdout())
                .as("printed verification URI and user code so the human can complete the flow")
                .contains("Open " + oidc.issuer() + "/verify?user_code=ABCD")
                .contains("enter code: ABCD");
            Map<String, Object> profile = defaultProfile(config);
            assertThat(profile).containsEntry("oidc", Map.of("issuer", oidc.issuer(), "clientId", "apkc"));
            assertThat(profile.get("auth")).isEqualTo(Map.of(
                "kind", "bearer",
                "accessToken", "access-1",
                "refreshToken", "refresh-1"));

            assertThat(cli.run(config, "auth", "refresh")).isZero();
            assertThat(defaultProfile(config).get("auth")).isEqualTo(Map.of(
                "kind", "bearer",
                "accessToken", "access-2",
                "refreshToken", "refresh-1"));

            assertThat(cli.run(config, "auth", "me")).isZero();
            assertThat(userinfoCalls).hasValue(1);

            assertThat(cli.run(config, "auth", "logout")).isZero();
            assertThat(defaultProfile(config)).doesNotContainKey("auth");
            assertThat(revocationCalls).hasValue(1);
        }
    }

    @Test
    void loginBacksOffAfterSlowDownBeforeEventuallySucceeding() throws Exception {
        try (ScriptedDeviceFlow flow = ScriptedDeviceFlow.start(1, 30,
                List.of(TokenOutcome.pending(), TokenOutcome.slowDown(), TokenOutcome.success()))) {
            GeneratedOidcCli cli = compileGeneratedCli();
            Map<String, Object> config = new LinkedHashMap<>();

            assertThat(cli.run(config, "auth", "login", "--issuer", flow.issuer(), "--client-id", "apkc")).isZero();

            assertThat(flow.tokenCallCount())
                .as("authorization_pending, then slow_down, then success")
                .isEqualTo(3);
            List<Instant> calls = flow.tokenCallTimestamps();
            Duration gapBeforeSlowDown = Duration.between(calls.get(0), calls.get(1));
            Duration gapAfterSlowDown = Duration.between(calls.get(1), calls.get(2));
            // Server advertises interval=1s; a slow_down response must add +5s to the
            // poll interval, so the gap after backing off is meaningfully larger than
            // the un-backed-off gap and at least ~6s on its own.
            assertThat(gapAfterSlowDown)
                .as("poll interval actually backs off by ~5s after slow_down")
                .isGreaterThan(gapBeforeSlowDown.plusSeconds(3))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(5));
        }
    }

    @Test
    void loginStopsCleanlyOnAccessDenied() throws Exception {
        try (ScriptedDeviceFlow flow = ScriptedDeviceFlow.start(1, 30, List.of(TokenOutcome.accessDenied()))) {
            Path classes = compileGeneratedCliClasses();

            ProcessResult result = runInSubprocess(classes,
                "auth", "login", "--issuer", flow.issuer(), "--client-id", "apkc");

            assertThat(result.exitCode()).as("non-zero exit on access_denied").isNotZero();
            assertThat(result.stderr()).as("clear error message").contains("access_denied");
            assertThat(flow.tokenCallCount()).isEqualTo(1);
        }
    }

    @Test
    void loginHardStopsWhenDeviceCodeExpires() throws Exception {
        // interval=1s, expires_in=1s: the very first poll response (still pending)
        // arrives after the deadline has already passed, so the loop must give up
        // rather than poll forever.
        try (ScriptedDeviceFlow flow = ScriptedDeviceFlow.start(1, 1, List.of(TokenOutcome.pending()))) {
            Path classes = compileGeneratedCliClasses();

            ProcessResult result = runInSubprocess(classes,
                "auth", "login", "--issuer", flow.issuer(), "--client-id", "apkc");

            assertThat(result.exitCode()).as("non-zero exit when device code expires").isNotZero();
            assertThat(result.stderr()).as("clear error message").contains("expired_token");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> defaultProfile(Map<String, Object> config) {
        return (Map<String, Object>) ((Map<String, Object>) config.get("profiles")).get("default");
    }

    private GeneratedOidcCli compileGeneratedCli() throws Exception {
        OidcDeviceCodeCliExtension extension = new OidcDeviceCodeCliExtension();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("runtime-src"));
        write(sourceRoot, "com/itsjool/aperture/cli/cmd/AuthCommand.java", extension.authCommandSource("apkc"));
        writeRuntimeStubSources(sourceRoot);
        Path classes = compile(sourceRoot, tempDir.resolve("runtime-classes"));
        URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL() }, getClass().getClassLoader());
        Class<?> runner = Class.forName("com.itsjool.aperture.cli.TestCliRunner", true, loader);
        return new GeneratedOidcCli(runner.getMethod("run", Map.class, String[].class));
    }

    /** Compiles the same generated CLI sources as {@link #compileGeneratedCli()}, but returns
     *  the classes directory for a forked-subprocess run instead of an in-process class loader.
     *  Needed for auth paths where the emitted source calls {@code System.exit(...)} on
     *  failure (access_denied / expired_token): invoking those in-process via reflection would
     *  terminate this test JVM. */
    private Path compileGeneratedCliClasses() throws Exception {
        OidcDeviceCodeCliExtension extension = new OidcDeviceCodeCliExtension();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("subprocess-src"));
        write(sourceRoot, "com/itsjool/aperture/cli/cmd/AuthCommand.java", extension.authCommandSource("apkc"));
        writeRuntimeStubSources(sourceRoot);
        return compile(sourceRoot, tempDir.resolve("subprocess-classes"));
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}

    private static ProcessResult runInSubprocess(Path classes, String... args) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = classes + File.pathSeparator + System.getProperty("java.class.path");
        var command = new java.util.ArrayList<String>();
        command.add(javaBin);
        command.add("-cp");
        command.add(classpath);
        command.add("com.itsjool.aperture.cli.TestCliMain");
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            throw new IllegalStateException(
                "Subprocess timed out. stdout=" + stdout + " stderr=" + stderr);
        }
        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    private static final class GeneratedOidcCli {
        private final Method runMethod;
        private String lastStdout = "";
        private String lastStderr = "";

        private GeneratedOidcCli(Method runMethod) {
            this.runMethod = runMethod;
        }

        int run(Map<String, Object> config, String... args) throws Exception {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
            try {
                System.setOut(new PrintStream(outBuffer, true, StandardCharsets.UTF_8));
                System.setErr(new PrintStream(errBuffer, true, StandardCharsets.UTF_8));
                return (Integer) runMethod.invoke(null, config, args);
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
                lastStdout = outBuffer.toString(StandardCharsets.UTF_8);
                lastStderr = errBuffer.toString(StandardCharsets.UTF_8);
            }
        }

        String lastStdout() {
            return lastStdout;
        }

        String lastStderr() {
            return lastStderr;
        }
    }

    private static void writeRuntimeStubSources(Path sourceRoot) throws Exception {
        write(sourceRoot, "com/itsjool/aperture/cli/TestCliRunner.java", """
            package com.itsjool.aperture.cli;
            import com.itsjool.aperture.cli.config.ConfigStore;
            import java.util.Map;
            import picocli.CommandLine;
            public class TestCliRunner {
                public static int run(Map<String, Object> config, String... args) {
                    ConfigStore.config = config;
                    return new CommandLine(new ApertureCli()).execute(args);
                }
            }
            """);
        // Entry point for subprocess-based tests: paths where the emitted AuthCommand calls
        // System.exit(...) directly (access_denied / expired_token) must run in a forked JVM,
        // since exercising them in-process would kill this test's own JVM.
        write(sourceRoot, "com/itsjool/aperture/cli/TestCliMain.java", """
            package com.itsjool.aperture.cli;
            import picocli.CommandLine;
            public class TestCliMain {
                public static void main(String[] args) {
                    int code = new CommandLine(new ApertureCli()).execute(args);
                    System.exit(code);
                }
            }
            """);
        write(sourceRoot, "com/itsjool/aperture/cli/ApertureCli.java", """
            package com.itsjool.aperture.cli;
            import com.itsjool.aperture.cli.cmd.AuthCommand;
            import picocli.CommandLine.Command;
            import picocli.CommandLine.Mixin;
            @Command(name = "apkc", subcommands = { AuthCommand.class })
            public class ApertureCli implements Runnable {
                @Mixin public GlobalOptions global = new GlobalOptions();
                public void run() {}
            }
            """);
        write(sourceRoot, "com/itsjool/aperture/cli/GlobalOptions.java", """
            package com.itsjool.aperture.cli;
            import picocli.CommandLine.Option;
            public class GlobalOptions {
                @Option(names = "--profile", scope = picocli.CommandLine.ScopeType.INHERIT)
                public String profile;
                @Option(names = "--format", defaultValue = "json", scope = picocli.CommandLine.ScopeType.INHERIT)
                public String format = "json";
            }
            """);
        write(sourceRoot, "com/itsjool/aperture/cli/config/ProfileConfig.java", """
            package com.itsjool.aperture.cli.config;
            public class ProfileConfig {
                public AuthConfig auth;
                public static class AuthConfig {
                    public String kind;
                    public String accessToken;
                    public String refreshToken;
                }
            }
            """);
        write(sourceRoot, "com/itsjool/aperture/cli/config/ConfigStore.java", """
            package com.itsjool.aperture.cli.config;
            import java.util.LinkedHashMap;
            import java.util.Map;
            public class ConfigStore {
                public static Map<String, Object> config = new LinkedHashMap<>();
                public static Map<String, Object> load() { return config; }
                public static void save(Map<String, Object> saved) { config = saved; }
                @SuppressWarnings("unchecked")
                public static ProfileConfig activeProfile(Map<String, Object> config, String profileOverride) {
                    String active = profileOverride != null ? profileOverride : (String) config.getOrDefault("activeProfile", "default");
                    Map<String, Object> profiles = (Map<String, Object>) config.getOrDefault("profiles", new LinkedHashMap<>());
                    Map<String, Object> raw = (Map<String, Object>) profiles.getOrDefault(active, new LinkedHashMap<>());
                    Map<String, Object> auth = (Map<String, Object>) raw.getOrDefault("auth", new LinkedHashMap<>());
                    ProfileConfig profile = new ProfileConfig();
                    profile.auth = new ProfileConfig.AuthConfig();
                    profile.auth.kind = (String) auth.get("kind");
                    profile.auth.accessToken = (String) auth.get("accessToken");
                    profile.auth.refreshToken = (String) auth.get("refreshToken");
                    return profile;
                }
            }
            """);
        write(sourceRoot, "com/itsjool/aperture/cli/cmd/OutputFormatter.java", """
            package com.itsjool.aperture.cli.cmd;
            import com.fasterxml.jackson.databind.JsonNode;
            public class OutputFormatter {
                public static void print(JsonNode node, String format) {}
            }
            """);
    }

    private Path compile(Path sourceRoot, Path classes) throws Exception {
        var sources = Files.walk(sourceRoot)
            .filter(path -> path.toString().endsWith(".java"))
            .map(Path::toString)
            .toList();
        Files.createDirectories(classes);
        var args = new java.util.ArrayList<String>();
        args.add("-d");
        args.add(classes.toString());
        args.add("-cp");
        args.add(System.getProperty("java.class.path"));
        args.addAll(sources);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
        assertThat(result).isZero();
        return classes;
    }

    private static final class StubOidcProvider implements AutoCloseable {
        private final HttpServer server;

        private StubOidcProvider(HttpServer server) {
            this.server = server;
        }

        static StubOidcProvider start(AtomicInteger tokenCalls, AtomicInteger userinfoCalls,
                                      AtomicInteger revocationCalls) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubOidcProvider provider = new StubOidcProvider(server);
            server.createContext("/.well-known/openid-configuration", exchange -> provider.json(exchange, 200, Map.of(
                "device_authorization_endpoint", provider.issuer() + "/device",
                "token_endpoint", provider.issuer() + "/token",
                "userinfo_endpoint", provider.issuer() + "/userinfo",
                "revocation_endpoint", provider.issuer() + "/revoke")));
            server.createContext("/device", exchange -> provider.json(exchange, 200, Map.of(
                "verification_uri_complete", provider.issuer() + "/verify?user_code=ABCD",
                "verification_uri", provider.issuer() + "/verify",
                "user_code", "ABCD",
                "device_code", "device-1",
                "interval", 1,
                "expires_in", 30)));
            server.createContext("/token", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (body.contains("grant_type=refresh_token")) {
                    tokenCalls.incrementAndGet();
                    provider.json(exchange, 200, Map.of("access_token", "access-2", "expires_in", 600));
                    return;
                }
                int call = tokenCalls.incrementAndGet();
                if (call == 1) {
                    provider.json(exchange, 400, Map.of("error", "authorization_pending"));
                    return;
                }
                provider.json(exchange, 200, Map.of(
                    "access_token", "access-1",
                    "refresh_token", "refresh-1",
                    "expires_in", 600));
            });
            server.createContext("/userinfo", exchange -> {
                userinfoCalls.incrementAndGet();
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer access-2");
                provider.json(exchange, 200, Map.of("sub", "user-1"));
            });
            server.createContext("/revoke", exchange -> {
                revocationCalls.incrementAndGet();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(body).contains("token=refresh-1");
                provider.json(exchange, 200, Map.of("revoked", true));
            });
            server.start();
            return provider;
        }

        String issuer() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void json(HttpExchange exchange, int status, Map<String, Object> body) throws java.io.IOException {
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().put("Content-Type", List.of("application/json"));
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** A canned {@code /token} endpoint response used to script poll sequences
     *  (authorization_pending -> slow_down -> success, access_denied, etc). */
    private record TokenOutcome(int status, Map<String, Object> body) {
        static TokenOutcome pending() {
            return new TokenOutcome(400, Map.of("error", "authorization_pending"));
        }

        static TokenOutcome slowDown() {
            return new TokenOutcome(400, Map.of("error", "slow_down"));
        }

        static TokenOutcome accessDenied() {
            return new TokenOutcome(400, Map.of("error", "access_denied"));
        }

        static TokenOutcome success() {
            return new TokenOutcome(200, Map.of(
                "access_token", "access-1",
                "refresh_token", "refresh-1",
                "expires_in", 600));
        }
    }

    /** Stub discovery + device + token endpoints driven by a scripted list of
     *  {@link TokenOutcome}s (the last entry repeats once exhausted), recording the wall-clock
     *  timestamp of each {@code /token} poll so tests can assert pacing/backoff behavior. */
    private static final class ScriptedDeviceFlow implements AutoCloseable {
        private final HttpServer server;
        private final List<Instant> tokenCallTimestamps = new CopyOnWriteArrayList<>();
        private final AtomicInteger tokenCallIndex = new AtomicInteger();

        private ScriptedDeviceFlow(HttpServer server) {
            this.server = server;
        }

        static ScriptedDeviceFlow start(long intervalSeconds, long expiresInSeconds,
                                         List<TokenOutcome> outcomes) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ScriptedDeviceFlow flow = new ScriptedDeviceFlow(server);
            server.createContext("/.well-known/openid-configuration", exchange -> flow.json(exchange, 200, Map.of(
                "device_authorization_endpoint", flow.issuer() + "/device",
                "token_endpoint", flow.issuer() + "/token",
                "userinfo_endpoint", flow.issuer() + "/userinfo",
                "revocation_endpoint", flow.issuer() + "/revoke")));
            server.createContext("/device", exchange -> flow.json(exchange, 200, Map.of(
                "verification_uri_complete", flow.issuer() + "/verify?user_code=ABCD",
                "verification_uri", flow.issuer() + "/verify",
                "user_code", "ABCD",
                "device_code", "device-1",
                "interval", intervalSeconds,
                "expires_in", expiresInSeconds)));
            server.createContext("/token", exchange -> {
                flow.tokenCallTimestamps.add(Instant.now());
                int index = Math.min(flow.tokenCallIndex.getAndIncrement(), outcomes.size() - 1);
                TokenOutcome outcome = outcomes.get(index);
                flow.json(exchange, outcome.status(), outcome.body());
            });
            server.start();
            return flow;
        }

        String issuer() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<Instant> tokenCallTimestamps() {
            return tokenCallTimestamps;
        }

        int tokenCallCount() {
            return tokenCallTimestamps.size();
        }

        private void json(HttpExchange exchange, int status, Map<String, Object> body) throws java.io.IOException {
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().put("Content-Type", List.of("application/json"));
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static void write(Path sourceRoot, String relativePath, String contents) throws Exception {
        Path target = sourceRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, contents);
    }
}
