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

class ProjectionExecutorTest {

    private final EvalContext evalCtx = new EvalContext();

    // Input schema: id (INT), name (VARCHAR), age (INT), salary (DOUBLE)
    private final List<ColumnInfo> inputSchema = List.of(
            col("id", DataType.INT),
            col("name", DataType.VARCHAR),
            col("age", DataType.INT),
            col("salary", DataType.DOUBLE)
    );

    private List<Row> sampleRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(new Datum[]{Datum.of(1L), Datum.of("Alice"), Datum.of(30L), Datum.of(50000.0)}));
        rows.add(new Row(new Datum[]{Datum.of(2L), Datum.of("Bob"), Datum.of(25L), Datum.of(45000.0)}));
        rows.add(new Row(new Datum[]{Datum.of(3L), Datum.of("Charlie"), Datum.of(35L), Datum.of(60000.0)}));
        return rows;
    }

    @Test
    void projectSubsetOfColumns() throws Exception {
        // SELECT name, age
        List<Expression> exprs = List.of(
                new ColumnRef(null, "name", 1, DataType.VARCHAR),
                new ColumnRef(null, "age", 2, DataType.INT)
        );
        List<ColumnInfo> outputCols = List.of(
                col("name", DataType.VARCHAR),
                col("age", DataType.INT)
        );

        ListExecutor child = new ListExecutor(sampleRows(), inputSchema);
        ProjectionExecutor projection = new ProjectionExecutor(child, exprs, outputCols, evalCtx);
        projection.open();

        Row row1 = projection.next();
        assertThat(row1).isNotNull();
        assertThat(row1.size()).isEqualTo(2);
        assertThat(((Datum.StringDatum) row1.get(0)).value()).isEqualTo("Alice");
        assertThat(row1.get(1).toLong()).isEqualTo(30L);

        Row row2 = projection.next();
        assertThat(row2).isNotNull();
        assertThat(((Datum.StringDatum) row2.get(0)).value()).isEqualTo("Bob");
        assertThat(row2.get(1).toLong()).isEqualTo(25L);

        Row row3 = projection.next();
        assertThat(row3).isNotNull();
        assertThat(((Datum.StringDatum) row3.get(0)).value()).isEqualTo("Charlie");
        assertThat(row3.get(1).toLong()).isEqualTo(35L);

        assertThat(projection.next()).isNull();
        projection.close();
    }

    @Test
    void projectSingleColumn() throws Exception {
        // SELECT id
        List<Expression> exprs = List.of(
                new ColumnRef(null, "id", 0, DataType.INT)
        );
        List<ColumnInfo> outputCols = List.of(col("id", DataType.INT));

        ListExecutor child = new ListExecutor(sampleRows(), inputSchema);
        ProjectionExecutor projection = new ProjectionExecutor(child, exprs, outputCols, evalCtx);
        projection.open();

        List<Long> ids = new ArrayList<>();
        Row row;
        while ((row = projection.next()) != null) {
            assertThat(row.size()).isEqualTo(1);
            ids.add(row.get(0).toLong());
        }

        assertThat(ids).containsExactly(1L, 2L, 3L);
        projection.close();
    }

    @Test
    void projectComputedExpression() throws Exception {
        // SELECT name, salary * 1.1 AS new_salary
        Expression salaryWithRaise = new BinaryOp(
                new ColumnRef(null, "salary", 3, DataType.DOUBLE),
                BinaryOp.Op.MUL,
                Constant.ofDouble(1.1)
        );
        List<Expression> exprs = List.of(
                new ColumnRef(null, "name", 1, DataType.VARCHAR),
                salaryWithRaise
        );
        List<ColumnInfo> outputCols = List.of(
                col("name", DataType.VARCHAR),
                col("new_salary", DataType.DOUBLE)
        );

        ListExecutor child = new ListExecutor(sampleRows(), inputSchema);
        ProjectionExecutor projection = new ProjectionExecutor(child, exprs, outputCols, evalCtx);
        projection.open();

        Row row1 = projection.next();
        assertThat(row1).isNotNull();
        assertThat(((Datum.StringDatum) row1.get(0)).value()).isEqualTo("Alice");
        assertThat(row1.get(1).toDouble()).isCloseTo(55000.0, org.assertj.core.data.Offset.offset(0.01));

        Row row2 = projection.next();
        assertThat(row2).isNotNull();
        assertThat(((Datum.StringDatum) row2.get(0)).value()).isEqualTo("Bob");
        assertThat(row2.get(1).toDouble()).isCloseTo(49500.0, org.assertj.core.data.Offset.offset(0.01));

        Row row3 = projection.next();
        assertThat(row3).isNotNull();
        assertThat(((Datum.StringDatum) row3.get(0)).value()).isEqualTo("Charlie");
        assertThat(row3.get(1).toDouble()).isCloseTo(66000.0, org.assertj.core.data.Offset.offset(0.01));

        assertThat(projection.next()).isNull();
        projection.close();
    }

    @Test
    void projectArithmeticExpression() throws Exception {
        // SELECT id, age + 10 AS age_plus_10
        Expression agePlus10 = new BinaryOp(
                new ColumnRef(null, "age", 2, DataType.INT),
                BinaryOp.Op.ADD,
                Constant.ofLong(10)
        );
        List<Expression> exprs = List.of(
                new ColumnRef(null, "id", 0, DataType.INT),
                agePlus10
        );
        List<ColumnInfo> outputCols = List.of(
                col("id", DataType.INT),
                col("age_plus_10", DataType.BIGINT)
        );

        ListExecutor child = new ListExecutor(sampleRows(), inputSchema);
        ProjectionExecutor projection = new ProjectionExecutor(child, exprs, outputCols, evalCtx);
        projection.open();

        Row row1 = projection.next();
        assertThat(row1.get(0).toLong()).isEqualTo(1L);
        assertThat(row1.get(1).toLong()).isEqualTo(40L);

        Row row2 = projection.next();
        assertThat(row2.get(0).toLong()).isEqualTo(2L);
        assertThat(row2.get(1).toLong()).isEqualTo(35L);

        Row row3 = projection.next();
        assertThat(row3.get(0).toLong()).isEqualTo(3L);
        assertThat(row3.get(1).toLong()).isEqualTo(45L);

        assertThat(projection.next()).isNull();
        projection.close();
    }

    @Test
    void projectConstantExpression() throws Exception {
        // SELECT name, 42 AS magic_number
        List<Expression> exprs = List.of(
                new ColumnRef(null, "name", 1, DataType.VARCHAR),
                Constant.ofLong(42)
        );
        List<ColumnInfo> outputCols = List.of(
                col("name", DataType.VARCHAR),
                col("magic_number", DataType.BIGINT)
        );

        ListExecutor child = new ListExecutor(sampleRows(), inputSchema);
        ProjectionExecutor projection = new ProjectionExecutor(child, exprs, outputCols, evalCtx);
        projection.open();

        Row row;
        while ((row = projection.next()) != null) {
            assertThat(row.size()).isEqualTo(2);
            assertThat(row.get(1).toLong()).isEqualTo(42L);
        }
        projection.close();
    }

    @Test
    void projectEmptyInput() throws Exception {
        List<Expression> exprs = List.of(
                new ColumnRef(null, "name", 1, DataType.VARCHAR)
        );
        List<ColumnInfo> outputCols = List.of(col("name", DataType.VARCHAR));

        ListExecutor child = new ListExecutor(List.of(), inputSchema);
        ProjectionExecutor projection = new ProjectionExecutor(child, exprs, outputCols, evalCtx);
        projection.open();

        assertThat(projection.next()).isNull();
        projection.close();
    }

    @Test
    void outputSchemaMatchesProjectionColumns() {
        List<Expression> exprs = List.of(
                new ColumnRef(null, "name", 1, DataType.VARCHAR)
        );
        List<ColumnInfo> outputCols = List.of(col("name", DataType.VARCHAR));

        ListExecutor child = new ListExecutor(List.of(), inputSchema);
        ProjectionExecutor projection = new ProjectionExecutor(child, exprs, outputCols, evalCtx);

        assertThat(projection.outputSchema()).hasSize(1);
        assertThat(projection.outputSchema().get(0).getName()).isEqualTo("name");
    }

    @Test
    void projectReorderedColumns() throws Exception {
        // SELECT age, name, id (reversed order)
        List<Expression> exprs = List.of(
                new ColumnRef(null, "age", 2, DataType.INT),
                new ColumnRef(null, "name", 1, DataType.VARCHAR),
                new ColumnRef(null, "id", 0, DataType.INT)
        );
        List<ColumnInfo> outputCols = List.of(
                col("age", DataType.INT),
                col("name", DataType.VARCHAR),
                col("id", DataType.INT)
        );

        ListExecutor child = new ListExecutor(sampleRows(), inputSchema);
        ProjectionExecutor projection = new ProjectionExecutor(child, exprs, outputCols, evalCtx);
        projection.open();

        Row row1 = projection.next();
        assertThat(row1).isNotNull();
        assertThat(row1.get(0).toLong()).isEqualTo(30L);        // age
        assertThat(((Datum.StringDatum) row1.get(1)).value()).isEqualTo("Alice"); // name
        assertThat(row1.get(2).toLong()).isEqualTo(1L);          // id

        projection.close();
    }
}
