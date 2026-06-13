package io.github.xinfra.lab.xdb.meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of all database/table metadata at a specific schema version.
 */
public class InfoSchema {

    private final long schemaVersion;
    private final Map<String, DatabaseInfo> databasesByName;
    private final Map<Long, DatabaseInfo> databasesById;
    private final Map<Long, Map<String, TableInfo>> tablesByDbId; // dbId -> {name -> TableInfo}
    private final Map<Long, TableInfo> tablesById;

    public InfoSchema(long schemaVersion,
                      List<DatabaseInfo> databases,
                      List<TableInfo> tables) {
        this.schemaVersion = schemaVersion;

        Map<String, DatabaseInfo> byName = new HashMap<>();
        Map<Long, DatabaseInfo> byId = new HashMap<>();
        for (DatabaseInfo db : databases) {
            byName.put(db.getName().toLowerCase(), db);
            byId.put(db.getId(), db);
        }
        this.databasesByName = Collections.unmodifiableMap(byName);
        this.databasesById = Collections.unmodifiableMap(byId);

        Map<Long, Map<String, TableInfo>> tblByDb = new HashMap<>();
        Map<Long, TableInfo> tblById = new HashMap<>();
        for (TableInfo tbl : tables) {
            tblByDb.computeIfAbsent(tbl.getDbId(), k -> new HashMap<>())
                    .put(tbl.getName().toLowerCase(), tbl);
            tblById.put(tbl.getId(), tbl);
        }
        // Make inner maps unmodifiable
        Map<Long, Map<String, TableInfo>> unmodifiableTblByDb = new HashMap<>();
        for (Map.Entry<Long, Map<String, TableInfo>> entry : tblByDb.entrySet()) {
            unmodifiableTblByDb.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }
        this.tablesByDbId = Collections.unmodifiableMap(unmodifiableTblByDb);
        this.tablesById = Collections.unmodifiableMap(tblById);
    }

    public long schemaVersion() {
        return schemaVersion;
    }

    public DatabaseInfo getDatabase(String name) {
        if (name == null) {
            return null;
        }
        return databasesByName.get(name.toLowerCase());
    }

    public DatabaseInfo getDatabase(long id) {
        return databasesById.get(id);
    }

    public List<DatabaseInfo> listDatabases() {
        return new ArrayList<>(databasesById.values());
    }

    public TableInfo getTable(String dbName, String tableName) {
        if (dbName == null || tableName == null) {
            return null;
        }
        DatabaseInfo db = getDatabase(dbName);
        if (db == null) {
            return null;
        }
        Map<String, TableInfo> tables = tablesByDbId.get(db.getId());
        if (tables == null) {
            return null;
        }
        return tables.get(tableName.toLowerCase());
    }

    public TableInfo getTable(long tableId) {
        return tablesById.get(tableId);
    }

    public List<TableInfo> listTables(String dbName) {
        if (dbName == null) {
            return Collections.emptyList();
        }
        DatabaseInfo db = getDatabase(dbName);
        if (db == null) {
            return Collections.emptyList();
        }
        return listTables(db.getId());
    }

    public List<TableInfo> listTables(long dbId) {
        Map<String, TableInfo> tables = tablesByDbId.get(dbId);
        if (tables == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(tables.values());
    }
}
