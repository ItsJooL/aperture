package com.itsjool.aperture.plugin;

import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicationRecoveryIT {

    @TempDir
    Path tempDir;

    private Path manifests;
    private Path sources;
    private Path resources;
    private Path locks;
    private ApertureGenerateMojo mojo;

    @BeforeEach
    void setUp() throws Exception {
        manifests = Files.createDirectories(tempDir.resolve("manifests"));
        sources = tempDir.resolve("generated-sources");
        resources = tempDir.resolve("generated-resources");
        locks = tempDir.resolve("locks");

        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Customer }
            spec:
              fields:
                name: { type: string, required: true }
            """);
        writeManifest(manifests, "versions.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata: { name: versions }
            spec:
              versions:
                "1": { status: ACTIVE }
            """);

        MavenProject project = new MavenProject();
        mojo = new ApertureGenerateMojo();
        mojo.setProject(project);
        mojo.setManifestDirectory(manifests.toFile());
        mojo.setOutputDirectory(sources.toFile());
        mojo.setGeneratedResourcesDirectory(resources.toFile());
        mojo.setLockDirectory(locks.toFile());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("aperture.plugin.injectFailureMidPublication");
        System.clearProperty("aperture.plugin.stagingReporterFile");
    }

    @Test
    void normalExecutionLeavesNoStagingFiles() throws Exception {
        Path stagingReport = tempDir.resolve("staging-report.txt");
        System.setProperty("aperture.plugin.stagingReporterFile", stagingReport.toString());

        mojo.execute();

        Path generatedSource = sources.resolve("com/itsjool/aperture/generated/v1/CustomerV1.java");
        Path generatedLock = locks.resolve("1-Customer.json");
        
        assertThat(generatedSource).exists();
        assertThat(generatedLock).exists();

        // Assert lock file exists
        Path generatedResource = resources.resolve("db/changelog/aperture-schema.xml");
        assertThat(generatedResource).exists();

        String reportContent = Files.readString(stagingReport);
        assertThat(reportContent).contains(".staging-generated-sources");
        assertThat(reportContent).contains(".staging-generated-resources");
        assertThat(reportContent).contains(".staging-locks");

        assertNoStagingDirectories();
    }

    @Test
    void failureRecoversToPreviousState() throws Exception {
        // 1. Seed a valid prior output
        mojo.execute();
        
        // Verify initial state
        Path priorCustomerFile = sources.resolve("com/itsjool/aperture/generated/v1/CustomerV1.java");
        Path priorLockFile = locks.resolve("1-Customer.json");
        Path priorResourceFile = resources.resolve("db/changelog/aperture-schema.xml");
        assertThat(priorCustomerFile).exists();
        assertThat(priorLockFile).exists();
        assertThat(priorResourceFile).exists();

        // Save prior file hashes or contents to compare later
        String priorCustomerContent = Files.readString(priorCustomerFile);
        String priorLockContent = Files.readString(priorLockFile);
        String priorResourceContent = Files.readString(priorResourceFile);

        // Modify manifest to cause a change in the new generation
        writeManifest(manifests, "entity.yaml", """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata: { name: Customer }
            spec:
              fields:
                name: { type: string, required: true }
                email: { type: string, required: true }
            """);

        // 2. Inject failure AFTER a target has been moved to backup and staging moved to target
        System.setProperty("aperture.plugin.injectFailureMidPublication", "AFTER_TARGET_CustomerV1.java");

        // 3. Run and expect failure
        assertThatThrownBy(() -> mojo.execute())
            .hasRootCauseMessage("Simulated crash");

        System.clearProperty("aperture.plugin.injectFailureMidPublication");

        // 4. Recover
        com.itsjool.aperture.engine.publication.PublicationManager manager = 
            new com.itsjool.aperture.engine.publication.PublicationManager(locks);
        manager.recover();

        // 5. Assert previous output remains byte-for-byte unchanged
        assertThat(priorCustomerFile).exists();
        assertThat(Files.readString(priorCustomerFile)).isEqualTo(priorCustomerContent);
        
        assertThat(priorLockFile).exists();
        assertThat(Files.readString(priorLockFile)).isEqualTo(priorLockContent);

        assertThat(priorResourceFile).exists();
        assertThat(Files.readString(priorResourceFile)).isEqualTo(priorResourceContent);

        assertNoStagingDirectories();
    }

    private void assertNoStagingDirectories() {
        assertThat(sources.getParent().resolve(".staging-generated-sources")).doesNotExist();
        assertThat(resources.getParent().resolve(".staging-generated-resources")).doesNotExist();
        assertThat(locks.getParent().resolve(".staging-locks")).doesNotExist();
    }

    private void writeManifest(Path directory, String name, String contents) throws Exception {
        Files.writeString(directory.resolve(name), contents);
    }
}
