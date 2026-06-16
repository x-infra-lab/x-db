package io.github.xinfra.lab.xdb.planner.cost;

import io.github.xinfra.lab.xdb.expression.Datum;

public class ColumnStatistics {
    private long ndv;
    private Datum minValue;
    private Datum maxValue;
    private long nullCount;
    private long totalCount;
    private Histogram histogram;

    public ColumnStatistics() {}

    public ColumnStatistics(long ndv, Datum minValue, Datum maxValue,
                            long nullCount, long totalCount) {
        this.ndv = ndv;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.nullCount = nullCount;
        this.totalCount = totalCount;
    }

    public long getNdv() { return ndv; }
    public void setNdv(long ndv) { this.ndv = ndv; }

    public Datum getMinValue() { return minValue; }
    public void setMinValue(Datum minValue) { this.minValue = minValue; }

    public Datum getMaxValue() { return maxValue; }
    public void setMaxValue(Datum maxValue) { this.maxValue = maxValue; }

    public long getNullCount() { return nullCount; }
    public void setNullCount(long nullCount) { this.nullCount = nullCount; }

    public long getTotalCount() { return totalCount; }
    public void setTotalCount(long totalCount) { this.totalCount = totalCount; }

    public Histogram getHistogram() { return histogram; }
    public void setHistogram(Histogram histogram) { this.histogram = histogram; }

    public double nullFraction() {
        return totalCount > 0 ? (double) nullCount / totalCount : 0.0;
    }

    public double equalitySelectivity() {
        if (ndv > 0) return 1.0 / ndv;
        return 0.33;
    }

    public double estimateEqual(Datum value) {
        if (histogram != null) return histogram.estimateEqual(value);
        return equalitySelectivity();
    }

    public double estimateLessThan(Datum value) {
        if (histogram != null) return histogram.estimateLessThan(value);
        return ndv > 0 ? Math.min(1.0, 3.0 / ndv) : 0.33;
    }

    public double estimateGreaterThan(Datum value) {
        if (histogram != null) return histogram.estimateGreaterThan(value);
        return ndv > 0 ? Math.min(1.0, 3.0 / ndv) : 0.33;
    }

    public double estimateRange(Datum low, Datum high) {
        if (histogram != null) return histogram.estimateRange(low, high, true, false);
        return ndv > 0 ? Math.min(1.0, 3.0 / ndv) : 0.33;
    }
}
