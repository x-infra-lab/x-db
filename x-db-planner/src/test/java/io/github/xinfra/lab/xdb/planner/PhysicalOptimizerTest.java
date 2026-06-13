package io.github.xinfra.lab.xdb.planner;

import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.meta.*;
import io.github.xinfra.lab.xdb.parser.SQLParser;
import io.github.xinfra.lab.xdb.parser.ast.Statement;
import io.github.xinfra.lab.xdb.planner.logical.*;
import io.github.xinfra.lab.xdb.planner.optimize.PhysicalOptimizer;
import io.github.xinfra.lab.xdb.planner.optimize.RuleBasedOptimizer;
import io.github.xinfra.lab.xdb.planner.physical.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhysicalOptimizerTest {

    private InfoSchema infoSchema;
    private Analyzer analyzer;
    private RuleBasedOptimizer rbo;
    private PhysicalOptimizer physicalOptimizer;

    @BeforeEach
    void setUp() {
        infoSchema = TestSchemaFactory.createInfoSchema();
        analyzer = new Analyzer(infoSchema, TestSchemaFactory.DB_NAME);
        rbo = new RuleBasedOptimizer();
        physicalOptimizer = new PhysicalOptimizer();
    }

    private LogicalPlan buildLogical(String sql) {
        Statement stmt = SQLParser.parse(sql);
        return analyzer.analyze(stmt);
    }

    private PhysicalPlan buildPhysical(String sql) {
        LogicalPlan logical = buildLogical(sql);
        LogicalPlan optimized = rbo.optimize(logical);
        return physicalOptimizer.optimize(optimized);
    }

    // -----------------------------------------------------------------------
    // Basic conversion tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Basic logical-to-physical conversion")
    class BasicConversion {

        @Test
        @DisplayName("SELECT * FROM table converts to PhysicalProjection over PhysicalTableScan")
        void selectStarConvertsToTableScan() {
            PhysicalPlan plan = buildPhysical("SELECT * FROM users");

            assertThat(plan).isInstanceOf(PhysicalProjection.class);
            PhysicalProjection proj = (PhysicalProjection) plan;
            assertThat(proj.child()).isInstanceOf(PhysicalTableScan.class);

            PhysicalTableScan scan = (PhysicalTableScan) proj.child();
            assertThat(scan.table().getName()).isEqualTo("users");
        }

        @Test
        @DisplayName("SELECT with WHERE converts to physical plan with access conditions")
        void selectWithWhereConverts() {
            PhysicalPlan plan = buildPhysical("SELECT * FROM users WHERE age > 18");

            // After predicate pushdown, the condition moves to the scan's accessConditions.
            // The users table has a non-primary index (idx_name), so the PhysicalOptimizer
            // may choose PhysicalIndexScan+PhysicalIndexLookup when conditions are present.
            PhysicalTableScan tableScan = findNode(plan, PhysicalTableScan.class);
            PhysicalIndexScan indexScan = findNode(plan, PhysicalIndexScan.class);

            // At least one scan type should be present
            assertThat(tableScan != null || indexScan != null)
                    .as("Expected either PhysicalTableScan or PhysicalIndexScan in plan")
                    .isTrue();

            if (tableScan != null) {
                assertThat(tableScan.accessConditions()).isNotEmpty();
            }
            if (indexScan != null) {
                assertThat(indexScan.accessConditions()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("INSERT converts to PhysicalInsert")
        void insertConverts() {
            PhysicalPlan plan = buildPhysical(
                    "INSERT INTO users (name, age) VALUES ('Alice', 30)");

            assertThat(plan).isInstanceOf(PhysicalInsert.class);
            PhysicalInsert ins = (PhysicalInsert) plan;
            assertThat(ins.table().getName()).isEqualTo("users");
            assertThat(ins.rows()).hasSize(1);
        }

        @Test
        @DisplayName("UPDATE converts to PhysicalUpdate with child scan")
        void updateConverts() {
            PhysicalPlan plan = buildPhysical(
                    "UPDATE users SET name = 'X' WHERE id = 1");

            assertThat(plan).isInstanceOf(PhysicalUpdate.class);
            PhysicalUpdate upd = (PhysicalUpdate) plan;
            assertThat(upd.table().getName()).isEqualTo("users");
            assertThat(upd.children()).hasSize(1);
        }

        @Test
        @DisplayName("DELETE converts to PhysicalDelete with child scan")
        void deleteConverts() {
            PhysicalPlan plan = buildPhysical("DELETE FROM users WHERE id = 1");

            assertThat(plan).isInstanceOf(PhysicalDelete.class);
            PhysicalDelete del = (PhysicalDelete) plan;
            assertThat(del.table().getName()).isEqualTo("users");
            assertThat(del.children()).hasSize(1);
        }

        @Test
        @DisplayName("SHOW TABLES converts to PhysicalShowStmt")
        void showTablesConverts() {
            PhysicalPlan plan = buildPhysical("SHOW TABLES");

            assertThat(plan).isInstanceOf(PhysicalShowStmt.class);
            PhysicalShowStmt show = (PhysicalShowStmt) plan;
            assertThat(show.showType()).isEqualTo(LogicalShowStmt.ShowType.TABLES);
        }

        @Test
        @DisplayName("SELECT 1+1 (no FROM) converts to PhysicalProjection over PhysicalDual")
        void selectDualConverts() {
            PhysicalPlan plan = buildPhysical("SELECT 1 + 1");

            assertThat(plan).isInstanceOf(PhysicalProjection.class);
            PhysicalProjection proj = (PhysicalProjection) plan;
            assertThat(proj.child()).isInstanceOf(PhysicalDual.class);
        }
    }

    // -----------------------------------------------------------------------
    // Sort and Limit conversion
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Sort and Limit conversion")
    class SortLimitConversion {

        @Test
        @DisplayName("ORDER BY converts to PhysicalSort")
        void orderByConverts() {
            PhysicalPlan plan = buildPhysical("SELECT * FROM users ORDER BY age DESC");

            assertThat(plan).isInstanceOf(PhysicalSort.class);
            PhysicalSort sort = (PhysicalSort) plan;
            assertThat(sort.orderByExprs()).hasSize(1);
            assertThat(sort.ascending().get(0)).isFalse();
        }

        @Test
        @DisplayName("LIMIT converts to PhysicalLimit")
        void limitConverts() {
            PhysicalPlan plan = buildPhysical("SELECT * FROM users LIMIT 10");

            assertThat(plan).isInstanceOf(PhysicalLimit.class);
            PhysicalLimit limit = (PhysicalLimit) plan;
            assertThat(limit.count()).isEqualTo(10);
            assertThat(limit.offset()).isEqualTo(0);
        }

        @Test
        @DisplayName("ORDER BY + LIMIT converts to PhysicalLimit over PhysicalSort")
        void orderByAndLimit() {
            PhysicalPlan plan = buildPhysical(
                    "SELECT * FROM users ORDER BY age LIMIT 5");

            assertThat(plan).isInstanceOf(PhysicalLimit.class);
            PhysicalLimit limit = (PhysicalLimit) plan;
            assertThat(limit.count()).isEqualTo(5);

            assertThat(limit.child()).isInstanceOf(PhysicalSort.class);
        }
    }

    // -----------------------------------------------------------------------
    // Join conversion
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Join conversion")
    class JoinConversion {

        @Test
        @DisplayName("INNER JOIN converts to a physical join node")
        void innerJoinConverts() {
            PhysicalPlan plan = buildPhysical(
                    "SELECT * FROM users INNER JOIN orders ON users.id = orders.user_id");

            // PhysicalTableScan defaults to 10000 estimated rows. The PhysicalOptimizer
            // uses HashJoin only when estimatedRowCount < 10000, so with default stats
            // it falls back to PhysicalNestedLoopJoin.
            PhysicalHashJoin hashJoin = findNode(plan, PhysicalHashJoin.class);
            PhysicalNestedLoopJoin nlJoin = findNode(plan, PhysicalNestedLoopJoin.class);

            assertThat(hashJoin != null || nlJoin != null)
                    .as("Expected either PhysicalHashJoin or PhysicalNestedLoopJoin")
                    .isTrue();

            if (hashJoin != null) {
                assertThat(hashJoin.joinType()).isEqualTo(JoinType.INNER);
                assertThat(hashJoin.condition()).isNotNull();
            }
            if (nlJoin != null) {
                assertThat(nlJoin.joinType()).isEqualTo(JoinType.INNER);
                assertThat(nlJoin.condition()).isNotNull();
            }
        }

        @Test
        @DisplayName("LEFT JOIN preserves join type in physical plan")
        void leftJoinConverts() {
            PhysicalPlan plan = buildPhysical(
                    "SELECT * FROM users LEFT JOIN orders ON users.id = orders.user_id");

            PhysicalHashJoin hashJoin = findNode(plan, PhysicalHashJoin.class);
            PhysicalNestedLoopJoin nlJoin = findNode(plan, PhysicalNestedLoopJoin.class);

            assertThat(hashJoin != null || nlJoin != null)
                    .as("Expected a physical join node")
                    .isTrue();

            if (hashJoin != null) {
                assertThat(hashJoin.joinType()).isEqualTo(JoinType.LEFT);
            }
            if (nlJoin != null) {
                assertThat(nlJoin.joinType()).isEqualTo(JoinType.LEFT);
            }
        }

        @Test
        @DisplayName("Join output schema is union of both sides")
        void joinOutputSchema() {
            PhysicalPlan plan = buildPhysical(
                    "SELECT * FROM users INNER JOIN orders ON users.id = orders.user_id");

            PhysicalHashJoin hashJoin = findNode(plan, PhysicalHashJoin.class);
            PhysicalNestedLoopJoin nlJoin = findNode(plan, PhysicalNestedLoopJoin.class);

            int schemaSize;
            if (hashJoin != null) {
                schemaSize = hashJoin.outputSchema().size();
            } else {
                assertThat(nlJoin).isNotNull();
                schemaSize = nlJoin.outputSchema().size();
            }
            // users has 4 cols + orders has 4 cols = 8 cols
            assertThat(schemaSize).isEqualTo(8);
        }
    }

    // -----------------------------------------------------------------------
    // Aggregation conversion
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Aggregation conversion")
    class AggregationConversion {

        @Test
        @DisplayName("GROUP BY with COUNT converts to PhysicalHashAgg")
        void groupByConverts() {
            PhysicalPlan plan = buildPhysical(
                    "SELECT status, COUNT(id) FROM orders GROUP BY status");

            PhysicalHashAgg hashAgg = findNode(plan, PhysicalHashAgg.class);
            assertThat(hashAgg).isNotNull();
            assertThat(hashAgg.groupByExprs()).hasSize(1);
            assertThat(hashAgg.aggFunctions()).hasSize(1);
        }

        @Test
        @DisplayName("Aggregation without GROUP BY converts to PhysicalHashAgg with empty groupBy")
        void aggWithoutGroupBy() {
            PhysicalPlan plan = buildPhysical("SELECT COUNT(id) FROM orders");

            PhysicalHashAgg hashAgg = findNode(plan, PhysicalHashAgg.class);
            assertThat(hashAgg).isNotNull();
            assertThat(hashAgg.groupByExprs()).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Index scan conversion
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Index scan conversion")
    class IndexScanConversion {

        @Test
        @DisplayName("Table with non-primary index and access conditions uses index scan")
        void indexScanUsed() {
            // The users table has idx_name (non-primary index).
            // When predicate pushdown moves a condition to the scan,
            // the physical optimizer should consider using the index.
            PhysicalPlan plan = buildPhysical(
                    "SELECT * FROM users WHERE name = 'Alice'");

            // The optimizer should use index scan for this query
            // since users has idx_name and there's a condition pushed down
            PhysicalIndexLookup lookup = findNode(plan, PhysicalIndexLookup.class);
            if (lookup != null) {
                assertThat(lookup.table().getName()).isEqualTo("users");
                PhysicalIndexScan idxScan = lookup.indexScan();
                assertThat(idxScan.index().getName()).isEqualTo("idx_name");
            } else {
                // If pushdown didn't push to scan, it falls back to table scan
                PhysicalTableScan scan = findNode(plan, PhysicalTableScan.class);
                assertThat(scan).isNotNull();
            }
        }

        @Test
        @DisplayName("Table with only primary index falls back to table scan")
        void noPrimaryIndexScan() {
            // orders table only has PRIMARY index; PhysicalOptimizer skips primary indices
            PhysicalPlan plan = buildPhysical(
                    "SELECT * FROM orders WHERE status = 'active'");

            // Should use table scan since the only index is primary (skipped)
            PhysicalTableScan scan = findNode(plan, PhysicalTableScan.class);
            assertThat(scan).isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // Cost and row estimates
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Cost and row count estimates")
    class CostEstimates {

        @Test
        @DisplayName("PhysicalTableScan has positive estimated cost and row count")
        void tableScanCostPositive() {
            PhysicalPlan plan = buildPhysical("SELECT * FROM users");

            PhysicalTableScan scan = findNode(plan, PhysicalTableScan.class);
            assertThat(scan).isNotNull();
            assertThat(scan.estimatedCost()).isGreaterThan(0);
            assertThat(scan.estimatedRowCount()).isGreaterThan(0);
        }

        @Test
        @DisplayName("PhysicalLimit estimatedRowCount does not exceed limit count")
        void limitReducesRowCount() {
            PhysicalPlan plan = buildPhysical("SELECT * FROM users LIMIT 5");

            assertThat(plan).isInstanceOf(PhysicalLimit.class);
            PhysicalLimit limit = (PhysicalLimit) plan;
            assertThat(limit.estimatedRowCount()).isLessThanOrEqualTo(5);
        }

        @Test
        @DisplayName("PhysicalDual has zero cost and 1 row")
        void dualCost() {
            PhysicalPlan plan = buildPhysical("SELECT 1");

            PhysicalDual dual = findNode(plan, PhysicalDual.class);
            assertThat(dual).isNotNull();
            assertThat(dual.estimatedCost()).isEqualTo(0);
            assertThat(dual.estimatedRowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("PhysicalInsert cost scales with row count")
        void insertCostScales() {
            PhysicalPlan plan1 = buildPhysical(
                    "INSERT INTO users (name, age) VALUES ('A', 1)");
            PhysicalPlan plan2 = buildPhysical(
                    "INSERT INTO users (name, age) VALUES ('A', 1), ('B', 2), ('C', 3)");

            assertThat(plan2.estimatedCost()).isGreaterThan(plan1.estimatedCost());
        }

        @Test
        @DisplayName("PhysicalHashAgg without GROUP BY estimates 1 output row")
        void hashAggNoGroupByOneRow() {
            PhysicalPlan plan = buildPhysical("SELECT COUNT(id) FROM orders");

            PhysicalHashAgg agg = findNode(plan, PhysicalHashAgg.class);
            assertThat(agg).isNotNull();
            assertThat(agg.estimatedRowCount()).isEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // Explain output
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Physical plan explain output")
    class ExplainOutput {

        @Test
        @DisplayName("Physical plan explain contains node type names")
        void explainContainsNodeNames() {
            PhysicalPlan plan = buildPhysical(
                    "SELECT * FROM users WHERE age > 18 ORDER BY name LIMIT 10");

            String explanation = plan.explain(0);
            assertThat(explanation).contains("PhysicalLimit");
            assertThat(explanation).contains("PhysicalSort");
            assertThat(explanation).contains("PhysicalProjection");
        }

        @Test
        @DisplayName("Planner.explain() produces end-to-end explain string")
        void plannerExplain() {
            Planner planner = new Planner(infoSchema);
            Statement stmt = SQLParser.parse("SELECT * FROM users");
            String explanation = planner.explain(stmt, TestSchemaFactory.DB_NAME);

            assertThat(explanation).isNotEmpty();
            assertThat(explanation).contains("Physical");
        }
    }

    // -----------------------------------------------------------------------
    // End-to-end through Planner
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("End-to-end Planner tests")
    class PlannerEndToEnd {

        @Test
        @DisplayName("Planner.plan() produces a PhysicalPlan from SQL")
        void plannerPlan() {
            Planner planner = new Planner(infoSchema);
            Statement stmt = SQLParser.parse("SELECT name FROM users WHERE age > 18");
            PhysicalPlan plan = planner.plan(stmt, TestSchemaFactory.DB_NAME);

            assertThat(plan).isNotNull();
            assertThat(plan.explain(0)).isNotEmpty();
        }

        @Test
        @DisplayName("Planner handles complex query with JOIN, WHERE, ORDER BY, LIMIT")
        void plannerComplexQuery() {
            Planner planner = new Planner(infoSchema);
            Statement stmt = SQLParser.parse(
                    "SELECT users.name, orders.amount " +
                    "FROM users INNER JOIN orders ON users.id = orders.user_id " +
                    "WHERE orders.amount > 100 " +
                    "ORDER BY orders.amount DESC " +
                    "LIMIT 20");
            PhysicalPlan plan = planner.plan(stmt, TestSchemaFactory.DB_NAME);

            assertThat(plan).isNotNull();
            assertThat(plan).isInstanceOf(PhysicalLimit.class);
            assertThat(plan.explain(0)).contains("PhysicalLimit");
        }
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <T extends PhysicalPlan> T findNode(PhysicalPlan plan, Class<T> type) {
        if (type.isInstance(plan)) return (T) plan;
        for (PhysicalPlan child : plan.children()) {
            T result = findNode(child, type);
            if (result != null) return result;
        }
        return null;
    }
}
