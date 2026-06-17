package io.github.xinfra.lab.xdb.meta;

import io.github.xinfra.lab.xdb.expression.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableInfoTest {

    private TableInfo table;

    @BeforeEach
    void setUp() {
        ColumnInfo id = new ColumnInfo(1L, "id", DataType.BIGINT, 20, 0,
                false, false, true, null, null, 0, SchemaState.PUBLIC);
        ColumnInfo name = new ColumnInfo(2L, "name", DataType.VARCHAR, 255, 0,
                true, false, false, null, null, 1, SchemaState.PUBLIC);
        ColumnInfo email = new ColumnInfo(3L, "email", DataType.VARCHAR, 255, 0,
                true, false, false, null, null, 2, SchemaState.PUBLIC);
        ColumnInfo deletedCol = new ColumnInfo(4L, "old_col", DataType.INT, 11, 0,
                true, false, false, null, null, 3, SchemaState.DELETE_ONLY);

        IndexInfo pk = new IndexInfo(1L, "PRIMARY", 100L,
                List.of(new IndexColumn("id", 1L, 0)), true, true, SchemaState.PUBLIC);
        IndexInfo emailIdx = new IndexInfo(2L, "idx_email", 100L,
                List.of(new IndexColumn("email", 3L, 0)), true, false, SchemaState.PUBLIC);
        IndexInfo nameIdx = new IndexInfo(3L, "idx_name", 100L,
                List.of(new IndexColumn("name", 2L, 0)), false, false, SchemaState.PUBLIC);

        table = new TableInfo(100L, "users", 1L, "utf8mb4", "utf8mb4_general_ci",
                "user table", "xkv", List.of(id, name, email, deletedCol),
                List.of(pk, emailIdx, nameIdx), 0, SchemaState.PUBLIC, 4, 3);
    }

    // -----------------------------------------------------------------------
    // Column lookups
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Column lookups")
    class ColumnLookups {

        @Test
        @DisplayName("getColumn by name")
        void byName() {
            ColumnInfo col = table.getColumn("name");
            assertThat(col).isNotNull();
            assertThat(col.getType()).isEqualTo(DataType.VARCHAR);
        }

        @Test
        @DisplayName("getColumn by name is case-insensitive")
        void byNameCaseInsensitive() {
            assertThat(table.getColumn("NAME")).isNotNull();
            assertThat(table.getColumn("Name")).isNotNull();
        }

        @Test
        @DisplayName("getColumn by name returns null for unknown")
        void byNameUnknown() {
            assertThat(table.getColumn("nonexistent")).isNull();
        }

        @Test
        @DisplayName("getColumn by name returns null for null")
        void byNameNull() {
            assertThat(table.getColumn((String) null)).isNull();
        }

        @Test
        @DisplayName("getColumn by id")
        void byId() {
            ColumnInfo col = table.getColumn(2L);
            assertThat(col).isNotNull();
            assertThat(col.getName()).isEqualTo("name");
        }

        @Test
        @DisplayName("getColumn by id returns null for unknown")
        void byIdUnknown() {
            assertThat(table.getColumn(999L)).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // Public columns filtering
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Public columns")
    class PublicColumns {

        @Test
        @DisplayName("getPublicColumns excludes non-PUBLIC columns")
        void filtersNonPublic() {
            List<ColumnInfo> publicCols = table.getPublicColumns();
            assertThat(publicCols).hasSize(3);
            assertThat(publicCols).extracting(ColumnInfo::getName)
                    .containsExactlyInAnyOrder("id", "name", "email");
            assertThat(publicCols).extracting(ColumnInfo::getName)
                    .doesNotContain("old_col");
        }

        @Test
        @DisplayName("getPublicColumns returns empty for table with no PUBLIC columns")
        void allNonPublic() {
            ColumnInfo col = new ColumnInfo(1L, "x", DataType.INT, 11, 0,
                    true, false, false, null, null, 0, SchemaState.WRITE_ONLY);
            TableInfo t = new TableInfo(1L, "t", 1L, null, null, null, null,
                    List.of(col), List.of(), 0, SchemaState.PUBLIC, 1, 0);
            assertThat(t.getPublicColumns()).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Index lookups
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Index lookups")
    class IndexLookups {

        @Test
        @DisplayName("getPrimaryIndex returns primary key")
        void primaryIndex() {
            IndexInfo pk = table.getPrimaryIndex();
            assertThat(pk).isNotNull();
            assertThat(pk.getName()).isEqualTo("PRIMARY");
            assertThat(pk.isPrimary()).isTrue();
            assertThat(pk.isUnique()).isTrue();
        }

        @Test
        @DisplayName("getPrimaryIndex returns null when no primary")
        void noPrimaryIndex() {
            TableInfo t = new TableInfo(1L, "t", 1L, null, null, null, null,
                    List.of(), List.of(), 0, SchemaState.PUBLIC, 0, 0);
            assertThat(t.getPrimaryIndex()).isNull();
        }

        @Test
        @DisplayName("getIndex by name")
        void byName() {
            IndexInfo idx = table.getIndex("idx_email");
            assertThat(idx).isNotNull();
            assertThat(idx.isUnique()).isTrue();
            assertThat(idx.isPrimary()).isFalse();
        }

        @Test
        @DisplayName("getIndex by name is case-insensitive")
        void byNameCaseInsensitive() {
            assertThat(table.getIndex("IDX_EMAIL")).isNotNull();
        }

        @Test
        @DisplayName("getIndex returns null for unknown name")
        void byNameUnknown() {
            assertThat(table.getIndex("nonexistent")).isNull();
        }

        @Test
        @DisplayName("getIndex returns null for null")
        void byNameNull() {
            assertThat(table.getIndex(null)).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // ID generation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ID generation")
    class IdGeneration {

        @Test
        @DisplayName("nextColumnId increments monotonically")
        void columnIdIncrement() {
            long id1 = table.nextColumnId();
            long id2 = table.nextColumnId();
            assertThat(id2).isGreaterThan(id1);
        }

        @Test
        @DisplayName("nextIndexId increments monotonically")
        void indexIdIncrement() {
            long id1 = table.nextIndexId();
            long id2 = table.nextIndexId();
            assertThat(id2).isGreaterThan(id1);
        }

        @Test
        @DisplayName("nextColumnId starts from maxColumnId + 1")
        void columnIdStartsFromMax() {
            assertThat(table.nextColumnId()).isEqualTo(5L);
        }

        @Test
        @DisplayName("nextIndexId starts from maxIndexId + 1")
        void indexIdStartsFromMax() {
            assertThat(table.nextIndexId()).isEqualTo(4L);
        }
    }

    // -----------------------------------------------------------------------
    // JSON serialization round-trip
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("JSON serialization")
    class JsonSerialization {

        @Test
        @DisplayName("DatabaseInfo round-trips through Jackson")
        void databaseInfoRoundTrip() throws Exception {
            DatabaseInfo original = new DatabaseInfo(42L, "testdb", "utf8mb4",
                    "utf8mb4_general_ci", SchemaState.PUBLIC);

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(original);
            DatabaseInfo restored = mapper.readValue(json, DatabaseInfo.class);

            assertThat(restored.getId()).isEqualTo(42L);
            assertThat(restored.getName()).isEqualTo("testdb");
            assertThat(restored.getCharset()).isEqualTo("utf8mb4");
            assertThat(restored.getCollation()).isEqualTo("utf8mb4_general_ci");
            assertThat(restored.getState()).isEqualTo(SchemaState.PUBLIC);
        }

        @Test
        @DisplayName("TableInfo round-trips through Jackson")
        void tableInfoRoundTrip() throws Exception {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(table);
            TableInfo restored = mapper.readValue(json, TableInfo.class);

            assertThat(restored.getId()).isEqualTo(100L);
            assertThat(restored.getName()).isEqualTo("users");
            assertThat(restored.getDbId()).isEqualTo(1L);
            assertThat(restored.getColumns()).hasSize(4);
            assertThat(restored.getIndices()).hasSize(3);
            assertThat(restored.getState()).isEqualTo(SchemaState.PUBLIC);
        }

        @Test
        @DisplayName("ColumnInfo preserves all fields through Jackson")
        void columnInfoRoundTrip() throws Exception {
            ColumnInfo original = new ColumnInfo(5L, "age", DataType.INT, 11, 0,
                    false, true, false, "0", "user age", 3, SchemaState.PUBLIC);

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(original);
            ColumnInfo restored = mapper.readValue(json, ColumnInfo.class);

            assertThat(restored.getId()).isEqualTo(5L);
            assertThat(restored.getName()).isEqualTo("age");
            assertThat(restored.getType()).isEqualTo(DataType.INT);
            assertThat(restored.isNullable()).isFalse();
            assertThat(restored.isUnsigned()).isTrue();
            assertThat(restored.getDefaultValue()).isEqualTo("0");
            assertThat(restored.getComment()).isEqualTo("user age");
            assertThat(restored.getState()).isEqualTo(SchemaState.PUBLIC);
        }

        @Test
        @DisplayName("IndexInfo preserves all fields through Jackson")
        void indexInfoRoundTrip() throws Exception {
            IndexColumn col = new IndexColumn("name", 2L, 10);
            IndexInfo original = new IndexInfo(7L, "idx_prefix", 100L,
                    List.of(col), true, false, SchemaState.WRITE_ONLY);

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(original);
            IndexInfo restored = mapper.readValue(json, IndexInfo.class);

            assertThat(restored.getId()).isEqualTo(7L);
            assertThat(restored.getName()).isEqualTo("idx_prefix");
            assertThat(restored.isUnique()).isTrue();
            assertThat(restored.isPrimary()).isFalse();
            assertThat(restored.getState()).isEqualTo(SchemaState.WRITE_ONLY);
            assertThat(restored.getColumns()).hasSize(1);
            assertThat(restored.getColumns().get(0).getColumnName()).isEqualTo("name");
            assertThat(restored.getColumns().get(0).getLength()).isEqualTo(10);
        }

        @Test
        @DisplayName("Jackson ignores unknown properties")
        void ignoresUnknownProperties() throws Exception {
            String json = "{\"id\":1,\"name\":\"db\",\"futureField\":\"value\",\"state\":\"PUBLIC\"}";
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            DatabaseInfo db = mapper.readValue(json, DatabaseInfo.class);
            assertThat(db.getId()).isEqualTo(1L);
            assertThat(db.getName()).isEqualTo("db");
            assertThat(db.getState()).isEqualTo(SchemaState.PUBLIC);
        }
    }

    // -----------------------------------------------------------------------
    // SchemaState
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("SchemaState")
    class SchemaStateTest {

        @Test
        @DisplayName("all 5 states are defined")
        void allStates() {
            SchemaState[] states = SchemaState.values();
            assertThat(states).hasSize(5);
            assertThat(states).containsExactly(
                    SchemaState.ABSENT,
                    SchemaState.DELETE_ONLY,
                    SchemaState.WRITE_ONLY,
                    SchemaState.WRITE_REORGANIZATION,
                    SchemaState.PUBLIC
            );
        }

        @Test
        @DisplayName("valueOf round-trips")
        void valueOf() {
            for (SchemaState s : SchemaState.values()) {
                assertThat(SchemaState.valueOf(s.name())).isEqualTo(s);
            }
        }
    }
}
