package io.github.xinfra.lab.xdb.planner.cost;

import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.DatumComparator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Histogram {

    private final List<Bucket> buckets;
    private final long totalCount;
    private final long ndv;
    private final Datum minValue;

    public Histogram(List<Bucket> buckets, long totalCount, long ndv, Datum minValue) {
        this.buckets = Collections.unmodifiableList(buckets);
        this.totalCount = totalCount;
        this.ndv = ndv;
        this.minValue = minValue;
    }

    public List<Bucket> getBuckets() { return buckets; }
    public long getTotalCount() { return totalCount; }
    public long getNdv() { return ndv; }
    public Datum getMinValue() { return minValue; }

    public static Histogram buildFromSorted(List<Datum> sortedValues, int numBuckets) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return new Histogram(List.of(), 0, 0, null);
        }

        long total = sortedValues.size();
        numBuckets = (int) Math.min(numBuckets, total);
        if (numBuckets <= 0) numBuckets = 1;

        long countDistinct = 0;
        Datum prev = null;
        for (Datum d : sortedValues) {
            if (prev == null || DatumComparator.compare(prev, d) != 0) {
                countDistinct++;
            }
            prev = d;
        }

        int valuesPerBucket = (int) Math.max(1, total / numBuckets);
        List<Bucket> buckets = new ArrayList<>();

        int pos = 0;
        while (pos < total) {
            int bucketEnd = Math.min(pos + valuesPerBucket, (int) total);

            // Extend to include all duplicates of the boundary value
            Datum boundary = sortedValues.get(bucketEnd - 1);
            while (bucketEnd < total && DatumComparator.compare(sortedValues.get(bucketEnd), boundary) == 0) {
                bucketEnd++;
            }

            long count = bucketEnd - pos;
            Datum upperBound = sortedValues.get(bucketEnd - 1);

            // Count repeats of upper bound
            long repeats = 0;
            for (int i = bucketEnd - 1; i >= pos; i--) {
                if (DatumComparator.compare(sortedValues.get(i), upperBound) == 0) {
                    repeats++;
                } else {
                    break;
                }
            }

            // Count distinct values in this bucket
            long bucketNdv = 0;
            Datum p = null;
            for (int i = pos; i < bucketEnd; i++) {
                Datum d = sortedValues.get(i);
                if (p == null || DatumComparator.compare(p, d) != 0) {
                    bucketNdv++;
                }
                p = d;
            }

            buckets.add(new Bucket(upperBound, count, repeats, bucketNdv));
            pos = bucketEnd;
        }

        return new Histogram(buckets, total, countDistinct, sortedValues.get(0));
    }

    public double estimateEqual(Datum value) {
        if (buckets.isEmpty() || totalCount == 0) return 0.0;
        if (value == null || value.isNull()) return 0.0;

        int idx = findBucket(value);
        if (idx < 0) return 0.0;

        Bucket b = buckets.get(idx);
        if (DatumComparator.compare(value, b.upperBound) == 0) {
            return (double) b.repeats / totalCount;
        }
        if (b.ndv <= 1) return 0.0;
        return (double) (b.count - b.repeats) / ((b.ndv - 1) * totalCount);
    }

    public double estimateLessThan(Datum value) {
        if (buckets.isEmpty() || totalCount == 0) return 0.0;
        if (value == null || value.isNull()) return 0.0;
        if (minValue != null && DatumComparator.compare(value, minValue) <= 0) return 0.0;

        long countLess = 0;
        Datum prevUpper = minValue;
        for (Bucket b : buckets) {
            int cmp = DatumComparator.compare(value, b.upperBound);
            if (cmp > 0) {
                countLess += b.count;
            } else if (cmp == 0) {
                countLess += b.count - b.repeats;
                break;
            } else {
                if (b.ndv > 1) {
                    double fraction = estimateFractionInBucket(prevUpper, b.upperBound, value);
                    countLess += (long) (fraction * (b.count - b.repeats));
                }
                break;
            }
            prevUpper = b.upperBound;
        }
        return (double) countLess / totalCount;
    }

    public double estimateGreaterThan(Datum value) {
        return Math.max(0.0, 1.0 - estimateLessThan(value) - estimateEqual(value));
    }

    public double estimateRange(Datum low, Datum high, boolean lowInclusive, boolean highInclusive) {
        if (buckets.isEmpty() || totalCount == 0) return 0.0;

        double selHigh = highInclusive
                ? estimateLessThan(high) + estimateEqual(high)
                : estimateLessThan(high);
        double selLow = lowInclusive
                ? estimateLessThan(low)
                : estimateLessThan(low) + estimateEqual(low);

        return Math.max(0.0, selHigh - selLow);
    }

    private int findBucket(Datum value) {
        int lo = 0, hi = buckets.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = DatumComparator.compare(value, buckets.get(mid).upperBound);
            if (cmp <= 0) {
                if (mid == 0 || DatumComparator.compare(value, buckets.get(mid - 1).upperBound) > 0) {
                    return mid;
                }
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return -1;
    }

    private double estimateFractionInBucket(Datum lowerBound, Datum upperBound, Datum value) {
        if (lowerBound == null || upperBound == null) return 0.5;
        try {
            double lo = toDouble(lowerBound);
            double hi = toDouble(upperBound);
            double v = toDouble(value);
            if (hi <= lo) return 0.5;
            return Math.max(0.0, Math.min(1.0, (v - lo) / (hi - lo)));
        } catch (UnsupportedOperationException e) {
            return 0.5;
        }
    }

    private static double toDouble(Datum d) {
        if (d instanceof Datum.IntDatum i) return i.value();
        if (d instanceof Datum.DoubleDatum dd) return dd.value();
        if (d instanceof Datum.DecimalDatum dec) return dec.value().doubleValue();
        throw new UnsupportedOperationException("Cannot convert " + d.getClass() + " to double for interpolation");
    }

    public record Bucket(Datum upperBound, long count, long repeats, long ndv) {}
}
