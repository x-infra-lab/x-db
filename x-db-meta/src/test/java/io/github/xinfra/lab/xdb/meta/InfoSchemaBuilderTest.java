package io.github.xinfra.lab.xdb.meta;

import io.github.xinfra.lab.xdb.expression.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InfoSchemaBuilderTest {

    private InMemoryMetaStore createStoreWithData() {
        InMemoryMetaStore store = new InMemoryMetaStore();
        store.setSchemaVersion(3);

        DatabaseInfo db = new DatabaseInfo(1L, "mydb", "utf8mb4", "utf8mb4_general_ci", SchemaState.PUBLIC);
        store.createDatabase(db);

        ColumnInfo col = new ColumnInfo(1L, "id", DataType.BIGINT, 20, 0,
                false, false, true, null, null, 0, SchemaState.PUBLIC);
        IndexInfo pk = new IndexInfo(1L, "PRIMARY", 10L,
                List.of(new IndexColumn("id", 1L, 0)), true, true, SchemaState.PUBLIC);
        TableInfo table = new TableInfo(10L, "users", 1L, "utf8mb4", "utf8mb4_general_ci",
                null, null, List.of(col), List.of(pk), 0, SchemaState.PUBLIC, 1, 1);
        store.createTable(1L, table);

        return store;
    }

    @Test
    @DisplayName("build produces InfoSchema with correct version")
    void buildVersion() {
        InMemoryMetaStore store = createStoreWithData();
        InfoSchema schema = InfoSchemaBuilder.build(store);
        assertThat(schema.schemaVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("build includes all databases")
    void buildDatabases() {
        InMemoryMetaStore store = createStoreWithData();
        InfoSchema schema = InfoSchemaBuilder.build(store);
        assertThat(schema.listDatabases()).hasSize(1);
        assertThat(schema.getDatabase("mydb")).isNotNull();
    }

    @Test
    @DisplayName("build includes all tables")
    void buildTables() {
        InMemoryMetaStore store = createStoreWithData();
        InfoSchema schema = InfoSchemaBuilder.build(store);
        assertThat(schema.listTables("mydb")).hasSize(1);
        assertThat(schema.getTable("mydb", "users")).isNotNull();
    }

    @Test
    @DisplayName("build from empty store produces empty schema")
    void buildEmpty() {
        InMemoryMetaStore store = new InMemoryMetaStore();
        InfoSchema schema = InfoSchemaBuilder.build(store);
        assertThat(schema.schemaVersion()).isEqualTo(0);
        assertThat(schema.listDatabases()).isEmpty();
    }

    @Test
    @DisplayName("build with multiple databases and tables")
    void buildMultiple() {
        InMemoryMetaStore store = new InMemoryMetaStore();
        store.setSchemaVersion(10);

        for (int i = 1; i <= 3; i++) {
            DatabaseInfo db = new DatabaseInfo(i, "db" + i, "utf8mb4", "utf8mb4_general_ci", SchemaState.PUBLIC);
            store.createDatabase(db);
            for (int j = 1; j <= 2; j++) {
                long tableId = i * 100L + j;
                ColumnInfo col = new ColumnInfo(1L, "id", DataType.BIGINT, 20, 0,
                        false, false, false, null, null, 0, SchemaState.PUBLIC);
                TableInfo table = new TableInfo(tableId, "t" + j, i, "utf8mb4",
                        "utf8mb4_general_ci", null, null, List.of(col), List.of(),
                        0, SchemaState.PUBLIC, 1, 0);
                store.createTable(i, table);
            }
        }

        InfoSchema schema = InfoSchemaBuilder.build(store);
        assertThat(schema.listDatabases()).hasSize(3);
        for (int i = 1; i <= 3; i++) {
            assertThat(schema.listTables("db" + i)).hasSize(2);
        }
    }

    @Test
    @DisplayName("build skips null databases returned by store")
    void buildSkipsNullDb() {
        InMemoryMetaStore store = new InMemoryMetaStore();
        store.setSchemaVersion(1);
        store.createDatabase(new DatabaseInfo(1L, "db1", "utf8mb4", "utf8mb4_general_ci", SchemaState.PUBLIC));
        // Drop the db but leave the ID in the list (simulating stale state)
        store.dropDatabase(1L);
        // Re-add just the ID reference
        store.forceAddDbId(1L);

        InfoSchema schema = InfoSchemaBuilder.build(store);
        assertThat(schema.listDatabases()).isEmpty();
    }

    @Test
    @DisplayName("rebuild after schema change reflects new state")
    void rebuildAfterChange() {
        InMemoryMetaStore store = createStoreWithData();
        InfoSchema v1 = InfoSchemaBuilder.build(store);
        assertThat(v1.listTables("mydb")).hasSize(1);

        // Add a table
        ColumnInfo col = new ColumnInfo(1L, "id", DataType.BIGINT, 20, 0,
                false, false, false, null, null, 0, SchemaState.PUBLIC);
        TableInfo newTable = new TableInfo(20L, "orders", 1L, "utf8mb4",
                "utf8mb4_general_ci", null, null, List.of(col), List.of(),
                0, SchemaState.PUBLIC, 1, 0);
        store.createTable(1L, newTable);
        store.setSchemaVersion(4);

        InfoSchema v2 = InfoSchemaBuilder.build(store);
        assertThat(v2.schemaVersion()).isEqualTo(4);
        assertThat(v2.listTables("mydb")).hasSize(2);

        // v1 should be unchanged (immutable snapshot)
        assertThat(v1.listTables("mydb")).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // In-memory MetaStore for testing
    // -----------------------------------------------------------------------

    static class InMemoryMetaStore implements MetaStore {
        private long schemaVersion;
        private final Map<Long, DatabaseInfo> databases = new HashMap<>();
        private final Map<Long, Map<Long, TableInfo>> tables = new HashMap<>();
        private final AtomicLong globalIdGen = new AtomicLong(1000);
        private final List<Long> extraDbIds = new ArrayList<>();

        @Override public long getSchemaVersion() { return schemaVersion; }
        @Override public void setSchemaVersion(long version) { this.schemaVersion = version; }
        @Override public long advanceSchemaVersion() { return ++schemaVersion; }
        @Override public void createDatabase(DatabaseInfo db) {
            databases.put(db.getId(), db);
            tables.putIfAbsent(db.getId(), new HashMap<>());
        }
        @Override public void dropDatabase(long dbId) { databases.remove(dbId); tables.remove(dbId); }
        @Override public DatabaseInfo getDatabase(long dbId) { return databases.get(dbId); }
        @Override public List<Long> listDatabaseIds() {
            List<Long> ids = new ArrayList<>(databases.keySet());
            ids.addAll(extraDbIds);
            return ids;
        }
        @Override public void createTable(long dbId, TableInfo table) {
            tables.computeIfAbsent(dbId, k -> new HashMap<>()).put(table.getId(), table);
        }
        @Override public void updateTable(long dbId, TableInfo table) {
            Map<Long, TableInfo> m = tables.get(dbId);
            if (m != null) m.put(table.getId(), table);
        }
        @Override public void dropTable(long dbId, long tableId) {
            Map<Long, TableInfo> m = tables.get(dbId);
            if (m != null) m.remove(tableId);
        }
        @Override public TableInfo getTable(long dbId, long tableId) {
            Map<Long, TableInfo> m = tables.get(dbId);
            return m != null ? m.get(tableId) : null;
        }
        @Override public List<Long> listTableIds(long dbId) {
            Map<Long, TableInfo> m = tables.get(dbId);
            return m != null ? new ArrayList<>(m.keySet()) : new ArrayList<>();
        }
        @Override public long allocAutoIncId(long tableId, int batchSize) { return 0; }
        @Override public long allocGlobalId() { return globalIdGen.incrementAndGet(); }
        @Override public void putTableStats(long tableId, byte[] statsJson) {}
        @Override public byte[] getTableStats(long tableId) { return null; }

        void forceAddDbId(long dbId) { extraDbIds.add(dbId); }
    }
}
