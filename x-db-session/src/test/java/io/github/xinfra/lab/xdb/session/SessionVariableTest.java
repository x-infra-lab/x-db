package io.github.xinfra.lab.xdb.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionVariableTest {

    private SessionVariable vars;

    @BeforeEach
    void setUp() {
        vars = new SessionVariable();
    }

    // -----------------------------------------------------------------------
    // Defaults
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Default values")
    class Defaults {

        @Test
        void charset() {
            assertThat(vars.getCharset()).isEqualTo("utf8mb4");
            assertThat(vars.get("charset")).isEqualTo("utf8mb4");
        }

        @Test
        void collation() {
            assertThat(vars.getCollation()).isEqualTo("utf8mb4_general_ci");
            assertThat(vars.get("collation")).isEqualTo("utf8mb4_general_ci");
        }

        @Test
        void autoCommit() {
            assertThat(vars.isAutoCommit()).isTrue();
            assertThat(vars.get("autocommit")).isEqualTo("ON");
        }

        @Test
        void transactionIsolation() {
            assertThat(vars.getTransactionIsolation()).isEqualTo("REPEATABLE-READ");
        }

        @Test
        void queryTimeout() {
            assertThat(vars.getQueryTimeout()).isEqualTo(0);
        }

        @Test
        void readOnly() {
            assertThat(vars.isReadOnly()).isFalse();
            assertThat(vars.get("transaction_read_only")).isEqualTo("OFF");
        }

        @Test
        void memQuotaQuery() {
            assertThat(vars.getMemQuotaQuery()).isEqualTo(1L << 30);
        }

        @Test
        void txnTimeout() {
            assertThat(vars.getTxnTimeout()).isEqualTo(60_000);
        }
    }

    // -----------------------------------------------------------------------
    // Charset aliases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Charset variable aliases")
    class CharsetAliases {

        @Test
        void characterSetClient() {
            vars.set("character_set_client", "latin1");
            assertThat(vars.get("character_set_client")).isEqualTo("latin1");
            assertThat(vars.getCharset()).isEqualTo("latin1");
        }

        @Test
        void characterSetConnection() {
            vars.set("character_set_connection", "gbk");
            assertThat(vars.get("character_set_connection")).isEqualTo("gbk");
        }

        @Test
        void characterSetResults() {
            vars.set("character_set_results", "utf8");
            assertThat(vars.get("character_set_results")).isEqualTo("utf8");
        }

        @Test
        void collationConnection() {
            vars.set("collation_connection", "utf8_bin");
            assertThat(vars.get("collation_connection")).isEqualTo("utf8_bin");
            assertThat(vars.getCollation()).isEqualTo("utf8_bin");
        }
    }

    // -----------------------------------------------------------------------
    // Boolean parsing (toBool)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Boolean variable parsing")
    class BoolParsing {

        @Test
        void onValue() {
            vars.set("autocommit", "ON");
            assertThat(vars.isAutoCommit()).isTrue();
        }

        @Test
        void offValue() {
            vars.set("autocommit", "OFF");
            assertThat(vars.isAutoCommit()).isFalse();
        }

        @Test
        void oneValue() {
            vars.set("autocommit", "1");
            assertThat(vars.isAutoCommit()).isTrue();
        }

        @Test
        void zeroValue() {
            vars.set("autocommit", "0");
            assertThat(vars.isAutoCommit()).isFalse();
        }

        @Test
        void trueValue() {
            vars.set("autocommit", "TRUE");
            assertThat(vars.isAutoCommit()).isTrue();
        }

        @Test
        void falseValue() {
            vars.set("autocommit", "false");
            assertThat(vars.isAutoCommit()).isFalse();
        }

        @Test
        void yesValue() {
            vars.set("autocommit", "YES");
            assertThat(vars.isAutoCommit()).isTrue();
        }

        @Test
        void nullValue() {
            vars.set("autocommit", null);
            assertThat(vars.isAutoCommit()).isFalse();
        }

        @Test
        void caseInsensitive() {
            vars.set("autocommit", "on");
            assertThat(vars.isAutoCommit()).isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // Transaction isolation aliases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Transaction isolation aliases")
    class IsolationAliases {

        @Test
        void transactionIsolation() {
            vars.set("transaction_isolation", "READ-COMMITTED");
            assertThat(vars.get("transaction_isolation")).isEqualTo("READ-COMMITTED");
            assertThat(vars.getTransactionIsolation()).isEqualTo("READ-COMMITTED");
        }

        @Test
        void txIsolation() {
            vars.set("tx_isolation", "SERIALIZABLE");
            assertThat(vars.get("tx_isolation")).isEqualTo("SERIALIZABLE");
        }
    }

    // -----------------------------------------------------------------------
    // Read-only aliases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Read-only aliases")
    class ReadOnlyAliases {

        @Test
        void transactionReadOnly() {
            vars.set("transaction_read_only", "ON");
            assertThat(vars.isReadOnly()).isTrue();
            assertThat(vars.get("transaction_read_only")).isEqualTo("ON");
        }

        @Test
        void txReadOnly() {
            vars.set("tx_read_only", "1");
            assertThat(vars.isReadOnly()).isTrue();
            assertThat(vars.get("tx_read_only")).isEqualTo("ON");
        }
    }

    // -----------------------------------------------------------------------
    // Numeric variables
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Numeric variables")
    class NumericVars {

        @Test
        void queryTimeout() {
            vars.set("query_timeout", "5000");
            assertThat(vars.getQueryTimeout()).isEqualTo(5000);
            assertThat(vars.get("query_timeout")).isEqualTo("5000");
        }

        @Test
        void maxExecutionTime() {
            vars.set("max_execution_time", "10000");
            assertThat(vars.getQueryTimeout()).isEqualTo(10000);
            assertThat(vars.get("max_execution_time")).isEqualTo("10000");
        }

        @Test
        void memQuotaQuery() {
            vars.set("mem_quota_query", "2147483648");
            assertThat(vars.getMemQuotaQuery()).isEqualTo(2147483648L);
        }

        @Test
        void tidbMemQuotaQuery() {
            vars.set("tidb_mem_quota_query", "1048576");
            assertThat(vars.getMemQuotaQuery()).isEqualTo(1048576);
            assertThat(vars.get("tidb_mem_quota_query")).isEqualTo("1048576");
        }

        @Test
        void txnTimeout() {
            vars.set("txn_timeout", "30000");
            assertThat(vars.getTxnTimeout()).isEqualTo(30000);
            assertThat(vars.get("txn_timeout")).isEqualTo("30000");
        }

        @Test
        void invalidNumberThrows() {
            assertThatThrownBy(() -> vars.set("query_timeout", "abc"))
                    .isInstanceOf(NumberFormatException.class);
        }
    }

    // -----------------------------------------------------------------------
    // Custom / user-defined variables
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Custom variables")
    class CustomVars {

        @Test
        void setAndGet() {
            vars.set("my_custom_var", "hello");
            assertThat(vars.get("my_custom_var")).isEqualTo("hello");
        }

        @Test
        void caseInsensitive() {
            vars.set("MyVar", "value1");
            assertThat(vars.get("myvar")).isEqualTo("value1");
            assertThat(vars.get("MYVAR")).isEqualTo("value1");
        }

        @Test
        void unknownReturnsNull() {
            assertThat(vars.get("nonexistent")).isNull();
        }

        @Test
        void overwrite() {
            vars.set("myvar", "v1");
            vars.set("myvar", "v2");
            assertThat(vars.get("myvar")).isEqualTo("v2");
        }
    }

    // -----------------------------------------------------------------------
    // Null handling
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Null handling")
    class NullHandling {

        @Test
        void getNullNameReturnsNull() {
            assertThat(vars.get(null)).isNull();
        }

        @Test
        void setNullNameIsNoOp() {
            vars.set(null, "value");
            // No exception
        }
    }

    // -----------------------------------------------------------------------
    // Typed setters
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Typed setters")
    class TypedSetters {

        @Test
        void setCharset() {
            vars.setCharset("gb2312");
            assertThat(vars.getCharset()).isEqualTo("gb2312");
        }

        @Test
        void setAutoCommit() {
            vars.setAutoCommit(false);
            assertThat(vars.isAutoCommit()).isFalse();
            assertThat(vars.get("autocommit")).isEqualTo("OFF");
        }

        @Test
        void setReadOnly() {
            vars.setReadOnly(true);
            assertThat(vars.isReadOnly()).isTrue();
        }

        @Test
        void setMemQuotaQuery() {
            vars.setMemQuotaQuery(512 * 1024 * 1024L);
            assertThat(vars.getMemQuotaQuery()).isEqualTo(512 * 1024 * 1024L);
        }

        @Test
        void setTxnTimeout() {
            vars.setTxnTimeout(120_000);
            assertThat(vars.getTxnTimeout()).isEqualTo(120_000);
        }
    }
}
