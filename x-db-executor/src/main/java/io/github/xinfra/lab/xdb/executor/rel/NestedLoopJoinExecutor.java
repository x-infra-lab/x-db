package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.planner.logical.JoinType;

import java.util.ArrayList;
import java.util.List;

/**
 * Nested loop join: for each outer row, scan all inner rows.
 */
public class NestedLoopJoinExecutor implements Executor {

    private final Executor outer;
    private final Executor inner;
    private final JoinType joinType;
    private final Expression condition;
    private final List<ColumnInfo> outputColumns;
    private final EvalContext evalCtx;

    private final int outerColCount;
    private final int innerColCount;

    private List<Row> innerRows;
    private Row currentOuterRow;
    private int innerIndex;
    private boolean outerExhausted;
    private boolean outerMatched;

    // For RIGHT join: track which inner rows were matched
    private boolean[] innerMatched;
    private boolean emittingUnmatchedInner;
    private int unmatchedInnerIndex;

    public NestedLoopJoinExecutor(Executor outer, Executor inner,
                                  JoinType joinType, Expression condition,
                                  List<ColumnInfo> outputColumns, EvalContext evalCtx) {
        this.outer = outer;
        this.inner = inner;
        this.joinType = joinType;
        this.condition = condition;
        this.outputColumns = outputColumns;
        this.evalCtx = evalCtx;
        this.outerColCount = outer.outputSchema().size();
        this.innerColCount = inner.outputSchema().size();
    }

    @Override
    public void open() throws Exception {
        outer.open();
        inner.open();

        // Buffer all inner rows
        innerRows = new ArrayList<>();
        Row row;
        while ((row = inner.next()) != null) {
            innerRows.add(row);
        }

        if (joinType == JoinType.RIGHT) {
            innerMatched = new boolean[innerRows.size()];
        }

        currentOuterRow = null;
        innerIndex = 0;
        outerExhausted = false;
        outerMatched = false;
        emittingUnmatchedInner = false;
        unmatchedInnerIndex = 0;
    }

    @Override
    public Row next() throws Exception {
        while (true) {
            // Emit unmatched inner rows for RIGHT join
            if (emittingUnmatchedInner) {
                return nextUnmatchedInnerRow();
            }

            // Advance to next outer row if needed
            if (currentOuterRow == null || innerIndex >= innerRows.size()) {
                // Before moving to next outer row, emit unmatched outer row for LEFT join
                if (currentOuterRow != null && !outerMatched
                        && joinType == JoinType.LEFT) {
                    Row nullInner = new Row(innerColCount);
                    Row result = joinRows(currentOuterRow, nullInner);
                    currentOuterRow = null;
                    return result;
                }

                currentOuterRow = outer.next();
                if (currentOuterRow == null) {
                    outerExhausted = true;
                    if (joinType == JoinType.RIGHT) {
                        emittingUnmatchedInner = true;
                        return nextUnmatchedInnerRow();
                    }
                    return null;
                }
                innerIndex = 0;
                outerMatched = false;
            }

            // Try matching with inner rows
            while (innerIndex < innerRows.size()) {
                Row innerRow = innerRows.get(innerIndex);
                innerIndex++;

                Row joined = joinRows(currentOuterRow, innerRow);

                // Check join condition
                if (condition != null) {
                    Datum result = condition.eval(evalCtx, joined);
                    if (!result.toBoolean()) {
                        continue;
                    }
                }

                outerMatched = true;
                if (innerMatched != null) {
                    innerMatched[innerIndex - 1] = true;
                }
                return joined;
            }

            // All inner rows processed for this outer row, loop will advance outer
        }
    }

    @Override
    public void close() throws Exception {
        try {
            outer.close();
        } finally {
            try {
                inner.close();
            } finally {
                innerRows = null;
            }
        }
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }

    private Row nextUnmatchedInnerRow() {
        while (unmatchedInnerIndex < innerRows.size()) {
            int idx = unmatchedInnerIndex++;
            if (!innerMatched[idx]) {
                Row nullOuter = new Row(outerColCount);
                return joinRows(nullOuter, innerRows.get(idx));
            }
        }
        return null;
    }

    private Row joinRows(Row outerRow, Row innerRow) {
        Datum[] values = new Datum[outerColCount + innerColCount];
        for (int i = 0; i < outerColCount; i++) {
            values[i] = outerRow.get(i);
        }
        for (int i = 0; i < innerColCount; i++) {
            values[outerColCount + i] = innerRow.get(i);
        }
        return new Row(values);
    }
}
