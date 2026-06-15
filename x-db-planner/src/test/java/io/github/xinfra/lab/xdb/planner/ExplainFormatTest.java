package io.github.xinfra.lab.xdb.planner;

import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.parser.SQLParser;
import io.github.xinfra.lab.xdb.parser.ast.Statement;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainFormatTest {

    private InfoSchema infoSchema;

    @BeforeEach
    void setUp() {
        infoSchema = TestSchemaFactory.createInfoSchema();
    }

    private String explain(String sql) {
        Statement stmt = SQLParser.parse(sql);
        Planner planner = new Planner(infoSchema);
        PhysicalPlan plan = planner.plan(stmt, TestSchemaFactory.DB_NAME);
        return plan.explain(0);
    }

    @Test
    @DisplayName("Simple SELECT explain includes cost and rows")
    void simpleSelectHasCostAndRows() {
        String output = explain("SELECT * FROM users");
        assertThat(output).contains("cost=");
        assertThat(output).contains("rows=");
    }

    @Test
    @DisplayName("SELECT with WHERE includes cost on all nodes")
    void selectWithWhereHasCostOnAllNodes() {
        String output = explain("SELECT name FROM users WHERE age > 18");
        for (String line : output.split("\n")) {
            assertThat(line).contains("cost=");
            assertThat(line).contains("rows=");
        }
    }

    @Test
    @DisplayName("JOIN explain includes cost and rows")
    void joinHasCostAndRows() {
        String output = explain(
                "SELECT * FROM users INNER JOIN orders ON users.id = orders.user_id");
        assertThat(output).contains("cost=");
        assertThat(output).contains("rows=");
    }

    @Test
    @DisplayName("Aggregation explain includes cost and rows")
    void aggregationHasCostAndRows() {
        String output = explain(
                "SELECT status, COUNT(id) FROM orders GROUP BY status");
        assertThat(output).contains("cost=");
        assertThat(output).contains("rows=");
    }

    @Test
    @DisplayName("ORDER BY explain includes cost and rows")
    void orderByHasCostAndRows() {
        String output = explain("SELECT * FROM users ORDER BY age");
        assertThat(output).contains("cost=");
        assertThat(output).contains("rows=");
    }

    @Test
    @DisplayName("LIMIT explain includes cost and rows")
    void limitHasCostAndRows() {
        String output = explain("SELECT * FROM users LIMIT 10");
        assertThat(output).contains("cost=");
        assertThat(output).contains("rows=");
    }

    @Test
    @DisplayName("SELECT 1+1 dual explain includes cost and rows")
    void dualHasCostAndRows() {
        String output = explain("SELECT 1+1");
        assertThat(output).contains("cost=");
        assertThat(output).contains("rows=");
    }
}
