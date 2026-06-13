package io.github.xinfra.lab.xdb.executor.util;

import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainExecutorTest {

    /**
     * A simple stub PhysicalPlan that returns a fixed explain string.
     */
    private static class StubPhysicalPlan implements PhysicalPlan {
        private final String explainText;

        StubPhysicalPlan(String explainText) {
            this.explainText = explainText;
        }

        @Override
        public List<PhysicalPlan> children() {
            return Collections.emptyList();
        }

        @Override
        public List<ColumnInfo> outputSchema() {
            return Collections.emptyList();
        }

        @Override
        public String explain(int indent) {
            return explainText;
        }

        @Override
        public double estimatedCost() {
            return 0;
        }

        @Override
        public long estimatedRowCount() {
            return 0;
        }
    }

    @Test
    void returnsPlanDescriptionAsRows() throws Exception {
        String planText = "PhysicalProjection\n  PhysicalTableScan(table=users)";
        PhysicalPlan plan = new StubPhysicalPlan(planText);

        ExplainExecutor explain = new ExplainExecutor(plan);
        explain.open();

        Row row1 = explain.next();
        assertThat(row1).isNotNull();
        assertThat(((Datum.StringDatum) row1.get(0)).value()).isEqualTo("PhysicalProjection");

        Row row2 = explain.next();
        assertThat(row2).isNotNull();
        assertThat(((Datum.StringDatum) row2.get(0)).value()).isEqualTo("  PhysicalTableScan(table=users)");

        assertThat(explain.next()).isNull();
        explain.close();
    }

    @Test
    void singleLinePlan() throws Exception {
        String planText = "PhysicalDual";
        PhysicalPlan plan = new StubPhysicalPlan(planText);

        ExplainExecutor explain = new ExplainExecutor(plan);
        explain.open();

        Row row = explain.next();
        assertThat(row).isNotNull();
        assertThat(((Datum.StringDatum) row.get(0)).value()).isEqualTo("PhysicalDual");

        assertThat(explain.next()).isNull();
        explain.close();
    }

    @Test
    void multiLinePlan() throws Exception {
        String planText = "Line1\nLine2\nLine3\nLine4";
        PhysicalPlan plan = new StubPhysicalPlan(planText);

        ExplainExecutor explain = new ExplainExecutor(plan);
        explain.open();

        List<String> lines = new ArrayList<>();
        Row row;
        while ((row = explain.next()) != null) {
            lines.add(((Datum.StringDatum) row.get(0)).value());
        }

        assertThat(lines).containsExactly("Line1", "Line2", "Line3", "Line4");
        explain.close();
    }

    @Test
    void outputSchemaHasSinglePlanColumn() {
        PhysicalPlan plan = new StubPhysicalPlan("test");
        ExplainExecutor explain = new ExplainExecutor(plan);

        List<ColumnInfo> schema = explain.outputSchema();
        assertThat(schema).hasSize(1);
        assertThat(schema.get(0).getName()).isEqualTo("Plan");
        assertThat(schema.get(0).getType()).isEqualTo(DataType.VARCHAR);
    }

    @Test
    void eachRowHasSingleColumn() throws Exception {
        PhysicalPlan plan = new StubPhysicalPlan("A\nB");
        ExplainExecutor explain = new ExplainExecutor(plan);
        explain.open();

        Row row = explain.next();
        assertThat(row).isNotNull();
        assertThat(row.size()).isEqualTo(1);

        explain.close();
    }
}
