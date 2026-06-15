package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.common.MemoryTracker;
import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.spill.PartitionFile;
import io.github.xinfra.lab.xdb.executor.spill.RowSerializer;
import io.github.xinfra.lab.xdb.executor.spill.TempFileManager;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.planner.logical.JoinType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hash join executor with partition-based spill-to-disk support.
 * <p>
 * Build rows are partitioned by hash(joinKey). When memory limit is exceeded,
 * the largest in-memory partition is spilled to disk. Probe rows for spilled
 * partitions are buffered to disk and processed after the in-memory probe phase.
 */
public class HashJoinExecutor implements Executor {

    private static final Logger log = LoggerFactory.getLogger(HashJoinExecutor.class);
    private static final int NUM_PARTITIONS = 64;

    private final Executor buildSide;
    private final Executor probeSide;
    private final JoinType joinType;
    private final Expression condition;
    private final List<Expression> buildKeys;
    private final List<Expression> probeKeys;
    private final List<ColumnInfo> outputColumns;
    private final EvalContext evalCtx;
    private final MemoryTracker tracker;

    private final int buildColCount;
    private final int probeColCount;
    private final List<ColumnInfo> buildSchema;
    private final List<ColumnInfo> probeSchema;

    // Partition state
    private JoinPartition[] partitions;
    private boolean hasSpilledPartitions;
    private TempFileManager tempFileManager;
    private final RowSerializer rowSerializer = new RowSerializer();
    private long trackedMemory;

    // Probe state (in-memory partitions)
    private Row currentProbeRow;
    private List<Row> currentMatches;
    private int matchIndex;
    private int currentProbePartition;
    private boolean probeExhausted;

    // LEFT join: unmatched build rows from in-memory partitions
    private boolean emittingUnmatched;
    private int unmatchedPartIdx;
    private int unmatchedRowIdx;

    // Spilled partition processing
    private int nextSpilledPartIdx;
    private List<Row> spilledResults;
    private int spilledResultIdx;

    public HashJoinExecutor(Executor buildSide, Executor probeSide,
                            JoinType joinType, Expression condition,
                            List<Expression> buildKeys, List<Expression> probeKeys,
                            List<ColumnInfo> outputColumns, EvalContext evalCtx) {
        this(buildSide, probeSide, joinType, condition, buildKeys, probeKeys,
                outputColumns, evalCtx, MemoryTracker.noopTracker());
    }

    public HashJoinExecutor(Executor buildSide, Executor probeSide,
                            JoinType joinType, Expression condition,
                            List<Expression> buildKeys, List<Expression> probeKeys,
                            List<ColumnInfo> outputColumns, EvalContext evalCtx,
                            MemoryTracker tracker) {
        this.buildSide = buildSide;
        this.probeSide = probeSide;
        this.joinType = joinType;
        this.condition = condition;
        this.buildKeys = buildKeys;
        this.probeKeys = probeKeys;
        this.outputColumns = outputColumns;
        this.evalCtx = evalCtx;
        this.tracker = tracker;
        this.buildSchema = buildSide.outputSchema();
        this.probeSchema = probeSide.outputSchema();
        this.buildColCount = buildSchema.size();
        this.probeColCount = probeSchema.size();
    }

    @Override
    public void open() throws Exception {
        buildSide.open();
        probeSide.open();

        partitions = new JoinPartition[NUM_PARTITIONS];
        for (int i = 0; i < NUM_PARTITIONS; i++) {
            partitions[i] = new JoinPartition();
        }

        AtomicBoolean needSpill = new AtomicBoolean(false);
        tracker.setActionOnExceed(new MemoryTracker.SpillDiskAction(() -> needSpill.set(true)));

        // Build phase
        Row buildRow;
        while ((buildRow = buildSide.next()) != null) {
            int partId = computePartition(buildRow, buildKeys);
            JoinPartition p = partitions[partId];

            if (p.spilled) {
                p.buildFile.appendRow(buildRow);
            } else {
                long rowMem = buildRow.estimateMemoryBytes();
                p.buildRows.add(buildRow);
                List<Datum> key = computeKey(buildRow, buildKeys);
                p.hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(buildRow);
                trackedMemory += rowMem;
                tracker.consume(rowMem);

                if (needSpill.compareAndSet(true, false)) {
                    spillLargestPartition();
                }
            }
        }

        currentMatches = null;
        matchIndex = 0;
        probeExhausted = false;
        emittingUnmatched = false;
        nextSpilledPartIdx = 0;
    }

    @Override
    public Row next() throws Exception {
        while (true) {
            // Phase 1: emit matches from current probe row
            if (currentMatches != null && matchIndex < currentMatches.size()) {
                Row buildRow = currentMatches.get(matchIndex++);
                Row joined = joinRows(buildRow, currentProbeRow);

                if (condition != null && !condition.eval(evalCtx, joined).toBoolean()) {
                    continue;
                }
                if (joinType == JoinType.LEFT) {
                    partitions[currentProbePartition].matchedRows.add(buildRow);
                }
                return joined;
            }

            // Phase 2: get next probe row
            if (!probeExhausted) {
                currentProbeRow = probeSide.next();
                if (currentProbeRow == null) {
                    probeExhausted = true;
                    finalizeProbeFiles();
                } else {
                    int partId = computePartition(currentProbeRow, probeKeys);
                    JoinPartition p = partitions[partId];

                    if (p.spilled) {
                        p.probeFile.appendRow(currentProbeRow);
                        currentMatches = null;
                        continue;
                    }

                    List<Datum> probeKey = computeKey(currentProbeRow, probeKeys);
                    currentMatches = p.hashTable.get(probeKey);
                    matchIndex = 0;
                    currentProbePartition = partId;

                    if ((currentMatches == null || currentMatches.isEmpty())
                            && joinType == JoinType.RIGHT) {
                        return joinRows(new Row(buildColCount), currentProbeRow);
                    }
                    continue;
                }
            }

            // Phase 3: unmatched build rows from in-memory partitions (LEFT join)
            if (joinType == JoinType.LEFT && !emittingUnmatched) {
                emittingUnmatched = true;
                unmatchedPartIdx = 0;
                unmatchedRowIdx = 0;
            }
            if (emittingUnmatched) {
                Row unmatched = nextUnmatchedBuildRow();
                if (unmatched != null) return unmatched;
                emittingUnmatched = false;
            }

            // Phase 4: process spilled partitions via mini hash joins
            if (hasSpilledPartitions) {
                Row spilledRow = nextFromSpilledPartition();
                if (spilledRow != null) return spilledRow;
            }

            return null;
        }
    }

    @Override
    public void close() throws Exception {
        try {
            buildSide.close();
        } finally {
            try {
                probeSide.close();
            } finally {
                if (partitions != null) {
                    for (JoinPartition p : partitions) {
                        if (p.buildFile != null) p.buildFile.close();
                        if (p.probeFile != null) p.probeFile.close();
                    }
                    partitions = null;
                }
                if (tempFileManager != null) {
                    tempFileManager.close();
                    tempFileManager = null;
                }
                if (trackedMemory > 0) {
                    tracker.release(trackedMemory);
                    trackedMemory = 0;
                }
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
    // Spill management
    // ---------------------------------------------------------------

    private void spillLargestPartition() throws IOException {
        if (tempFileManager == null) {
            tempFileManager = new TempFileManager();
        }
        hasSpilledPartitions = true;

        int largest = -1;
        int largestSize = 0;
        for (int i = 0; i < NUM_PARTITIONS; i++) {
            if (!partitions[i].spilled && partitions[i].buildRows.size() > largestSize) {
                largest = i;
                largestSize = partitions[i].buildRows.size();
            }
        }
        if (largest < 0) return;

        JoinPartition p = partitions[largest];
        p.spilled = true;
        p.buildFile = new PartitionFile(
                tempFileManager.createTempFile("build-" + largest), rowSerializer);
        p.probeFile = new PartitionFile(
                tempFileManager.createTempFile("probe-" + largest), rowSerializer);

        long freedMem = 0;
        for (Row row : p.buildRows) {
            p.buildFile.appendRow(row);
            freedMem += row.estimateMemoryBytes();
        }
        p.buildRows.clear();
        p.hashTable.clear();
        p.matchedRows.clear();
        tracker.release(freedMem);
        trackedMemory -= freedMem;

        log.debug("HashJoinExecutor: spilled partition {} ({} rows)", largest, largestSize);
    }

    private void finalizeProbeFiles() {
        if (!hasSpilledPartitions) return;
        for (JoinPartition p : partitions) {
            if (p.spilled && p.probeFile != null) {
                try {
                    p.probeFile.finishWriting();
                } catch (IOException e) {
                    log.warn("Failed to finalize probe file", e);
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Unmatched build rows (LEFT join, in-memory partitions only)
    // ---------------------------------------------------------------

    private Row nextUnmatchedBuildRow() {
        while (unmatchedPartIdx < NUM_PARTITIONS) {
            JoinPartition p = partitions[unmatchedPartIdx];
            if (!p.spilled) {
                while (unmatchedRowIdx < p.buildRows.size()) {
                    Row buildRow = p.buildRows.get(unmatchedRowIdx++);
                    if (!p.matchedRows.contains(buildRow)) {
                        return joinRows(buildRow, new Row(probeColCount));
                    }
                }
            }
            unmatchedPartIdx++;
            unmatchedRowIdx = 0;
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Spilled partition processing
    // ---------------------------------------------------------------

    private Row nextFromSpilledPartition() throws Exception {
        while (true) {
            if (spilledResults != null && spilledResultIdx < spilledResults.size()) {
                return spilledResults.get(spilledResultIdx++);
            }
            while (nextSpilledPartIdx < NUM_PARTITIONS) {
                JoinPartition p = partitions[nextSpilledPartIdx++];
                if (!p.spilled || p.buildFile == null) continue;

                spilledResults = processSpilledPartition(p);
                spilledResultIdx = 0;
                if (!spilledResults.isEmpty()) {
                    return spilledResults.get(spilledResultIdx++);
                }
            }
            return null;
        }
    }

    private List<Row> processSpilledPartition(JoinPartition p) throws IOException {
        List<Row> results = new ArrayList<>();

        p.buildFile.finishWriting();
        List<Row> buildRows = p.buildFile.readAll();
        Map<List<Datum>, List<Row>> ht = new HashMap<>();
        for (Row row : buildRows) {
            List<Datum> key = computeKey(row, buildKeys);
            ht.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        Set<Row> matched = new HashSet<>();

        p.probeFile.finishWriting();
        List<Row> probeRows = p.probeFile.readAll();
        for (Row probeRow : probeRows) {
            List<Datum> probeKey = computeKey(probeRow, probeKeys);
            List<Row> matches = ht.get(probeKey);

            if (matches != null) {
                for (Row buildRow : matches) {
                    Row joined = joinRows(buildRow, probeRow);
                    if (condition != null && !condition.eval(evalCtx, joined).toBoolean()) {
                        continue;
                    }
                    matched.add(buildRow);
                    results.add(joined);
                }
            } else if (joinType == JoinType.RIGHT) {
                results.add(joinRows(new Row(buildColCount), probeRow));
            }
        }

        if (joinType == JoinType.LEFT) {
            for (Row buildRow : buildRows) {
                if (!matched.contains(buildRow)) {
                    results.add(joinRows(buildRow, new Row(probeColCount)));
                }
            }
        }

        return results;
    }

    // ---------------------------------------------------------------
    // Key computation and row assembly
    // ---------------------------------------------------------------

    private int computePartition(Row row, List<Expression> keyExprs) {
        List<Datum> key = computeKey(row, keyExprs);
        return (key.hashCode() & 0x7FFFFFFF) % NUM_PARTITIONS;
    }

    private List<Datum> computeKey(Row row, List<Expression> keyExprs) {
        List<Datum> key = new ArrayList<>(keyExprs.size());
        for (Expression expr : keyExprs) {
            key.add(expr.eval(evalCtx, row));
        }
        return key;
    }

    private Row joinRows(Row buildRow, Row probeRow) {
        Datum[] values = new Datum[buildColCount + probeColCount];
        for (int i = 0; i < buildColCount; i++) {
            values[i] = buildRow.get(i);
        }
        for (int i = 0; i < probeColCount; i++) {
            values[buildColCount + i] = probeRow.get(i);
        }
        return new Row(values);
    }

    // ---------------------------------------------------------------
    // Inner classes
    // ---------------------------------------------------------------

    static class JoinPartition {
        boolean spilled;
        final Map<List<Datum>, List<Row>> hashTable = new HashMap<>();
        final List<Row> buildRows = new ArrayList<>();
        final Set<Row> matchedRows = new HashSet<>();
        PartitionFile buildFile;
        PartitionFile probeFile;
    }
}
