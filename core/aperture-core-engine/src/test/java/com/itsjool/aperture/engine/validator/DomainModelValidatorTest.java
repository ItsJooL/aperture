package com.itsjool.aperture.engine.validator;

import com.itsjool.aperture.engine.model.AbacPolicyDef;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.ApertureConfigDef;
import com.itsjool.aperture.engine.model.HookDef;
import com.itsjool.aperture.engine.model.McpConfig;
import com.itsjool.aperture.engine.model.McpEntityConfig;
import com.itsjool.aperture.engine.model.OneOfDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import com.itsjool.aperture.engine.model.RoleDef;
import com.itsjool.aperture.engine.model.RoleDefinitionDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainModelValidatorTest {

    private final DomainModelValidator validator = new DomainModelValidator();

    private static EntityDef entity(String name, Map<String, FieldDef> fields) {
        return new EntityDef(name, name.toLowerCase() + "s", null, null,
            false, false, false, fields, null, null, null, Map.of(), Map.of());
    }

    private static EntityDef tenantScopedEntity(String name, Map<String, FieldDef> fields) {
        return new EntityDef(name, name.toLowerCase() + "s", null, null,
            false, false, true, fields, null, null, null, Map.of(), Map.of());
    }

    private static FieldDef stringField() {
        return new FieldDef("String", false, false, false, false, null, null, null, null, null, null, null);
    }

    private static FieldDef manyToOneField(String targetClass) {
        return new FieldDef("ref", false, false, false, false, null, null, null, "ManyToOne", targetClass, null, null);
    }

    private static FieldDef oneOfField(String targetClass) {
        return new FieldDef("oneof", false, false, false, false, null, null, null, null, targetClass, null, null);
    }

    // ---------- relationship target validation ----------

    @Test
    void manyToOneTargetReferencesKnownEntity_passes() {
        EntityDef customer = entity("Customer", Map.of("name", stringField()));
        EntityDef invoice = entity("Invoice", Map.of("customer", manyToOneField("Customer")));

        assertThatCode(() -> validator.validate(
            new ResolvedDomainModel(List.of(customer, invoice)), Map.of()))
            .doesNotThrowAnyException();
    }

    @Test
    void manyToOneTargetReferencesUnknownEntity_throws() {
        EntityDef invoice = entity("Invoice", Map.of("customer", manyToOneField("Ghost")));

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(invoice)), Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unknown relationship target")
            .hasMessageContaining("Ghost");
    }

    @Test
    void oneOfFieldTargetReferencesKnownOneOf_passes() {
        EntityDef product = entity("Product", Map.of("name", stringField()));
        EntityDef servicePackage = entity("ServicePackage", Map.of("name", stringField()));
        EntityDef lineItem = entity("LineItem", Map.of("billable", oneOfField("Billable")));
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "ServicePackage"));

        assertThatCode(() -> validator.validate(
            ResolvedDomainModel.builder()
                .entities(List.of(product, servicePackage, lineItem))
                .oneOfs(List.of(billable))
                .build(),
            Map.of()))
            .doesNotThrowAnyException();
    }

    @Test
    void oneOfFieldTargetReferencesEntity_throws() {
        EntityDef product = entity("Product", Map.of("name", stringField()));
        EntityDef lineItem = entity("LineItem", Map.of("billable", oneOfField("Product")));
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "LineItem"));

        assertThatThrownBy(() -> validator.validate(
            ResolvedDomainModel.builder()
                .entities(List.of(product, lineItem))
                .oneOfs(List.of(billable))
                .build(),
            Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unknown oneof target")
            .hasMessageContaining("Product");
    }

    @Test
    void oneOfMemberReferencesUnknownEntity_throws() {
        EntityDef product = entity("Product", Map.of("name", stringField()));
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "Ghost"));

        assertThatThrownBy(() -> validator.validate(
            ResolvedDomainModel.builder()
                .entities(List.of(product))
                .oneOfs(List.of(billable))
                .build(),
            Map.of(billable, "billable.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unknown oneof member")
            .hasMessageContaining("Ghost")
            .hasMessageContaining("billable.yaml");
    }

    @Test
    void oneOfWithThreeMembersRejectsUnknownThirdMember_throws() {
        EntityDef product = entity("Product", Map.of("name", stringField()));
        EntityDef servicePackage = entity("ServicePackage", Map.of("name", stringField()));
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "ServicePackage", "SubscriptionPlan"));

        assertThatThrownBy(() -> validator.validate(
            ResolvedDomainModel.builder()
                .entities(List.of(product, servicePackage))
                .oneOfs(List.of(billable))
                .build(),
            Map.of(billable, "billable.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unknown oneof member")
            .hasMessageContaining("SubscriptionPlan")
            .hasMessageContaining("billable.yaml");
    }

    @Test
    void oneOfWithThreeValidMembersPasses() {
        EntityDef product = entity("Product", Map.of("name", stringField()));
        EntityDef servicePackage = entity("ServicePackage", Map.of("name", stringField()));
        EntityDef subscriptionPlan = entity("SubscriptionPlan", Map.of("name", stringField()));
        EntityDef lineItem = entity("LineItem", Map.of("billable", oneOfField("Billable")));
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "ServicePackage", "SubscriptionPlan"));

        assertThatCode(() -> validator.validate(
            ResolvedDomainModel.builder()
                .entities(List.of(product, servicePackage, subscriptionPlan, lineItem))
                .oneOfs(List.of(billable))
                .build(),
            Map.of()))
            .doesNotThrowAnyException();
    }

    @Test
    void oneOfMembersMustShareTenantShape_throws() {
        EntityDef product = tenantScopedEntity("Product", Map.of("name", stringField()));
        EntityDef servicePackage = entity("ServicePackage", Map.of("name", stringField()));
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "ServicePackage"));

        assertThatThrownBy(() -> validator.validate(
            ResolvedDomainModel.builder()
                .entities(List.of(product, servicePackage))
                .oneOfs(List.of(billable))
                .build(),
            Map.of(billable, "billable.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("same tenant shape")
            .hasMessageContaining("Billable");
    }

    @Test
    void globalEntityCannotReferenceTenantScopedOneOf_throws() {
        EntityDef product = tenantScopedEntity("Product", Map.of("name", stringField()));
        EntityDef servicePackage = tenantScopedEntity("ServicePackage", Map.of("name", stringField()));
        EntityDef lineItem = entity("LineItem", Map.of("billable", oneOfField("Billable")));
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "ServicePackage"));

        assertThatThrownBy(() -> validator.validate(
            ResolvedDomainModel.builder()
                .entities(List.of(product, servicePackage, lineItem))
                .oneOfs(List.of(billable))
                .build(),
            Map.of(lineItem, "line-item.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Global entity LineItem cannot reference tenant-scoped OneOf Billable")
            .hasMessageContaining("LineItem.billable");
    }

    @Test
    void tenantScopedEntityCanReferenceTenantScopedOneOf_passes() {
        EntityDef product = tenantScopedEntity("Product", Map.of("name", stringField()));
        EntityDef servicePackage = tenantScopedEntity("ServicePackage", Map.of("name", stringField()));
        EntityDef lineItem = tenantScopedEntity("LineItem", Map.of("billable", oneOfField("Billable")));
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "ServicePackage"));

        assertThatCode(() -> validator.validate(
            ResolvedDomainModel.builder()
                .entities(List.of(product, servicePackage, lineItem))
                .oneOfs(List.of(billable))
                .build(),
            Map.of()))
            .doesNotThrowAnyException();
    }

    @Test
    void oneOfFieldWithCollectionRelation_throws() {
        EntityDef product = entity("Product", Map.of("name", stringField()));
        EntityDef servicePackage = entity("ServicePackage", Map.of("name", stringField()));
        FieldDef collectionOneOf = new FieldDef("oneof", false, false, false, false,
            null, null, null, "OneToMany", "Billable", null, null);
        EntityDef lineItem = entity("LineItem", Map.of("billable", collectionOneOf));
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "ServicePackage"));

        assertThatThrownBy(() -> validator.validate(
            ResolvedDomainModel.builder()
                .entities(List.of(product, servicePackage, lineItem))
                .oneOfs(List.of(billable))
                .build(),
            Map.of(lineItem, "line-item.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("oneof field LineItem.billable")
            .hasMessageContaining("does not support relation");
    }

    @Test
    void oneOfFieldWithMappedBy_throws() {
        EntityDef product = entity("Product", Map.of("name", stringField()));
        EntityDef servicePackage = entity("ServicePackage", Map.of("name", stringField()));
        FieldDef inverseOneOf = new FieldDef("oneof", false, false, false, false,
            null, null, null, null, "Billable", "lineItem", null);
        EntityDef lineItem = entity("LineItem", Map.of("billable", inverseOneOf));
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "ServicePackage"));

        assertThatThrownBy(() -> validator.validate(
            ResolvedDomainModel.builder()
                .entities(List.of(product, servicePackage, lineItem))
                .oneOfs(List.of(billable))
                .build(),
            Map.of(lineItem, "line-item.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("oneof field LineItem.billable")
            .hasMessageContaining("does not support mappedBy");
    }

    @Test
    void selfReferentialField_throws() {
        EntityDef category = entity("Category", Map.of("parent", manyToOneField("Unknown")));

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(category)), Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unknown relationship target");
    }

    // ---------- scopedBy validation ----------

    private static EntityDef scopedEntity(String name, Map<String, FieldDef> fields, String scopedBy) {
        return new EntityDef(name, name.toLowerCase() + "s", null, null,
            false, false, false, fields, null, null, null, Map.of(), Map.of(), scopedBy);
    }

    @Test
    void scopedByReferencingManyToOneField_passes() {
        EntityDef project = entity("Project", Map.of("name", stringField()));
        EntityDef task = scopedEntity("Task", Map.of("project", manyToOneField("Project")), "project");

        assertThatCode(() -> validator.validate(
            new ResolvedDomainModel(List.of(project, task)), Map.of()))
            .doesNotThrowAnyException();
    }

    @Test
    void scopedByReferencingUndeclaredField_throws() {
        EntityDef task = scopedEntity("Task", Map.of("name", stringField()), "project");

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(task)), Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unknown scopedBy field")
            .hasMessageContaining("project");
    }

    @Test
    void scopedByReferencingNonRelationshipField_throws() {
        EntityDef task = scopedEntity("Task", Map.of("project", stringField()), "project");

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(task)), Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Invalid scopedBy field")
            .hasMessageContaining("ManyToOne");
    }

    // ---------- hook semantic validation ----------

    @Test
    void semanticHookWithInvalidFailureMode_throws() {
        EntityDef entity = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of(), null, null, null, Map.of(),
            Map.of("ValidateOrder", new HookDef("validate", List.of("create"), "passthrough", "http://hook")));

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity)), Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("onFailure 'passthrough' is not allowed");
    }

    @Test
    void semanticHookWithValidFailureMode_passes() {
        EntityDef entity = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of(), null, null, null, Map.of(),
            Map.of("Enrich", new HookDef("mutate", List.of("create"), "passthrough", "http://hook")));

        assertThatCode(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity)), Map.of()))
            .doesNotThrowAnyException();
    }

    @Test
    void semanticMutateHookWithRetries_throws() {
        // Plan 032, Decisions (2026-07-14): mutate is excluded from retries — no per-invocation
        // idempotency key exists for a hook service to dedupe a retried enrichment call safely.
        EntityDef entity = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of(), null, null, null, Map.of(),
            Map.of("Enrich", new HookDef("mutate", List.of("create"), "passthrough", "http://hook", 2)));

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity)), Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("retries is not supported for mutate hooks");
    }

    @Test
    void semanticGuardHookWithRetriesAboveSyncCap_throws() {
        EntityDef entity = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of(), null, null, null, Map.of(),
            Map.of("Guard", new HookDef("guard", List.of("create"), "reject", "http://hook", 3)));

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity)), Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("retries (3) exceeds the maximum of 2");
    }

    // ---------- MCP config validation ----------

    @Test
    void apertureMcpInvalidTool_throws() {
        ApertureConfigDef framework = new ApertureConfigDef(
            List.of(), null, new McpConfig(true, "stateless", List.of("list", "publish")), null);

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(), List.of(), framework, List.of(), List.of(), List.of()), Map.of(framework, "framework.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Invalid MCP tool")
            .hasMessageContaining("publish")
            .hasMessageContaining("framework.yaml");
    }

    @Test
    void apertureMcpInvalidTransport_throws() {
        ApertureConfigDef framework = new ApertureConfigDef(
            List.of(), null, new McpConfig(true, "stdio", List.of("list")), null);

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(), List.of(), framework, List.of(), List.of(), List.of()), Map.of(framework, "framework.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Invalid MCP transport")
            .hasMessageContaining("stdio")
            .hasMessageContaining("framework.yaml");
    }

    @Test
    void entityMcpInvalidTool_throws() {
        EntityDef entity = new EntityDef("Order", "orders", null, new McpEntityConfig(true, List.of("get", "archive")),
            false, false, false, Map.of("name", stringField()), null, null, null, Map.of(), Map.of());

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity)), Map.of(entity, "order.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Invalid MCP tool")
            .hasMessageContaining("archive")
            .hasMessageContaining("order.yaml");
    }

    @Test
    void entityMcpToolNotGrantedByAnyRolePolicyOrPublicOperation_throws() {
        // "delete" is a syntactically valid MCP tool, but nothing on this entity reaches the
        // delete operation: no permissions, no policies, no publicOperations. Plan 016 requires
        // this to be rejected rather than silently producing a tool that can never succeed for
        // anyone but a superadmin.
        EntityDef entity = new EntityDef("Order", "orders", null, new McpEntityConfig(true, List.of("delete")),
            false, false, false, Map.of("name", stringField()),
            Map.of("Viewer", List.of("read")), null, null, Map.of(), Map.of());

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity)), Map.of(entity, "order.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Order")
            .hasMessageContaining("delete")
            .hasMessageContaining("order.yaml");
    }

    @Test
    void entityMcpToolExceedsFrameworkCeiling_throws() {
        // The framework declares a ceiling of list/get only. Order's own permissions grant full
        // CRUD, but the entity still cannot list "delete" as an MCP tool: the ceiling is a hard
        // upper bound, not a default an entity can widen past.
        ApertureConfigDef framework = new ApertureConfigDef(
            List.of(), null, new McpConfig(true, "stateless", List.of("list", "get")), null);
        EntityDef entity = new EntityDef("Order", "orders", null, new McpEntityConfig(true, List.of("delete")),
            false, false, false, Map.of("name", stringField()),
            Map.of("Admin", List.of("read", "create", "update", "delete")), null, null, Map.of(), Map.of());

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity), List.of(), framework, List.of(), List.of(), List.of()),
            Map.of(entity, "order.yaml", framework, "framework.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Order")
            .hasMessageContaining("delete")
            .hasMessageContaining("ceiling");
    }

    @Test
    void entityMcpDisabledWithNonEmptyTools_throws() {
        // enabled: false together with a non-empty tools list is contradictory; the plan requires
        // this to be a hard error rather than silently picking one interpretation over the other.
        EntityDef entity = new EntityDef("Order", "orders", null, new McpEntityConfig(false, List.of("get")),
            false, false, false, Map.of("name", stringField()),
            Map.of("Viewer", List.of("read")), null, null, Map.of(), Map.of());

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity)), Map.of(entity, "order.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Order")
            .hasMessageContaining("contradictory");
    }

    // ---------- role and policy validation ----------

    @Test
    void entityPermissionReferencesUndeclaredRole_throws() {
        EntityDef entity = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of(), Map.of("Admin", List.of("read")), null, null, Map.of(), Map.of());

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity)), Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unknown role");
    }

    @Test
    void duplicateRoleName_throws() {
        RoleDefinitionDef roleA = new RoleDefinitionDef(Map.of("Admin", new RoleDef("Admin users")));
        RoleDefinitionDef roleB = new RoleDefinitionDef(Map.of("Admin", new RoleDef("Also admin")));

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(), List.of(), null, List.of(roleA, roleB), List.of(), List.of()),
            Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Duplicate role");
    }

    @Test
    void unusedPolicy_throws() {
        AbacPolicyDef policy = new AbacPolicyDef("ViewerRead", "#user.roles.contains('Viewer')");

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(), List.of(), null, List.of(), List.of(policy), List.of()),
            Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unattached policy");
    }

    @Test
    void testRejectsReservedRoles() {
        DomainModelValidator validator = new DomainModelValidator();
        ResolvedDomainModel model = new ResolvedDomainModel(List.of(), List.of(), null, List.of(
            new RoleDefinitionDef(Map.of("SuperAdmin", new RoleDef("desc")))
        ), List.of(), List.of());
        
        assertThrows(ManifestValidationException.class, () -> validator.validate(model, Map.of()));
    }

    @Test
    void testRejectsReservedRolesInPermissions() {
        EntityDef entity = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of(), Map.of("SuperAdmin", List.of("read")), null, null, Map.of(), Map.of());

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity)), Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Reserved role name cannot be used in permissions");
    }

    @Test
    void testRejectsOldUserAttributesSyntax() {
        AbacPolicyDef policy = new AbacPolicyDef("MyPolicy", "#user.attributes['org'] == 'Acme'");
        EntityDef entity = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of(), Map.of(), Map.of("MyPolicy", List.of("read")), null, Map.of(), Map.of());

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity), List.of(), null, List.of(), List.of(policy), List.of()), Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Deprecated #user.attributes syntax used");
    }

    @Test
    void testRejectsUndeclaredSecurityAttributes_bracketNotation() {
        AbacPolicyDef policy = new AbacPolicyDef("MyPolicy", "#user.securityAttributes['unknown'] == 'Acme'");
        EntityDef entity = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of(), Map.of(), Map.of("MyPolicy", List.of("read")), null, Map.of(), Map.of());

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity), List.of(), null, List.of(), List.of(policy), List.of()), Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Undeclared security attribute 'unknown'");
    }

    @Test
    void testRejectsUndeclaredSecurityAttributes_dotNotation() {
        AbacPolicyDef policy = new AbacPolicyDef("MyPolicy", "#user.securityAttributes.unknown == 'Acme'");
        EntityDef entity = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of(), Map.of(), Map.of("MyPolicy", List.of("read")), null, Map.of(), Map.of());

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(entity), List.of(), null, List.of(), List.of(policy), List.of()), Map.of()))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Undeclared security attribute 'unknown'");
    }
}
