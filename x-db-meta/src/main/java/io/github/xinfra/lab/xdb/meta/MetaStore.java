package io.github.xinfra.lab.xdb.meta;

import java.util.List;

/**
 * Interface for storing and retrieving schema metadata from KV storage.
 */
public interface MetaStore {

    long getSchemaVersion();

    void setSchemaVersion(long version);

    long advanceSchemaVersion();

    default void truncateTable(long dbId, long tableId) {
        dropTable(dbId, tableId);
    }

    void createDatabase(DatabaseInfo db);

    void dropDatabase(long dbId);

    DatabaseInfo getDatabase(long dbId);

    List<Long> listDatabaseIds();

    void createTable(long dbId, TableInfo table);

    void updateTable(long dbId, TableInfo table);

    void dropTable(long dbId, long tableId);

    TableInfo getTable(long dbId, long tableId);

    List<Long> listTableIds(long dbId);

    long allocAutoIncId(long tableId, int batchSize);

    long allocGlobalId();

    void putTableStats(long tableId, byte[] statsJson);

    byte[] getTableStats(long tableId);
}
