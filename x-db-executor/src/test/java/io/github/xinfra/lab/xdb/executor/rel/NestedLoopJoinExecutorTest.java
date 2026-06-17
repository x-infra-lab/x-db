package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.ListExecutor;
import io.github.xinfra.lab.xdb.expression.BinaryOp;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.planner.logical.JoinType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.xinfra.lab.xdb.executor.ListExecutor.col;
import static org.assertj.core.api.Assertions.assertThat;

class NestedLoopJoinExecutorTest {

    private final EvalContext evalCtx = new EvalContext();

    private final List<ColumnInfo> leftSchema = List.of(
            col("id", DataType.INT),
            col("name", DataType.VARCHAR)
    );

    private final List<ColumnInfo> rightSchema = List.of(
            col("user_id", DataType.INT),
            col("order", DataType.VARCHAR)
    );

    private final List<ColumnInfo> outputSchema;

    {
        List<ColumnInfo> out = new ArrayList<>(leftSchema);
        out.addAll(rightSchema);
        outputSchema = out;
    }

    private List<Row> leftRows() {
        return List.of(
                new Row(new Datum[]{Datum.of(1L), Datum.of("Alice")}),
                new Row(new Datum[]{Datum.of(2L), Datum.of("Bob")}),
                new Row(new Datum[]{Datum.of(3L), Datum.of("Charlie")})
        );
    }

    private List<Row> rightRows() {
        return List.of(
                new Row(new Datum[]{Datum.of(1L), Datum.of("order-A")}),
                new Row(new Datum[]{Datum.of(1L), Datum.of("order-B")}),
                new Row(new Datum[]{Datum.of(2L), Datum.of("order-C")}),
                new Row(new Datum[]{Datum.of(4L), Datum.of("order-D")})
        );
    }

    private Expression joinCondition() {
        // left.id = right.user_id  (col indices: 0 = left.id, 2 = right.user_id in joined row)
        return new BinaryOp(
                new ColumnRef(null, "id", 0, DataType.INT),
                BinaryOp.Op.EQ,
                new ColumnRef(null, "user_id", 2, DataType.INT)
        );
    }

    @Test
    @DisplayName("INNER JOIN returns matching rows")
    void innerJoin() throws Exception {
        NestedLoopJoinExecutor exec = new NestedLoopJoinExecutor(
                new ListExecutor(leftRows(), leftSchema),
                new ListExecutor(rightRows(), rightSchema),
                JoinType.INNER, joinCondition(), outputSchema, evalCtx);
        exec.open();

        List<Row> results = drain(exec);
        // Alice matches order-A, order-B; Bob matches order-C => 3 rows
        assertThat(results).hasSize(3);
        exec.close();
    }

    @Test
    @DisplayName("CROSS JOIN returns cartesian product")
    void crossJoin() throws Exception {
        List<Row> left = List.of(
                new Row(new Datum[]{Datum.of(1L), Datum.of("A")}),
                new Row(new Datum[]{Datum.of(2L), Datum.of("B")})
        );
        List<Row> right = List.of(
                new Row(new Datum[]{Datum.of(10L), Datum.of("X")}),
                new Row(new Datum[]{Datum.of(20L), Datum.of("Y")})
        );

        NestedLoopJoinExecutor exec = new NestedLoopJoinExecutor(
                new ListExecutor(left, leftSchema),
                new ListExecutor(right, rightSchema),
                JoinType.CROSS, null, outputSchema, evalCtx);
        exec.open();

        List<Row> results = drain(exec);
        assertThat(results).hasSize(4); // 2 x 2
        exec.close();
    }

    @Test
    @DisplayName("LEFT JOIN includes unmatched outer rows with nulls")
    void leftJoin() throws Exception {
        NestedLoopJoinExecutor exec = new NestedLoopJoinExecutor(
                new ListExecutor(leftRows(), leftSchema),
                new ListExecutor(rightRows(), rightSchema),
                JoinType.LEFT, joinCondition(), outputSchema, evalCtx);
        exec.open();

        List<Row> results = drain(exec);
        // Alice: 2 matches, Bob: 1 match, Charlie: 0 matches (1 null-padded) => 4 rows
        assertThat(results).hasSize(4);

        // Charlie's row should have null inner columns
        Row charlieRow = results.get(3);
        assertThat(charlieRow.get(1).toStringValue()).isEqualTo("Charlie");
        assertThat(charlieRow.get(2).isNull()).isTrue();
        assertThat(charlieRow.get(3).isNull()).isTrue();
        exec.close();
    }

    @Test
    @DisplayName("RIGHT JOIN includes unmatched inner rows with nulls")
    void rightJoin() throws Exception {
        NestedLoopJoinExecutor exec = new NestedLoopJoinExecutor(
                new ListExecutor(leftRows(), leftSchema),
                new ListExecutor(rightRows(), rightSchema),
                JoinType.RIGHT, joinCondition(), outputSchema, evalCtx);
        exec.open();

        List<Row> results = drain(exec);
        // 3 matched + 1 unmatched (order-D, user_id=4 has no match) => 4 rows
        assertThat(results).hasSize(4);

        // The unmatched inner row should have null outer columns
        Row unmatchedRow = results.get(3);
        assertThat(unmatchedRow.get(0).isNull()).isTrue();
        assertThat(unmatchedRow.get(1).isNull()).isTrue();
        assertThat(unmatchedRow.get(3).toStringValue()).isEqualTo("order-D");
        exec.close();
    }

    @Test
    @DisplayName("INNER JOIN with empty left returns empty")
    void innerJoinEmptyLeft() throws Exception {
        NestedLoopJoinExecutor exec = new NestedLoopJoinExecutor(
                new ListExecutor(List.of(), leftSchema),
                new ListExecutor(rightRows(), rightSchema),
                JoinType.INNER, joinCondition(), outputSchema, evalCtx);
        exec.open();
        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    @DisplayName("INNER JOIN with empty right returns empty")
    void innerJoinEmptyRight() throws Exception {
        NestedLoopJoinExecutor exec = new NestedLoopJoinExecutor(
                new ListExecutor(leftRows(), leftSchema),
                new ListExecutor(List.of(), rightSchema),
                JoinType.INNER, joinCondition(), outputSchema, evalCtx);
        exec.open();
        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    @DisplayName("outputSchema returns configured columns")
    void outputSchemaTest() {
        NestedLoopJoinExecutor exec = new NestedLoopJoinExecutor(
                new ListExecutor(List.of(), leftSchema),
                new ListExecutor(List.of(), rightSchema),
                JoinType.INNER, null, outputSchema, evalCtx);
        assertThat(exec.outputSchema()).hasSize(4);
    }

    private List<Row> drain(NestedLoopJoinExecutor exec) throws Exception {
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = exec.next()) != null) {
            rows.add(row);
        }
        return rows;
    }
}
