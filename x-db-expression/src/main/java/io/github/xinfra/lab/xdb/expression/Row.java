package io.github.xinfra.lab.xdb.expression;

import java.util.Arrays;

public final class Row {
    private final Datum[] values;

    public Row(Datum[] values) { this.values = values; }

    public Row(int size) {
        this.values = new Datum[size];
        Arrays.fill(this.values, Datum.nil());
    }

    public Datum get(int index) { return values[index]; }
    public void set(int index, Datum value) { values[index] = value; }
    public int size() { return values.length; }
    public Datum[] values() { return values; }

    public Row copy() { return new Row(Arrays.copyOf(values, values.length)); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Row other)) return false;
        return Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    public long estimateMemoryBytes() {
        long size = 32 + (long) values.length * 8;
        for (Datum d : values) {
            size += Datum.estimateMemoryBytes(d);
        }
        return size;
    }

    @Override public String toString() { return Arrays.toString(values); }
}
