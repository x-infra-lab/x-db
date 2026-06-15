package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.AggFunctions;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.planner.logical.JoinType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.xinfra.lab.xdb.executor.ListExecutor.col;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutorCloseTest {

    private static class FailOnCloseExecutor implements Executor {
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final boolean failOnClose;

        FailOnCloseExecutor(boolean failOnClose) {
            this.failOnClose = failOnClose;
        }

        boolean wasClosed() { return closed.get(); }

        @Override public void open() {}
        @Override public Row next() { return null; }
        @Override
        public void close() throws Exception {
            closed.set(true);
            if (failOnClose) {
                throw new RuntimeException("close failed");
            }
        }
        @Override public List<ColumnInfo> outputSchema() {
            return List.of(col("x", DataType.INT));
        }
    }

    @Test
    @DisplayName("HashJoinExecutor closes probe side even if build side close() throws")
    void hashJoinClosesProbeEvenIfBuildCloseThrows() {
        FailOnCloseExecutor build = new FailOnCloseExecutor(true);
        FailOnCloseExecutor probe = new FailOnCloseExecutor(false);

        List<ColumnInfo> outCols = List.of(col("x", DataType.INT));
        List<Expression> buildKeys = List.of(new ColumnRef(null, "x", 0, DataType.INT));
        List<Expression> probeKeys = List.of(new ColumnRef(null, "x", 0, DataType.INT));

        HashJoinExecutor join = new HashJoinExecutor(build, probe,
                JoinType.INNER, null, buildKeys, probeKeys,
                outCols, new EvalContext());

        assertThatThrownBy(join::close).hasMessageContaining("close failed");
        assertThat(build.wasClosed()).isTrue();
        assertThat(probe.wasClosed()).isTrue();
    }

    @Test
    @DisplayName("NestedLoopJoinExecutor closes inner even if outer close() throws")
    void nestedLoopClosesInnerEvenIfOuterCloseThrows() {
        FailOnCloseExecutor outer = new FailOnCloseExecutor(true);
        FailOnCloseExecutor inner = new FailOnCloseExecutor(false);

        List<ColumnInfo> outCols = List.of(col("x", DataType.INT));

        NestedLoopJoinExecutor join = new NestedLoopJoinExecutor(outer, inner,
                JoinType.INNER, null, outCols, new EvalContext());

        assertThatThrownBy(join::close).hasMessageContaining("close failed");
        assertThat(outer.wasClosed()).isTrue();
        assertThat(inner.wasClosed()).isTrue();
    }

    @Test
    @DisplayName("SortExecutor releases resources even if child close() throws")
    void sortClosesResourcesEvenIfChildCloseThrows() {
        FailOnCloseExecutor child = new FailOnCloseExecutor(true);

        Expression orderExpr = new ColumnRef(null, "x", 0, DataType.INT);
        SortExecutor sort = new SortExecutor(child,
                List.of(orderExpr), List.of(true), new EvalContext());

        assertThatThrownBy(sort::close).hasMessageContaining("close failed");
        assertThat(child.wasClosed()).isTrue();
    }

    @Test
    @DisplayName("HashAggExecutor releases resources even if child close() throws")
    void hashAggClosesResourcesEvenIfChildCloseThrows() {
        FailOnCloseExecutor child = new FailOnCloseExecutor(true);

        Expression groupExpr = new ColumnRef(null, "x", 0, DataType.INT);
        AggFunction countAgg = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outCols = List.of(col("x", DataType.INT), col("cnt", DataType.BIGINT));

        HashAggExecutor agg = new HashAggExecutor(child,
                List.of(groupExpr), List.of(countAgg), outCols, new EvalContext());

        assertThatThrownBy(agg::close).hasMessageContaining("close failed");
        assertThat(child.wasClosed()).isTrue();
    }
}
