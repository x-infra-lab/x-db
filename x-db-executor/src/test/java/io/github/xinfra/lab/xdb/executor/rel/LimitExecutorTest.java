package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.ListExecutor;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.xinfra.lab.xdb.executor.ListExecutor.col;
import static org.assertj.core.api.Assertions.assertThat;

class LimitExecutorTest {

    private final List<ColumnInfo> schema = List.of(
            col("id", DataType.INT),
            col("name", DataType.VARCHAR)
    );

    private List<Row> sampleRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(new Datum[]{Datum.of(1L), Datum.of("Alice")}));
        rows.add(new Row(new Datum[]{Datum.of(2L), Datum.of("Bob")}));
        rows.add(new Row(new Datum[]{Datum.of(3L), Datum.of("Charlie")}));
        rows.add(new Row(new Datum[]{Datum.of(4L), Datum.of("Diana")}));
        rows.add(new Row(new Datum[]{Datum.of(5L), Datum.of("Eve")}));
        return rows;
    }

    @Test
    void limitOnly() throws Exception {
        // LIMIT 3
        ListExecutor child = new ListExecutor(sampleRows(), schema);
        LimitExecutor limit = new LimitExecutor(child, 3, 0);
        limit.open();

        List<Long> ids = new ArrayList<>();
        Row row;
        while ((row = limit.next()) != null) {
            ids.add(row.get(0).toLong());
        }

        assertThat(ids).containsExactly(1L, 2L, 3L);
        limit.close();
    }

    @Test
    void limitWithOffset() throws Exception {
        // LIMIT 2 OFFSET 2
        ListExecutor child = new ListExecutor(sampleRows(), schema);
        LimitExecutor limit = new LimitExecutor(child, 2, 2);
        limit.open();

        List<Long> ids = new ArrayList<>();
        Row row;
        while ((row = limit.next()) != null) {
            ids.add(row.get(0).toLong());
        }

        assertThat(ids).containsExactly(3L, 4L);
        limit.close();
    }

    @Test
    void limitLargerThanInput() throws Exception {
        // LIMIT 100 (but only 5 rows available)
        ListExecutor child = new ListExecutor(sampleRows(), schema);
        LimitExecutor limit = new LimitExecutor(child, 100, 0);
        limit.open();

        List<Long> ids = new ArrayList<>();
        Row row;
        while ((row = limit.next()) != null) {
            ids.add(row.get(0).toLong());
        }

        assertThat(ids).containsExactly(1L, 2L, 3L, 4L, 5L);
        limit.close();
    }

    @Test
    void limitZero() throws Exception {
        // LIMIT 0
        ListExecutor child = new ListExecutor(sampleRows(), schema);
        LimitExecutor limit = new LimitExecutor(child, 0, 0);
        limit.open();

        assertThat(limit.next()).isNull();
        limit.close();
    }

    @Test
    void offsetBeyondInput() throws Exception {
        // LIMIT 10 OFFSET 100
        ListExecutor child = new ListExecutor(sampleRows(), schema);
        LimitExecutor limit = new LimitExecutor(child, 10, 100);
        limit.open();

        assertThat(limit.next()).isNull();
        limit.close();
    }

    @Test
    void offsetExactlyAtEnd() throws Exception {
        // LIMIT 10 OFFSET 5 (5 rows, offset 5 = skip all)
        ListExecutor child = new ListExecutor(sampleRows(), schema);
        LimitExecutor limit = new LimitExecutor(child, 10, 5);
        limit.open();

        assertThat(limit.next()).isNull();
        limit.close();
    }

    @Test
    void limitOneWithOffset() throws Exception {
        // LIMIT 1 OFFSET 3
        ListExecutor child = new ListExecutor(sampleRows(), schema);
        LimitExecutor limit = new LimitExecutor(child, 1, 3);
        limit.open();

        Row row = limit.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(4L); // 4th row (0-indexed offset=3)

        assertThat(limit.next()).isNull();
        limit.close();
    }

    @Test
    void emptyInput() throws Exception {
        ListExecutor child = new ListExecutor(List.of(), schema);
        LimitExecutor limit = new LimitExecutor(child, 5, 0);
        limit.open();

        assertThat(limit.next()).isNull();
        limit.close();
    }

    @Test
    void outputSchemaMatchesChild() {
        ListExecutor child = new ListExecutor(List.of(), schema);
        LimitExecutor limit = new LimitExecutor(child, 5, 0);

        assertThat(limit.outputSchema()).isEqualTo(schema);
    }
}
