package com.itsjool.aperture.mcp;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.AbacPolicyDef;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.mcp.McpToolAccessClassifier.ToolAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolAccessClassifierTest {

    private static final FieldDef NAME_FIELD =
        new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null);

    private static EntityDef entity(Map<String, List<String>> permissions,
                                     Map<String, List<String>> policies,
                                     List<String> publicOperations) {
        return entity(permissions, policies, publicOperations, false, null);
    }

    private static EntityDef entity(Map<String, List<String>> permissions,
                                     Map<String, List<String>> policies,
                                     List<String> publicOperations,
                                     boolean tenantScoped,
                                     String scopedBy) {
        return new EntityDef("Project", "projects", null, null,
            false, false, tenantScoped, Map.of("name", NAME_FIELD),
            permissions, policies, publicOperations, Map.of(), Map.of(), scopedBy);
    }

    @Test
    void rolesGrantingReadAreCapturedOnListAndGetTools() {
        EntityDef entity = entity(
            Map.of("Admin", List.of("read", "create"), "ReadOnly", List.of("read")),
            Map.of(), List.of());

        Map<String, ToolAccess> access = McpToolAccessClassifier.classify(
            entity, List.of("list", "get", "create"), List.of(), TenancyMode.NONE);

        assertThat(access.get("list_projects").roles()).containsExactlyInAnyOrder("Admin", "ReadOnly");
        assertThat(access.get("get_project").roles()).containsExactlyInAnyOrder("Admin", "ReadOnly");
        assertThat(access.get("create_project").roles()).containsExactly("Admin");
        assertThat(access.get("list_projects").operation()).isEqualTo("read");
        assertThat(access.get("create_project").operation()).isEqualTo("create");
    }

    @Test
    void publicOperationIsCapturedAndHasNoRoleRequirement() {
        EntityDef entity = entity(Map.of(), Map.of(), List.of("read"));

        Map<String, ToolAccess> access = McpToolAccessClassifier.classify(
            entity, List.of("list", "get"), List.of(), TenancyMode.NONE);

        assertThat(access.get("list_projects").publicOperation()).isTrue();
        assertThat(access.get("get_project").publicOperation()).isTrue();
        assertThat(access.get("list_projects").roles()).isEmpty();
    }

    @Test
    void userOnlyPolicyExpression_isIncludedAsPrincipalOnly() {
        EntityDef entity = entity(Map.of(), Map.of("FinanceOnly", List.of("read")), List.of());
        List<AbacPolicyDef> abacPolicies = List.of(
            new AbacPolicyDef("FinanceOnly", "#user.securityAttributes['department'] == 'finance'"));

        Map<String, ToolAccess> access = McpToolAccessClassifier.classify(
            entity, List.of("list", "get"), abacPolicies, TenancyMode.NONE);

        assertThat(access.get("list_projects").principalOnlyPolicyExpressions())
            .containsExactly("#user.securityAttributes['department'] == 'finance'");
    }

    @Test
    void recordScopedPolicyExpression_isExcludedFromPrincipalOnlyList() {
        // The fail-closed regression this pins: AbacPolicyEvaluator.evaluate fails closed on any
        // exception. If a #record-referencing policy leaked into principalOnlyPolicyExpressions,
        // the runtime filter would evaluate it against a null record at tools/list time, throw (or
        // return false), and silently hide this tool from every non-superadmin caller. The
        // classifier must never let that happen: the tool must stay listed, unfiltered by this
        // policy, and Elide alone enforces it on the actual tools/call.
        EntityDef entity = entity(Map.of(), Map.of("OwnerOnly", List.of("read")), List.of());
        List<AbacPolicyDef> abacPolicies = List.of(
            new AbacPolicyDef("OwnerOnly", "#record.ownerId == #user.identityId"));

        Map<String, ToolAccess> access = McpToolAccessClassifier.classify(
            entity, List.of("list", "get"), abacPolicies, TenancyMode.NONE);

        assertThat(access.get("list_projects").principalOnlyPolicyExpressions()).isEmpty();
        assertThat(access.get("get_project").principalOnlyPolicyExpressions()).isEmpty();
        // The role set is also empty here (no permissions granted), which on its own would hide
        // the tool in the runtime filter — but that's a separate, correct concern (no role grants
        // it) from the fail-closed hazard this test targets (the policy itself must not hide it).
    }

    @Test
    void inputScopedPolicyExpression_isExcludedFromPrincipalOnlyList() {
        EntityDef entity = entity(Map.of(), Map.of("InputCheck", List.of("create")), List.of());
        List<AbacPolicyDef> abacPolicies = List.of(
            new AbacPolicyDef("InputCheck", "#input.department == 'Finance'"));

        Map<String, ToolAccess> access = McpToolAccessClassifier.classify(
            entity, List.of("create"), abacPolicies, TenancyMode.NONE);

        assertThat(access.get("create_project").principalOnlyPolicyExpressions()).isEmpty();
    }

    @Test
    void onlyEffectiveToolsGetAnEntry() {
        EntityDef entity = entity(
            Map.of("Admin", List.of("read", "create", "update", "delete")), Map.of(), List.of());

        Map<String, ToolAccess> access = McpToolAccessClassifier.classify(
            entity, List.of("list", "get"), List.of(), TenancyMode.NONE);

        assertThat(access).containsOnlyKeys("list_projects", "get_project");
    }

    @Test
    void tenantScopedEntityInPoolMode_setsTenantAdminBypass() {
        // Mirrors CodeGenerator's exact condition for adding TenantAdminCheck as a bypass: this is
        // the regression an aperture-demo Customer-shaped entity (tenant-scoped, role-based
        // permissions that never mention TenantAdmin) hit — see the class javadoc.
        EntityDef entity = entity(
            Map.of("Accountant", List.of("read")), Map.of(), List.of(), true, null);

        Map<String, ToolAccess> access = McpToolAccessClassifier.classify(
            entity, List.of("list", "get"), List.of(), TenancyMode.POOL);

        assertThat(access.get("list_projects").tenantAdminBypass()).isTrue();
        assertThat(access.get("list_projects").roles()).containsExactly("Accountant");
    }

    @Test
    void tenantScopedEntityOutsidePoolMode_doesNotSetTenantAdminBypass() {
        // CodeGenerator only adds a tenant filter (and therefore the TenantAdminCheck bypass) in
        // POOL mode; SILO mode isolates tenants physically and never emits TenantAdminCheck here.
        EntityDef entity = entity(
            Map.of("Accountant", List.of("read")), Map.of(), List.of(), true, null);

        Map<String, ToolAccess> silo = McpToolAccessClassifier.classify(
            entity, List.of("list"), List.of(), TenancyMode.SILO);
        Map<String, ToolAccess> none = McpToolAccessClassifier.classify(
            entity, List.of("list"), List.of(), TenancyMode.NONE);

        assertThat(silo.get("list_projects").tenantAdminBypass()).isFalse();
        assertThat(none.get("list_projects").tenantAdminBypass()).isFalse();
    }

    @Test
    void nonTenantScopedEntity_doesNotSetTenantAdminBypass() {
        EntityDef entity = entity(Map.of("Accountant", List.of("read")), Map.of(), List.of(), false, null);

        Map<String, ToolAccess> access = McpToolAccessClassifier.classify(
            entity, List.of("list"), List.of(), TenancyMode.POOL);

        assertThat(access.get("list_projects").tenantAdminBypass()).isFalse();
    }

    @Test
    void scopedByEntity_setsTenantAdminBypassRegardlessOfTenancyMode() {
        // entity.scopedBy() != null adds a ScopeFilter independent of tenancyMode, and
        // CodeGenerator's TenantAdminCheck bypass condition applies whenever any filter is
        // present — scopedBy alone is enough, even with tenancyMode == NONE.
        EntityDef entity = entity(Map.of("Accountant", List.of("read")), Map.of(), List.of(), false, "region");

        Map<String, ToolAccess> access = McpToolAccessClassifier.classify(
            entity, List.of("list"), List.of(), TenancyMode.NONE);

        assertThat(access.get("list_projects").tenantAdminBypass()).isTrue();
    }
}
