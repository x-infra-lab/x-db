package io.github.xinfra.lab.xdb.meta;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds an immutable {@link InfoSchema} snapshot from a {@link MetaStore}.
 */
public class InfoSchemaBuilder {

    private InfoSchemaBuilder() {
    }

    public static InfoSchema build(MetaStore store) {
        long version = store.getSchemaVersion();

        List<DatabaseInfo> databases = new ArrayList<>();
        List<TableInfo> allTables = new ArrayList<>();

        List<Long> dbIds = store.listDatabaseIds();
        for (Long dbId : dbIds) {
            DatabaseInfo db = store.getDatabase(dbId);
            if (db == null) {
                continue;
            }
            databases.add(db);

            List<Long> tableIds = store.listTableIds(dbId);
            for (Long tableId : tableIds) {
                TableInfo table = store.getTable(dbId, tableId);
                if (table != null) {
                    allTables.add(table);
                }
            }
        }

        return new InfoSchema(version, databases, allTables);
    }
}
