package com.itsjool.aperture.changeset;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.CompositeResourceAccessor;
import liquibase.resource.FileSystemResourceAccessor;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that generated Liquibase changesets produce the correct physical schema in PostgreSQL.
 * Each test builds a minimal domain model, generates the snapshot, applies it, and queries the
 * information_schema to assert columns, indexes, and constraints exist as expected.
 */
@Testcontainers
class DomainSchemaIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    // ---------- optimistic locking ----------

    @Test
    void optimisticLocking_addsVersionColumn() throws Exception {
        EntityDef product = new EntityDef("Product", "products", null, null,
            true, false, false,
            Map.of("name", stringField(true)),
            null, null, null, Map.of(), Map.of());

        try (Connection conn = applySchema(List.of(product), TenancyMode.NONE)) {
            assertThat(columnNames(conn, "aperture_products")).contains("version");
            assertThat(columnType(conn, "aperture_products", "version")).isEqualTo("integer");
        }
    }

    // ---------- soft delete ----------

    @Test
    void softDelete_addsDeletedAtColumn() throws Exception {
        EntityDef item = new EntityDef("Item", "items", null, null,
            false, true, false,
            Map.of("label", stringField(false)),
            null, null, null, Map.of(), Map.of());

        try (Connection conn = applySchema(List.of(item), TenancyMode.NONE)) {
            assertThat(columnNames(conn, "aperture_items")).contains("deleted_at");
            // nullable — soft-delete rows keep the row but set deleted_at
            assertThat(columnNullable(conn, "aperture_items", "deleted_at")).isTrue();
        }
    }

    // ---------- unique constraint ----------

    @Test
    void uniqueField_createsUniqueIndex() throws Exception {
        EntityDef sku = new EntityDef("Sku", "skus", null, null,
            false, false, false,
            Map.of("code", new FieldDef("String", true, true, false, false, null, null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());

        try (Connection conn = applySchema(List.of(sku), TenancyMode.NONE)) {
            assertThat(uniqueIndexExists(conn, "aperture_skus", "code")).isTrue();
        }
    }

    // ---------- regular index ----------

    @Test
    void indexedField_createsNonUniqueIndex() throws Exception {
        EntityDef event = new EntityDef("Event", "events", null, null,
            false, false, false,
            Map.of("timestamp", new FieldDef("String", false, false, true, false, null, null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());

        try (Connection conn = applySchema(List.of(event), TenancyMode.NONE)) {
            assertThat(indexExists(conn, "aperture_events", "timestamp")).isTrue();
        }
    }

    // ---------- soft-delete + unique → partial unique index ----------

    @Test
    void softDeleteWithUniqueField_createsPartialUniqueIndex() throws Exception {
        EntityDef tag = new EntityDef("Tag", "tags", null, null,
            false, true, false,
            Map.of("slug", new FieldDef("String", true, true, false, false, null, null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());

        try (Connection conn = applySchema(List.of(tag), TenancyMode.NONE)) {
            // partial unique index: WHERE deleted_at IS NULL
            assertThat(partialUniqueIndexExists(conn, "aperture_tags", "slug")).isTrue();
        }
    }

    // ---------- tenant-scoped entity ----------

    @Test
    void tenantScopedEntity_hasApertureTenantIdColumn() throws Exception {
        EntityDef order = new EntityDef("Order", "orders", null, null,
            false, false, true,
            Map.of("total", stringField(true)),
            null, null, null, Map.of(), Map.of());

        try (Connection conn = applySchema(List.of(order), TenancyMode.POOL)) {
            assertThat(columnNames(conn, "aperture_orders")).contains("aperture_tenant_id");
        }
    }

    // ---------- helpers ----------

    private static Connection applySchema(List<EntityDef> entities, TenancyMode mode) throws Exception {
        String xml = new ChangesetGenerator().generateSchemaSnapshot(
            new ResolvedDomainModel(entities), mode);
        Path tmp = Files.createTempDirectory("aperture-schema-it");
        Path changelog = tmp.resolve("schema.xml");
        Files.writeString(changelog, xml);

        try (Connection migrationConn = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Liquibase liquibase = new Liquibase(
                changelog.getFileName().toString(),
                new CompositeResourceAccessor(
                    new FileSystemResourceAccessor(tmp.toFile()),
                    new ClassLoaderResourceAccessor()),
                new JdbcConnection(migrationConn))) {
                liquibase.update();
            }
        }
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static List<String> columnNames(Connection conn, String table) throws Exception {
        List<String> cols = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT column_name FROM information_schema.columns WHERE table_name = '" + table + "'")) {
            while (rs.next()) cols.add(rs.getString(1));
        }
        return cols;
    }

    private static String columnType(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT data_type FROM information_schema.columns WHERE table_name = '"
                 + table + "' AND column_name = '" + column + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static boolean columnNullable(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT is_nullable FROM information_schema.columns WHERE table_name = '"
                 + table + "' AND column_name = '" + column + "'")) {
            return rs.next() && "YES".equals(rs.getString(1));
        }
    }

    private static boolean uniqueIndexExists(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT i.relname FROM pg_index ix JOIN pg_class i ON i.oid = ix.indexrelid "
                 + "JOIN pg_class t ON t.oid = ix.indrelid "
                 + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey) "
                 + "WHERE t.relname = '" + table + "' AND a.attname = '" + column
                 + "' AND ix.indisunique = true AND ix.indpred IS NULL")) {
            return rs.next();
        }
    }

    private static boolean partialUniqueIndexExists(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT i.relname FROM pg_index ix JOIN pg_class i ON i.oid = ix.indexrelid "
                 + "JOIN pg_class t ON t.oid = ix.indrelid "
                 + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey) "
                 + "WHERE t.relname = '" + table + "' AND a.attname = '" + column
                 + "' AND ix.indisunique = true AND ix.indpred IS NOT NULL")) {
            return rs.next();
        }
    }

    private static boolean indexExists(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT i.relname FROM pg_index ix JOIN pg_class i ON i.oid = ix.indexrelid "
                 + "JOIN pg_class t ON t.oid = ix.indrelid "
                 + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey) "
                 + "WHERE t.relname = '" + table + "' AND a.attname = '" + column + "'")) {
            return rs.next();
        }
    }

    private static FieldDef stringField(boolean required) {
        return new FieldDef("String", required, false, false, false, null, null, null, null, null, null, null);
    }
}
