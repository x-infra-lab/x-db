package io.github.xinfra.lab.xdb.ddl;

import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import io.github.xinfra.lab.xdb.meta.TableInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryMetaStore implements MetaStore {

    private long schemaVersion;
    private final Map<Long, DatabaseInfo> databases = new HashMap<>();
    private final Map<Long, Map<Long, TableInfo>> tables = new HashMap<>();
    private final Map<Long, AtomicLong> autoIncIds = new HashMap<>();
    private final AtomicLong globalIdGen = new AtomicLong(1000);

    @Override
    public long getSchemaVersion() {
        return schemaVersion;
    }

    @Override
    public void setSchemaVersion(long version) {
        this.schemaVersion = version;
    }

    @Override
    public void createDatabase(DatabaseInfo db) {
        databases.put(db.getId(), db);
        tables.putIfAbsent(db.getId(), new HashMap<>());
    }

    @Override
    public void dropDatabase(long dbId) {
        databases.remove(dbId);
        tables.remove(dbId);
    }

    @Override
    public DatabaseInfo getDatabase(long dbId) {
        return databases.get(dbId);
    }

    @Override
    public List<Long> listDatabaseIds() {
        return new ArrayList<>(databases.keySet());
    }

    @Override
    public void createTable(long dbId, TableInfo table) {
        tables.computeIfAbsent(dbId, k -> new HashMap<>()).put(table.getId(), table);
    }

    @Override
    public void updateTable(long dbId, TableInfo table) {
        Map<Long, TableInfo> dbTables = tables.get(dbId);
        if (dbTables != null) {
            dbTables.put(table.getId(), table);
        }
    }

    @Override
    public void dropTable(long dbId, long tableId) {
        Map<Long, TableInfo> dbTables = tables.get(dbId);
        if (dbTables != null) {
            dbTables.remove(tableId);
        }
    }

    @Override
    public TableInfo getTable(long dbId, long tableId) {
        Map<Long, TableInfo> dbTables = tables.get(dbId);
        return dbTables != null ? dbTables.get(tableId) : null;
    }

    @Override
    public List<Long> listTableIds(long dbId) {
        Map<Long, TableInfo> dbTables = tables.get(dbId);
        return dbTables != null ? new ArrayList<>(dbTables.keySet()) : new ArrayList<>();
    }

    @Override
    public long advanceSchemaVersion() {
        return ++schemaVersion;
    }

    @Override
    public long allocAutoIncId(long tableId, int batchSize) {
        return autoIncIds.computeIfAbsent(tableId, k -> new AtomicLong(0))
                .addAndGet(batchSize);
    }

    @Override
    public long allocGlobalId() {
        return globalIdGen.incrementAndGet();
    }

    private final Map<Long, byte[]> statsData = new HashMap<>();

    @Override
    public void putTableStats(long tableId, byte[] statsJson) {
        statsData.put(tableId, statsJson);
    }

    @Override
    public byte[] getTableStats(long tableId) {
        return statsData.get(tableId);
    }
}
