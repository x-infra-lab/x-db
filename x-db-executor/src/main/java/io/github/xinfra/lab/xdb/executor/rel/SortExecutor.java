package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.common.MemoryTracker;
import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.spill.MergeSortIterator;
import io.github.xinfra.lab.xdb.executor.spill.RowSerializer;
import io.github.xinfra.lab.xdb.executor.spill.SortedRun;
import io.github.xinfra.lab.xdb.executor.spill.TempFileManager;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.DatumComparator;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sorts rows from the child executor. Supports in-memory sort for small datasets
 * and external merge sort (spill-to-disk) when memory limit is exceeded.
 */
public class SortExecutor implements Executor {

    private static final Logger log = LoggerFactory.getLogger(SortExecutor.class);

    private final Executor child;
    private final List<Expression> orderByExprs;
    private final List<Boolean> ascending;
    private final EvalContext evalCtx;
    private final MemoryTracker tracker;

    private List<Row> sortedRows;
    private int currentIndex;

    private boolean spilled;
    private TempFileManager tempFileManager;
    private MergeSortIterator mergeIterator;
    private final RowSerializer rowSerializer = new RowSerializer();
    private long trackedMemory;

    public SortExecutor(Executor child, List<Expression> orderByExprs,
                        List<Boolean> ascending, EvalContext evalCtx) {
        this(child, orderByExprs, ascending, evalCtx, MemoryTracker.noopTracker());
    }

    public SortExecutor(Executor child, List<Expression> orderByExprs,
                        List<Boolean> ascending, EvalContext evalCtx,
                        MemoryTracker tracker) {
        this.child = child;
        this.orderByExprs = orderByExprs;
        this.ascending = ascending;
        this.evalCtx = evalCtx;
        this.tracker = tracker;
    }

    @Override
    public void open() throws Exception {
        child.open();

        Comparator<Row> comparator = buildComparator();
        List<Row> buffer = new ArrayList<>();
        List<SortedRun> runs = new ArrayList<>();
        long bufferMemory = 0;

        AtomicBoolean needSpill = new AtomicBoolean(false);
        tracker.setActionOnExceed(new MemoryTracker.SpillDiskAction(() -> needSpill.set(true)));

        Row row;
        while ((row = child.next()) != null) {
            long rowMem = row.estimateMemoryBytes();
            buffer.add(row);
            bufferMemory += rowMem;
            trackedMemory += rowMem;
            tracker.consume(rowMem);

            if (needSpill.compareAndSet(true, false)) {
                if (!spilled) {
                    spilled = true;
                    tempFileManager = new TempFileManager();
                    log.debug("SortExecutor: spilling to disk, buffer size={}", buffer.size());
                }
                buffer.sort(comparator);
                runs.add(SortedRun.write(buffer, rowSerializer, tempFileManager));
                tracker.release(bufferMemory);
                trackedMemory -= bufferMemory;
                buffer.clear();
                bufferMemory = 0;
            }
        }

        if (!spilled) {
            sortedRows = buffer;
            sortedRows.sort(comparator);
            currentIndex = 0;
        } else {
            if (!buffer.isEmpty()) {
                buffer.sort(comparator);
                runs.add(SortedRun.write(buffer, rowSerializer, tempFileManager));
                tracker.release(bufferMemory);
                trackedMemory -= bufferMemory;
                buffer.clear();
            }
            for (SortedRun run : runs) {
                run.openForRead();
            }
            mergeIterator = new MergeSortIterator(runs, comparator);
            log.debug("SortExecutor: merge sort with {} runs", runs.size());
        }
    }

    @Override
    public Row next() throws Exception {
        if (spilled) {
            return mergeIterator.next();
        }
        if (currentIndex >= sortedRows.size()) {
            return null;
        }
        return sortedRows.get(currentIndex++);
    }

    @Override
    public void close() throws Exception {
        try {
            child.close();
        } finally {
            if (mergeIterator != null) {
                mergeIterator.close();
                mergeIterator = null;
            }
            if (tempFileManager != null) {
                tempFileManager.close();
                tempFileManager = null;
            }
            if (sortedRows != null) {
                long totalMem = sortedRows.stream().mapToLong(Row::estimateMemoryBytes).sum();
                tracker.release(totalMem);
                sortedRows = null;
            }
            if (trackedMemory > 0) {
                tracker.release(trackedMemory);
                trackedMemory = 0;
            }
        }
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return child.outputSchema();
    }

    public MemoryTracker tracker() {
        return tracker;
    }

    private Comparator<Row> buildComparator() {
        return (r1, r2) -> {
            for (int i = 0; i < orderByExprs.size(); i++) {
                Datum v1 = orderByExprs.get(i).eval(evalCtx, r1);
                Datum v2 = orderByExprs.get(i).eval(evalCtx, r2);
                int cmp = DatumComparator.compare(v1, v2);
                if (cmp != 0) {
                    return ascending.get(i) ? cmp : -cmp;
                }
            }
            return 0;
        };
    }
}
