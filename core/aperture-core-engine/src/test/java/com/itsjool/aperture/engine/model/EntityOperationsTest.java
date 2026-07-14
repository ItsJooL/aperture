package com.itsjool.aperture.engine.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EntityOperationsTest {

    private static EntityDef entity(Map<String, List<String>> permissions,
                                     Map<String, List<String>> policies,
                                     List<String> publicOperations) {
        return new EntityDef("Widget", "widgets", null, null,
            false, false, false, Map.of(), permissions, policies, publicOperations, Map.of(), Map.of());
    }

    @Test
    void roleGrantOnly_makesThatOperationReachable() {
        EntityDef entity = entity(Map.of("Admin", List.of("read", "update")), null, null);

        assertThat(EntityOperations.reachableOperations(entity)).containsExactlyInAnyOrder("read", "update");
    }

    @Test
    void policyGrantOnly_makesThatOperationReachable() {
        EntityDef entity = entity(null, Map.of("FinanceOnly", List.of("create")), null);

        assertThat(EntityOperations.reachableOperations(entity)).containsExactly("create");
    }

    @Test
    void publicOperationOnly_makesThatOperationReachable() {
        EntityDef entity = entity(null, null, List.of("read"));

        assertThat(EntityOperations.reachableOperations(entity)).containsExactly("read");
    }

    @Test
    void noPermissionsPoliciesOrPublicOperations_reachableSetIsEmpty() {
        EntityDef entity = entity(null, null, null);

        assertThat(EntityOperations.reachableOperations(entity)).isEmpty();
    }

    @Test
    void emptyMapsAndLists_reachableSetIsEmpty() {
        EntityDef entity = entity(Map.of(), Map.of(), List.of());

        assertThat(EntityOperations.reachableOperations(entity)).isEmpty();
    }

    @Test
    void superAdminIsNeverConsulted_onlyDeclaredAccessRulesCount() {
        // EntityOperations only ever looks at permissions/policies/publicOperations. There is no
        // superadmin concept represented in EntityDef at all, so an entity with none of those
        // three declared derives nothing — exactly like CodeGenerator's Prefab.Role.None case,
        // which SuperAdminCheck (added separately, at expression-build time) also does not affect.
        EntityDef entity = entity(null, null, null);

        assertThat(EntityOperations.reachableOperations(entity)).isEmpty();
        assertThat(EntityOperations.derivedMcpTools(entity)).isEmpty();
    }

    @Test
    void derivedMcpTools_readMapsToListAndGet() {
        EntityDef entity = entity(Map.of("Viewer", List.of("read")), null, null);

        assertThat(EntityOperations.derivedMcpTools(entity)).containsExactlyInAnyOrder("list", "get");
    }

    @Test
    void derivedMcpTools_fullCrudPermissions_allFiveTools() {
        EntityDef entity = entity(Map.of("Admin", List.of("read", "create", "update", "delete")), null, null);

        assertThat(EntityOperations.derivedMcpTools(entity))
            .containsExactlyInAnyOrder("list", "get", "create", "update", "delete");
    }

    @Test
    void mixedRolesAndPolicies_unionOfBothGrantsCounts() {
        EntityDef entity = entity(
            Map.of("Admin", List.of("delete")),
            Map.of("FinanceOnly", List.of("create")),
            List.of("read"));

        assertThat(EntityOperations.reachableOperations(entity))
            .containsExactlyInAnyOrder("read", "create", "delete");
    }
}
