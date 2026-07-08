package com.itsjool.aperture.engine.lock;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.HookDef;
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
}
