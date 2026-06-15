package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.common.MemoryTracker;
import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.spill.PartitionFile;
import io.github.xinfra.lab.xdb.executor.spill.RowSerializer;
import io.github.xinfra.lab.xdb.executor.spill.TempFileManager;
import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hash-based aggregation executor with spill-to-disk support.
 * <p>
 * Aggregates in memory until memory limit is exceeded. On spill, remaining
 * input rows are written to partitioned files on disk. After input is exhausted,
 * spilled partitions are re-read and aggregated one at a time.
 */
public class HashAggExecutor implements Executor {

    private static final Logger log = LoggerFactory.getLogger(HashAggExecutor.class);
    private static final long GROUP_ENTRY_OVERHEAD = 128;
    private static final int NUM_PARTITIONS = 64;

    private final Executor child;
    private final List<Expression> groupByExprs;
    private final List<AggFunction> aggFunctions;
    private final List<ColumnInfo> outputColumns;
    private final EvalContext evalCtx;
    private final MemoryTracker tracker;

    // group key -> (group key values, aggregate function instances)
    private Map<List<Datum>, GroupEntry> groups;
    private Iterator<GroupEntry> resultIterator;

    // Spill state
    private boolean spillMode;
    private TempFileManager tempFileManager;
    private PartitionFile[] spillFiles;
    private final RowSerializer rowSerializer = new RowSerializer();

    public HashAggExecutor(Executor child, List<Expression> groupByExprs,
                           List<AggFunction> aggFunctions,
                           List<ColumnInfo> outputColumns, EvalContext evalCtx) {
        this(child, groupByExprs, aggFunctions, outputColumns, evalCtx,
                MemoryTracker.noopTracker());
    }

    public HashAggExecutor(Executor child, List<Expression> groupByExprs,
                           List<AggFunction> aggFunctions,
                           List<ColumnInfo> outputColumns, EvalContext evalCtx,
                           MemoryTracker tracker) {
        this.child = child;
        this.groupByExprs = groupByExprs;
        this.aggFunctions = aggFunctions;
        this.outputColumns = outputColumns;
        this.evalCtx = evalCtx;
        this.tracker = tracker;
    }

    @Override
    public void open() throws Exception {
        child.open();
        groups = new LinkedHashMap<>();

        AtomicBoolean needSpill = new AtomicBoolean(false);
        tracker.setActionOnExceed(new MemoryTracker.SpillDiskAction(() -> needSpill.set(true)));

        Row row;
        while ((row = child.next()) != null) {
            if (spillMode) {
                spillRow(row);
            } else {
                aggregateRow(row);
                if (needSpill.compareAndSet(true, false)) {
                    enterSpillMode();
                }
            }
        }

        // Re-aggregate spilled partitions
        if (spillMode) {
            processSpilledPartitions();
        }

        // Scalar aggregation on empty input
        if (groups.isEmpty() && groupByExprs.isEmpty()) {
            List<AggFunction> emptyAggs = new ArrayList<>(aggFunctions.size());
            for (AggFunction aggFunc : aggFunctions) {
                emptyAggs.add(aggFunc.newInstance());
            }
            groups.put(List.of(), new GroupEntry(new Datum[0], emptyAggs));
        }

        resultIterator = groups.values().iterator();
    }

    @Override
    public Row next() throws Exception {
        if (!resultIterator.hasNext()) {
            return null;
        }

        GroupEntry entry = resultIterator.next();
        int groupCount = groupByExprs.size();
        int aggCount = entry.aggFunctions.size();
        Datum[] values = new Datum[groupCount + aggCount];

        for (int i = 0; i < groupCount; i++) {
            values[i] = entry.groupKeyValues[i];
        }
        for (int i = 0; i < aggCount; i++) {
            values[groupCount + i] = entry.aggFunctions.get(i).result();
        }

        return new Row(values);
    }

    @Override
    public void close() throws Exception {
        try {
            child.close();
        } finally {
            if (groups != null) {
                tracker.release(groups.size() * GROUP_ENTRY_OVERHEAD);
                groups = null;
            }
            if (spillFiles != null) {
                for (PartitionFile f : spillFiles) {
                    if (f != null) f.close();
                }
                spillFiles = null;
            }
            if (tempFileManager != null) {
                tempFileManager.close();
                tempFileManager = null;
            }
        }
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }

    public MemoryTracker tracker() {
        return tracker;
    }

    // ---------------------------------------------------------------
    // Aggregation
    // ---------------------------------------------------------------

    private void aggregateRow(Row row) {
        List<Datum> groupKey = computeGroupKey(row);
        Datum[] groupKeyValues = evaluateGroupByExprs(row);

        GroupEntry entry = groups.get(groupKey);
        if (entry == null) {
            List<AggFunction> groupAggs = new ArrayList<>(aggFunctions.size());
            for (AggFunction aggFunc : aggFunctions) {
                groupAggs.add(aggFunc.newInstance());
            }
            entry = new GroupEntry(groupKeyValues, groupAggs);
            groups.put(groupKey, entry);
            tracker.consume(GROUP_ENTRY_OVERHEAD);
        }

        for (int i = 0; i < entry.aggFunctions.size(); i++) {
            AggFunction agg = entry.aggFunctions.get(i);
            Expression arg = agg.arg();
            Datum value = arg != null ? arg.eval(evalCtx, row) : Datum.of(1L);
            agg.update(value);
        }
    }

    // ---------------------------------------------------------------
    // Spill management
    // ---------------------------------------------------------------

    private void enterSpillMode() throws IOException {
        spillMode = true;
        tempFileManager = new TempFileManager();
        spillFiles = new PartitionFile[NUM_PARTITIONS];
        log.debug("HashAggExecutor: entering spill mode, {} groups in memory", groups.size());
    }

    private void spillRow(Row row) throws IOException {
        int partId = computePartition(row);
        if (spillFiles[partId] == null) {
            spillFiles[partId] = new PartitionFile(
                    tempFileManager.createTempFile("agg-" + partId), rowSerializer);
        }
        spillFiles[partId].appendRow(row);
    }

    private void processSpilledPartitions() throws IOException {
        tracker.setActionOnExceed(new MemoryTracker.LogOnlyAction());
        long totalSpilled = 0;
        for (PartitionFile file : spillFiles) {
            if (file == null || file.rowCount() == 0) continue;
            totalSpilled += file.rowCount();
            List<Row> rows = file.readAll();
            for (Row row : rows) {
                aggregateRow(row);
            }
        }
        log.debug("HashAggExecutor: re-aggregated {} spilled rows", totalSpilled);
    }

    // ---------------------------------------------------------------
    // Key computation
    // ---------------------------------------------------------------

    private int computePartition(Row row) {
        List<Datum> key = computeGroupKey(row);
        return (key.hashCode() & 0x7FFFFFFF) % NUM_PARTITIONS;
    }

    private List<Datum> computeGroupKey(Row row) {
        if (groupByExprs.isEmpty()) {
            return List.of();
        }
        List<Datum> key = new ArrayList<>(groupByExprs.size());
        for (Expression expr : groupByExprs) {
            key.add(expr.eval(evalCtx, row));
        }
        return key;
    }

    private Datum[] evaluateGroupByExprs(Row row) {
        Datum[] values = new Datum[groupByExprs.size()];
        for (int i = 0; i < groupByExprs.size(); i++) {
            values[i] = groupByExprs.get(i).eval(evalCtx, row);
        }
        return values;
    }

    // ---------------------------------------------------------------
    // Inner classes
    // ---------------------------------------------------------------

    static class GroupEntry {
        final Datum[] groupKeyValues;
        final List<AggFunction> aggFunctions;

        GroupEntry(Datum[] groupKeyValues, List<AggFunction> aggFunctions) {
            this.groupKeyValues = groupKeyValues;
            this.aggFunctions = aggFunctions;
        }
    }
}
