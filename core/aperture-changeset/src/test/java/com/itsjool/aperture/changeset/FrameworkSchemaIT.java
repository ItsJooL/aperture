package com.itsjool.aperture.changeset;

import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FrameworkSchemaIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer("postgres:17-alpine");

    private static Connection connection;

    @BeforeAll
    static void applyFrameworkChangelog() throws Exception {
        try (Connection migrationConnection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Liquibase liquibase = new Liquibase(
                "db/changelog/aperture-framework-tables.xml",
                new ClassLoaderResourceAccessor(),
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
    void definesTenantAwareIdentityLifecycleColumns() throws SQLException {
        assertColumn("aperture_tenants", "status", "character varying", false);
        assertColumn("aperture_users", "status", "character varying", false);
        assertColumn("aperture_users", "profile", "jsonb", false);
        assertColumn("aperture_users", "security_attributes", "jsonb", false);
        assertColumn("aperture_users", "super_admin", "boolean", false);
        assertThat(columnDefault("aperture_users", "profile")).contains("'{}'::jsonb");
        assertThat(columnDefault("aperture_users", "security_attributes")).contains("'{}'::jsonb");
        assertThat(columnDefault("aperture_users", "super_admin")).isEqualTo("false");

        assertColumn("aperture_roles", "tenant_id", "character varying", false);
        assertColumn("aperture_service_accounts", "status", "character varying", false);
        assertColumn("aperture_service_accounts", "client_secret_hash", "character varying", false);
        assertColumn("aperture_service_accounts", "expires_at", "timestamp with time zone", false);
        assertColumn("aperture_service_account_roles", "expires_at", "timestamp with time zone", true);
        assertThat(columnNames("aperture_service_accounts")).doesNotContain("token", "token_hash");

        assertColumn("aperture_refresh_tokens", "token_hash", "character varying", false);
        assertColumn("aperture_refresh_tokens", "expires_at", "timestamp with time zone", false);
        assertColumn("aperture_refresh_tokens", "used_at", "timestamp with time zone", true);
        assertColumn("aperture_refresh_tokens", "revoked_at", "timestamp with time zone", true);
        assertColumn("aperture_refresh_tokens", "replaced_by_token_id", "character varying", true);
        assertThat(columnNames("aperture_refresh_tokens")).doesNotContain("token", "refresh_token");
    }

    @Test
    void storesOnlyTenantAndPrincipalBoundApiKeyHashes() throws SQLException {
        assertColumn("aperture_personal_api_keys", "key_hash", "character varying", false);
        assertColumn("aperture_personal_api_keys", "tenant_id", "character varying", false);
        assertColumn("aperture_personal_api_keys", "user_id", "character varying", false);
        assertColumn("aperture_personal_api_keys", "status", "character varying", false);
        assertColumn("aperture_personal_api_keys", "created_at", "timestamp with time zone", false);
        assertColumn("aperture_personal_api_keys", "expires_at", "timestamp with time zone", true);
        assertColumn("aperture_personal_api_keys", "last_used_at", "timestamp with time zone", true);
        assertColumn("aperture_personal_api_keys", "revoked_at", "timestamp with time zone", true);
        assertColumn("aperture_personal_api_keys", "domain_roles", "jsonb", false);
        assertColumn("aperture_personal_api_keys", "security_attributes", "jsonb", false);
        assertColumn("aperture_personal_api_keys", "name", "character varying", false);
        assertThat(columnNames("aperture_personal_api_keys")).doesNotContain("api_key", "key", "token");
        assertThat(uniqueConstraintColumns("aperture_personal_api_keys")).contains(List.of("key_hash"));
        assertThat(foreignKey("aperture_personal_api_keys", "tenant_id", "aperture_tenants", "id")).isTrue();
        assertThat(compositeForeignKey(
            "aperture_personal_api_keys", List.of("tenant_id", "user_id"),
            "aperture_users", List.of("tenant_id", "id"))).isTrue();
    }

    @Test
    void definesIdentityIdentifiersAsVarchar255() throws SQLException {
        assertVarchar255("aperture_tenants", "id");
        assertVarchar255("aperture_users", "id", "username", "password_hash", "tenant_id");
        assertVarchar255("aperture_roles", "id", "tenant_id", "role_name");
        assertVarchar255("aperture_user_roles", "tenant_id", "user_id", "role_name");
        assertVarchar255(
            "aperture_refresh_tokens", "id", "user_id", "token_hash", "replaced_by_token_id");
        assertVarchar255(
            "aperture_personal_api_keys", "id", "key_hash", "tenant_id", "user_id");
        assertVarchar255(
            "aperture_service_accounts", "id", "client_id", "client_secret_hash", "tenant_id");
        assertVarchar255(
            "aperture_service_account_roles", "tenant_id", "service_account_id", "role_name");
        assertVarchar255("aperture_audit_log", "id", "user_id", "tenant_id", "entity_id");
    }

    @Test
    void permitsOnlyMarkedFrameworkAdministratorsWithoutATenant() throws SQLException {
        execute("INSERT INTO aperture_users " +
            "(id, username, tenant_id, status, super_admin) VALUES " +
            "('framework-superadmin', 'SuperAdmin', NULL, 'active', true)");

        assertThatThrownBy(() -> execute(
            "INSERT INTO aperture_users (id, username, tenant_id, status) VALUES " +
                "('tenantless-user', 'tenantless-user', NULL, 'active')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("check constraint");
    }

    @Test
    void scopesRoleNamesToTenants() throws SQLException {
        execute("INSERT INTO aperture_tenants (id, name, status) VALUES " +
            "('tenant-a', 'Tenant A', 'active'), ('tenant-b', 'Tenant B', 'active')");
        execute("INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES " +
            "('role-a', 'tenant-a', 'Viewer'), ('role-b', 'tenant-b', 'Viewer')");

        assertThatThrownBy(() -> execute(
            "INSERT INTO aperture_roles (id, tenant_id, role_name) " +
                "VALUES ('role-c', 'tenant-a', 'Viewer')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("duplicate key");
    }

    @Test
    void constrainsRoleAssignmentsToTheirTenant() throws SQLException {
        assertThat(primaryKeyColumns("aperture_user_roles"))
            .containsExactly("tenant_id", "user_id", "role_name");
        assertThat(foreignKey("aperture_user_roles", "tenant_id", "aperture_tenants", "id")).isTrue();
        assertThat(compositeForeignKey(
            "aperture_user_roles", List.of("tenant_id", "user_id"),
            "aperture_users", List.of("tenant_id", "id"))).isTrue();
        assertThat(compositeForeignKey(
            "aperture_user_roles", List.of("tenant_id", "role_name"),
            "aperture_roles", List.of("tenant_id", "role_name"))).isTrue();

        assertThat(primaryKeyColumns("aperture_service_account_roles"))
            .containsExactly("tenant_id", "service_account_id", "role_name");
        assertThat(compositeForeignKey(
            "aperture_service_account_roles", List.of("tenant_id", "service_account_id"),
            "aperture_service_accounts", List.of("tenant_id", "id"))).isTrue();
        assertThat(compositeForeignKey(
            "aperture_service_account_roles", List.of("tenant_id", "role_name"),
            "aperture_roles", List.of("tenant_id", "role_name"))).isTrue();

        execute("INSERT INTO aperture_tenants (id, name, status) VALUES " +
            "('assignment-a', 'Assignment A', 'active'), ('assignment-b', 'Assignment B', 'active')");
        execute("INSERT INTO aperture_roles (id, tenant_id, role_name) " +
            "VALUES ('assignment-role', 'assignment-b', 'Assignee')");
        execute("INSERT INTO aperture_users (id, username, tenant_id, status) " +
            "VALUES ('cross-tenant-user', 'cross-tenant-user', 'assignment-a', 'active')");
        assertThatThrownBy(() -> execute(
            "INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) " +
                "VALUES ('assignment-b', 'cross-tenant-user', 'Assignee')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("foreign key");
    }

    @Test
    void linksRefreshTokenRotationAndRevocation() throws SQLException {
        assertThat(foreignKey("aperture_refresh_tokens", "user_id", "aperture_users", "id")).isTrue();
        assertThat(foreignKey(
            "aperture_refresh_tokens", "replaced_by_token_id", "aperture_refresh_tokens", "id")).isTrue();
    }

    @Test
    void rollsBackFrameworkObjectsInReverseDependencyOrder() throws Exception {
        try (Connection admin = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement statement = admin.createStatement()) {
                statement.execute("CREATE SCHEMA rollback_test");
            }
        }

        String schemaUrl = POSTGRES.getJdbcUrl() + "&currentSchema=rollback_test";
        try (Connection rollbackConnection = DriverManager.getConnection(
            schemaUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             Liquibase liquibase = new Liquibase(
                 "db/changelog/aperture-framework-tables.xml",
                 new ClassLoaderResourceAccessor(),
                 new JdbcConnection(rollbackConnection))) {
            liquibase.update();
            liquibase.rollback(4, "");

            try (Statement statement = rollbackConnection.createStatement();
                 ResultSet tables = statement.executeQuery("""
                     SELECT table_name FROM information_schema.tables
                      WHERE table_schema = 'rollback_test' AND table_name LIKE 'aperture_%'
                     """)) {
                assertThat(tables.next()).isFalse();
            }
            try (Statement statement = rollbackConnection.createStatement();
                 ResultSet functions = statement.executeQuery("""
                     SELECT proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                      WHERE n.nspname = 'rollback_test' AND proname = 'prevent_audit_modifications'
                     """)) {
                assertThat(functions.next()).isFalse();
            }
        }
    }

    private static void assertColumn(String table, String column, String type, boolean nullable)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT data_type, is_nullable
              FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
            """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("column %s.%s exists", table, column).isTrue();
                assertThat(result.getString("data_type")).isEqualTo(type);
                assertThat(result.getString("is_nullable")).isEqualTo(nullable ? "YES" : "NO");
            }
        }
    }

    private static void assertVarchar255(String table, String... columns) throws SQLException {
        for (String column : columns) {
            try (var statement = connection.prepareStatement("""
                SELECT data_type, character_maximum_length
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """)) {
                statement.setString(1, table);
                statement.setString(2, column);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).as("column %s.%s exists", table, column).isTrue();
                    assertThat(result.getString("data_type"))
                        .as("type of %s.%s", table, column)
                        .isEqualTo("character varying");
                    assertThat(result.getInt("character_maximum_length"))
                        .as("length of %s.%s", table, column)
                        .isEqualTo(255);
                }
            }
        }
    }

    private static String columnDefault(String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT column_default FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
            """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static List<String> columnNames(String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT column_name FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position
            """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                var columns = new java.util.ArrayList<String>();
                while (result.next()) {
                    columns.add(result.getString(1));
                }
                return columns;
            }
        }
    }

    private static List<String> primaryKeyColumns(String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT a.attname
              FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid
              JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, position) ON true
              JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
             WHERE t.relname = ? AND c.contype = 'p'
             ORDER BY k.position
            """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                var columns = new java.util.ArrayList<String>();
                while (result.next()) {
                    columns.add(result.getString(1));
                }
                return columns;
            }
        }
    }

    private static List<List<String>> uniqueConstraintColumns(String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT array_agg(a.attname ORDER BY k.position)
              FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid
              JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, position) ON true
              JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
             WHERE t.relname = ? AND c.contype = 'u'
             GROUP BY c.oid
            """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                var constraints = new java.util.ArrayList<List<String>>();
                while (result.next()) {
                    constraints.add(List.of((String[]) result.getArray(1).getArray()));
                }
                return constraints;
            }
        }
    }

    private static boolean foreignKey(
        String table, String column, String referencedTable, String referencedColumn) throws SQLException {
        return compositeForeignKey(
            table, List.of(column), referencedTable, List.of(referencedColumn));
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

    private static void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
