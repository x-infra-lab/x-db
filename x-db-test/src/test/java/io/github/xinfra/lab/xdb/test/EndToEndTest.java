package io.github.xinfra.lab.xdb.test;

import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.session.ExecuteResult;
import io.github.xinfra.lab.xdb.session.Session;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndToEndTest {

    private static TestHarness harness;
    private static final AtomicInteger dbSeq = new AtomicInteger(0);
    private Session session;

    @BeforeAll
    static void startHarness() {
        harness = new TestHarness();
    }

    @AfterAll
    static void stopHarness() {
        if (harness != null) harness.close();
    }

    @BeforeEach
    void createSession() {
        session = harness.createSession();
    }

    @AfterEach
    void closeSession() {
        if (session != null) session.close();
    }

    private String freshDb() {
        String name = "db_" + dbSeq.incrementAndGet();
        session.execute("CREATE DATABASE " + name);
        session.useDatabase(name);
        return name;
    }

    // -----------------------------------------------------------------------
    // DDL
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DDL operations")
    class DDLOperations {

        @Test
        @DisplayName("CREATE DATABASE and USE")
        void createDatabaseAndUse() {
            String db = freshDb();
            assertThat(session.currentDatabase()).isEqualTo(db);
        }

        @Test
        @DisplayName("CREATE TABLE and SHOW TABLES")
        void createTable() {
            freshDb();
            session.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(255), age INT)");

            ExecuteResult result = session.execute("SHOW TABLES");
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getRows()).isNotEmpty();

            boolean found = result.getRows().stream()
                    .anyMatch(r -> "users".equalsIgnoreCase(r.get(0).toStringValue()));
            assertThat(found).as("users table should be listed").isTrue();
        }

        @Test
        @DisplayName("SHOW TABLES on empty database")
        void showTablesEmpty() {
            freshDb();
            ExecuteResult result = session.execute("SHOW TABLES");
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getRows()).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // DML: INSERT, SELECT, UPDATE, DELETE
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DML operations")
    class DMLOperations {

        @BeforeEach
        void setupTable() {
            freshDb();
            session.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(255), age INT)");
        }

        @Test
        @DisplayName("INSERT and SELECT")
        void insertAndSelect() {
            session.execute("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)");
            session.execute("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)");

            ExecuteResult result = session.execute("SELECT * FROM users");
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getRows()).hasSize(2);
        }

        @Test
        @DisplayName("INSERT returns affected rows")
        void insertAffectedRows() {
            ExecuteResult result = session.execute(
                    "INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)");
            assertThat(result.isQuery()).isFalse();
            assertThat(result.getAffectedRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("SELECT with WHERE clause")
        void selectWithWhere() {
            session.execute("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)");
            session.execute("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)");
            session.execute("INSERT INTO users (id, name, age) VALUES (3, 'Charlie', 35)");

            ExecuteResult result = session.execute("SELECT * FROM users WHERE age > 28");
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getRows()).hasSize(2);
        }

        @Test
        @DisplayName("UPDATE modifies rows")
        void updateRows() {
            session.execute("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)");
            session.execute("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)");

            ExecuteResult updateResult = session.execute("UPDATE users SET age = 31 WHERE id = 1");
            assertThat(updateResult.getAffectedRows()).isEqualTo(1);

            ExecuteResult selectResult = session.execute("SELECT * FROM users WHERE id = 1");
            assertThat(selectResult.getRows()).hasSize(1);
            // age is the 3rd column (index 2) in the full schema
            assertThat(selectResult.getRows().get(0).get(2).toLong()).isEqualTo(31);
        }

        @Test
        @DisplayName("DELETE removes rows")
        void deleteRows() {
            session.execute("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)");
            session.execute("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)");

            ExecuteResult deleteResult = session.execute("DELETE FROM users WHERE id = 1");
            assertThat(deleteResult.getAffectedRows()).isEqualTo(1);

            ExecuteResult selectResult = session.execute("SELECT * FROM users");
            assertThat(selectResult.getRows()).hasSize(1);
            assertThat(selectResult.getRows().get(0).get(1).toStringValue()).isEqualTo("Bob");
        }
    }

    // -----------------------------------------------------------------------
    // Query features: ORDER BY, LIMIT
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Query features")
    class QueryFeatures {

        @BeforeEach
        void setupData() {
            freshDb();
            session.execute("CREATE TABLE items (id BIGINT PRIMARY KEY, name VARCHAR(255), price INT)");
            session.execute("INSERT INTO items (id, name, price) VALUES (1, 'Apple', 100)");
            session.execute("INSERT INTO items (id, name, price) VALUES (2, 'Banana', 50)");
            session.execute("INSERT INTO items (id, name, price) VALUES (3, 'Cherry', 200)");
            session.execute("INSERT INTO items (id, name, price) VALUES (4, 'Date', 150)");
        }

        @Test
        @DisplayName("ORDER BY ASC")
        void orderByAsc() {
            // price is column index 2 in (id, name, price)
            ExecuteResult result = session.execute("SELECT * FROM items ORDER BY price");
            List<Row> rows = result.getRows();
            assertThat(rows).hasSize(4);
            assertThat(rows.get(0).get(2).toLong()).isEqualTo(50);
            assertThat(rows.get(3).get(2).toLong()).isEqualTo(200);
        }

        @Test
        @DisplayName("ORDER BY DESC")
        void orderByDesc() {
            ExecuteResult result = session.execute("SELECT * FROM items ORDER BY price DESC");
            List<Row> rows = result.getRows();
            assertThat(rows).hasSize(4);
            assertThat(rows.get(0).get(2).toLong()).isEqualTo(200);
            assertThat(rows.get(3).get(2).toLong()).isEqualTo(50);
        }

        @Test
        @DisplayName("LIMIT")
        void limit() {
            ExecuteResult result = session.execute("SELECT * FROM items LIMIT 2");
            assertThat(result.getRows()).hasSize(2);
        }

        @Test
        @DisplayName("ORDER BY + LIMIT")
        void orderByWithLimit() {
            ExecuteResult result = session.execute(
                    "SELECT * FROM items ORDER BY price DESC LIMIT 2");
            List<Row> rows = result.getRows();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get(2).toLong()).isEqualTo(200);
        }
    }

    // -----------------------------------------------------------------------
    // SELECT without FROM
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("SELECT expression without FROM clause")
    void selectExpressionNoTable() {
        ExecuteResult result = session.execute("SELECT 1 + 1");
        assertThat(result.isQuery()).isTrue();
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).get(0).toLong()).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // Aggregation features
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Aggregation features")
    class AggregationFeatures {

        @BeforeEach
        void setupData() {
            freshDb();
            session.execute("CREATE TABLE products (id BIGINT PRIMARY KEY, category VARCHAR(255), price INT)");
            session.execute("INSERT INTO products (id, category, price) VALUES (1, 'fruit', 100)");
            session.execute("INSERT INTO products (id, category, price) VALUES (2, 'fruit', 200)");
            session.execute("INSERT INTO products (id, category, price) VALUES (3, 'veggie', 50)");
            session.execute("INSERT INTO products (id, category, price) VALUES (4, 'veggie', 80)");
            session.execute("INSERT INTO products (id, category, price) VALUES (5, 'fruit', 150)");
        }

        @Test
        @DisplayName("COUNT(*) on table with rows")
        void countAll() {
            ExecuteResult result = session.execute("SELECT COUNT(*) FROM products");
            assertThat(result.getRows()).hasSize(1);
            assertThat(result.getRows().get(0).get(0).toLong()).isEqualTo(5);
        }

        @Test
        @DisplayName("COUNT(*) on empty table")
        void countEmpty() {
            session.execute("CREATE TABLE empty_tbl (id BIGINT PRIMARY KEY, val INT)");
            ExecuteResult result = session.execute("SELECT COUNT(*) FROM empty_tbl");
            assertThat(result.getRows()).hasSize(1);
            assertThat(result.getRows().get(0).get(0).toLong()).isEqualTo(0);
        }

        @Test
        @DisplayName("SUM, AVG, MIN, MAX")
        void aggregateFunctions() {
            ExecuteResult sumResult = session.execute("SELECT SUM(price) FROM products");
            assertThat(sumResult.getRows().get(0).get(0).toLong()).isEqualTo(580);

            ExecuteResult minResult = session.execute("SELECT MIN(price) FROM products");
            assertThat(minResult.getRows().get(0).get(0).toLong()).isEqualTo(50);

            ExecuteResult maxResult = session.execute("SELECT MAX(price) FROM products");
            assertThat(maxResult.getRows().get(0).get(0).toLong()).isEqualTo(200);
        }

        @Test
        @DisplayName("GROUP BY with COUNT")
        void groupByCount() {
            ExecuteResult result = session.execute(
                    "SELECT category, COUNT(*) FROM products GROUP BY category");
            assertThat(result.getRows()).hasSize(2);

            boolean foundFruit = false, foundVeggie = false;
            for (Row row : result.getRows()) {
                String cat = row.get(0).toStringValue();
                long cnt = row.get(1).toLong();
                if ("fruit".equals(cat)) { assertThat(cnt).isEqualTo(3); foundFruit = true; }
                if ("veggie".equals(cat)) { assertThat(cnt).isEqualTo(2); foundVeggie = true; }
            }
            assertThat(foundFruit).as("fruit group").isTrue();
            assertThat(foundVeggie).as("veggie group").isTrue();
        }

        @Test
        @DisplayName("GROUP BY with SUM")
        void groupBySum() {
            ExecuteResult result = session.execute(
                    "SELECT category, SUM(price) FROM products GROUP BY category");
            assertThat(result.getRows()).hasSize(2);

            for (Row row : result.getRows()) {
                String cat = row.get(0).toStringValue();
                long total = row.get(1).toLong();
                if ("fruit".equals(cat)) assertThat(total).isEqualTo(450);
                if ("veggie".equals(cat)) assertThat(total).isEqualTo(130);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Distributed push-down (selection / aggregation / TopN)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Distributed push-down")
    class DistributedPushDown {

        @BeforeEach
        void setupData() {
            freshDb();
            session.execute("CREATE TABLE employees (id BIGINT PRIMARY KEY, dept VARCHAR(255), salary INT)");
            session.execute("INSERT INTO employees (id, dept, salary) VALUES (1, 'eng', 100)");
            session.execute("INSERT INTO employees (id, dept, salary) VALUES (2, 'eng', 200)");
            session.execute("INSERT INTO employees (id, dept, salary) VALUES (3, 'sales', 150)");
            session.execute("INSERT INTO employees (id, dept, salary) VALUES (4, 'sales', 250)");
            session.execute("INSERT INTO employees (id, dept, salary) VALUES (5, 'eng', 300)");
        }

        @Test
        @DisplayName("Selection push-down: WHERE filter via DistScanExecutor")
        void selectionPushDown() {
            ExecuteResult result = session.execute(
                    "SELECT * FROM employees WHERE salary > 150");
            assertThat(result.getRows()).hasSize(3);
        }

        @Test
        @DisplayName("Aggregation push-down: COUNT via DistAggExecutor")
        void aggCountPushDown() {
            ExecuteResult result = session.execute(
                    "SELECT COUNT(*) FROM employees");
            assertThat(result.getRows()).hasSize(1);
            assertThat(result.getRows().get(0).get(0).toLong()).isEqualTo(5);
        }

        @Test
        @DisplayName("Aggregation push-down: SUM via DistAggExecutor")
        void aggSumPushDown() {
            ExecuteResult result = session.execute(
                    "SELECT SUM(salary) FROM employees");
            assertThat(result.getRows()).hasSize(1);
            assertThat(result.getRows().get(0).get(0).toLong()).isEqualTo(1000);
        }

        @Test
        @DisplayName("Aggregation push-down: GROUP BY via DistAggExecutor")
        void aggGroupByPushDown() {
            ExecuteResult result = session.execute(
                    "SELECT dept, COUNT(*), SUM(salary) FROM employees GROUP BY dept");
            assertThat(result.getRows()).hasSize(2);

            boolean foundEng = false, foundSales = false;
            for (Row row : result.getRows()) {
                String dept = row.get(0).toStringValue();
                long cnt = row.get(1).toLong();
                long total = row.get(2).toLong();
                if ("eng".equals(dept)) {
                    assertThat(cnt).isEqualTo(3);
                    assertThat(total).isEqualTo(600);
                    foundEng = true;
                }
                if ("sales".equals(dept)) {
                    assertThat(cnt).isEqualTo(2);
                    assertThat(total).isEqualTo(400);
                    foundSales = true;
                }
            }
            assertThat(foundEng).isTrue();
            assertThat(foundSales).isTrue();
        }

        @Test
        @DisplayName("Aggregation push-down: MIN/MAX via DistAggExecutor")
        void aggMinMaxPushDown() {
            ExecuteResult result = session.execute(
                    "SELECT MIN(salary), MAX(salary) FROM employees");
            assertThat(result.getRows()).hasSize(1);
            assertThat(result.getRows().get(0).get(0).toLong()).isEqualTo(100);
            assertThat(result.getRows().get(0).get(1).toLong()).isEqualTo(300);
        }

        @Test
        @DisplayName("TopN push-down: ORDER BY ASC LIMIT via DistTopNExecutor")
        void topNAscPushDown() {
            ExecuteResult result = session.execute(
                    "SELECT * FROM employees ORDER BY salary LIMIT 3");
            List<Row> rows = result.getRows();
            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).get(2).toLong()).isEqualTo(100);
            assertThat(rows.get(1).get(2).toLong()).isEqualTo(150);
            assertThat(rows.get(2).get(2).toLong()).isEqualTo(200);
        }

        @Test
        @DisplayName("TopN push-down: ORDER BY DESC LIMIT via DistTopNExecutor")
        void topNDescPushDown() {
            ExecuteResult result = session.execute(
                    "SELECT * FROM employees ORDER BY salary DESC LIMIT 2");
            List<Row> rows = result.getRows();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get(2).toLong()).isEqualTo(300);
            assertThat(rows.get(1).get(2).toLong()).isEqualTo(250);
        }

        @Test
        @DisplayName("Aggregation push-down with WHERE: filtered COUNT")
        void aggWithFilterPushDown() {
            ExecuteResult result = session.execute(
                    "SELECT COUNT(*) FROM employees WHERE salary >= 200");
            assertThat(result.getRows()).hasSize(1);
            assertThat(result.getRows().get(0).get(0).toLong()).isEqualTo(3);
        }

        @Test
        @DisplayName("Aggregation push-down: empty table")
        void aggEmptyTable() {
            session.execute("CREATE TABLE empty_emp (id BIGINT PRIMARY KEY, val INT)");
            ExecuteResult result = session.execute("SELECT COUNT(*) FROM empty_emp");
            assertThat(result.getRows()).hasSize(1);
            assertThat(result.getRows().get(0).get(0).toLong()).isEqualTo(0);
        }
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("UNION features")
    class UnionFeatures {

        @Test
        @DisplayName("UNION ALL merges two result sets")
        void unionAll() {
            freshDb();
            session.execute("CREATE TABLE t1 (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            session.execute("CREATE TABLE t2 (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            session.execute("INSERT INTO t1 VALUES (1, 'alice'), (2, 'bob')");
            session.execute("INSERT INTO t2 VALUES (3, 'charlie'), (4, 'dave')");

            ExecuteResult result = session.execute(
                    "SELECT id, name FROM t1 UNION ALL SELECT id, name FROM t2");
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getRows()).hasSize(4);
        }

        @Test
        @DisplayName("UNION removes duplicates")
        void unionDistinct() {
            freshDb();
            session.execute("CREATE TABLE t1 (id BIGINT PRIMARY KEY, val INT)");
            session.execute("CREATE TABLE t2 (id BIGINT PRIMARY KEY, val INT)");
            session.execute("INSERT INTO t1 VALUES (1, 10), (2, 20)");
            session.execute("INSERT INTO t2 VALUES (3, 10), (4, 30)");

            ExecuteResult result = session.execute(
                    "SELECT val FROM t1 UNION SELECT val FROM t2");
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getRows()).hasSize(3);
        }

        @Test
        @DisplayName("UNION ALL keeps duplicates")
        void unionAllKeepsDuplicates() {
            freshDb();
            session.execute("CREATE TABLE t1 (id BIGINT PRIMARY KEY, val INT)");
            session.execute("CREATE TABLE t2 (id BIGINT PRIMARY KEY, val INT)");
            session.execute("INSERT INTO t1 VALUES (1, 10), (2, 20)");
            session.execute("INSERT INTO t2 VALUES (3, 10), (4, 30)");

            ExecuteResult result = session.execute(
                    "SELECT val FROM t1 UNION ALL SELECT val FROM t2");
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getRows()).hasSize(4);
        }

        @Test
        @DisplayName("UNION column count mismatch throws")
        void unionColumnMismatch() {
            freshDb();
            session.execute("CREATE TABLE t1 (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            session.execute("CREATE TABLE t2 (id BIGINT PRIMARY KEY)");

            assertThatThrownBy(() -> session.execute(
                    "SELECT id, name FROM t1 UNION ALL SELECT id FROM t2"))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("Three-way UNION ALL")
        void threeWayUnion() {
            freshDb();
            session.execute("CREATE TABLE t1 (id BIGINT PRIMARY KEY, val INT)");
            session.execute("CREATE TABLE t2 (id BIGINT PRIMARY KEY, val INT)");
            session.execute("CREATE TABLE t3 (id BIGINT PRIMARY KEY, val INT)");
            session.execute("INSERT INTO t1 VALUES (1, 10)");
            session.execute("INSERT INTO t2 VALUES (2, 20)");
            session.execute("INSERT INTO t3 VALUES (3, 30)");

            ExecuteResult result = session.execute(
                    "SELECT val FROM t1 UNION ALL SELECT val FROM t2 UNION ALL SELECT val FROM t3");
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getRows()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("CREATE INDEX standalone syntax")
    class CreateIndexSyntax {

        @Test
        @DisplayName("CREATE INDEX creates an index")
        void createIndex() {
            freshDb();
            session.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(100), age INT)");
            session.execute("CREATE INDEX idx_name ON users(name)");

            ExecuteResult desc = session.execute("DESCRIBE users");
            assertThat(desc.isQuery()).isTrue();
            boolean foundIdx = desc.getRows().stream()
                    .anyMatch(r -> "name".equalsIgnoreCase(r.get(0).toStringValue())
                            && "MUL".equalsIgnoreCase(r.get(3).toStringValue()));
            assertThat(foundIdx).as("name column should show MUL key after CREATE INDEX").isTrue();
        }

        @Test
        @DisplayName("CREATE INDEX on multiple columns")
        void createCompositeIndex() {
            freshDb();
            session.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_id INT, status VARCHAR(20))");
            session.execute("CREATE INDEX idx_cust_status ON orders(customer_id, status)");

            ExecuteResult desc = session.execute("DESCRIBE orders");
            assertThat(desc.isQuery()).isTrue();
            boolean foundIdx = desc.getRows().stream()
                    .anyMatch(r -> "customer_id".equalsIgnoreCase(r.get(0).toStringValue())
                            && "MUL".equalsIgnoreCase(r.get(3).toStringValue()));
            assertThat(foundIdx).as("customer_id should show MUL key").isTrue();
        }
    }

    @Nested
    @DisplayName("HAVING validation")
    class HavingValidation {

        @Test
        @DisplayName("HAVING with aggregate function is valid")
        void havingWithAgg() {
            freshDb();
            session.execute("CREATE TABLE items (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100), qty INT)");
            session.execute("INSERT INTO items (name, qty) VALUES ('a', 5), ('a', 3), ('b', 10)");

            ExecuteResult result = session.execute(
                    "SELECT name, COUNT(*) FROM items GROUP BY name HAVING COUNT(*) > 1");
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getRows()).hasSize(1);
            assertThat(result.getRows().get(0).get(0).toStringValue()).isEqualTo("a");
        }

        @Test
        @DisplayName("HAVING referencing non-aggregated column throws")
        void havingNonAggColumn() {
            freshDb();
            session.execute("CREATE TABLE items (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100), age INT)");
            session.execute("INSERT INTO items (name, age) VALUES ('a', 10), ('a', 20), ('b', 30)");

            assertThatThrownBy(() -> session.execute(
                    "SELECT name FROM items GROUP BY name HAVING age > 1"))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("HAVING without GROUP BY with aggregate is valid")
        void havingNoGroupBy() {
            freshDb();
            session.execute("CREATE TABLE items (id BIGINT PRIMARY KEY AUTO_INCREMENT, val INT)");
            session.execute("INSERT INTO items (val) VALUES (1), (2), (3)");

            ExecuteResult result = session.execute(
                    "SELECT COUNT(*) FROM items HAVING COUNT(*) > 0");
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getRows()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("SELECT from unknown table throws")
        void unknownTable() {
            freshDb();
            assertThatThrownBy(() -> session.execute("SELECT * FROM nonexistent"))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("Syntax error throws")
        void syntaxError() {
            assertThatThrownBy(() -> session.execute("SELEC BROKEN"))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
