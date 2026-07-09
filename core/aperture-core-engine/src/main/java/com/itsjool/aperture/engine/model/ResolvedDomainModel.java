package com.itsjool.aperture.engine.model;
import java.util.List;
public record ResolvedDomainModel(
    List<EntityDef> entities,
    List<MigrationDef> migrations,
    FrameworkConfigDef frameworkConfig,
    List<RoleDefinitionDef> roleDefinitions,
    List<AbacPolicyDef> abacPolicies,
    List<ApiVersionConfigDef> apiVersionConfigs,
    List<PrincipalAttributeDefinitionDef> principalAttributeDefinitions,
    List<OneOfDef> oneOfs
) {
    public ResolvedDomainModel {
        entities = entities != null ? List.copyOf(entities) : List.of();
        migrations = migrations != null ? List.copyOf(migrations) : List.of();
        roleDefinitions = roleDefinitions != null ? List.copyOf(roleDefinitions) : List.of();
        abacPolicies = abacPolicies != null ? List.copyOf(abacPolicies) : List.of();
        apiVersionConfigs = apiVersionConfigs != null ? List.copyOf(apiVersionConfigs) : List.of();
        principalAttributeDefinitions = principalAttributeDefinitions != null
            ? List.copyOf(principalAttributeDefinitions)
            : List.of();
        oneOfs = oneOfs != null ? List.copyOf(oneOfs) : List.of();
    }

    public ResolvedDomainModel(List<EntityDef> entities) {
        this(entities, List.of());
    }

    public ResolvedDomainModel(List<EntityDef> entities, List<MigrationDef> migrations) {
        this(entities, migrations, new FrameworkConfigDef(List.of(), null, null, null), List.of(), List.of(), List.of(), List.of(), List.of());
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

    public ResolvedDomainModel(
        List<EntityDef> entities,
        List<MigrationDef> migrations,
        FrameworkConfigDef frameworkConfig,
        List<RoleDefinitionDef> roleDefinitions,
        List<AbacPolicyDef> abacPolicies,
        List<ApiVersionConfigDef> apiVersionConfigs,
        List<PrincipalAttributeDefinitionDef> principalAttributeDefinitions
    ) {
        this(entities, migrations, frameworkConfig, roleDefinitions, abacPolicies, apiVersionConfigs,
            principalAttributeDefinitions, List.of());
    }
}
