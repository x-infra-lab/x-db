package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecuteResultTest {

    // -----------------------------------------------------------------------
    // Query results
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Query results")
    class QueryResults {

        @Test
        @DisplayName("query result has isQuery=true")
        void isQuery() {
            ColumnInfo col = new ColumnInfo();
            col.setName("id");
            col.setType(DataType.BIGINT);
            Row row = new Row(new Datum[]{Datum.of(1L)});

            ExecuteResult result = ExecuteResult.query(List.of(col), List.of(row));
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getColumns()).hasSize(1);
            assertThat(result.getRows()).hasSize(1);
            assertThat(result.getAffectedRows()).isEqualTo(0);
            assertThat(result.getLastInsertId()).isEqualTo(0);
        }

        @Test
        @DisplayName("empty query result")
        void emptyQuery() {
            ExecuteResult result = ExecuteResult.query(List.of(), List.of());
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getColumns()).isEmpty();
            assertThat(result.getRows()).isEmpty();
        }

        @Test
        @DisplayName("multi-row query result")
        void multiRow() {
            ColumnInfo col = new ColumnInfo();
            col.setName("name");
            col.setType(DataType.VARCHAR);

            Row r1 = new Row(new Datum[]{Datum.of("alice")});
            Row r2 = new Row(new Datum[]{Datum.of("bob")});

            ExecuteResult result = ExecuteResult.query(List.of(col), List.of(r1, r2));
            assertThat(result.getRows()).hasSize(2);
        }
    }

    // -----------------------------------------------------------------------
    // DML results
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DML results")
    class DmlResults {

        @Test
        @DisplayName("dml result has isQuery=false")
        void isNotQuery() {
            ExecuteResult result = ExecuteResult.dml(5, 42);
            assertThat(result.isQuery()).isFalse();
            assertThat(result.getAffectedRows()).isEqualTo(5);
            assertThat(result.getLastInsertId()).isEqualTo(42);
            assertThat(result.getColumns()).isEmpty();
            assertThat(result.getRows()).isEmpty();
        }

        @Test
        @DisplayName("dml with zero affected rows")
        void zeroAffected() {
            ExecuteResult result = ExecuteResult.dml(0, 0);
            assertThat(result.getAffectedRows()).isEqualTo(0);
            assertThat(result.getLastInsertId()).isEqualTo(0);
        }
    }

    // -----------------------------------------------------------------------
    // OK results
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("OK results")
    class OkResults {

        @Test
        @DisplayName("ok() returns non-query result")
        void ok() {
            ExecuteResult result = ExecuteResult.ok();
            assertThat(result.isQuery()).isFalse();
            assertThat(result.getAffectedRows()).isEqualTo(0);
            assertThat(result.getLastInsertId()).isEqualTo(0);
            assertThat(result.getMessage()).isNull();
        }

        @Test
        @DisplayName("ok(message) preserves the message")
        void okWithMessage() {
            ExecuteResult result = ExecuteResult.ok("Table created");
            assertThat(result.isQuery()).isFalse();
            assertThat(result.getMessage()).isEqualTo("Table created");
        }
    }
}
