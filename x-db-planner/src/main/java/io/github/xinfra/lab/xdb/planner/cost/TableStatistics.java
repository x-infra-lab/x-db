package io.github.xinfra.lab.xdb.planner.cost;

import java.util.HashMap;
import java.util.Map;

public class TableStatistics {
    private long rowCount;
    private long dataSize;
    private Map<String, ColumnStatistics> columnStats;
    private long lastAnalyzeTime;

    public TableStatistics() {
        this.columnStats = new HashMap<>();
    }

    public TableStatistics(long rowCount, long dataSize) {
        this.rowCount = rowCount;
        this.dataSize = dataSize;
        this.columnStats = new HashMap<>();
        this.lastAnalyzeTime = System.currentTimeMillis();
    }

    public long getRowCount() { return rowCount; }
    public void setRowCount(long rowCount) { this.rowCount = rowCount; }

    public long getDataSize() { return dataSize; }
    public void setDataSize(long dataSize) { this.dataSize = dataSize; }

    public Map<String, ColumnStatistics> getColumnStats() { return columnStats; }
    public void setColumnStats(Map<String, ColumnStatistics> columnStats) {
        this.columnStats = columnStats;
    }

    public ColumnStatistics getColumnStat(String columnName) {
        return columnStats.get(columnName.toLowerCase());
    }

    public void putColumnStat(String columnName, ColumnStatistics stat) {
        columnStats.put(columnName.toLowerCase(), stat);
    }

    public long getLastAnalyzeTime() { return lastAnalyzeTime; }
    public void setLastAnalyzeTime(long lastAnalyzeTime) { this.lastAnalyzeTime = lastAnalyzeTime; }
}
