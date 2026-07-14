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

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<EntityDef> entities = List.of();
        private List<MigrationDef> migrations = List.of();
        private FrameworkConfigDef frameworkConfig;
        private List<RoleDefinitionDef> roleDefinitions = List.of();
        private List<AbacPolicyDef> abacPolicies = List.of();
        private List<ApiVersionConfigDef> apiVersionConfigs = List.of();
        private List<PrincipalAttributeDefinitionDef> principalAttributeDefinitions = List.of();
        private List<OneOfDef> oneOfs = List.of();

        private Builder() {
        }

        public Builder entities(List<EntityDef> entities) {
            this.entities = entities;
            return this;
        }

        public Builder migrations(List<MigrationDef> migrations) {
            this.migrations = migrations;
            return this;
        }

        public Builder frameworkConfig(FrameworkConfigDef frameworkConfig) {
            this.frameworkConfig = frameworkConfig;
            return this;
        }

        public Builder roleDefinitions(List<RoleDefinitionDef> roleDefinitions) {
            this.roleDefinitions = roleDefinitions;
            return this;
        }

        public Builder abacPolicies(List<AbacPolicyDef> abacPolicies) {
            this.abacPolicies = abacPolicies;
            return this;
        }

        public Builder apiVersionConfigs(List<ApiVersionConfigDef> apiVersionConfigs) {
            this.apiVersionConfigs = apiVersionConfigs;
            return this;
        }

        public Builder principalAttributeDefinitions(
            List<PrincipalAttributeDefinitionDef> principalAttributeDefinitions
        ) {
            this.principalAttributeDefinitions = principalAttributeDefinitions;
            return this;
        }

        public Builder oneOfs(List<OneOfDef> oneOfs) {
            this.oneOfs = oneOfs;
            return this;
        }

        public ResolvedDomainModel build() {
            return new ResolvedDomainModel(entities, migrations, frameworkConfig, roleDefinitions,
                abacPolicies, apiVersionConfigs, principalAttributeDefinitions, oneOfs);
        }
    }
}
