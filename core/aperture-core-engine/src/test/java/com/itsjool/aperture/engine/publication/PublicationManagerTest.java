package com.itsjool.aperture.engine.publication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicationManagerTest {

    private Path rootDir;
    private PublicationManager manager;
    private Path journalFile;

    @BeforeEach
    void setUp() throws IOException {
        rootDir = Files.createTempDirectory("aperture-test");
        manager = new PublicationManager(rootDir);
        journalFile = rootDir.resolveSibling(".aperture.journal");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(rootDir)
             .sorted((a, b) -> b.compareTo(a))
             .forEach(p -> {
                 try {
                     Files.delete(p);
                 } catch (IOException e) {
                     // ignore
                 }
             });
        Files.deleteIfExists(journalFile);
        Files.deleteIfExists(journalFile.resolveSibling(journalFile.getFileName() + ".tmp"));
    }

    @Test
    void testSuccessfulPublication() throws IOException {
        Path target1 = rootDir.resolve("file1.txt");
        Path lockFile = rootDir.resolve("aperture.lock");
        
        Files.writeString(target1.resolveSibling(target1.getFileName() + ".staging"), "new content 1");
        Files.writeString(lockFile.resolveSibling(lockFile.getFileName() + ".staging"), "lock content");

        // Target1 already exists
        Files.writeString(target1, "old content 1");

        java.util.Map<Path, Path> stagingMap = new java.util.HashMap<>();
        stagingMap.put(target1, target1.resolveSibling(target1.getFileName() + ".staging"));
        stagingMap.put(lockFile, lockFile.resolveSibling(lockFile.getFileName() + ".staging"));

        manager.publish(List.of(target1), List.of(lockFile), stagingMap, null);

        assertThat(target1).hasContent("new content 1");
        assertThat(lockFile).hasContent("lock content");

        // Backups and staging should be deleted
        assertThat(target1.resolveSibling(target1.getFileName() + ".backup")).doesNotExist();
        assertThat(target1.resolveSibling(target1.getFileName() + ".staging")).doesNotExist();
        assertThat(lockFile.resolveSibling(lockFile.getFileName() + ".backup")).doesNotExist();
        assertThat(lockFile.resolveSibling(lockFile.getFileName() + ".staging")).doesNotExist();
        
        // Journal deleted
        assertThat(journalFile).doesNotExist();
    }

    @Test
    void testRecoveryAfterFailureDuringTargetMove() throws IOException {
        Path target1 = rootDir.resolve("file1.txt");
        Path target2 = rootDir.resolve("file2.txt");
        
        Files.writeString(target1.resolveSibling(target1.getFileName() + ".staging"), "new content 1");
        Files.writeString(target2.resolveSibling(target2.getFileName() + ".staging"), "new content 2");

        Files.writeString(target1, "old content 1");
        Files.writeString(target2, "old content 2");

        java.util.Map<Path, Path> stagingMap = new java.util.HashMap<>();
        stagingMap.put(target1, target1.resolveSibling(target1.getFileName() + ".staging"));
        stagingMap.put(target2, target2.resolveSibling(target2.getFileName() + ".staging"));

        assertThatThrownBy(() -> manager.publish(List.of(target1, target2), null, stagingMap, "AFTER_TARGET_file1.txt"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated crash");

        // file1 was processed, file2 was not
        assertThat(target1).hasContent("new content 1");
        assertThat(target2).hasContent("old content 2");
        assertThat(journalFile).exists();

        // Recover
        manager.recover();

        // Targets should be restored to original
        assertThat(target1).hasContent("old content 1");
        assertThat(target2).hasContent("old content 2");
        
        // Staging and journal should be cleaned up
        assertThat(target1.resolveSibling(target1.getFileName() + ".staging")).doesNotExist();
        assertThat(target2.resolveSibling(target2.getFileName() + ".staging")).doesNotExist();
        assertThat(journalFile).doesNotExist();

        // Verify idempotency of recovery
        manager.recover();
        assertThat(target1).hasContent("old content 1");
        assertThat(target2).hasContent("old content 2");
    }

    @Test
    void testRecoveryForNewFiles() throws IOException {
        Path newTarget = rootDir.resolve("new-file.txt");
        Files.writeString(newTarget.resolveSibling(newTarget.getFileName() + ".staging"), "new content");

        java.util.Map<Path, Path> stagingMap = new java.util.HashMap<>();
        stagingMap.put(newTarget, newTarget.resolveSibling(newTarget.getFileName() + ".staging"));

        assertThatThrownBy(() -> manager.publish(List.of(newTarget), null, stagingMap, "AFTER_TARGET_new-file.txt"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated crash");

        // File was published
        assertThat(newTarget).hasContent("new content");
        
        // Recover
        manager.recover();

        // Since it's a new file (no backup), it should be deleted
        assertThat(newTarget).doesNotExist();
        assertThat(newTarget.resolveSibling(newTarget.getFileName() + ".staging")).doesNotExist();

        // Verify idempotency of recovery
        manager.recover();
        assertThat(newTarget).doesNotExist();
        assertThat(newTarget.resolveSibling(newTarget.getFileName() + ".staging")).doesNotExist();
    }

    @Test
    void testRecoveryAfterCommitFailure() throws IOException {
        Path target1 = rootDir.resolve("file1.txt");
        Files.writeString(target1.resolveSibling(target1.getFileName() + ".staging"), "new content 1");
        Files.writeString(target1, "old content 1");

        java.util.Map<Path, Path> stagingMap = new java.util.HashMap<>();
        stagingMap.put(target1, target1.resolveSibling(target1.getFileName() + ".staging"));

        assertThatThrownBy(() -> manager.publish(List.of(target1), null, stagingMap, "AFTER_COMMIT"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated crash");

        // Target1 should be fully moved and journal committed
        assertThat(target1).hasContent("new content 1");
        assertThat(journalFile).exists();
        assertThat(target1.resolveSibling(target1.getFileName() + ".backup")).exists();

        // Recover
        manager.recover();

        // Target1 still has new content, backup/staging deleted, journal deleted
        assertThat(target1).hasContent("new content 1");
        assertThat(target1.resolveSibling(target1.getFileName() + ".backup")).doesNotExist();
        assertThat(target1.resolveSibling(target1.getFileName() + ".staging")).doesNotExist();
        assertThat(journalFile).doesNotExist();

        // Verify idempotency of recovery
        manager.recover();
        assertThat(target1).hasContent("new content 1");
        assertThat(target1.resolveSibling(target1.getFileName() + ".backup")).doesNotExist();
        assertThat(target1.resolveSibling(target1.getFileName() + ".staging")).doesNotExist();
        assertThat(journalFile).doesNotExist();
    }
}
