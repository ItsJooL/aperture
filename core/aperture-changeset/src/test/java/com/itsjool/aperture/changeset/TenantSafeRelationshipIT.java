package com.itsjool.aperture.changeset;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.CompositeResourceAccessor;
import liquibase.resource.FileSystemResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class TenantSafeRelationshipIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer("postgres:17-alpine");

    private static Connection connection;

    @BeforeAll
    static void applyGeneratedSchema() throws Exception {
        Path tempChangelog = generateChangelogFile();

        try (Connection migrationConnection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Liquibase liquibase = new Liquibase(
                tempChangelog.getFileName().toString(),
                new CompositeResourceAccessor(
                    new FileSystemResourceAccessor(tempChangelog.getParent().toFile()),
                    new ClassLoaderResourceAccessor()),
                new JdbcConnection(migrationConnection))) {
                liquibase.update();
            }
        }
        connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterAll
    static void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void allowsSameTenantRelationshipInserts() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID customerA = UUID.randomUUID();
        UUID invoiceA = UUID.randomUUID();

        execute(String.format(
            "INSERT INTO aperture_customers (id, aperture_tenant_id, name) VALUES ('%s', '%s', 'Customer A')",
            customerA, tenantA));
        execute(String.format(
            "INSERT INTO aperture_invoices (id, aperture_tenant_id, amount, customer_id) VALUES ('%s', '%s', 100.00, '%s')",
            invoiceA, tenantA, customerA));

        assertThat(count("aperture_invoices", invoiceA)).isEqualTo(1);
    }

    @Test
    void rejectsCrossTenantRelationshipInserts() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID customerA = UUID.randomUUID();

        execute(String.format(
            "INSERT INTO aperture_customers (id, aperture_tenant_id, name) VALUES ('%s', '%s', 'Customer A')",
            customerA, tenantA));

        assertThatThrownBy(() -> execute(String.format(
            "INSERT INTO aperture_invoices (id, aperture_tenant_id, amount, customer_id) VALUES ('%s', '%s', 100.00, '%s')",
            UUID.randomUUID(), tenantB, customerA)))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("foreign key");
    }

    @Test
    void rejectsCrossTenantRelationshipUpdates() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID customerA = UUID.randomUUID();
        UUID customerB = UUID.randomUUID();
        UUID invoiceB = UUID.randomUUID();

        execute(String.format(
            "INSERT INTO aperture_customers (id, aperture_tenant_id, name) VALUES ('%s', '%s', 'Customer A')",
            customerA, tenantA));
        execute(String.format(
            "INSERT INTO aperture_customers (id, aperture_tenant_id, name) VALUES ('%s', '%s', 'Customer B')",
            customerB, tenantB));
        execute(String.format(
            "INSERT INTO aperture_invoices (id, aperture_tenant_id, amount, customer_id) VALUES ('%s', '%s', 100.00, '%s')",
            invoiceB, tenantB, customerB));

        assertThatThrownBy(() -> execute(String.format(
            "UPDATE aperture_invoices SET customer_id = '%s' WHERE id = '%s'",
            customerA, invoiceB)))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("foreign key");
    }

    @Test
    void compositeForeignKeyExists() throws SQLException {
        assertThat(compositeForeignKey(
            "aperture_invoices", List.of("aperture_tenant_id", "customer_id"),
            "aperture_customers", List.of("aperture_tenant_id", "id"))).isTrue();
    }

    private static Path generateChangelogFile() throws IOException {
        EntityDef customer = new EntityDef("Customer", "customers", null, null, false, false, true, Map.of(
            "name", new FieldDef("String", true, false, false, false, "1", null, null, null, null, null, null)
        ), null, null, null, Map.of(), Map.of());
        EntityDef invoice = new EntityDef("Invoice", "invoices", null, null, false, false, true, Map.of(
            "amount", new FieldDef("decimal", true, false, false, false, "1", null, null, null, null, null, null),
            "customer", new FieldDef("ref", true, false, false, false, "1", null, null, "ManyToOne", "Customer", null, null)
        ), null, null, null, Map.of(), Map.of());

        String xml = new ChangesetGenerator().generateSchemaSnapshot(
            new ResolvedDomainModel(List.of(customer, invoice)), TenancyMode.POOL);

        Path tempDir = Files.createTempDirectory("aperture-changeset-it");
        Path changelog = tempDir.resolve("aperture-schema.xml");
        Files.writeString(changelog, xml);
        return changelog;
    }

    private static void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int count(String table, UUID id) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 String.format("SELECT COUNT(*) FROM %s WHERE id = '%s'", table, id))) {
            result.next();
            return result.getInt(1);
        }
    }

    private static boolean compositeForeignKey(
        String table, List<String> columns, String referencedTable, List<String> referencedColumns)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT array_agg(source.attname ORDER BY keys.position),
                   array_agg(target.attname ORDER BY keys.position)
              FROM pg_constraint constraint_record
              JOIN pg_class source_table ON source_table.oid = constraint_record.conrelid
              JOIN pg_class target_table ON target_table.oid = constraint_record.confrelid
              JOIN unnest(constraint_record.conkey, constraint_record.confkey)
                   WITH ORDINALITY AS keys(source_number, target_number, position) ON true
              JOIN pg_attribute source
                ON source.attrelid = source_table.oid AND source.attnum = keys.source_number
              JOIN pg_attribute target
                ON target.attrelid = target_table.oid AND target.attnum = keys.target_number
             WHERE constraint_record.contype = 'f'
               AND source_table.relname = ? AND target_table.relname = ?
             GROUP BY constraint_record.oid
            """)) {
            statement.setString(1, table);
            statement.setString(2, referencedTable);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (columns.equals(List.of((String[]) result.getArray(1).getArray()))
                        && referencedColumns.equals(List.of((String[]) result.getArray(2).getArray()))) {
                        return true;
                    }
                }
                return false;
            }
        }
    }
}
