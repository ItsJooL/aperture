package com.itsjool.aperture.cli.generator;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.diff.DiffResult;
import com.itsjool.aperture.engine.model.CliConfig;
import com.itsjool.aperture.engine.model.FrameworkConfigDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import com.itsjool.aperture.cli.spi.AuthPaths;
import com.itsjool.aperture.cli.spi.CliAuthExtension;
import com.itsjool.aperture.cli.spi.CliCommandContribution;
import com.itsjool.aperture.generation.spi.ApertureGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CliGenerationTargetTest {

    @TempDir
    Path tempDir;

    @Test
    void disabledByDefault_doesNotGenerate() {
        CliGenerationTarget target = new CliGenerationTarget(false);
        ApertureGenerationRequest request = minimalRequest();
        assertThat(target.enabled(request)).isFalse();
    }

    @Test
    void whenEnabled_generatesMavenProject() throws Exception {
        Path allocDir = tempDir.resolve("generated");
        Files.createDirectories(allocDir);

        CliGenerationTarget target = new CliGenerationTarget(true);
        ApertureGenerationRequest request = minimalRequest();

        target.generate(request, testContext(allocDir));

        Path cliRoot = allocDir.resolve("generated-cli/aperture-cli");
        assertThat(cliRoot.resolve("pom.xml")).exists();
        assertThat(cliRoot.resolve("src/main/java/com/itsjool/aperture/cli/ApertureCli.java")).exists();
        assertThat(cliRoot.resolve("src/main/java/com/itsjool/aperture/cli/GlobalOptions.java")).exists();
        assertThat(cliRoot.resolve("src/main/java/com/itsjool/aperture/cli/cmd/VersionCommand.java")).exists();
        assertThat(cliRoot.resolve("src/main/resources/aperture-cli-version.properties")).exists();
        assertThat(cliRoot.resolve("src/main/resources/META-INF/native-image/com.itsjool/aperture-cli/reflect-config.json")).exists();

        String pomContent = Files.readString(cliRoot.resolve("pom.xml"));
        assertThat(pomContent).contains("picocli.version>4.7.7");
        assertThat(pomContent).contains("jackson.version>2.19.1");
        assertThat(pomContent).contains("native.maven.plugin.version>1.1.3");
        assertThat(pomContent).contains("com.itsjool.aperture.cli.ApertureCli");
    }

    @Test
    void generatedPom_containsNativeProfile() throws Exception {
        Path allocDir = tempDir.resolve("generated2");
        Files.createDirectories(allocDir);

        CliGenerationTarget target = new CliGenerationTarget(true);
        target.generate(minimalRequest(), testContext(allocDir));

        String pom = Files.readString(allocDir.resolve("generated-cli/aperture-cli/pom.xml"));
        assertThat(pom).contains("<id>native</id>");
        assertThat(pom).contains("native-maven-plugin");
        assertThat(pom).contains("<imageName>aperture</imageName>");
    }

    @Test
    void customBinaryName_appearsInPomAndMainCommand() throws Exception {
        Path allocDir = tempDir.resolve("generated-custom");
        Files.createDirectories(allocDir);

        CliGenerationTarget target = new CliGenerationTarget(true);
        target.generate(requestWithBinaryName("myapp"), testContext(allocDir));

        Path cliRoot = allocDir.resolve("generated-cli/aperture-cli");
        String pom = Files.readString(cliRoot.resolve("pom.xml"));
        assertThat(pom).contains("<imageName>myapp</imageName>");

        String main = Files.readString(cliRoot.resolve("src/main/java/com/itsjool/aperture/cli/ApertureCli.java"));
        assertThat(main).contains("name = \"myapp\"");
    }

    @Test
    void generatedProject_containsSimpleAuthCommand() throws Exception {
        Path allocDir = tempDir.resolve("generated-auth");
        Files.createDirectories(allocDir);

        CliGenerationTarget target = new CliGenerationTarget(true);
        target.generate(minimalRequest(), testContext(allocDir));

        Path authCmd = allocDir.resolve(
            "generated-cli/aperture-cli/src/main/java/com/itsjool/aperture/cli/cmd/SimpleAuthCommand.java");
        assertThat(authCmd).exists();
        String content = Files.readString(authCmd);
        assertThat(content).contains("class LoginCommand");
        assertThat(content).contains("class RefreshCommand");
        assertThat(content).contains("class LogoutCommand");
        assertThat(content).contains("class MeCommand");
    }

    @Test
    void authCommandUsesExtensionProvidedPaths() throws Exception {
        CliAuthExtension custom = new CliAuthExtension() {
            @Override public String id() { return "custom"; }
            @Override public AuthPaths authPaths() {
                return new AuthPaths("/idp/signin", "/idp/refresh", "/idp/signout",
                    "/idp/whoami", "/idp/token", "/idp/me/keys");
            }
        };

        Path root = generateProject(List.of(custom));
        String auth = Files.readString(root.resolve(
            "src/main/java/com/itsjool/aperture/cli/cmd/SimpleAuthCommand.java"));

        assertThat(auth)
            .contains("/idp/signin")
            .contains("/idp/refresh")
            .contains("/idp/signout")
            .contains("/idp/whoami")
            .doesNotContain("/auth/login");
    }

    @Test
    void authCommandDefaultsToSimpleAuthPathsWhenNoExtensionConfigured() throws Exception {
        Path root = generateProject(List.of());
        String auth = Files.readString(root.resolve(
            "src/main/java/com/itsjool/aperture/cli/cmd/SimpleAuthCommand.java"));

        assertThat(auth)
            .contains("/auth/login")
            .contains("/auth/refresh")
            .contains("/auth/logout")
            .contains("/auth/me");
    }

    @Test
    void commandContribution_writesSourceFileAndRegistersAsSubcommand() throws Exception {
        Path allocDir = Files.createDirectories(tempDir.resolve("generated-command-contribution"));
        CliCommandContribution stub = stubContribution("stub-command", "StatusCommand", "System.out.println(\"stub-marker\");");

        CliGenerationTarget target = new CliGenerationTarget(true, List.of(), List.of(stub));
        target.generate(minimalRequest(), testContext(allocDir));

        Path cliRoot = allocDir.resolve("generated-cli/aperture-cli");
        Path commandFile = cliRoot.resolve("src/main/java/com/itsjool/aperture/cli/cmd/StatusCommand.java");
        assertThat(commandFile).exists();
        assertThat(Files.readString(commandFile))
            .contains("package com.itsjool.aperture.cli.cmd;")
            .contains("class StatusCommand")
            .contains("stub-marker");

        String main = Files.readString(cliRoot.resolve("src/main/java/com/itsjool/aperture/cli/ApertureCli.java"));
        assertThat(main).contains("StatusCommand.class");
    }

    @Test
    void commandContribution_invalidClassNameFailsLoudlyWithContributionId() {
        Path allocDir = tempDir.resolve("generated-command-contribution-bad-name");
        CliCommandContribution stub = stubContribution("bad-id", "not a valid class name", "");

        CliGenerationTarget target = new CliGenerationTarget(true, List.of(), List.of(stub));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> target.generate(minimalRequest(), testContext(allocDir)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bad-id");
    }

    @Test
    void commandContribution_wrongPackageFailsLoudlyWithContributionId() {
        Path allocDir = tempDir.resolve("generated-command-contribution-bad-package");
        CliCommandContribution stub = new CliCommandContribution() {
            @Override public String id() { return "wrong-package"; }
            @Override public String commandClassName() { return "StatusCommand"; }
            @Override public String commandSource(String binaryName) {
                return "package com.itsjool.aperture.cli.other;\npublic class StatusCommand implements Runnable { public void run() {} }\n";
            }
        };

        CliGenerationTarget target = new CliGenerationTarget(true, List.of(), List.of(stub));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> target.generate(minimalRequest(), testContext(allocDir)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("wrong-package");
    }

    @Test
    void authCommandSourceOverride_writesReplacementSkipsSimpleAuthAndRegistersClass() throws Exception {
        CliAuthExtension oidc = authSourceExtension(
            "oidc-test", "OidcAuthCommand", """
                package com.itsjool.aperture.cli.cmd;

                import picocli.CommandLine.Command;

                @Command(name = "auth", description = "OIDC auth")
                public class OidcAuthCommand implements Runnable {
                    @Override public void run() {
                        System.out.println("oidc auth for @BINARY@");
                    }
                }
                """);

        Path root = generateProject(List.of(oidc));

        assertThat(root.resolve("src/main/java/com/itsjool/aperture/cli/cmd/OidcAuthCommand.java"))
            .exists().content().contains("oidc auth for aperture");
        assertThat(root.resolve("src/main/java/com/itsjool/aperture/cli/cmd/SimpleAuthCommand.java"))
            .doesNotExist();
        String main = Files.readString(root.resolve("src/main/java/com/itsjool/aperture/cli/ApertureCli.java"));
        assertThat(main)
            .contains("OidcAuthCommand.class")
            .doesNotContain("SimpleAuthCommand.class");
    }

    @Test
    void authCommandSourceOverride_invalidClassNameFailsLoudlyWithExtensionId() {
        CliAuthExtension oidc = authSourceExtension(
            "bad-auth-name", "not a valid class name", """
                package com.itsjool.aperture.cli.cmd;
                public class IgnoredAuthCommand implements Runnable { public void run() {} }
                """);

        CliGenerationTarget target = new CliGenerationTarget(true, List.of(oidc));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> target.generate(minimalRequest(), testContext(tempDir.resolve("generated-bad-auth-name"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bad-auth-name");
    }

    @Test
    void authCommandSourceOverride_wrongPackageFailsLoudlyWithExtensionId() {
        CliAuthExtension oidc = authSourceExtension(
            "bad-auth-package", "OidcAuthCommand", """
                package com.itsjool.aperture.cli.other;
                public class OidcAuthCommand implements Runnable { public void run() {} }
                """);

        CliGenerationTarget target = new CliGenerationTarget(true, List.of(oidc));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> target.generate(minimalRequest(), testContext(tempDir.resolve("generated-bad-auth-package"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bad-auth-package");
    }

    private CliAuthExtension authSourceExtension(String id, String className, String source) {
        return new CliAuthExtension() {
            @Override public String id() { return id; }
            @Override public AuthPaths authPaths() {
                return new AuthPaths("/unused-login", "/unused-refresh", "/unused-logout",
                    "/unused-me", "/unused-token", "/unused-api-keys");
            }
            @Override public String authCommandSource(String binaryName) {
                return source.replace("@BINARY@", binaryName);
            }
            @Override public String authCommandClassName() { return className; }
        };
    }

    private CliCommandContribution stubContribution(String id, String className, String runBody) {
        return new CliCommandContribution() {
            @Override public String id() { return id; }
            @Override public String commandClassName() { return className; }
            @Override public String commandSource(String binaryName) {
                return """
                    package com.itsjool.aperture.cli.cmd;

                    import picocli.CommandLine.Command;

                    @Command(name = "status", description = "stub")
                    public class %s implements Runnable {
                        @Override public void run() { %s }
                    }
                    """.formatted(className, runBody);
            }
        };
    }

    @Test
    void generatesApplyCommandAndSharedFileOps() throws Exception {
        Path allocDir = tempDir.resolve("generated-apply-delete");
        Files.createDirectories(allocDir);

        CliGenerationTarget target = new CliGenerationTarget(true);
        target.generate(minimalRequest(), testContext(allocDir));

        Path cliRoot = allocDir.resolve("generated-cli/aperture-cli");
        String apply = Files.readString(cliRoot.resolve(
            "src/main/java/com/itsjool/aperture/cli/cmd/ApplyCommand.java"));
        String fileOps = Files.readString(cliRoot.resolve(
            "src/main/java/com/itsjool/aperture/cli/cmd/FileOps.java"));
        String main = Files.readString(cliRoot.resolve(
            "src/main/java/com/itsjool/aperture/cli/ApertureCli.java"));

        assertThat(apply)
            .contains("name = \"apply\"")
            .contains("void runAtomicApply(")
            .contains("FileOps.resolveFiles(")
            .contains("FileOps.resolveRelId(")
            .contains("FileOps.lookupId(");
        // FileOps carries the registry and lookup machinery shared by ApplyCommand and the -f
        // paths of Create/Update/DeleteCommand — there is no more standalone DeleteFileCommand.
        assertThat(fileOps)
            .contains("class FileOps")
            .contains("ENTITY_SPECS")
            .contains("resolveFiles(")
            .contains("resolveRelId(")
            .contains("lookupIdAndEtag(")
            .contains("fetchEtag(");
        assertThat(cliRoot.resolve(
            "src/main/java/com/itsjool/aperture/cli/cmd/DeleteFileCommand.java")).doesNotExist();
        assertThat(main)
            .contains("ApplyCommand.class")
            .contains("GetCommand.class")
            .contains("CreateCommand.class")
            .contains("UpdateCommand.class")
            .contains("DeleteCommand.class")
            .doesNotContain("DeleteFileCommand.class");
    }

    @Test
    void generatedMainCommand_importsAndNameCorrect() throws Exception {
        Path allocDir = tempDir.resolve("generated3");
        Files.createDirectories(allocDir);

        CliGenerationTarget target = new CliGenerationTarget(true);
        target.generate(minimalRequest(), testContext(allocDir));

        String main = Files.readString(allocDir.resolve(
            "generated-cli/aperture-cli/src/main/java/com/itsjool/aperture/cli/ApertureCli.java"));
        assertThat(main).contains("import picocli.CommandLine");
        assertThat(main).contains("name = \"aperture\"");
        assertThat(main).contains("System.exit(exit)");
    }

    private Path generateProject(List<CliAuthExtension> authExtensions) throws Exception {
        Path allocDir = Files.createDirectories(tempDir.resolve("generated-auth-extension"));
        CliGenerationTarget target = new CliGenerationTarget(true, authExtensions);
        target.generate(minimalRequest(), testContext(allocDir));
        return allocDir.resolve("generated-cli/aperture-cli");
    }

    private ApertureGenerationContext testContext(Path allocDir) {
        return new ApertureGenerationContext() {
            @Override public void writeJavaSource(String pkg, String cls, String source) {}
            @Override public void writeResource(Path rel, String content) throws java.io.IOException {
                Path dest = allocDir.resolve(rel);
                Files.createDirectories(dest.getParent());
                Files.writeString(dest, content);
            }
            @Override public void writeLock(String fileName, String content) {}
            @Override public Path allocateTargetDirectory(String classifier) throws java.io.IOException {
                Path dir = allocDir.resolve(classifier);
                Files.createDirectories(dir);
                return dir;
            }
        };
    }

    @Test
    void defaultApiVersion_fallsBackToHighestServedVersion_whenNoneMarkedActive() throws Exception {
        // minimalRequest() declares no ApiVersionConfig at all, just activeVersions = ["1"]
        Path allocDir = tempDir.resolve("generated-default-version-fallback");
        Files.createDirectories(allocDir);

        CliGenerationTarget target = new CliGenerationTarget(true);
        target.generate(minimalRequest(), testContext(allocDir));

        String globalOptions = Files.readString(
            allocDir.resolve("generated-cli/aperture-cli/src/main/java/com/itsjool/aperture/cli/GlobalOptions.java"));
        assertThat(globalOptions).contains("DEFAULT_API_VERSION = \"1\";");
    }

    @Test
    void defaultApiVersion_prefersExplicitActiveStatus_overHighestServedNumber() throws Exception {
        // Versions 1/2/3 all served, but only 2 is marked ACTIVE — a not-yet-promoted
        // higher number (3) should NOT win just because it's the biggest.
        Path allocDir = tempDir.resolve("generated-default-version-active");
        Files.createDirectories(allocDir);

        CliGenerationTarget target = new CliGenerationTarget(true);
        target.generate(requestWithApiVersions(
            Map.of("1", "SUNSET", "2", "ACTIVE", "3", "SUNSET"), List.of("1", "2", "3")), testContext(allocDir));

        String globalOptions = Files.readString(
            allocDir.resolve("generated-cli/aperture-cli/src/main/java/com/itsjool/aperture/cli/GlobalOptions.java"));
        assertThat(globalOptions).contains("DEFAULT_API_VERSION = \"2\";");
    }

    @Test
    void defaultApiVersion_isNull_whenNoVersionsDeclaredAtAll() throws Exception {
        Path allocDir = tempDir.resolve("generated-default-version-none");
        Files.createDirectories(allocDir);

        CliGenerationTarget target = new CliGenerationTarget(true);
        target.generate(requestWithApiVersions(Map.of(), List.of()), testContext(allocDir));

        String globalOptions = Files.readString(
            allocDir.resolve("generated-cli/aperture-cli/src/main/java/com/itsjool/aperture/cli/GlobalOptions.java"));
        assertThat(globalOptions).contains("DEFAULT_API_VERSION = null;");
    }

    private ApertureGenerationRequest minimalRequest() {
        return requestWithBinaryName("aperture");
    }

    private ApertureGenerationRequest requestWithBinaryName(String binaryName) {
        FrameworkConfigDef framework = new FrameworkConfigDef(
            List.of(), TenancyMode.POOL, null, new CliConfig(binaryName));
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(), List.of(), framework, List.of(), List.of(), List.of());
        ResolvedDomainModel previousModel = new ResolvedDomainModel(List.of());
        DiffResult diff = new DiffResult(
            List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of(),
            com.itsjool.aperture.engine.diff.ChangeType.SAFE, Map.of());
        return new ApertureGenerationRequest(
            model, previousModel, diff, List.of("1"), TenancyMode.POOL, tempDir);
    }

    private ApertureGenerationRequest requestWithApiVersions(Map<String, String> versionsAndStatus, List<String> activeVersions) {
        FrameworkConfigDef framework = new FrameworkConfigDef(
            List.of(), TenancyMode.POOL, null, new CliConfig("aperture"));
        Map<String, com.itsjool.aperture.engine.model.ApiVersionDef> versions = new java.util.LinkedHashMap<>();
        versionsAndStatus.forEach((version, status) ->
            versions.put(version, new com.itsjool.aperture.engine.model.ApiVersionDef(status)));
        List<com.itsjool.aperture.engine.model.ApiVersionConfigDef> apiVersionConfigs =
            versions.isEmpty() ? List.of() : List.of(new com.itsjool.aperture.engine.model.ApiVersionConfigDef(versions));
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(), List.of(), framework, List.of(), List.of(), apiVersionConfigs);
        ResolvedDomainModel previousModel = new ResolvedDomainModel(List.of());
        DiffResult diff = new DiffResult(
            List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of(),
            com.itsjool.aperture.engine.diff.ChangeType.SAFE, Map.of());
        return new ApertureGenerationRequest(
            model, previousModel, diff, activeVersions, TenancyMode.POOL, tempDir);
    }
}
