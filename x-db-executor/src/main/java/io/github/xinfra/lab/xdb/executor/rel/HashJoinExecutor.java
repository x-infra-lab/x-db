package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.planner.logical.JoinType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hash join executor.
 * Build phase: reads all rows from build side into a hash map.
 * Probe phase: for each row from probe side, probes the hash map.
 */
public class HashJoinExecutor implements Executor {

    private final Executor buildSide;
    private final Executor probeSide;
    private final JoinType joinType;
    private final Expression condition;
    private final List<Expression> buildKeys;
    private final List<Expression> probeKeys;
    private final List<ColumnInfo> outputColumns;
    private final EvalContext evalCtx;

    private final int buildColCount;
    private final int probeColCount;

    // Build hash table: key -> list of build rows
    private Map<String, List<Row>> hashTable;
    // Track which build rows have been matched (for LEFT join)
    private Set<Integer> matchedBuildIndices;
    private List<Row> allBuildRows;

    // Probe state
    private Row currentProbeRow;
    private List<Row> currentMatches;
    private int matchIndex;
    private boolean probeExhausted;

    // For emitting unmatched build rows (LEFT join)
    private boolean emittingUnmatched;
    private int unmatchedIndex;

    public HashJoinExecutor(Executor buildSide, Executor probeSide,
                            JoinType joinType, Expression condition,
                            List<Expression> buildKeys, List<Expression> probeKeys,
                            List<ColumnInfo> outputColumns, EvalContext evalCtx) {
        this.buildSide = buildSide;
        this.probeSide = probeSide;
        this.joinType = joinType;
        this.condition = condition;
        this.buildKeys = buildKeys;
        this.probeKeys = probeKeys;
        this.outputColumns = outputColumns;
        this.evalCtx = evalCtx;
        this.buildColCount = buildSide.outputSchema().size();
        this.probeColCount = probeSide.outputSchema().size();
    }

    @Override
    public void open() throws Exception {
        buildSide.open();
        probeSide.open();

        // Build phase: read all build rows into hash table
        hashTable = new HashMap<>();
        allBuildRows = new ArrayList<>();
        matchedBuildIndices = new HashSet<>();

        Row buildRow;
        int index = 0;
        while ((buildRow = buildSide.next()) != null) {
            allBuildRows.add(buildRow);
            String key = computeKey(buildRow, buildKeys);
            hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(buildRow);
            index++;
        }

        currentMatches = null;
        matchIndex = 0;
        probeExhausted = false;
        emittingUnmatched = false;
        unmatchedIndex = 0;
    }

    @Override
    public Row next() throws Exception {
        while (true) {
            // If we're emitting unmatched build rows (for LEFT join)
            if (emittingUnmatched) {
                return nextUnmatchedBuildRow();
            }

            // If we have pending matches for the current probe row
            if (currentMatches != null && matchIndex < currentMatches.size()) {
                Row buildRow = currentMatches.get(matchIndex++);
                Row joined = joinRows(buildRow, currentProbeRow);

                // Check additional condition
                if (condition != null) {
                    Datum result = condition.eval(evalCtx, joined);
                    if (!result.toBoolean()) {
                        continue;
                    }
                }

                // Track matched build row
                int buildIdx = allBuildRows.indexOf(buildRow);
                if (buildIdx >= 0) {
                    matchedBuildIndices.add(buildIdx);
                }

                return joined;
            }

            // Get next probe row
            if (probeExhausted) {
                // For LEFT join, emit unmatched build rows
                if (joinType == JoinType.LEFT) {
                    emittingUnmatched = true;
                    return nextUnmatchedBuildRow();
                }
                return null;
            }

            currentProbeRow = probeSide.next();
            if (currentProbeRow == null) {
                probeExhausted = true;
                if (joinType == JoinType.LEFT) {
                    emittingUnmatched = true;
                    return nextUnmatchedBuildRow();
                }
                return null;
            }

            // Probe the hash table
            String probeKey = computeKey(currentProbeRow, probeKeys);
            currentMatches = hashTable.get(probeKey);
            matchIndex = 0;

            // For RIGHT join, if no match, emit probe row with null build columns
            if ((currentMatches == null || currentMatches.isEmpty())
                    && joinType == JoinType.RIGHT) {
                Row nullBuild = new Row(buildColCount);
                return joinRows(nullBuild, currentProbeRow);
            }
        }
    }

    @Override
    public void close() throws Exception {
        buildSide.close();
        probeSide.close();
        hashTable = null;
        allBuildRows = null;
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }

    private Row nextUnmatchedBuildRow() {
        while (unmatchedIndex < allBuildRows.size()) {
            int idx = unmatchedIndex++;
            if (!matchedBuildIndices.contains(idx)) {
                Row buildRow = allBuildRows.get(idx);
                Row nullProbe = new Row(probeColCount);
                return joinRows(buildRow, nullProbe);
            }
        }
        return null;
    }

    private String computeKey(Row row, List<Expression> keyExprs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyExprs.size(); i++) {
            if (i > 0) sb.append('\0');
            Datum value = keyExprs.get(i).eval(evalCtx, row);
            sb.append(value.toStringValue());
        }
        return sb.toString();
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
}
