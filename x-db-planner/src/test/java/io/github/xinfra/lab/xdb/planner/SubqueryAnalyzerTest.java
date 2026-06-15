package io.github.xinfra.lab.xdb.planner;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.parser.SQLParser;
import io.github.xinfra.lab.xdb.parser.ast.Statement;
import io.github.xinfra.lab.xdb.planner.logical.LogicalPlan;
import io.github.xinfra.lab.xdb.planner.logical.LogicalProjection;
import io.github.xinfra.lab.xdb.planner.logical.LogicalSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubqueryAnalyzerTest {

    private InfoSchema infoSchema;

    @BeforeEach
    void setUp() {
        infoSchema = TestSchemaFactory.createInfoSchema();
    }

    private LogicalPlan analyze(String sql) {
        Statement stmt = SQLParser.parse(sql);
        Analyzer analyzer = new Analyzer(infoSchema, TestSchemaFactory.DB_NAME);
        return analyzer.analyze(stmt);
    }

    @Test
    @DisplayName("Scalar subquery in WHERE produces ScalarSubqueryRef")
    void scalarSubqueryInWhere() {
        LogicalPlan plan = analyze("SELECT * FROM users WHERE age = (SELECT MAX(age) FROM users)");
        LogicalSelection sel = findSelection(plan);
        assertThat(sel).isNotNull();
        assertThat(containsSubqueryRef(sel.conditions(), ScalarSubqueryRef.class)).isTrue();
    }

    @Test
    @DisplayName("IN subquery produces InSubqueryRef")
    void inSubquery() {
        LogicalPlan plan = analyze("SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)");
        LogicalSelection sel = findSelection(plan);
        assertThat(sel).isNotNull();
        assertThat(containsSubqueryRef(sel.conditions(), InSubqueryRef.class)).isTrue();
    }

    @Test
    @DisplayName("NOT IN subquery produces InSubqueryRef with not=true")
    void notInSubquery() {
        LogicalPlan plan = analyze("SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM orders)");
        LogicalSelection sel = findSelection(plan);
        assertThat(sel).isNotNull();
        boolean found = false;
        for (Expression cond : sel.conditions()) {
            if (cond instanceof InSubqueryRef ref) {
                assertThat(ref.isNot()).isTrue();
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("EXISTS subquery produces ExistsSubqueryRef")
    void existsSubquery() {
        LogicalPlan plan = analyze("SELECT * FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE user_id = 1)");
        LogicalSelection sel = findSelection(plan);
        assertThat(sel).isNotNull();
        assertThat(containsSubqueryRef(sel.conditions(), ExistsSubqueryRef.class)).isTrue();
    }

    @Test
    @DisplayName("Scalar subquery in SELECT list produces ScalarSubqueryRef")
    void scalarSubqueryInProjection() {
        LogicalPlan plan = analyze("SELECT name, (SELECT COUNT(id) FROM orders) FROM users");
        LogicalProjection proj = findProjection(plan);
        assertThat(proj).isNotNull();
        assertThat(containsSubqueryRef(proj.expressions(), ScalarSubqueryRef.class)).isTrue();
    }

    private LogicalSelection findSelection(LogicalPlan plan) {
        if (plan instanceof LogicalSelection sel) return sel;
        if (plan instanceof LogicalProjection proj) return findSelection(proj.child());
        return null;
    }

    private LogicalProjection findProjection(LogicalPlan plan) {
        if (plan instanceof LogicalProjection proj) return proj;
        return null;
    }

    private boolean containsSubqueryRef(List<Expression> exprs, Class<?> refType) {
        for (Expression expr : exprs) {
            if (refType.isInstance(expr)) return true;
            if (expr instanceof io.github.xinfra.lab.xdb.expression.BinaryOp op) {
                if (refType.isInstance(op.left()) || refType.isInstance(op.right())) return true;
            }
        }
        return false;
    }
}
