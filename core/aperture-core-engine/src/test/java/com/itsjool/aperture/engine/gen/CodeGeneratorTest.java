package com.itsjool.aperture.engine.gen;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.HookDef;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CodeGeneratorTest {
    @Test
    void generatesApiVersionOnPackageOnly() {
        EntityDef entity = new EntityDef("Customer", "Customers", null, null, false, false, false,
            Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of());

        List<String> classes = new CodeGenerator()
            .generateForEntity(entity, TenancyMode.NONE, List.of("2"));

        assertThat(classes).anySatisfy(source -> assertThat(source)
            .contains("@com.yahoo.elide.annotation.ApiVersion(version = \"2\")")
            .contains("package com.itsjool.aperture.generated.v2;"));
        assertThat(classes.stream().filter(source -> source.contains("public class CustomerV2")))
            .singleElement()
            .satisfies(source -> assertThat(source).doesNotContain("@ApiVersion"));
    }

    @Test
    void generatesEntityAndSecurityClasses() {
        EntityDef entity = new EntityDef("Customer", "Customers", null, null, true, true, true, Map.of(
            "email", new FieldDef("String", true, true, false, false, "1", null, null, null, null, null, null),
            "ssn", new FieldDef("String", false, false, false, true, "1", null, null, null, null, null, null)
        ), null, Map.of("ViewerRead", List.of("read")), null, null, Map.of("EnrichCustomer", new HookDef("PRECREATE", false, "passthrough", "http://hook")));

        CodeGenerator generator = new CodeGenerator();
        List<String> classes = generator.generateForEntity(entity, TenancyMode.POOL, List.of("1", "2"));
        List<String> policyClasses = generator.generatePolicyChecks(List.of(
            new com.itsjool.aperture.engine.model.AbacPolicyDef("ViewerRead", "#user.roles.contains('Viewer')")
        ));
        classes.addAll(policyClasses);

        String joined = String.join("\n\n", classes);
        assertThat(joined).contains("public class CustomerV1");
        assertThat(joined).contains("public class CustomerV2");
        assertThat(joined).contains("com.yahoo.elide.annotation.Include");
        assertThat(joined).contains("private UUID apertureTenantId;");
        assertThat(joined).contains("@Convert(\n      converter = CustomerSsnConverter.class\n  )");
        assertThat(joined).contains("public class CustomerSsnConverter implements AttributeConverter<String, String>");
        assertThat(joined).contains("public class CustomerV2TenantFilter extends FilterExpressionCheck<CustomerV2>");
        assertThat(joined).contains("public class CustomerV2SoftDeleteFilter extends FilterExpressionCheck<CustomerV2>");
        assertThat(joined).contains("@Component");
        assertThat(joined).contains("public class ViewerReadCheck extends OperationCheck");
        assertThat(joined).contains("public class CustomerV1EnrichCustomerHook implements LifeCycleHook<CustomerV1>");
        assertThat(joined).contains("principal.tenantId()");
    }

    @Test
    void generatesUpdateAuditHookOnEntityFields() {
        EntityDef entity = new EntityDef("Product", "Products", null, null, false, false, false, Map.of(
            "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null),
            "price", new FieldDef("decimal", false, false, false, false, null, null, null, null, null, null, null)
        ), null, null, null, Map.of(), Map.of());

        List<String> classes = new CodeGenerator().generateForEntity(entity, TenancyMode.NONE, List.of("1"));
        String joined = String.join("\n\n", classes);

        assertThat(joined).containsSubsequence(
            "operation = Operation.UPDATE",
            "hook = AuditBridge.class",
            "private String name;"
        );
        assertThat(joined).containsSubsequence(
            "operation = Operation.UPDATE",
            "hook = AuditBridge.class",
            "private BigDecimal price;"
        );
    }

    @Test
    void doesNotGenerateAuditHookOnRelationshipFields() {
        // Per-field UPDATE audit hooks only make sense for scalar columns. On a relationship field
        // ChangeSpec's before/after are entity references or lazy collections; feeding those to the
        // AuditBridge serializer risks LazyInitializationException, unbounded/circular output, or
        // leaking a whole related entity into the audit trail. Relationship fields must be skipped.
        EntityDef entity = new EntityDef("Invoice", "Invoices", null, null, false, false, false, Map.of(
            "amount", new FieldDef("decimal", true, false, false, false, null, null, null, null, null, null, null),
            "customer", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Customer", null, null)
        ), null, null, null, Map.of(), Map.of());

        List<String> classes = new CodeGenerator().generateForEntity(entity, TenancyMode.NONE, List.of("1"));
        String joined = String.join("\n\n", classes);

        // The scalar field still carries its per-field audit hook...
        assertThat(joined).containsSubsequence(
            "operation = Operation.UPDATE",
            "hook = AuditBridge.class",
            "private BigDecimal amount;"
        );
        // ...but the relationship is generated (@ManyToOne present) with no audit hook. The entity
        // always carries class-level CREATE/DELETE audit hooks; the per-field hook is the UPDATE
        // binding, and exactly one field — the scalar amount — gets it, never the relation.
        assertThat(joined).contains("@ManyToOne");
        int perFieldUpdateHooks = joined.split("operation = Operation.UPDATE", -1).length - 1;
        assertThat(perFieldUpdateHooks).isEqualTo(1);
    }

    @Test
    void since_fieldAbsentFromEarlierVersions_presentFromTargetVersion() {
        EntityDef entity = new EntityDef("Product", "products", null, null, false, false, false, Map.of(
            "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null),
            "sku", new FieldDef("String", true, false, false, false, "2", null, null, null, null, null, null)
        ), null, null, null, Map.of(), Map.of());

        CodeGenerator generator = new CodeGenerator();
        List<String> v1Classes = generator.generateForEntity(entity, TenancyMode.NONE, List.of("1"));
        List<String> v2Classes = generator.generateForEntity(entity, TenancyMode.NONE, List.of("2"));

        String v1Source = String.join("\n", v1Classes);
        String v2Source = String.join("\n", v2Classes);

        assertThat(v1Source).contains("ProductV1").doesNotContain("private String sku");
        assertThat(v2Source).contains("ProductV2").contains("private String sku");
    }

    @Test
    void removedIn_fieldPresentBeforeTargetVersion_absentFromTargetVersion() {
        EntityDef entity = new EntityDef("Product", "products", null, null, false, false, false, Map.of(
            "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null),
            "legacyCode", new FieldDef("String", false, false, false, false, null, "2", null, null, null, null, null)
        ), null, null, null, Map.of(), Map.of());

        CodeGenerator generator = new CodeGenerator();
        List<String> v1Classes = generator.generateForEntity(entity, TenancyMode.NONE, List.of("1"));
        List<String> v2Classes = generator.generateForEntity(entity, TenancyMode.NONE, List.of("2"));

        String v1Source = String.join("\n", v1Classes);
        String v2Source = String.join("\n", v2Classes);

        assertThat(v1Source).contains("private String legacyCode");
        assertThat(v2Source).doesNotContain("legacyCode");
    }

    @Test
    void renamedFrom_fieldGeneratesWithNewJavaName() {
        // renamedFrom only drives the Liquibase migration — CodeGenerator generates the Java field
        // using the new (correct) name; the column rename is handled by ChangesetGenerator
        EntityDef entity = new EntityDef("Product", "products", null, null, false, false, false, Map.of(
            "unit_price", new FieldDef("decimal", false, false, false, false, null, null, "unitPrice", null, null, null, null)
        ), null, null, null, Map.of(), Map.of());

        List<String> classes = new CodeGenerator().generateForEntity(entity, TenancyMode.NONE, List.of("1"));
        String joined = String.join("\n", classes);

        assertThat(joined).contains("private BigDecimal unit_price");
        assertThat(joined).doesNotContain("unitPrice");
    }

    @Test
    void tenantScopedManyToOneEmitsSingleJoinColumn() {
        EntityDef invoice = new EntityDef("Invoice", "invoices", null, null, false, false, true, Map.of(
            "customer", new FieldDef("ref", true, false, false, false, "1", null, null, "ManyToOne", "Customer", null, null)
        ), null, null, null, Map.of(), Map.of());

        CodeGenerator generator = new CodeGenerator();
        List<String> classes = generator.generateForEntity(invoice, TenancyMode.POOL, List.of("1"));

        String joined = String.join("\n\n", classes);
        assertThat(joined).contains("@ManyToOne");
        assertThat(joined).contains("@JoinColumn(")
            .contains("name = \"customer_id\"");
        assertThat(joined).doesNotContain("@JoinColumns");
        assertThat(joined).contains("private CustomerV1 customer;");
    }

    @Test
    void tenantScopedPermissionsUseGeneratedAdminCheckNamesOnce() {
        EntityDef customer = new EntityDef("Customer", "customers", null, null, false, false, true,
                Map.of(), Map.of("Viewer", List.of("read")), Map.of(), List.of(), Map.of(), Map.of());

        List<String> classes = new CodeGenerator().generateForEntity(customer, TenancyMode.POOL, List.of("1"));
        String joined = String.join("\n\n", classes);

        assertThat(joined).contains("expression = \"SuperAdminCheck OR (CustomerV1TenantFilter AND (TenantAdminCheck OR (CustomerViewerCheck)))\"");
        assertThat(joined).doesNotContain("SuperAdmin OR TenantAdmin");
    }

    @Test
    void scopedByGeneratesFilterCheckFilteringOnRelationshipTarget() {
        EntityDef task = new EntityDef("Task", "tasks", null, null, false, false, false,
            Map.of("project", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Project", null, null)),
            null, null, null, null, null, "project");

        List<String> classes = new CodeGenerator().generateForEntity(task, TenancyMode.NONE, List.of("1"));
        String joined = String.join("\n\n", classes);

        assertThat(joined).contains("public class TaskV1ScopeFilter extends FilterExpressionCheck<TaskV1>");
        assertThat(joined).contains("ScopeContextHolder.get(\"project\")");
        assertThat(joined).contains("ForbiddenAccessException");
        // Filters via the relationship (ProjectV1.id), not a raw "project_id" scalar attribute —
        // scopedBy points at a ManyToOne field, so the predicate must traverse it.
        assertThat(joined).contains("ClassType.of(ProjectV1.class)");
    }

    @Test
    void scopedByWithCamelCaseFieldLowercasesScopeContextHolderLookupKey() {
        // AuthFilter lowercases the X-Aperture-Scope-<Field> header suffix when populating
        // ScopeContextHolder, so the generated lookup must canonicalize to the same lowercase
        // key or a camelCase scopedBy field (e.g. parentProject) can never match, causing a
        // permanent 403.
        EntityDef task = new EntityDef("Task", "tasks", null, null, false, false, false,
            Map.of("parentProject", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Project", null, null)),
            null, null, null, null, null, "parentProject");

        List<String> classes = new CodeGenerator().generateForEntity(task, TenancyMode.NONE, List.of("1"));
        String joined = String.join("\n\n", classes);

        assertThat(joined).contains("ScopeContextHolder.get(\"parentproject\")");
        assertThat(joined).doesNotContain("ScopeContextHolder.get(\"parentProject\")");
        // The join-path field name accessed via reflection must stay the actual Java field name.
        assertThat(joined).contains("\"parentProject\"");
    }

    @Test
    void scopedByGeneratesWritePathValidationHookNotInjection() {
        // v1 policy is validate-not-inject: create/update must reject a scopedBy relationship
        // that mismatches the current ScopeContextHolder value, but never auto-set it (unlike
        // tenant scoping's auto-injection hook).
        EntityDef task = new EntityDef("Task", "tasks", null, null, false, false, false,
            Map.of("parentProject", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Project", null, null)),
            null, null, null, null, null, "parentProject");

        List<String> classes = new CodeGenerator().generateForEntity(task, TenancyMode.NONE, List.of("1"));
        String joined = String.join("\n\n", classes);

        assertThat(joined).contains("public class TaskV1ScopeValidationHook implements LifeCycleHook<TaskV1>");
        // Looks up the scope using the same lowercased key as the read-path filter.
        assertThat(joined).contains("ScopeContextHolder.get(\"parentproject\")");
        // No scope in context → allow (return), never auto-assigns the relationship.
        assertThat(joined).contains("if (scopeId == null) return");
        assertThat(joined).doesNotContain("elideEntity.setParentProject");
        // Mismatch → reject with a 400-mapped exception, not Forbidden (403 is for the read filter).
        assertThat(joined).contains("BadRequestException");
        assertThat(joined).contains("elideEntity.getParentProject().getId().toString()");
        // Bound on CREATE and UPDATE (either can (re)point the relationship), not DELETE.
        assertThat(joined).contains(
            "@LifeCycleHookBinding(\n"
                + "    operation = Operation.CREATE,\n"
                + "    phase = TransactionPhase.PRESECURITY,\n"
                + "    hook = TaskV1ScopeValidationHook.class\n"
                + ")");
        assertThat(joined).contains(
            "@LifeCycleHookBinding(\n"
                + "    operation = Operation.UPDATE,\n"
                + "    phase = TransactionPhase.PRESECURITY,\n"
                + "    hook = TaskV1ScopeValidationHook.class\n"
                + ")");
    }

    @Test
    void scopedByCombinesWithTenantFilterInPermissionExpression() {
        EntityDef task = new EntityDef("Task", "tasks", null, null, false, false, true,
            Map.of("project", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Project", null, null)),
            Map.of("Viewer", List.of("read")), null, null, null, null, "project");

        List<String> classes = new CodeGenerator().generateForEntity(task, TenancyMode.POOL, List.of("1"));
        String joined = String.join("\n\n", classes);

        assertThat(joined).contains(
            "expression = \"SuperAdminCheck OR (TaskV1TenantFilter AND TaskV1ScopeFilter AND (TenantAdminCheck OR (TaskViewerCheck)))\"");
    }
}
