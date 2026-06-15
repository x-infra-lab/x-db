package io.github.xinfra.lab.xdb.planner.cost;

import java.util.concurrent.ConcurrentHashMap;

public class StatsStore {
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
}
