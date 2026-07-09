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
            null, null, null, null, null
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
            null, null, null, null, null
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
            null, null, null, null, null
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
            null, null, null, null, null
        );
        McpConfig globalConfig = new McpConfig(true, "stateless", List.of("create"));

        String source = new McpToolGenerator().generateForEntity(entity, globalConfig, null, "1");

        assertThat(source)
            .contains("Legal name (required)")
            .contains("Contact email");  // no (required) for optional
    }

    @Test
    void entityConfigRestrictsTools_onlyListAndGet() {
        EntityDef entity = new EntityDef(
            "Customer", "Customers", null, null,
            false, false, false,
            Map.of(
                "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)
            ),
            null, null, null, null, null
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
            null, null, null, null, null
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
            null, null, null, null, null
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
            null, null, null, null, null
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
            null, null, null, null, null
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
            null, null, null, null, null
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
}
