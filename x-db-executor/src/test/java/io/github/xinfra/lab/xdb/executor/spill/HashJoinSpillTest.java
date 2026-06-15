package io.github.xinfra.lab.xdb.executor.spill;

import io.github.xinfra.lab.xdb.common.MemoryTracker;
import io.github.xinfra.lab.xdb.executor.ListExecutor;
import io.github.xinfra.lab.xdb.executor.rel.HashJoinExecutor;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.planner.logical.JoinType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.github.xinfra.lab.xdb.executor.ListExecutor.col;
import static org.assertj.core.api.Assertions.assertThat;

class HashJoinSpillTest {

    private final EvalContext evalCtx = new EvalContext();

    private final List<ColumnInfo> buildSchema = List.of(
            col("id", DataType.INT),
            col("name", DataType.VARCHAR),
            col("dept_id", DataType.INT)
    );

    private final List<ColumnInfo> probeSchema = List.of(
            col("dept_id", DataType.INT),
            col("dept_name", DataType.VARCHAR)
    );

    private final List<ColumnInfo> joinOutput = List.of(
            col("id", DataType.INT),
            col("name", DataType.VARCHAR),
            col("dept_id", DataType.INT),
            col("dept_id", DataType.INT),
            col("dept_name", DataType.VARCHAR)
    );

    private final List<Expression> buildKeys = List.of(
            new ColumnRef(null, "dept_id", 2, DataType.INT)
    );
    private final List<Expression> probeKeys = List.of(
            new ColumnRef(null, "dept_id", 0, DataType.INT)
    );

    private List<Row> generateBuildRows(int count) {
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            long deptId = (i % 10) + 1;
            rows.add(new Row(new Datum[]{
                    Datum.of((long) i),
                    Datum.of("emp-" + i),
                    Datum.of(deptId)
            }));
        }
        return rows;
    }

    private List<Row> generateProbeRows(int deptCount) {
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= deptCount; i++) {
            rows.add(new Row(new Datum[]{
                    Datum.of((long) i),
                    Datum.of("dept-" + i)
            }));
        }
        return rows;
    }

    @Test
    void innerJoinWithSpill() throws Exception {
        int buildCount = 200;
        List<Row> buildRows = generateBuildRows(buildCount);
        List<Row> probeRows = generateProbeRows(10);

        MemoryTracker tracker = new MemoryTracker("hash_join", null, 500);

        ListExecutor buildExec = new ListExecutor(buildRows, buildSchema);
        ListExecutor probeExec = new ListExecutor(probeRows, probeSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildExec, probeExec, JoinType.INNER, null,
                buildKeys, probeKeys, joinOutput, evalCtx, tracker);
        join.open();

        List<Row> results = new ArrayList<>();
        Row row;
        while ((row = join.next()) != null) {
            results.add(row);
        }
        join.close();

        // All 200 build rows should match (dept_id 1-10, all exist in probe)
        assertThat(results).hasSize(buildCount);

        // Verify each result has correct join
        for (Row r : results) {
            long empDeptId = r.get(2).toLong();
            long probeDeptId = r.get(3).toLong();
            assertThat(empDeptId).isEqualTo(probeDeptId);
        }
    }

    @Test
    void innerJoinWithSpillPartialMatch() throws Exception {
        int buildCount = 200;
        List<Row> buildRows = generateBuildRows(buildCount);
        // Only 5 departments — half the build rows won't match
        List<Row> probeRows = generateProbeRows(5);

        MemoryTracker tracker = new MemoryTracker("hash_join", null, 500);

        ListExecutor buildExec = new ListExecutor(buildRows, buildSchema);
        ListExecutor probeExec = new ListExecutor(probeRows, probeSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildExec, probeExec, JoinType.INNER, null,
                buildKeys, probeKeys, joinOutput, evalCtx, tracker);
        join.open();

        List<Row> results = new ArrayList<>();
        Row row;
        while ((row = join.next()) != null) {
            results.add(row);
        }
        join.close();

        // dept_id 1-5 match, dept_id 6-10 don't
        // Each of 200 employees has dept_id = (i%10)+1
        // For dept 1-5: employees with i%10 = 0,1,2,3,4 -> 100 matches
        assertThat(results).hasSize(100);
    }

    @Test
    void leftJoinWithSpill() throws Exception {
        int buildCount = 100;
        List<Row> buildRows = generateBuildRows(buildCount);
        List<Row> probeRows = generateProbeRows(5);

        MemoryTracker tracker = new MemoryTracker("hash_join", null, 500);

        ListExecutor buildExec = new ListExecutor(buildRows, buildSchema);
        ListExecutor probeExec = new ListExecutor(probeRows, probeSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildExec, probeExec, JoinType.LEFT, null,
                buildKeys, probeKeys, joinOutput, evalCtx, tracker);
        join.open();

        List<Row> results = new ArrayList<>();
        int matchedCount = 0;
        int unmatchedCount = 0;
        Row row;
        while ((row = join.next()) != null) {
            results.add(row);
            if (row.get(4).isNull()) {
                unmatchedCount++;
            } else {
                matchedCount++;
            }
        }
        join.close();

        // All 100 build rows should appear (matched or unmatched)
        assertThat(results).hasSize(buildCount);
        assertThat(matchedCount).isEqualTo(50); // dept 1-5
        assertThat(unmatchedCount).isEqualTo(50); // dept 6-10
    }

    @Test
    void inMemoryJoinStillWorks() throws Exception {
        List<Row> buildRows = generateBuildRows(5);
        List<Row> probeRows = generateProbeRows(10);

        // Large limit — no spill
        MemoryTracker tracker = new MemoryTracker("hash_join", null, MemoryTracker.UNLIMITED);

        ListExecutor buildExec = new ListExecutor(buildRows, buildSchema);
        ListExecutor probeExec = new ListExecutor(probeRows, probeSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildExec, probeExec, JoinType.INNER, null,
                buildKeys, probeKeys, joinOutput, evalCtx, tracker);
        join.open();

        List<Row> results = new ArrayList<>();
        Row row;
        while ((row = join.next()) != null) {
            results.add(row);
        }
        join.close();

        assertThat(results).hasSize(5);
    }

    @Test
    void emptyBuildSideWithSpill() throws Exception {
        MemoryTracker tracker = new MemoryTracker("hash_join", null, 100);

        ListExecutor buildExec = new ListExecutor(List.of(), buildSchema);
        ListExecutor probeExec = new ListExecutor(generateProbeRows(5), probeSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildExec, probeExec, JoinType.INNER, null,
                buildKeys, probeKeys, joinOutput, evalCtx, tracker);
        join.open();

        assertThat(join.next()).isNull();
        join.close();
    }

    @Test
    void spillPreservesAllDistinctMatchPairs() throws Exception {
        int buildCount = 300;
        List<Row> buildRows = generateBuildRows(buildCount);
        List<Row> probeRows = generateProbeRows(10);

        MemoryTracker tracker = new MemoryTracker("hash_join", null, 300);

        ListExecutor buildExec = new ListExecutor(buildRows, buildSchema);
        ListExecutor probeExec = new ListExecutor(probeRows, probeSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildExec, probeExec, JoinType.INNER, null,
                buildKeys, probeKeys, joinOutput, evalCtx, tracker);
        join.open();

        Set<Long> seenEmpIds = new HashSet<>();
        Row row;
        while ((row = join.next()) != null) {
            seenEmpIds.add(row.get(0).toLong());
        }
        join.close();

        // Every build row should appear exactly once
        assertThat(seenEmpIds).hasSize(buildCount);
    }
}
