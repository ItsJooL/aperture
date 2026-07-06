package com.itsjool.aperture.cli.generator;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.cli.spi.AuthPaths;
import com.palantir.javapoet.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static source templates for the generated CLI project.
 * All generated classes use the package {@code com.itsjool.aperture.cli}.
 */
final class CliTemplates {

    private CliTemplates() {}

    private static final String CLI_PKG = "com.itsjool.aperture.cli";
    private static final String CMD_PKG = CLI_PKG + ".cmd";

    private static final ClassName APERTURE_CLI     = ClassName.get(CLI_PKG, "ApertureCli");
    private static final ClassName GLOBAL_OPTIONS   = ClassName.get(CLI_PKG, "GlobalOptions");
    private static final ClassName VERSION_PROVIDER = ClassName.get(CLI_PKG, "VersionProvider");
    private static final ClassName CONFIG_STORE     = ClassName.get(CLI_PKG + ".config", "ConfigStore");
    private static final ClassName API_CLIENT       = ClassName.get(CLI_PKG + ".http", "ApiClient");
    private static final ClassName PROFILE_CONFIG   = ClassName.get(CLI_PKG + ".config", "ProfileConfig");
    private static final ClassName OBJECT_MAPPER    = ClassName.get("com.fasterxml.jackson.databind", "ObjectMapper");
    private static final ClassName YAML_FACTORY     = ClassName.get("com.fasterxml.jackson.dataformat.yaml", "YAMLFactory");
    private static final ClassName CMD_LINE         = ClassName.get("picocli", "CommandLine");
    private static final ClassName CMD_ANN          = ClassName.get("picocli", "CommandLine", "Command");
    private static final ClassName OPTION_ANN       = ClassName.get("picocli", "CommandLine", "Option");
    private static final ClassName PARENT_CMD       = ClassName.get("picocli", "CommandLine", "ParentCommand");
    private static final ClassName MIXIN            = ClassName.get("picocli", "CommandLine", "Mixin");
    private static final ClassName LINKED_MAP       = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName PATH_CN          = ClassName.get("java.nio.file", "Path");
    private static final ClassName COLLECTIONS      = ClassName.get("java.util", "Collections");
    private static final ClassName PATTERN_CN       = ClassName.get("java.util.regex", "Pattern");
    // Inner record types in generated class — empty package → no import → simple name
    private static final ClassName FIELD_SPEC_CN    = ClassName.get("", "FieldSpec");
    private static final ClassName ENTITY_SPEC_CN   = ClassName.get("", "EntitySpec");
    // The shared generated FileOps class (registry + relationship/natural-key lookup helpers)
    // reused by ApplyCommand and the -f paths of Create/Update/DeleteCommand.
    private static final ClassName FILE_OPS_CN      = ClassName.get(CMD_PKG, "FileOps");

    static String pom(String binaryName) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.itsjool</groupId>
              <artifactId>aperture-cli</artifactId>
              <version>0.0.1-SNAPSHOT</version>
              <packaging>jar</packaging>

              <properties>
                <maven.compiler.release>21</maven.compiler.release>
                <picocli.version>4.7.7</picocli.version>
                <jackson.version>2.19.1</jackson.version>
                <native.maven.plugin.version>1.1.3</native.maven.plugin.version>
              </properties>

              <dependencies>
                <dependency>
                  <groupId>info.picocli</groupId>
                  <artifactId>picocli</artifactId>
                  <version>${picocli.version}</version>
                </dependency>
                <dependency>
                  <groupId>com.fasterxml.jackson.core</groupId>
                  <artifactId>jackson-databind</artifactId>
                  <version>${jackson.version}</version>
                </dependency>
                <dependency>
                  <groupId>com.fasterxml.jackson.datatype</groupId>
                  <artifactId>jackson-datatype-jsr310</artifactId>
                  <version>${jackson.version}</version>
                </dependency>
                <dependency>
                  <groupId>com.fasterxml.jackson.dataformat</groupId>
                  <artifactId>jackson-dataformat-yaml</artifactId>
                  <version>${jackson.version}</version>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.12.2</version>
                  <scope>test</scope>
                </dependency>
                <dependency>
                  <groupId>org.assertj</groupId>
                  <artifactId>assertj-core</artifactId>
                  <version>3.27.3</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>

              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.14.0</version>
                    <configuration>
                      <annotationProcessorPaths>
                        <path>
                          <groupId>info.picocli</groupId>
                          <artifactId>picocli-codegen</artifactId>
                          <version>${picocli.version}</version>
                        </path>
                      </annotationProcessorPaths>
                      <compilerArgs>
                        <arg>-Aproject=${project.groupId}/${project.artifactId}</arg>
                      </compilerArgs>
                    </configuration>
                  </plugin>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-jar-plugin</artifactId>
                    <version>3.4.2</version>
                    <configuration>
                      <archive>
                        <manifest>
                          <mainClass>com.itsjool.aperture.cli.ApertureCli</mainClass>
                        </manifest>
                      </archive>
                    </configuration>
                  </plugin>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-shade-plugin</artifactId>
                    <version>3.6.0</version>
                    <executions>
                      <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                          <createDependencyReducedPom>false</createDependencyReducedPom>
                          <filters>
                            <filter>
                              <artifact>*:*</artifact>
                              <excludes>
                                <exclude>META-INF/*.SF</exclude>
                                <exclude>META-INF/*.DSA</exclude>
                                <exclude>META-INF/*.RSA</exclude>
                              </excludes>
                            </filter>
                          </filters>
                          <transformers>
                            <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                              <mainClass>com.itsjool.aperture.cli.ApertureCli</mainClass>
                            </transformer>
                          </transformers>
                        </configuration>
                      </execution>
                    </executions>
                  </plugin>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.3</version>
                  </plugin>
                </plugins>
              </build>

              <profiles>
                <profile>
                  <id>native</id>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.graalvm.buildtools</groupId>
                        <artifactId>native-maven-plugin</artifactId>
                        <version>${native.maven.plugin.version}</version>
                        <extensions>true</extensions>
                        <executions>
                          <execution>
                            <id>build-native</id>
                            <goals><goal>compile-no-fork</goal></goals>
                            <phase>package</phase>
                          </execution>
                        </executions>
                        <configuration>
                          <imageName>@BINARY_NAME@</imageName>
                          <mainClass>com.itsjool.aperture.cli.ApertureCli</mainClass>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </profile>
              </profiles>
            </project>
            """.replace("@BINARY_NAME@", binaryName);
    }

    static String mainCommandWithEntityCommands(
            String binaryName, String authCommandClassName, List<String> contributedCommandClassNames) {
        ClassName versionCmd    = ClassName.get(CMD_PKG, "VersionCommand");
        ClassName authCmd       = ClassName.get(CMD_PKG, authCommandClassName);
        ClassName configCmd     = ClassName.get(CMD_PKG, "ConfigCommand");
        ClassName getCmd        = ClassName.get(CMD_PKG, "GetCommand");
        ClassName createCmd     = ClassName.get(CMD_PKG, "CreateCommand");
        ClassName updateCmd     = ClassName.get(CMD_PKG, "UpdateCommand");
        ClassName deleteCmd     = ClassName.get(CMD_PKG, "DeleteCommand");
        ClassName applyCmd      = ClassName.get(CMD_PKG, "ApplyCommand");
        ClassName helpCmd       = ClassName.get("picocli", "CommandLine", "HelpCommand");
        ClassName completionCmd = ClassName.get("picocli", "AutoComplete", "GenerateCompletion");

        StringBuilder subFmt = new StringBuilder("{\n");
        List<Object> subArgs = new ArrayList<>();
        // Fixed kubectl-style verb set (no per-entity top-level command groups — clean break
        // from the noun-first surface).
        for (ClassName cn : List.of(versionCmd, authCmd, configCmd, getCmd, createCmd, updateCmd, deleteCmd, applyCmd)) {
            subFmt.append("    $T.class,\n");
            subArgs.add(cn);
        }
        // Contributed commands (from CliCommandContribution extensions) come after the built-in
        // commands and before the built-in completion/help commands.
        for (String contributedClassName : contributedCommandClassNames) {
            subFmt.append("    $T.class,\n");
            subArgs.add(ClassName.get(CMD_PKG, contributedClassName));
        }
        subFmt.append("    $T.class,\n");
        subArgs.add(completionCmd);
        subFmt.append("    $T.class\n}");
        subArgs.add(helpCmd);

        AnnotationSpec cmdAnnotation = AnnotationSpec.builder(CMD_ANN)
            .addMember("name", "$S", binaryName)
            .addMember("mixinStandardHelpOptions", "$L", true)
            .addMember("versionProvider", "$T.class", VERSION_PROVIDER)
            .addMember("subcommands", subFmt.toString(), subArgs.toArray())
            .addMember("description", "$S", "Aperture API CLI")
            .build();

        TypeSpec mainClass = TypeSpec.classBuilder("ApertureCli")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(Runnable.class)
            .addAnnotation(cmdAnnotation)
            .addField(FieldSpec.builder(GLOBAL_OPTIONS, "global")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(MIXIN)
                .build())
            .addMethod(MethodSpec.methodBuilder("main")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(void.class)
                .addParameter(String[].class, "args")
                .addStatement("int exit = new $T(new ApertureCli()).execute(args)", CMD_LINE)
                .addStatement("$T.exit(exit)", System.class)
                .build())
            .addMethod(MethodSpec.methodBuilder("run")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addStatement("$T.usage(this, $T.out)", CMD_LINE, System.class)
                .build())
            .build();

        return JavaFile.builder(CLI_PKG, mainClass).build().toString();
    }

    static String globalOptions(String defaultApiVersion) {
        String defaultVersionLiteral = defaultApiVersion != null ? "\"" + defaultApiVersion + "\"" : "null";
        String apiVersionDescription = defaultApiVersion != null
            ? "API version to use (default: " + defaultApiVersion + ")"
            : "API version to use";
        return """
            package com.itsjool.aperture.cli;

            import picocli.CommandLine.Option;

            public class GlobalOptions {
                /** The manifest's ACTIVE API version at generation time, used when neither
                 *  --api-version nor a profile's pinned version is set. Null if the manifest
                 *  declares no ApiVersionConfig (unversioned API). */
                public static final String DEFAULT_API_VERSION = %s;

                @Option(names = {"--server"}, description = "Server base URL", scope = picocli.CommandLine.ScopeType.INHERIT)
                public String server;

                @Option(names = {"--profile"}, description = "Configuration profile", scope = picocli.CommandLine.ScopeType.INHERIT)
                public String profile;

                @Option(names = {"--tenant"}, description = "Tenant context (SuperAdmin only)", scope = picocli.CommandLine.ScopeType.INHERIT)
                public String tenant;

                @Option(names = {"--scope"}, description = "Scope context as field=value (repeatable; e.g. --scope project=<id>)", scope = picocli.CommandLine.ScopeType.INHERIT)
                public java.util.List<String> scope = new java.util.ArrayList<>();

                @Option(names = {"--api-version"}, description = "%s", scope = picocli.CommandLine.ScopeType.INHERIT)
                public String apiVersion;

                @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table", scope = picocli.CommandLine.ScopeType.INHERIT)
                public String format;

                @Option(names = {"--verbose", "-v"}, description = "Verbose output", scope = picocli.CommandLine.ScopeType.INHERIT)
                public boolean verbose;
            }
            """.formatted(defaultVersionLiteral, apiVersionDescription);
    }

    static String profileConfig() {
        return """
            package com.itsjool.aperture.cli.config;

            import java.util.LinkedHashMap;
            import java.util.Map;

            public class ProfileConfig {
                public String server;
                public String apiVersion;
                public String tenant;
                /** Scope context (kubectl-namespace-style), keyed by lowercased scopedBy field
                 *  name (e.g. "project") to its configured value. Populated from the profile's
                 *  persisted {@code scopes} map and layered with {@code --scope} overrides in
                 *  {@code applyParentOverrides}. Never null. */
                public Map<String, String> scopes = new LinkedHashMap<>();
                public String username;
                public AuthConfig auth;

                public static class AuthConfig {
                    public String kind;
                    public String accessToken;
                    public String refreshToken;
                    public String apiKey;
                }
            }
            """;
    }

    static String configStore(String binaryName) {
        String configDir = binaryName.toLowerCase();
        String configDirTitle = Character.toUpperCase(binaryName.charAt(0)) + binaryName.substring(1).toLowerCase();
        String envPrefix = binaryName.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        return """
            package com.itsjool.aperture.cli.config;

            import com.fasterxml.jackson.databind.ObjectMapper;

            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.attribute.PosixFilePermissions;
            import java.util.LinkedHashMap;
            import java.util.Map;

            public class ConfigStore {
                private static final ObjectMapper MAPPER = new ObjectMapper();

                public static Path configFile() {
                    String explicit = System.getenv("@ENVPREFIX@_CONFIG");
                    if (explicit != null && !explicit.isBlank()) {
                        return Path.of(explicit);
                    }
                    String os = System.getProperty("os.name", "").toLowerCase();
                    if (os.contains("mac")) {
                        return Path.of(System.getProperty("user.home"),
                            "Library", "Application Support", "@TITLE@", "config.json");
                    } else if (os.contains("win")) {
                        return Path.of(System.getenv().getOrDefault("APPDATA",
                            System.getProperty("user.home")), "@TITLE@", "config.json");
                    } else {
                        String xdg = System.getenv("XDG_CONFIG_HOME");
                        return Path.of(xdg != null ? xdg : System.getProperty("user.home") + "/.config",
                            "@DIR@", "config.json");
                    }
                }

                @SuppressWarnings("unchecked")
                public static Map<String, Object> load() throws Exception {
                    Path f = configFile();
                    if (!Files.exists(f)) return new LinkedHashMap<>();
                    return MAPPER.readValue(f.toFile(), LinkedHashMap.class);
                }

                public static void save(Map<String, Object> config) throws Exception {
                    Path f = configFile();
                    Files.createDirectories(f.getParent());
                    try {
                        Files.setPosixFilePermissions(f.getParent(), PosixFilePermissions.fromString("rwx------"));
                    } catch (UnsupportedOperationException ignored) {}
                    String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config);
                    Path tmp = Files.createTempFile(f.getParent(), ".config-", ".tmp");
                    try {
                        try {
                            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
                        } catch (UnsupportedOperationException ignored) {}
                        Files.writeString(tmp, json);
                        try {
                            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        try {
                            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
                        } catch (UnsupportedOperationException ignored) {}
                    } catch (Exception e) {
                        Files.deleteIfExists(tmp);
                        throw e;
                    }
                }

                public static ProfileConfig activeProfile(Map<String, Object> config) {
                    return activeProfile(config, null);
                }

                @SuppressWarnings("unchecked")
                public static ProfileConfig activeProfile(Map<String, Object> config, String profileOverride) {
                    String active = profileOverride != null ? profileOverride : (String) config.getOrDefault("activeProfile", "default");
                    String serverEnv = System.getenv("@ENVPREFIX@_SERVER");
                    String tokenEnv = System.getenv("@ENVPREFIX@_TOKEN");
                    String apiKeyEnv = System.getenv("@ENVPREFIX@_API_KEY");
                    String tenantEnv = System.getenv("@ENVPREFIX@_TENANT");

                    Map<String, Object> profiles = (Map<String, Object>) config.getOrDefault("profiles", new LinkedHashMap<>());
                    Map<String, Object> raw = (Map<String, Object>) profiles.getOrDefault(active, new LinkedHashMap<>());

                    ProfileConfig p = new ProfileConfig();
                    p.server = serverEnv != null ? serverEnv : (String) raw.get("server");
                    p.apiVersion = (String) raw.getOrDefault("apiVersion", null);
                    p.tenant = tenantEnv != null ? tenantEnv : (String) raw.get("tenant");
                    Map<String, Object> rawScopes = (Map<String, Object>) raw.getOrDefault("scopes", new LinkedHashMap<>());
                    Map<String, String> scopes = new LinkedHashMap<>();
                    rawScopes.forEach((k, v) -> {
                        if (v != null) scopes.put(k.toLowerCase(), v.toString());
                    });
                    p.scopes = scopes;
                    p.username = (String) raw.get("username");

                    Map<String, Object> auth = (Map<String, Object>) raw.getOrDefault("auth", new LinkedHashMap<>());
                    p.auth = new ProfileConfig.AuthConfig();
                    p.auth.kind = (String) auth.getOrDefault("kind", "bearer");
                    p.auth.accessToken = tokenEnv != null ? tokenEnv : (String) auth.get("accessToken");
                    p.auth.refreshToken = (String) auth.get("refreshToken");
                    p.auth.apiKey = apiKeyEnv != null ? apiKeyEnv : (String) auth.get("apiKey");
                    return p;
                }
            }
            """
            .replace("@TITLE@", configDirTitle)
            .replace("@DIR@", configDir)
            .replace("@ENVPREFIX@", envPrefix);
    }

    static String apiClient() {
        return """
            package com.itsjool.aperture.cli.http;

            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
            import com.itsjool.aperture.cli.config.ProfileConfig;

            import java.net.URI;
            import java.net.http.HttpClient;
            import java.net.http.HttpRequest;
            import java.net.http.HttpResponse;
            import java.util.Map;

            public class ApiClient {
                private static final String JSON_API = "application/vnd.api+json";
                private static final ObjectMapper MAPPER = new ObjectMapper()
                    .registerModule(new JavaTimeModule());
                private static final HttpClient HTTP = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

                private final String baseUrl;
                private final ProfileConfig profile;
                private final boolean verbose;

                public ApiClient(String baseUrl, ProfileConfig profile, boolean verbose) {
                    if (baseUrl == null || baseUrl.isBlank())
                        throw new IllegalArgumentException("Server URL not configured. Use --server or set a profile with a server URL.");
                    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
                    this.profile = profile;
                    this.verbose = verbose;
                }

                public JsonNode get(String path) throws Exception {
                    HttpRequest.Builder req = request(path).GET();
                    return execute(req.build(), path);
                }

                public JsonNode post(String path, Map<String, Object> body) throws Exception {
                    String json = MAPPER.writeValueAsString(body);
                    HttpRequest.Builder req = request(path)
                        .header("Content-Type", JSON_API)
                        .POST(HttpRequest.BodyPublishers.ofString(json));
                    return execute(req.build(), path);
                }

                public JsonNode patch(String path, Map<String, Object> body, String etag) throws Exception {
                    String json = MAPPER.writeValueAsString(body);
                    HttpRequest.Builder req = request(path)
                        .header("Content-Type", JSON_API)
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(json));
                    if (etag != null) req.header("If-Match", etag);
                    return execute(req.build(), path);
                }

                public void delete(String path, String etag) throws Exception {
                    HttpRequest.Builder req = request(path);
                    if (etag != null) req.header("If-Match", etag);
                    req.DELETE();
                    execute(req.build(), path);
                }

                private HttpRequest.Builder request(String path) {
                    HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .header("Accept", JSON_API);
                    if (profile != null && profile.auth != null) {
                        if ("bearer".equals(profile.auth.kind) && profile.auth.accessToken != null) {
                            b.header("Authorization", "Bearer " + profile.auth.accessToken);
                        } else if ("api-key".equals(profile.auth.kind) && profile.auth.apiKey != null) {
                            b.header("X-API-Key", profile.auth.apiKey);
                        }
                        if (profile.tenant != null) {
                            b.header("X-Aperture-Tenant-Context", profile.tenant);
                        }
                    }
                    // Scope context is independent of auth (unlike tenant above, which is nested in the
                    // auth check) — a configured scope should always be sent, and doesn't require a
                    // particular auth kind. Keys are already lowercase-canonicalized by ConfigStore /
                    // applyParentOverrides; sent as-is in the header suffix (the server lowercases anyway).
                    if (profile != null && profile.scopes != null) {
                        profile.scopes.forEach((field, value) -> {
                            if (value != null && !value.isBlank()) {
                                b.header("X-Aperture-Scope-" + field, value);
                            }
                        });
                    }
                    return b;
                }

                private JsonNode execute(HttpRequest req, String path) throws Exception {
                    if (verbose) {
                        System.err.println("> " + req.method() + " " + req.uri());
                    }
                    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                    if (verbose) {
                        System.err.println("< " + resp.statusCode());
                        resp.headers().map().forEach((k, vs) -> vs.forEach(v -> System.err.println("< " + k + ": " + v)));
                        System.err.println(resp.body());
                    }
                    if (resp.statusCode() >= 400) {
                        handleError(resp);
                    }
                    String body = resp.body();
                    if (body == null || body.isBlank()) return MAPPER.nullNode();
                    return MAPPER.readTree(body);
                }

                private void handleError(HttpResponse<String> resp) {
                    String body = resp.body();
                    if (resp.statusCode() == 401) {
                        System.err.println("Error: session expired or not authenticated. Run: auth refresh  (or auth login)");
                        throw new RuntimeException("HTTP 401 Unauthorized");
                    }
                    // Always fold the response body into the exception message (not just stderr) —
                    // callers like apply's conflict-detection need to inspect it (e.g. "already
                    // exists" / "Unique constraint"), and the status code alone isn't reliable:
                    // this server maps unique-constraint violations to 423, not 409.
                    String suffix = (body != null && !body.isBlank()) ? ": " + body : "";
                    if (body != null && !body.isBlank()) {
                        try {
                            JsonNode node = MAPPER.readTree(body);
                            if (node.has("errors")) {
                                node.get("errors").forEach(e ->
                                    System.err.println("Error: " + e.path("detail").asText(e.path("title").asText("Unknown error"))));
                            } else if (node.has("message")) {
                                System.err.println("Error: " + node.get("message").asText());
                            } else if (node.has("detail")) {
                                String title = node.path("title").asText("");
                                System.err.println("Error: " + (title.isBlank() ? "" : title + " — ") + node.get("detail").asText());
                            } else {
                                System.err.println("HTTP " + resp.statusCode() + ": " + body);
                            }
                        } catch (Exception ignored) {
                            System.err.println("HTTP " + resp.statusCode() + ": " + body);
                        }
                    } else {
                        System.err.println("HTTP " + resp.statusCode());
                    }
                    throw new RuntimeException("HTTP " + resp.statusCode() + suffix);
                }
            }
            """;
    }

    static String configCommand(String binaryName) {
        return """
            package com.itsjool.aperture.cli.cmd;

            import com.itsjool.aperture.cli.ApertureCli;
            import com.itsjool.aperture.cli.config.ConfigStore;
            import picocli.CommandLine;
            import picocli.CommandLine.Command;
            import picocli.CommandLine.Parameters;
            import picocli.CommandLine.Option;

            import java.util.LinkedHashMap;
            import java.util.Map;

            @Command(
                name = "config",
                description = "Manage CLI configuration and profiles",
                mixinStandardHelpOptions = true,
                subcommands = {
                    ConfigCommand.ShowCommand.class,
                    ConfigCommand.SetServerCommand.class,
                    ConfigCommand.UseProfileCommand.class,
                    ConfigCommand.ListProfilesCommand.class,
                    ConfigCommand.SetApiVersionCommand.class,
                    ConfigCommand.SetApiKeyCommand.class,
                    ConfigCommand.SetTenantCommand.class,
                    ConfigCommand.SetScopeCommand.class,
                    ConfigCommand.UnsetScopeCommand.class,
                    ConfigCommand.DeleteProfileCommand.class
                }
            )
            public class ConfigCommand implements Runnable {
                @CommandLine.ParentCommand ApertureCli root;
                @Override public void run() { CommandLine.usage(this, System.out); }

                /** Load config, resolve the active profile (option override or config default),
                 *  apply {@code mutator} to its profile map, save, and return the active profile
                 *  name — the shared body of set-server / set-api-version / set-tenant / set-api-key. */
                private static String applyProfileMutation(ApertureCli root, java.util.function.Consumer<Map<String, Object>> mutator) throws Exception {
                    var config = ConfigStore.load();
                    String active = root.global.profile != null ? root.global.profile
                        : (String) config.getOrDefault("activeProfile", "default");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> profiles = (Map<String, Object>) config.computeIfAbsent("profiles", k -> new LinkedHashMap<>());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> profileMap = (Map<String, Object>) profiles.computeIfAbsent(active, k -> new LinkedHashMap<>());
                    mutator.accept(profileMap);
                    ConfigStore.save(config);
                    return active;
                }

                @Command(name = "show", description = "Show current configuration and auth status")
                static class ShowCommand implements Runnable {
                    @CommandLine.ParentCommand ConfigCommand parent;
                    @Override public void run() {
                        try {
                            var config = ConfigStore.load();
                            String active = (String) config.getOrDefault("activeProfile", "default");
                            var profile = ConfigStore.activeProfile(config, parent.root.global.profile);
                            System.out.println("Config file:     " + ConfigStore.configFile());
                            System.out.println("Active profile:  " + active);
                            System.out.println("Server:          " + (profile.server != null ? profile.server : "(not set — use: @BINARY@ config set-server <url>)"));
                            System.out.println("API version:     " + (profile.apiVersion != null ? "v" + profile.apiVersion
                                : (com.itsjool.aperture.cli.GlobalOptions.DEFAULT_API_VERSION != null ? "v" + com.itsjool.aperture.cli.GlobalOptions.DEFAULT_API_VERSION + " (default)" : "(none — unversioned)")));
                            System.out.println("Tenant:          " + (profile.tenant != null ? profile.tenant : "(none)"));
                            System.out.println("Scopes:          " + (profile.scopes != null && !profile.scopes.isEmpty()
                                ? profile.scopes.entrySet().stream()
                                    .map(e -> e.getKey() + "=" + e.getValue())
                                    .collect(java.util.stream.Collectors.joining(", "))
                                : "(none)"));
                            boolean loggedIn = profile.auth != null &&
                                (profile.auth.accessToken != null || profile.auth.apiKey != null);
                            String authKind = loggedIn && profile.auth.kind != null ? profile.auth.kind : "(none)";
                            System.out.println("Auth:            " + authKind);
                            System.out.print("Authenticated:   " + (loggedIn ? "yes" : "no"));
                            if (loggedIn && profile.username != null) System.out.print(" (as " + profile.username + ")");
                            System.out.println();
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(name = "set-server", description = "Set the API server URL for a profile")
                static class SetServerCommand implements Runnable {
                    @CommandLine.ParentCommand ConfigCommand parent;
                    @Parameters(index = "0", description = "Server base URL (e.g. http://localhost:8080)") String url;
                    @Override public void run() {
                        try {
                            String active = applyProfileMutation(parent.root,
                                m -> m.put("server", url.endsWith("/") ? url.substring(0, url.length() - 1) : url));
                            System.out.println("Server set to " + url + " for profile '" + active + "'.");
                            System.out.println("Next step: @BINARY@ auth login");
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(name = "set-api-version", description = "Pin the API version for a profile (e.g. 1)")
                static class SetApiVersionCommand implements Runnable {
                    @CommandLine.ParentCommand ConfigCommand parent;
                    @Parameters(index = "0", description = "API version number (e.g. 1)") String version;
                    @Override public void run() {
                        try {
                            String active = applyProfileMutation(parent.root, m -> m.put("apiVersion", version));
                            System.out.println("API version pinned to v" + version + " for profile '" + active + "'.");
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(name = "set-tenant", description = "Set the tenant context for a profile")
                static class SetTenantCommand implements Runnable {
                    @CommandLine.ParentCommand ConfigCommand parent;
                    @Parameters(index = "0", description = "Tenant id") String tenant;
                    @Override public void run() {
                        try {
                            String active = applyProfileMutation(parent.root, m -> m.put("tenant", tenant));
                            System.out.println("Tenant set to " + tenant + " for profile '" + active + "'.");
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(name = "set-scope", description = "Set a scope context value for a profile (kubectl-namespace-style)")
                static class SetScopeCommand implements Runnable {
                    @CommandLine.ParentCommand ConfigCommand parent;
                    @Parameters(index = "0", description = "Scope field name (e.g. project — matches an entity's scopedBy field)") String field;
                    @Parameters(index = "1", description = "Scope value (e.g. an id)") String value;
                    @Override public void run() {
                        try {
                            String key = field.toLowerCase();
                            String active = applyProfileMutation(parent.root, m -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> scopes = (Map<String, Object>) m.computeIfAbsent("scopes", k -> new LinkedHashMap<>());
                                scopes.put(key, value);
                            });
                            System.out.println("Scope '" + key + "' set to " + value + " for profile '" + active + "'.");
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(name = "unset-scope", description = "Remove a scope context value from a profile")
                static class UnsetScopeCommand implements Runnable {
                    @CommandLine.ParentCommand ConfigCommand parent;
                    @Parameters(index = "0", description = "Scope field name (e.g. project)") String field;
                    @Override public void run() {
                        try {
                            String key = field.toLowerCase();
                            String active = applyProfileMutation(parent.root, m -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> scopes = (Map<String, Object>) m.get("scopes");
                                if (scopes != null) scopes.remove(key);
                            });
                            System.out.println("Scope '" + key + "' unset for profile '" + active + "'.");
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(name = "set-api-key", description = "Store an API key for a profile")
                static class SetApiKeyCommand implements Runnable {
                    @CommandLine.ParentCommand ConfigCommand parent;
                    @Parameters(index = "0", description = "Raw API key") String key;
                    @Override public void run() {
                        try {
                            String active = applyProfileMutation(parent.root, m -> {
                                Map<String, Object> auth = new LinkedHashMap<>();
                                auth.put("kind", "api-key");
                                auth.put("apiKey", key);
                                m.put("auth", auth);
                            });
                            System.out.println("API key stored for profile '" + active + "'.");
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(name = "use", description = "Switch the active profile")
                static class UseProfileCommand implements Runnable {
                    @CommandLine.ParentCommand ConfigCommand parent;
                    @Parameters(index = "0", description = "Profile name") String name;
                    @Override public void run() {
                        try {
                            var config = ConfigStore.load();
                            config.put("activeProfile", name);
                            ConfigStore.save(config);
                            System.out.println("Active profile switched to '" + name + "'.");
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(name = "list-profiles", description = "List all configured profiles")
                static class ListProfilesCommand implements Runnable {
                    @CommandLine.ParentCommand ConfigCommand parent;
                    @Override public void run() {
                        try {
                            var config = ConfigStore.load();
                            String active = (String) config.getOrDefault("activeProfile", "default");
                            @SuppressWarnings("unchecked")
                            Map<String, Object> profiles = (Map<String, Object>) config.getOrDefault("profiles", new LinkedHashMap<>());
                            if (profiles.isEmpty()) {
                                System.out.println("No profiles configured. Run: @BINARY@ config set-server <url>");
                            } else {
                                profiles.forEach((name, raw) -> {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> p = (Map<String, Object>) raw;
                                    boolean loggedIn = p.containsKey("auth");
                                    System.out.println((name.equals(active) ? "* " : "  ")
                                        + name
                                        + "  server=" + p.getOrDefault("server", "(not set)")
                                        + (loggedIn ? "  [authenticated]" : ""));
                                });
                            }
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(name = "delete-profile", description = "Delete a non-active profile")
                static class DeleteProfileCommand implements Runnable {
                    @CommandLine.ParentCommand ConfigCommand parent;
                    @Parameters(index = "0", description = "Profile name") String name;
                    @Override public void run() {
                        try {
                            var config = ConfigStore.load();
                            String active = (String) config.getOrDefault("activeProfile", "default");
                            if (name.equals(active)) {
                                System.err.println("Cannot delete active profile '" + name + "'. Switch first: @BINARY@ config use <profile>");
                                System.exit(1);
                            }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> profiles = (Map<String, Object>) config.getOrDefault("profiles", new LinkedHashMap<>());
                            if (profiles.remove(name) == null) {
                                System.err.println("Profile not found: " + name);
                                System.exit(1);
                            }
                            ConfigStore.save(config);
                            System.out.println("Deleted profile '" + name + "'.");
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }
            }
            """.replace("@BINARY@", binaryName);
    }

    static String versionCommand(String binaryName) {
        return """
            package com.itsjool.aperture.cli.cmd;

            import picocli.CommandLine.Command;

            import java.io.InputStream;
            import java.util.Properties;

            @Command(name = "version", description = "Print the CLI version")
            public class VersionCommand implements Runnable {
                @Override
                public void run() {
                    try (InputStream in = getClass().getResourceAsStream("/aperture-cli-version.properties")) {
                        Properties props = new Properties();
                        if (in != null) props.load(in);
                        System.out.println("@BINARY@ " + props.getProperty("version", "unknown"));
                    } catch (Exception e) {
                        System.out.println("@BINARY@ (version unknown)");
                    }
                }
            }
            """.replace("@BINARY@", binaryName);
    }

    static String simpleAuthCommand(String binaryName, AuthPaths paths) {
        String envPrefix = binaryName.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        return """
            package com.itsjool.aperture.cli.cmd;

            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.itsjool.aperture.cli.ApertureCli;
            import com.itsjool.aperture.cli.GlobalOptions;
            import com.itsjool.aperture.cli.config.ConfigStore;
            import com.itsjool.aperture.cli.config.ProfileConfig;
            import com.itsjool.aperture.cli.http.ApiClient;
            import picocli.CommandLine;
            import picocli.CommandLine.Command;
            import picocli.CommandLine.Option;

            import java.util.LinkedHashMap;
            import java.util.Map;

            @Command(
                name = "auth",
                description = "Authentication commands",
                mixinStandardHelpOptions = true,
                subcommands = {
                    SimpleAuthCommand.LoginCommand.class,
                    SimpleAuthCommand.TokenCommand.class,
                    SimpleAuthCommand.ApiKeysCommand.class,
                    SimpleAuthCommand.RefreshCommand.class,
                    SimpleAuthCommand.LogoutCommand.class,
                    SimpleAuthCommand.MeCommand.class
                }
            )
            public class SimpleAuthCommand implements Runnable {
                @CommandLine.ParentCommand ApertureCli root;
                @Override public void run() { CommandLine.usage(this, System.out); }

                private record RawResponse(int status, String body) {}

                /** POST a JSON body to server+path with no auth headers (used before any
                 *  credentials exist yet — login/token/refresh all hit unauthenticated endpoints). */
                private static RawResponse postJson(String server, String path, Map<String, String> body) throws Exception {
                    ObjectMapper mapper = new ObjectMapper();
                    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(server + path))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                    java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                    return new RawResponse(resp.statusCode(), resp.body());
                }

                /** Store tokens on the active profile and save. When {@code preserveExistingAuth}
                 *  is true (refresh), the existing auth map is reused so unrelated fields (e.g. an
                 *  auth "kind" set by a prior login) survive; otherwise a fresh bearer auth map is
                 *  created (login/token always start a new session). server/username are left
                 *  untouched when null (refresh doesn't change either). Returns the active profile name. */
                private static String storeTokens(ApertureCli root, Map<String, Object> config, String server, String username,
                                                   String accessToken, String refreshToken, boolean preserveExistingAuth) throws Exception {
                    String activeProfile = root.global.profile != null ? root.global.profile
                        : (String) config.getOrDefault("activeProfile", "default");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> profiles = (Map<String, Object>) config.computeIfAbsent("profiles", k -> new LinkedHashMap<>());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> profileMap = (Map<String, Object>) profiles.computeIfAbsent(activeProfile, k -> new LinkedHashMap<>());
                    Map<String, Object> auth;
                    if (preserveExistingAuth) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> existing = (Map<String, Object>) profileMap.computeIfAbsent("auth", k -> new LinkedHashMap<>());
                        auth = existing;
                    } else {
                        auth = new LinkedHashMap<>();
                        auth.put("kind", "bearer");
                    }
                    if (accessToken != null) auth.put("accessToken", accessToken);
                    if (refreshToken != null) auth.put("refreshToken", refreshToken);
                    profileMap.put("auth", auth);
                    if (server != null) profileMap.put("server", server);
                    if (username != null) profileMap.put("username", username);
                    ConfigStore.save(config);
                    return activeProfile;
                }

                @Command(name = "login", description = "Log in with username and password")
                static class LoginCommand implements Runnable {
                    @CommandLine.ParentCommand SimpleAuthCommand parent;
                    @Option(names = {"--username", "-u"}, required = true, description = "Username or email") String username;
                    @Option(names = {"--password", "-p"}, required = true, interactive = true, arity = "0..1", description = "Password (prompted if omitted)") String password;
                    @Override public void run() {
                        try {
                            GlobalOptions global = parent.root.global;
                            var config = ConfigStore.load();
                            var profile = ConfigStore.activeProfile(config, global.profile);
                            if (global.server != null) profile.server = global.server;
                            if (profile.server == null || profile.server.isBlank()) {
                                System.err.println("No server URL configured. Specify one with --server <url> or run:");
                                System.err.println("  @BINARY@ config set-server <url>");
                                System.exit(1);
                            }
                            ObjectMapper mapper = new ObjectMapper();
                            RawResponse resp = postJson(profile.server, "@LOGIN_PATH@", Map.of("username", username, "password", password));
                            if (resp.status() >= 400) {
                                try {
                                    JsonNode err = mapper.readTree(resp.body());
                                    String msg = err.path("message").asText(err.path("detail").asText(resp.body()));
                                    System.err.println("Login failed: " + msg);
                                } catch (Exception ignored) {
                                    System.err.println("Login failed (HTTP " + resp.status() + "): " + resp.body());
                                }
                                System.exit(1);
                            }
                            JsonNode node = mapper.readTree(resp.body());
                            String accessToken = node.path("accessToken").asText(null);
                            String refreshToken = node.path("refreshToken").asText(null);
                            storeTokens(parent.root, config, profile.server, username, accessToken, refreshToken, false);
                            System.out.println("Logged in as " + username);
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(name = "token", description = "Obtain a service-account access token", mixinStandardHelpOptions = true)
                static class TokenCommand implements Runnable {
                    @CommandLine.ParentCommand SimpleAuthCommand parent;
                    @Option(names = "--client-id", description = "Service account client id") String clientId;
                    @Option(names = "--client-secret", description = "Service account client secret") String clientSecret;
                    @Override public void run() {
                        try {
                            if (clientId == null || clientSecret == null) {
                                System.err.println("Missing required options: --client-id, --client-secret");
                                System.exit(2);
                            }
                            GlobalOptions global = parent.root.global;
                            var config = ConfigStore.load();
                            var profile = ConfigStore.activeProfile(config, global.profile);
                            if (global.server != null) profile.server = global.server;
                            if (profile.server == null || profile.server.isBlank()) {
                                System.err.println("No server URL configured. Specify one with --server <url> or run:");
                                System.err.println("  @BINARY@ config set-server <url>");
                                System.exit(1);
                            }
                            ObjectMapper mapper = new ObjectMapper();
                            RawResponse resp = postJson(profile.server, "@TOKEN_PATH@", Map.of("clientId", clientId, "clientSecret", clientSecret));
                            if (resp.status() >= 400) {
                                System.err.println("Service-account token failed (HTTP " + resp.status() + "): " + resp.body());
                                System.exit(1);
                            }
                            JsonNode node = mapper.readTree(resp.body());
                            String accessToken = node.path("accessToken").asText(null);
                            if (accessToken == null || accessToken.isBlank()) {
                                System.err.println("Service-account token response did not include accessToken");
                                System.exit(1);
                            }
                            String activeProfile = storeTokens(parent.root, config, profile.server, clientId, accessToken, null, false);
                            System.out.println("Service-account token stored for profile '" + activeProfile + "'.");
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(
                    name = "api-keys",
                    description = "Manage personal API keys",
                    mixinStandardHelpOptions = true,
                    subcommands = {
                        ApiKeysCommand.CreateCommand.class,
                        ApiKeysCommand.ListCommand.class,
                        ApiKeysCommand.DisableCommand.class
                    }
                )
                static class ApiKeysCommand implements Runnable {
                    @CommandLine.ParentCommand SimpleAuthCommand parent;
                    @Override public void run() { CommandLine.usage(this, System.out); }

                    @Command(name = "create", description = "Create a personal API key", mixinStandardHelpOptions = true)
                    static class CreateCommand implements Runnable {
                        @CommandLine.ParentCommand ApiKeysCommand parent;
                        @Option(names = "--name", description = "API key name") String name;
                        @Option(names = "--expires-at", description = "ISO-8601 expiration timestamp") String expiresAt;
                        @Option(names = "--non-expiring", description = "Create a non-expiring key") boolean nonExpiring;
                        @Override public void run() {
                            try {
                                if (name == null || name.isBlank()) {
                                    System.err.println("Missing required option: --name");
                                    System.exit(2);
                                }
                                GlobalOptions global = parent.parent.root.global;
                                var config = ConfigStore.load();
                                var profile = ConfigStore.activeProfile(config, global.profile);
                                if (global.server != null) profile.server = global.server;
                                var client = new ApiClient(profile.server, profile, global.verbose);
                                Map<String, Object> body = new LinkedHashMap<>();
                                body.put("name", name);
                                body.put("expiresAt", expiresAt);
                                body.put("nonExpiring", nonExpiring);
                                body.put("domainRoles", null);
                                body.put("securityAttributes", null);
                                JsonNode result = client.post("@API_KEYS_PATH@", body);
                                if ("json".equalsIgnoreCase(global.format)) {
                                    System.out.println(result.toPrettyString());
                                } else {
                                    String secret = result.path("secret").asText("");
                                    System.out.println("API key created. Store it now; it is not shown again:");
                                    System.out.println(secret);
                                }
                            } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                        }
                    }

                    @Command(name = "list", description = "List personal API keys", mixinStandardHelpOptions = true)
                    static class ListCommand implements Runnable {
                        @CommandLine.ParentCommand ApiKeysCommand parent;
                        @Override public void run() {
                            try {
                                GlobalOptions global = parent.parent.root.global;
                                var config = ConfigStore.load();
                                var profile = ConfigStore.activeProfile(config, global.profile);
                                if (global.server != null) profile.server = global.server;
                                var client = new ApiClient(profile.server, profile, global.verbose);
                                JsonNode result = client.get("@API_KEYS_PATH@");
                                if ("json".equalsIgnoreCase(global.format)) {
                                    System.out.println(result.toPrettyString());
                                    return;
                                }
                                System.out.printf("%-36s  %-10s  %-24s  %-24s%n", "ID", "STATUS", "CREATED", "EXPIRES");
                                if (result != null && result.isArray()) {
                                    for (JsonNode key : result) {
                                        System.out.printf("%-36s  %-10s  %-24s  %-24s%n",
                                            key.path("id").asText(""),
                                            key.path("status").asText(""),
                                            key.path("createdAt").asText(""),
                                            key.path("expiresAt").isMissingNode() || key.path("expiresAt").isNull() ? "" : key.path("expiresAt").asText(""));
                                    }
                                }
                            } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                        }
                    }

                    @Command(name = "disable", description = "Disable a personal API key", mixinStandardHelpOptions = true)
                    static class DisableCommand implements Runnable {
                        @CommandLine.ParentCommand ApiKeysCommand parent;
                        @CommandLine.Parameters(index = "0", description = "API key id") String keyId;
                        @Override public void run() {
                            try {
                                GlobalOptions global = parent.parent.root.global;
                                var config = ConfigStore.load();
                                var profile = ConfigStore.activeProfile(config, global.profile);
                                if (global.server != null) profile.server = global.server;
                                var client = new ApiClient(profile.server, profile, global.verbose);
                                JsonNode result = client.post("@API_KEYS_PATH@/" + keyId + "/disable", Map.of());
                                if ("json".equalsIgnoreCase(global.format)) {
                                    System.out.println(result.toPrettyString());
                                } else {
                                    System.out.println("API key disabled: " + keyId);
                                }
                            } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                        }
                    }
                }

                @Command(name = "refresh", description = "Refresh the access token using the stored refresh token")
                static class RefreshCommand implements Runnable {
                    @CommandLine.ParentCommand SimpleAuthCommand parent;
                    @Override public void run() {
                        try {
                            GlobalOptions global = parent.root.global;
                            var config = ConfigStore.load();
                            var profile = ConfigStore.activeProfile(config, global.profile);
                            if (global.server != null) profile.server = global.server;
                            if (profile.server == null || profile.server.isBlank()) {
                                System.err.println("No server URL configured. Run: @BINARY@ config set-server <url>");
                                System.exit(1);
                            }
                            if (profile.auth == null || profile.auth.refreshToken == null) {
                                System.err.println("No refresh token found. Please log in first: @BINARY@ auth login");
                                System.exit(1);
                            }
                            ObjectMapper mapper = new ObjectMapper();
                            RawResponse resp = postJson(profile.server, "@REFRESH_PATH@", Map.of("refreshToken", profile.auth.refreshToken));
                            if (resp.status() >= 400) {
                                System.err.println("Refresh failed (HTTP " + resp.status() + "). Your session may have expired — please log in again: @BINARY@ auth login");
                                System.exit(1);
                            }
                            JsonNode node = mapper.readTree(resp.body());
                            String accessToken = node.path("accessToken").asText(null);
                            String refreshToken = node.path("refreshToken").asText(null);
                            storeTokens(parent.root, config, null, null, accessToken, refreshToken, true);
                            System.out.println("Token refreshed.");
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(name = "logout", description = "Clear stored credentials for the active profile")
                static class LogoutCommand implements Runnable {
                    @CommandLine.ParentCommand SimpleAuthCommand parent;
                    @Override public void run() {
                        try {
                            GlobalOptions global = parent.root.global;
                            var config = ConfigStore.load();
                            String activeProfile = global.profile != null ? global.profile
                                : (String) config.getOrDefault("activeProfile", "default");
                            @SuppressWarnings("unchecked")
                            Map<String, Object> profiles = (Map<String, Object>) config.getOrDefault("profiles", new LinkedHashMap<>());
                            @SuppressWarnings("unchecked")
                            Map<String, Object> profileMap = (Map<String, Object>) profiles.getOrDefault(activeProfile, new LinkedHashMap<>());
                            String refreshToken = null;
                            Object authObj = profileMap.get("auth");
                            if (authObj instanceof Map<?, ?> authMap) {
                                Object tokenObj = authMap.get("refreshToken");
                                if (tokenObj != null) refreshToken = tokenObj.toString();
                            }
                            if (refreshToken != null) {
                                ObjectMapper mapper = new ObjectMapper();
                                Map<String, String> body = Map.of("refreshToken", refreshToken);
                                var profile = ConfigStore.activeProfile(config, global.profile);
                                if (global.server != null) profile.server = global.server;
                                if (profile.server != null && !profile.server.isBlank()) {
                                    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                                        .uri(java.net.URI.create(profile.server + "@LOGOUT_PATH@"))
                                        .header("Content-Type", "application/json")
                                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                                        .build();
                                    java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                                    if (resp.statusCode() >= 400) {
                                        System.err.println("Logout revoke failed (HTTP " + resp.statusCode() + "); clearing local credentials.");
                                    }
                                }
                            }
                            profileMap.remove("auth");
                            profileMap.remove("username");
                            ConfigStore.save(config);
                            System.out.println("Logged out.");
                            if (System.getenv("@ENVPREFIX@_TOKEN") != null) {
                                System.err.println("Warning: @ENVPREFIX@_TOKEN environment variable is set and will continue to authenticate requests until unset.");
                            }
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }

                @Command(name = "me", description = "Show current authenticated user")
                static class MeCommand implements Runnable {
                    @CommandLine.ParentCommand SimpleAuthCommand parent;
                    @Override public void run() {
                        try {
                            GlobalOptions global = parent.root.global;
                            var config = ConfigStore.load();
                            var profile = ConfigStore.activeProfile(config, global.profile);
                            if (global.server != null) profile.server = global.server;
                            if (profile.auth == null || (profile.auth.accessToken == null && profile.auth.apiKey == null)) {
                                System.err.println("Not logged in. Run: @BINARY@ auth login");
                                System.exit(1);
                            }
                            var client = new ApiClient(profile.server, profile, global.verbose);
                            JsonNode result = client.get("@ME_PATH@");
                            OutputFormatter.print(result, global.format);
                        } catch (Exception e) { System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString()); System.exit(1); }
                    }
                }
            }
            """
            .replace("@BINARY@", binaryName)
            .replace("@LOGIN_PATH@", paths.login())
            .replace("@REFRESH_PATH@", paths.refresh())
            .replace("@LOGOUT_PATH@", paths.logout())
            .replace("@ME_PATH@", paths.me())
            .replace("@TOKEN_PATH@", paths.token())
            .replace("@API_KEYS_PATH@", paths.apiKeys())
            .replace("@ENVPREFIX@", envPrefix);
    }

    static String outputFormatter() {
        return """
            package com.itsjool.aperture.cli.cmd;

            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;

            public class OutputFormatter {
                private static final ObjectMapper MAPPER = new ObjectMapper();

                public static void print(JsonNode node, String format) {
                    if (node == null || node.isNull()) return;
                    if ("json".equalsIgnoreCase(format)) {
                        try {
                            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
                        } catch (Exception e) {
                            System.out.println(node);
                        }
                        return;
                    }
                    // Default: table-like flat output
                    if (node.has("data")) {
                        JsonNode data = node.get("data");
                        if (data.isArray()) {
                            data.forEach(item -> printItem(item));
                        } else {
                            printItem(data);
                        }
                    } else {
                        System.out.println(node.toPrettyString());
                    }
                }

                private static void printItem(JsonNode item) {
                    String id = item.path("id").asText("-");
                    JsonNode attrs = item.path("attributes");
                    System.out.print("id=" + id);
                    attrs.fields().forEachRemaining(e ->
                        System.out.print("  " + e.getKey() + "=" + e.getValue().asText()));
                    System.out.println();
                }
            }
            """;
    }

    static String versionProvider(String binaryName) {
        return """
            package com.itsjool.aperture.cli;

            import picocli.CommandLine;

            import java.io.InputStream;
            import java.util.Properties;

            public class VersionProvider implements CommandLine.IVersionProvider {
                @Override
                public String[] getVersion() throws Exception {
                    try (InputStream in = getClass().getResourceAsStream("/aperture-cli-version.properties")) {
                        Properties props = new Properties();
                        if (in != null) props.load(in);
                        return new String[]{"@BINARY@ " + props.getProperty("version", "unknown")};
                    }
                }
            }
            """.replace("@BINARY@", binaryName);
    }

    /** Generates ApplyCommand.java — applies resources from a YAML file. */
    static String applyCommand(List<EntityDef> entities, String binaryName) {
        return JavaFile.builder(CMD_PKG, buildApplyTypeSpec(entities)).build().toString();
    }

    /**
     * Generates FileOps.java — the registry (ENTITY_SPECS) and relationship/natural-key
     * resolution helpers shared by ApplyCommand and the {@code -f} paths of
     * CreateCommand/UpdateCommand/DeleteCommand (see EntityCommandGenerator).
     */
    static String fileOps(List<EntityDef> entities) {
        return JavaFile.builder(CMD_PKG, buildFileOpsTypeSpec(entities)).build().toString();
    }

    private static TypeSpec buildApplyTypeSpec(List<EntityDef> entities) {
        return TypeSpec.classBuilder("ApplyCommand")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(Runnable.class)
            .addAnnotation(AnnotationSpec.builder(CMD_ANN)
                .addMember("name", "$S", "apply")
                .addMember("description", "$S", "Apply resources from a YAML file (create or upsert)")
                .addMember("mixinStandardHelpOptions", "$L", true)
                .build())
            .addField(FieldSpec.builder(APERTURE_CLI, "root").addAnnotation(PARENT_CMD).build())
            .addField(optionField(String.class, "file", new String[]{"-f", "--file"},
                "YAML file or directory to apply", true))
            .addField(optionField(boolean.class, "dryRun", new String[]{"--dry-run"},
                "Print what would be applied without making API calls", false))
            .addField(optionField(boolean.class, "upsert", new String[]{"--upsert"},
                "Update existing resources instead of skipping them", false))
            .addField(optionField(boolean.class, "continueOnError", new String[]{"--continue-on-error"},
                "Continue processing after errors", false))
            .addField(optionField(boolean.class, "atomic", new String[]{"--atomic"},
                "Apply all resources in a single atomic batch via JSON:API Atomic Operations (requires a versioned API path)", false))
            .addMethod(applyRunMethod(false))
            .addMethod(runAtomicApplyMethod())
            .build();
    }

    private static TypeSpec buildFileOpsTypeSpec(List<EntityDef> entities) {
        return TypeSpec.classBuilder("FileOps")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("""
                Shared file-mode helpers: the entity registry (ENTITY_SPECS) plus relationship and
                natural-key resolution used by ApplyCommand and the {@code -f} paths of
                CreateCommand/UpdateCommand/DeleteCommand. Generated once per project so all four
                commands share one source of truth instead of duplicating this machinery.
                """)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addType(fieldSpecRecord())
            .addType(entitySpecRecord())
            .addField(entitySpecsField())
            .addField(uuidPatternField())
            .addStaticBlock(buildEntityRegistryBlock(entities))
            .addMethod(resolveFilesMethod())
            .addMethod(isUuidMethod())
            .addMethod(resolveRelIdMethod())
            .addMethod(lookupIdMethod())
            .addMethod(lookupIdAndEtagMethod())
            .addMethod(fetchEtagMethod())
            .addMethod(warnIfNaturalKeyNotUniqueMethod())
            .addMethod(applyParentOverridesMethod())
            .build();
    }

    /**
     * Applies {@code --server}/{@code --tenant}/{@code --scope} global-option overrides onto the
     * active profile before an {@code ApiClient} is built. Shared by ApplyCommand and every verb
     * command's entity-subcommand and {@code -f} file-mode path (see EntityCommandGenerator) so
     * the override/precedence rule lives in one place instead of being re-parsed per call site.
     */
    private static MethodSpec applyParentOverridesMethod() {
        return MethodSpec.methodBuilder("applyParentOverrides")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(void.class)
            .addParameter(GLOBAL_OPTIONS, "global")
            .addParameter(PROFILE_CONFIG, "profile")
            .beginControlFlow("if (global.server != null)")
            .addStatement("profile.server = global.server")
            .endControlFlow()
            .beginControlFlow("if (global.tenant != null)")
            .addStatement("profile.tenant = global.tenant")
            .endControlFlow()
            // --scope field=value (repeatable) overrides the profile's persisted scopes for
            // the same field — same precedence rule as --tenant above, just merged into a map
            // instead of replacing a single value, since a command can touch several entities
            // each declaring a different scopedBy field. Field names are canonicalized to
            // lowercase on the way in so "--scope Project=x" and "--scope project=x" collapse
            // to one entry (the server also lowercases the header suffix it reads).
            .beginControlFlow("for ($T entry : global.scope)", String.class)
            .beginControlFlow("if (entry != null && !entry.isBlank())")
            .addStatement("int eq = entry.indexOf('=')")
            .beginControlFlow("if (eq <= 0)")
            .addStatement("throw new $T($S + entry)", IllegalArgumentException.class,
                    "Invalid --scope value; expected field=value: ")
            .endControlFlow()
            .addStatement("$T field = entry.substring(0, eq).trim().toLowerCase()", String.class)
            .addStatement("$T value = entry.substring(eq + 1)", String.class)
            .addStatement("profile.scopes.put(field, value)")
            .endControlFlow()
            .endControlFlow()
            .build();
    }

    private static FieldSpec optionField(Class<?> type, String fieldName, String[] names,
                                         String description, boolean required) {
        AnnotationSpec.Builder ann = AnnotationSpec.builder(OPTION_ANN);
        if (names.length == 1) {
            ann.addMember("names", "$S", names[0]);
        } else {
            StringBuilder fmt = new StringBuilder("{");
            Object[] args = new Object[names.length];
            for (int i = 0; i < names.length; i++) {
                if (i > 0) fmt.append(", ");
                fmt.append("$S");
                args[i] = names[i];
            }
            ann.addMember("names", fmt.append("}").toString(), args);
        }
        ann.addMember("description", "$S", description);
        if (required) ann.addMember("required", "$L", true);
        return FieldSpec.builder(type, fieldName).addAnnotation(ann.build()).build();
    }

    private static TypeSpec fieldSpecRecord() {
        return TypeSpec.recordBuilder("FieldSpec")
            .addModifiers(Modifier.PUBLIC)
            .recordConstructor(MethodSpec.constructorBuilder()
                .addParameter(boolean.class, "isRel")
                .addParameter(boolean.class, "manyToOne")
                .addParameter(String.class, "targetPath")
                .addParameter(String.class, "yamlKey")
                .addParameter(String.class, "naturalKey")
                .build())
            .build();
    }

    private static TypeSpec entitySpecRecord() {
        return TypeSpec.recordBuilder("EntitySpec")
            .addModifiers(Modifier.PUBLIC)
            .recordConstructor(MethodSpec.constructorBuilder()
                .addParameter(String.class, "path")
                .addParameter(String.class, "naturalKey")
                .addParameter(boolean.class, "naturalKeyUnique")
                .addParameter(ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), FIELD_SPEC_CN), "fields")
                .build())
            .build();
    }

    private static FieldSpec entitySpecsField() {
        return FieldSpec.builder(
                ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), ENTITY_SPEC_CN),
                "ENTITY_SPECS")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .build();
    }

    /** Warn once per entity type (not once per record) when apply/create/update/delete file-mode
     *  relies on a natural key that isn't declared unique — duplicate/existence detection can't
     *  be relied on for that entity in that case. Shared across all four -f callers. */
    private static MethodSpec warnIfNaturalKeyNotUniqueMethod() {
        return MethodSpec.methodBuilder("warnIfNaturalKeyNotUnique")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(void.class)
            .addParameter(ENTITY_SPEC_CN, "spec")
            .addParameter(String.class, "entityPath")
            .addParameter(ParameterizedTypeName.get(ClassName.get(java.util.Set.class), ClassName.get(String.class)), "warned")
            .addParameter(boolean.class, "dryRun")
            .beginControlFlow("if (!spec.naturalKeyUnique() && !dryRun && warned.add(entityPath))")
            .addStatement("""
                System.err.printf("  [WARN] %s' natural key '%s' is not declared unique in the manifest — "
                    + "duplicate detection (skip-on-conflict / --upsert) will not work reliably for this entity.%n",
                    entityPath, spec.naturalKey())
                """)
            .endControlFlow()
            .build();
    }

    private static CodeBlock buildEntityRegistryBlock(List<EntityDef> entities) {
        Map<String, String> classToPath = new HashMap<>();
        Map<String, String> classToNaturalKey = new HashMap<>();
        for (EntityDef e : entities) {
            String path = e.plural() != null ? e.plural().toLowerCase() : e.name().toLowerCase() + "s";
            classToPath.put(e.name().toLowerCase(), path);
            classToNaturalKey.put(e.name().toLowerCase(), detectNaturalKey(e));
        }
        CodeBlock.Builder cb = CodeBlock.builder();
        cb.addStatement("$T<$T, EntitySpec> m = new $T<>()", Map.class, String.class, LINKED_MAP);
        for (int i = 0; i < entities.size(); i++) {
            EntityDef entity = entities.get(i);
            String path = entity.plural() != null ? entity.plural().toLowerCase() : entity.name().toLowerCase() + "s";
            String naturalKey = detectNaturalKey(entity);
            String fVar = "f" + i;
            cb.addStatement("$T<$T, FieldSpec> $L = new $T<>()", Map.class, String.class, fVar, LINKED_MAP);
            if (entity.fields() != null) {
                for (Map.Entry<String, FieldDef> entry : entity.fields().entrySet()) {
                    String fname = entry.getKey();
                    FieldDef fd = entry.getValue();
                    if ("ref".equals(fd.type())) {
                        String rel = fd.relation() != null ? fd.relation() : "";
                        boolean manyToOne = rel.equalsIgnoreCase("ManyToOne") || rel.equalsIgnoreCase("ManyToManyOwner");
                        String targetClass = fd.targetClass() != null ? fd.targetClass().toLowerCase() : fname;
                        String targetPath = classToPath.getOrDefault(targetClass, targetClass + "s");
                        String targetNaturalKey = classToNaturalKey.getOrDefault(targetClass, "name");
                        cb.addStatement("$L.put($S, new FieldSpec(true, $L, $S, $S, $S))",
                            fVar, fname, manyToOne, targetPath, fname, targetNaturalKey);
                    } else {
                        cb.addStatement("$L.put($S, new FieldSpec(false, false, null, $S, null))",
                            fVar, fname, fname);
                    }
                }
            }
            boolean naturalKeyUnique = isNaturalKeyUnique(entity, naturalKey);
            cb.addStatement("m.put($S, new EntitySpec($S, $S, $L, $L))", path, path, naturalKey, naturalKeyUnique, fVar);
        }
        cb.addStatement("ENTITY_SPECS = $T.unmodifiableMap(m)", COLLECTIONS);
        return cb.build();
    }

    /** Whether the field detectNaturalKey() picked is actually declared unique: true — if not,
     *  the server has no way to reject a duplicate, so apply's skip-on-conflict/--upsert paths
     *  can't detect repeats. Used to warn, not to change behavior. */
    private static boolean isNaturalKeyUnique(EntityDef entity, String naturalKey) {
        // "id" is detectNaturalKey()'s fallback when no unique/candidate field exists — it's the
        // real primary key, always unique by definition, not a manifest declaration to check.
        if ("id".equals(naturalKey)) return true;
        if (entity.fields() == null) return false;
        FieldDef field = entity.fields().get(naturalKey);
        return field != null && field.unique();
    }

    /** Returns the best natural key for an entity — used for lookup by name. */

    private static String detectNaturalKey(EntityDef entity) {
        if (entity.fields() == null) return "name";
        for (Map.Entry<String, FieldDef> e : entity.fields().entrySet()) {
            if (!"ref".equals(e.getValue().type()) && e.getValue().unique()) return e.getKey();
        }
        for (String candidate : List.of("name", "code", "sku", "email", "company_name", "username")) {
            if (entity.fields().containsKey(candidate)) return candidate;
        }
        return "id";
    }

    private static MethodSpec applyRunMethod(boolean isDelete) {
        String verbPast = isDelete ? "Deleted" : "Applied";

        MethodSpec.Builder m = MethodSpec.methodBuilder("run")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .beginControlFlow("try")
            .addStatement("var config = $T.load()", CONFIG_STORE)
            .addStatement("var profile = $T.activeProfile(config, root.global.profile)", CONFIG_STORE)
            .addStatement("$T.applyParentOverrides(root.global, profile)", FILE_OPS_CN)
            .addStatement("var client = new $T(profile.server, profile, root.global.verbose)", API_CLIENT)
            .addStatement("$T version = root.global.apiVersion != null ? root.global.apiVersion : (profile.apiVersion != null ? profile.apiVersion : $T.DEFAULT_API_VERSION)", String.class, GLOBAL_OPTIONS)
            .addStatement("$T versionPrefix = (version != null && !version.isBlank()) ? $S + version : $S",
                String.class, "/api/v", "/api")
            .addStatement("var files = $T.resolveFiles(file)", FILE_OPS_CN)
            .beginControlFlow("if (files.isEmpty())")
            .addStatement("$T.err.println($S + file)", System.class, "No YAML files found: ")
            .addStatement("$T.exit(1)", System.class)
            .endControlFlow();

        if (!isDelete) {
            m.beginControlFlow("if (atomic)")
             .beginControlFlow("if (upsert)")
             .addStatement("$T.err.println($S)", System.class, "Error: --atomic cannot be combined with --upsert")
             .addStatement("$T.exit(2)", System.class)
             .endControlFlow()
             .addStatement("runAtomicApply(files, versionPrefix, client, profile, dryRun)")
             .addStatement("return")
             .endControlFlow()
             .addStatement("$T<$T, $T> created = new $T<>()", Map.class, String.class, String.class, LINKED_MAP);
        }

        if (!isDelete) {
            m.addStatement("$T yaml = new $T(new $T())", OBJECT_MAPPER, OBJECT_MAPPER, YAML_FACTORY);
            m.addStatement("$T<$T> warnedNaturalKey = new $T<>()", java.util.Set.class, String.class, ClassName.get("java.util", "HashSet"));
        }

        m.addStatement("int total = 0, success = 0, skipped = 0, failed = 0")
         .addCode(isDelete ? applyDeleteLoop() : applyCreateLoop())
         .addStatement("$T.out.printf($S, success, skipped, failed)",
             System.class, "%n" + verbPast + " %d resource(s)  skipped=%d  failed=%d%n")
         .nextControlFlow("catch ($T e)", Exception.class)
         .addStatement("$T.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + $S + e)",
             System.class, ": ")
         .addStatement("$T.exit(1)", System.class)
         .endControlFlow();

        return m.build();
    }

    private static MethodSpec runAtomicApplyMethod() {
        return MethodSpec.methodBuilder("runAtomicApply")
            .addJavadoc("Atomic apply (JSON:API Atomic Operations).\n")
            .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "$S", "unchecked")
                .build())
            .addModifiers(Modifier.PRIVATE)
            .returns(void.class)
            .addParameter(ParameterizedTypeName.get(ClassName.get(List.class), PATH_CN), "files")
            .addParameter(String.class, "versionPrefix")
            .addParameter(API_CLIENT, "client")
            .addParameter(PROFILE_CONFIG, "profile")
            .addParameter(boolean.class, "dryRun")
            .addException(Exception.class)
            .addCode("""
                Map<String, String> lidCache = new LinkedHashMap<>();
                List<Map<String, Object>> ops = new java.util.ArrayList<>();
                List<String[]> labels = new java.util.ArrayList<>();
                int globalIdx = 0;

                ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
                for (Path f : files) {
                    System.out.println("Applying " + f + " (atomic)...");
                    Map<String, Object> doc = yaml.readValue(f.toFile(), Map.class);
                    if (doc == null) continue;
                    for (Map.Entry<String, Object> section : doc.entrySet()) {
                        String entityPath = section.getKey();
                        FileOps.EntitySpec spec = FileOps.ENTITY_SPECS.get(entityPath);
                        if (spec == null) { System.err.println("  [WARN] Unknown entity type '" + entityPath + "' — skipping"); continue; }
                        List<Map<String, Object>> records = (List<Map<String, Object>>) section.getValue();
                        if (records == null) continue;
                        for (Map<String, Object> record : records) {
                            String lid = entityPath + "-" + globalIdx++;
                            Object refVal = record.get("_ref");
                            Object naturalVal = record.get(spec.naturalKey());
                            String displayName = refVal != null ? refVal.toString()
                                : naturalVal != null ? naturalVal.toString() : lid;
                            // Register before processing so later records in the same file can reference this one
                            // by display name — a user-assigned _ref (if present), else the natural key, else the
                            // positional label (entityPath-index; unstable, see resources-atomic/order.yaml).
                            lidCache.put(entityPath + ":" + displayName, lid);

                            Map<String, Object> attrs = new LinkedHashMap<>();
                            Map<String, Object> rels = new LinkedHashMap<>();
                            for (Map.Entry<String, Object> field : record.entrySet()) {
                                String key = field.getKey();
                                Object val = field.getValue();
                                if (val == null) continue;
                                FileOps.FieldSpec fs = spec.fields().get(key);
                                if (fs == null) continue;
                                if (fs.isRel()) {
                                    if (!fs.manyToOne()) continue;
                                    String strVal = val.toString();
                                    Map<String, Object> relData = new LinkedHashMap<>();
                                    relData.put("type", fs.targetPath());
                                    if (FileOps.isUuid(strVal)) {
                                        relData.put("id", strVal);
                                    } else {
                                        String refLid = lidCache.get(fs.targetPath() + ":" + strVal);
                                        if (refLid != null) {
                                            relData.put("lid", refLid);
                                        } else if (!dryRun) {
                                            String existId = FileOps.lookupId(strVal, fs.naturalKey(), fs.targetPath(), versionPrefix, client);
                                            if (existId != null) { relData.put("id", existId); }
                                            else { System.err.printf("  [WARN] Cannot resolve %s '%s' — skipping relationship%n", key, strVal); continue; }
                                        } else {
                                            relData.put("id", "<lookup:" + strVal + ">");
                                        }
                                    }
                                    rels.put(key, Map.of("data", relData));
                                } else {
                                    attrs.put(key, val);
                                }
                            }
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("type", entityPath);
                            data.put("lid", lid);
                            data.put("attributes", attrs);
                            if (!rels.isEmpty()) data.put("relationships", rels);
                            Map<String, Object> op = new LinkedHashMap<>();
                            op.put("op", "add");
                            op.put("data", data);
                            ops.add(op);
                            labels.add(new String[]{entityPath, displayName});
                        }
                    }
                }

                if (ops.isEmpty()) { System.out.println("No operations to apply."); return; }

                if (dryRun) {
                    System.out.printf("Would POST %d operation(s) atomically to %s/operations%n", ops.size(), versionPrefix);
                    for (String[] lbl : labels)
                        System.out.printf("  [DRY-RUN] would create %s  %s%n", lbl[0], lbl[1]);
                    System.out.printf("%nWould apply %d resource(s) atomically%n", ops.size());
                    return;
                }

                ObjectMapper jsonMapper = new ObjectMapper();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("atomic:operations", ops);
                String reqBody = jsonMapper.writeValueAsString(payload);
                String atomicCt = "application/vnd.api+json;ext=\\"https://jsonapi.org/ext/atomic\\"";
                String serverBase = profile.server != null && profile.server.endsWith("/")
                    ? profile.server.substring(0, profile.server.length() - 1) : profile.server;
                java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(serverBase + versionPrefix + "/operations"))
                    .header("Content-Type", atomicCt)
                    .header("Accept", atomicCt)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(reqBody));
                if (profile.auth != null) {
                    if ("bearer".equals(profile.auth.kind) && profile.auth.accessToken != null)
                        reqBuilder.header("Authorization", "Bearer " + profile.auth.accessToken);
                    else if ("api-key".equals(profile.auth.kind) && profile.auth.apiKey != null)
                        reqBuilder.header("X-API-Key", profile.auth.apiKey);
                    if (profile.tenant != null) reqBuilder.header("X-Aperture-Tenant-Context", profile.tenant);
                }
                java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpResponse<String> resp = httpClient.send(
                    reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    Map<String, Object> respBody = jsonMapper.readValue(resp.body(), Map.class);
                    List<Map<String, Object>> results = (List<Map<String, Object>>) respBody.get("atomic:results");
                    System.out.println();
                    for (int i = 0; i < labels.size(); i++) {
                        String[] lbl = labels.get(i);
                        String id = null;
                        if (results != null && i < results.size() && results.get(i) != null) {
                            Object dataObj = results.get(i).get("data");
                            if (dataObj instanceof Map<?,?> dm) id = (String) dm.get("id");
                        }
                        System.out.printf("  ✓ %s  %s  → %s%n", lbl[0], lbl[1], id != null ? id : "?");
                    }
                    System.out.printf("%nApplied %d resource(s) atomically%n", ops.size());
                } else {
                    System.err.println("Atomic batch failed: HTTP " + resp.statusCode());
                    try {
                        Map<String, Object> err = jsonMapper.readValue(resp.body(), Map.class);
                        List<?> errors = (List<?>) err.get("errors");
                        if (errors != null) {
                            for (Object e : errors) {
                                @SuppressWarnings("unchecked") Map<String,Object> em = (Map<String,Object>) e;
                                System.err.println("  " + em.getOrDefault("detail", em.getOrDefault("title", em.toString())));
                            }
                        } else { System.err.println(resp.body()); }
                    } catch (Exception ignored) { System.err.println(resp.body()); }
                    System.exit(1);
                }
                """)
            .build();
    }

    private static MethodSpec resolveFilesMethod() {
        return MethodSpec.methodBuilder("resolveFiles")
            .addJavadoc("Expand file arg to sorted list of YAML files.\n")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(ClassName.get(List.class), PATH_CN))
            .addParameter(String.class, "fileArg")
            .addException(Exception.class)
            .addCode("""
                Path p = Path.of(fileArg);
                if (!java.nio.file.Files.exists(p)) return List.of();
                if (java.nio.file.Files.isDirectory(p)) {
                    try (var stream = java.nio.file.Files.list(p)) {
                        return stream
                            .filter(f -> f.toString().endsWith(".yaml") || f.toString().endsWith(".yml"))
                            .sorted()
                            .toList();
                    }
                }
                return List.of(p);
                """)
            .build();
    }

    /** Precompiled UUID-matcher field shared by resolveRelId (both templates) and
     *  runAtomicApply (apply template only), so the pattern is compiled once per JVM run
     *  instead of on every relationship check. */
    private static FieldSpec uuidPatternField() {
        return FieldSpec.builder(PATTERN_CN, "UUID_PATTERN")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.compile($S)", PATTERN_CN,
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            .build();
    }

    private static MethodSpec isUuidMethod() {
        return MethodSpec.methodBuilder("isUuid")
            .addJavadoc("Whether the value looks like a UUID (used to skip a natural-key lookup).\n")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(String.class, "value")
            .addStatement("return UUID_PATTERN.matcher(value).matches()")
            .build();
    }

    private static MethodSpec resolveRelIdMethod() {
        return MethodSpec.methodBuilder("resolveRelId")
            .addJavadoc("""
                Resolve a relationship value to an ID.
                If the value looks like a UUID, use it directly.
                Otherwise, check the in-run cache, then fall back to an API lookup by natural key —
                caching the result so N records referencing the same existing parent cost one lookup.
                """)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(String.class, "value")
            .addParameter(String.class, "targetPath")
            .addParameter(String.class, "targetKey")
            .addParameter(ParameterizedTypeName.get(ClassName.get(Map.class),
                ClassName.get(String.class), ClassName.get(String.class)), "cache")
            .addParameter(String.class, "versionPrefix")
            .addParameter(API_CLIENT, "client")
            .addException(Exception.class)
            .addCode("""
                if (isUuid(value)) return value;
                String cached = cache.get(targetPath + ":" + value);
                if (cached != null) return cached;
                String looked = lookupId(value, targetKey, targetPath, versionPrefix, client);
                if (looked != null) cache.put(targetPath + ":" + value, looked);
                return looked;
                """)
            .build();
    }

    private static MethodSpec lookupIdMethod() {
        return MethodSpec.methodBuilder("lookupId")
            .addJavadoc("Search the API for a resource matching naturalKeyField=value, return its id or null.\n")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(String.class, "value")
            .addParameter(String.class, "naturalKeyField")
            .addParameter(String.class, "entityPath")
            .addParameter(String.class, "versionPrefix")
            .addParameter(API_CLIENT, "client")
            .addException(Exception.class)
            .addCode("""
                if (value == null || naturalKeyField.equals("id")) return value;
                String escaped = value.replace("'", "\\\\'");
                String encoded = java.net.URLEncoder.encode(naturalKeyField + "=='" + escaped + "'", java.nio.charset.StandardCharsets.UTF_8);
                try {
                    com.fasterxml.jackson.databind.JsonNode resp = client.get(versionPrefix + "/" + entityPath + "?filter=" + encoded);
                    if (resp != null && resp.has("data") && resp.get("data").isArray() && resp.get("data").size() > 0) {
                        return resp.get("data").get(0).path("id").asText(null);
                    }
                } catch (Exception ex) {
                    System.err.println("  [WARN] lookup failed for " + entityPath + " " + naturalKeyField + "=" + value + ": "
                        + (ex.getMessage() != null ? ex.getMessage() : ex.toString()));
                }
                return null;
                """)
            .build();
    }

    private static MethodSpec lookupIdAndEtagMethod() {
        return MethodSpec.methodBuilder("lookupIdAndEtag")
            .addJavadoc("""
                Same lookup as lookupId(), but also extracts the resource's optimistic-locking
                version (quoted as an ETag, e.g. `"3"`) from the same filter response — avoiding a
                second GET for callers (upsert, delete-file's natural-key path) that need both.
                Returns {id, etag} with either element possibly null (no match / no version).
                """)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String[].class)
            .addParameter(String.class, "value")
            .addParameter(String.class, "naturalKeyField")
            .addParameter(String.class, "entityPath")
            .addParameter(String.class, "versionPrefix")
            .addParameter(API_CLIENT, "client")
            .addException(Exception.class)
            .addCode("""
                if (value == null || naturalKeyField.equals("id")) return new String[]{value, null};
                String escaped = value.replace("'", "\\\\'");
                String encoded = java.net.URLEncoder.encode(naturalKeyField + "=='" + escaped + "'", java.nio.charset.StandardCharsets.UTF_8);
                try {
                    com.fasterxml.jackson.databind.JsonNode resp = client.get(versionPrefix + "/" + entityPath + "?filter=" + encoded);
                    if (resp != null && resp.has("data") && resp.get("data").isArray() && resp.get("data").size() > 0) {
                        com.fasterxml.jackson.databind.JsonNode item = resp.get("data").get(0);
                        String id = item.path("id").asText(null);
                        com.fasterxml.jackson.databind.JsonNode version = item.path("attributes").path("version");
                        String etag = (!version.isMissingNode() && !version.isNull()) ? "\\"" + version.asText() + "\\"" : null;
                        return new String[]{id, etag};
                    }
                } catch (Exception ex) {
                    System.err.println("  [WARN] lookup failed for " + entityPath + " " + naturalKeyField + "=" + value + ": "
                        + (ex.getMessage() != null ? ex.getMessage() : ex.toString()));
                }
                return new String[]{null, null};
                """)
            .build();
    }

    private static MethodSpec fetchEtagMethod() {
        return MethodSpec.methodBuilder("fetchEtag")
            .addJavadoc("""
                Fetch the current optimistic-locking version for a resource, quoted as an ETag
                (e.g. `"3"`), for use as If-Match on a subsequent update/delete. Returns null for
                entities with no `version` attribute (no optimistic locking) or on any lookup
                failure — callers pass a null etag through unchanged, same as omitting --if-match.
                Used only for the explicit-id path (delete-file's `id:` records); the natural-key
                path uses lookupIdAndEtag() instead so it doesn't re-GET what it just fetched.
                """)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(String.class, "entityPath")
            .addParameter(String.class, "id")
            .addParameter(String.class, "versionPrefix")
            .addParameter(API_CLIENT, "client")
            .addCode("""
                try {
                    com.fasterxml.jackson.databind.JsonNode resp = client.get(versionPrefix + "/" + entityPath + "/" + id);
                    if (resp != null && resp.has("data")) {
                        com.fasterxml.jackson.databind.JsonNode version = resp.get("data").path("attributes").path("version");
                        if (!version.isMissingNode() && !version.isNull()) {
                            return "\\"" + version.asText() + "\\"";
                        }
                    }
                } catch (Exception ignored) {}
                return null;
                """)
            .build();
    }

    private static String applyCreateLoop() {
        return """
                for (Path f : files) {
                    System.out.println("Applying " + f + "...");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> doc = yaml.readValue(f.toFile(), Map.class);
                            if (doc == null) continue;

                            for (Map.Entry<String, Object> section : doc.entrySet()) {
                                String entityPath = section.getKey();
                                FileOps.EntitySpec spec = FileOps.ENTITY_SPECS.get(entityPath);
                                if (spec == null) {
                                    System.err.println("  [WARN] Unknown entity type '" + entityPath + "' — skipping");
                                    continue;
                                }
                                if (!spec.naturalKeyUnique() && !dryRun && warnedNaturalKey.add(entityPath)) {
                                    System.err.printf("  [WARN] %s' natural key '%s' is not declared unique in the manifest — "
                                        + "duplicate detection (skip-on-conflict / --upsert) will not work reliably for this entity.%n",
                                        entityPath, spec.naturalKey());
                                }
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> records = (List<Map<String, Object>>) section.getValue();
                                if (records == null) continue;

                                int idx = 0;
                                for (Map<String, Object> record : records) {
                                    total++;
                                    String label = entityPath + "[" + idx++ + "]";
                                    Object refVal = record.get("_ref");
                                    Object naturalVal = record.get(spec.naturalKey());
                                    String displayName = refVal != null ? refVal.toString()
                                        : naturalVal != null ? naturalVal.toString() : label;
                                    try {
                                        if (dryRun) {
                                            System.out.println("  [DRY-RUN] would create " + label + "  " + displayName);
                                            success++;
                                            continue;
                                        }
                                        // Build JSON:API body
                                        Map<String, Object> attrs = new LinkedHashMap<>();
                                        Map<String, Object> rels = new LinkedHashMap<>();
                                        boolean relFailed = false;
                                        for (Map.Entry<String, Object> field : record.entrySet()) {
                                            String key = field.getKey();
                                            Object val = field.getValue();
                                            if (val == null) continue;
                                            FileOps.FieldSpec fs = spec.fields().get(key);
                                            if (fs == null) { /* extra fields ignored */ continue; }
                                            if (fs.isRel()) {
                                                if (!fs.manyToOne()) continue; // OneToMany: skip, set via child
                                                String relId = FileOps.resolveRelId(val.toString(), fs.targetPath(), fs.naturalKey(), created, versionPrefix, client);
                                                if (relId == null) {
                                                    System.err.printf("  [WARN]  %s  %s → cannot resolve relationship '%s' = '%s'%n", label, displayName, key, val);
                                                    relFailed = true;
                                                    break;
                                                }
                                                Map<String, Object> rd = new LinkedHashMap<>();
                                                rd.put("type", fs.targetPath()); rd.put("id", relId);
                                                rels.put(key, Map.of("data", rd));
                                            } else {
                                                attrs.put(key, val);
                                            }
                                        }
                                        if (relFailed) {
                                            // Unresolvable relationship — skip the whole record (it would
                                            // otherwise be POSTed without the relationship and double-counted).
                                            failed++;
                                            if (!continueOnError) {
                                                System.err.println("Stopping. Use --continue-on-error to keep going.");
                                                System.exit(1);
                                            }
                                            continue;
                                        }
                                        Map<String, Object> data = new LinkedHashMap<>();
                                        data.put("type", entityPath);
                                        data.put("attributes", attrs);
                                        if (!rels.isEmpty()) data.put("relationships", rels);
                                        Map<String, Object> body = Map.of("data", data);

                                        com.fasterxml.jackson.databind.JsonNode result = null;
                                        try {
                                            result = client.post(versionPrefix + "/" + entityPath, body);
                                        } catch (RuntimeException ex) {
                                            String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                                            boolean isConflict = msg.contains("409") || msg.contains("423")
                                                || msg.contains("already exists") || msg.contains("Unique")
                                                || msg.toLowerCase().contains("duplicate key");
                                            if (isConflict) {
                                                if (upsert) {
                                                    String naturalLookupVal = naturalVal != null ? naturalVal.toString() : null;
                                                    if (naturalLookupVal == null) {
                                                        System.out.printf("  ~ %s  %s  [skipped — cannot upsert without a natural key value]%n", label, displayName);
                                                        skipped++; continue;
                                                    }
                                                    // Find existing and PATCH it — one lookup gets both the id
                                                    // and its current version, avoiding a redundant fetchEtag GET.
                                                    String[] idAndEtag = FileOps.lookupIdAndEtag(naturalLookupVal, spec.naturalKey(), entityPath, versionPrefix, client);
                                                    String existId = idAndEtag[0];
                                                    if (existId != null) {
                                                        Map<String, Object> uData = new LinkedHashMap<>();
                                                        uData.put("type", entityPath); uData.put("id", existId);
                                                        uData.put("attributes", attrs);
                                                        if (!rels.isEmpty()) uData.put("relationships", rels);
                                                        client.patch(versionPrefix + "/" + entityPath + "/" + existId, Map.of("data", uData), idAndEtag[1]);
                                                        System.out.printf("  ✓ %s  %s  → %s  [updated]%n", label, displayName, existId);
                                                        created.put(entityPath + ":" + displayName, existId);
                                                        success++; continue;
                                                    }
                                                }
                                                System.out.printf("  ~ %s  %s  [skipped — already exists]%n", label, displayName);
                                                skipped++; continue;
                                            }
                                            throw ex;
                                        }

                                        if (result != null && result.has("data")) {
                                            String id = result.get("data").path("id").asText(null);
                                            System.out.printf("  ✓ %s  %s  → %s%n", label, displayName, id);
                                            if (id != null && naturalVal != null)
                                                created.put(entityPath + ":" + displayName, id);
                                            success++;
                                        } else {
                                            System.out.printf("  ? %s  %s  (no id in response)%n", label, displayName);
                                            success++;
                                        }
                                    } catch (Exception ex) {
                                        String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                                        System.err.printf("  ✗ %s  %s  → %s%n", label, displayName, msg);
                                        failed++;
                                        if (!continueOnError) {
                                            System.err.println("Stopping. Use --continue-on-error to keep going.");
                                            System.exit(1);
                                        }
                                    }
                                }
                            }
                        }
            """;
    }

    private static String applyDeleteLoop() {
        return """
                        // Collect all records first (so we can confirm total before acting)
                        List<Map.Entry<String, Map<String, Object>>> toDelete = new java.util.ArrayList<>();
                        com.fasterxml.jackson.databind.ObjectMapper yaml =
                            new com.fasterxml.jackson.databind.ObjectMapper(
                                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
                        for (Path f : files) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> doc = yaml.readValue(f.toFile(), Map.class);
                            if (doc == null) continue;
                            // Reverse iteration within each file so dependents are deleted before parents
                            List<Map.Entry<String, Object>> sections = new java.util.ArrayList<>(doc.entrySet());
                            Collections.reverse(sections);
                            for (Map.Entry<String, Object> section : sections) {
                                String entityPath = section.getKey();
                                FileOps.EntitySpec spec = FileOps.ENTITY_SPECS.get(entityPath);
                                if (spec == null) continue;
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> records = (List<Map<String, Object>>) section.getValue();
                                if (records == null) continue;
                                List<Map<String, Object>> reversed = new java.util.ArrayList<>(records);
                                Collections.reverse(reversed);
                                for (Map<String, Object> record : reversed) {
                                    toDelete.add(Map.entry(entityPath, record));
                                }
                            }
                        }
                        if (toDelete.isEmpty()) { System.out.println("Nothing to delete."); return; }

                        if (!yes && !dryRun) {
                            System.out.print("Delete " + toDelete.size() + " resource(s)? [y/N] ");
                            System.out.flush();
                            String line = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
                            if (line == null || !line.trim().equalsIgnoreCase("y")) {
                                System.out.println("Aborted."); return;
                            }
                        }

                        int idx = 0;
                        for (Map.Entry<String, Map<String, Object>> entry : toDelete) {
                            total++;
                            String entityPath = entry.getKey();
                            Map<String, Object> record = entry.getValue();
                            FileOps.EntitySpec spec = FileOps.ENTITY_SPECS.get(entityPath);
                            String label = entityPath + "[" + idx++ + "]";
                            Object naturalVal = record.get(spec.naturalKey());
                            String displayName = naturalVal != null ? naturalVal.toString() : label;
                            try {
                                // Resolve ID: explicit 'id' field takes priority, then natural key lookup.
                                // The natural-key lookup also returns the current version in one GET
                                // (lookupIdAndEtag) instead of a second fetchEtag round-trip.
                                boolean explicitId = record.containsKey("id");
                                String id;
                                String etag = null;
                                if (explicitId) {
                                    id = record.get("id").toString();
                                } else {
                                    String[] idAndEtag = FileOps.lookupIdAndEtag(displayName, spec.naturalKey(), entityPath, versionPrefix, client);
                                    id = idAndEtag[0];
                                    etag = idAndEtag[1];
                                }
                                if (id == null) {
                                    System.out.printf("  ~ %s  %s  [not found — skipped]%n", label, displayName);
                                    skipped++; continue;
                                }
                                if (dryRun) {
                                    System.out.printf("  [DRY-RUN] would delete %s  %s  (id=%s)%n", label, displayName, id);
                                    success++; continue;
                                }
                                if (explicitId) {
                                    etag = FileOps.fetchEtag(entityPath, id, versionPrefix, client);
                                }
                                client.delete(versionPrefix + "/" + entityPath + "/" + id, etag);
                                System.out.printf("  ✓ %s  %s  (id=%s)%n", label, displayName, id);
                                success++;
                            } catch (Exception ex) {
                                String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                                System.err.printf("  ✗ %s  %s  → %s%n", label, displayName, msg);
                                failed++;
                                if (!continueOnError) {
                                    System.err.println("Stopping. Use --continue-on-error to keep going.");
                                    System.exit(1);
                                }
                            }
                        }
            """;
    }

}
