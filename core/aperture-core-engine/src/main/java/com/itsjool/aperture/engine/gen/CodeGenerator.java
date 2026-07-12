package com.itsjool.aperture.engine.gen;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.hook.HookSemantics;
import com.itsjool.aperture.engine.hook.HookSemanticsResolver;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.HookDef;
import com.itsjool.aperture.engine.model.OneOfDef;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class CodeGenerator {

    private final HookSemanticsResolver hookSemanticsResolver = new HookSemanticsResolver();
    
    public List<String> generateForEntity(EntityDef entityDef, TenancyMode tenancyMode, List<String> activeVersions) {
        return generateForEntity(entityDef, tenancyMode, activeVersions, Map.of());
    }

    public List<String> generateForEntity(EntityDef entityDef, TenancyMode tenancyMode, List<String> activeVersions,
                                          Map<String, EntityDef> allEntities) {
        return generateForEntity(entityDef, tenancyMode, activeVersions, allEntities, List.of());
    }

    public List<String> generateForEntity(EntityDef entityDef, TenancyMode tenancyMode, List<String> activeVersions,
                                          Map<String, EntityDef> allEntities, List<OneOfDef> oneOfs) {
        List<String> generated = new ArrayList<>();
        
        if (entityDef.fields() != null) {
            for (Map.Entry<String, FieldDef> entry : entityDef.fields().entrySet()) {
                if (entry.getValue().encrypted()) {
                    generated.add(generateAttributeConverter(entityDef, entry.getKey(), entry.getValue()));
                }
            }
        }
        
        for (String version : activeVersions) {
            generated.add(generatePackageInfo(version));
            generated.add(generateEntityForVersion(entityDef, tenancyMode, version, allEntities, oneOfs));
            if (tenancyMode == com.itsjool.aperture.engine.config.TenancyMode.POOL && entityDef.tenantScoped()) {
                generated.add(generateFilterCheck(entityDef, "TenantFilter", "apertureTenantId", true, version));
                generated.add(generateTenantIdSetterHook(entityDef, version));
            }
            if (entityDef.softDelete()) {
                generated.add(generateFilterCheck(entityDef, "SoftDeleteFilter", "deletedAt", false, version));
            }
            if (entityDef.scopedBy() != null) {
                generated.add(generateScopeFilterCheck(entityDef, version));
                generated.add(generateScopeValidationHook(entityDef, version));
            }
            if (entityDef.hooks() != null) {
                for (Map.Entry<String, HookDef> hookEntry : entityDef.hooks().entrySet()) {
                    generated.add(generateLifecycleHook(entityDef, hookEntry.getKey(), hookEntry.getValue(), version));
                }
            }
            if (entityDef.permissions() != null) {
                for (String role : entityDef.permissions().keySet()) {
                    generated.add(generateRoleCheck(entityDef, role, version));
                }
            }
        } // Closing brace for activeVersions loop



        return generated;
    }

    public List<String> generateOneOfInterfaces(List<OneOfDef> oneOfs, List<String> activeVersions) {
        List<String> generated = new ArrayList<>();
        if (oneOfs == null) {
            return generated;
        }
        for (String version : activeVersions) {
            for (OneOfDef oneOf : oneOfs) {
                TypeSpec oneOfInterface = TypeSpec.interfaceBuilder(oneOf.name() + "V" + version)
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(ClassName.get("com.yahoo.elide.annotation", "MappedInterface"))
                    .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "Include"))
                        .addMember("name", "$S", oneOf.name().toLowerCase(Locale.ROOT))
                        .addMember("rootLevel", "$L", false)
                        .build())
                    .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "ReadPermission"))
                        .addMember("expression", "$S", "Prefab.Role.All")
                        .build())
                    .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "CreatePermission"))
                        .addMember("expression", "$S", "Prefab.Role.None")
                        .build())
                    .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "UpdatePermission"))
                        .addMember("expression", "$S", "Prefab.Role.None")
                        .build())
                    .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "DeletePermission"))
                        .addMember("expression", "$S", "Prefab.Role.None")
                        .build())
                    .addMethod(MethodSpec.methodBuilder("getId")
                        .addAnnotation(ClassName.get("com.yahoo.elide.annotation", "EntityId"))
                        .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                        .returns(UUID.class)
                        .build())
                    .addMethod(MethodSpec.methodBuilder("getApertureOneOf")
                        .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                        .returns(String.class)
                        .addStatement("return $S", oneOf.name())
                        .build())
                    .addMethod(MethodSpec.methodBuilder("setApertureOneOf")
                        .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                        .addParameter(String.class, "apertureOneOf")
                        .build())
                    .build();
                generated.add(JavaFile.builder("com.itsjool.aperture.generated.v" + version, oneOfInterface)
                    .build()
                    .toString());
            }
        }
        return generated;
    }

    private String generatePackageInfo(String version) {
        String pkg = "com.itsjool.aperture.generated.v" + version;
        return "/* package-info */\n@com.yahoo.elide.annotation.ApiVersion(version = \"" + version + "\")\npackage " + pkg + ";\n";
    }

    private List<OneOfDef> containingOneOfs(EntityDef entityDef, List<OneOfDef> oneOfs) {
        if (oneOfs == null) {
            return List.of();
        }
        return oneOfs.stream()
            .filter(oneOf -> oneOf.members().contains(entityDef.name()))
            .toList();
    }

    private OneOfDef findOneOf(String name, List<OneOfDef> oneOfs) {
        if (oneOfs != null) {
            for (OneOfDef oneOf : oneOfs) {
                if (oneOf.name().equals(name)) {
                    return oneOf;
                }
            }
        }
        throw new IllegalArgumentException("Unknown oneof target: " + name);
    }

    private String generateEntityForVersion(EntityDef entityDef, TenancyMode tenancyMode, String version,
                                            Map<String, EntityDef> allEntities, List<OneOfDef> oneOfs) {
        ClassName entityAnnotation = ClassName.get("jakarta.persistence", "Entity");
        ClassName idAnnotation = ClassName.get("jakarta.persistence", "Id");
        
        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(entityDef.name() + "V" + version)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(entityAnnotation)
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "LifeCycleHookBinding"))
                .addMember("operation", "$T.$L", ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "Operation"), "CREATE")
                .addMember("phase", "$T.$L", ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "TransactionPhase"), "POSTCOMMIT")
                .addMember("hook", "$T.class", ClassName.bestGuess(ApertureRuntimeClassNames.AUDIT_BRIDGE))
                .build())
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "LifeCycleHookBinding"))
                .addMember("operation", "$T.$L", ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "Operation"), "DELETE")
                .addMember("phase", "$T.$L", ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "TransactionPhase"), "POSTCOMMIT")
                .addMember("hook", "$T.class", ClassName.bestGuess(ApertureRuntimeClassNames.AUDIT_BRIDGE))
                .build())
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("jakarta.persistence", "Table"))
                .addMember("name", "$S", entityDef.plural() != null ? "aperture_" + entityDef.plural().toLowerCase() : "aperture_" + entityDef.name().toLowerCase() + "s")
                .build())
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "Include"))
                .addMember("name", "$S", entityDef.plural() != null ? entityDef.plural().toLowerCase() : entityDef.name().toLowerCase() + "s")
                .build());

        for (OneOfDef oneOf : containingOneOfs(entityDef, oneOfs)) {
            typeBuilder.addSuperinterface(ClassName.get("com.itsjool.aperture.generated.v" + version, oneOf.name() + "V" + version));
        }

        String tenantCheck = (tenancyMode == TenancyMode.POOL && entityDef.tenantScoped()) ? entityDef.name() + "V" + version + "TenantFilter" : null;
        String scopeCheck = entityDef.scopedBy() != null ? entityDef.name() + "V" + version + "ScopeFilter" : null;
        String filterChecks = java.util.stream.Stream.of(tenantCheck, scopeCheck)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.joining(" AND "));
        String softDeleteCheck = entityDef.softDelete() ? entityDef.name() + "V" + version + "SoftDeleteFilter" : null;

        java.util.Map<String, java.util.List<String>> roleOps = new java.util.HashMap<>();
        roleOps.put("read", new java.util.ArrayList<>());
        roleOps.put("create", new java.util.ArrayList<>());
        roleOps.put("update", new java.util.ArrayList<>());
        roleOps.put("delete", new java.util.ArrayList<>());

        if (entityDef.permissions() != null) {
            for (java.util.Map.Entry<String, java.util.List<String>> p : entityDef.permissions().entrySet()) {
                String ruleName = entityDef.name() + capitalize(sanitizeIdentifier(p.getKey())) + "Check";
                for (String op : p.getValue()) {
                    if (roleOps.containsKey(op.toLowerCase())) {
                        roleOps.get(op.toLowerCase()).add(ruleName);
                    }
                }
            }
        }

        java.util.Map<String, java.util.List<String>> policyOps = new java.util.HashMap<>();
        policyOps.put("read", new java.util.ArrayList<>());
        policyOps.put("create", new java.util.ArrayList<>());
        policyOps.put("update", new java.util.ArrayList<>());
        policyOps.put("delete", new java.util.ArrayList<>());

        if (entityDef.policies() != null) {
            for (java.util.Map.Entry<String, java.util.List<String>> p : entityDef.policies().entrySet()) {
                String ruleName = capitalize(sanitizeIdentifier(p.getKey())) + "Check";
                for (String op : p.getValue()) {
                    if (policyOps.containsKey(op.toLowerCase())) {
                        policyOps.get(op.toLowerCase()).add(ruleName);
                    }
                }
            }
        }

        java.util.List<String> publicOps = entityDef.publicOperations() != null ? 
            entityDef.publicOperations().stream().map(String::toLowerCase).toList() : java.util.List.of();

        for (String op : roleOps.keySet()) {
            String expr;
            if (publicOps.contains(op)) {
                expr = "Prefab.Role.All";
            } else {
                java.util.List<String> opRoles = roleOps.get(op);
                java.util.List<String> opPolicies = policyOps.get(op);
                
                String roleExpr = opRoles.isEmpty() ? null : "(" + String.join(" OR ", opRoles) + ")";
                String policyExpr = opPolicies.isEmpty() ? null : "(" + String.join(" AND ", opPolicies) + ")";
                
                if (roleExpr != null && policyExpr != null) {
                    expr = "(" + roleExpr + " AND " + policyExpr + ")";
                } else if (roleExpr != null) {
                    expr = roleExpr;
                } else if (policyExpr != null) {
                    expr = policyExpr;
                } else {
                    expr = "Prefab.Role.None";
                }
            }

            String innerExpr = expr;
            
            String accessExpr;
            if (!filterChecks.isEmpty()) {
                if (innerExpr.equals("Prefab.Role.All")) {
                    accessExpr = "SuperAdminCheck OR " + filterChecks;
                } else {
                    accessExpr = "SuperAdminCheck OR (" + filterChecks + " AND (TenantAdminCheck OR " + innerExpr + "))";
                }
            } else {
                if (innerExpr.equals("Prefab.Role.All")) {
                    accessExpr = "Prefab.Role.All";
                } else {
                    accessExpr = "SuperAdminCheck OR " + innerExpr;
                }
            }

            String finalExpr = accessExpr;
            if ("read".equals(op) && softDeleteCheck != null) {
                if (accessExpr.equals("Prefab.Role.All")) {
                    finalExpr = softDeleteCheck;
                } else {
                    finalExpr = "(" + accessExpr + ") AND " + softDeleteCheck;
                }
            }
            
            String annName = switch (op) {
                case "read" -> "ReadPermission";
                case "create" -> "CreatePermission";
                case "update" -> "UpdatePermission";
                case "delete" -> "DeletePermission";
                default -> "ReadPermission";
            };
            typeBuilder.addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", annName))
                .addMember("expression", "$S", finalExpr)
                .build());
        }

        if (tenancyMode == TenancyMode.POOL && entityDef.tenantScoped()) {
            typeBuilder.addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "LifeCycleHookBinding"))
                .addMember("operation", "$T.CREATE", ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "Operation"))
                .addMember("phase", "$T.PRESECURITY", ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "TransactionPhase"))
                .addMember("hook", "$T.class", ClassName.get("com.itsjool.aperture.generated.hooks.v" + version, entityDef.name() + "V" + version + "TenantIdHook"))
                .build());
        }

        if (entityDef.scopedBy() != null) {
            // Write-path is validate-not-inject (v1 policy): if a scope value for this
            // entity's scopedBy field is present in ScopeContextHolder and the incoming
            // relationship doesn't match it, reject; otherwise (no scope in context) allow.
            // Bound on both CREATE and UPDATE since either can (re)point the relationship.
            for (String op : java.util.List.of("CREATE", "UPDATE")) {
                typeBuilder.addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "LifeCycleHookBinding"))
                    .addMember("operation", "$T.$L", ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "Operation"), op)
                    .addMember("phase", "$T.PRESECURITY", ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "TransactionPhase"))
                    .addMember("hook", "$T.class", ClassName.get("com.itsjool.aperture.generated.hooks.v" + version, entityDef.name() + "V" + version + "ScopeValidationHook"))
                    .build());
            }
        }

        if (entityDef.hooks() != null) {
            for (Map.Entry<String, HookDef> hookEntry : entityDef.hooks().entrySet()) {
                String hookClassName = entityDef.name() + "V" + version + capitalize(hookEntry.getKey()) + "Hook";
                HookSemantics semantics = hookSemanticsResolver.resolve(entityDef.name(), hookEntry.getKey(), hookEntry.getValue());
                for (String operation : semantics.operations()) {
                    typeBuilder.addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "LifeCycleHookBinding"))
                        .addMember("operation", "$T.$L", ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "Operation"), operation.toUpperCase(Locale.ROOT))
                        .addMember("phase", "$T.$L", ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "TransactionPhase"), semantics.phase())
                        .addMember("hook", "$T.class", ClassName.get("com.itsjool.aperture.generated.hooks.v" + version, hookClassName))
                        .build());
                }
            }
        }

        typeBuilder.addField(FieldSpec.builder(UUID.class, "id", Modifier.PRIVATE)
            .addAnnotation(idAnnotation)
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("jakarta.persistence", "GeneratedValue"))
                .addMember("strategy", "$T.UUID", ClassName.get("jakarta.persistence", "GenerationType"))
                .build())
            .build());
        typeBuilder.addMethod(MethodSpec.methodBuilder("getId")
            .addModifiers(Modifier.PUBLIC)
            .returns(UUID.class)
            .addStatement("return this.id")
            .build());
        typeBuilder.addMethod(MethodSpec.methodBuilder("setId")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addParameter(UUID.class, "id")
            .addStatement("this.id = id")
            .build());

        if (entityDef.fields() != null) {
            for (Map.Entry<String, FieldDef> entry : entityDef.fields().entrySet()) {
                String fieldName = entry.getKey();
                FieldDef field = entry.getValue();

                if (field.removedIn() != null && Integer.parseInt(version) >= Integer.parseInt(field.removedIn())) continue;
                if (field.since() != null && Integer.parseInt(version) < Integer.parseInt(field.since())) continue;

                FieldSpec.Builder fieldBuilder;
                com.palantir.javapoet.TypeName fieldTypeName = null;
                if ("oneof".equalsIgnoreCase(field.type())) {
                    OneOfDef oneOf = findOneOf(field.targetClass(), oneOfs);
                    fieldTypeName = ClassName.get("com.itsjool.aperture.generated.v" + version, field.targetClass() + "V" + version);
                    fieldBuilder = FieldSpec.builder(fieldTypeName, fieldName, Modifier.PRIVATE)
                        .addAnnotation(ClassName.get("com.yahoo.elide.annotation", "ToOne"))
                        .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("org.hibernate.annotations", "Any"))
                            .addMember("optional", "$L", !field.required())
                            .build())
                        .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("org.hibernate.annotations", "AnyDiscriminator"))
                            .addMember("value", "$T.STRING", ClassName.get("jakarta.persistence", "DiscriminatorType"))
                            .build())
                        .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("org.hibernate.annotations", "AnyKeyJavaClass"))
                            .addMember("value", "$T.class", UUID.class)
                            .build())
                        .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("jakarta.persistence", "Column"))
                            .addMember("name", "$S", fieldName + "_type")
                            .build())
                        .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("jakarta.persistence", "JoinColumn"))
                            .addMember("name", "$S", fieldName + "_id")
                            .build());
                    for (String member : oneOf.members()) {
                        fieldBuilder.addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("org.hibernate.annotations", "AnyDiscriminatorValue"))
                            .addMember("discriminator", "$S", member)
                            .addMember("entity", "$T.class", ClassName.get("com.itsjool.aperture.generated.v" + version, member + "V" + version))
                            .build());
                    }
                } else if (field.targetClass() != null) {
                    if ("OneToMany".equals(field.relation()) || "ManyToMany".equals(field.relation())) {
                        fieldTypeName = ParameterizedTypeName.get(ClassName.get(java.util.Set.class), ClassName.get("com.itsjool.aperture.generated.v" + version, field.targetClass() + "V" + version));
                    } else {
                        fieldTypeName = ClassName.get("com.itsjool.aperture.generated.v" + version, field.targetClass() + "V" + version);
                    }
                    fieldBuilder = FieldSpec.builder(fieldTypeName, fieldName, Modifier.PRIVATE);
                    if ("OneToMany".equals(field.relation())) {
                        fieldBuilder.addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("jakarta.persistence", "OneToMany"))
                            .addMember("mappedBy", "$S", field.mappedBy())
                            .addMember("cascade", "$T.ALL", ClassName.get("jakarta.persistence", "CascadeType"))
                            .build());
                    } else if ("ManyToOne".equals(field.relation())) {
                        fieldBuilder.addAnnotation(ClassName.get("jakarta.persistence", "ManyToOne"));
                        fieldBuilder.addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("jakarta.persistence", "JoinColumn"))
                            .addMember("name", "$S", fieldName + "_id")
                            .build());
                    }
                } else {
                    Class<?> fieldType;
                    String typeName = field.type() != null ? field.type().toLowerCase() : "";
                    switch (typeName) {
                        case "string" -> fieldType = String.class;
                        case "uuid" -> fieldType = UUID.class;
                        case "integer" -> fieldType = Integer.class;
                        case "boolean" -> fieldType = Boolean.class;
                        case "datetime", "date-time" -> fieldType = LocalDateTime.class;
                        case "decimal" -> fieldType = java.math.BigDecimal.class;
                        default -> fieldType = Object.class;
                    }
                    fieldTypeName = ClassName.get(fieldType);
                    fieldBuilder = FieldSpec.builder(fieldTypeName, fieldName, Modifier.PRIVATE);
                }
                
                if (field.encrypted()) {
                    String converterName = entityDef.name() + capitalize(fieldName) + "Converter";
                    fieldBuilder.addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("jakarta.persistence", "Convert")).addMember("converter", "$T.class", ClassName.get("com.itsjool.aperture.generated", converterName)).build());
                }
                
                if (field.required()) {
                    fieldBuilder.addAnnotation(ClassName.get("jakarta.validation.constraints", "NotNull"));
                }
                // Only scalar fields get the per-field UPDATE audit hook. For relationship fields,
                // ChangeSpec's before/after are entity references or collections; serializing those
                // risks LazyInitializationException, unbounded/circular serialization, or leaking a
                // whole related entity into the audit trail.
                if (field.targetClass() == null) {
                    fieldBuilder.addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "LifeCycleHookBinding"))
                        .addMember("operation", "$T.$L", ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "Operation"), "UPDATE")
                        .addMember("phase", "$T.$L", ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "TransactionPhase"), "POSTCOMMIT")
                        .addMember("hook", "$T.class", ClassName.bestGuess(ApertureRuntimeClassNames.AUDIT_BRIDGE))
                        .build());
                }
                typeBuilder.addField(fieldBuilder.build());

                // Getters and Setters
                typeBuilder.addMethod(MethodSpec.methodBuilder("get" + capitalize(fieldName))
                    .addModifiers(Modifier.PUBLIC)
                    .returns(fieldTypeName)
                    .addStatement("return this.$L", fieldName)
                    .build());
                typeBuilder.addMethod(MethodSpec.methodBuilder("set" + capitalize(fieldName))
                    .addModifiers(Modifier.PUBLIC)
                    .returns(void.class)
                    .addParameter(fieldTypeName, "val")
                    .addStatement("this.$L = val", fieldName)
                    .build());
            }
        }

        if (tenancyMode == TenancyMode.POOL && entityDef.tenantScoped()) {
            typeBuilder.addField(FieldSpec.builder(UUID.class, "apertureTenantId", Modifier.PRIVATE).build());
            typeBuilder.addMethod(MethodSpec.methodBuilder("getApertureTenantId")
                .addModifiers(Modifier.PUBLIC)
                .returns(UUID.class)
                .addStatement("return this.apertureTenantId")
                .build());
            typeBuilder.addMethod(MethodSpec.methodBuilder("setApertureTenantId")
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addParameter(UUID.class, "id")
                .addStatement("this.apertureTenantId = id")
                .build());
            typeBuilder.addMethod(MethodSpec.methodBuilder("ensureApertureTenantId")
                .addAnnotation(ClassName.get("jakarta.persistence", "PrePersist"))
                .addModifiers(Modifier.PRIVATE)
                .returns(void.class)
                .addStatement("if (this.apertureTenantId != null) return")
                .addStatement("java.lang.String tenantId = $L.getTenantId()", ApertureRuntimeClassNames.TENANT_CONTEXT_HOLDER)
                .addStatement("if (tenantId != null) this.apertureTenantId = java.util.UUID.fromString(tenantId)")
                .build());
        }

        if (entityDef.optimisticLocking()) {
            typeBuilder.addField(FieldSpec.builder(Integer.class, "version", Modifier.PRIVATE)
                .addAnnotation(ClassName.get("jakarta.persistence", "Version"))
                .build());
            
            typeBuilder.addMethod(MethodSpec.methodBuilder("getVersion")
                .addModifiers(Modifier.PUBLIC)
                .returns(Integer.class)
                .addStatement("return this.version")
                .build());
                
            typeBuilder.addMethod(MethodSpec.methodBuilder("setVersion")
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addParameter(Integer.class, "version")
                .addStatement("this.version = version")
                .build());
        }

        if (entityDef.softDelete()) {
            typeBuilder.addField(FieldSpec.builder(OffsetDateTime.class, "deletedAt", Modifier.PRIVATE).build());
        }

        return JavaFile.builder("com.itsjool.aperture.generated.v" + version, typeBuilder.build()).build().toString();
    }

    private String generateTenantIdSetterHook(EntityDef entityDef, String version) {
        String className = entityDef.name() + "V" + version + "TenantIdHook";
        TypeSpec hookAdapter = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ParameterizedTypeName.get(ClassName.get("com.yahoo.elide.core.lifecycle", "LifeCycleHook"), ClassName.get("com.itsjool.aperture.generated.v" + version, entityDef.name() + "V" + version)))
            .addMethod(MethodSpec.methodBuilder("execute")
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addParameter(ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "Operation"), "operation")
                .addParameter(ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "TransactionPhase"), "phase")
                .addParameter(ClassName.get("com.itsjool.aperture.generated.v" + version, entityDef.name() + "V" + version), "elideEntity")
                .addParameter(ClassName.get("com.yahoo.elide.core.security", "RequestScope"), "requestScope")
                .addParameter(ParameterizedTypeName.get(ClassName.get("java.util", "Optional"), ClassName.get("com.yahoo.elide.core.security", "ChangeSpec")), "changes")
                .addStatement("$L principal = null", ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("if (requestScope.getUser() != null && requestScope.getUser().getPrincipal() instanceof $L) principal = ($L) requestScope.getUser().getPrincipal()", ApertureRuntimeClassNames.APERTURE_PRINCIPAL, ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("else principal = ($L) org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes().getAttribute(\"aperturePrincipal\", org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST)", ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("if (principal != null && principal.tenantId() != null) elideEntity.setApertureTenantId(java.util.UUID.fromString(principal.tenantId()))")
                .build())
            .build();
        return JavaFile.builder("com.itsjool.aperture.generated.hooks.v" + version, hookAdapter).build().toString();
    }

    /**
     * Generates the write-path {@code scopedBy} lifecycle hook: v1 policy is
     * validate-not-inject. Unlike tenant scoping (which auto-injects), a {@code scopedBy}
     * relationship is explicit on the payload, so this only rejects a mismatch — it never
     * sets the relationship itself. If no scope value is present in {@link com.itsjool.aperture.runtime.scope.ScopeContextHolder}
     * (keyed by the lowercased manifest field name, matching {@code generateScopeFilterCheck}
     * and the header-parsing lowercasing in AuthFilter), the create/update is allowed —
     * a scope is read context, not an ownership grant. If the incoming relationship is
     * simply absent, there is nothing to validate against, so that is also allowed (Elide's
     * own required-field validation is responsible for rejecting a missing required
     * relationship).
     */
    private String generateScopeValidationHook(EntityDef entityDef, String version) {
        String scopedByField = entityDef.scopedBy();
        String scopedByKey = scopedByField.toLowerCase();
        String className = entityDef.name() + "V" + version + "ScopeValidationHook";
        ClassName entityClass = ClassName.get("com.itsjool.aperture.generated.v" + version, entityDef.name() + "V" + version);
        String mismatchMessage = "Cannot set '" + scopedByField + "' to a value outside the current scope";

        TypeSpec hookAdapter = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ParameterizedTypeName.get(ClassName.get("com.yahoo.elide.core.lifecycle", "LifeCycleHook"), entityClass))
            .addMethod(MethodSpec.methodBuilder("execute")
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addParameter(ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "Operation"), "operation")
                .addParameter(ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "TransactionPhase"), "phase")
                .addParameter(entityClass, "elideEntity")
                .addParameter(ClassName.get("com.yahoo.elide.core.security", "RequestScope"), "requestScope")
                .addParameter(ParameterizedTypeName.get(ClassName.get("java.util", "Optional"), ClassName.get("com.yahoo.elide.core.security", "ChangeSpec")), "changes")
                .addStatement("java.lang.String scopeId = $L.get($S)", ApertureRuntimeClassNames.SCOPE_CONTEXT_HOLDER, scopedByKey)
                .addStatement("if (scopeId == null) return")
                .addStatement("if (elideEntity.get$L() == null) return", capitalize(scopedByField))
                .addStatement("if (elideEntity.get$L().getId() == null) return", capitalize(scopedByField))
                .addStatement("if (!scopeId.equals(elideEntity.get$L().getId().toString())) throw new com.yahoo.elide.core.exceptions.BadRequestException($S)", capitalize(scopedByField), mismatchMessage)
                .build())
            .build();
        return JavaFile.builder("com.itsjool.aperture.generated.hooks.v" + version, hookAdapter).build().toString();
    }

    private String generateAttributeConverter(EntityDef entityDef, String fieldName, FieldDef field) {
        String converterName = entityDef.name() + capitalize(fieldName) + "Converter";
        ClassName converterInterface = ClassName.get("jakarta.persistence", "AttributeConverter");
        ParameterizedTypeName parameterized = ParameterizedTypeName.get(converterInterface, ClassName.get(String.class), ClassName.get(String.class));

        TypeSpec converter = TypeSpec.classBuilder(converterName)
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(parameterized)
            .addAnnotation(ClassName.get("jakarta.persistence", "Converter"))
            .addMethod(MethodSpec.methodBuilder("convertToDatabaseColumn")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addParameter(String.class, "attribute")
                .addStatement("if (attribute == null) return null")
                .addStatement("com.itsjool.aperture.spi.EncryptionService encryptionService = $L.getBean(com.itsjool.aperture.spi.EncryptionService.class)", ApertureRuntimeClassNames.SPRING_CONTEXT_HELPER)
                .addStatement("java.lang.String tenantId = $L.getTenantId()", ApertureRuntimeClassNames.TENANT_CONTEXT_HOLDER)
                .addStatement("return encryptionService.encrypt(attribute, new com.itsjool.aperture.spi.EncryptionContext(tenantId, $S, $S, false)).value()", entityDef.name(), fieldName)
                .build())
            .addMethod(MethodSpec.methodBuilder("convertToEntityAttribute")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addParameter(String.class, "dbData")
                .addStatement("if (dbData == null) return null")
                .addStatement("com.itsjool.aperture.spi.EncryptionService encryptionService = $L.getBean(com.itsjool.aperture.spi.EncryptionService.class)", ApertureRuntimeClassNames.SPRING_CONTEXT_HELPER)
                .addStatement("java.lang.String tenantId = $L.getTenantId()", ApertureRuntimeClassNames.TENANT_CONTEXT_HOLDER)
                .addStatement("return encryptionService.decrypt(new com.itsjool.aperture.spi.EncryptedValue(dbData), new com.itsjool.aperture.spi.EncryptionContext(tenantId, $S, $S, false))", entityDef.name(), fieldName)
                .build())
            .build();
        return JavaFile.builder("com.itsjool.aperture.generated", converter).build().toString();
    }

    private String generateFilterCheck(EntityDef entityDef, String suffix, String fieldName, boolean isTenantFilter, String version) {
        String className = entityDef.name() + "V" + version + suffix;
        
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("getFilterExpression")
            .addModifiers(Modifier.PUBLIC)
            .returns(ClassName.get("com.yahoo.elide.core.filter.expression", "FilterExpression"))
            .addParameter(ParameterizedTypeName.get(ClassName.get("com.yahoo.elide.core.type", "Type"), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class)), "entityClass")
            .addParameter(ClassName.get("com.yahoo.elide.core.security", "RequestScope"), "requestScope");
            
        if (isTenantFilter) {
            methodBuilder.addStatement("java.lang.String tenantId = $L.getTenantId()", ApertureRuntimeClassNames.TENANT_CONTEXT_HOLDER);
            methodBuilder.addStatement("if (tenantId == null) throw new com.yahoo.elide.core.exceptions.ForbiddenAccessException(com.yahoo.elide.annotation.ReadPermission.class)");
            methodBuilder.addStatement("return new com.yahoo.elide.core.filter.predicates.InPredicate(new com.yahoo.elide.core.Path.PathElement(entityClass, com.yahoo.elide.core.type.ClassType.of(java.util.UUID.class), $S), java.util.List.of(java.util.UUID.fromString(tenantId)))", fieldName);
        } else {
            methodBuilder.addStatement("return new com.yahoo.elide.core.filter.predicates.IsNullPredicate(new com.yahoo.elide.core.Path.PathElement(entityClass, com.yahoo.elide.core.type.ClassType.of(java.util.UUID.class), $S))", fieldName);
        }
        
        TypeSpec filter = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "SecurityCheck")).addMember("value", "$S", className).build())
            .superclass(ParameterizedTypeName.get(ClassName.get("com.yahoo.elide.core.security.checks", "FilterExpressionCheck"), ClassName.get("com.itsjool.aperture.generated.v" + version, entityDef.name() + "V" + version)))
            .addMethod(methodBuilder.build())
            .build();
        return JavaFile.builder("com.itsjool.aperture.generated.security", filter).build().toString();
    }

    /**
     * Generates a FilterExpressionCheck for {@code scopedBy: <field>} — a real SQL-level
     * filter on the relationship's foreign key, sourced from {@code ScopeContextHolder}
     * (populated from an {@code X-Aperture-Scope-<Field>} header; see the runtime class
     * for what this does and does not guarantee). Denies (like tenant scoping) if no scope
     * value is present in context, rather than silently returning everything.
     */
    private String generateScopeFilterCheck(EntityDef entityDef, String version) {
        String scopedByField = entityDef.scopedBy();
        String scopedByKey = scopedByField.toLowerCase();
        String className = entityDef.name() + "V" + version + "ScopeFilter";
        FieldDef fieldDef = entityDef.fields().get(scopedByField);
        String targetEntityName = fieldDef.targetClass();
        ClassName targetClass = ClassName.get("com.itsjool.aperture.generated.v" + version, targetEntityName + "V" + version);

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("getFilterExpression")
            .addModifiers(Modifier.PUBLIC)
            .returns(ClassName.get("com.yahoo.elide.core.filter.expression", "FilterExpression"))
            .addParameter(ParameterizedTypeName.get(ClassName.get("com.yahoo.elide.core.type", "Type"), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class)), "entityClass")
            .addParameter(ClassName.get("com.yahoo.elide.core.security", "RequestScope"), "requestScope")
            .addStatement("java.lang.String scopeId = $L.get($S)", ApertureRuntimeClassNames.SCOPE_CONTEXT_HOLDER, scopedByKey)
            .addStatement("if (scopeId == null) throw new com.yahoo.elide.core.exceptions.ForbiddenAccessException(com.yahoo.elide.annotation.ReadPermission.class)")
            .addStatement(
                "return new com.yahoo.elide.core.filter.predicates.InPredicate(new com.yahoo.elide.core.Path(java.util.List.of("
                    + "new com.yahoo.elide.core.Path.PathElement(entityClass, com.yahoo.elide.core.type.ClassType.of($T.class), $S), "
                    + "new com.yahoo.elide.core.Path.PathElement(com.yahoo.elide.core.type.ClassType.of($T.class), com.yahoo.elide.core.type.ClassType.of(java.util.UUID.class), $S))), "
                    + "java.util.List.of(java.util.UUID.fromString(scopeId)))",
                targetClass, scopedByField, targetClass, "id");

        TypeSpec filter = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "SecurityCheck")).addMember("value", "$S", className).build())
            .superclass(ParameterizedTypeName.get(ClassName.get("com.yahoo.elide.core.security.checks", "FilterExpressionCheck"), ClassName.get("com.itsjool.aperture.generated.v" + version, entityDef.name() + "V" + version)))
            .addMethod(methodBuilder.build())
            .build();
        return JavaFile.builder("com.itsjool.aperture.generated.security", filter).build().toString();
    }

    public List<String> generatePolicyChecks(List<com.itsjool.aperture.engine.model.AbacPolicyDef> policies) {
        List<String> generated = new ArrayList<>();
        if (policies != null) {
            for (com.itsjool.aperture.engine.model.AbacPolicyDef policy : policies) {
                generated.add(generateGlobalOperationCheck(policy.name(), policy.expression()));
            }
        }
        return generated;
    }

    private String generateGlobalOperationCheck(String ruleName, String spelExpression) {
        String className = capitalize(sanitizeIdentifier(ruleName)) + "Check";
        TypeSpec check = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("org.springframework.stereotype", "Component"))
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "SecurityCheck")).addMember("value", "$S", className).build())
            .superclass(ParameterizedTypeName.get(ClassName.get("com.yahoo.elide.core.security.checks", "OperationCheck"), ClassName.get(Object.class)))
            .addField(FieldSpec.builder(ClassName.get("org.springframework.expression", "Expression"), "EXPRESSION", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T().parseExpression($S)", ClassName.get("org.springframework.expression.spel.standard", "SpelExpressionParser"), spelExpression)
                .build())
            .addMethod(MethodSpec.methodBuilder("ok")
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(Object.class, "record")
                .addParameter(ClassName.get("com.yahoo.elide.core.security", "RequestScope"), "requestScope")
                .addParameter(ClassName.get("java.util", "Optional"), "changeSpec")
                .addStatement("Object recordState = changeSpec.isPresent() && ((com.yahoo.elide.core.security.ChangeSpec) changeSpec.get()).getOriginal() != null ? ((com.yahoo.elide.core.security.ChangeSpec) changeSpec.get()).getOriginal() : record")
                .addStatement("Object inputState = changeSpec.isPresent() && ((com.yahoo.elide.core.security.ChangeSpec) changeSpec.get()).getModified() != null ? ((com.yahoo.elide.core.security.ChangeSpec) changeSpec.get()).getModified() : record")
                .addStatement("$L principal = null", ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("if (requestScope.getUser() != null && requestScope.getUser().getPrincipal() instanceof $L) principal = ($L) requestScope.getUser().getPrincipal()", ApertureRuntimeClassNames.APERTURE_PRINCIPAL, ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("else { org.springframework.web.context.request.RequestAttributes attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes(); if (attrs != null) principal = ($L) attrs.getAttribute(\"aperturePrincipal\", org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST); }", ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("return $L.evaluate(EXPRESSION, recordState, inputState, principal)", ApertureRuntimeClassNames.ABAC_POLICY_EVALUATOR)
                .build())
            .build();
        return JavaFile.builder("com.itsjool.aperture.generated.security", check).build().toString();
    }

    private String generateLifecycleHook(EntityDef entityDef, String hookName, HookDef hookDef, String version) {
        String className = entityDef.name() + "V" + version + capitalize(hookName) + "Hook";
        HookSemantics semantics = hookSemanticsResolver.resolve(entityDef.name(), hookName, hookDef);

        MethodSpec.Builder executeBuilder = MethodSpec.methodBuilder("execute")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addParameter(ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "Operation"), "operation")
            .addParameter(ClassName.get("com.yahoo.elide.annotation.LifeCycleHookBinding", "TransactionPhase"), "phaseArg")
            .addParameter(ClassName.get("com.itsjool.aperture.generated.v" + version, entityDef.name() + "V" + version), "elideEntity")
            .addParameter(ClassName.get("com.yahoo.elide.core.security", "RequestScope"), "requestScope")
            .addParameter(ParameterizedTypeName.get(ClassName.get("java.util", "Optional"), ClassName.get("com.yahoo.elide.core.security", "ChangeSpec")), "changes")
            .addStatement("$L hookExecutor = $L.getBean($L.class)", ApertureRuntimeClassNames.HOOK_EXECUTOR, ApertureRuntimeClassNames.SPRING_CONTEXT_HELPER, ApertureRuntimeClassNames.HOOK_EXECUTOR)
            .beginControlFlow("if (hookExecutor != null)")
            .addStatement("jakarta.servlet.http.HttpServletRequest req = null")
            .beginControlFlow("try")
            .addStatement("req = ((org.springframework.web.context.request.ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getRequest()")
            .nextControlFlow("catch (Exception e)")
            .addComment("Not in web context")
            .endControlFlow()
            .addStatement("String payload = \"{}\"")
            .addStatement("com.fasterxml.jackson.databind.ObjectMapper __om = $L.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)", ApertureRuntimeClassNames.SPRING_CONTEXT_HELPER)
            .beginControlFlow("try")
            .addStatement("if (__om != null) payload = $L.build(elideEntity, __om)", ApertureRuntimeClassNames.HOOK_PAYLOAD_BUILDER)
            .nextControlFlow("catch (Exception e)")
            .addComment("Ignore payload serialization error")
            .endControlFlow();

        if (semantics.enrichment()) {
            // Sync enrichment: call hook, parse response, apply attribute overrides back to entity
            executeBuilder
                .addStatement("String responseBody = hookExecutor.executeHookWithResponse($S, $S, $S, $S, payload, req, $S)", hookName, entityDef.name(), semantics.phase(), hookDef.url(), semantics.onFailure())
                .beginControlFlow("if (responseBody != null && __om != null)")
                .addStatement("$L.applyEnrichmentOverrides(elideEntity, responseBody, __om)", ApertureRuntimeClassNames.HOOK_PAYLOAD_BUILDER)
                .endControlFlow();
        } else {
            if (semantics.async()) {
                executeBuilder
                    .addStatement("hookExecutor.executeHook($S, $S, $S, $S, payload, req, $S, $L, true)",
                        hookName, entityDef.name(), semantics.phase(), hookDef.url(), semantics.onFailure(), 0);
            } else {
                executeBuilder
                    .addStatement("java.util.concurrent.CompletableFuture<Boolean> future = hookExecutor.executeHook($S, $S, $S, $S, payload, req, $S, $L, false)",
                        hookName, entityDef.name(), semantics.phase(), hookDef.url(), semantics.onFailure(), 0)
                    .addStatement("future.join()");
            }
        }

        executeBuilder.endControlFlow();

        TypeSpec hookAdapter = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ParameterizedTypeName.get(ClassName.get("com.yahoo.elide.core.lifecycle", "LifeCycleHook"), ClassName.get("com.itsjool.aperture.generated.v" + version, entityDef.name() + "V" + version)))
            .addMethod(executeBuilder.build())
            .build();
        return JavaFile.builder("com.itsjool.aperture.generated.hooks.v" + version, hookAdapter).build().toString();
    }

    private String generateRoleCheck(EntityDef entityDef, String roleName, String version) {
        String className = entityDef.name() + capitalize(sanitizeIdentifier(roleName)) + "Check";
        TypeSpec check = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("org.springframework.stereotype", "Component"))
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "SecurityCheck")).addMember("value", "$S", className).build())
            .superclass(ClassName.get("com.yahoo.elide.core.security.checks", "UserCheck"))
            .addMethod(MethodSpec.methodBuilder("ok")
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(ClassName.get("com.yahoo.elide.core.security", "User"), "user")
                .addStatement("$L principal = null", ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("if (user != null && user.getPrincipal() instanceof $L) principal = ($L) user.getPrincipal()", ApertureRuntimeClassNames.APERTURE_PRINCIPAL, ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("else principal = ($L) org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes().getAttribute(\"aperturePrincipal\", org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST)", ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("if (principal == null || principal.roles() == null) return false")
                .addStatement("return principal.roles().contains($S)", roleName)
                .build())
            .build();
        return JavaFile.builder("com.itsjool.aperture.generated.security", check).build().toString();
    }

    private String generateSpringContextHelper() {
        TypeSpec helper = TypeSpec.classBuilder("SpringContextHelper")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("org.springframework.stereotype", "Component"))
            .addSuperinterface(ClassName.get("org.springframework.context", "ApplicationContextAware"))
            .addField(FieldSpec.builder(ClassName.get("org.springframework.context", "ApplicationContext"), "context", Modifier.PRIVATE, Modifier.STATIC).build())
            .addMethod(MethodSpec.methodBuilder("setApplicationContext")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get("org.springframework.context", "ApplicationContext"), "applicationContext")
                .addException(ClassName.get("org.springframework.beans", "BeansException"))
                .addStatement("context = applicationContext")
                .build())
            .addMethod(MethodSpec.methodBuilder("getBean")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(com.palantir.javapoet.TypeVariableName.get("T"))
                .returns(com.palantir.javapoet.TypeVariableName.get("T"))
                .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class), com.palantir.javapoet.TypeVariableName.get("T")), "beanClass")
                .addStatement("if (context == null) return null")
                .beginControlFlow("try")
                .addStatement("return context.getBean(beanClass)")
                .nextControlFlow("catch (org.springframework.beans.factory.NoSuchBeanDefinitionException e)")
                .addStatement("return null")
                .endControlFlow()
                .build())
            .build();
        return JavaFile.builder("com.itsjool.aperture.generated.util", helper).build().toString();
    }

    public List<String> generateAdminChecks() {
        List<String> generated = new ArrayList<>();
        
        TypeSpec superAdminCheck = TypeSpec.classBuilder("SuperAdminCheck")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("org.springframework.stereotype", "Component"))
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "SecurityCheck")).addMember("value", "$S", "SuperAdminCheck").build())
            .superclass(ClassName.get("com.yahoo.elide.core.security.checks", "UserCheck"))
            .addMethod(MethodSpec.methodBuilder("ok")
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(ClassName.get("com.yahoo.elide.core.security", "User"), "user")
                .addStatement("$L principal = null", ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("if (user != null && user.getPrincipal() instanceof $L) principal = ($L) user.getPrincipal()", ApertureRuntimeClassNames.APERTURE_PRINCIPAL, ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("else { org.springframework.web.context.request.RequestAttributes attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes(); if (attrs != null) principal = ($L) attrs.getAttribute(\"aperturePrincipal\", org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST); }", ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("if (principal == null) return false")
                .addStatement("return principal.superAdmin()")
                .build())
            .build();
        generated.add(JavaFile.builder("com.itsjool.aperture.generated.security", superAdminCheck).build().toString());

        TypeSpec tenantAdminCheck = TypeSpec.classBuilder("TenantAdminCheck")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("org.springframework.stereotype", "Component"))
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(ClassName.get("com.yahoo.elide.annotation", "SecurityCheck")).addMember("value", "$S", "TenantAdminCheck").build())
            .superclass(ClassName.get("com.yahoo.elide.core.security.checks", "UserCheck"))
            .addMethod(MethodSpec.methodBuilder("ok")
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(ClassName.get("com.yahoo.elide.core.security", "User"), "user")
                .addStatement("$L principal = null", ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("if (user != null && user.getPrincipal() instanceof $L) principal = ($L) user.getPrincipal()", ApertureRuntimeClassNames.APERTURE_PRINCIPAL, ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("else { org.springframework.web.context.request.RequestAttributes attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes(); if (attrs != null) principal = ($L) attrs.getAttribute(\"aperturePrincipal\", org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST); }", ApertureRuntimeClassNames.APERTURE_PRINCIPAL)
                .addStatement("if (principal == null) return false")
                .addStatement("return principal.tenantAdmin()")
                .build())
            .build();
        generated.add(JavaFile.builder("com.itsjool.aperture.generated.security", tenantAdminCheck).build().toString());

        return generated;
    }

    private String capitalize(String str) {
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private String sanitizeIdentifier(String str) {
        if (str == null) return null;
        return str.replaceAll("[^a-zA-Z0-9_]", "");
    }
}
