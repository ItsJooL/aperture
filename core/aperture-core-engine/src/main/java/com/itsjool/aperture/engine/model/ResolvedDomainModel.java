package com.itsjool.aperture.engine.model;
import java.util.List;
public record ResolvedDomainModel(
    List<EntityDef> entities,
    List<MigrationDef> migrations,
    FrameworkConfigDef frameworkConfig,
    List<RoleDefinitionDef> roleDefinitions,
    List<AbacPolicyDef> abacPolicies,
    List<ApiVersionConfigDef> apiVersionConfigs,
    List<PrincipalAttributeDefinitionDef> principalAttributeDefinitions
) {
    public ResolvedDomainModel(List<EntityDef> entities) {
        this(entities, List.of());
    }

    public ResolvedDomainModel(List<EntityDef> entities, List<MigrationDef> migrations) {
        this(entities, migrations, new FrameworkConfigDef(List.of(), null, null, null), List.of(), List.of(), List.of(), List.of());
    }

    public ResolvedDomainModel(
        List<EntityDef> entities,
        List<MigrationDef> migrations,
        FrameworkConfigDef frameworkConfig,
        List<RoleDefinitionDef> roleDefinitions,
        List<AbacPolicyDef> abacPolicies,
        List<ApiVersionConfigDef> apiVersionConfigs
    ) {
        this(entities, migrations, frameworkConfig, roleDefinitions, abacPolicies, apiVersionConfigs, List.of());
    }
}
