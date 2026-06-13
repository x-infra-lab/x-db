package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.ListExecutor;
import io.github.xinfra.lab.xdb.expression.BinaryOp;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.Constant;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.xinfra.lab.xdb.executor.ListExecutor.col;
import static org.assertj.core.api.Assertions.assertThat;

class SelectionExecutorTest {

    private final EvalContext evalCtx = new EvalContext();

    // Schema: id (INT), name (VARCHAR), age (INT)
    private final List<ColumnInfo> schema = List.of(
            col("id", DataType.INT),
            col("name", DataType.VARCHAR),
            col("age", DataType.INT)
    );

    private List<Row> sampleRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(new Datum[]{Datum.of(1L), Datum.of("Alice"), Datum.of(30L)}));
        rows.add(new Row(new Datum[]{Datum.of(2L), Datum.of("Bob"), Datum.of(25L)}));
        rows.add(new Row(new Datum[]{Datum.of(3L), Datum.of("Charlie"), Datum.of(35L)}));
        rows.add(new Row(new Datum[]{Datum.of(4L), Datum.of("Diana"), Datum.of(28L)}));
        rows.add(new Row(new Datum[]{Datum.of(5L), Datum.of("Eve"), Datum.of(30L)}));
        return rows;
    }

    @Test
    void filterWithEquals() throws Exception {
        // WHERE age = 30
        Expression condition = new BinaryOp(
                new ColumnRef(null, "age", 2, DataType.INT),
                BinaryOp.Op.EQ,
                Constant.ofLong(30)
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        SelectionExecutor selection = new SelectionExecutor(child, List.of(condition), evalCtx);
        selection.open();

        Row row1 = selection.next();
        assertThat(row1).isNotNull();
        assertThat(row1.get(0).toLong()).isEqualTo(1L); // Alice
        assertThat(row1.get(2).toLong()).isEqualTo(30L);

        Row row2 = selection.next();
        assertThat(row2).isNotNull();
        assertThat(row2.get(0).toLong()).isEqualTo(5L); // Eve
        assertThat(row2.get(2).toLong()).isEqualTo(30L);

        assertThat(selection.next()).isNull();
        selection.close();
    }

    @Test
    void filterWithGreaterThan() throws Exception {
        // WHERE age > 28
        Expression condition = new BinaryOp(
                new ColumnRef(null, "age", 2, DataType.INT),
                BinaryOp.Op.GT,
                Constant.ofLong(28)
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        SelectionExecutor selection = new SelectionExecutor(child, List.of(condition), evalCtx);
        selection.open();

        List<Long> matchedIds = new ArrayList<>();
        Row row;
        while ((row = selection.next()) != null) {
            matchedIds.add(row.get(0).toLong());
        }

        // Alice(30), Charlie(35), Eve(30)
        assertThat(matchedIds).containsExactly(1L, 3L, 5L);
        selection.close();
    }

    @Test
    void filterWithLessThanOrEqual() throws Exception {
        // WHERE age <= 28
        Expression condition = new BinaryOp(
                new ColumnRef(null, "age", 2, DataType.INT),
                BinaryOp.Op.LE,
                Constant.ofLong(28)
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        SelectionExecutor selection = new SelectionExecutor(child, List.of(condition), evalCtx);
        selection.open();

        List<Long> matchedIds = new ArrayList<>();
        Row row;
        while ((row = selection.next()) != null) {
            matchedIds.add(row.get(0).toLong());
        }

        // Bob(25), Diana(28)
        assertThat(matchedIds).containsExactly(2L, 4L);
        selection.close();
    }

    @Test
    void filterWithAnd() throws Exception {
        // WHERE age >= 28 AND age <= 30
        Expression condition = new BinaryOp(
                new BinaryOp(
                        new ColumnRef(null, "age", 2, DataType.INT),
                        BinaryOp.Op.GE,
                        Constant.ofLong(28)
                ),
                BinaryOp.Op.AND,
                new BinaryOp(
                        new ColumnRef(null, "age", 2, DataType.INT),
                        BinaryOp.Op.LE,
                        Constant.ofLong(30)
                )
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        SelectionExecutor selection = new SelectionExecutor(child, List.of(condition), evalCtx);
        selection.open();

        List<Long> matchedIds = new ArrayList<>();
        Row row;
        while ((row = selection.next()) != null) {
            matchedIds.add(row.get(0).toLong());
        }

        // Alice(30), Diana(28), Eve(30)
        assertThat(matchedIds).containsExactly(1L, 4L, 5L);
        selection.close();
    }

    @Test
    void filterWithOr() throws Exception {
        // WHERE age = 25 OR age = 35
        Expression condition = new BinaryOp(
                new BinaryOp(
                        new ColumnRef(null, "age", 2, DataType.INT),
                        BinaryOp.Op.EQ,
                        Constant.ofLong(25)
                ),
                BinaryOp.Op.OR,
                new BinaryOp(
                        new ColumnRef(null, "age", 2, DataType.INT),
                        BinaryOp.Op.EQ,
                        Constant.ofLong(35)
                )
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        SelectionExecutor selection = new SelectionExecutor(child, List.of(condition), evalCtx);
        selection.open();

        List<Long> matchedIds = new ArrayList<>();
        Row row;
        while ((row = selection.next()) != null) {
            matchedIds.add(row.get(0).toLong());
        }

        // Bob(25), Charlie(35)
        assertThat(matchedIds).containsExactly(2L, 3L);
        selection.close();
    }

    @Test
    void filterWithMultipleConditions() throws Exception {
        // WHERE id > 1 AND age < 35 (both must pass - conditions list acts as AND)
        Expression cond1 = new BinaryOp(
                new ColumnRef(null, "id", 0, DataType.INT),
                BinaryOp.Op.GT,
                Constant.ofLong(1)
        );
        Expression cond2 = new BinaryOp(
                new ColumnRef(null, "age", 2, DataType.INT),
                BinaryOp.Op.LT,
                Constant.ofLong(35)
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        SelectionExecutor selection = new SelectionExecutor(child, List.of(cond1, cond2), evalCtx);
        selection.open();

        List<Long> matchedIds = new ArrayList<>();
        Row row;
        while ((row = selection.next()) != null) {
            matchedIds.add(row.get(0).toLong());
        }

        // Bob(25), Diana(28), Eve(30) - all have id > 1 and age < 35
        assertThat(matchedIds).containsExactly(2L, 4L, 5L);
        selection.close();
    }

    @Test
    void filterWithNoMatchingRows() throws Exception {
        // WHERE age > 100
        Expression condition = new BinaryOp(
                new ColumnRef(null, "age", 2, DataType.INT),
                BinaryOp.Op.GT,
                Constant.ofLong(100)
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        SelectionExecutor selection = new SelectionExecutor(child, List.of(condition), evalCtx);
        selection.open();

        assertThat(selection.next()).isNull();
        selection.close();
    }

    @Test
    void filterWithEmptyInput() throws Exception {
        Expression condition = new BinaryOp(
                new ColumnRef(null, "age", 2, DataType.INT),
                BinaryOp.Op.EQ,
                Constant.ofLong(30)
        );

        ListExecutor child = new ListExecutor(List.of(), schema);
        SelectionExecutor selection = new SelectionExecutor(child, List.of(condition), evalCtx);
        selection.open();

        assertThat(selection.next()).isNull();
        selection.close();
    }

    @Test
    void filterWithStringComparison() throws Exception {
        // WHERE name = 'Bob'
        Expression condition = new BinaryOp(
                new ColumnRef(null, "name", 1, DataType.VARCHAR),
                BinaryOp.Op.EQ,
                Constant.ofString("Bob")
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        SelectionExecutor selection = new SelectionExecutor(child, List.of(condition), evalCtx);
        selection.open();

        Row row = selection.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(2L);
        assertThat(((Datum.StringDatum) row.get(1)).value()).isEqualTo("Bob");

        assertThat(selection.next()).isNull();
        selection.close();
    }

    @Test
    void outputSchemaMatchesChild() {
        ListExecutor child = new ListExecutor(List.of(), schema);
        SelectionExecutor selection = new SelectionExecutor(child, List.of(), evalCtx);

        assertThat(selection.outputSchema()).isEqualTo(schema);
    }
}
