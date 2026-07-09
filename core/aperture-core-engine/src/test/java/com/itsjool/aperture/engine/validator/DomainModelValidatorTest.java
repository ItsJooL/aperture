package com.itsjool.aperture.engine.validator;

import com.itsjool.aperture.engine.model.AbacPolicyDef;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.FrameworkConfigDef;
import com.itsjool.aperture.engine.model.HookDef;
import com.itsjool.aperture.engine.model.McpConfig;
import com.itsjool.aperture.engine.model.McpEntityConfig;
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

    private static FieldDef stringField() {
        return new FieldDef("String", false, false, false, false, null, null, null, null, null, null, null);
    }

    private static FieldDef manyToOneField(String targetClass) {
        return new FieldDef("ref", false, false, false, false, null, null, null, "ManyToOne", targetClass, null, null);
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

    // ---------- MCP config validation ----------

    @Test
    void frameworkMcpInvalidTool_throws() {
        FrameworkConfigDef framework = new FrameworkConfigDef(
            List.of(), null, new McpConfig(true, "stateless", List.of("list", "publish")), null);

        assertThatThrownBy(() -> validator.validate(
            new ResolvedDomainModel(List.of(), List.of(), framework, List.of(), List.of(), List.of()), Map.of(framework, "framework.yaml")))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Invalid MCP tool")
            .hasMessageContaining("publish")
            .hasMessageContaining("framework.yaml");
    }

    @Test
    void frameworkMcpInvalidTransport_throws() {
        FrameworkConfigDef framework = new FrameworkConfigDef(
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
