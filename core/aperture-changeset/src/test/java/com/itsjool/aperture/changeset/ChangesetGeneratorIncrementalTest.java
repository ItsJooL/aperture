package com.itsjool.aperture.changeset;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.diff.ChangeType;
import com.itsjool.aperture.engine.diff.DiffResult;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChangesetGeneratorIncrementalTest {

    @Test
    void incrementalChangesetsHaveIdempotentPreconditions() {
        // Entity: Product with field unit_price (renamed from unitPrice)
        EntityDef product = new EntityDef(
            "Product", "products", null, null, false, false, false,
            Map.of("unit_price", new FieldDef("decimal", false, false, false, false, "2", null, "unitPrice", null, null, null, null)),
            null, null, null, Map.of(), Map.of()
        );

        // Entity: LineItem with field quantity (newly added)
        EntityDef lineItem = new EntityDef(
            "LineItem", "lineitems", null, null, false, false, false,
            Map.of("quantity", new FieldDef("decimal", true, false, false, false, "2", null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of()
        );

        Map<String, Map<String, FieldDef>> renamedFields = Map.of(
            "Product", Map.of(
                "unit_price", new FieldDef("decimal", false, false, false, false, "2", null, "unitPrice", null, null, null, null)
            )
        );

        Map<String, Map<String, FieldDef>> addedFields = Map.of(
            "LineItem", Map.of(
                "quantity", new FieldDef("decimal", true, false, false, false, "2", null, null, null, null, null, null)
            )
        );

        DiffResult diff = new DiffResult(
            List.of(),
            List.of(),
            List.of(),
            addedFields,
            Map.of(),
            renamedFields,
            ChangeType.SAFE,
            Map.of("Product", product, "LineItem", lineItem)
        );

        ChangesetGenerator generator = new ChangesetGenerator();
        String xml = generator.generateGeneratedChangesets(diff, TenancyMode.POOL);

        // Rename: should contain renameColumn with correct old/new names
        assertThat(xml).contains("<renameColumn")
            .as("should contain renameColumn element");
        assertThat(xml).contains("oldColumnName=\"unitPrice\"")
            .as("should use renamedFrom value as oldColumnName");
        assertThat(xml).contains("newColumnName=\"unit_price\"")
            .as("should use new field name as newColumnName");

        // Rename: precondition checks that old column still exists (idempotent on fresh DB)
        assertThat(xml).contains("<columnExists tableName=\"aperture_products\" columnName=\"unitPrice\"/>")
            .as("rename precondition should check old column exists");

        // Add: should contain addColumn for quantity on lineitems
        assertThat(xml).contains("<addColumn tableName=\"aperture_lineitems\">")
            .as("should contain addColumn for LineItem");

        // Add: precondition checks column does not exist (idempotent on fresh DB)
        assertThat(xml).contains("<not><columnExists tableName=\"aperture_lineitems\" columnName=\"quantity\"/></not>")
            .as("addColumn precondition should check column does not exist");

        // Should NOT include the snapshot's framework tables include
        assertThat(xml).doesNotContain("<include file=\"db/changelog/aperture-framework-tables.xml\"/>")
            .as("incremental changesets should not re-include framework tables");

        // Should NOT include createTable (new entities handled by snapshot)
        assertThat(xml).doesNotContain("<createTable")
            .as("incremental changesets should not emit createTable for new entities");
    }
}
