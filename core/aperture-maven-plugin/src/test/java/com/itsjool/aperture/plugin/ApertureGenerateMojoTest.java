package com.itsjool.aperture.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            kind: FrameworkConfig
            metadata: { name: framework }
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
            kind: FrameworkConfig
            metadata: { name: framework }
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
            kind: FrameworkConfig
            metadata: { name: framework }
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
            kind: FrameworkConfig
            metadata: { name: framework }
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

    private static void writeManifest(Path directory, String name, String contents) throws Exception {
        Files.writeString(directory.resolve(name), contents);
    }
}
