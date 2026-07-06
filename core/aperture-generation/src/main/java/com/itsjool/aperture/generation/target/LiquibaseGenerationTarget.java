package com.itsjool.aperture.generation.target;

import com.itsjool.aperture.changeset.ChangesetGenerator;
import com.itsjool.aperture.generation.spi.ApertureGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import com.itsjool.aperture.generation.spi.ApertureGenerationTarget;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class LiquibaseGenerationTarget implements ApertureGenerationTarget {

    @Override
    public String name() {
        return "liquibase";
    }

    @Override
    public boolean enabled(ApertureGenerationRequest request) {
        return true;
    }

    @Override
    public void generate(ApertureGenerationRequest request, ApertureGenerationContext context) throws Exception {
        ChangesetGenerator changesets = new ChangesetGenerator();
        Path changelogDir = Path.of("db/changelog");

        context.writeResource(changelogDir.resolve("manual/.gitkeep"), "");
        copyFrameworkChangelog(context, changelogDir);
        context.writeResource(changelogDir.resolve("aperture-schema.xml"),
            changesets.generateSchemaSnapshot(request.model(), request.tenancyMode()));

        var diff = request.diff();
        boolean hasIncremental = !diff.addedFields().isEmpty()
            || !diff.renamedFields().isEmpty()
            || !diff.deferredDrops().isEmpty();
        if (hasIncremental) {
            context.writeResource(changelogDir.resolve("aperture-incremental.xml"),
                changesets.generateGeneratedChangesets(diff, request.tenancyMode()));
        }
        for (var migration : request.model().migrations()) {
            context.writeResource(changelogDir.resolve("manual").resolve(migration.name() + ".xml"),
                changesets.generateManualMigration(migration));
        }
        context.writeResource(changelogDir.resolve("db.changelog-master.xml"),
            changesets.generateRootChangelog(request.model().migrations(), hasIncremental));
    }

    private void copyFrameworkChangelog(ApertureGenerationContext context, Path changelogDir) throws Exception {
        try (InputStream input = ChangesetGenerator.class.getResourceAsStream(
                "/db/changelog/aperture-framework-tables.xml")) {
            if (input == null) {
                throw new IllegalStateException(
                    "Framework Liquibase changelog is missing from aperture-changeset");
            }
            byte[] bytes = input.readAllBytes();
            context.writeResource(changelogDir.resolve("aperture-framework-tables.xml"),
                new String(bytes));
        }
    }
}
