package com.itsjool.aperture.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.changeset.ChangesetGenerator;
import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.diff.DiffEngine;
import com.itsjool.aperture.engine.gen.CodeGenerator;
import com.itsjool.aperture.engine.lock.LockFileManager;
import com.itsjool.aperture.engine.model.ApiVersionDef;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import com.itsjool.aperture.engine.parser.ManifestParser;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class ApertureGenerateMojo extends AbstractMojo {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package ([a-zA-Z0-9_.]+);");
    private static final Pattern CLASS_PATTERN = Pattern.compile("(?:public class|public interface|class) ([A-Za-z0-9_]+)");

    @Parameter(defaultValue = "${project.basedir}/manifests")
    private File manifestDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/aperture")
    private File outputDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-resources")
    private File generatedResourcesDirectory;

    @Parameter(defaultValue = "${project.basedir}/.aperture.lock")
    private File lockDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    public void setProject(MavenProject project) {
        this.project = project;
    }

    public void setManifestDirectory(File manifestDirectory) {
        this.manifestDirectory = manifestDirectory;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public void setGeneratedResourcesDirectory(File generatedResourcesDirectory) {
        this.generatedResourcesDirectory = generatedResourcesDirectory;
    }

    public void setLockDirectory(File lockDirectory) {
        this.lockDirectory = lockDirectory;
    }

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Aperture API Server: Generating sources and changesets");

        String uuid = java.util.UUID.randomUUID().toString();
        Path tempSourcesStaging = outputDirectory.toPath().getParent().resolve(".staging-" + outputDirectory.getName() + "-" + uuid);
        Path tempResourcesStaging = generatedResourcesDirectory.toPath().getParent().resolve(".staging-" + generatedResourcesDirectory.getName() + "-" + uuid);
        Path tempLocksStaging = lockDirectory.toPath().getParent().resolve(".staging-" + lockDirectory.getName() + "-" + uuid);

        try {
            com.itsjool.aperture.engine.publication.PublicationManager manager = 
                new com.itsjool.aperture.engine.publication.PublicationManager(lockDirectory.toPath());
            manager.recover();

            if (outputDirectory.toPath().getParent() != null) {
                cleanOrphanedStagingFiles(outputDirectory.toPath().getParent());
            }
            if (generatedResourcesDirectory.toPath().getParent() != null) {
                cleanOrphanedStagingFiles(generatedResourcesDirectory.toPath().getParent());
            }
            if (lockDirectory.toPath().getParent() != null) {
                cleanOrphanedStagingFiles(lockDirectory.toPath().getParent());
            }

            ResolvedDomainModel model = new ManifestParser().parseDirectory(manifestDirectory);
            List<String> activeVersions = activeVersions(model);
            LockFileManager lockManager = new LockFileManager();
            ResolvedDomainModel oldModel = new ResolvedDomainModel(lockDirectory.exists()
                ? lockManager.readAllLockFiles(lockDirectory.toPath()) : List.of());
            var diff = new DiffEngine().computeDiff(oldModel, model, activeVersions);
            if (diff.hasBreakingChanges()) {
                throw new MojoExecutionException("Breaking changes detected without API version bump");
            }
            TenancyMode tenancyMode = tenancyMode(model);

            Files.createDirectories(tempSourcesStaging);
            Files.createDirectories(tempResourcesStaging);
            Files.createDirectories(tempLocksStaging);
            
            String reporterFile = System.getProperty("aperture.plugin.stagingReporterFile");
            if (reporterFile != null) {
                Files.writeString(Path.of(reporterFile), 
                    tempSourcesStaging.toString() + "\n" + 
                    tempResourcesStaging.toString() + "\n" + 
                    tempLocksStaging.toString() + "\n");
            }

            CodeGenerator codeGenerator = new CodeGenerator();
            for (String source : codeGenerator.generateAdminChecks()) {
                writeSource(source, tempSourcesStaging);
            }
            if (model.abacPolicies() != null && !model.abacPolicies().isEmpty()) {
                for (String source : codeGenerator.generatePolicyChecks(model.abacPolicies())) {
                    writeSource(source, tempSourcesStaging);
                }
            }
            Map<String, EntityDef> allEntities = model.entities().stream()
                .collect(Collectors.toMap(EntityDef::name, e -> e));
            for (EntityDef entity : model.entities().stream().sorted(Comparator.comparing(EntityDef::name)).toList()) {
                for (String source : codeGenerator.generateForEntity(entity, tenancyMode, activeVersions, allEntities)) {
                    writeSource(source, tempSourcesStaging);
                }
                lockManager.writeLockFile(activeVersions.getFirst(), entity, tempLocksStaging);
            }

            // MCP tool generation — only when framework config enables MCP
            com.itsjool.aperture.engine.model.McpConfig mcpConfig =
                model.frameworkConfig() != null ? model.frameworkConfig().mcp() : null;
            if (mcpConfig != null && mcpConfig.enabled()) {
                // Only generate MCP tools for the latest API version to avoid duplicate tool name conflicts.
                String latestVersion = activeVersions.getLast();
                com.itsjool.aperture.mcp.McpToolGenerator mcpGen =
                    new com.itsjool.aperture.mcp.McpToolGenerator();
                for (EntityDef entity : model.entities().stream()
                        .sorted(Comparator.comparing(EntityDef::name)).toList()) {
                    com.itsjool.aperture.engine.model.McpEntityConfig entityMcp = entity.mcpConfig();
                    if (entityMcp != null && !entityMcp.enabled()) continue;
                    String source = mcpGen.generateForEntity(entity, mcpConfig, entityMcp, latestVersion);
                    writeSource(source, tempSourcesStaging);
                }
                getLog().info("Aperture MCP: Generated tool classes for version " + latestVersion);
            }

            ChangesetGenerator changesets = new ChangesetGenerator();
            Path changelogDirectory = tempResourcesStaging.resolve("db/changelog");
            Files.createDirectories(changelogDirectory.resolve("manual"));
            copyFrameworkChangelog(changelogDirectory);
            Files.writeString(changelogDirectory.resolve("aperture-schema.xml"),
                changesets.generateSchemaSnapshot(model, tenancyMode));
            boolean hasIncremental = !diff.addedFields().isEmpty() || !diff.renamedFields().isEmpty() || !diff.deferredDrops().isEmpty();
            if (hasIncremental) {
                Files.writeString(changelogDirectory.resolve("aperture-incremental.xml"),
                    changesets.generateGeneratedChangesets(diff, tenancyMode));
            }
            for (var migration : model.migrations()) {
                Files.writeString(changelogDirectory.resolve("manual").resolve(migration.name() + ".xml"),
                    changesets.generateManualMigration(migration));
            }
            Files.writeString(changelogDirectory.resolve("db.changelog-master.xml"),
                changesets.generateRootChangelog(model.migrations(), hasIncremental));
            writeRuntimeMetadata(model, activeVersions, tempResourcesStaging);
            
            String oasYaml = new com.itsjool.aperture.engine.oas.OasGenerator().generate(model, tenancyMode, activeVersions);
            Path oasOut = tempResourcesStaging.resolve("aperture-openapi.yaml");
            Files.writeString(oasOut, oasYaml);
            
            java.util.Set<Path> previousTargets = new java.util.HashSet<>();
            collectFiles(outputDirectory.toPath(), previousTargets);
            collectFiles(generatedResourcesDirectory.toPath().resolve("db/changelog"), previousTargets);
            collectFiles(generatedResourcesDirectory.toPath().resolve("aperture"), previousTargets);
            collectFiles(lockDirectory.toPath(), previousTargets);

            java.util.Set<Path> newTargets = new java.util.HashSet<>();
            List<Path> allTargets = new java.util.ArrayList<>();
            java.util.Map<Path, Path> stagingMap = new java.util.HashMap<>();

            collectTargetsFromTemp(tempSourcesStaging, outputDirectory.toPath(), newTargets, allTargets, stagingMap);
            collectTargetsFromTemp(tempResourcesStaging, generatedResourcesDirectory.toPath(), newTargets, allTargets, stagingMap);
            collectTargetsFromTemp(tempLocksStaging, lockDirectory.toPath(), newTargets, allTargets, stagingMap);

            for (Path prev : previousTargets) {
                if (!newTargets.contains(prev)) {
                    allTargets.add(prev);
                }
            }

            List<Path> normalTargets = new java.util.ArrayList<>();
            List<Path> lockFiles = new java.util.ArrayList<>();
            for (Path target : allTargets) {
                if (target.getParent() != null && target.getParent().equals(lockDirectory.toPath()) && target.getFileName().toString().endsWith(".json")) {
                    lockFiles.add(target);
                } else {
                    normalTargets.add(target);
                }
            }

            String failureInjection = System.getProperty("aperture.plugin.injectFailureMidPublication");
            
            manager.publish(normalTargets, lockFiles, stagingMap, failureInjection);

            registerBuildOutput();
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Generation failed", e);
        } finally {
            deleteDirectory(tempSourcesStaging);
            deleteDirectory(tempResourcesStaging);
            deleteDirectory(tempLocksStaging);
        }
    }

    private void collectFiles(Path dir, java.util.Set<Path> set) throws MojoExecutionException {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile).forEach(set::add);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to collect previous targets", e);
        }
    }

    private void collectTargetsFromTemp(Path tempDir, Path targetDir, java.util.Set<Path> newTargets, List<Path> allTargets, java.util.Map<Path, Path> stagingMap) throws Exception {
        if (!Files.exists(tempDir)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(tempDir)) {
            s.filter(Files::isRegularFile).forEach(tempFile -> {
                Path target = targetDir.resolve(tempDir.relativize(tempFile));
                newTargets.add(target);
                allTargets.add(target);
                stagingMap.put(target, tempFile);
            });
        }
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(f -> {
                if (!f.delete()) {
                    getLog().warn("Failed to delete " + f);
                }
            });
        } catch (Exception e) { getLog().warn("Failed to walk directory for deletion: " + dir, e); }
    }

    private void cleanOrphanedStagingFiles(Path dir) {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(dir, 1)) {
            s.filter(p -> !p.equals(dir) && Files.isDirectory(p) && p.getFileName().toString().startsWith(".staging-"))
             .forEach(this::deleteDirectory);
        } catch (Exception e) { getLog().warn("Failed to clean orphaned staging files in " + dir, e); }
    }

    private List<String> activeVersions(ResolvedDomainModel model) throws MojoExecutionException {
        TreeSet<String> versions = new TreeSet<>(Comparator.comparingInt(Integer::parseInt));
        model.apiVersionConfigs().stream()
            .flatMap(config -> config.versions().entrySet().stream())
            .filter(entry -> isServed(entry.getValue()))
            .map(Map.Entry::getKey)
            .forEach(versions::add);
        if (versions.isEmpty()) {
            throw new MojoExecutionException("At least one ACTIVE or SUNSET API version must be declared");
        }
        return List.copyOf(versions);
    }

    private boolean isServed(ApiVersionDef version) {
        return version != null && version.status() != null
            && ("ACTIVE".equalsIgnoreCase(version.status()) || "SUNSET".equalsIgnoreCase(version.status()));
    }

    private TenancyMode tenancyMode(ResolvedDomainModel model) {
        return model.frameworkConfig().tenancyMode();
    }

    private java.util.Set<String> computeAllowedHttpMethods(ResolvedDomainModel model) {
        java.util.Set<String> methods = new java.util.LinkedHashSet<>();
        methods.add("OPTIONS");
        for (EntityDef entity : model.entities()) {
            if (entity.permissions() != null) {
                entity.permissions().values().stream()
                    .flatMap(java.util.Collection::stream)
                    .forEach(op -> operationToMethod(op).ifPresent(methods::add));
            }
            if (entity.publicOperations() != null) {
                entity.publicOperations()
                    .forEach(op -> operationToMethod(op).ifPresent(methods::add));
            }
        }
        return methods;
    }

    private java.util.Optional<String> operationToMethod(String operation) {
        return switch (operation.toLowerCase()) {
            case "read"   -> java.util.Optional.of("GET");
            case "create" -> java.util.Optional.of("POST");
            case "update" -> java.util.Optional.of("PATCH");
            case "delete" -> java.util.Optional.of("DELETE");
            default       -> java.util.Optional.empty();
        };
    }

    private void writeSource(String source, Path tempSourcesStaging) throws Exception {
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
        String packageName = packageMatcher.find() ? packageMatcher.group(1) : "com.itsjool.aperture.generated";
        Matcher classMatcher = CLASS_PATTERN.matcher(source);
        String className = classMatcher.find() ? classMatcher.group(1) : "package-info";
        Path packageDirectory = tempSourcesStaging.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve(className + ".java"), source);
    }

    private void copyFrameworkChangelog(Path changelogDirectory) throws Exception {
        try (InputStream input = ChangesetGenerator.class.getResourceAsStream(
            "/db/changelog/aperture-framework-tables.xml")) {
            if (input == null) {
                throw new MojoExecutionException("Framework Liquibase changelog is missing from aperture-changeset");
            }
            Files.copy(input, changelogDirectory.resolve("aperture-framework-tables.xml"),
                StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeRuntimeMetadata(ResolvedDomainModel model, List<String> activeVersions, Path tempResourcesStaging) throws Exception {
        TreeSet<String> declaredRoles = new TreeSet<>();
        declaredRoles.add("TenantAdmin");
        declaredRoles.add("SuperAdmin");
        model.roleDefinitions().forEach(definition -> declaredRoles.addAll(definition.roles().keySet()));
        TreeSet<String> lockingEntities = new TreeSet<>();
        for (EntityDef entity : model.entities()) {
            if (entity.optimisticLocking()) {
                String plural = entity.plural() != null ? entity.plural().toLowerCase() : entity.name().toLowerCase() + "s";
                lockingEntities.add(plural);
            }
        }
        TreeSet<String> tenantScopedApiResources = new TreeSet<>();
        for (EntityDef entity : model.entities()) {
            if (entity.tenantScoped()) {
                tenantScopedApiResources.add(entity.plural() != null ? entity.plural().toLowerCase() : entity.name().toLowerCase() + "s");
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("activeVersions", activeVersions);
        metadata.put("defaultRoles", model.frameworkConfig().defaultRoles());
        metadata.put("declaredRoles", declaredRoles);
        metadata.put("lockingEntities", lockingEntities);
        metadata.put("tenancyMode", model.frameworkConfig().tenancyMode().name().toLowerCase());
        metadata.put("allowedHttpMethods", computeAllowedHttpMethods(model));
        metadata.put("tenantScopedApiResources", tenantScopedApiResources);

        TreeSet<String> securityAttributeKeys = new TreeSet<>();
        Map<String, Object> securityAttributeDefinitions = new TreeMap<>();
        model.principalAttributeDefinitions().stream()
            .filter(def -> def.spec() != null && def.spec().securityAttributes() != null)
            .forEach(def -> def.spec().securityAttributes().forEach((name, attribute) -> {
                securityAttributeKeys.add(name);
                Map<String, Object> definition = new LinkedHashMap<>();
                definition.put("type", attribute.type());
                definition.put("allowedValues", attribute.allowedValues());
                definition.put("personalKeyDelegation", attribute.personalKeyDelegation());
                definition.put("serviceAccountAssignable", attribute.serviceAccountAssignable());
                securityAttributeDefinitions.put(name, definition);
            }));
        metadata.put("securityAttributeKeys", securityAttributeKeys);
        metadata.put("securityAttributeDefinitions", securityAttributeDefinitions);

        Path apertureDirectory = tempResourcesStaging.resolve("aperture");
        Files.createDirectories(apertureDirectory);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
            apertureDirectory.resolve("aperture-runtime-metadata.json").toFile(), metadata);
    }

    private void registerBuildOutput() {
        if (project == null) {
            return;
        }
        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
        Resource resource = new Resource();
        resource.setDirectory(generatedResourcesDirectory.getAbsolutePath());
        project.addResource(resource);
    }
}
