package io.github.xinfra.lab.xdb.meta;

import io.github.xinfra.lab.xdb.expression.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InfoSchemaTest {

    private InfoSchema schema;
    private DatabaseInfo db1;
    private DatabaseInfo db2;
    private TableInfo usersTable;
    private TableInfo ordersTable;

    @BeforeEach
    void setUp() {
        db1 = new DatabaseInfo(1L, "testdb", "utf8mb4", "utf8mb4_general_ci", SchemaState.PUBLIC);
        db2 = new DatabaseInfo(2L, "otherdb", "utf8mb4", "utf8mb4_general_ci", SchemaState.PUBLIC);

        usersTable = createTable(100L, "users", 1L);
        ordersTable = createTable(200L, "orders", 1L);

        schema = new InfoSchema(5L, List.of(db1, db2), List.of(usersTable, ordersTable));
    }

    private TableInfo createTable(long id, String name, long dbId) {
        ColumnInfo col1 = new ColumnInfo(1L, "id", DataType.BIGINT, 20, 0,
                false, false, true, null, null, 0, SchemaState.PUBLIC);
        ColumnInfo col2 = new ColumnInfo(2L, "name", DataType.VARCHAR, 255, 0,
                true, false, false, null, null, 1, SchemaState.PUBLIC);
        IndexInfo pk = new IndexInfo(1L, "PRIMARY", id,
                List.of(new IndexColumn("id", 1L, 0)), true, true, SchemaState.PUBLIC);

        return new TableInfo(id, name, dbId, "utf8mb4", "utf8mb4_general_ci",
                null, null, List.of(col1, col2), List.of(pk), 0, SchemaState.PUBLIC, 2, 1);
    }

    @Test
    @DisplayName("schemaVersion returns the version passed at construction")
    void schemaVersion() {
        assertThat(schema.schemaVersion()).isEqualTo(5L);
    }

    // -----------------------------------------------------------------------
    // Database lookups
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Database lookups")
    class DatabaseLookups {

        @Test
        @DisplayName("getDatabase by name returns correct database")
        void getByName() {
            assertThat(schema.getDatabase("testdb")).isNotNull();
            assertThat(schema.getDatabase("testdb").getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("getDatabase by name is case-insensitive")
        void getByNameCaseInsensitive() {
            assertThat(schema.getDatabase("TESTDB")).isNotNull();
            assertThat(schema.getDatabase("TestDb")).isNotNull();
            assertThat(schema.getDatabase("TESTDB").getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("getDatabase by name returns null for unknown database")
        void getByNameUnknown() {
            assertThat(schema.getDatabase("nonexistent")).isNull();
        }

        @Test
        @DisplayName("getDatabase by name returns null for null input")
        void getByNameNull() {
            assertThat(schema.getDatabase((String) null)).isNull();
        }

        @Test
        @DisplayName("getDatabase by id returns correct database")
        void getById() {
            assertThat(schema.getDatabase(1L)).isNotNull();
            assertThat(schema.getDatabase(1L).getName()).isEqualTo("testdb");
        }

        @Test
        @DisplayName("getDatabase by id returns null for unknown id")
        void getByIdUnknown() {
            assertThat(schema.getDatabase(999L)).isNull();
        }

        @Test
        @DisplayName("listDatabases returns all databases")
        void listDatabases() {
            List<DatabaseInfo> dbs = schema.listDatabases();
            assertThat(dbs).hasSize(2);
            assertThat(dbs).extracting(DatabaseInfo::getName)
                    .containsExactlyInAnyOrder("testdb", "otherdb");
        }
    }

    // -----------------------------------------------------------------------
    // Table lookups
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Table lookups")
    class TableLookups {

        @Test
        @DisplayName("getTable by dbName and tableName")
        void getByDbAndName() {
            TableInfo tbl = schema.getTable("testdb", "users");
            assertThat(tbl).isNotNull();
            assertThat(tbl.getId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("getTable is case-insensitive")
        void getByDbAndNameCaseInsensitive() {
            assertThat(schema.getTable("TESTDB", "USERS")).isNotNull();
            assertThat(schema.getTable("TestDb", "Users")).isNotNull();
        }

        @Test
        @DisplayName("getTable returns null for unknown table")
        void getByDbAndNameUnknown() {
            assertThat(schema.getTable("testdb", "nonexistent")).isNull();
        }

        @Test
        @DisplayName("getTable returns null for unknown database")
        void getTableUnknownDb() {
            assertThat(schema.getTable("nodb", "users")).isNull();
        }

        @Test
        @DisplayName("getTable returns null for null inputs")
        void getTableNulls() {
            assertThat(schema.getTable(null, "users")).isNull();
            assertThat(schema.getTable("testdb", null)).isNull();
            assertThat(schema.getTable(null, null)).isNull();
        }

        @Test
        @DisplayName("getTable by id")
        void getById() {
            TableInfo tbl = schema.getTable(100L);
            assertThat(tbl).isNotNull();
            assertThat(tbl.getName()).isEqualTo("users");
        }

        @Test
        @DisplayName("getTable by id returns null for unknown id")
        void getByIdUnknown() {
            assertThat(schema.getTable(999L)).isNull();
        }

        @Test
        @DisplayName("listTables by dbName")
        void listByDbName() {
            List<TableInfo> tables = schema.listTables("testdb");
            assertThat(tables).hasSize(2);
            assertThat(tables).extracting(TableInfo::getName)
                    .containsExactlyInAnyOrder("users", "orders");
        }

        @Test
        @DisplayName("listTables for database with no tables")
        void listByDbNameEmpty() {
            List<TableInfo> tables = schema.listTables("otherdb");
            assertThat(tables).isEmpty();
        }

        @Test
        @DisplayName("listTables for unknown database")
        void listByDbNameUnknown() {
            assertThat(schema.listTables("nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("listTables for null database")
        void listByDbNameNull() {
            assertThat(schema.listTables((String) null)).isEmpty();
        }

        @Test
        @DisplayName("listTables by dbId")
        void listByDbId() {
            List<TableInfo> tables = schema.listTables(1L);
            assertThat(tables).hasSize(2);
        }

        @Test
        @DisplayName("listTables by dbId with no tables")
        void listByDbIdNoTables() {
            assertThat(schema.listTables(999L)).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty InfoSchema returns empty lists")
        void emptySchema() {
            InfoSchema empty = new InfoSchema(0L, List.of(), List.of());
            assertThat(empty.schemaVersion()).isEqualTo(0L);
            assertThat(empty.listDatabases()).isEmpty();
            assertThat(empty.getDatabase("any")).isNull();
            assertThat(empty.getTable("any", "any")).isNull();
        }

        @Test
        @DisplayName("InfoSchema is immutable — listDatabases returns a copy")
        void immutableDatabases() {
            List<DatabaseInfo> dbs = schema.listDatabases();
            int originalSize = dbs.size();
            dbs.clear();
            assertThat(schema.listDatabases()).hasSize(originalSize);
        }

        @Test
        @DisplayName("InfoSchema is immutable — listTables returns a copy")
        void immutableTables() {
            List<TableInfo> tables = schema.listTables("testdb");
            int originalSize = tables.size();
            tables.clear();
            assertThat(schema.listTables("testdb")).hasSize(originalSize);
        }
    }
}
