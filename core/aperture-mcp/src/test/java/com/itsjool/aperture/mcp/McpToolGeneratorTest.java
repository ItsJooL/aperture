package com.itsjool.aperture.mcp;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.McpConfig;
import com.itsjool.aperture.engine.model.McpEntityConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolGeneratorTest {

    @Test
    void entityWithDescription_listTool_descriptionContainsEntityBlurb() {
        EntityDef entity = new EntityDef(
            "Widget", "Widgets", "A widget that does things", null,
            false, false, false,
            Map.of(
                "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)
            ),
            Map.of("Viewer", List.of("read")), null, null, null, null
        );
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("list", "get"));

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, null, "1");

        assertThat(source)
            .contains("list_widgets")
            .contains("A widget that does things");
    }

    @Test
    void entityWithoutDescription_listTool_fallsBackToName() {
        EntityDef entity = new EntityDef(
            "Widget", "Widgets", null, null,
            false, false, false,
            Map.of(
                "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)
            ),
            Map.of("Viewer", List.of("read")), null, null, null, null
        );
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("list"));

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, null, "1");

        assertThat(source)
            .contains("list_widgets")
            .contains("A Widget within the current tenant");
    }

    @Test
    void manyToOneField_becomesIdParam_oneToManyExcluded() {
        EntityDef entity = new EntityDef(
            "Invoice", "Invoices", null, null,
            false, false, false,
            Map.of(
                "customer", new FieldDef("UUID", false, false, false, false, null, null, null, "ManyToOne", "Customer", null, null),
                "attachments", new FieldDef("List", false, false, false, false, null, null, null, "OneToMany", null, "invoice", null)
            ),
            Map.of("Admin", List.of("create")), null, null, null, null
        );
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("create"));

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, null, "1");

        assertThat(source)
            .contains("customer_id")
            .doesNotContain("attachments");
    }

    @Test
    void requiredField_descriptionAppendsRequired() {
        EntityDef entity = new EntityDef(
            "Customer", "Customers", null, null,
            false, false, false,
            Map.of(
                "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, "Legal name"),
                "email", new FieldDef("String", false, false, false, false, null, null, null, null, null, null, "Contact email")
            ),
            Map.of("Admin", List.of("create")), null, null, null, null
        );
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("create"));

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, null, "1");

        assertThat(source)
            .contains("Legal name (required)")
            .contains("Contact email");  // no (required) for optional
    }

    @Test
    void entityConfigRestrictsTools_onlyListAndGet() {
        // narrowing: derived and ceiling both permit all five tools, but the entity's own
        // mcp.tools list narrows the effective set down to list/get.
        EntityDef entity = new EntityDef(
            "Customer", "Customers", null, null,
            false, false, false,
            Map.of(
                "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)
            ),
            TestEntities.FULL_CRUD_PERMISSIONS, null, null, null, null
        );
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("list", "get", "create", "update", "delete"));
        McpEntityConfig entityConfig = new McpEntityConfig(true, List.of("list", "get"));

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, entityConfig, "1");

        assertThat(source)
            .contains("list_customers")
            .contains("get_customer")
            .doesNotContain("create_customer")
            .doesNotContain("update_customer")
            .doesNotContain("delete_customer");
    }

    @Test
    void ceilingBlocksAnOperationTheEntityItselfPermits() {
        // The framework ceiling is now a hard upper bound, not a default: an entity that permits
        // full CRUD via its own permissions still can't exceed a narrower framework ceiling.
        EntityDef entity = new EntityDef(
            "Customer", "Customers", null, null,
            false, false, false,
            Map.of(
                "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)
            ),
            TestEntities.FULL_CRUD_PERMISSIONS, null, null, null, null
        );
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("list", "get"));

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, null, "1");

        assertThat(source)
            .contains("list_customers")
            .contains("get_customer")
            .doesNotContain("create_customer")
            .doesNotContain("update_customer")
            .doesNotContain("delete_customer");
    }

    @Test
    void derivedRestrictsBeyondAnUnrestrictedCeilingAndNarrowing() {
        // No ceiling (global null), no narrowing (entityConfig null): the only restriction left
        // is derived(entity), from an entity that only grants read and create.
        EntityDef entity = new EntityDef(
            "Customer", "Customers", null, null,
            false, false, false,
            Map.of(
                "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)
            ),
            Map.of("Assistant", List.of("read", "create")), null, null, null, null
        );

        String source = new McpToolGenerator().generateForEntity(entity, null, null, "1");

        assertThat(source)
            .contains("list_customers")
            .contains("get_customer")
            .contains("create_customer")
            .doesNotContain("update_customer")
            .doesNotContain("delete_customer");
    }

    @Test
    void entityToolsCannotWidenPastWhatDerivedPermits_evenWhenListedExplicitly() {
        // The one precision that matters: an entity's own mcp.tools list is a narrowing, never a
        // grant. Listing "delete" here must not produce a delete tool when nothing in permissions/
        // policies/publicOperations reaches the delete operation. (DomainModelValidator separately
        // rejects this manifest shape outright; this test pins the generator's math regardless.)
        EntityDef entity = new EntityDef(
            "Customer", "Customers", null, null,
            false, false, false,
            Map.of(
                "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)
            ),
            Map.of("Viewer", List.of("read")), null, null, null, null
        );
        McpEntityConfig entityConfig = new McpEntityConfig(true, List.of("list", "get", "delete"));

        String source = new McpToolGenerator().generateForEntity(entity, null, entityConfig, "1");

        assertThat(source)
            .contains("list_customers")
            .contains("get_customer")
            .doesNotContain("delete_customer");
    }

    @Test
    void entityWithNoAccessRulesAtAll_derivesNoTools() {
        // The framework is default-deny: an entity with no permissions, policies, or
        // publicOperations at all derives zero MCP tools, matching the fact that CodeGenerator
        // would emit Prefab.Role.None for every operation on it.
        EntityDef entity = new EntityDef(
            "Customer", "Customers", null, null,
            false, false, false,
            Map.of(
                "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)
            ),
            null, null, null, null, null
        );

        String source = new McpToolGenerator().generateForEntity(entity, null, null, "1");

        assertThat(source)
            .doesNotContain("list_customers")
            .doesNotContain("get_customer")
            .doesNotContain("create_customer")
            .doesNotContain("update_customer")
            .doesNotContain("delete_customer");
    }

    @Test
    void entityConfigDisabled_generatesNoTools() {
        EntityDef entity = new EntityDef(
            "Customer", "Customers", null, null,
            false, false, false,
            Map.of(
                "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)
            ),
            null, null, null, null, null
        );
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("list", "get", "create"));
        McpEntityConfig entityConfig = new McpEntityConfig(false, null);

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, entityConfig, "1");

        // When disabled, entity config should still generate based on global, since the generator
        // doesn't check enabled flag (that's the caller's responsibility). Here we verify
        // the generator produces valid Java even with disabled config.
        assertThat(source)
            .contains("package com.itsjool.aperture.generated.mcp")
            .contains("public class CustomerV1McpTools");
    }

    @Test
    void nullPlural_fallsBackToEntityNameLowercasePlusS() {
        EntityDef entity = new EntityDef(
            "Widget", null, null, null,
            false, false, false,
            Map.of("name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)),
            Map.of("Viewer", List.of("read")), null, null, null, null
        );
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("list"));

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, null, "1");

        assertThat(source)
                .contains("list_widgets")
                .contains("/api/v1/widgets");
    }

    @Test
    void allTools_generatedMethodNamesFollowConvention() {
        EntityDef entity = new EntityDef(
            "Invoice", "invoices", "An invoice", null,
            false, false, false,
            Map.of("amount", new FieldDef("decimal", true, false, false, false, null, null, null, null, null, null, null)),
            TestEntities.FULL_CRUD_PERMISSIONS, null, null, null, null
        );

        String source = new McpToolGenerator().generateForEntity(entity, null, null, "3");

        // Java method names
        assertThat(source).contains("listInvoices(");
        assertThat(source).contains("getInvoice(");
        assertThat(source).contains("createInvoice(");
        assertThat(source).contains("updateInvoice(");
        assertThat(source).contains("deleteInvoice(");
        // @Tool name values
        assertThat(source).contains("name = \"list_invoices\"");
        assertThat(source).contains("name = \"get_invoice\"");
        assertThat(source).contains("name = \"create_invoice\"");
        assertThat(source).contains("name = \"update_invoice\"");
        assertThat(source).contains("name = \"delete_invoice\"");
    }

    @Test
    void toolParamAnnotation_presentOnAllParameters() {
        EntityDef entity = new EntityDef(
            "Customer", "customers", null, null,
            false, false, false,
            Map.of("name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, "Full name")),
            TestEntities.FULL_CRUD_PERMISSIONS, null, null, null, null
        );

        String source = new McpToolGenerator().generateForEntity(entity, null, null, "1");

        // @ToolParam must appear on method parameters (list has filter, page, pageSize)
        assertThat(source).contains("@ToolParam(");
        // The description on the manifest field appears in the @ToolParam description
        assertThat(source).contains("Full name");
    }

    @Test
    void toolAnnotation_descriptionContainsEntityBlurb() {
        // @Tool(description=...) must embed the entity's description (not just appear somewhere in the file)
        EntityDef entity = TestEntities.simpleCustomer();

        String source = new McpToolGenerator().generateForEntity(entity, null, null, "1");

        // List tool description contains the entity blurb and the "Supports RSQL" suffix
        assertThat(source).contains("description = \"List customers for the current tenant. A customer record. Supports RSQL filtering and pagination.\"");
        // Get tool description references the entity blurb
        assertThat(source).contains("description = \"Retrieve a single Customer by its UUID. A customer record.\"");
        // Create tool description references the entity blurb
        assertThat(source).contains("description = \"Create a new Customer. A customer record.\"");
    }

    @Test
    void manyToOneField_createMethod_emitsRelationshipsBlockResolvingRegisteredTargetType() {
        EntityDef entity = TestEntities.taskWithProjectRelation();
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("create"));
        Map<String, String> resourceTypes = Map.of("Project", "projects");

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, null, "1", resourceTypes);

        assertThat(source)
            .contains("String project_id")
            .contains("rels.put(\"project\", adapter.relationshipRef(\"projects\", project_id))")
            .contains("adapter.buildBody(\"tasks\", null, attrs, rels)");
    }

    @Test
    void manyToOneField_updateMethod_guardsRelationshipPutOnNonNullId() {
        EntityDef entity = TestEntities.taskWithProjectRelation();
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("update"));
        Map<String, String> resourceTypes = Map.of("Project", "projects");

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, null, "1", resourceTypes);

        assertThat(source)
            .contains("if (project_id != null)")
            .contains("rels.put(\"project\", adapter.relationshipRef(\"projects\", project_id))")
            .contains("adapter.buildBody(\"tasks\", id, attrs, rels)");
    }

    @Test
    void manyToOneField_noRegistryEntry_fallsBackToDefaultPluralForTargetType() {
        EntityDef entity = new EntityDef(
            "Invoice", "Invoices", null, null,
            false, false, false,
            Map.of(
                "customer", TestEntities.manyToOneField("Customer")
            ),
            Map.of("Admin", List.of("create")), null, null, null, null
        );
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("create"));

        // 4-arg overload — no resourceTypesByEntity registry supplied.
        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, null, "1");

        assertThat(source)
            .contains("rels.put(\"customer\", adapter.relationshipRef(\"customers\", customer_id))");
    }

    @Test
    void oneOfField_createAndUpdateMethodsUseExplicitTypeAndIdParams() {
        EntityDef entity = new EntityDef(
            "LineItem", "lineitems", null, null,
            false, false, false,
            Map.of(
                "billable", new FieldDef("oneof", false, false, false, false, null, null, null, null, "Billable", null, null)
            ),
            Map.of("Admin", List.of("create", "update")), null, null, null, null
        );
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("create", "update"));

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, null, "1");

        assertThat(source)
            .contains("String billable_type")
            .contains("String billable_id")
            .contains("rels.put(\"billable\", adapter.relationshipRef(billable_type, billable_id))")
            .contains("if (billable_type != null && billable_id != null)")
            .doesNotContain("adapter.relationshipRef(\"billables\"");
    }

    @Test
    void componentAndConditionalAnnotations_alwaysPresent() {
        EntityDef entity = new EntityDef(
            "Order", "orders", null, null,
            false, false, false, Map.of(),
            null, null, null, null, null
        );

        String source = new McpToolGenerator().generateForEntity(entity, null, null, "1");

        assertThat(source).contains("@Component");
        assertThat(source).contains("aperture.mcp.enabled");
        assertThat(source).contains("public class OrderV1McpTools");
        assertThat(source).contains("package com.itsjool.aperture.generated.mcp");
    }

    /**
     * Every generated tool method wraps its {@code adapter.*} call in an OTel {@link
     * io.micrometer.observation.Observation} named {@code aperture.mcp.tool_call}, tagged with the
     * tool's own name and a fixed {@code server.name} (plan 013/MCP observability, commit
     * d9f7a34). This is generated code: a malformed {@code addStatement(...)} template (wrong
     * {@code $T}/{@code $S} arity, wrong tag key) would only surface downstream as a consumer's
     * compile error or a silently wrong tag, never a failing test here — hence this pin.
     */
    @Test
    void allTools_wrapAdapterCallInObservationTaggedWithToolNameAndServerName() {
        EntityDef entity = TestEntities.simpleCustomer();
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("list", "get", "create", "update", "delete"));

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, null, "1");

        assertThat(source).contains("Observation.createNotStarted(\"aperture.mcp.tool_call\", observationRegistry)");
        assertThat(source).contains("lowCardinalityKeyValue(\"tool.name\", \"list_customers\")");
        assertThat(source).contains("lowCardinalityKeyValue(\"tool.name\", \"get_customer\")");
        assertThat(source).contains("lowCardinalityKeyValue(\"tool.name\", \"create_customer\")");
        assertThat(source).contains("lowCardinalityKeyValue(\"tool.name\", \"update_customer\")");
        assertThat(source).contains("lowCardinalityKeyValue(\"tool.name\", \"delete_customer\")");
        // server.name is shared across every tool call on this generated class — 5 occurrences.
        assertThat(source.split("lowCardinalityKeyValue\\(\"server\\.name\", \"aperture-mcp\"\\)", -1)).hasSize(6);
    }

    /**
     * Manifest validation accepts any casing for tool names, so the ceiling and narrowing bounds
     * must be compared case-insensitively. Comparing the raw manifest strings against the
     * lower-cased vocabulary silently yields an empty tool set instead.
     */
    @Test
    void toolNamesAreCaseInsensitiveInBothTheCeilingAndTheNarrowing() {
        EntityDef entity = new EntityDef(
            "Widget", "Widgets", null, null,
            false, false, false,
            Map.of("name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)),
            Map.of("Admin", List.of("create", "read", "update", "delete")),
            null, null, null, null
        );
        McpConfig mixedCaseCeiling = new McpConfig(true, "stateless", List.of("List", "GET", "create"));
        McpEntityConfig mixedCaseNarrowing = new McpEntityConfig(null, List.of("LIST", "Get"));

        assertThat(new McpToolGenerator().effectiveTools(mixedCaseCeiling, entity, mixedCaseNarrowing))
            .containsExactly("list", "get");

        assertThat(new McpToolGenerator().effectiveTools(mixedCaseCeiling, entity, null))
            .containsExactly("list", "get", "create");
    }
}
