package io.github.xinfra.lab.xdb.executor.util;

import io.github.xinfra.lab.xdb.expression.Row;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DualExecutorTest {

    @Test
    void returnsSingleEmptyRow() throws Exception {
        DualExecutor dual = new DualExecutor();
        dual.open();

        Row row = dual.next();
        assertThat(row).isNotNull();
        assertThat(row.size()).isEqualTo(0);

        dual.close();
    }

    @Test
    void returnsNullAfterFirstRow() throws Exception {
        DualExecutor dual = new DualExecutor();
        dual.open();

        dual.next(); // consume the single row
        assertThat(dual.next()).isNull();

        dual.close();
    }

    @Test
    void canBeReopened() throws Exception {
        DualExecutor dual = new DualExecutor();

        // First open
        dual.open();
        assertThat(dual.next()).isNotNull();
        assertThat(dual.next()).isNull();
        dual.close();

        // Second open - should reset state
        dual.open();
        assertThat(dual.next()).isNotNull();
        assertThat(dual.next()).isNull();
        dual.close();
    }

    @Test
    void outputSchemaIsEmpty() {
        DualExecutor dual = new DualExecutor();
        assertThat(dual.outputSchema()).isEmpty();
    }

    @Test
    void multipleNextCallsAfterExhaustion() throws Exception {
        DualExecutor dual = new DualExecutor();
        dual.open();

        dual.next(); // consume
        assertThat(dual.next()).isNull();
        assertThat(dual.next()).isNull();
        assertThat(dual.next()).isNull();

        dual.close();
    }
}
