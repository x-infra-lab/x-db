package io.github.xinfra.lab.xdb.executor.spill;

import io.github.xinfra.lab.xdb.expression.Row;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * K-way merge over multiple {@link SortedRun}s using a min-heap.
 * Produces rows in globally sorted order according to the provided comparator.
 */
public class MergeSortIterator implements AutoCloseable {

    private final List<SortedRun> runs;
    private final PriorityQueue<Entry> heap;

    private static class Entry {
        final Row row;
        final int runIndex;

        Entry(Row row, int runIndex) {
            this.row = row;
            this.runIndex = runIndex;
        }
    }

    public MergeSortIterator(List<SortedRun> runs, Comparator<Row> comparator) throws IOException {
        this.runs = runs;
        this.heap = new PriorityQueue<>((a, b) -> comparator.compare(a.row, b.row));

        for (int i = 0; i < runs.size(); i++) {
            Row row = runs.get(i).readNext();
            if (row != null) {
                heap.add(new Entry(row, i));
            }
        }
    }

    public Row next() throws IOException {
        if (heap.isEmpty()) {
            return null;
        }
        Entry top = heap.poll();
        Row result = top.row;

        Row nextRow = runs.get(top.runIndex).readNext();
        if (nextRow != null) {
            heap.add(new Entry(nextRow, top.runIndex));
        }

        return result;
    }

    @Override
    public void close() throws IOException {
        for (SortedRun run : runs) {
            run.close();
        }
    }
}
