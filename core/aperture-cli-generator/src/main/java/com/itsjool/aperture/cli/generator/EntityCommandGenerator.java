package com.itsjool.aperture.cli.generator;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.palantir.javapoet.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates the four verb-first Picocli command groups — {@code GetCommand}, {@code CreateCommand},
 * {@code UpdateCommand}, {@code DeleteCommand} — each carrying one static inner subcommand per
 * entity (named by the entity's declared name), plus a {@code -f} file-mode path on create/update/
 * delete that shares registry/lookup machinery with {@code ApplyCommand} via the generated
 * {@code FileOps} class.
 *
 * <p>kubectl-style verb-first surface: {@code <binary> get customers}, {@code <binary> create
 * customers --name Acme}, {@code <binary> create -f seed.yaml}, etc. There is no per-entity
 * top-level command group anymore (clean break from the noun-first surface).
 */
class EntityCommandGenerator {

    private static final String CLI_PKG    = "com.itsjool.aperture.cli";
    private static final String CMD_PKG    = CLI_PKG + ".cmd";
    private static final String CONFIG_PKG = CLI_PKG + ".config";
    private static final String HTTP_PKG   = CLI_PKG + ".http";

    private static final ClassName APERTURE_CLI   = ClassName.get(CLI_PKG, "ApertureCli");
    private static final ClassName GLOBAL_OPTIONS = ClassName.get(CLI_PKG, "GlobalOptions");
    private static final ClassName CONFIG_STORE   = ClassName.get(CONFIG_PKG, "ConfigStore");
    private static final ClassName API_CLIENT     = ClassName.get(HTTP_PKG, "ApiClient");
    private static final ClassName OUTPUT_FMT     = ClassName.get(CMD_PKG, "OutputFormatter");
    private static final ClassName FILE_OPS       = ClassName.get(CMD_PKG, "FileOps");
    private static final ClassName JSON_NODE      = ClassName.get("com.fasterxml.jackson.databind", "JsonNode");
    private static final ClassName OBJECT_MAPPER  = ClassName.get("com.fasterxml.jackson.databind", "ObjectMapper");
    private static final ClassName YAML_FACTORY   = ClassName.get("com.fasterxml.jackson.dataformat.yaml", "YAMLFactory");
    private static final ClassName CMD_LINE       = ClassName.get("picocli", "CommandLine");
    private static final ClassName CMD_ANN        = ClassName.get("picocli", "CommandLine", "Command");
    private static final ClassName OPTION_ANN     = ClassName.get("picocli", "CommandLine", "Option");
    private static final ClassName PARAMS_ANN     = ClassName.get("picocli", "CommandLine", "Parameters");
    private static final ClassName PARENT_CMD     = ClassName.get("picocli", "CommandLine", "ParentCommand");
    private static final ClassName LINKED_MAP     = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName PATH_CN        = ClassName.get("java.nio.file", "Path");
    private static final ClassName HASH_SET       = ClassName.get("java.util", "HashSet");

    private final List<EntityDef> entities;
    private final Map<String, String> resourcePathsByEntity;

    /**
     * @param entities              entities to generate subcommands for, one per verb class.
     * @param resourcePathsByEntity map of entityName(lowercase) → resourcePath (the entity's
     *                              declared {@code plural}, lowercased, or {@code name + "s"} when
     *                              no plural is declared) — used to resolve relationship JSON:API
     *                              types so a custom plural (e.g. Currency → "currencies") isn't
     *                              overridden by the naive {@code name + "s"} fallback.
     */
    EntityCommandGenerator(List<EntityDef> entities, Map<String, String> resourcePathsByEntity) {
        this.entities = entities;
        this.resourcePathsByEntity = resourcePathsByEntity;
    }

    // ── GetCommand ───────────────────────────────────────────────────────────

    String generateGetCommand() {
        ClassName self = ClassName.get(CMD_PKG, "GetCommand");
        TypeSpec.Builder cls = TypeSpec.classBuilder("GetCommand")
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(Runnable.class)
                .addAnnotation(verbCommandAnnotation("get", "Get or list resources", self))
                .addField(FieldSpec.builder(APERTURE_CLI, "root").addAnnotation(PARENT_CMD).build())
                .addMethod(topLevelRun(false));
        for (EntityDef entity : entities) {
            cls.addType(entityGetSubcommand(entity, self));
        }
        return JavaFile.builder(CMD_PKG, cls.build()).build().toString();
    }

    private TypeSpec entityGetSubcommand(EntityDef entity, ClassName parent) {
        MethodSpec.Builder run = baseRun();
        preamble(run, "parent");
        run.beginControlFlow("if (id == null)");
        run.addStatement("$T result = client.get(versionPrefix + $S + buildListQuery())",
                JSON_NODE, "/" + resourcePath(entity));
        run.addStatement("$T.print(result, parent.root.global.format)", OUTPUT_FMT);
        run.nextControlFlow("else");
        run.addStatement("$T result = client.get(versionPrefix + $S + id)", JSON_NODE, "/" + resourcePath(entity) + "/");
        run.addStatement("$T.print(result, parent.root.global.format)", OUTPUT_FMT);
        run.endControlFlow();
        catchBlock(run);

        return TypeSpec.classBuilder(entity.name())
                .addModifiers(Modifier.STATIC)
                .addSuperinterface(Runnable.class)
                .addAnnotation(subCmdAnnotation(resourceName(entity), "Get or list " + entity.name() + " resources"))
                .addField(FieldSpec.builder(parent, "parent").addAnnotation(PARENT_CMD).build())
                .addField(FieldSpec.builder(String.class, "id")
                        .addAnnotation(AnnotationSpec.builder(PARAMS_ANN)
                                .addMember("index", "$S", "0")
                                .addMember("arity", "$S", "0..1")
                                .addMember("description", "$S", "Resource ID (omit to list)")
                                .build())
                        .build())
                .addField(intOption("--page", "Page number (1-based)", "page", "1"))
                .addField(intOption("--size", "Page size", "size", "20"))
                .addField(stringArrayOption("--filter", "JSON:API filter as key=value; repeat or comma-separate", "filters"))
                .addField(stringOption("--sort", "JSON:API sort expression (e.g. -createdAt,name)", "sort"))
                .addField(stringOption("--include", "JSON:API include path list", "include"))
                .addMethod(run.build())
                .addMethod(buildListQueryMethod())
                .addMethod(encodeQueryMethod())
                .build();
    }

    // ── CreateCommand ────────────────────────────────────────────────────────

    String generateCreateCommand() {
        ClassName self = ClassName.get(CMD_PKG, "CreateCommand");
        TypeSpec.Builder cls = TypeSpec.classBuilder("CreateCommand")
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(Runnable.class)
                .addAnnotation(verbCommandAnnotation("create", "Create resources", self))
                .addField(FieldSpec.builder(APERTURE_CLI, "root").addAnnotation(PARENT_CMD).build())
                .addField(fileOption())
                .addField(dryRunOption())
                .addField(continueOnErrorOption())
                .addMethod(topLevelRun(true))
                .addMethod(fileModeRunMethod(FileModeVerb.CREATE));
        for (EntityDef entity : entities) {
            cls.addType(entityCreateSubcommand(entity, self));
        }
        return JavaFile.builder(CMD_PKG, cls.build()).build().toString();
    }

    private TypeSpec entityCreateSubcommand(EntityDef entity, ClassName parent) {
        TypeSpec.Builder cmd = TypeSpec.classBuilder(entity.name())
                .addModifiers(Modifier.STATIC)
                .addSuperinterface(Runnable.class)
                .addAnnotation(subCmdAnnotation(resourceName(entity), "Create a " + entity.name()))
                .addField(FieldSpec.builder(parent, "parent").addAnnotation(PARENT_CMD).build());
        addFieldOptions(entity, cmd);

        MethodSpec.Builder run = baseRun();
        addRequiredValidation(entity, run);
        preamble(run, "parent");
        run.addStatement("$T<$T, $T> body = buildCreateBody()", Map.class, String.class, Object.class);
        run.addStatement("$T result = client.post(versionPrefix + $S, body)", JSON_NODE, "/" + resourcePath(entity));
        run.addStatement("$T.print(result, parent.root.global.format)", OUTPUT_FMT);
        catchBlock(run);

        cmd.addMethod(run.build());
        cmd.addMethod(buildBody(entity, false));
        return cmd.build();
    }

    // ── UpdateCommand ────────────────────────────────────────────────────────

    String generateUpdateCommand() {
        ClassName self = ClassName.get(CMD_PKG, "UpdateCommand");
        TypeSpec.Builder cls = TypeSpec.classBuilder("UpdateCommand")
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(Runnable.class)
                .addAnnotation(verbCommandAnnotation("update", "Update resources", self))
                .addField(FieldSpec.builder(APERTURE_CLI, "root").addAnnotation(PARENT_CMD).build())
                .addField(fileOption())
                .addField(dryRunOption())
                .addField(continueOnErrorOption())
                .addMethod(topLevelRun(true))
                .addMethod(fileModeRunMethod(FileModeVerb.UPDATE));
        for (EntityDef entity : entities) {
            cls.addType(entityUpdateSubcommand(entity, self));
        }
        return JavaFile.builder(CMD_PKG, cls.build()).build().toString();
    }

    private TypeSpec entityUpdateSubcommand(EntityDef entity, ClassName parent) {
        TypeSpec.Builder cmd = TypeSpec.classBuilder(entity.name())
                .addModifiers(Modifier.STATIC)
                .addSuperinterface(Runnable.class)
                .addAnnotation(subCmdAnnotation(resourceName(entity), "Update a " + entity.name()))
                .addField(FieldSpec.builder(parent, "parent").addAnnotation(PARENT_CMD).build())
                .addField(requiredPositionalId());

        if (entity.optimisticLocking()) {
            cmd.addField(FieldSpec.builder(String.class, "ifMatch")
                    .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                            .addMember("names", "{$S}", "--if-match")
                            .addMember("description", "$S", "ETag for optimistic locking (required by server)")
                            .build())
                    .build());
        }
        addFieldOptions(entity, cmd);

        MethodSpec.Builder run = baseRun();
        preamble(run, "parent");
        run.addStatement("$T<$T, $T> body = buildUpdateBody()", Map.class, String.class, Object.class);
        String ifMatchArg = entity.optimisticLocking() ? "ifMatch" : "null";
        run.addStatement("$T result = client.patch(versionPrefix + $S + id, body, $L)",
                JSON_NODE, "/" + resourcePath(entity) + "/", ifMatchArg);
        run.beginControlFlow("if (result == null || result.isNull())");
        run.addStatement("$T.out.println($S + id)", System.class, "Updated " + entity.name() + " ");
        run.nextControlFlow("else");
        run.addStatement("$T.print(result, parent.root.global.format)", OUTPUT_FMT);
        run.endControlFlow();
        catchBlock(run);

        cmd.addMethod(run.build());
        cmd.addMethod(buildBody(entity, true));
        return cmd.build();
    }

    // ── DeleteCommand ────────────────────────────────────────────────────────

    String generateDeleteCommand() {
        ClassName self = ClassName.get(CMD_PKG, "DeleteCommand");
        TypeSpec.Builder cls = TypeSpec.classBuilder("DeleteCommand")
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(Runnable.class)
                .addAnnotation(verbCommandAnnotation("delete", "Delete resources", self))
                .addField(FieldSpec.builder(APERTURE_CLI, "root").addAnnotation(PARENT_CMD).build())
                .addField(fileOption())
                .addField(yesOption())
                .addField(dryRunOption())
                .addField(continueOnErrorOption())
                .addMethod(topLevelRun(true))
                .addMethod(fileModeRunMethod(FileModeVerb.DELETE));
        for (EntityDef entity : entities) {
            cls.addType(entityDeleteSubcommand(entity, self));
        }
        return JavaFile.builder(CMD_PKG, cls.build()).build().toString();
    }

    private TypeSpec entityDeleteSubcommand(EntityDef entity, ClassName parent) {
        TypeSpec.Builder cmd = TypeSpec.classBuilder(entity.name())
                .addModifiers(Modifier.STATIC)
                .addSuperinterface(Runnable.class)
                .addAnnotation(subCmdAnnotation(resourceName(entity), "Delete a " + entity.name()))
                .addField(FieldSpec.builder(parent, "parent").addAnnotation(PARENT_CMD).build())
                .addField(requiredPositionalId());

        if (entity.optimisticLocking()) {
            cmd.addField(FieldSpec.builder(String.class, "ifMatch")
                    .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                            .addMember("names", "{$S}", "--if-match")
                            .addMember("description", "$S", "ETag for optimistic locking")
                            .build())
                    .build());
        }
        cmd.addField(FieldSpec.builder(boolean.class, "yes")
                .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                        .addMember("names", "{$S, $S}", "-y", "--yes")
                        .addMember("description", "$S", "Skip confirmation prompt")
                        .build())
                .build());

        MethodSpec.Builder run = baseRun();
        run.beginControlFlow("if (!yes)");
        run.addStatement("$T.out.print($S + id + $S)", System.class, "Delete " + entity.name() + " ", "? [y/N] ");
        run.addStatement("$T.out.flush()", System.class);
        run.addStatement("$T line = new java.io.BufferedReader(new java.io.InputStreamReader($T.in)).readLine()",
                String.class, System.class);
        run.beginControlFlow("if (line == null || !line.trim().equalsIgnoreCase($S))", "y");
        run.addStatement("$T.out.println($S)", System.class, "Aborted.");
        run.addStatement("return");
        run.endControlFlow();
        run.endControlFlow();
        preamble(run, "parent");
        String ifMatchArg = entity.optimisticLocking() ? "ifMatch" : "null";
        run.addStatement("client.delete(versionPrefix + $S + id, $L)", "/" + resourcePath(entity) + "/", ifMatchArg);
        run.addStatement("$T.out.println($S + id)", System.class, "Deleted " + entity.name() + " ");
        catchBlock(run);

        cmd.addMethod(run.build());
        return cmd.build();
    }

    // ── Top-level verb run() / file-mode dispatch ───────────────────────────

    /**
     * The Runnable#run() body for a verb's outer command. Picocli only invokes this when no
     * entity subcommand was matched — i.e. bare {@code <verb>}, or {@code <verb> -f <file>}.
     * {@code get} has no file mode; the others print usage and exit 2 when {@code file} is null.
     */
    private MethodSpec topLevelRun(boolean hasFileMode) {
        MethodSpec.Builder m = MethodSpec.methodBuilder("run")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class);
        if (hasFileMode) {
            m.beginControlFlow("if (file != null)");
            m.addStatement("runFileMode()");
            m.nextControlFlow("else");
            m.addStatement("$T.usage(this, $T.out)", CMD_LINE, System.class);
            m.addStatement("$T.exit(2)", System.class);
            m.endControlFlow();
        } else {
            m.addStatement("$T.usage(this, $T.out)", CMD_LINE, System.class);
        }
        return m.build();
    }

    private enum FileModeVerb { CREATE, UPDATE, DELETE }

    /**
     * Builds the {@code runFileMode()} method shared preamble (config/profile/client/version,
     * {@code FileOps.resolveFiles}, counters) plus the verb-specific record loop. Mirrors
     * {@code ApplyCommand}'s run-method shape but is driven by this verb's own semantics
     * (see class javadoc on each loop builder below).
     */
    private MethodSpec fileModeRunMethod(FileModeVerb verb) {
        String verbPast = switch (verb) {
            case CREATE -> "Created";
            case UPDATE -> "Updated";
            case DELETE -> "Deleted";
        };
        MethodSpec.Builder m = MethodSpec.methodBuilder("runFileMode")
                .addModifiers(Modifier.PRIVATE)
                .returns(void.class)
                .beginControlFlow("try")
                .addStatement("var config = $T.load()", CONFIG_STORE)
                .addStatement("var profile = $T.activeProfile(config, root.global.profile)", CONFIG_STORE)
                .addStatement("$T.applyParentOverrides(root.global, profile)", FILE_OPS)
                .addStatement("var client = new $T(profile.server, profile, root.global.verbose)", API_CLIENT)
                .addStatement("$T version = root.global.apiVersion != null ? root.global.apiVersion : (profile.apiVersion != null ? profile.apiVersion : $T.DEFAULT_API_VERSION)", String.class, GLOBAL_OPTIONS)
                .addStatement("$T versionPrefix = (version != null && !version.isBlank()) ? $S + version : $S",
                        String.class, "/api/v", "/api")
                .addStatement("var files = $T.resolveFiles(file)", FILE_OPS)
                .beginControlFlow("if (files.isEmpty())")
                .addStatement("$T.err.println($S + file)", System.class, "No YAML files found: ")
                .addStatement("$T.exit(1)", System.class)
                .endControlFlow();

        if (verb != FileModeVerb.DELETE) {
            m.addStatement("$T yaml = new $T(new $T())", OBJECT_MAPPER, OBJECT_MAPPER, YAML_FACTORY);
            m.addStatement("$T<$T> warnedNaturalKey = new $T<>()", java.util.Set.class, String.class, HASH_SET);
            m.addStatement("$T<$T, $T> relCache = new $T<>()", Map.class, String.class, String.class, LINKED_MAP);
        }

        m.addStatement("int total = 0, success = 0, skipped = 0, failed = 0")
         .addCode(switch (verb) {
             case CREATE -> createFileLoop();
             case UPDATE -> updateFileLoop();
             case DELETE -> deleteFileLoop();
         })
         .addStatement("$T.out.printf($S, success, skipped, failed)",
             System.class, "%n" + verbPast + " %d resource(s)  skipped=%d  failed=%d%n")
         .nextControlFlow("catch ($T e)", Exception.class)
         .addStatement("$T.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + $S + e)",
             System.class, ": ")
         .addStatement("$T.exit(1)", System.class)
         .endControlFlow();

        return m.build();
    }

    /**
     * {@code create -f}: create-only. Unlike {@code apply -f}, a conflict (409/423/"already
     * exists"/unique-constraint message) is a hard error — counted failed, process stops unless
     * {@code --continue-on-error} — never a silent skip or an upsert.
     */
    private static String createFileLoop() {
        return """
                for (java.nio.file.Path f : files) {
                    System.out.println("Creating from " + f + "...");
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> doc = yaml.readValue(f.toFile(), java.util.Map.class);
                    if (doc == null) continue;

                    for (java.util.Map.Entry<String, Object> section : doc.entrySet()) {
                        String entityPath = section.getKey();
                        FileOps.EntitySpec spec = FileOps.ENTITY_SPECS.get(entityPath);
                        if (spec == null) {
                            System.err.println("  [WARN] Unknown entity type '" + entityPath + "' — skipping");
                            continue;
                        }
                        FileOps.warnIfNaturalKeyNotUnique(spec, entityPath, warnedNaturalKey, dryRun);
                        @SuppressWarnings("unchecked")
                        java.util.List<java.util.Map<String, Object>> records = (java.util.List<java.util.Map<String, Object>>) section.getValue();
                        if (records == null) continue;

                        int idx = 0;
                        for (java.util.Map<String, Object> record : records) {
                            total++;
                            String label = entityPath + "[" + idx++ + "]";
                            Object refVal = record.get("_ref");
                            Object naturalVal = record.get(spec.naturalKey());
                            String displayName = refVal != null ? refVal.toString()
                                : naturalVal != null ? naturalVal.toString() : label;
                            try {
                                if (dryRun) {
                                    System.out.println("  [DRY-RUN] would create " + label + "  " + displayName);
                                    success++;
                                    continue;
                                }
                                java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>();
                                java.util.Map<String, Object> rels = new java.util.LinkedHashMap<>();
                                boolean relFailed = false;
                                for (java.util.Map.Entry<String, Object> field : record.entrySet()) {
                                    String key = field.getKey();
                                    Object val = field.getValue();
                                    if (val == null) continue;
                                    FileOps.FieldSpec fs = spec.fields().get(key);
                                    if (fs == null) { continue; }
                                    if (fs.isRel()) {
                                        if (!fs.manyToOne()) continue;
                                        String relId = FileOps.resolveRelId(val.toString(), fs.targetPath(), fs.naturalKey(), relCache, versionPrefix, client);
                                        if (relId == null) {
                                            System.err.printf("  [WARN]  %s  %s → cannot resolve relationship '%s' = '%s'%n", label, displayName, key, val);
                                            relFailed = true;
                                            break;
                                        }
                                        java.util.Map<String, Object> rd = new java.util.LinkedHashMap<>();
                                        rd.put("type", fs.targetPath()); rd.put("id", relId);
                                        rels.put(key, java.util.Map.of("data", rd));
                                    } else {
                                        attrs.put(key, val);
                                    }
                                }
                                if (relFailed) {
                                    failed++;
                                    if (!continueOnError) {
                                        System.err.println("Stopping. Use --continue-on-error to keep going.");
                                        System.exit(1);
                                    }
                                    continue;
                                }
                                java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
                                data.put("type", entityPath);
                                data.put("attributes", attrs);
                                if (!rels.isEmpty()) data.put("relationships", rels);
                                java.util.Map<String, Object> body = java.util.Map.of("data", data);

                                com.fasterxml.jackson.databind.JsonNode result = client.post(versionPrefix + "/" + entityPath, body);
                                if (result != null && result.has("data")) {
                                    String id = result.get("data").path("id").asText(null);
                                    System.out.printf("  ✓ %s  %s  → %s%n", label, displayName, id);
                                    if (id != null && naturalVal != null) relCache.put(entityPath + ":" + displayName, id);
                                    success++;
                                } else {
                                    System.out.printf("  ? %s  %s  (no id in response)%n", label, displayName);
                                    success++;
                                }
                            } catch (Exception ex) {
                                String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                                System.err.printf("  ✗ %s  %s  → %s%n", label, displayName, msg);
                                failed++;
                                if (!continueOnError) {
                                    System.err.println("Stopping. Use --continue-on-error to keep going.");
                                    System.exit(1);
                                }
                            }
                        }
                    }
                }
            """;
    }

    /**
     * {@code update -f}: every record must resolve to an already-existing resource — an explicit
     * {@code id:} field, else a natural-key lookup via {@code FileOps.lookupIdAndEtag}. A record
     * that can't be resolved is a hard error (failed, stops unless {@code --continue-on-error}),
     * never silently skipped. Resolved records are PATCHed with the etag from the same lookup.
     */
    private static String updateFileLoop() {
        return """
                for (java.nio.file.Path f : files) {
                    System.out.println("Updating from " + f + "...");
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> doc = yaml.readValue(f.toFile(), java.util.Map.class);
                    if (doc == null) continue;

                    for (java.util.Map.Entry<String, Object> section : doc.entrySet()) {
                        String entityPath = section.getKey();
                        FileOps.EntitySpec spec = FileOps.ENTITY_SPECS.get(entityPath);
                        if (spec == null) {
                            System.err.println("  [WARN] Unknown entity type '" + entityPath + "' — skipping");
                            continue;
                        }
                        FileOps.warnIfNaturalKeyNotUnique(spec, entityPath, warnedNaturalKey, dryRun);
                        @SuppressWarnings("unchecked")
                        java.util.List<java.util.Map<String, Object>> records = (java.util.List<java.util.Map<String, Object>>) section.getValue();
                        if (records == null) continue;

                        int idx = 0;
                        for (java.util.Map<String, Object> record : records) {
                            total++;
                            String label = entityPath + "[" + idx++ + "]";
                            boolean explicitId = record.containsKey("id");
                            Object naturalVal = record.get(spec.naturalKey());
                            String displayName = explicitId ? record.get("id").toString()
                                : naturalVal != null ? naturalVal.toString() : label;
                            try {
                                String id;
                                String etag = null;
                                if (explicitId) {
                                    id = record.get("id").toString();
                                    if (!dryRun) etag = FileOps.fetchEtag(entityPath, id, versionPrefix, client);
                                } else {
                                    String naturalLookupVal = naturalVal != null ? naturalVal.toString() : null;
                                    if (naturalLookupVal == null) {
                                        System.err.printf("  ✗ %s  %s  → no 'id' field and no natural key value to look up%n", label, displayName);
                                        id = null;
                                    } else {
                                        String[] idAndEtag = FileOps.lookupIdAndEtag(naturalLookupVal, spec.naturalKey(), entityPath, versionPrefix, client);
                                        id = idAndEtag[0];
                                        etag = idAndEtag[1];
                                    }
                                }
                                if (id == null) {
                                    System.err.printf("  ✗ %s  %s  → not found%n", label, displayName);
                                    failed++;
                                    if (!continueOnError) {
                                        System.err.println("Stopping. Use --continue-on-error to keep going.");
                                        System.exit(1);
                                    }
                                    continue;
                                }
                                if (dryRun) {
                                    System.out.printf("  [DRY-RUN] would update %s  %s  (id=%s)%n", label, displayName, id);
                                    success++;
                                    continue;
                                }
                                java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>();
                                java.util.Map<String, Object> rels = new java.util.LinkedHashMap<>();
                                boolean relFailed = false;
                                for (java.util.Map.Entry<String, Object> field : record.entrySet()) {
                                    String key = field.getKey();
                                    if (key.equals("id")) continue;
                                    Object val = field.getValue();
                                    if (val == null) continue;
                                    FileOps.FieldSpec fs = spec.fields().get(key);
                                    if (fs == null) { continue; }
                                    if (fs.isRel()) {
                                        if (!fs.manyToOne()) continue;
                                        String relId = FileOps.resolveRelId(val.toString(), fs.targetPath(), fs.naturalKey(), relCache, versionPrefix, client);
                                        if (relId == null) {
                                            System.err.printf("  [WARN]  %s  %s → cannot resolve relationship '%s' = '%s'%n", label, displayName, key, val);
                                            relFailed = true;
                                            break;
                                        }
                                        java.util.Map<String, Object> rd = new java.util.LinkedHashMap<>();
                                        rd.put("type", fs.targetPath()); rd.put("id", relId);
                                        rels.put(key, java.util.Map.of("data", rd));
                                    } else {
                                        attrs.put(key, val);
                                    }
                                }
                                if (relFailed) {
                                    failed++;
                                    if (!continueOnError) {
                                        System.err.println("Stopping. Use --continue-on-error to keep going.");
                                        System.exit(1);
                                    }
                                    continue;
                                }
                                java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
                                data.put("type", entityPath); data.put("id", id);
                                data.put("attributes", attrs);
                                if (!rels.isEmpty()) data.put("relationships", rels);
                                client.patch(versionPrefix + "/" + entityPath + "/" + id, java.util.Map.of("data", data), etag);
                                System.out.printf("  ✓ %s  %s  → %s  [updated]%n", label, displayName, id);
                                success++;
                            } catch (Exception ex) {
                                String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                                System.err.printf("  ✗ %s  %s  → %s%n", label, displayName, msg);
                                failed++;
                                if (!continueOnError) {
                                    System.err.println("Stopping. Use --continue-on-error to keep going.");
                                    System.exit(1);
                                }
                            }
                        }
                    }
                }
            """;
    }

    /**
     * {@code delete -f}: unchanged from the former top-level {@code delete-file} command —
     * reverse order (dependents before parents), confirm unless {@code -y}, dry-run, and
     * explicit-id or natural-key resolution via {@code FileOps}.
     */
    private static String deleteFileLoop() {
        return """
                        java.util.List<java.util.Map.Entry<String, java.util.Map<String, Object>>> toDelete = new java.util.ArrayList<>();
                        com.fasterxml.jackson.databind.ObjectMapper yaml =
                            new com.fasterxml.jackson.databind.ObjectMapper(
                                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
                        for (java.nio.file.Path f : files) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> doc = yaml.readValue(f.toFile(), java.util.Map.class);
                            if (doc == null) continue;
                            java.util.List<java.util.Map.Entry<String, Object>> sections = new java.util.ArrayList<>(doc.entrySet());
                            java.util.Collections.reverse(sections);
                            for (java.util.Map.Entry<String, Object> section : sections) {
                                String entityPath = section.getKey();
                                FileOps.EntitySpec spec = FileOps.ENTITY_SPECS.get(entityPath);
                                if (spec == null) continue;
                                @SuppressWarnings("unchecked")
                                java.util.List<java.util.Map<String, Object>> records = (java.util.List<java.util.Map<String, Object>>) section.getValue();
                                if (records == null) continue;
                                java.util.List<java.util.Map<String, Object>> reversed = new java.util.ArrayList<>(records);
                                java.util.Collections.reverse(reversed);
                                for (java.util.Map<String, Object> record : reversed) {
                                    toDelete.add(java.util.Map.entry(entityPath, record));
                                }
                            }
                        }
                        if (toDelete.isEmpty()) { System.out.println("Nothing to delete."); return; }

                        if (!yes && !dryRun) {
                            System.out.print("Delete " + toDelete.size() + " resource(s)? [y/N] ");
                            System.out.flush();
                            String line = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
                            if (line == null || !line.trim().equalsIgnoreCase("y")) {
                                System.out.println("Aborted."); return;
                            }
                        }

                        int idx = 0;
                        for (java.util.Map.Entry<String, java.util.Map<String, Object>> entry : toDelete) {
                            total++;
                            String entityPath = entry.getKey();
                            java.util.Map<String, Object> record = entry.getValue();
                            FileOps.EntitySpec spec = FileOps.ENTITY_SPECS.get(entityPath);
                            String label = entityPath + "[" + idx++ + "]";
                            Object naturalVal = record.get(spec.naturalKey());
                            String displayName = naturalVal != null ? naturalVal.toString() : label;
                            try {
                                boolean explicitId = record.containsKey("id");
                                String id;
                                String etag = null;
                                if (explicitId) {
                                    id = record.get("id").toString();
                                } else {
                                    String[] idAndEtag = FileOps.lookupIdAndEtag(displayName, spec.naturalKey(), entityPath, versionPrefix, client);
                                    id = idAndEtag[0];
                                    etag = idAndEtag[1];
                                }
                                if (id == null) {
                                    System.out.printf("  ~ %s  %s  [not found — skipped]%n", label, displayName);
                                    skipped++; continue;
                                }
                                if (dryRun) {
                                    System.out.printf("  [DRY-RUN] would delete %s  %s  (id=%s)%n", label, displayName, id);
                                    success++; continue;
                                }
                                if (explicitId) {
                                    etag = FileOps.fetchEtag(entityPath, id, versionPrefix, client);
                                }
                                client.delete(versionPrefix + "/" + entityPath + "/" + id, etag);
                                System.out.printf("  ✓ %s  %s  (id=%s)%n", label, displayName, id);
                                success++;
                            } catch (Exception ex) {
                                String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                                System.err.printf("  ✗ %s  %s  → %s%n", label, displayName, msg);
                                failed++;
                                if (!continueOnError) {
                                    System.err.println("Stopping. Use --continue-on-error to keep going.");
                                    System.exit(1);
                                }
                            }
                        }
            """;
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private AnnotationSpec verbCommandAnnotation(String name, String description, ClassName self) {
        AnnotationSpec.Builder ann = AnnotationSpec.builder(CMD_ANN)
                .addMember("name", "$S", name)
                .addMember("description", "$S", description)
                .addMember("mixinStandardHelpOptions", "true");
        if (!entities.isEmpty()) {
            StringBuilder fmt = new StringBuilder("{\n");
            List<Object> args = new ArrayList<>();
            for (EntityDef entity : entities) {
                fmt.append("    $T.$L.class,\n");
                args.add(self);
                args.add(entity.name());
            }
            fmt.setLength(fmt.length() - 2);
            fmt.append("\n}");
            ann.addMember("subcommands", fmt.toString(), args.toArray());
        }
        return ann.build();
    }

    private MethodSpec buildBody(EntityDef entity, boolean includeId) {
        String methodName = includeId ? "buildUpdateBody" : "buildCreateBody";
        MethodSpec.Builder m = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .returns(ParameterizedTypeName.get(ClassName.get(Map.class),
                        ClassName.get(String.class), ClassName.get(Object.class)));
        m.addStatement("$T<$T, $T> attrs = new $T<>()", Map.class, String.class, Object.class, LINKED_MAP);
        appendFieldBodyBuilding(entity, m);
        m.addStatement("$T<$T, $T> rels = new $T<>()", Map.class, String.class, Object.class, LINKED_MAP);
        appendRelBodyBuilding(entity, m);
        m.addStatement("$T<$T, $T> data = new $T<>()", Map.class, String.class, Object.class, LINKED_MAP);
        m.addStatement("data.put($S, $S)", "type", resourcePath(entity));
        if (includeId) m.addStatement("data.put($S, id)", "id");
        m.addStatement("data.put($S, attrs)", "attributes");
        m.beginControlFlow("if (!rels.isEmpty())");
        m.addStatement("data.put($S, rels)", "relationships");
        m.endControlFlow();
        m.addStatement("return $T.of($S, data)", Map.class, "data");
        return m.build();
    }

    private void appendFieldBodyBuilding(EntityDef entity, MethodSpec.Builder m) {
        if (entity.fields() == null) return;
        for (Map.Entry<String, FieldDef> entry : entity.fields().entrySet()) {
            String name = entry.getKey();
            FieldDef field = entry.getValue();
            if ("ref".equals(field.type())) continue;
            m.beginControlFlow("if ($L != null)", camel(name));
            m.addStatement("attrs.put($S, $L)", name, camel(name));
            m.endControlFlow();
        }
    }

    private void appendRelBodyBuilding(EntityDef entity, MethodSpec.Builder m) {
        if (entity.fields() == null) return;
        for (Map.Entry<String, FieldDef> entry : entity.fields().entrySet()) {
            String name = entry.getKey();
            FieldDef field = entry.getValue();
            if (!"ref".equals(field.type())) continue;
            if (field.mappedBy() != null && !field.mappedBy().isBlank()) continue;
            String rel = field.relation() != null ? field.relation() : "";
            String targetKey = field.targetClass() != null ? field.targetClass().toLowerCase() : name;
            String targetType = resourcePathsByEntity.getOrDefault(targetKey, targetKey + "s");
            String camelName = camel(name);
            if (rel.equalsIgnoreCase("ManyToOne") || rel.equalsIgnoreCase("ManyToManyOwner")) {
                String var = camelName + "Id";
                m.beginControlFlow("if ($L != null)", var);
                m.addStatement("$T<$T, $T> relData = new $T<>()", Map.class, String.class, Object.class, LINKED_MAP);
                m.addStatement("relData.put($S, $S)", "type", targetType);
                m.addStatement("relData.put($S, $L)", "id", var);
                m.addStatement("rels.put($S, $T.of($S, relData))", name, Map.class, "data");
                m.endControlFlow();
            } else if (rel.equalsIgnoreCase("OneToMany") || rel.equalsIgnoreCase("ManyToMany")) {
                String var = camelName + "Ids";
                m.beginControlFlow("if ($L != null && $L.length > 0)", var, var);
                m.addStatement("java.util.List<$T<$T, $T>> relList = new java.util.ArrayList<>()",
                        Map.class, String.class, Object.class);
                m.beginControlFlow("for ($T rid : $L)", String.class, var);
                m.addStatement("$T<$T, $T> rd = new $T<>()", Map.class, String.class, Object.class, LINKED_MAP);
                m.addStatement("rd.put($S, $S)", "type", targetType);
                m.addStatement("rd.put($S, rid)", "id");
                m.addStatement("relList.add(rd)");
                m.endControlFlow();
                m.addStatement("rels.put($S, $T.of($S, relList))", name, Map.class, "data");
                m.endControlFlow();
            }
        }
    }

    private void addFieldOptions(EntityDef entity, TypeSpec.Builder cmd) {
        if (entity.fields() == null) return;
        for (Map.Entry<String, FieldDef> entry : entity.fields().entrySet()) {
            String name = entry.getKey();
            FieldDef field = entry.getValue();
            if ("ref".equals(field.type())) continue;
            cmd.addField(FieldSpec.builder(javaType(field.type()), camel(name))
                    .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                            .addMember("names", "{$S}", "--" + kebab(name))
                            .addMember("description", "$S", name)
                            .build())
                    .build());
        }
        for (Map.Entry<String, FieldDef> entry : entity.fields().entrySet()) {
            String name = entry.getKey();
            FieldDef field = entry.getValue();
            if (!"ref".equals(field.type())) continue;
            if (field.mappedBy() != null && !field.mappedBy().isBlank()) continue;
            String rel = field.relation() != null ? field.relation() : "";
            if (rel.equalsIgnoreCase("ManyToOne") || rel.equalsIgnoreCase("ManyToManyOwner")) {
                cmd.addField(FieldSpec.builder(String.class, camel(name) + "Id")
                        .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                                .addMember("names", "{$S}", "--" + kebab(name) + "-id")
                                .addMember("description", "$S", "ID of related " + name)
                                .build())
                        .build());
            } else if (rel.equalsIgnoreCase("OneToMany") || rel.equalsIgnoreCase("ManyToMany")) {
                cmd.addField(FieldSpec.builder(String[].class, camel(name) + "Ids")
                        .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                                .addMember("names", "{$S}", "--" + kebab(name) + "-ids")
                                .addMember("description", "$S", "IDs of related " + name)
                                .addMember("split", "$S", ",")
                                .build())
                        .build());
            }
        }
    }

    private void addRequiredValidation(EntityDef entity, MethodSpec.Builder m) {
        if (entity.fields() == null) return;
        for (Map.Entry<String, FieldDef> entry : entity.fields().entrySet()) {
            String name = entry.getKey();
            FieldDef field = entry.getValue();
            if (!field.required()) continue;
            if ("ref".equals(field.type())) {
                if (field.mappedBy() != null && !field.mappedBy().isBlank()) continue;
                String rel = field.relation() != null ? field.relation() : "";
                if (rel.equalsIgnoreCase("ManyToOne") || rel.equalsIgnoreCase("ManyToManyOwner")) {
                    String var = camel(name) + "Id";
                    m.beginControlFlow("if ($L == null)", var);
                    m.addStatement("$T.err.println($S)", System.class, "Missing required option: --" + kebab(name) + "-id");
                    m.addStatement("$T.exit(2)", System.class);
                    m.endControlFlow();
                }
            } else {
                m.beginControlFlow("if ($L == null)", camel(name));
                m.addStatement("$T.err.println($S)", System.class, "Missing required option: --" + kebab(name));
                m.addStatement("$T.exit(2)", System.class);
                m.endControlFlow();
            }
        }
    }

    // ── Tiny builders ─────────────────────────────────────────────────────────

    private AnnotationSpec subCmdAnnotation(String name, String desc) {
        return AnnotationSpec.builder(CMD_ANN)
                .addMember("name", "$S", name)
                .addMember("description", "$S", desc)
                .addMember("mixinStandardHelpOptions", "true")
                .build();
    }

    private FieldSpec fileOption() {
        return FieldSpec.builder(String.class, "file")
                .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                        .addMember("names", "{$S, $S}", "-f", "--file")
                        .addMember("description", "$S", "YAML file or directory (mutually exclusive with a resource subcommand)")
                        .build())
                .build();
    }

    private FieldSpec dryRunOption() {
        return FieldSpec.builder(boolean.class, "dryRun")
                .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                        .addMember("names", "{$S}", "--dry-run")
                        .addMember("description", "$S", "Print what would happen without making API calls")
                        .build())
                .build();
    }

    private FieldSpec continueOnErrorOption() {
        return FieldSpec.builder(boolean.class, "continueOnError")
                .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                        .addMember("names", "{$S}", "--continue-on-error")
                        .addMember("description", "$S", "Continue processing after errors")
                        .build())
                .build();
    }

    private FieldSpec yesOption() {
        return FieldSpec.builder(boolean.class, "yes")
                .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                        .addMember("names", "{$S, $S}", "-y", "--yes")
                        .addMember("description", "$S", "Skip confirmation prompt")
                        .build())
                .build();
    }

    private FieldSpec intOption(String flag, String desc, String fieldName, String defaultVal) {
        return FieldSpec.builder(int.class, fieldName)
                .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                        .addMember("names", "{$S}", flag)
                        .addMember("description", "$S", desc)
                        .addMember("defaultValue", "$S", defaultVal)
                        .build())
                .build();
    }

    private FieldSpec stringOption(String flag, String desc, String fieldName) {
        return FieldSpec.builder(String.class, fieldName)
                .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                        .addMember("names", "{$S}", flag)
                        .addMember("description", "$S", desc)
                        .build())
                .build();
    }

    private FieldSpec stringArrayOption(String flag, String desc, String fieldName) {
        return FieldSpec.builder(String[].class, fieldName)
                .addAnnotation(AnnotationSpec.builder(OPTION_ANN)
                        .addMember("names", "{$S}", flag)
                        .addMember("description", "$S", desc)
                        .addMember("split", "$S", ",")
                        .build())
                .build();
    }

    private MethodSpec buildListQueryMethod() {
        MethodSpec.Builder m = MethodSpec.methodBuilder("buildListQuery")
                .addModifiers(Modifier.PRIVATE)
                .returns(String.class)
                .addStatement("$T<$T> params = new java.util.ArrayList<>()", List.class, String.class)
                .addStatement("params.add($S + encodeQuery(String.valueOf(page)))", "page%5Bnumber%5D=")
                .addStatement("params.add($S + encodeQuery(String.valueOf(size)))", "page%5Bsize%5D=")
                .beginControlFlow("if (filters != null)")
                .beginControlFlow("for ($T filter : filters)", String.class)
                .beginControlFlow("if (filter == null || filter.isBlank())")
                .addStatement("continue")
                .endControlFlow()
                .addStatement("int eq = filter.indexOf('=')")
                .beginControlFlow("if (eq <= 0)")
                .addStatement("throw new $T($S + filter)", IllegalArgumentException.class,
                        "Invalid --filter value; expected key=value: ")
                .endControlFlow()
                .addStatement("$T key = filter.substring(0, eq)", String.class)
                .addStatement("$T value = filter.substring(eq + 1)", String.class)
                .addStatement("params.add($S + encodeQuery(key) + $S + encodeQuery(value))",
                        "filter%5B", "%5D=")
                .endControlFlow()
                .endControlFlow()
                .beginControlFlow("if (sort != null && !sort.isBlank())")
                .addStatement("params.add($S + encodeQuery(sort))", "sort=")
                .endControlFlow()
                .beginControlFlow("if (include != null && !include.isBlank())")
                .addStatement("params.add($S + encodeQuery(include))", "include=")
                .endControlFlow()
                .addStatement("return $S + $T.join($S, params)", "?", String.class, "&");
        return m.build();
    }

    private MethodSpec encodeQueryMethod() {
        return MethodSpec.methodBuilder("encodeQuery")
                .addModifiers(Modifier.PRIVATE)
                .returns(String.class)
                .addParameter(String.class, "value")
                .addStatement("return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)")
                .build();
    }

    private FieldSpec requiredPositionalId() {
        return FieldSpec.builder(String.class, "id")
                .addAnnotation(AnnotationSpec.builder(PARAMS_ANN)
                        .addMember("index", "$S", "0")
                        .addMember("description", "$S", "Resource ID")
                        .build())
                .build();
    }

    private MethodSpec.Builder baseRun() {
        return MethodSpec.methodBuilder("run")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .beginControlFlow("try");
    }

    private void preamble(MethodSpec.Builder m, String parentRef) {
        String root = parentRef + ".root";
        m.addStatement("var config = $T.load()", CONFIG_STORE);
        m.addStatement("var profile = $T.activeProfile(config, $L.global.profile)", CONFIG_STORE, root);
        m.addStatement("$T.applyParentOverrides($L.global, profile)", FILE_OPS, root);
        m.addStatement("var client = new $T(profile.server, profile, $L.global.verbose)", API_CLIENT, root);
        m.addStatement("$T version = $L.global.apiVersion != null ? $L.global.apiVersion : (profile.apiVersion != null ? profile.apiVersion : $T.DEFAULT_API_VERSION)",
                String.class, root, root, GLOBAL_OPTIONS);
        m.addStatement("$T versionPrefix = (version != null && !version.isBlank()) ? $S + version : $S",
                String.class, "/api/v", "/api");
    }

    private void catchBlock(MethodSpec.Builder m) {
        m.nextControlFlow("catch ($T e)", Exception.class);
        m.addStatement("$T.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + $S + e.toString())",
                System.class, ": ");
        m.addStatement("$T.exit(1)", System.class);
        m.endControlFlow();
    }

    // ── Type / name helpers ───────────────────────────────────────────────────

    private TypeName javaType(String manifestType) {
        if (manifestType == null) return ClassName.get(String.class);
        return switch (manifestType) {
            case "integer"  -> ClassName.get(Integer.class);
            case "decimal"  -> ClassName.get("java.math", "BigDecimal");
            case "boolean"  -> ClassName.get(Boolean.class);
            default         -> ClassName.get(String.class);
        };
    }

    private String resourceName(EntityDef entity) {
        return entity.plural() != null ? entity.plural().toLowerCase()
                : entity.name().toLowerCase() + "s";
    }

    private String resourcePath(EntityDef entity) {
        return resourceName(entity);
    }

    private static String kebab(String name) {
        return name.replaceAll("([A-Z])", "-$1").replace('_', '-').toLowerCase().replaceFirst("^-", "");
    }

    private static String camel(String name) {
        if (name == null || name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') { nextUpper = true; }
            else if (nextUpper) { sb.append(Character.toUpperCase(c)); nextUpper = false; }
            else { sb.append(c); }
        }
        return Character.toLowerCase(sb.charAt(0)) + sb.substring(1);
    }
}
