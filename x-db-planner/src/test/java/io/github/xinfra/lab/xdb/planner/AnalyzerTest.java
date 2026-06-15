package io.github.xinfra.lab.xdb.planner;

import io.github.xinfra.lab.xdb.expression.*;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.parser.SQLParser;
import io.github.xinfra.lab.xdb.parser.ast.Statement;
import io.github.xinfra.lab.xdb.planner.logical.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyzerTest {

    private InfoSchema infoSchema;
    private Analyzer analyzer;

    @BeforeEach
    void setUp() {
        infoSchema = TestSchemaFactory.createInfoSchema();
        analyzer = new Analyzer(infoSchema, TestSchemaFactory.DB_NAME);
    }

    private LogicalPlan analyze(String sql) {
        Statement stmt = SQLParser.parse(sql);
        return analyzer.analyze(stmt);
    }

    // -----------------------------------------------------------------------
    // SELECT tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("SELECT statements")
    class SelectTests {

        @Test
        @DisplayName("SELECT * FROM table produces Projection over TableScan")
        void selectStarFromTable() {
            LogicalPlan plan = analyze("SELECT * FROM users");

            assertThat(plan).isInstanceOf(LogicalProjection.class);
            LogicalProjection proj = (LogicalProjection) plan;

            // The output schema should contain all 4 columns of the users table
            assertThat(proj.outputSchema()).hasSizeGreaterThanOrEqualTo(4);
            List<String> colNames = proj.outputSchema().stream()
                    .map(ColumnInfo::getName).toList();
            assertThat(colNames).contains("id", "name", "age", "email");

            // Child must be a TableScan
            assertThat(proj.child()).isInstanceOf(LogicalTableScan.class);
            LogicalTableScan scan = (LogicalTableScan) proj.child();
            assertThat(scan.table().getName()).isEqualTo("users");
        }

        @Test
        @DisplayName("SELECT specific columns produces Projection with column refs")
        void selectSpecificColumns() {
            LogicalPlan plan = analyze("SELECT name, age FROM users");

            assertThat(plan).isInstanceOf(LogicalProjection.class);
            LogicalProjection proj = (LogicalProjection) plan;

            assertThat(proj.expressions()).hasSize(2);
            assertThat(proj.outputSchema()).hasSize(2);
            assertThat(proj.outputSchema().get(0).getName()).isEqualTo("name");
            assertThat(proj.outputSchema().get(1).getName()).isEqualTo("age");
        }

        @Test
        @DisplayName("SELECT with WHERE clause produces Selection below Projection")
        void selectWithWhere() {
            LogicalPlan plan = analyze("SELECT * FROM users WHERE age > 18");

            assertThat(plan).isInstanceOf(LogicalProjection.class);
            LogicalProjection proj = (LogicalProjection) plan;

            assertThat(proj.child()).isInstanceOf(LogicalSelection.class);
            LogicalSelection sel = (LogicalSelection) proj.child();
            assertThat(sel.conditions()).hasSize(1);

            Expression cond = sel.conditions().get(0);
            assertThat(cond).isInstanceOf(BinaryOp.class);
            BinaryOp binOp = (BinaryOp) cond;
            assertThat(binOp.op()).isEqualTo(BinaryOp.Op.GT);

            assertThat(sel.child()).isInstanceOf(LogicalTableScan.class);
        }

        @Test
        @DisplayName("SELECT with compound WHERE (AND) produces Selection with BinaryOp(AND)")
        void selectWithCompoundWhere() {
            LogicalPlan plan = analyze("SELECT * FROM users WHERE age > 18 AND name = 'Alice'");

            assertThat(plan).isInstanceOf(LogicalProjection.class);
            LogicalProjection proj = (LogicalProjection) plan;

            assertThat(proj.child()).isInstanceOf(LogicalSelection.class);
            LogicalSelection sel = (LogicalSelection) proj.child();
            assertThat(sel.conditions()).hasSize(1);

            Expression cond = sel.conditions().get(0);
            assertThat(cond).isInstanceOf(BinaryOp.class);
            BinaryOp andOp = (BinaryOp) cond;
            assertThat(andOp.op()).isEqualTo(BinaryOp.Op.AND);
        }

        @Test
        @DisplayName("SELECT with JOIN produces Projection over Join")
        void selectWithJoin() {
            LogicalPlan plan = analyze(
                    "SELECT * FROM users INNER JOIN orders ON users.id = orders.user_id");

            assertThat(plan).isInstanceOf(LogicalProjection.class);
            LogicalProjection proj = (LogicalProjection) plan;

            assertThat(proj.child()).isInstanceOf(LogicalJoin.class);
            LogicalJoin join = (LogicalJoin) proj.child();
            assertThat(join.joinType()).isEqualTo(JoinType.INNER);
            assertThat(join.condition()).isNotNull();
            assertThat(join.condition()).isInstanceOf(BinaryOp.class);

            assertThat(join.left()).isInstanceOf(LogicalTableScan.class);
            assertThat(join.right()).isInstanceOf(LogicalTableScan.class);
            assertThat(((LogicalTableScan) join.left()).table().getName()).isEqualTo("users");
            assertThat(((LogicalTableScan) join.right()).table().getName()).isEqualTo("orders");
        }

        @Test
        @DisplayName("SELECT with LEFT JOIN has LEFT join type")
        void selectWithLeftJoin() {
            LogicalPlan plan = analyze(
                    "SELECT * FROM users LEFT JOIN orders ON users.id = orders.user_id");

            assertThat(plan).isInstanceOf(LogicalProjection.class);
            LogicalProjection proj = (LogicalProjection) plan;
            assertThat(proj.child()).isInstanceOf(LogicalJoin.class);
            LogicalJoin join = (LogicalJoin) proj.child();
            assertThat(join.joinType()).isEqualTo(JoinType.LEFT);
        }

        @Test
        @DisplayName("SELECT with GROUP BY and COUNT produces Aggregation")
        void selectWithGroupByAndCount() {
            LogicalPlan plan = analyze(
                    "SELECT status, COUNT(id) FROM orders GROUP BY status");

            assertThat(plan).isInstanceOf(LogicalProjection.class);
            LogicalProjection proj = (LogicalProjection) plan;

            // Child of projection should be Aggregation
            assertThat(proj.child()).isInstanceOf(LogicalAggregation.class);
            LogicalAggregation agg = (LogicalAggregation) proj.child();

            assertThat(agg.groupByExprs()).hasSize(1);
            assertThat(agg.aggFunctions()).hasSize(1);
            assertThat(agg.aggFunctions().get(0).type()).isEqualTo(AggFunction.Type.COUNT);
        }

        @Test
        @DisplayName("SELECT with aggregation but no GROUP BY still produces Aggregation")
        void selectWithAggregationWithoutGroupBy() {
            LogicalPlan plan = analyze("SELECT COUNT(id) FROM users");

            assertThat(plan).isInstanceOf(LogicalProjection.class);
            LogicalProjection proj = (LogicalProjection) plan;

            assertThat(proj.child()).isInstanceOf(LogicalAggregation.class);
            LogicalAggregation agg = (LogicalAggregation) proj.child();
            assertThat(agg.groupByExprs()).isEmpty();
            assertThat(agg.aggFunctions()).hasSize(1);
            assertThat(agg.aggFunctions().get(0).type()).isEqualTo(AggFunction.Type.COUNT);
        }

        @Test
        @DisplayName("SELECT with ORDER BY produces Sort above Projection")
        void selectWithOrderBy() {
            LogicalPlan plan = analyze("SELECT * FROM users ORDER BY age DESC");

            assertThat(plan).isInstanceOf(LogicalSort.class);
            LogicalSort sort = (LogicalSort) plan;

            assertThat(sort.orderByExprs()).hasSize(1);
            assertThat(sort.ascending()).hasSize(1);
            assertThat(sort.ascending().get(0)).isFalse();

            assertThat(sort.child()).isInstanceOf(LogicalProjection.class);
        }

        @Test
        @DisplayName("SELECT with ORDER BY ASC produces ascending flag")
        void selectWithOrderByAsc() {
            LogicalPlan plan = analyze("SELECT * FROM users ORDER BY name ASC");

            assertThat(plan).isInstanceOf(LogicalSort.class);
            LogicalSort sort = (LogicalSort) plan;
            assertThat(sort.ascending().get(0)).isTrue();
        }

        @Test
        @DisplayName("SELECT with LIMIT produces Limit at the top")
        void selectWithLimit() {
            LogicalPlan plan = analyze("SELECT * FROM users LIMIT 10");

            assertThat(plan).isInstanceOf(LogicalLimit.class);
            LogicalLimit limit = (LogicalLimit) plan;
            assertThat(limit.count()).isEqualTo(10);
            assertThat(limit.offset()).isEqualTo(0);
        }

        @Test
        @DisplayName("SELECT with LIMIT and OFFSET produces correct Limit values")
        void selectWithLimitAndOffset() {
            LogicalPlan plan = analyze("SELECT * FROM users LIMIT 10 OFFSET 5");

            assertThat(plan).isInstanceOf(LogicalLimit.class);
            LogicalLimit limit = (LogicalLimit) plan;
            assertThat(limit.count()).isEqualTo(10);
            assertThat(limit.offset()).isEqualTo(5);
        }

        @Test
        @DisplayName("SELECT with ORDER BY and LIMIT: Limit wraps Sort wraps Projection")
        void selectWithOrderByAndLimit() {
            LogicalPlan plan = analyze("SELECT * FROM users ORDER BY age LIMIT 5");

            assertThat(plan).isInstanceOf(LogicalLimit.class);
            LogicalLimit limit = (LogicalLimit) plan;
            assertThat(limit.count()).isEqualTo(5);

            assertThat(limit.child()).isInstanceOf(LogicalSort.class);
            LogicalSort sort = (LogicalSort) limit.child();
            assertThat(sort.child()).isInstanceOf(LogicalProjection.class);
        }

        @Test
        @DisplayName("SELECT with alias on column")
        void selectWithColumnAlias() {
            LogicalPlan plan = analyze("SELECT name AS username FROM users");

            assertThat(plan).isInstanceOf(LogicalProjection.class);
            LogicalProjection proj = (LogicalProjection) plan;
            assertThat(proj.aliases().get(0)).isEqualTo("username");
            assertThat(proj.outputSchema().get(0).getName()).isEqualTo("username");
        }

        @Test
        @DisplayName("SELECT from non-existent table throws XDBException")
        void selectFromNonExistentTable() {
            assertThatThrownBy(() -> analyze("SELECT * FROM nonexistent"))
                    .isInstanceOf(io.github.xinfra.lab.xdb.common.XDBException.class)
                    .hasMessageContaining("doesn't exist");
        }

        @Test
        @DisplayName("SELECT with no FROM clause produces Dual")
        void selectDual() {
            LogicalPlan plan = analyze("SELECT 1 + 2");

            assertThat(plan).isInstanceOf(LogicalProjection.class);
            LogicalProjection proj = (LogicalProjection) plan;
            assertThat(proj.child()).isInstanceOf(LogicalDual.class);
        }

        @Test
        @DisplayName("SELECT with multiple aggregates collects all agg functions")
        void selectWithMultipleAggregates() {
            LogicalPlan plan = analyze(
                    "SELECT COUNT(id), SUM(amount), AVG(amount) FROM orders");

            assertThat(plan).isInstanceOf(LogicalProjection.class);
            LogicalProjection proj = (LogicalProjection) plan;
            assertThat(proj.child()).isInstanceOf(LogicalAggregation.class);
            LogicalAggregation agg = (LogicalAggregation) proj.child();

            assertThat(agg.aggFunctions()).hasSize(3);
            assertThat(agg.aggFunctions().get(0).type()).isEqualTo(AggFunction.Type.COUNT);
            assertThat(agg.aggFunctions().get(1).type()).isEqualTo(AggFunction.Type.SUM);
            assertThat(agg.aggFunctions().get(2).type()).isEqualTo(AggFunction.Type.AVG);
        }
    }

    // -----------------------------------------------------------------------
    // INSERT tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("INSERT statements")
    class InsertTests {

        @Test
        @DisplayName("INSERT with explicit columns produces LogicalInsert")
        void insertWithColumns() {
            LogicalPlan plan = analyze(
                    "INSERT INTO users (name, age) VALUES ('Alice', 30)");

            assertThat(plan).isInstanceOf(LogicalInsert.class);
            LogicalInsert ins = (LogicalInsert) plan;

            assertThat(ins.table().getName()).isEqualTo("users");
            assertThat(ins.targetColumns()).hasSize(2);
            assertThat(ins.targetColumns().get(0).getName()).isEqualTo("name");
            assertThat(ins.targetColumns().get(1).getName()).isEqualTo("age");
            assertThat(ins.rows()).hasSize(1);
            assertThat(ins.rows().get(0)).hasSize(2);
        }

        @Test
        @DisplayName("INSERT without column list uses all public columns")
        void insertWithoutColumns() {
            LogicalPlan plan = analyze(
                    "INSERT INTO users VALUES (1, 'Bob', 25, 'bob@test.com')");

            assertThat(plan).isInstanceOf(LogicalInsert.class);
            LogicalInsert ins = (LogicalInsert) plan;

            assertThat(ins.targetColumns()).hasSize(4);
            assertThat(ins.rows()).hasSize(1);
            assertThat(ins.rows().get(0)).hasSize(4);
        }

        @Test
        @DisplayName("INSERT with multiple rows produces multi-row LogicalInsert")
        void insertMultipleRows() {
            LogicalPlan plan = analyze(
                    "INSERT INTO users (name, age) VALUES ('Alice', 30), ('Bob', 25)");

            assertThat(plan).isInstanceOf(LogicalInsert.class);
            LogicalInsert ins = (LogicalInsert) plan;
            assertThat(ins.rows()).hasSize(2);
        }

        @Test
        @DisplayName("INSERT with non-existent column throws XDBException")
        void insertWithBadColumn() {
            assertThatThrownBy(() -> analyze(
                    "INSERT INTO users (nonexistent) VALUES ('val')"))
                    .isInstanceOf(io.github.xinfra.lab.xdb.common.XDBException.class)
                    .hasMessageContaining("Unknown column");
        }
    }

    // -----------------------------------------------------------------------
    // UPDATE tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("UPDATE statements")
    class UpdateTests {

        @Test
        @DisplayName("UPDATE with WHERE produces Update with Selection child")
        void updateWithWhere() {
            LogicalPlan plan = analyze(
                    "UPDATE users SET name = 'Charlie' WHERE id = 1");

            assertThat(plan).isInstanceOf(LogicalUpdate.class);
            LogicalUpdate upd = (LogicalUpdate) plan;

            assertThat(upd.table().getName()).isEqualTo("users");
            assertThat(upd.updateColumns()).hasSize(1);
            assertThat(upd.updateColumns().get(0).getName()).isEqualTo("name");
            assertThat(upd.updateValues()).hasSize(1);

            // Child should be Selection(TableScan)
            assertThat(upd.child()).isInstanceOf(LogicalSelection.class);
            LogicalSelection sel = (LogicalSelection) upd.child();
            assertThat(sel.child()).isInstanceOf(LogicalTableScan.class);
        }

        @Test
        @DisplayName("UPDATE without WHERE has TableScan as child")
        void updateWithoutWhere() {
            LogicalPlan plan = analyze("UPDATE users SET age = 0");

            assertThat(plan).isInstanceOf(LogicalUpdate.class);
            LogicalUpdate upd = (LogicalUpdate) plan;
            assertThat(upd.child()).isInstanceOf(LogicalTableScan.class);
        }

        @Test
        @DisplayName("UPDATE multiple columns produces correct number of update columns")
        void updateMultipleColumns() {
            LogicalPlan plan = analyze(
                    "UPDATE users SET name = 'X', age = 99 WHERE id = 1");

            assertThat(plan).isInstanceOf(LogicalUpdate.class);
            LogicalUpdate upd = (LogicalUpdate) plan;
            assertThat(upd.updateColumns()).hasSize(2);
            assertThat(upd.updateValues()).hasSize(2);
        }
    }

    // -----------------------------------------------------------------------
    // DELETE tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DELETE statements")
    class DeleteTests {

        @Test
        @DisplayName("DELETE with WHERE produces Delete with Selection child")
        void deleteWithWhere() {
            LogicalPlan plan = analyze("DELETE FROM users WHERE id = 1");

            assertThat(plan).isInstanceOf(LogicalDelete.class);
            LogicalDelete del = (LogicalDelete) plan;

            assertThat(del.table().getName()).isEqualTo("users");
            assertThat(del.child()).isInstanceOf(LogicalSelection.class);

            LogicalSelection sel = (LogicalSelection) del.child();
            assertThat(sel.child()).isInstanceOf(LogicalTableScan.class);
        }

        @Test
        @DisplayName("DELETE without WHERE has TableScan as child")
        void deleteWithoutWhere() {
            LogicalPlan plan = analyze("DELETE FROM users");

            assertThat(plan).isInstanceOf(LogicalDelete.class);
            LogicalDelete del = (LogicalDelete) plan;
            assertThat(del.child()).isInstanceOf(LogicalTableScan.class);
        }
    }

    // -----------------------------------------------------------------------
    // SHOW / DESCRIBE tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("SHOW and DESCRIBE statements")
    class ShowDescribeTests {

        @Test
        @DisplayName("SHOW TABLES produces LogicalShowStmt with TABLES type")
        void showTables() {
            LogicalPlan plan = analyze("SHOW TABLES");

            assertThat(plan).isInstanceOf(LogicalShowStmt.class);
            LogicalShowStmt show = (LogicalShowStmt) plan;
            assertThat(show.showType()).isEqualTo(LogicalShowStmt.ShowType.TABLES);
            assertThat(show.databaseName()).isEqualTo(TestSchemaFactory.DB_NAME);
        }

        @Test
        @DisplayName("SHOW DATABASES produces LogicalShowStmt with DATABASES type")
        void showDatabases() {
            LogicalPlan plan = analyze("SHOW DATABASES");

            assertThat(plan).isInstanceOf(LogicalShowStmt.class);
            LogicalShowStmt show = (LogicalShowStmt) plan;
            assertThat(show.showType()).isEqualTo(LogicalShowStmt.ShowType.DATABASES);
        }

        @Test
        @DisplayName("DESCRIBE table produces LogicalShowStmt with COLUMNS type")
        void describeTable() {
            LogicalPlan plan = analyze("DESCRIBE users");

            assertThat(plan).isInstanceOf(LogicalShowStmt.class);
            LogicalShowStmt show = (LogicalShowStmt) plan;
            assertThat(show.showType()).isEqualTo(LogicalShowStmt.ShowType.COLUMNS);
            assertThat(show.tableName()).isEqualTo("users");
        }

        @Test
        @DisplayName("SHOW COLUMNS output schema has Field, Type, Null, Key, Default, Extra")
        void showColumnsOutputSchema() {
            LogicalPlan plan = analyze("DESCRIBE users");

            List<String> colNames = plan.outputSchema().stream()
                    .map(ColumnInfo::getName).toList();
            assertThat(colNames).containsExactly("Field", "Type", "Null", "Key", "Default", "Extra");
        }
    }

    // -----------------------------------------------------------------------
    // Analyzer error handling
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Analyzer without database throws on table resolution")
        void noDatabaseSelected() {
            Analyzer noDB = new Analyzer(infoSchema, null);
            Statement stmt = SQLParser.parse("SELECT * FROM users");
            assertThatThrownBy(() -> noDB.analyze(stmt))
                    .isInstanceOf(io.github.xinfra.lab.xdb.common.XDBException.class)
                    .hasMessageContaining("No database");
        }

        @Test
        @DisplayName("Unsupported statement type throws UnsupportedOperationException")
        void unsupportedStatement() {
            Statement stmt = SQLParser.parse("CREATE TABLE t (id INT)");
            assertThatThrownBy(() -> analyzer.analyze(stmt))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // -----------------------------------------------------------------------
    // Explain output tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Explain output")
    class ExplainTests {

        @Test
        @DisplayName("explain() produces non-empty string containing plan node names")
        void explainBasic() {
            LogicalPlan plan = analyze("SELECT * FROM users WHERE age > 18");
            String explanation = plan.explain(0);

            assertThat(explanation).isNotEmpty();
            assertThat(explanation).contains("Projection");
            assertThat(explanation).contains("Selection");
            assertThat(explanation).contains("TableScan");
        }
    }
}
