package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.ListExecutor;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.xinfra.lab.xdb.executor.ListExecutor.col;
import static org.assertj.core.api.Assertions.assertThat;

class UnionExecutorTest {

    private final List<ColumnInfo> schema = List.of(
            col("id", DataType.INT),
            col("name", DataType.VARCHAR)
    );

    private List<Row> rows(Object[]... data) {
        List<Row> result = new ArrayList<>();
        for (Object[] d : data) {
            result.add(new Row(new Datum[]{Datum.of((long) (int) d[0]), Datum.of((String) d[1])}));
        }
        return result;
    }

    @Test
    @DisplayName("UNION ALL returns all rows from both sides")
    void unionAll() throws Exception {
        ListExecutor left = new ListExecutor(rows(
                new Object[]{1, "Alice"}, new Object[]{2, "Bob"}), schema);
        ListExecutor right = new ListExecutor(rows(
                new Object[]{3, "Charlie"}, new Object[]{2, "Bob"}), schema);

        UnionExecutor union = new UnionExecutor(List.of(left, right), true, schema);
        union.open();

        List<Row> results = drain(union);
        assertThat(results).hasSize(4);
        union.close();
    }

    @Test
    @DisplayName("UNION (distinct) deduplicates rows")
    void unionDistinct() throws Exception {
        ListExecutor left = new ListExecutor(rows(
                new Object[]{1, "Alice"}, new Object[]{2, "Bob"}), schema);
        ListExecutor right = new ListExecutor(rows(
                new Object[]{2, "Bob"}, new Object[]{3, "Charlie"}), schema);

        UnionExecutor union = new UnionExecutor(List.of(left, right), false, schema);
        union.open();

        List<Row> results = drain(union);
        assertThat(results).hasSize(3);
        union.close();
    }

    @Test
    @DisplayName("UNION ALL with empty left side")
    void emptyLeft() throws Exception {
        ListExecutor left = new ListExecutor(List.of(), schema);
        ListExecutor right = new ListExecutor(rows(
                new Object[]{1, "Alice"}), schema);

        UnionExecutor union = new UnionExecutor(List.of(left, right), true, schema);
        union.open();

        List<Row> results = drain(union);
        assertThat(results).hasSize(1);
        union.close();
    }

    @Test
    @DisplayName("UNION ALL with empty right side")
    void emptyRight() throws Exception {
        ListExecutor left = new ListExecutor(rows(
                new Object[]{1, "Alice"}), schema);
        ListExecutor right = new ListExecutor(List.of(), schema);

        UnionExecutor union = new UnionExecutor(List.of(left, right), true, schema);
        union.open();

        List<Row> results = drain(union);
        assertThat(results).hasSize(1);
        union.close();
    }

    @Test
    @DisplayName("UNION ALL with both sides empty")
    void bothEmpty() throws Exception {
        ListExecutor left = new ListExecutor(List.of(), schema);
        ListExecutor right = new ListExecutor(List.of(), schema);

        UnionExecutor union = new UnionExecutor(List.of(left, right), true, schema);
        union.open();

        assertThat(union.next()).isNull();
        union.close();
    }

    @Test
    @DisplayName("UNION ALL with three branches")
    void threeBranches() throws Exception {
        ListExecutor a = new ListExecutor(rows(new Object[]{1, "A"}), schema);
        ListExecutor b = new ListExecutor(rows(new Object[]{2, "B"}), schema);
        ListExecutor c = new ListExecutor(rows(new Object[]{3, "C"}), schema);

        UnionExecutor union = new UnionExecutor(List.of(a, b, c), true, schema);
        union.open();

        List<Row> results = drain(union);
        assertThat(results).hasSize(3);
        union.close();
    }

    @Test
    @DisplayName("outputSchema returns correct schema")
    void outputSchema() {
        ListExecutor left = new ListExecutor(List.of(), schema);
        UnionExecutor union = new UnionExecutor(List.of(left), true, schema);
        assertThat(union.outputSchema()).isEqualTo(schema);
    }

    private List<Row> drain(UnionExecutor exec) throws Exception {
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = exec.next()) != null) {
            rows.add(row);
        }
        return rows;
    }
}
