package com.itsjool.aperture.engine.lock;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.HookDef;
import com.itsjool.aperture.engine.model.OneOfDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LockFileManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void writesSemanticHookVocabularyToLockFile() throws Exception {
        EntityDef invoice = new EntityDef("Invoice", "invoices", null, null, false, false, true,
            Map.of(), Map.of(), Map.of(), List.of(), Map.of(),
            Map.of("ValidateInvoice", new HookDef("validate", List.of("create", "update"),
                null, "http://hook")));

        new LockFileManager().writeLockFile("1", invoice, tempDir);

        String lockJson = Files.readString(tempDir.resolve("1-Invoice.json"));
        assertThat(lockJson)
            .contains("\"type\":\"validate\"")
            .contains("\"on\":[\"create\",\"update\"]")
            .contains("\"url\":\"http://hook\"")
            .doesNotContain("\"phase\"")
            .doesNotContain("\"async\"");
    }

    @Test
    void writesAndReadsDomainModelLockFileWithOneOfs() throws Exception {
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(),
            List.of(new OneOfDef("Billable", List.of("Product", "ServicePackage"))));
        LockFileManager lockManager = new LockFileManager();

        lockManager.writeDomainModelLockFile("1", model, tempDir);

        Path lockFile = tempDir.resolve("1-domain-model.json");
        assertThat(Files.readString(lockFile))
            .contains("\"oneOfs\"")
            .contains("\"Billable\"")
            .contains("\"Product\"")
            .contains("\"ServicePackage\"");
        assertThat(lockManager.readDomainModelLockFile(lockFile).oneOfs())
            .singleElement()
            .satisfies(oneOf -> {
                assertThat(oneOf.name()).isEqualTo("Billable");
                assertThat(oneOf.members()).containsExactly("Product", "ServicePackage");
            });
    }

    @Test
    void writesDomainModelOneOfsSortedByNameWhilePreservingMemberOrder() throws Exception {
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(),
            List.of(
                new OneOfDef("Taxable", List.of("ServicePackage", "Product")),
                new OneOfDef("Billable", List.of("Product", "ServicePackage"))));

        LockFileManager lockManager = new LockFileManager();
        lockManager.writeDomainModelLockFile("1", model, tempDir);

        List<OneOfDef> oneOfs = lockManager
            .readDomainModelLockFile(tempDir.resolve("1-domain-model.json"))
            .oneOfs();
        assertThat(oneOfs).extracting(OneOfDef::name).containsExactly("Billable", "Taxable");
        assertThat(oneOfs.get(1).members()).containsExactly("ServicePackage", "Product");
    }

    @Test
    void skipsEmptyDomainModelLockFileWhenNoPriorLockExists() {
        ResolvedDomainModel model = new ResolvedDomainModel(List.of());

        new LockFileManager().writeDomainModelLockFile("1", model, tempDir);

        assertThat(tempDir.resolve("1-domain-model.json")).doesNotExist();
    }

    @Test
    void updatesExistingDomainModelLockFileToEmptyWhenOneOfsAreRemoved() throws Exception {
        Path lockFile = tempDir.resolve("1-domain-model.json");
        Files.writeString(lockFile, "{\"oneOfs\":[{\"name\":\"Billable\",\"members\":[\"Product\"]}]}");
        ResolvedDomainModel model = new ResolvedDomainModel(List.of());

        new LockFileManager().writeDomainModelLockFile("1", model, tempDir);

        assertThat(Files.readString(lockFile)).contains("\"oneOfs\":[]");
    }

    @Test
    void readsLockedDomainModelFromEntityAndDomainModelLockFiles() {
        EntityDef product = new EntityDef("Product", "products", null, null, false, false, false,
            Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of());
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(product), List.of(), null, List.of(), List.of(), List.of(), List.of(),
            List.of(new OneOfDef("Billable", List.of("Product", "ServicePackage"))));
        LockFileManager lockManager = new LockFileManager();
        lockManager.writeLockFile("1", product, tempDir);
        lockManager.writeDomainModelLockFile("1", model, tempDir);

        ResolvedDomainModel lockedModel = lockManager.readLockedDomainModel(tempDir);

        assertThat(lockedModel.entities()).extracting(EntityDef::name).containsExactly("Product");
        assertThat(lockedModel.oneOfs()).extracting(OneOfDef::name).containsExactly("Billable");
    }
}
