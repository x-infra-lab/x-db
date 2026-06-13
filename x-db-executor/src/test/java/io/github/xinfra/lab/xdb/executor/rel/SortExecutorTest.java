package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.ListExecutor;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
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

class SortExecutorTest {

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
        return rows;
    }

    @Test
    void sortAscendingByAge() throws Exception {
        List<Expression> orderExprs = List.of(
                new ColumnRef(null, "age", 2, DataType.INT)
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        SortExecutor sort = new SortExecutor(child, orderExprs, List.of(true), evalCtx);
        sort.open();

        List<Long> ages = new ArrayList<>();
        Row row;
        while ((row = sort.next()) != null) {
            ages.add(row.get(2).toLong());
        }

        assertThat(ages).containsExactly(25L, 28L, 30L, 35L);
        sort.close();
    }

    @Test
    void sortDescendingByAge() throws Exception {
        List<Expression> orderExprs = List.of(
                new ColumnRef(null, "age", 2, DataType.INT)
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        SortExecutor sort = new SortExecutor(child, orderExprs, List.of(false), evalCtx);
        sort.open();

        List<Long> ages = new ArrayList<>();
        Row row;
        while ((row = sort.next()) != null) {
            ages.add(row.get(2).toLong());
        }

        assertThat(ages).containsExactly(35L, 30L, 28L, 25L);
        sort.close();
    }

    @Test
    void sortByName() throws Exception {
        List<Expression> orderExprs = List.of(
                new ColumnRef(null, "name", 1, DataType.VARCHAR)
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        SortExecutor sort = new SortExecutor(child, orderExprs, List.of(true), evalCtx);
        sort.open();

        List<String> names = new ArrayList<>();
        Row row;
        while ((row = sort.next()) != null) {
            names.add(((Datum.StringDatum) row.get(1)).value());
        }

        assertThat(names).containsExactly("Alice", "Bob", "Charlie", "Diana");
        sort.close();
    }

    @Test
    void multiColumnSort() throws Exception {
        // Rows with same age but different ids
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(new Datum[]{Datum.of(3L), Datum.of("C"), Datum.of(30L)}));
        rows.add(new Row(new Datum[]{Datum.of(1L), Datum.of("A"), Datum.of(25L)}));
        rows.add(new Row(new Datum[]{Datum.of(4L), Datum.of("D"), Datum.of(30L)}));
        rows.add(new Row(new Datum[]{Datum.of(2L), Datum.of("B"), Datum.of(25L)}));

        // ORDER BY age ASC, id DESC
        List<Expression> orderExprs = List.of(
                new ColumnRef(null, "age", 2, DataType.INT),
                new ColumnRef(null, "id", 0, DataType.INT)
        );

        ListExecutor child = new ListExecutor(rows, schema);
        SortExecutor sort = new SortExecutor(child, orderExprs, List.of(true, false), evalCtx);
        sort.open();

        List<Long> ids = new ArrayList<>();
        Row row;
        while ((row = sort.next()) != null) {
            ids.add(row.get(0).toLong());
        }

        // age=25: ids 2,1 (desc); age=30: ids 4,3 (desc)
        assertThat(ids).containsExactly(2L, 1L, 4L, 3L);
        sort.close();
    }

    @Test
    void sortEmptyInput() throws Exception {
        List<Expression> orderExprs = List.of(
                new ColumnRef(null, "age", 2, DataType.INT)
        );

        ListExecutor child = new ListExecutor(List.of(), schema);
        SortExecutor sort = new SortExecutor(child, orderExprs, List.of(true), evalCtx);
        sort.open();

        assertThat(sort.next()).isNull();
        sort.close();
    }

    @Test
    void sortSingleRow() throws Exception {
        List<Row> rows = List.of(
                new Row(new Datum[]{Datum.of(1L), Datum.of("Alice"), Datum.of(30L)})
        );

        List<Expression> orderExprs = List.of(
                new ColumnRef(null, "age", 2, DataType.INT)
        );

        ListExecutor child = new ListExecutor(rows, schema);
        SortExecutor sort = new SortExecutor(child, orderExprs, List.of(true), evalCtx);
        sort.open();

        Row row = sort.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(1L);

        assertThat(sort.next()).isNull();
        sort.close();
    }

    @Test
    void outputSchemaMatchesChild() {
        ListExecutor child = new ListExecutor(List.of(), schema);
        SortExecutor sort = new SortExecutor(child,
                List.of(new ColumnRef(null, "age", 2, DataType.INT)),
                List.of(true), evalCtx);

        assertThat(sort.outputSchema()).isEqualTo(schema);
    }
}
