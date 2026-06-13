package io.github.xinfra.lab.xdb.planner.cost;

public class Statistics {
    private long rowCount;
    private long dataSize;
    private int columnCount;

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

    public double selectivity(int conditionCount) {
        double sel = 1.0;
        for (int i = 0; i < conditionCount; i++) {
            sel *= 0.33;
        }
        return sel;
    }
}
