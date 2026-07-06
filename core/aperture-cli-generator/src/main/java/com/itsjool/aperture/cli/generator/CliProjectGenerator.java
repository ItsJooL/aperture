package com.itsjool.aperture.cli.generator;

import com.itsjool.aperture.cli.spi.AuthPaths;
import com.itsjool.aperture.cli.spi.CliAuthExtension;
import com.itsjool.aperture.cli.spi.CliCommandContribution;
import com.itsjool.aperture.engine.model.ApiVersionConfigDef;
import com.itsjool.aperture.engine.model.ApiVersionDef;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;

import javax.lang.model.SourceVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes the standalone Maven project for the generated CLI under {@code cliProjectRoot}.
 */
class CliProjectGenerator {

    private static final Pattern COMMAND_PACKAGE_PATTERN =
        Pattern.compile("^\\s*package\\s+com\\.itsjool\\.aperture\\.cli\\.cmd\\s*;", Pattern.MULTILINE);

    private final ApertureGenerationRequest request;
    private final Path root;
    private final List<CliAuthExtension> authExtensions;
    private final List<CliCommandContribution> commandContributions;

    CliProjectGenerator(ApertureGenerationRequest request, Path root) {
        this(request, root, List.of(), List.of());
    }

    CliProjectGenerator(ApertureGenerationRequest request, Path root, List<CliAuthExtension> authExtensions) {
        this(request, root, authExtensions, List.of());
    }

    CliProjectGenerator(
            ApertureGenerationRequest request,
            Path root,
            List<CliAuthExtension> authExtensions,
            List<CliCommandContribution> commandContributions) {
        this.request = request;
        this.root = root;
        this.authExtensions = List.copyOf(authExtensions);
        this.commandContributions = List.copyOf(commandContributions);
    }

    void generate() throws Exception {
        String binaryName = request.model().frameworkConfig().cli().binaryName();
        AuthCommandSpec authCommand = authCommandSpec(binaryName);
        List<String> contributedCommandClassNames = validatedContributionClassNames();
        writePom(binaryName);
        writeMainCommand(binaryName, authCommand);
        writeEntityCommands(authCommand.className(), contributedCommandClassNames);
        writeCommandContributions(binaryName);
        writeVersionProvider(binaryName);
        writeNativeImageConfig();
    }

    /**
     * Validates each contribution's declared class name up front (before any files are written)
     * so a misconfigured extension fails loudly and by id, rather than producing a broken
     * generated project or an obscure javac error later.
     */
    private List<String> validatedContributionClassNames() {
        List<String> names = new ArrayList<>();
        for (CliCommandContribution contribution : commandContributions) {
            String className = contribution.commandClassName();
            if (className == null || !SourceVersion.isIdentifier(className) || SourceVersion.isKeyword(className)) {
                throw new IllegalStateException("CLI command contribution '" + contribution.id()
                    + "' returned an invalid Java class name: " + className);
            }
            names.add(className);
        }
        return names;
    }

    private AuthCommandSpec authCommandSpec(String binaryName) {
        if (!authExtensions.isEmpty()) {
            CliAuthExtension extension = authExtensions.getFirst();
            String source = extension.authCommandSource(binaryName);
            if (source != null) {
                String className = extension.authCommandClassName();
                if (className == null || !SourceVersion.isIdentifier(className) || SourceVersion.isKeyword(className)) {
                    throw new IllegalStateException("CLI auth extension '" + extension.id()
                        + "' returned an invalid Java class name: " + className);
                }
                if (!COMMAND_PACKAGE_PATTERN.matcher(source).find()) {
                    throw new IllegalStateException("CLI auth extension '" + extension.id()
                        + "' (class " + className + ") must declare package com.itsjool.aperture.cli.cmd");
                }
                return new AuthCommandSpec(className, source);
            }
        }
        return new AuthCommandSpec("SimpleAuthCommand", CliTemplates.simpleAuthCommand(binaryName, authPaths()));
    }

    private void writeCommandContributions(String binaryName) throws Exception {
        String src = "src/main/java/com/itsjool/aperture/cli/cmd";
        for (CliCommandContribution contribution : commandContributions) {
            String className = contribution.commandClassName();
            String source = contribution.commandSource(binaryName);
            if (source == null || !COMMAND_PACKAGE_PATTERN.matcher(source).find()) {
                throw new IllegalStateException("CLI command contribution '" + contribution.id()
                    + "' (class " + className + ") must declare package com.itsjool.aperture.cli.cmd");
            }
            write(root.resolve(src + "/" + className + ".java"), source);
        }
    }

    private void writePom(String binaryName) throws Exception {
        write(root.resolve("pom.xml"), CliTemplates.pom(binaryName));
    }

    /**
     * The manifest's ACTIVE API version, for use as the CLI's compiled-in default when
     * neither --api-version nor a profile's pinned version is set. Prefers the version
     * explicitly marked status: ACTIVE over just taking the highest served number, since
     * those can diverge (e.g. a not-yet-promoted or rolled-back highest version). Returns
     * null if the manifest declares no ApiVersionConfig at all (unversioned API).
     */
    private String computeDefaultApiVersion() {
        for (ApiVersionConfigDef config : request.model().apiVersionConfigs()) {
            for (Map.Entry<String, ApiVersionDef> entry : config.versions().entrySet()) {
                if (entry.getValue() != null && "ACTIVE".equalsIgnoreCase(entry.getValue().status())) {
                    return entry.getKey();
                }
            }
        }
        return request.activeVersions().isEmpty() ? null : request.activeVersions().getLast();
    }

    private void writeMainCommand(String binaryName, AuthCommandSpec authCommand) throws Exception {
        String src = "src/main/java/com/itsjool/aperture/cli";
        write(root.resolve(src + "/GlobalOptions.java"), CliTemplates.globalOptions(computeDefaultApiVersion()));
        write(root.resolve(src + "/config/ProfileConfig.java"), CliTemplates.profileConfig());
        write(root.resolve(src + "/config/ConfigStore.java"), CliTemplates.configStore(binaryName));
        write(root.resolve(src + "/http/ApiClient.java"), CliTemplates.apiClient());
        write(root.resolve(src + "/cmd/VersionCommand.java"), CliTemplates.versionCommand(binaryName));
        write(root.resolve(src + "/cmd/" + authCommand.className() + ".java"), authCommand.source());
        write(root.resolve(src + "/cmd/ConfigCommand.java"), CliTemplates.configCommand(binaryName));
        write(root.resolve(src + "/cmd/OutputFormatter.java"), CliTemplates.outputFormatter());
    }

    private AuthPaths authPaths() {
        if (!authExtensions.isEmpty()) {
            return authExtensions.getFirst().authPaths();
        }
        return new AuthPaths(
            "/auth/login",
            "/auth/refresh",
            "/auth/logout",
            "/auth/me",
            "/auth/token",
            "/auth/me/api-keys"
        );
    }

    private void writeEntityCommands(String authCommandClassName, List<String> contributedCommandClassNames) throws Exception {
        String src = "src/main/java/com/itsjool/aperture/cli/cmd";
        List<EntityDef> entities = request.model().entities().stream()
            .sorted(Comparator.comparing(EntityDef::name)).toList();
        // entityName(lowercase) -> resourcePath, so relationship JSON:API types honor a custom
        // plural (e.g. Currency -> "currencies") instead of naively appending "s" to the name.
        Map<String, String> resourcePathsByEntity = new HashMap<>();
        for (EntityDef e : request.model().entities()) {
            String path = e.plural() != null ? e.plural().toLowerCase() : e.name().toLowerCase() + "s";
            resourcePathsByEntity.put(e.name().toLowerCase(), path);
        }
        EntityCommandGenerator gen = new EntityCommandGenerator(entities, resourcePathsByEntity);
        write(root.resolve(src + "/GetCommand.java"), gen.generateGetCommand());
        write(root.resolve(src + "/CreateCommand.java"), gen.generateCreateCommand());
        write(root.resolve(src + "/UpdateCommand.java"), gen.generateUpdateCommand());
        write(root.resolve(src + "/DeleteCommand.java"), gen.generateDeleteCommand());
        String binaryName = request.model().frameworkConfig().cli().binaryName();
        // Write apply command and the shared file-mode machinery (FileOps) with the full entity
        // registry. FileOps is reused by ApplyCommand and the -f paths of Create/Update/Delete.
        write(root.resolve(src + "/ApplyCommand.java"), CliTemplates.applyCommand(entities, binaryName));
        write(root.resolve(src + "/FileOps.java"), CliTemplates.fileOps(entities));
        // ApertureCli.java registers the fixed verb set + contributed commands; it no longer grows
        // per entity, but is still written here (once) rather than in writeMainCommand, which runs
        // before contributed command class names are validated.
        write(root.resolve("src/main/java/com/itsjool/aperture/cli/ApertureCli.java"),
            CliTemplates.mainCommandWithEntityCommands(binaryName, authCommandClassName, contributedCommandClassNames));
    }

    private void writeVersionProvider(String binaryName) throws Exception {
        String src = "src/main/java/com/itsjool/aperture/cli";
        write(root.resolve(src + "/VersionProvider.java"), CliTemplates.versionProvider(binaryName));
        String res = "src/main/resources";
        String version = request.activeVersions().isEmpty() ? "unversioned" : request.activeVersions().getLast();
        write(root.resolve(res + "/aperture-cli-version.properties"), "version=" + version + "\n");
    }

    private void writeNativeImageConfig() throws Exception {
        String nativeDir = "src/main/resources/META-INF/native-image/com.itsjool/aperture-cli";
        write(root.resolve(nativeDir + "/reflect-config.json"), "[]");
        write(root.resolve(nativeDir + "/resource-config.json"),
            """
            {
              "resources": {
                "includes": [
                  {"pattern": "aperture-cli-version.properties"}
                ]
              }
            }
            """);
    }

    private void write(Path target, String content) throws Exception {
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private record AuthCommandSpec(String className, String source) {
    }
}
