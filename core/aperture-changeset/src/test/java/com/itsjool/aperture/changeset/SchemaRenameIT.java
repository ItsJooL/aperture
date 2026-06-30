package com.itsjool.aperture.changeset;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.diff.ChangeType;
import com.itsjool.aperture.engine.diff.DiffResult;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.FileSystemResourceAccessor;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@Testcontainers
class SchemaRenameIT {

    @Container
    @SuppressWarnings("resource") // Testcontainers owns the lifecycle — not a leak
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void existingDb_renameColumnMigration_renamesColumnAndIsIdempotent() throws Exception {
        String schema = "rename_test";
        createSchema(schema);
        String xml = generateRenameXml();

        try (Connection conn = connect(schema)) {
            execute(conn, """
                    CREATE TABLE aperture_products (
                        id UUID PRIMARY KEY,
                        aperture_tenant_id UUID NOT NULL,
                        "unitPrice" NUMERIC(19,4) NOT NULL DEFAULT 0
                    )
                    """);
        }

        runLiquibase(schema, xml);

        try (Connection conn = connect(schema)) {
            assertThat(columnExists(conn, schema, "aperture_products", "unit_price"))
                    .as("unit_price must exist after rename migration").isTrue();
            assertThat(columnExists(conn, schema, "aperture_products", "unitPrice"))
                    .as("camelCase unitPrice must not exist after rename migration").isFalse();
        }

        assertThatCode(() -> runLiquibase(schema, xml))
                .as("second Liquibase run on same DB must be idempotent")
                .doesNotThrowAnyException();
    }

    @Test
    void existingDb_addColumnMigration_addsColumnAndIsIdempotent() throws Exception {
        String schema = "addcol_test";
        createSchema(schema);
        String xml = generateAddColumnXml();

        try (Connection conn = connect(schema)) {
            execute(conn, """
                    CREATE TABLE aperture_lineitems (
                        id UUID PRIMARY KEY,
                        aperture_tenant_id UUID NOT NULL,
                        description VARCHAR(255)
                    )
                    """);
        }

        runLiquibase(schema, xml);

        try (Connection conn = connect(schema)) {
            assertThat(columnExists(conn, schema, "aperture_lineitems", "quantity"))
                    .as("quantity must exist after addColumn migration").isTrue();
        }

        assertThatCode(() -> runLiquibase(schema, xml))
                .as("second Liquibase run on same DB must be idempotent")
                .doesNotThrowAnyException();
    }

    @Test
    void freshDb_incrementalMigration_preconditionsSkipRenameAndAddColumn() throws Exception {
        String schema = "fresh_test";
        createSchema(schema);

        try (Connection conn = connect(schema)) {
            execute(conn, """
                    CREATE TABLE aperture_products (
                        id UUID PRIMARY KEY,
                        aperture_tenant_id UUID NOT NULL,
                        unit_price NUMERIC(19,4)
                    )
                    """);
            execute(conn, """
                    CREATE TABLE aperture_lineitems (
                        id UUID PRIMARY KEY,
                        aperture_tenant_id UUID NOT NULL,
                        quantity NUMERIC(19,4)
                    )
                    """);
        }

        String xml = generateBothXml();
        // Rename precondition: columnExists("unitPrice") is false → onFail=MARK_RAN → changeset skipped
        // AddColumn precondition: NOT columnExists("quantity") is false (quantity present) → onFail=MARK_RAN → changeset skipped
        assertThatCode(() -> runLiquibase(schema, xml))
                .as("incremental migration on fresh DB must not fail")
                .doesNotThrowAnyException();

        try (Connection conn = connect(schema)) {
            assertThat(columnExists(conn, schema, "aperture_products", "unit_price"))
                    .as("unit_price must still exist (rename was MARK_RAN-skipped)").isTrue();
            assertThat(columnExists(conn, schema, "aperture_lineitems", "quantity"))
                    .as("quantity must still exist (addColumn was MARK_RAN-skipped)").isTrue();
        }
    }

    // --- helpers ---

    private static void createSchema(String schema) throws Exception {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = admin.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        }
    }

    private static Connection connect(String schema) throws Exception {
        Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO " + schema);
        }
        return conn;
    }

    private static void runLiquibase(String schema, String xml) throws Exception {
        Path tmp = Files.createTempFile("aperture-incremental-", ".xml");
        try {
            Files.writeString(tmp, xml);
            try (Connection conn = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET search_path TO " + schema);
                }
                Database db = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(conn));
                db.setDefaultSchemaName(schema);
                try (Liquibase lb = new Liquibase(
                        tmp.getFileName().toString(),
                        new FileSystemResourceAccessor(tmp.getParent().toFile()),
                        db)) {
                    lb.update();
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void execute(Connection conn, String sql) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static boolean columnExists(Connection conn, String schema, String table, String column) throws Exception {
        try (ResultSet rs = conn.getMetaData().getColumns(null, schema, table, column)) {
            return rs.next();
        }
    }

    private static String generateRenameXml() {
        EntityDef product = new EntityDef("Product", "products", null, null, false, false, false,
                Map.of("unit_price", new FieldDef("decimal", false, false, false, false, "2", null, "unitPrice", null, null, null, null)),
                null, null, null, Map.of(), Map.of());
        DiffResult diff = new DiffResult(List.of(), List.of(), List.of(), Map.of(), Map.of(),
                Map.of("Product", Map.of("unit_price",
                        new FieldDef("decimal", false, false, false, false, "2", null, "unitPrice", null, null, null, null))),
                ChangeType.SAFE, Map.of("Product", product));
        return new ChangesetGenerator().generateGeneratedChangesets(diff, TenancyMode.POOL);
    }

    private static String generateAddColumnXml() {
        EntityDef lineItem = new EntityDef("LineItem", "lineitems", null, null, false, false, false,
                Map.of("quantity", new FieldDef("decimal", true, false, false, false, "2", null, null, null, null, null, null)),
                null, null, null, Map.of(), Map.of());
        DiffResult diff = new DiffResult(List.of(), List.of(), List.of(),
                Map.of("LineItem", Map.of("quantity",
                        new FieldDef("decimal", true, false, false, false, "2", null, null, null, null, null, null))),
                Map.of(), Map.of(), ChangeType.SAFE, Map.of("LineItem", lineItem));
        return new ChangesetGenerator().generateGeneratedChangesets(diff, TenancyMode.POOL);
    }

    private static String generateBothXml() {
        EntityDef product = new EntityDef("Product", "products", null, null, false, false, false,
                Map.of("unit_price", new FieldDef("decimal", false, false, false, false, "2", null, "unitPrice", null, null, null, null)),
                null, null, null, Map.of(), Map.of());
        EntityDef lineItem = new EntityDef("LineItem", "lineitems", null, null, false, false, false,
                Map.of("quantity", new FieldDef("decimal", true, false, false, false, "2", null, null, null, null, null, null)),
                null, null, null, Map.of(), Map.of());
        DiffResult diff = new DiffResult(List.of(), List.of(), List.of(),
                Map.of("LineItem", Map.of("quantity",
                        new FieldDef("decimal", true, false, false, false, "2", null, null, null, null, null, null))),
                Map.of(),
                Map.of("Product", Map.of("unit_price",
                        new FieldDef("decimal", false, false, false, false, "2", null, "unitPrice", null, null, null, null))),
                ChangeType.SAFE, Map.of("Product", product, "LineItem", lineItem));
        return new ChangesetGenerator().generateGeneratedChangesets(diff, TenancyMode.POOL);
    }
}
