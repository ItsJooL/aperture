package com.itsjool.aperture.generation;

import com.itsjool.aperture.engine.diff.DiffEngine;
import com.itsjool.aperture.engine.lock.LockFileManager;
import com.itsjool.aperture.engine.model.ApiVersionDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import com.itsjool.aperture.engine.parser.ManifestParser;
import com.itsjool.aperture.engine.publication.PublicationManager;
import com.itsjool.aperture.generation.context.StagingGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import com.itsjool.aperture.generation.spi.ApertureGenerationTarget;
import com.itsjool.aperture.generation.target.EntityJavaGenerationTarget;
import com.itsjool.aperture.generation.target.LiquibaseGenerationTarget;
import com.itsjool.aperture.generation.target.McpJavaGenerationTarget;
import com.itsjool.aperture.generation.target.OpenApiGenerationTarget;
import com.itsjool.aperture.generation.target.RuntimeMetadataGenerationTarget;
import com.itsjool.aperture.generation.target.SecurityChecksGenerationTarget;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Orchestrates the full generation lifecycle: parse → diff → stage → run targets → publish.
 *
 * The Mojo becomes a thin adapter that builds a {@link GenerationOptions} and calls
 * {@link #generate(GenerationOptions, Consumer)}.
 */
public class GeneratorOrchestrator {

    private static final List<ApertureGenerationTarget> BUILT_IN_TARGETS = List.of(
        new SecurityChecksGenerationTarget(),
        new EntityJavaGenerationTarget(),
        new McpJavaGenerationTarget(),
        new LiquibaseGenerationTarget(),
        new RuntimeMetadataGenerationTarget(),
        new OpenApiGenerationTarget()
    );

    /**
     * Runs all built-in targets plus any {@code extensionTargets} provided by the caller.
     *
     * @param options         directories and configuration
     * @param logger          receives info-level progress lines (e.g. {@code getLog()::info})
     * @param extensionTargets additional targets registered via plugin configuration
     */
    public void generate(
            GenerationOptions options,
            Consumer<String> logger,
            List<ApertureGenerationTarget> extensionTargets) throws Exception {

        File manifestDirectory = options.manifestDirectory();
        File outputDirectory = options.outputDirectory();
        File generatedResourcesDirectory = options.generatedResourcesDirectory();
        File lockDirectory = options.lockDirectory();

        String uuid = UUID.randomUUID().toString();
        Path tempSourcesStaging = outputDirectory.toPath().getParent()
            .resolve(".staging-" + outputDirectory.getName() + "-" + uuid);
        Path tempResourcesStaging = generatedResourcesDirectory.toPath().getParent()
            .resolve(".staging-" + generatedResourcesDirectory.getName() + "-" + uuid);
        Path tempLocksStaging = lockDirectory.toPath().getParent()
            .resolve(".staging-" + lockDirectory.getName() + "-" + uuid);

        try {
            PublicationManager manager = new PublicationManager(lockDirectory.toPath());
            manager.recover();

            cleanOrphanedStagingFiles(outputDirectory.toPath().getParent());
            cleanOrphanedStagingFiles(generatedResourcesDirectory.toPath().getParent());
            cleanOrphanedStagingFiles(lockDirectory.toPath().getParent());

            ResolvedDomainModel model = new ManifestParser().parseDirectory(manifestDirectory);
            List<String> activeVersions = computeActiveVersions(model);
            LockFileManager lockManager = new LockFileManager();
            ResolvedDomainModel previousModel = new ResolvedDomainModel(lockDirectory.exists()
                ? lockManager.readAllLockFiles(lockDirectory.toPath()) : List.of());
            var diff = new DiffEngine().computeDiff(previousModel, model, activeVersions);
            if (diff.hasBreakingChanges()) {
                throw new IllegalStateException("Breaking changes detected without API version bump");
            }

            Files.createDirectories(tempSourcesStaging);
            Files.createDirectories(tempResourcesStaging);
            Files.createDirectories(tempLocksStaging);

            String reporterFile = System.getProperty("aperture.plugin.stagingReporterFile");
            if (reporterFile != null) {
                Files.writeString(Path.of(reporterFile),
                    tempSourcesStaging + "\n" + tempResourcesStaging + "\n" + tempLocksStaging + "\n");
            }

            ApertureGenerationRequest request = new ApertureGenerationRequest(
                model,
                previousModel,
                diff,
                activeVersions,
                model.frameworkConfig().tenancyMode(),
                options.projectBaseDirectory().toPath()
            );

            StagingGenerationContext context = new StagingGenerationContext(
                tempSourcesStaging, tempResourcesStaging, tempLocksStaging);

            List<ApertureGenerationTarget> allTargets = new ArrayList<>(BUILT_IN_TARGETS);
            if (extensionTargets != null) allTargets.addAll(extensionTargets);

            for (ApertureGenerationTarget target : allTargets) {
                if (target.enabled(request)) {
                    logger.accept("Aperture: running target [" + target.name() + "]");
                    target.generate(request, context);
                }
            }

            Set<Path> previousFiles = new HashSet<>();
            collectFiles(outputDirectory.toPath(), previousFiles);
            collectFiles(generatedResourcesDirectory.toPath().resolve("db/changelog"), previousFiles);
            collectFiles(generatedResourcesDirectory.toPath().resolve("aperture"), previousFiles);
            collectFiles(lockDirectory.toPath(), previousFiles);

            Set<Path> newFiles = new HashSet<>();
            List<Path> publicationTargets = new ArrayList<>();
            Map<Path, Path> stagingMap = new HashMap<>();

            collectTargets(tempSourcesStaging, outputDirectory.toPath(), newFiles, publicationTargets, stagingMap);
            collectTargets(tempResourcesStaging, generatedResourcesDirectory.toPath(), newFiles, publicationTargets, stagingMap);
            collectTargets(tempLocksStaging, lockDirectory.toPath(), newFiles, publicationTargets, stagingMap);

            for (Path prev : previousFiles) {
                if (!newFiles.contains(prev)) {
                    publicationTargets.add(prev);
                }
            }

            List<Path> normalTargets = new ArrayList<>();
            List<Path> lockFiles = new ArrayList<>();
            for (Path target : publicationTargets) {
                if (target.getParent() != null
                        && target.getParent().equals(lockDirectory.toPath())
                        && target.getFileName().toString().endsWith(".json")) {
                    lockFiles.add(target);
                } else {
                    normalTargets.add(target);
                }
            }

            String failureInjection = System.getProperty("aperture.plugin.injectFailureMidPublication");
            manager.publish(normalTargets, lockFiles, stagingMap, failureInjection);

        } finally {
            deleteDirectory(tempSourcesStaging);
            deleteDirectory(tempResourcesStaging);
            deleteDirectory(tempLocksStaging);
        }
    }

    /** Convenience overload for callers with no extension targets. */
    public void generate(GenerationOptions options, Consumer<String> logger) throws Exception {
        generate(options, logger, List.of());
    }

    private List<String> computeActiveVersions(ResolvedDomainModel model) {
        TreeSet<String> versions = new TreeSet<>(Comparator.comparingInt(Integer::parseInt));
        model.apiVersionConfigs().stream()
            .flatMap(config -> config.versions().entrySet().stream())
            .filter(e -> isServed(e.getValue()))
            .map(Map.Entry::getKey)
            .forEach(versions::add);
        if (versions.isEmpty()) {
            throw new IllegalStateException(
                "At least one ACTIVE or SUNSET API version must be declared");
        }
        return List.copyOf(versions);
    }

    private boolean isServed(ApiVersionDef version) {
        return version != null && version.status() != null
            && ("ACTIVE".equalsIgnoreCase(version.status())
                || "SUNSET".equalsIgnoreCase(version.status()));
    }

    private void collectFiles(Path dir, Set<Path> set) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile).forEach(set::add);
        }
    }

    private void collectTargets(Path tempDir, Path targetDir,
            Set<Path> newFiles, List<Path> allTargets, Map<Path, Path> stagingMap) throws Exception {
        if (!Files.exists(tempDir)) return;
        try (Stream<Path> s = Files.walk(tempDir)) {
            s.filter(Files::isRegularFile).forEach(tempFile -> {
                Path target = targetDir.resolve(tempDir.relativize(tempFile));
                newFiles.add(target);
                allTargets.add(target);
                stagingMap.put(target, tempFile);
            });
        }
    }

    private void cleanOrphanedStagingFiles(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir, 1)) {
            s.filter(p -> !p.equals(dir) && Files.isDirectory(p)
                    && p.getFileName().toString().startsWith(".staging-"))
             .forEach(this::deleteDirectory);
        } catch (Exception ignored) {}
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (Exception ignored) {}
    }
}
