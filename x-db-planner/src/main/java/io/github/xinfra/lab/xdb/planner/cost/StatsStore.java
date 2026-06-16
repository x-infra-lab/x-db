package io.github.xinfra.lab.xdb.planner.cost;

import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public class StatsStore {
    private static final Logger log = LoggerFactory.getLogger(StatsStore.class);
    private static final StatsStore INSTANCE = new StatsStore();

    private final ConcurrentHashMap<Long, TableStatistics> tableStats = new ConcurrentHashMap<>();

    public static StatsStore getInstance() { return INSTANCE; }

    public TableStatistics getTableStats(long tableId) {
        return tableStats.get(tableId);
    }

    public void putTableStats(long tableId, TableStatistics stats) {
        tableStats.put(tableId, stats);
    }

    public void removeTableStats(long tableId) {
        tableStats.remove(tableId);
    }

    public void clear() {
        tableStats.clear();
    }

    public void persistToMetaStore(long tableId, TableStatistics stats, MetaStore metaStore) {
        byte[] data = StatsSerializer.serialize(stats);
        metaStore.putTableStats(tableId, data);
        putTableStats(tableId, stats);
    }

    public void loadFromMetaStore(MetaStore metaStore, InfoSchema infoSchema) {
        if (infoSchema == null) return;
        for (var db : infoSchema.listDatabases()) {
            for (TableInfo table : infoSchema.listTables(db.getName())) {
                try {
                    byte[] data = metaStore.getTableStats(table.getId());
                    if (data != null && data.length > 0) {
                        TableStatistics ts = StatsSerializer.deserialize(data);
                        putTableStats(table.getId(), ts);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load stats for table {}: {}", table.getName(), e.getMessage());
                }
            }
        }
    }
}
