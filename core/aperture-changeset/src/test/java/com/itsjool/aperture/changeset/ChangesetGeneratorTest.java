package com.itsjool.aperture.changeset;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.diff.ChangeType;
import com.itsjool.aperture.engine.diff.DiffResult;
import com.itsjool.aperture.engine.diff.DiffEngine;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.MigrationDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangesetGeneratorTest {
    @Test
    void generatesDeterministicCompleteSchemaSnapshot() {
        EntityDef customerA = tenantEntity("Customer", "customers", fields(
            "name", field("String", true),
            "email", field("String", false)
        ));
        EntityDef invoiceA = tenantEntity("Invoice", "invoices", fields(
            "total", field("decimal", false),
            "customer", reference("Customer")
        ));
        EntityDef customerB = tenantEntity("Customer", "customers", fields(
            "email", field("String", false),
            "name", field("String", true)
        ));
        EntityDef invoiceB = tenantEntity("Invoice", "invoices", fields(
            "customer", reference("Customer"),
            "total", field("decimal", false)
        ));
        ChangesetGenerator generator = new ChangesetGenerator();

        String first = generator.generateSchemaSnapshot(
            new ResolvedDomainModel(List.of(invoiceA, customerA)), TenancyMode.POOL);
        String second = generator.generateSchemaSnapshot(
            new ResolvedDomainModel(List.of(customerB, invoiceB)), TenancyMode.POOL);

        assertThat(first).isEqualTo(second);
        assertThat(first).contains("<include file=\"db/changelog/aperture-framework-tables.xml\"/>");
        assertThat(first).contains("<createTable tableName=\"aperture_customers\">");
        assertThat(first).contains("<createTable tableName=\"aperture_invoices\">");
        assertThat(first.indexOf("<createTable tableName=\"aperture_customers\">"))
            .isLessThan(first.indexOf("<createTable tableName=\"aperture_invoices\">"));
        assertThat(first).contains("<addForeignKeyConstraint constraintName=\"fk_aperture_invoices_customer_id\"");
        assertThat(first).contains("baseTableName=\"aperture_invoices\" baseColumnNames=\"aperture_tenant_id, customer_id\"");
        assertThat(first).contains("referencedTableName=\"aperture_customers\" referencedColumnNames=\"aperture_tenant_id, id\"/>");
        assertThat(first.indexOf("<addForeignKeyConstraint"))
            .isGreaterThan(first.lastIndexOf("<createTable"));
        assertThat(first).contains("<addUniqueConstraint tableName=\"aperture_customers\" columnNames=\"aperture_tenant_id, id\"");
        assertThat(first).contains("<addUniqueConstraint tableName=\"aperture_invoices\" columnNames=\"aperture_tenant_id, id\"");
        assertThat(first).contains("<rollback>\n            <dropTable tableName=\"aperture_customers\"/>");
        assertThat(first).contains("<rollback>\n            <dropForeignKeyConstraint baseTableName=\"aperture_invoices\"");
    }

    @Test
    void tenantScopedManyToOneEmitsCompositeForeignKey() {
        EntityDef customer = tenantEntity("Customer", "customers", fields(
            "name", field("String", true)
        ));
        EntityDef invoice = tenantEntity("Invoice", "invoices", fields(
            "customer", reference("Customer")
        ));
        ChangesetGenerator generator = new ChangesetGenerator();

        String xml = generator.generateSchemaSnapshot(
            new ResolvedDomainModel(List.of(customer, invoice)), TenancyMode.POOL);

        assertThat(xml).contains("baseTableName=\"aperture_invoices\" baseColumnNames=\"aperture_tenant_id, customer_id\"");
        assertThat(xml).contains("referencedTableName=\"aperture_customers\" referencedColumnNames=\"aperture_tenant_id, id\"/>");
    }

    @Test
    void tenantScopedTargetHasUniqueTenantIdConstraint() {
        EntityDef customer = tenantEntity("Customer", "customers", fields(
            "name", field("String", true)
        ));
        EntityDef invoice = tenantEntity("Invoice", "invoices", fields(
            "customer", reference("Customer")
        ));
        ChangesetGenerator generator = new ChangesetGenerator();

        String xml = generator.generateSchemaSnapshot(
            new ResolvedDomainModel(List.of(customer, invoice)), TenancyMode.POOL);

        assertThat(xml).contains("<addUniqueConstraint tableName=\"aperture_customers\" columnNames=\"aperture_tenant_id, id\"");
        int fkIndex = xml.indexOf("<addForeignKeyConstraint constraintName=\"fk_aperture_invoices_customer_id\"");
        int uniqueIndex = xml.indexOf("<addUniqueConstraint tableName=\"aperture_customers\"");
        assertThat(uniqueIndex).isLessThan(fkIndex);
    }

    @Test
    void globalToTenantRelationshipIsExplicitlyDefinedOrRejected() {
        EntityDef customer = tenantEntity("Customer", "customers", fields(
            "name", field("String", true)
        ));
        EntityDef invoice = entity("Invoice", "invoices", fields(
            "customer", reference("Customer")
        ));
        ChangesetGenerator generator = new ChangesetGenerator();

        assertThatThrownBy(() -> generator.generateSchemaSnapshot(
            new ResolvedDomainModel(List.of(customer, invoice)), TenancyMode.POOL))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invoice")
            .hasMessageContaining("customer")
            .hasMessageContaining("Global entity")
            .hasMessageContaining("tenant");
    }

    @Test
    void tenantToGlobalRelationshipOmitsTenantColumn() {
        EntityDef settings = entity("GlobalSettings", "global_settings", fields(
            "key", field("String", true)
        ));
        EntityDef invoice = tenantEntity("Invoice", "invoices", fields(
            "settings", reference("GlobalSettings")
        ));
        ChangesetGenerator generator = new ChangesetGenerator();

        String xml = generator.generateSchemaSnapshot(
            new ResolvedDomainModel(List.of(settings, invoice)), TenancyMode.POOL);

        assertThat(xml).contains("baseTableName=\"aperture_invoices\" baseColumnNames=\"settings_id\"");
        assertThat(xml).contains("referencedTableName=\"aperture_global_settings\" referencedColumnNames=\"id\"/>");
        assertThat(xml).doesNotContain("referencedColumnNames=\"aperture_tenant_id, id\"");
    }

    @Test
    void generatesCreateTableWithCompositeConstraints() {
        EntityDef entity = new EntityDef("Customer", "customers", null, null, true, true, true, Map.of(
            "email", new FieldDef("String", true, true, false, false, "1", null, null, null, null, null, null),
            "name", new FieldDef("String", false, true, false, false, "1", null, null, null, null, null, null)
        ), null, null, null, Map.of(), Map.of());

        // New entities are handled by the snapshot; verify via generateSchemaSnapshot
        ChangesetGenerator generator = new ChangesetGenerator();
        String xml = generator.generateSchemaSnapshot(
            new ResolvedDomainModel(List.of(entity)), TenancyMode.POOL);

        assertThat(xml).contains("<createTable tableName=\"aperture_customers\">");
        assertThat(xml).contains("<column name=\"id\" type=\"UUID\">");
        assertThat(xml).contains("<column name=\"aperture_tenant_id\" type=\"UUID\">");
        assertThat(xml).contains("<sql>CREATE UNIQUE INDEX idx_aperture_customers_email_uniq ON aperture_customers(aperture_tenant_id, email) WHERE deleted_at IS NULL;</sql>");
    }

    @Test
    void generatedChangesetsEmitCompositeFkForAddedOwningReference() {
        EntityDef customer = tenantEntity("Customer", "customers", fields(
            "name", field("String", true)
        ));
        EntityDef invoice = tenantEntity("Invoice", "invoices", fields(
            "customer", reference("Customer")
        ));

        // New entities are handled by the snapshot; foreign keys for new entities appear there
        ChangesetGenerator generator = new ChangesetGenerator();
        String xml = generator.generateSchemaSnapshot(
            new ResolvedDomainModel(List.of(customer, invoice)), TenancyMode.POOL);

        assertThat(xml).contains("baseTableName=\"aperture_invoices\" baseColumnNames=\"aperture_tenant_id, customer_id\"");
        assertThat(xml).contains("referencedTableName=\"aperture_customers\" referencedColumnNames=\"aperture_tenant_id, id\"/>");
    }

    @Test
    void generatesAddColumnChangeset() {
        FieldDef newField = new FieldDef("String", false, false, false, false, "2", null, null, null, null, null, null);
        DiffResult diff = new DiffResult(List.of(), List.of(), List.of(), Map.of("Customer", Map.of("phone", newField)), Map.of(), Map.of(), ChangeType.SAFE);

        ChangesetGenerator generator = new ChangesetGenerator();
        String xml = generator.generateGeneratedChangesets(diff, TenancyMode.POOL);

        assertThat(xml).contains("<addColumn tableName=\"aperture_customers\">");
        assertThat(xml).contains("<column name=\"phone\" type=\"VARCHAR(255)\"/>");
        assertThat(xml).contains("<not><columnExists tableName=\"aperture_customers\" columnName=\"phone\"/></not>");
    }

    @Test
    void generatesRenameColumnChangeset() {
        FieldDef newField = new FieldDef("String", false, false, false, false, "2", null, "phone", null, null, null, null);
        DiffResult diff = new DiffResult(List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of("Customer", Map.of("mobile", newField)), ChangeType.SAFE);

        ChangesetGenerator generator = new ChangesetGenerator();
        String xml = generator.generateGeneratedChangesets(diff, TenancyMode.POOL);

        assertThat(xml).contains("<renameColumn tableName=\"aperture_customers\" oldColumnName=\"phone\" newColumnName=\"mobile\" columnDataType=\"VARCHAR(255)\"/>");
        assertThat(xml).contains("<columnExists tableName=\"aperture_customers\" columnName=\"phone\"/>");
    }

    @Test
    void generatesCreateIndexChangeset() {
        EntityDef entity = new EntityDef("Customer", "customers", null, null, false, false, false, Map.of(
            "email", new FieldDef("String", true, false, true, false, "1", null, null, null, null, null, null)
        ), null, null, null, Map.of(), Map.of());

        // New entities and their indexes are handled by the snapshot
        ChangesetGenerator generator = new ChangesetGenerator();
        String xml = generator.generateSchemaSnapshot(
            new ResolvedDomainModel(List.of(entity)), TenancyMode.NONE);
        assertThat(xml).contains("<createIndex indexName=\"idx_aperture_customers_email\" tableName=\"aperture_customers\" unique=\"false\">");
    }
    

    @Test
    void generatesSortedRootChangelog() {
        List<MigrationDef> migrations = List.of(
            new MigrationDef("backfill-b", "sql", "rollback", "backfill-a"),
            new MigrationDef("backfill-c", "sql", "rollback", "backfill-b"),
            new MigrationDef("backfill-a", "sql", "rollback", null)
        );
        ChangesetGenerator generator = new ChangesetGenerator();
        String xml = generator.generateRootChangelog(migrations);
        
        int idxA = xml.indexOf("manual/backfill-a.xml");
        int idxB = xml.indexOf("manual/backfill-b.xml");
        int idxC = xml.indexOf("manual/backfill-c.xml");
        
        assertThat(idxA).isLessThan(idxB);
        assertThat(idxB).isLessThan(idxC);
    }

    private static EntityDef entity(String name, String plural, Map<String, FieldDef> fields) {
        return new EntityDef(name, plural, null, null, false, false, false, fields, null, null, null, Map.of(), Map.of());
    }

    private static EntityDef tenantEntity(String name, String plural, Map<String, FieldDef> fields) {
        return new EntityDef(name, plural, null, null, false, false, true, fields, null, null, null, Map.of(), Map.of());
    }

    private static Map<String, FieldDef> fields(Object... entries) {
        Map<String, FieldDef> fields = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            fields.put((String) entries[i], (FieldDef) entries[i + 1]);
        }
        return fields;
    }

    private static FieldDef field(String type, boolean required) {
        return new FieldDef(type, required, false, false, false, "1", null, null, null, null, null, null);
    }

    private static FieldDef reference(String target) {
        return new FieldDef("ref", true, false, true, false, "1", null, null,
            "ManyToOne", target, null, null);
    }

    @Test
    void generatedChangesetsEmitFkForRefFieldAddedToExistingEntity() {
        EntityDef customer = tenantEntity("Customer", "customers", fields(
            "name", field("String", true)
        ));
        EntityDef invoice = tenantEntity("Invoice", "invoices", fields(
            "total", field("decimal", false)
        ));

        Map<String, EntityDef> allEntities = new LinkedHashMap<>();
        allEntities.put("Customer", customer);
        allEntities.put("Invoice", invoice);

        FieldDef customerRef = new FieldDef("ref", true, false, true, false, "2", null, null,
            "ManyToOne", "Customer", null, null);

        DiffResult diff = new DiffResult(
            List.of(),
            List.of(),
            List.of(),
            Map.of("Invoice", Map.of("customer", customerRef)),
            Map.of(),
            Map.of(),
            ChangeType.SAFE,
            allEntities
        );

        ChangesetGenerator generator = new ChangesetGenerator();
        String xml = generator.generateGeneratedChangesets(diff, TenancyMode.POOL);

        assertThat(xml).contains("<addColumn tableName=\"aperture_invoices\">");
        assertThat(xml).contains("baseColumnNames=\"aperture_tenant_id, customer_id\"");
    }

    @Test
    void schemaSnapshotEmitsOneOfPointerColumnsWithoutForeignKey() {
        EntityDef lineItem = tenantEntity("LineItem", "lineitems", fields(
            "billable", oneOf("Billable", true, false)
        ));

        String xml = new ChangesetGenerator().generateSchemaSnapshot(
            new ResolvedDomainModel(List.of(lineItem)), TenancyMode.POOL);

        assertThat(xml).contains("<column name=\"billable_type\" type=\"VARCHAR(255)\">");
        assertThat(xml).contains("<column name=\"billable_id\" type=\"UUID\">");
        assertThat(xml).doesNotContain("<column name=\"billable_type\" type=\"VARCHAR(255)\">\n                <constraints nullable=\"false\"/>");
        assertThat(xml).contains("CREATE CONSTRAINT TRIGGER require_aperture_lineitems_billable")
            .contains("DEFERRABLE INITIALLY DEFERRED")
            .contains("billable_type IS NULL OR billable_id IS NULL")
            .contains("<dropTable tableName=\"aperture_lineitems\"/>")
            .contains("<sql>DROP FUNCTION IF EXISTS aperture_require_aperture_lineitems_billable();</sql>");
        assertThat(xml).contains("<createIndex indexName=\"idx_aperture_lineitems_billable\" tableName=\"aperture_lineitems\" unique=\"false\">");
        assertThat(xml).contains("<column name=\"billable_type\"/>");
        assertThat(xml).contains("<column name=\"billable_id\"/>");
        assertThat(xml).doesNotContain("fk_aperture_lineitems_billable_id");
        assertThat(xml).doesNotContain("<column name=\"billable\" type=");
    }

    @Test
    void generatedChangesetsEmitOneOfPointerColumnsForAddedField() {
        EntityDef lineItem = tenantEntity("LineItem", "lineitems", fields(
            "description", field("String", false)
        ));
        FieldDef billable = oneOf("Billable", false, false);

        DiffResult diff = new DiffResult(
            List.of(),
            List.of(),
            List.of(),
            Map.of("LineItem", Map.of("billable", billable)),
            Map.of(),
            Map.of(),
            ChangeType.SAFE,
            Map.of("LineItem", lineItem)
        );

        String xml = new ChangesetGenerator().generateGeneratedChangesets(diff, TenancyMode.POOL);

        assertThat(xml).contains("<column name=\"billable_type\" type=\"VARCHAR(255)\"/>");
        assertThat(xml).contains("<column name=\"billable_id\" type=\"UUID\"/>");
        assertThat(xml).contains("<not><columnExists tableName=\"aperture_lineitems\" columnName=\"billable_type\"/></not>");
        assertThat(xml).contains("<not><columnExists tableName=\"aperture_lineitems\" columnName=\"billable_id\"/></not>");
        assertThat(xml).contains("<createIndex indexName=\"idx_aperture_lineitems_billable\" tableName=\"aperture_lineitems\" unique=\"false\">");
        assertThat(xml).contains("<column name=\"aperture_tenant_id\"/>");
        assertThat(xml).contains("<column name=\"billable_type\"/>");
        assertThat(xml).contains("<column name=\"billable_id\"/>");
        assertThat(xml).doesNotContain("<column name=\"billable\" type=");
        assertThat(xml).doesNotContain("addForeignKeyConstraint");
    }

    @Test
    void generatedChangesetsRollbackRequiredOneOfConstraintObjects() {
        EntityDef lineItem = tenantEntity("LineItem", "lineitems", fields(
            "description", field("String", false)
        ));
        FieldDef billable = oneOf("Billable", true, false);
        DiffResult diff = new DiffResult(
            List.of(), List.of(), List.of(), Map.of("LineItem", Map.of("billable", billable)),
            Map.of(), Map.of(), ChangeType.SAFE, Map.of("LineItem", lineItem));

        String xml = new ChangesetGenerator().generateGeneratedChangesets(diff, TenancyMode.POOL);

        assertThat(xml).contains("<sql>DROP TRIGGER IF EXISTS require_aperture_lineitems_billable "
            + "ON aperture_lineitems;</sql>");
        assertThat(xml).contains(
            "<sql>DROP FUNCTION IF EXISTS aperture_require_aperture_lineitems_billable();</sql>");
    }

    @Test
    void deferredDropRemovesRequiredOneOfConstraintObjectsBeforePointerColumns() {
        EntityDef lineItem = tenantEntity("LineItem", "lineitems", fields(
            "description", field("String", false)
        ));
        FieldDef billable = oneOf("Billable", true, false);
        DiffResult diff = new DiffResult(
            List.of(), List.of(), List.of(), Map.of(), Map.of("LineItem", Map.of("billable", billable)),
            Map.of(), ChangeType.BREAKING, Map.of("LineItem", lineItem));

        String xml = new ChangesetGenerator().generateGeneratedChangesets(diff, TenancyMode.POOL);

        String triggerDrop = "<sql>DROP TRIGGER IF EXISTS require_aperture_lineitems_billable "
            + "ON aperture_lineitems;</sql>";
        String functionDrop =
            "<sql>DROP FUNCTION IF EXISTS aperture_require_aperture_lineitems_billable();</sql>";
        String firstColumnDrop =
            "<dropColumn tableName=\"aperture_lineitems\" columnName=\"billable_type\"/>";

        assertThat(xml).contains(triggerDrop).contains(functionDrop).contains(firstColumnDrop);
        assertThat(xml.indexOf(triggerDrop)).isLessThan(xml.indexOf(functionDrop));
        assertThat(xml.indexOf(functionDrop)).isLessThan(xml.indexOf(firstColumnDrop));
    }

    @Test
    void generatedChangesetsPreserveUniqueOneOfIndexSemantics() {
        EntityDef lineItem = tenantEntity("LineItem", "lineitems", fields(
            "description", field("String", false)
        ));
        FieldDef billable = new FieldDef("oneof", false, true, false, false,
            "1", null, null, null, "Billable", null, null);
        DiffResult diff = new DiffResult(
            List.of(), List.of(), List.of(), Map.of("LineItem", Map.of("billable", billable)),
            Map.of(), Map.of(), ChangeType.SAFE, Map.of("LineItem", lineItem));

        String xml = new ChangesetGenerator().generateGeneratedChangesets(diff, TenancyMode.POOL);

        assertThat(xml).contains(
            "<createIndex indexName=\"idx_aperture_lineitems_billable_uniq\" "
                + "tableName=\"aperture_lineitems\" unique=\"true\">");
        assertThat(xml).contains("<column name=\"aperture_tenant_id\"/>");
        assertThat(xml).contains("<column name=\"billable_type\"/>");
        assertThat(xml).contains("<column name=\"billable_id\"/>");
    }

    @Test
    void generatedChangesetsPreserveSoftDeleteUniqueOneOfIndexSemantics() {
        EntityDef lineItem = new EntityDef("LineItem", "lineitems", null, null,
            false, true, true, fields("description", field("String", false)),
            null, null, null, Map.of(), Map.of());
        FieldDef billable = new FieldDef("oneof", false, true, false, false,
            "1", null, null, null, "Billable", null, null);
        DiffResult diff = new DiffResult(
            List.of(), List.of(), List.of(), Map.of("LineItem", Map.of("billable", billable)),
            Map.of(), Map.of(), ChangeType.SAFE, Map.of("LineItem", lineItem));

        String xml = new ChangesetGenerator().generateGeneratedChangesets(diff, TenancyMode.POOL);

        assertThat(xml).contains(
            "<sql>CREATE UNIQUE INDEX idx_aperture_lineitems_billable_uniq "
                + "ON aperture_lineitems(aperture_tenant_id, billable_type, billable_id) "
                + "WHERE deleted_at IS NULL;</sql>");
        assertThat(xml).doesNotContain(
            "<createIndex indexName=\"idx_aperture_lineitems_billable_uniq\"");
    }

    private static FieldDef oneOf(String target, boolean required, boolean index) {
        return new FieldDef("oneof", required, false, index, false, "1", null, null,
            null, target, null, null);
    }
}
