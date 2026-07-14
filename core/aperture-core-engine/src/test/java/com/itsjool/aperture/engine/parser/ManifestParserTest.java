package com.itsjool.aperture.engine.parser;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.PrincipalAttributeDefinitionDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import com.itsjool.aperture.engine.model.SecurityAttributeDef;
import com.itsjool.aperture.engine.validator.ManifestValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ManifestParserTest {
    @TempDir
    File tempDir;

    @Test
    void parsesCompleteManifestModel() throws Exception {
        File refFile = new File(tempDir, "common.yaml");
        Files.writeString(refFile.toPath(), "type: String");

        File entityFile = new File(tempDir, "entity.yaml");
        Files.writeString(entityFile.toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              plural: Customers
              tenantScoped: false
              fields:
                email:
                  ref: common.yaml
            """);
            
        File migrationFile = new File(tempDir, "migration.yaml");
        Files.writeString(migrationFile.toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Migration
            metadata:
              name: initial_data
            spec:
              sql: INSERT INTO stuff VALUES (1);
              rollback: DELETE FROM stuff WHERE id = 1;
              position:
                after: create-stuff
            """);

        Files.writeString(new File(tempDir, "framework.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: FrameworkConfig
            metadata:
              name: framework
            spec:
              defaultRoles: [OrgAdmin, Accountant, Viewer]
            """);
        Files.writeString(new File(tempDir, "roles.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata:
              name: roles
            spec:
              roles:
                OrgAdmin: { description: Full access }
                Accountant: { description: Financial access }
                Viewer: { description: Read only }
            """);
        Files.writeString(new File(tempDir, "versions.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: ApiVersionConfig
            metadata:
              name: versions
            spec:
              versions:
                "1": { status: SUNSET }
                "2": { status: ACTIVE }
            """);

        ManifestParser parser = new ManifestParser();
        ResolvedDomainModel model = parser.parseDirectory(tempDir);
        
        assertThat(model.entities()).hasSize(1);
        assertThat(model.entities().get(0).name()).isEqualTo("Customer");
        assertThat(model.entities().get(0).plural()).isEqualTo("Customers");
        assertThat(model.entities().get(0).tenantScoped()).isFalse();
        assertThat(model.entities().get(0).fields().get("email").type()).isEqualTo("String");
        
        assertThat(model.migrations()).hasSize(1);
        assertThat(model.migrations().get(0).name()).isEqualTo("initial_data");
        assertThat(model.migrations().get(0).rollbackSql()).contains("DELETE FROM stuff");
        assertThat(model.migrations().get(0).positionAfter()).isEqualTo("create-stuff");
        assertThat(model.frameworkConfig().defaultRoles())
            .containsExactly("OrgAdmin", "Accountant", "Viewer");
        assertThat(model.roleDefinitions()).singleElement().satisfies(roles ->
            assertThat(roles.roles()).containsOnlyKeys("OrgAdmin", "Accountant", "Viewer"));
        assertThat(model.apiVersionConfigs()).singleElement().satisfies(versions ->
            assertThat(versions.versions()).containsOnlyKeys("1", "2"));
    }

    @Test
    void rejectsMigrationWithoutRollback() throws Exception {
        Files.writeString(new File(tempDir, "migration.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Migration
            metadata:
              name: irreversible
            spec:
              sql: DELETE FROM customers;
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .hasMessageContaining("migration.yaml")
            .rootCause()
            .hasMessageContaining("required property 'rollback' not found");
    }

    @Test
    void semanticHookVocabularyParsesTypeAndOperations() throws Exception {
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Invoice
            spec:
              fields:
                amount:
                  type: decimal
              hooks:
                ValidateInvoice:
                  type: validate
                  on: [create, update]
                  url: http://hook
            """);

        ResolvedDomainModel model = new ManifestParser().parseDirectory(tempDir);

        assertThat(model.entities()).singleElement().satisfies(entity -> {
            assertThat(entity.hooks()).containsKey("ValidateInvoice");
            assertThat(entity.hooks().get("ValidateInvoice").type()).isEqualTo("validate");
            assertThat(entity.hooks().get("ValidateInvoice").on()).containsExactly("create", "update");
        });
    }

    @Test
    void semanticHookWithLegacyPhaseFailsSchemaValidation() throws Exception {
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Invoice
            spec:
              fields:
                amount:
                  type: decimal
              hooks:
                ValidateInvoice:
                  type: validate
                  phase: PRECOMMIT
                  url: http://hook
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .hasMessageContaining("entity.yaml")
            .rootCause()
            .hasMessageContaining("Schema validation failed");
    }

    @Test
    void hookRetriesFlowsThroughFromYaml() throws Exception {
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Invoice
            spec:
              fields:
                amount:
                  type: decimal
              hooks:
                ValidateInvoice:
                  type: validate
                  on: [create, update]
                  url: http://hook
                  retries: 2
            """);

        ResolvedDomainModel model = new ManifestParser().parseDirectory(tempDir);

        assertThat(model.entities()).singleElement().satisfies(entity ->
            assertThat(entity.hooks().get("ValidateInvoice").retries()).isEqualTo(2));
    }

    @Test
    void hookRetriesAboveSchemaCeilingFailsSchemaValidation() throws Exception {
        // The schema's flat maximum (5) is the loosest per-type cap (trigger's); this only proves
        // the structural ceiling. The tighter guard/validate cap (2) is enforced semantically by
        // HookSemanticsResolver, exercised separately in HookSemanticsResolverTest/DomainModelValidatorTest.
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Invoice
            spec:
              fields:
                amount:
                  type: decimal
              hooks:
                ValidateInvoice:
                  type: validate
                  on: [create, update]
                  url: http://hook
                  retries: 6
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .hasMessageContaining("entity.yaml")
            .rootCause()
            .hasMessageContaining("Schema validation failed");
    }

    @Test
    void hookRetriesOnMutateFailsSemanticValidation() throws Exception {
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              fields:
                name:
                  type: String
              hooks:
                EnrichCustomer:
                  type: mutate
                  on: [create]
                  onFailure: passthrough
                  url: http://hook
                  retries: 1
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .hasMessageContaining("entity.yaml")
            .hasMessageContaining("retries is not supported for mutate hooks");
    }

    @Test
    void rejectsDuplicateRoleNames() throws Exception {
        Files.writeString(new File(tempDir, "roles.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata:
              name: roles
            spec:
              roles:
                Admin: { description: Admin }
            """);
        Files.writeString(new File(tempDir, "roles2.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata:
              name: roles2
            spec:
              roles:
                Admin: { description: Admin2 }
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Duplicate role name in")
            .hasMessageContaining("roles2.yaml");
    }

    @Test
    void rejectsUnknownDefaultRole() throws Exception {
        Files.writeString(new File(tempDir, "framework.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: FrameworkConfig
            metadata:
              name: framework
            spec:
              defaultRoles: [UnknownRole]
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unknown default role referenced in")
            .hasMessageContaining("framework.yaml");
    }

    @Test
    void rejectsUnknownRoleReferencedByOperation() throws Exception {
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              fields:
                name: { type: String }
              permissions:
                FakeRole: [read]
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unknown role referenced by an operation in")
            .hasMessageContaining("entity.yaml");
    }

    @Test
    void rejectsMalformedPermissionOperation() throws Exception {
        Files.writeString(new File(tempDir, "roles.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata:
              name: roles
            spec:
              roles:
                Admin: { description: Admin }
            """);
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              fields:
                name: { type: String }
              permissions:
                Admin: [readd]
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Malformed permission operation in")
            .hasMessageContaining("entity.yaml");
    }

    @Test
    void rejectsPermissionBlockThatResolvesToNoRole() throws Exception {
        Files.writeString(new File(tempDir, "roles.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata:
              name: roles
            spec:
              roles:
                Admin: { description: Admin }
            """);
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              fields:
                name: { type: String }
              permissions:
                Admin: []
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("A permission block that resolves to no role in")
            .hasMessageContaining("entity.yaml");
    }

    @Test
    void rejectsUnknownAbacPolicyReferencedByEntity() throws Exception {
        Files.writeString(new File(tempDir, "abac.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: AbacPolicy
            metadata:
              name: ValidPolicy
            spec:
              expression: "user.department == entity.department"
            """);
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              fields:
                name: { type: String }
              abacRules:
                InvalidPolicy: "read"
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Inline abacRules are deprecated")
            .hasMessageContaining("entity.yaml");
    }

    @Test
    void acceptsValidPolicyAndAttachment() throws Exception {
        Files.writeString(new File(tempDir, "abac.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: AbacPolicy
            metadata:
              name: ValidPolicy
            spec:
              expression: "#record.department == 'Finance'"
            """);
        Files.writeString(new File(tempDir, "abac2.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: AbacPolicy
            metadata:
              name: ValidPolicy2
            spec:
              expression: "#input.department == 'Finance'"
            """);
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              fields:
                name: { type: String }
              policies:
                ValidPolicy: [read]
                ValidPolicy2: [create]
              publicOperations: [delete]
            """);

        ManifestParser parser = new ManifestParser();
        ResolvedDomainModel model = parser.parseDirectory(tempDir);
        assertThat(model.entities()).hasSize(1);
    }

    @Test
    void rejectsDuplicatePolicyNames() throws Exception {
        Files.writeString(new File(tempDir, "abac.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: AbacPolicy
            metadata:
              name: DupPolicy
            spec:
              expression: "true"
            """);
        Files.writeString(new File(tempDir, "abac2.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: AbacPolicy
            metadata:
              name: DupPolicy
            spec:
              expression: "false"
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Duplicate policy name");
    }

    @Test
    void rejectsUnattachedPolicy() throws Exception {
        Files.writeString(new File(tempDir, "abac.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: AbacPolicy
            metadata:
              name: UnusedPolicy
            spec:
              expression: "true"
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unattached policy in");
    }

    @Test
    void rejectsIncompatibleRulePhasesCreate() throws Exception {
        Files.writeString(new File(tempDir, "abac.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: AbacPolicy
            metadata:
              name: BadPolicy
            spec:
              expression: "#record.department == 'Finance'"
            """);
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              fields:
                name: { type: String }
              policies:
                BadPolicy: [create]
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Incompatible rule phases")
            .hasMessageContaining("'record' is unavailable in create phase");
    }

    @Test
    void rejectsUnknownAbacPolicyReferencedByEntityPolicies() throws Exception {
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              fields:
                name: { type: String }
              policies:
                UnknownPolicy: [read]
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unknown policy referenced")
            .hasMessageContaining("entity.yaml");
    }

    @Test
    void frameworkConfigTenancyModeNoneParsed() throws Exception {
        Files.writeString(new File(tempDir, "framework.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: FrameworkConfig
            metadata:
              name: framework
            spec:
              defaultRoles: [OrgAdmin]
              tenancyMode: none
            """);
        Files.writeString(new File(tempDir, "roles.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata:
              name: roles
            spec:
              roles:
                OrgAdmin: { description: Full access }
            """);

        ResolvedDomainModel model = new ManifestParser().parseDirectory(tempDir);
        assertThat(model.frameworkConfig().tenancyMode()).isEqualTo(TenancyMode.NONE);
    }

    @Test
    void frameworkConfigTenancyModeDefaultsToPool() throws Exception {
        Files.writeString(new File(tempDir, "framework.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: FrameworkConfig
            metadata:
              name: framework
            spec:
              defaultRoles: [OrgAdmin]
            """);
        Files.writeString(new File(tempDir, "roles.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata:
              name: roles
            spec:
              roles:
                OrgAdmin: { description: Full access }
            """);

        ResolvedDomainModel model = new ManifestParser().parseDirectory(tempDir);
        assertThat(model.frameworkConfig().tenancyMode()).isEqualTo(TenancyMode.POOL);
    }

    @Test
    void rejectsIncompatibleRulePhasesRead() throws Exception {
        Files.writeString(new File(tempDir, "abac.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: AbacPolicy
            metadata:
              name: BadPolicy
            spec:
              expression: "#input.department == 'Finance'"
            """);
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              fields:
                name: { type: String }
              policies:
                BadPolicy: [read]
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Incompatible rule phases")
            .hasMessageContaining("'input' is unavailable in read phase");
    }

    @Test
    void rejectsUnknownAbacExpressionVariable() throws Exception {
        Files.writeString(new File(tempDir, "abac.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: AbacPolicy
            metadata:
              name: BadPolicy
            spec:
              expression: "#request.headers['X-Department'] == 'finance'"
            """);
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Invoice
            spec:
              fields:
                amount: { type: decimal }
              policies:
                BadPolicy: [read]
            """);

        assertThatThrownBy(() -> new ManifestParser().parseDirectory(tempDir))
            .isInstanceOf(ManifestValidationException.class)
            .hasMessageContaining("Unknown ABAC variable")
            .hasMessageContaining("request")
            .hasMessageContaining("entity.yaml");
    }

    @Test
    void entityWithDescription_parsesDescription() throws Exception {
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              description: "A business customer within the tenant"
              plural: Customers
              tenantScoped: false
              fields:
                name: { type: String }
            """);

        ManifestParser parser = new ManifestParser();
        ResolvedDomainModel model = parser.parseDirectory(tempDir);

        assertThat(model.entities())
            .singleElement()
            .satisfies(entity ->
                assertThat(entity.description()).isEqualTo("A business customer within the tenant")
            );
    }

    @Test
    void entityWithoutDescription_descripton_isNull() throws Exception {
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              plural: Customers
              tenantScoped: false
              fields:
                name: { type: String }
            """);

        ManifestParser parser = new ManifestParser();
        ResolvedDomainModel model = parser.parseDirectory(tempDir);

        assertThat(model.entities())
            .singleElement()
            .satisfies(entity ->
                assertThat(entity.description()).isNull()
            );
    }

    @Test
    void fieldWithDescription_parsesDescription() throws Exception {
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              plural: Customers
              tenantScoped: false
              fields:
                name:
                  type: String
                  description: "Legal name of the customer"
            """);

        ManifestParser parser = new ManifestParser();
        ResolvedDomainModel model = parser.parseDirectory(tempDir);

        assertThat(model.entities())
            .singleElement()
            .satisfies(entity ->
                assertThat(entity.fields().get("name").description())
                    .isEqualTo("Legal name of the customer")
            );
    }

    @Test
    void frameworkConfigWithMcp_parsesMcpConfig() throws Exception {
        Files.writeString(new File(tempDir, "roles.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata:
              name: roles
            spec:
              roles:
                OrgAdmin: { description: Full access }
                Viewer: { description: Read only }
            """);
        Files.writeString(new File(tempDir, "framework.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: FrameworkConfig
            metadata:
              name: framework
            spec:
              defaultRoles: [OrgAdmin, Viewer]
              mcp:
                enabled: true
                transport: stateless
                tools: [list, get, create]
            """);

        ManifestParser parser = new ManifestParser();
        ResolvedDomainModel model = parser.parseDirectory(tempDir);

        assertThat(model.frameworkConfig().mcp())
            .isNotNull()
            .satisfies(mcp -> {
                assertThat(mcp.enabled()).isTrue();
                assertThat(mcp.transport()).isEqualTo("stateless");
                assertThat(mcp.tools()).containsExactly("list", "get", "create");
            });
    }

    @Test
    void frameworkConfigWithoutMcp_mcpIsNull() throws Exception {
        Files.writeString(new File(tempDir, "roles.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata:
              name: roles
            spec:
              roles:
                OrgAdmin: { description: Full access }
            """);
        Files.writeString(new File(tempDir, "framework.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: FrameworkConfig
            metadata:
              name: framework
            spec:
              defaultRoles: [OrgAdmin]
            """);

        ManifestParser parser = new ManifestParser();
        ResolvedDomainModel model = parser.parseDirectory(tempDir);

        assertThat(model.frameworkConfig().mcp()).isNull();
    }

    @Test
    void entityMcpToolsWithoutEnabledKey_parsesEnabledAsNullAndEntityIsExposed() throws Exception {
        // The bug this plan fixes: an entity mcp block with only `tools` set (no `enabled` key)
        // must parse enabled() as null — meaning "inherit" — not false. Before McpEntityConfig
        // became tri-state, this silently produced enabled=false and dropped the entity from MCP.
        Files.writeString(new File(tempDir, "roles.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata:
              name: roles
            spec:
              roles:
                Admin: { description: Full access }
            """);
        Files.writeString(new File(tempDir, "entity.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: Entity
            metadata:
              name: Customer
            spec:
              mcp:
                tools: [list, get]
              fields:
                name: { type: String }
              permissions:
                Admin: [read]
            """);

        ManifestParser parser = new ManifestParser();
        ResolvedDomainModel model = parser.parseDirectory(tempDir);

        assertThat(model.entities()).singleElement().satisfies(entity -> {
            assertThat(entity.mcpConfig()).isNotNull();
            assertThat(entity.mcpConfig().enabled()).isNull();
            assertThat(entity.mcpConfig().tools()).containsExactly("list", "get");
        });
    }

    @Test
    void frameworkConfigWithCli_parsesBinaryName() throws Exception {
        Files.writeString(new File(tempDir, "roles.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata:
              name: roles
            spec:
              roles:
                Admin: { description: Admin }
            """);
        Files.writeString(new File(tempDir, "framework.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: FrameworkConfig
            metadata:
              name: framework
            spec:
              defaultRoles: [Admin]
              cli:
                binaryName: myapp
            """);

        ManifestParser parser = new ManifestParser();
        ResolvedDomainModel model = parser.parseDirectory(tempDir);

        assertThat(model.frameworkConfig().cli().binaryName()).isEqualTo("myapp");
    }

    @Test
    void frameworkConfigWithoutCli_defaultsBinaryNameToAperture() throws Exception {
        Files.writeString(new File(tempDir, "roles.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: RoleDefinition
            metadata:
              name: roles
            spec:
              roles:
                Admin: { description: Admin }
            """);
        Files.writeString(new File(tempDir, "framework.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: FrameworkConfig
            metadata:
              name: framework
            spec:
              defaultRoles: [Admin]
            """);

        ManifestParser parser = new ManifestParser();
        ResolvedDomainModel model = parser.parseDirectory(tempDir);

        assertThat(model.frameworkConfig().cli().binaryName()).isEqualTo("aperture");
    }

    @Test
    void testParsePrincipalAttributeDefinition() throws Exception {
        ManifestParser parser = new ManifestParser();
        File dir = new File(tempDir, "valid-attributes");
        dir.mkdirs();
        java.nio.file.Files.writeString(new File(dir, "attributes.yaml").toPath(), """
            apiVersion: aperture.itsjool.com/v1
            kind: PrincipalAttributeDefinition
            metadata:
              name: principal-security-attributes
            spec:
              securityAttributes:
                department:
                  type: string
                  allowedValues: [finance, sales, ops]
                  personalKeyDelegation: exact
                  serviceAccountAssignable: true
            """);

        var model = parser.parseDirectory(dir);
        assertEquals(1, model.principalAttributeDefinitions().size());
        PrincipalAttributeDefinitionDef def = model.principalAttributeDefinitions().get(0);
        assertEquals("principal-security-attributes", def.metadata().name());
        SecurityAttributeDef dept = def.spec().securityAttributes().get("department");
        assertEquals("string", dept.type());
        assertEquals(List.of("finance", "sales", "ops"), dept.allowedValues());
        assertEquals("exact", dept.personalKeyDelegation());
        assertEquals(true, dept.serviceAccountAssignable());
    }
}
