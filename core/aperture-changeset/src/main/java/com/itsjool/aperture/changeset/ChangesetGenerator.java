package com.itsjool.aperture.changeset;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.diff.DiffResult;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.MigrationDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ChangesetGenerator {

    public String generateSchemaSnapshot(ResolvedDomainModel model, TenancyMode tenancyMode) {
        List<EntityDef> entities = model.entities().stream()
            .sorted(Comparator.comparing(this::tableName))
            .toList();
        Map<String, EntityDef> entitiesByName = entities.stream()
            .collect(Collectors.toMap(EntityDef::name, Function.identity()));

        StringBuilder sb = new StringBuilder();
        appendChangelogHeader(sb);
        sb.append("    <include file=\"db/changelog/aperture-framework-tables.xml\"/>\n");

        for (EntityDef entity : entities) {
            appendCreateTable(sb, entity, tenancyMode);
        }
        entities.stream()
            .flatMap(entity -> sortedFields(entity).stream()
                .filter(entry -> isOwningReference(entry.getValue()))
                .map(entry -> new Reference(entity, entry.getKey(), entry.getValue())))
            .sorted(Comparator.comparing(this::foreignKeyName))
            .forEach(reference -> appendForeignKey(sb, reference, entitiesByName, tenancyMode));

        sb.append("</databaseChangeLog>\n");
        return sb.toString();
    }

    private void appendCreateTable(StringBuilder sb, EntityDef entity, TenancyMode tenancyMode) {
        String tableName = tableName(entity);
        sb.append(String.format("    <changeSet id=\"create-%s\" author=\"aperture\">\n", tableName));
        sb.append(String.format("        <createTable tableName=\"%s\">\n", tableName));
        sb.append("            <column name=\"id\" type=\"UUID\">\n");
        sb.append("                <constraints primaryKey=\"true\" nullable=\"false\"/>\n");
        sb.append("            </column>\n");
        if (tenancyMode == TenancyMode.POOL && entity.tenantScoped()) {
            sb.append("            <column name=\"aperture_tenant_id\" type=\"UUID\">\n");
            sb.append("                <constraints nullable=\"false\"/>\n");
            sb.append("            </column>\n");
        }
        for (Map.Entry<String, FieldDef> entry : sortedFields(entity)) {
            FieldDef field = entry.getValue();
            if ("ref".equals(field.type()) && !isOwningReference(field)) {
                continue;
            }
            for (FieldColumn column : physicalColumns(entry.getKey(), field)) {
                sb.append(String.format("            <column name=\"%s\" type=\"%s\">\n",
                    column.name(), column.type()));
                if (field.required()) {
                    sb.append("                <constraints nullable=\"false\"/>\n");
                }
                sb.append("            </column>\n");
            }
        }
        if (entity.optimisticLocking()) {
            sb.append("            <column name=\"version\" type=\"INTEGER\" defaultValue=\"0\">\n");
            sb.append("                <constraints nullable=\"false\"/>\n");
            sb.append("            </column>\n");
        }
        if (entity.softDelete()) {
            sb.append("            <column name=\"deleted_at\" type=\"TIMESTAMPTZ\"/>\n");
        }
        sb.append("        </createTable>\n");
        appendIndexes(sb, entity, tenancyMode);
        if (tenancyMode == TenancyMode.POOL && entity.tenantScoped()) {
            sb.append(String.format(
                "        <addUniqueConstraint tableName=\"%s\" columnNames=\"aperture_tenant_id, id\" constraintName=\"uq_%s_tenant_id\"/>\n",
                tableName, tableName));
        }
        sb.append("        <rollback>\n");
        sb.append(String.format("            <dropTable tableName=\"%s\"/>\n", tableName));
        sb.append("        </rollback>\n");
        sb.append("    </changeSet>\n");
    }

    private void appendIndexes(StringBuilder sb, EntityDef entity, TenancyMode tenancyMode) {
        String tableName = tableName(entity);
        for (Map.Entry<String, FieldDef> entry : sortedFields(entity)) {
            String fieldName = entry.getKey();
            FieldDef field = entry.getValue();
            if ("ref".equals(field.type()) && !isOwningReference(field)) {
                continue;
            }
            if (field.unique() && entity.softDelete()) {
                String columns = tenancyMode == TenancyMode.POOL && entity.tenantScoped()
                    ? "aperture_tenant_id, " + columnNamesForIndex(fieldName, field) : columnNamesForIndex(fieldName, field);
                sb.append(String.format(
                    "        <sql>CREATE UNIQUE INDEX idx_%s_%s_uniq ON %s(%s) WHERE deleted_at IS NULL;</sql>\n",
                    tableName, fieldName.toLowerCase(Locale.ROOT), tableName, columns));
            } else if (field.unique() || field.index()) {
                String suffix = field.unique() ? "_uniq" : "";
                sb.append(String.format(
                    "        <createIndex indexName=\"idx_%s_%s%s\" tableName=\"%s\" unique=\"%s\">\n",
                    tableName, fieldName.toLowerCase(Locale.ROOT), suffix, tableName, field.unique()));
                if (tenancyMode == TenancyMode.POOL && entity.tenantScoped()) {
                    sb.append("            <column name=\"aperture_tenant_id\"/>\n");
                }
                for (FieldColumn column : physicalColumns(fieldName, field)) {
                    sb.append(String.format("            <column name=\"%s\"/>\n", column.name()));
                }
                sb.append("        </createIndex>\n");
            }
        }
    }

    private void appendForeignKey(StringBuilder sb, Reference reference, Map<String, EntityDef> entitiesByName,
                                  TenancyMode tenancyMode) {
        EntityDef target = entitiesByName.get(reference.field().targetClass());
        if (target == null) {
            throw new IllegalArgumentException("Unknown reference target " + reference.field().targetClass()
                + " for " + reference.entity().name() + "." + reference.fieldName());
        }
        boolean sourceTenantScoped = tenancyMode == TenancyMode.POOL && reference.entity().tenantScoped();
        boolean targetTenantScoped = tenancyMode == TenancyMode.POOL && target.tenantScoped();
        if (!sourceTenantScoped && targetTenantScoped) {
            throw new IllegalArgumentException("Global entity " + reference.entity().name()
                + " cannot reference tenant-scoped entity " + target.name()
                + " through relationship " + reference.entity().name() + "." + reference.fieldName()
                + " in POOL tenancy mode");
        }
        String constraintName = foreignKeyName(reference);
        String baseTable = tableName(reference.entity());
        String baseColumn;
        String referencedColumn;
        if (sourceTenantScoped && targetTenantScoped) {
            baseColumn = "aperture_tenant_id, " + reference.fieldName() + "_id";
            referencedColumn = "aperture_tenant_id, id";
        } else {
            baseColumn = reference.fieldName() + "_id";
            referencedColumn = "id";
        }
        sb.append(String.format("    <changeSet id=\"add-%s\" author=\"aperture\">\n", constraintName));
        sb.append(String.format("        <addForeignKeyConstraint constraintName=\"%s\"\n", constraintName));
        sb.append(String.format("            baseTableName=\"%s\" baseColumnNames=\"%s\"\n", baseTable, baseColumn));
        sb.append(String.format("            referencedTableName=\"%s\" referencedColumnNames=\"%s\"/>\n",
            tableName(target), referencedColumn));
        sb.append("        <rollback>\n");
        sb.append(String.format("            <dropForeignKeyConstraint baseTableName=\"%s\" constraintName=\"%s\"/>\n",
            baseTable, constraintName));
        sb.append("        </rollback>\n");
        sb.append("    </changeSet>\n");
    }

    private void appendChangelogHeader(StringBuilder sb) {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<databaseChangeLog\n");
        sb.append("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
        sb.append("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
        sb.append("        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd\">\n");
    }

    private List<Map.Entry<String, FieldDef>> sortedFields(EntityDef entity) {
        if (entity.fields() == null) {
            return List.of();
        }
        return entity.fields().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .toList();
    }

    private boolean isOwningReference(FieldDef field) {
        return "ref".equals(field.type()) && (field.mappedBy() == null || field.mappedBy().isBlank());
    }

    private List<FieldColumn> physicalColumns(String fieldName, FieldDef field) {
        if ("oneof".equalsIgnoreCase(field.type())) {
            return List.of(
                new FieldColumn(fieldName + "_type", "VARCHAR(255)"),
                new FieldColumn(fieldName + "_id", "UUID")
            );
        }
        if ("ref".equals(field.type())) {
            return List.of(new FieldColumn(fieldName + "_id", toDbType(field.type())));
        }
        return List.of(new FieldColumn(fieldName, toDbType(field.type())));
    }

    private String columnNamesForIndex(String fieldName, FieldDef field) {
        return physicalColumns(fieldName, field).stream()
            .map(FieldColumn::name)
            .collect(Collectors.joining(", "));
    }

    private String tableName(EntityDef entity) {
        return entity.plural() != null
            ? "aperture_" + entity.plural().toLowerCase(Locale.ROOT)
            : "aperture_" + entity.name().toLowerCase(Locale.ROOT) + "s";
    }

    private String foreignKeyName(Reference reference) {
        return "fk_" + tableName(reference.entity()) + "_"
            + reference.fieldName().toLowerCase(Locale.ROOT) + "_id";
    }

    private record Reference(EntityDef entity, String fieldName, FieldDef field) {}

    private record FieldColumn(String name, String type) {}

    public String generateGeneratedChangesets(DiffResult diff, TenancyMode tenancyMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<databaseChangeLog\n");
        sb.append("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
        sb.append("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
        sb.append("        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd\">\n");

        Map<String, EntityDef> knownEntities = new java.util.HashMap<>(diff.allEntities());

        diff.addedFields().forEach((entityName, fields) -> {
            boolean hasEmittableColumns = fields.entrySet().stream().anyMatch(entry -> {
                FieldDef f = entry.getValue();
                return !("ref".equals(f.type()) && f.mappedBy() != null && !f.mappedBy().isEmpty());
            });
            if (!hasEmittableColumns) return;

            EntityDef entityDef = knownEntities.get(entityName);
            String tableName = entityDef != null ? tableName(entityDef) : "aperture_" + entityName.toLowerCase(java.util.Locale.ROOT) + "s";
            String changesetId = "add-cols-" + tableName + "-" + String.join("-", new java.util.TreeSet<>(fields.keySet()));
            for (java.util.Map.Entry<String, FieldDef> entry : new java.util.TreeMap<>(fields).entrySet()) {
                String fieldName = entry.getKey();
                FieldDef field = entry.getValue();
                if ("ref".equals(field.type())) {
                    if (field.mappedBy() != null && !field.mappedBy().isEmpty()) continue;
                }
                for (FieldColumn column : physicalColumns(fieldName, field)) {
                    String colChangesetId = changesetId + "-" + entry.getKey() + "-" + column.name();
                    sb.append(String.format("    <changeSet id=\"%s\" author=\"aperture\">\n", colChangesetId));
                    sb.append("        <preConditions onFail=\"MARK_RAN\">\n");
                    sb.append(String.format("            <not><columnExists tableName=\"%s\" columnName=\"%s\"/></not>\n", tableName, column.name()));
                    sb.append("        </preConditions>\n");
                    sb.append(String.format("        <addColumn tableName=\"%s\">\n", tableName));
                    sb.append(String.format("            <column name=\"%s\" type=\"%s\"/>\n", column.name(), column.type()));
                    sb.append("        </addColumn>\n");
                    sb.append("    </changeSet>\n");
                }
            }
        });

        diff.renamedFields().forEach((entityName, fields) -> {
            EntityDef entityDef = knownEntities.get(entityName);
            String tableName = entityDef != null ? tableName(entityDef) : "aperture_" + entityName.toLowerCase(java.util.Locale.ROOT) + "s";
            String changesetId = "rename-cols-" + tableName + "-" + String.join("-", new java.util.TreeSet<>(fields.keySet()));
            sb.append(String.format("    <changeSet id=\"%s\" author=\"aperture\">\n", changesetId));
            for (java.util.Map.Entry<String, FieldDef> entry : fields.entrySet()) {
                String fieldName = entry.getKey();
                FieldDef field = entry.getValue();
                String dbType = toDbType(field.type());
                sb.append("        <preConditions onFail=\"MARK_RAN\">\n");
                sb.append(String.format("            <columnExists tableName=\"%s\" columnName=\"%s\"/>\n", tableName, field.renamedFrom()));
                sb.append("        </preConditions>\n");
                sb.append(String.format("        <renameColumn tableName=\"%s\" oldColumnName=\"%s\" newColumnName=\"%s\" columnDataType=\"%s\"/>\n",
                    tableName, field.renamedFrom(), fieldName, dbType));
            }
            sb.append("    </changeSet>\n");
        });

        diff.deferredDrops().forEach((entityName, fields) -> {
            EntityDef entityDef = knownEntities.get(entityName);
            String tableName = entityDef != null ? tableName(entityDef) : "aperture_" + entityName.toLowerCase(java.util.Locale.ROOT) + "s";
            String changesetId = "deferred-drop-" + tableName + "-" + String.join("-", new java.util.TreeSet<>(fields.keySet()));
            sb.append(String.format("    <changeSet id=\"%s\" author=\"aperture\" context=\"pending\">\n", changesetId));
            for (Map.Entry<String, FieldDef> entry : fields.entrySet()) {
                for (FieldColumn column : physicalColumns(entry.getKey(), entry.getValue())) {
                    sb.append(String.format("        <dropColumn tableName=\"%s\" columnName=\"%s\"/>\n", tableName, column.name()));
                }
            }
            sb.append("    </changeSet>\n");
        });

        diff.addedFields().entrySet().stream()
            .filter(e -> knownEntities.containsKey(e.getKey()))
            .flatMap(e -> {
                EntityDef entity = knownEntities.get(e.getKey());
                return e.getValue().entrySet().stream()
                    .filter(entry -> isOwningReference(entry.getValue()))
                    .map(entry -> new Reference(entity, entry.getKey(), entry.getValue()));
            })
            .sorted(Comparator.comparing(this::foreignKeyName))
            .forEach(reference -> appendForeignKey(sb, reference, knownEntities, tenancyMode));

        sb.append("</databaseChangeLog>\n");
        return sb.toString();
    }

    public String generateManualMigration(MigrationDef migration) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<databaseChangeLog\n" +
               "    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n" +
               "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
               "    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n" +
               "        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd\">\n" +
               String.format("    <changeSet id=\"%s\" author=\"manual\">\n", migration.name()) +
               String.format("        <sql><![CDATA[%s]]></sql>\n", migration.sql()) +
               "        <rollback>\n" +
               String.format("            <sql><![CDATA[%s]]></sql>\n", migration.rollbackSql()) +
               "        </rollback>\n    </changeSet>\n</databaseChangeLog>\n";
    }

    public String generateRootChangelog(List<MigrationDef> migrations) {
        return generateRootChangelog(migrations, false);
    }

    public String generateRootChangelog(List<MigrationDef> migrations, boolean hasIncrementalChanges) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<databaseChangeLog\n");
        sb.append("    xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n");
        sb.append("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n");
        sb.append("        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd\">\n");
        sb.append("    <include file=\"db/changelog/aperture-schema.xml\"/>\n");
        if (hasIncrementalChanges) {
            sb.append("    <include file=\"db/changelog/aperture-incremental.xml\"/>\n");
        }

        List<MigrationDef> sorted = sortMigrations(migrations);
        for (MigrationDef m : sorted) {
            sb.append(String.format("    <include file=\"db/changelog/manual/%s.xml\"/>\n", m.name()));
        }

        sb.append("</databaseChangeLog>\n");
        return sb.toString();
    }

    private String toDbType(String type) {
        if (type == null) return "VARCHAR(255)";
        return switch(type.toLowerCase()) {
            case "integer" -> "INTEGER";
            case "boolean" -> "BOOLEAN";
            case "uuid", "ref" -> "UUID";
            case "datetime", "date-time" -> "TIMESTAMPTZ";
            case "decimal" -> "DECIMAL(19,4)";
            default -> "VARCHAR(255)";
        };
    }

    private List<MigrationDef> sortMigrations(List<MigrationDef> migrations) {
        List<MigrationDef> sorted = new ArrayList<>();
        List<MigrationDef> remaining = new ArrayList<>(migrations);
        
        while (!remaining.isEmpty()) {
            boolean progress = false;
            for (int i = 0; i < remaining.size(); i++) {
                MigrationDef m = remaining.get(i);
                if (m.positionAfter() == null || m.positionAfter().isEmpty() || 
                    sorted.stream().anyMatch(s -> s.name().equals(m.positionAfter()))) {
                    sorted.add(m);
                    remaining.remove(i);
                    progress = true;
                    break;
                }
            }
            if (!progress) {
                sorted.addAll(remaining);
                break;
            }
        }
        return sorted;
    }
}
