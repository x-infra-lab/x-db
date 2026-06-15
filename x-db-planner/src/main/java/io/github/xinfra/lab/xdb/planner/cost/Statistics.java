package io.github.xinfra.lab.xdb.planner.cost;

import java.util.Map;

public class Statistics {
    private long rowCount;
    private long dataSize;
    private int columnCount;
    private Map<String, ColumnStatistics> columnStats;

    public Statistics() {
        this.rowCount = 10000;
        this.dataSize = 1024 * 1024;
        this.columnCount = 5;
    }

    public Statistics(long rowCount, long dataSize, int columnCount) {
        this.rowCount = rowCount;
        this.dataSize = dataSize;
        this.columnCount = columnCount;
    }

    public long rowCount() { return rowCount; }
    public long dataSize() { return dataSize; }
    public int columnCount() { return columnCount; }

    public void setRowCount(long rowCount) { this.rowCount = rowCount; }
    public void setDataSize(long dataSize) { this.dataSize = dataSize; }
    public void setColumnCount(int columnCount) { this.columnCount = columnCount; }

    public Map<String, ColumnStatistics> columnStats() { return columnStats; }
    public void setColumnStats(Map<String, ColumnStatistics> columnStats) {
        this.columnStats = columnStats;
    }

    public double selectivity(int conditionCount) {
        double sel = 1.0;
        for (int i = 0; i < conditionCount; i++) {
            sel *= 0.33;
        }
        return sel;
    }

    public double equalitySelectivity(String column) {
        if (columnStats != null) {
            ColumnStatistics cs = columnStats.get(column.toLowerCase());
            if (cs != null && cs.getNdv() > 0) {
                return 1.0 / cs.getNdv();
            }
        }
        return 0.33;
    }

    public double rangeSelectivity(String column) {
        if (columnStats != null) {
            ColumnStatistics cs = columnStats.get(column.toLowerCase());
            if (cs != null && cs.getNdv() > 0) {
                return Math.min(1.0, 3.0 / cs.getNdv());
            }
        }
        return 0.33;
    }
}
