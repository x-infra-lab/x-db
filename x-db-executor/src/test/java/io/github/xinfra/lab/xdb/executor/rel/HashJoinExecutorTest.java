package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.ListExecutor;
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
import java.util.List;

import static io.github.xinfra.lab.xdb.executor.ListExecutor.col;
import static org.assertj.core.api.Assertions.assertThat;

class HashJoinExecutorTest {

    private final EvalContext evalCtx = new EvalContext();

    // Build side: employees(emp_id, name, dept_id)
    private final List<ColumnInfo> empSchema = List.of(
            col("emp_id", DataType.INT),
            col("name", DataType.VARCHAR),
            col("dept_id", DataType.INT)
    );

    // Probe side: departments(dept_id, dept_name)
    private final List<ColumnInfo> deptSchema = List.of(
            col("dept_id", DataType.INT),
            col("dept_name", DataType.VARCHAR)
    );

    // Output: emp_id, name, dept_id (from emp), dept_id (from dept), dept_name
    private final List<ColumnInfo> joinOutputSchema = List.of(
            col("emp_id", DataType.INT),
            col("name", DataType.VARCHAR),
            col("dept_id", DataType.INT),
            col("dept_id", DataType.INT),
            col("dept_name", DataType.VARCHAR)
    );

    private List<Row> employeeRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(new Datum[]{Datum.of(1L), Datum.of("Alice"), Datum.of(10L)}));
        rows.add(new Row(new Datum[]{Datum.of(2L), Datum.of("Bob"), Datum.of(20L)}));
        rows.add(new Row(new Datum[]{Datum.of(3L), Datum.of("Charlie"), Datum.of(10L)}));
        rows.add(new Row(new Datum[]{Datum.of(4L), Datum.of("Diana"), Datum.of(30L)}));
        return rows;
    }

    private List<Row> departmentRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(new Datum[]{Datum.of(10L), Datum.of("Engineering")}));
        rows.add(new Row(new Datum[]{Datum.of(20L), Datum.of("Marketing")}));
        rows.add(new Row(new Datum[]{Datum.of(40L), Datum.of("Sales")}));
        return rows;
    }

    @Test
    void innerJoinMatchingRows() throws Exception {
        // Build key: emp.dept_id (index 2 in build row)
        // Probe key: dept.dept_id (index 0 in probe row)
        List<Expression> buildKeys = List.of(
                new ColumnRef(null, "dept_id", 2, DataType.INT)
        );
        List<Expression> probeKeys = List.of(
                new ColumnRef(null, "dept_id", 0, DataType.INT)
        );

        ListExecutor buildSide = new ListExecutor(employeeRows(), empSchema);
        ListExecutor probeSide = new ListExecutor(departmentRows(), deptSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildSide, probeSide, JoinType.INNER, null,
                buildKeys, probeKeys, joinOutputSchema, evalCtx
        );
        join.open();

        List<String> results = new ArrayList<>();
        Row row;
        while ((row = join.next()) != null) {
            String empName = ((Datum.StringDatum) row.get(1)).value();
            String deptName = ((Datum.StringDatum) row.get(4)).value();
            results.add(empName + "-" + deptName);
        }

        // Alice(dept 10->Engineering), Charlie(dept 10->Engineering), Bob(dept 20->Marketing)
        // Diana(dept 30) has no match, Sales(dept 40) has no match
        assertThat(results).hasSize(3);
        assertThat(results).contains("Alice-Engineering", "Charlie-Engineering", "Bob-Marketing");
        join.close();
    }

    @Test
    void innerJoinNoMatches() throws Exception {
        // Employees with dept_id 99 (no matching dept)
        List<Row> noMatchEmps = List.of(
                new Row(new Datum[]{Datum.of(1L), Datum.of("Alone"), Datum.of(99L)})
        );

        List<Expression> buildKeys = List.of(
                new ColumnRef(null, "dept_id", 2, DataType.INT)
        );
        List<Expression> probeKeys = List.of(
                new ColumnRef(null, "dept_id", 0, DataType.INT)
        );

        ListExecutor buildSide = new ListExecutor(noMatchEmps, empSchema);
        ListExecutor probeSide = new ListExecutor(departmentRows(), deptSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildSide, probeSide, JoinType.INNER, null,
                buildKeys, probeKeys, joinOutputSchema, evalCtx
        );
        join.open();

        assertThat(join.next()).isNull();
        join.close();
    }

    @Test
    void innerJoinEmptyBuildSide() throws Exception {
        List<Expression> buildKeys = List.of(
                new ColumnRef(null, "dept_id", 2, DataType.INT)
        );
        List<Expression> probeKeys = List.of(
                new ColumnRef(null, "dept_id", 0, DataType.INT)
        );

        ListExecutor buildSide = new ListExecutor(List.of(), empSchema);
        ListExecutor probeSide = new ListExecutor(departmentRows(), deptSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildSide, probeSide, JoinType.INNER, null,
                buildKeys, probeKeys, joinOutputSchema, evalCtx
        );
        join.open();

        assertThat(join.next()).isNull();
        join.close();
    }

    @Test
    void innerJoinEmptyProbeSide() throws Exception {
        List<Expression> buildKeys = List.of(
                new ColumnRef(null, "dept_id", 2, DataType.INT)
        );
        List<Expression> probeKeys = List.of(
                new ColumnRef(null, "dept_id", 0, DataType.INT)
        );

        ListExecutor buildSide = new ListExecutor(employeeRows(), empSchema);
        ListExecutor probeSide = new ListExecutor(List.of(), deptSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildSide, probeSide, JoinType.INNER, null,
                buildKeys, probeKeys, joinOutputSchema, evalCtx
        );
        join.open();

        assertThat(join.next()).isNull();
        join.close();
    }

    @Test
    void innerJoinMultipleMatchesPerKey() throws Exception {
        // Two departments with same id won't happen, but two employees with same dept_id
        // Build: employees with dept_id 10 (Alice, Charlie)
        // Probe: dept 10 -> should match both
        List<Row> depts = List.of(
                new Row(new Datum[]{Datum.of(10L), Datum.of("Engineering")})
        );

        List<Expression> buildKeys = List.of(
                new ColumnRef(null, "dept_id", 2, DataType.INT)
        );
        List<Expression> probeKeys = List.of(
                new ColumnRef(null, "dept_id", 0, DataType.INT)
        );

        ListExecutor buildSide = new ListExecutor(employeeRows(), empSchema);
        ListExecutor probeSide = new ListExecutor(depts, deptSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildSide, probeSide, JoinType.INNER, null,
                buildKeys, probeKeys, joinOutputSchema, evalCtx
        );
        join.open();

        List<String> names = new ArrayList<>();
        Row row;
        while ((row = join.next()) != null) {
            names.add(((Datum.StringDatum) row.get(1)).value());
        }

        // Alice and Charlie both have dept_id=10
        assertThat(names).containsExactlyInAnyOrder("Alice", "Charlie");
        join.close();
    }

    @Test
    void joinOutputHasCorrectColumnLayout() throws Exception {
        // Verify the joined row has build columns followed by probe columns
        List<Row> emps = List.of(
                new Row(new Datum[]{Datum.of(1L), Datum.of("Alice"), Datum.of(10L)})
        );
        List<Row> depts = List.of(
                new Row(new Datum[]{Datum.of(10L), Datum.of("Engineering")})
        );

        List<Expression> buildKeys = List.of(
                new ColumnRef(null, "dept_id", 2, DataType.INT)
        );
        List<Expression> probeKeys = List.of(
                new ColumnRef(null, "dept_id", 0, DataType.INT)
        );

        ListExecutor buildSide = new ListExecutor(emps, empSchema);
        ListExecutor probeSide = new ListExecutor(depts, deptSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildSide, probeSide, JoinType.INNER, null,
                buildKeys, probeKeys, joinOutputSchema, evalCtx
        );
        join.open();

        Row row = join.next();
        assertThat(row).isNotNull();
        assertThat(row.size()).isEqualTo(5); // 3 emp cols + 2 dept cols

        // Build cols: emp_id=1, name=Alice, dept_id=10
        assertThat(row.get(0).toLong()).isEqualTo(1L);
        assertThat(((Datum.StringDatum) row.get(1)).value()).isEqualTo("Alice");
        assertThat(row.get(2).toLong()).isEqualTo(10L);

        // Probe cols: dept_id=10, dept_name=Engineering
        assertThat(row.get(3).toLong()).isEqualTo(10L);
        assertThat(((Datum.StringDatum) row.get(4)).value()).isEqualTo("Engineering");

        assertThat(join.next()).isNull();
        join.close();
    }

    @Test
    void leftJoinEmitsUnmatchedBuildRows() throws Exception {
        List<Expression> buildKeys = List.of(
                new ColumnRef(null, "dept_id", 2, DataType.INT)
        );
        List<Expression> probeKeys = List.of(
                new ColumnRef(null, "dept_id", 0, DataType.INT)
        );

        ListExecutor buildSide = new ListExecutor(employeeRows(), empSchema);
        ListExecutor probeSide = new ListExecutor(departmentRows(), deptSchema);

        HashJoinExecutor join = new HashJoinExecutor(
                buildSide, probeSide, JoinType.LEFT, null,
                buildKeys, probeKeys, joinOutputSchema, evalCtx
        );
        join.open();

        List<String> results = new ArrayList<>();
        Row row;
        while ((row = join.next()) != null) {
            String empName = ((Datum.StringDatum) row.get(1)).value();
            Datum deptNameDatum = row.get(4);
            String deptName = deptNameDatum.isNull() ? "NULL" : ((Datum.StringDatum) deptNameDatum).value();
            results.add(empName + "-" + deptName);
        }

        // Matched: Alice-Engineering, Charlie-Engineering, Bob-Marketing
        // Unmatched: Diana-NULL (dept_id=30 has no match)
        assertThat(results).hasSize(4);
        assertThat(results).contains("Alice-Engineering", "Charlie-Engineering", "Bob-Marketing", "Diana-NULL");
        join.close();
    }

    @Test
    void outputSchemaIsCorrect() {
        ListExecutor buildSide = new ListExecutor(List.of(), empSchema);
        ListExecutor probeSide = new ListExecutor(List.of(), deptSchema);

        List<Expression> buildKeys = List.of(
                new ColumnRef(null, "dept_id", 2, DataType.INT)
        );
        List<Expression> probeKeys = List.of(
                new ColumnRef(null, "dept_id", 0, DataType.INT)
        );

        HashJoinExecutor join = new HashJoinExecutor(
                buildSide, probeSide, JoinType.INNER, null,
                buildKeys, probeKeys, joinOutputSchema, evalCtx
        );

        assertThat(join.outputSchema()).hasSize(5);
    }
}
