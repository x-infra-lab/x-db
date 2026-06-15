package io.github.xinfra.lab.xdb.executor.spill;

import io.github.xinfra.lab.xdb.common.MemoryTracker;
import io.github.xinfra.lab.xdb.executor.ListExecutor;
import io.github.xinfra.lab.xdb.executor.rel.SortExecutor;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;

import static io.github.xinfra.lab.xdb.executor.ListExecutor.col;
import static org.assertj.core.api.Assertions.assertThat;

class SortSpillTest {

    private final EvalContext evalCtx = new EvalContext();

    private final List<ColumnInfo> schema = List.of(
            col("id", DataType.INT),
            col("name", DataType.VARCHAR),
            col("value", DataType.INT)
    );

    private List<Row> generateRows(int count) {
        List<Row> rows = new ArrayList<>();
        for (int i = count; i > 0; i--) {
            rows.add(new Row(new Datum[]{
                    Datum.of((long) i),
                    Datum.of("row-" + i),
                    Datum.of((long) (i * 10))
            }));
        }
        return rows;
    }

    @Test
    void spillProducesCorrectSortOrder() throws Exception {
        int rowCount = 200;
        List<Row> rows = generateRows(rowCount);

        // Very small memory limit to force spilling
        MemoryTracker tracker = new MemoryTracker("sort", null, 500);

        List<Expression> orderExprs = List.of(
                new ColumnRef(null, "id", 0, DataType.INT)
        );

        ListExecutor child = new ListExecutor(rows, schema);
        SortExecutor sort = new SortExecutor(child, orderExprs, List.of(true), evalCtx, tracker);
        sort.open();

        List<Long> ids = new ArrayList<>();
        Row row;
        while ((row = sort.next()) != null) {
            ids.add(row.get(0).toLong());
        }
        sort.close();

        assertThat(ids).hasSize(rowCount);
        List<Long> expected = LongStream.rangeClosed(1, rowCount).boxed().toList();
        assertThat(ids).isEqualTo(expected);
    }

    @Test
    void spillDescendingOrder() throws Exception {
        int rowCount = 100;
        List<Row> rows = generateRows(rowCount);
        Collections.shuffle(rows);

        MemoryTracker tracker = new MemoryTracker("sort", null, 300);

        List<Expression> orderExprs = List.of(
                new ColumnRef(null, "id", 0, DataType.INT)
        );

        ListExecutor child = new ListExecutor(rows, schema);
        SortExecutor sort = new SortExecutor(child, orderExprs, List.of(false), evalCtx, tracker);
        sort.open();

        List<Long> ids = new ArrayList<>();
        Row row;
        while ((row = sort.next()) != null) {
            ids.add(row.get(0).toLong());
        }
        sort.close();

        assertThat(ids).hasSize(rowCount);
        List<Long> expected = LongStream.rangeClosed(1, rowCount)
                .boxed().sorted(Collections.reverseOrder()).toList();
        assertThat(ids).isEqualTo(expected);
    }

    @Test
    void inMemorySortStillWorks() throws Exception {
        List<Row> rows = generateRows(5);

        // Large limit — no spill
        MemoryTracker tracker = new MemoryTracker("sort", null, MemoryTracker.UNLIMITED);

        List<Expression> orderExprs = List.of(
                new ColumnRef(null, "id", 0, DataType.INT)
        );

        ListExecutor child = new ListExecutor(rows, schema);
        SortExecutor sort = new SortExecutor(child, orderExprs, List.of(true), evalCtx, tracker);
        sort.open();

        List<Long> ids = new ArrayList<>();
        Row row;
        while ((row = sort.next()) != null) {
            ids.add(row.get(0).toLong());
        }
        sort.close();

        assertThat(ids).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void emptyInputNoSpill() throws Exception {
        MemoryTracker tracker = new MemoryTracker("sort", null, 100);

        List<Expression> orderExprs = List.of(
                new ColumnRef(null, "id", 0, DataType.INT)
        );

        ListExecutor child = new ListExecutor(List.of(), schema);
        SortExecutor sort = new SortExecutor(child, orderExprs, List.of(true), evalCtx, tracker);
        sort.open();

        assertThat(sort.next()).isNull();
        sort.close();
    }

    @Test
    void spillWithMultipleRuns() throws Exception {
        int rowCount = 500;
        List<Row> rows = generateRows(rowCount);
        Collections.shuffle(rows);

        // Very tight limit to ensure many runs
        MemoryTracker tracker = new MemoryTracker("sort", null, 200);

        List<Expression> orderExprs = List.of(
                new ColumnRef(null, "value", 2, DataType.INT)
        );

        ListExecutor child = new ListExecutor(rows, schema);
        SortExecutor sort = new SortExecutor(child, orderExprs, List.of(true), evalCtx, tracker);
        sort.open();

        List<Long> values = new ArrayList<>();
        Row row;
        while ((row = sort.next()) != null) {
            values.add(row.get(2).toLong());
        }
        sort.close();

        assertThat(values).hasSize(rowCount);
        // value = id * 10, sorted ascending
        for (int i = 1; i < values.size(); i++) {
            assertThat(values.get(i)).isGreaterThanOrEqualTo(values.get(i - 1));
        }
    }

    @Test
    void sortedRunRoundTrip() throws Exception {
        RowSerializer serializer = new RowSerializer();
        TempFileManager tmpMgr = new TempFileManager();

        List<Row> rows = List.of(
                new Row(new Datum[]{Datum.of(1L), Datum.of("a")}),
                new Row(new Datum[]{Datum.of(2L), Datum.of("b")}),
                new Row(new Datum[]{Datum.of(3L), Datum.of("c")})
        );

        SortedRun run = SortedRun.write(rows, serializer, tmpMgr);
        assertThat(run.rowCount()).isEqualTo(3);

        run.openForRead();
        Row r1 = run.readNext();
        Row r2 = run.readNext();
        Row r3 = run.readNext();
        Row r4 = run.readNext();

        assertThat(r1.get(0).toLong()).isEqualTo(1L);
        assertThat(r2.get(0).toLong()).isEqualTo(2L);
        assertThat(r3.get(0).toLong()).isEqualTo(3L);
        assertThat(r4).isNull();

        run.close();
        tmpMgr.close();
    }

    @Test
    void mergeSortIteratorMergesCorrectly() throws Exception {
        RowSerializer serializer = new RowSerializer();
        TempFileManager tmpMgr = new TempFileManager();

        // Run 1: 1, 3, 5
        List<Row> run1Rows = List.of(
                new Row(new Datum[]{Datum.of(1L)}),
                new Row(new Datum[]{Datum.of(3L)}),
                new Row(new Datum[]{Datum.of(5L)})
        );
        // Run 2: 2, 4, 6
        List<Row> run2Rows = List.of(
                new Row(new Datum[]{Datum.of(2L)}),
                new Row(new Datum[]{Datum.of(4L)}),
                new Row(new Datum[]{Datum.of(6L)})
        );

        SortedRun run1 = SortedRun.write(run1Rows, serializer, tmpMgr);
        SortedRun run2 = SortedRun.write(run2Rows, serializer, tmpMgr);
        run1.openForRead();
        run2.openForRead();

        MergeSortIterator iter = new MergeSortIterator(
                List.of(run1, run2),
                (a, b) -> Long.compare(a.get(0).toLong(), b.get(0).toLong())
        );

        List<Long> merged = new ArrayList<>();
        Row row;
        while ((row = iter.next()) != null) {
            merged.add(row.get(0).toLong());
        }

        assertThat(merged).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);

        iter.close();
        tmpMgr.close();
    }
}
