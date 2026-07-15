package com.itsjool.aperture.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.cli.oidc.OidcDeviceCodeCliExtension;
import com.itsjool.aperture.cli.spi.AuthPaths;
import com.itsjool.aperture.cli.spi.CliAuthExtension;
import com.itsjool.aperture.cli.spi.CliCommandContribution;
import com.itsjool.aperture.mcp.spi.McpToolContribution;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApertureGenerateMojoTest {
    @TempDir
    Path tempDir;

    @Test
    void generatesCompleteBuildOutputAndRegistersItWithMaven() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests"));
        Path sources = tempDir.resolve("generated-sources");
        Path resources = tempDir.resolve("generated-resources");
        Path locks = tempDir.resolve("locks");
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Customer }
            spec:
              plural: customers
              tenantScoped: true
              optimisticLocking: true
              fields:
                name: { type: string, required: true }
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: SUNSET }
                "2": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: [OrgAdmin, Viewer]
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles:
                OrgAdmin: { description: Full access }
                Viewer: { description: Read only }
            """);
        writeManifest(manifests, "migration.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Migration
            metadata: { name: seed-customer }
            spec:
              sql: INSERT INTO aperture_customers (id, name) VALUES ('00000000-0000-0000-0000-000000000001', 'Example');
              rollback: DELETE FROM aperture_customers WHERE id = '00000000-0000-0000-0000-000000000001';
            """);
        MavenProject project = new MavenProject();
        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(project);
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());

        mojo.execute();

        assertThat(sources.resolve("com/itsjool/aperture/generated/v1/CustomerV1.java")).exists();
        assertThat(sources.resolve("com/itsjool/aperture/generated/v2/CustomerV2.java")).exists();
        Path changelog = resources.resolve("db/changelog");
        assertThat(changelog.resolve("aperture-framework-tables.xml")).content()
            .contains("<createTable tableName=\"aperture_tenants\">");
        assertThat(changelog.resolve("aperture-schema.xml")).content()
            .contains("aperture_customers", "<rollback>");
        assertThat(changelog.resolve("manual/seed-customer.xml")).content()
            .contains("INSERT INTO aperture_customers", "DELETE FROM aperture_customers");
        assertThat(changelog.resolve("db.changelog-master.xml")).content()
            .contains("aperture-schema.xml", "manual/seed-customer.xml");
        JsonNode metadata = new ObjectMapper().readTree(
            resources.resolve("aperture/aperture-runtime-metadata.json").toFile());
        assertThat(metadata.path("activeVersions")).extracting(JsonNode::asText)
            .containsExactly("1", "2");
        assertThat(metadata.path("defaultRoles")).extracting(JsonNode::asText)
            .containsExactly("OrgAdmin", "Viewer");
        assertThat(metadata.path("declaredRoles")).extracting(JsonNode::asText)
            .containsExactly("OrgAdmin", "SuperAdmin", "TenantAdmin", "Viewer");
        assertThat(locks.resolve("1-Customer.json")).exists();
        assertThat(project.getCompileSourceRoots()).contains(sources.toAbsolutePath().toString());
        assertThat(project.getResources()).anySatisfy(resource ->
            assertThat(((Resource) resource).getDirectory()).isEqualTo(resources.toAbsolutePath().toString()));
    }

    @Test
    void rejectsManifestsWithoutAServedApiVersion() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-without-versions"));
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Customer }
            spec:
              fields:
                name: { type: string }
            """);
        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(tempDir.resolve("missing-version-sources").toFile());
        mojo.setGeneratedResourcesDirectory(tempDir.resolve("missing-version-resources").toFile());
        mojo.setLockDirectory(tempDir.resolve("missing-version-locks").toFile());

        assertThatThrownBy(mojo::execute)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("At least one ACTIVE or SUNSET API version");
    }

    @Test
    void allowedMethods_entityWithAllOperations_includesAllVerbs() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-all-ops"));
        Path sources = tempDir.resolve("sources-all-ops");
        Path resources = tempDir.resolve("resources-all-ops");
        Path locks = tempDir.resolve("locks-all-ops");
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Product }
            spec:
              fields:
                name: { type: string, required: true }
              permissions:
                Admin: [create, read, update, delete]
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: [Admin]
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles:
                Admin: { description: Admin }
            """);
        MavenProject project = new MavenProject();
        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(project);
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        mojo.execute();

        Path metadataFile = resources.resolve("aperture/aperture-runtime-metadata.json");
        JsonNode metadata = new ObjectMapper().readTree(metadataFile.toFile());
        JsonNode methods = metadata.get("allowedHttpMethods");
        assertThat(methods).isNotNull();
        java.util.Set<String> methodSet = new java.util.HashSet<>();
        methods.forEach(m -> methodSet.add(m.asText()));
        assertThat(methodSet).contains("GET", "POST", "PATCH", "DELETE", "OPTIONS");
    }

    @Test
    void allowedMethods_readOnlyEntity_excludesPostPatchDelete() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-readonly"));
        Path sources = tempDir.resolve("sources-readonly");
        Path resources = tempDir.resolve("resources-readonly");
        Path locks = tempDir.resolve("locks-readonly");
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Report }
            spec:
              fields:
                title: { type: string, required: true }
              permissions:
                Viewer: [read]
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: [Viewer]
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles:
                Viewer: { description: Viewer }
            """);
        MavenProject project = new MavenProject();
        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(project);
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        mojo.execute();

        Path metadataFile = resources.resolve("aperture/aperture-runtime-metadata.json");
        JsonNode metadata = new ObjectMapper().readTree(metadataFile.toFile());
        java.util.Set<String> methodSet = new java.util.HashSet<>();
        metadata.get("allowedHttpMethods").forEach(m -> methodSet.add(m.asText()));
        assertThat(methodSet).contains("GET", "OPTIONS");
        assertThat(methodSet).doesNotContain("POST", "PATCH", "DELETE");
    }

    @Test
    void allowedMethods_publicOperationsIncluded() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-public"));
        Path sources = tempDir.resolve("sources-public");
        Path resources = tempDir.resolve("resources-public");
        Path locks = tempDir.resolve("locks-public");
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Catalog }
            spec:
              fields:
                name: { type: string, required: true }
              publicOperations: [read]
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: []
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles: {}
            """);
        MavenProject project = new MavenProject();
        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(project);
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        mojo.execute();

        Path metadataFile = resources.resolve("aperture/aperture-runtime-metadata.json");
        JsonNode metadata = new ObjectMapper().readTree(metadataFile.toFile());
        java.util.Set<String> methodSet = new java.util.HashSet<>();
        metadata.get("allowedHttpMethods").forEach(m -> methodSet.add(m.asText()));
        assertThat(methodSet).contains("GET", "OPTIONS");
    }

    @Test
    void generatesAdminSecurityChecks() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-admin-checks"));
        Path sources = tempDir.resolve("sources-admin-checks");
        Path resources = tempDir.resolve("resources-admin-checks");
        Path locks = tempDir.resolve("locks-admin-checks");
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Order }
            spec:
              fields:
                total: { type: decimal, required: true }
              permissions:
                Admin: [create, read, update, delete]
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: [Admin]
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles:
                Admin: { description: Administrator }
            """);
        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        mojo.execute();

        assertThat(sources.resolve("com/itsjool/aperture/generated/security/SuperAdminCheck.java"))
            .exists().content().contains("SuperAdminCheck");
        assertThat(sources.resolve("com/itsjool/aperture/generated/security/TenantAdminCheck.java"))
            .exists().content().contains("TenantAdminCheck");
    }

    @Test
    void generatesAbacPolicyCheck() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-abac"));
        Path sources = tempDir.resolve("sources-abac");
        Path resources = tempDir.resolve("resources-abac");
        Path locks = tempDir.resolve("locks-abac");
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Document }
            spec:
              fields:
                title: { type: string, required: true }
              permissions:
                Editor: [create, read, update]
              policies:
                EuRegionOnly: [read]
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: [Editor]
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles:
                Editor: { description: Editor }
            """);
        writeManifest(manifests, "principal-attributes.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: PrincipalAttributeDefinition
            metadata: { name: principal-attributes }
            spec:
              securityAttributes:
                region:
                  type: string
            """);
        writeManifest(manifests, "policy.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: AbacPolicy
            metadata: { name: EuRegionOnly }
            spec:
              expression: "#user.securityAttributes['region'] == 'eu'"
            """);
        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        mojo.execute();

        assertThat(sources.resolve("com/itsjool/aperture/generated/security/EuRegionOnlyCheck.java"))
            .exists().content().contains("EuRegionOnlyCheck");
    }

    @Test
    void mcpToolGenerationRunsWhenEnabled() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-mcp-on"));
        Path sources = tempDir.resolve("sources-mcp-on");
        Path resources = tempDir.resolve("resources-mcp-on");
        Path locks = tempDir.resolve("locks-mcp-on");
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Widget }
            spec:
              fields:
                name: { type: string, required: true }
              permissions:
                Admin: [create, read, update, delete]
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: [Admin]
              mcp:
                enabled: true
                serverName: widget-server
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles:
                Admin: { description: Admin }
            """);
        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        mojo.execute();

        assertThat(sources.resolve("com/itsjool/aperture/generated/mcp/WidgetV1McpTools.java"))
            .exists().content().contains("WidgetV1McpTools");
    }

    @Test
    void mcpToolGenerationSkippedWhenDisabled() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-mcp-off"));
        Path sources = tempDir.resolve("sources-mcp-off");
        Path resources = tempDir.resolve("resources-mcp-off");
        Path locks = tempDir.resolve("locks-mcp-off");
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Gadget }
            spec:
              fields:
                name: { type: string, required: true }
              permissions:
                Admin: [create, read, update, delete]
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: [Admin]
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles:
                Admin: { description: Admin }
            """);
        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        mojo.execute();

        try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(sources)) {
            assertThat(walk.filter(p -> p.toString().contains("McpTool")).toList()).isEmpty();
        }
    }

    @Test
    void mcpToolGenerationFailsForInvalidToolName() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-mcp-invalid-tool"));
        Path sources = tempDir.resolve("sources-mcp-invalid-tool");
        Path resources = tempDir.resolve("resources-mcp-invalid-tool");
        Path locks = tempDir.resolve("locks-mcp-invalid-tool");
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Widget }
            spec:
              fields:
                name: { type: string, required: true }
              permissions:
                Admin: [create, read, update, delete]
              mcp:
                tools: [list, publish]
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: [Admin]
              mcp:
                enabled: true
                transport: stateless
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles:
                Admin: { description: Admin }
            """);
        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());

        assertThatThrownBy(mojo::execute)
            .hasMessageContaining("Generation failed")
            .hasStackTraceContaining("$.spec.mcp.tools[1]")
            .hasStackTraceContaining("enumeration");
    }

    @Test
    void mcpToolContributionExtensionReachesGeneratedProject() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-mcp-contribution"));
        Path sources = tempDir.resolve("sources-mcp-contribution");
        Path resources = tempDir.resolve("resources-mcp-contribution");
        Path locks = tempDir.resolve("locks-mcp-contribution");
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Widget }
            spec:
              fields:
                name: { type: string, required: true }
              permissions:
                Admin: [create, read, update, delete]
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: [Admin]
              mcp:
                enabled: true
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles:
                Admin: { description: Admin }
            """);

        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        ApertureGenerateMojo.McpPluginConfig mcp = new ApertureGenerateMojo.McpPluginConfig();
        mcp.setExtensions(java.util.List.of(StubToolContribution.class.getName()));
        mojo.setMcp(mcp);

        mojo.execute();

        // The generated entity tool class and the SPI contribution land in the same package.
        assertThat(sources.resolve("com/itsjool/aperture/generated/mcp/WidgetV1McpTools.java")).exists();
        Path contributionFile = sources.resolve("com/itsjool/aperture/generated/mcp/StubMcpTools.java");
        assertThat(contributionFile).exists().content()
            .contains("package com.itsjool.aperture.generated.mcp;")
            .contains("public class StubMcpTools")
            .contains("latestApiVersion=1");
    }

    @Test
    void mcpExtensionNotImplementingInterfaceFailsWithMojoExecutionException() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-mcp-bad-extension"));
        Path sources = tempDir.resolve("sources-mcp-bad-extension");
        Path resources = tempDir.resolve("resources-mcp-bad-extension");
        Path locks = tempDir.resolve("locks-mcp-bad-extension");
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Widget }
            spec:
              fields:
                name: { type: string, required: true }
              permissions:
                Admin: [create, read, update, delete]
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: [Admin]
              mcp:
                enabled: true
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles:
                Admin: { description: Admin }
            """);

        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        ApertureGenerateMojo.McpPluginConfig mcp = new ApertureGenerateMojo.McpPluginConfig();
        mcp.setExtensions(java.util.List.of(NotAnExtension.class.getName()));
        mojo.setMcp(mcp);

        assertThatThrownBy(mojo::execute)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("does not implement McpToolContribution");
    }

    @Test
    void generatesOpenApiYaml() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-oas"));
        Path sources = tempDir.resolve("sources-oas");
        Path resources = tempDir.resolve("resources-oas");
        Path locks = tempDir.resolve("locks-oas");
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Item }
            spec:
              fields:
                label: { type: string, required: true }
              permissions:
                Admin: [create, read, update, delete]
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: [Admin]
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles:
                Admin: { description: Admin }
            """);
        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        mojo.execute();

        assertThat(resources.resolve("aperture-openapi.yaml"))
            .exists().content().contains("openapi:", "items");
    }

    @Test
    void cliCommandContributionExtensionReachesGeneratedCliProject() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-command-contribution"));
        Path sources = tempDir.resolve("sources-command-contribution");
        Path resources = tempDir.resolve("resources-command-contribution");
        Path locks = tempDir.resolve("locks-command-contribution");
        writeStatusManifests(manifests);

        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        ApertureGenerateMojo.CliConfig cli = new ApertureGenerateMojo.CliConfig();
        cli.setEnabled(true);
        cli.setExtensions(java.util.List.of(StubCommandContribution.class.getName()));
        mojo.setCli(cli);

        mojo.execute();

        // The cli target directory is allocated relative to the resources build directory's
        // parent (see StagingGenerationContext.allocateTargetDirectory), i.e. resources.getParent().
        Path cliRoot = resources.getParent().resolve("generated-cli/aperture-cli");
        Path commandFile = cliRoot.resolve(
            "src/main/java/com/itsjool/aperture/cli/cmd/StubStatusCommand.java");
        assertThat(commandFile).exists().content().contains("package com.itsjool.aperture.cli.cmd;");

        String main = Files.readString(cliRoot.resolve(
            "src/main/java/com/itsjool/aperture/cli/ApertureCli.java"));
        assertThat(main).contains("StubStatusCommand.class");
    }

    @Test
    void extensionClassImplementingNeitherInterfaceFailsWithMojoExecutionException() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-bad-extension"));
        Path sources = tempDir.resolve("sources-bad-extension");
        Path resources = tempDir.resolve("resources-bad-extension");
        Path locks = tempDir.resolve("locks-bad-extension");
        writeStatusManifests(manifests);

        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        ApertureGenerateMojo.CliConfig cli = new ApertureGenerateMojo.CliConfig();
        cli.setEnabled(true);
        cli.setExtensions(java.util.List.of(NotAnExtension.class.getName()));
        mojo.setCli(cli);

        assertThatThrownBy(mojo::execute)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("does not implement CliAuthExtension or CliCommandContribution");
    }

    @Test
    void multipleCliAuthExtensionsFailWithMojoExecutionException() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-multiple-auth-extensions"));
        Path sources = tempDir.resolve("sources-multiple-auth-extensions");
        Path resources = tempDir.resolve("resources-multiple-auth-extensions");
        Path locks = tempDir.resolve("locks-multiple-auth-extensions");
        writeStatusManifests(manifests);

        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        ApertureGenerateMojo.CliConfig cli = new ApertureGenerateMojo.CliConfig();
        cli.setEnabled(true);
        cli.setExtensions(java.util.List.of(StubAuthExtensionA.class.getName(), StubAuthExtensionB.class.getName()));
        mojo.setCli(cli);

        assertThatThrownBy(mojo::execute)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("Only one CliAuthExtension");
    }

    @Test
    void oidcDeviceCodeCliAuthExtensionReachesGeneratedCliProjectAsRealTierTwoAuth() throws Exception {
        Path manifests = Files.createDirectories(tempDir.resolve("manifests-oidc-auth-extension"));
        Path sources = tempDir.resolve("sources-oidc-auth-extension");
        Path resources = tempDir.resolve("resources-oidc-auth-extension");
        Path locks = tempDir.resolve("locks-oidc-auth-extension");
        writeStatusManifests(manifests);

        ApertureGenerateMojo mojo = new ApertureGenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
        ApertureGenerateMojo.CliConfig cli = new ApertureGenerateMojo.CliConfig();
        cli.setEnabled(true);
        cli.setExtensions(java.util.List.of(OidcDeviceCodeCliExtension.class.getName()));
        mojo.setCli(cli);

        mojo.execute();

        // The cli target directory is allocated relative to the resources build directory's
        // parent (see StagingGenerationContext.allocateTargetDirectory), i.e. resources.getParent().
        Path cliRoot = resources.getParent().resolve("generated-cli/aperture-cli");
        Path authCommandFile = cliRoot.resolve("src/main/java/com/itsjool/aperture/cli/cmd/AuthCommand.java");
        assertThat(authCommandFile).exists().content()
            .contains("package com.itsjool.aperture.cli.cmd;")
            .contains("urn:ietf:params:oauth:grant-type:device_code")
            .contains("device_authorization_endpoint")
            .contains("authorization_pending");
        assertThat(cliRoot.resolve("src/main/java/com/itsjool/aperture/cli/cmd/SimpleAuthCommand.java"))
            .doesNotExist();

        String main = Files.readString(cliRoot.resolve(
            "src/main/java/com/itsjool/aperture/cli/ApertureCli.java"));
        assertThat(main).contains("AuthCommand.class");
    }

    private void writeStatusManifests(Path manifests) throws Exception {
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Customer }
            spec:
              fields:
                name: { type: string, required: true }
              permissions:
                Admin: [create, read, update, delete]
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);
        writeManifest(manifests, "framework.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApertureConfig
            metadata: { name: aperture }
            spec:
              defaultRoles: [Admin]
            """);
        writeManifest(manifests, "roles.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata: { name: roles }
            spec:
              roles:
                Admin: { description: Admin }
            """);
    }

    /** Test-only stub — a real implementation would live in its own artifact, same as
     *  {@code SimpleStatusCliContribution} in aperture-simple-cli. */
    public static class StubCommandContribution implements CliCommandContribution {
        @Override public String id() { return "stub-status"; }
        @Override public String commandClassName() { return "StubStatusCommand"; }
        @Override public String commandSource(String binaryName) {
            return """
                package com.itsjool.aperture.cli.cmd;

                import picocli.CommandLine.Command;

                @Command(name = "stub-status", description = "stub")
                public class StubStatusCommand implements Runnable {
                    @Override public void run() { System.out.println("stub"); }
                }
                """;
        }
    }

    /** Test-only stub — a real implementation would live in its own artifact. */
    public static class StubToolContribution implements McpToolContribution {
        @Override public String id() { return "stub-mcp-tools"; }
        @Override public String toolClassName() { return "StubMcpTools"; }
        @Override public String toolSource(String latestApiVersion) {
            return """
                package com.itsjool.aperture.generated.mcp;

                import org.springframework.stereotype.Component;
                import org.springframework.ai.tool.annotation.Tool;

                @Component
                public class StubMcpTools {
                    @Tool(name = "stub_tool", description = "stub, latestApiVersion=%s")
                    public String stubTool() { return "stub"; }
                }
                """.formatted(latestApiVersion);
        }
    }

    public static class StubAuthExtensionA implements CliAuthExtension {
        @Override public String id() { return "stub-auth-a"; }
        @Override public AuthPaths authPaths() { return authPathsFor("/a"); }
    }

    public static class StubAuthExtensionB implements CliAuthExtension {
        @Override public String id() { return "stub-auth-b"; }
        @Override public AuthPaths authPaths() { return authPathsFor("/b"); }
    }

    private static AuthPaths authPathsFor(String prefix) {
        return new AuthPaths(prefix + "/login", prefix + "/refresh", prefix + "/logout",
            prefix + "/me", prefix + "/token", prefix + "/api-keys");
    }

    /** Test-only stub implementing neither {@code CliAuthExtension} nor
     *  {@code CliCommandContribution} — exercises the mojo's misconfiguration guard. */
    public static class NotAnExtension {
    }

    private static void writeManifest(Path directory, String name, String contents) throws Exception {
        Files.writeString(directory.resolve(name), contents);
    }
}
