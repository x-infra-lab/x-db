package io.github.xinfra.lab.xdb.parser;

import io.github.xinfra.lab.xdb.parser.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SQLParserTest {

    // ========== DDL ==========

    @Test
    void testCreateDatabase() {
        Statement stmt = SQLParser.parse("CREATE DATABASE IF NOT EXISTS mydb CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        assertThat(stmt).isInstanceOf(CreateDatabaseStmt.class);
        CreateDatabaseStmt s = (CreateDatabaseStmt) stmt;
        assertThat(s.getName()).isEqualTo("mydb");
        assertThat(s.isIfNotExists()).isTrue();
        assertThat(s.getCharset()).isEqualTo("utf8mb4");
        assertThat(s.getCollation()).isEqualTo("utf8mb4_general_ci");
    }

    @Test
    void testDropDatabase() {
        Statement stmt = SQLParser.parse("DROP DATABASE IF EXISTS mydb");
        assertThat(stmt).isInstanceOf(DropDatabaseStmt.class);
        DropDatabaseStmt s = (DropDatabaseStmt) stmt;
        assertThat(s.getName()).isEqualTo("mydb");
        assertThat(s.isIfExists()).isTrue();
    }

    @Test
    void testCreateTable() {
        String sql = "CREATE TABLE IF NOT EXISTS users (" +
                "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'primary key', " +
                "name VARCHAR(255) NOT NULL DEFAULT 'unknown', " +
                "age INT UNSIGNED, " +
                "email VARCHAR(100), " +
                "INDEX idx_name (name(10)), " +
                "UNIQUE KEY uk_email (email)" +
                ") ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='user table'";
        Statement stmt = SQLParser.parse(sql);
        assertThat(stmt).isInstanceOf(CreateTableStmt.class);
        CreateTableStmt s = (CreateTableStmt) stmt;
        assertThat(s.getTableName()).isEqualTo("users");
        assertThat(s.isIfNotExists()).isTrue();
        assertThat(s.getColumns()).hasSize(4);

        ColumnDef id = s.getColumns().get(0);
        assertThat(id.getName()).isEqualTo("id");
        assertThat(id.getTypeName()).isEqualTo("BIGINT");
        assertThat(id.isNullable()).isFalse();
        assertThat(id.isAutoIncrement()).isTrue();
        assertThat(id.isPrimaryKey()).isTrue();
        assertThat(id.getComment()).isEqualTo("primary key");

        ColumnDef name = s.getColumns().get(1);
        assertThat(name.getName()).isEqualTo("name");
        assertThat(name.getTypeName()).isEqualTo("VARCHAR");
        assertThat(name.getLength()).isEqualTo(255);
        assertThat(name.isNullable()).isFalse();
        assertThat(name.getDefaultValue()).isInstanceOf(LiteralExpr.class);
        assertThat(((LiteralExpr) name.getDefaultValue()).getValue()).isEqualTo("unknown");

        ColumnDef age = s.getColumns().get(2);
        assertThat(age.getName()).isEqualTo("age");
        assertThat(age.isUnsigned()).isTrue();

        assertThat(s.getConstraints()).hasSize(2);
        assertThat(s.getConstraints().get(0).getType()).isEqualTo(TableConstraint.Type.INDEX);
        assertThat(s.getConstraints().get(0).getColumns().get(0).getLength()).isEqualTo(10);
        assertThat(s.getConstraints().get(1).getType()).isEqualTo(TableConstraint.Type.UNIQUE);
        assertThat(s.getConstraints().get(1).getName()).isEqualTo("uk_email");

        assertThat(s.getOptions()).containsEntry("ENGINE", "InnoDB");
        assertThat(s.getOptions()).containsEntry("AUTO_INCREMENT", "1");
        assertThat(s.getOptions()).containsEntry("COMMENT", "user table");
    }

    @Test
    void testDropTable() {
        Statement stmt = SQLParser.parse("DROP TABLE IF EXISTS users");
        assertThat(stmt).isInstanceOf(DropTableStmt.class);
        DropTableStmt s = (DropTableStmt) stmt;
        assertThat(s.getTableName()).isEqualTo("users");
        assertThat(s.isIfExists()).isTrue();
    }

    @Test
    void testAlterTable() {
        String sql = "ALTER TABLE users ADD COLUMN score DOUBLE DEFAULT 0.0, DROP COLUMN age, ADD INDEX idx_score (score), DROP INDEX idx_name";
        Statement stmt = SQLParser.parse(sql);
        assertThat(stmt).isInstanceOf(AlterTableStmt.class);
        AlterTableStmt s = (AlterTableStmt) stmt;
        assertThat(s.getTableName()).isEqualTo("users");
        assertThat(s.getSpecs()).hasSize(4);
        assertThat(s.getSpecs().get(0)).isInstanceOf(AlterSpec.AddColumn.class);
        assertThat(s.getSpecs().get(1)).isInstanceOf(AlterSpec.DropColumn.class);
        assertThat(s.getSpecs().get(2)).isInstanceOf(AlterSpec.AddIndex.class);
        assertThat(s.getSpecs().get(3)).isInstanceOf(AlterSpec.DropIndex.class);
    }

    @Test
    void testTruncateTable() {
        Statement stmt = SQLParser.parse("TRUNCATE TABLE users");
        assertThat(stmt).isInstanceOf(TruncateTableStmt.class);
    }

    // ========== DML ==========

    @Test
    void testSelect() {
        String sql = "SELECT DISTINCT a, b AS alias, t.* FROM t1 AS t " +
                "LEFT JOIN t2 ON t.id = t2.id " +
                "WHERE a > 1 AND b < 10 " +
                "GROUP BY a HAVING COUNT(*) > 1 " +
                "ORDER BY b DESC " +
                "LIMIT 10 OFFSET 5";
        Statement stmt = SQLParser.parse(sql);
        assertThat(stmt).isInstanceOf(SelectStmt.class);
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.isDistinct()).isTrue();
        assertThat(s.getSelectItems()).hasSize(3);
        assertThat(s.getSelectItems().get(1).getAlias()).isEqualTo("alias");
        assertThat(s.getSelectItems().get(2).isWildcard()).isTrue();
        assertThat(s.getSelectItems().get(2).getTableName()).isEqualTo("t");

        // FROM with JOIN
        assertThat(s.getFrom()).isInstanceOf(TableRef.JoinTableRef.class);
        TableRef.JoinTableRef join = (TableRef.JoinTableRef) s.getFrom();
        assertThat(join.getJoinType()).isEqualTo(JoinType.LEFT);
        assertThat(join.getLeft()).isInstanceOf(TableRef.SimpleTableRef.class);
        assertThat(((TableRef.SimpleTableRef) join.getLeft()).getAlias()).isEqualTo("t");

        // WHERE
        assertThat(s.getWhere()).isInstanceOf(BinaryExpr.class);
        assertThat(((BinaryExpr) s.getWhere()).getOp()).isEqualTo(BinaryExpr.Op.AND);

        // GROUP BY / HAVING
        assertThat(s.getGroupBy()).hasSize(1);
        assertThat(s.getHaving()).isInstanceOf(BinaryExpr.class);

        // ORDER BY
        assertThat(s.getOrderBy()).hasSize(1);
        assertThat(s.getOrderBy().get(0).isAsc()).isFalse();

        // LIMIT/OFFSET
        assertThat(s.getLimit()).isInstanceOf(LiteralExpr.class);
        assertThat(((LiteralExpr) s.getLimit()).getValue()).isEqualTo(10L);
        assertThat(s.getOffset()).isInstanceOf(LiteralExpr.class);
        assertThat(((LiteralExpr) s.getOffset()).getValue()).isEqualTo(5L);
    }

    @Test
    void testSelectStar() {
        Statement stmt = SQLParser.parse("SELECT * FROM users");
        assertThat(stmt).isInstanceOf(SelectStmt.class);
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getSelectItems()).hasSize(1);
        assertThat(s.getSelectItems().get(0).isWildcard()).isTrue();
    }

    @Test
    void testInsert() {
        String sql = "INSERT INTO users (name, age) VALUES ('Alice', 30), ('Bob', 25)";
        Statement stmt = SQLParser.parse(sql);
        assertThat(stmt).isInstanceOf(InsertStmt.class);
        InsertStmt s = (InsertStmt) stmt;
        assertThat(s.getTableName()).isEqualTo("users");
        assertThat(s.getColumns()).containsExactly("name", "age");
        assertThat(s.getValues()).hasSize(2);
        assertThat(s.getValues().get(0)).hasSize(2);
    }

    @Test
    void testUpdate() {
        String sql = "UPDATE users SET name = 'Charlie', age = age + 1 WHERE id = 1";
        Statement stmt = SQLParser.parse(sql);
        assertThat(stmt).isInstanceOf(UpdateStmt.class);
        UpdateStmt s = (UpdateStmt) stmt;
        assertThat(s.getTableName()).isEqualTo("users");
        assertThat(s.getAssignments()).hasSize(2);
        assertThat(s.getAssignments().get(0).getColumn()).isEqualTo("name");
        assertThat(s.getWhere()).isNotNull();
    }

    @Test
    void testDelete() {
        String sql = "DELETE FROM users WHERE id = 1";
        Statement stmt = SQLParser.parse(sql);
        assertThat(stmt).isInstanceOf(DeleteStmt.class);
        DeleteStmt s = (DeleteStmt) stmt;
        assertThat(s.getTableName()).isEqualTo("users");
        assertThat(s.getWhere()).isNotNull();
    }

    // ========== Expressions ==========

    @Test
    void testExpressionPrecedence() {
        // 1 + 2 * 3 should parse as 1 + (2 * 3)
        Statement stmt = SQLParser.parse("SELECT 1 + 2 * 3");
        SelectStmt s = (SelectStmt) stmt;
        ExprNode expr = s.getSelectItems().get(0).getExpression();
        assertThat(expr).isInstanceOf(BinaryExpr.class);
        BinaryExpr add = (BinaryExpr) expr;
        assertThat(add.getOp()).isEqualTo(BinaryExpr.Op.ADD);
        assertThat(add.getRight()).isInstanceOf(BinaryExpr.class);
        assertThat(((BinaryExpr) add.getRight()).getOp()).isEqualTo(BinaryExpr.Op.MUL);
    }

    @Test
    void testIsNull() {
        Statement stmt = SQLParser.parse("SELECT * FROM t WHERE a IS NULL");
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getWhere()).isInstanceOf(UnaryExpr.class);
        assertThat(((UnaryExpr) s.getWhere()).getOp()).isEqualTo(UnaryExpr.Op.IS_NULL);
    }

    @Test
    void testIsNotNull() {
        Statement stmt = SQLParser.parse("SELECT * FROM t WHERE a IS NOT NULL");
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getWhere()).isInstanceOf(UnaryExpr.class);
        assertThat(((UnaryExpr) s.getWhere()).getOp()).isEqualTo(UnaryExpr.Op.IS_NOT_NULL);
    }

    @Test
    void testLike() {
        Statement stmt = SQLParser.parse("SELECT * FROM t WHERE name LIKE '%test%'");
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getWhere()).isInstanceOf(LikeExpr.class);
        assertThat(((LikeExpr) s.getWhere()).isNot()).isFalse();
    }

    @Test
    void testNotLike() {
        Statement stmt = SQLParser.parse("SELECT * FROM t WHERE name NOT LIKE '%test%'");
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getWhere()).isInstanceOf(LikeExpr.class);
        assertThat(((LikeExpr) s.getWhere()).isNot()).isTrue();
    }

    @Test
    void testIn() {
        Statement stmt = SQLParser.parse("SELECT * FROM t WHERE id IN (1, 2, 3)");
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getWhere()).isInstanceOf(InExpr.class);
        InExpr in = (InExpr) s.getWhere();
        assertThat(in.getValues()).hasSize(3);
        assertThat(in.isNot()).isFalse();
    }

    @Test
    void testBetween() {
        Statement stmt = SQLParser.parse("SELECT * FROM t WHERE age BETWEEN 18 AND 65");
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getWhere()).isInstanceOf(BetweenExpr.class);
        BetweenExpr b = (BetweenExpr) s.getWhere();
        assertThat(b.isNot()).isFalse();
    }

    @Test
    void testExists() {
        Statement stmt = SQLParser.parse("SELECT * FROM t WHERE EXISTS (SELECT 1 FROM t2)");
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getWhere()).isInstanceOf(ExistsExpr.class);
    }

    @Test
    void testCaseWhen() {
        String sql = "SELECT CASE WHEN a > 0 THEN 'positive' WHEN a = 0 THEN 'zero' ELSE 'negative' END FROM t";
        Statement stmt = SQLParser.parse(sql);
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getSelectItems().get(0).getExpression()).isInstanceOf(CaseWhenExpr.class);
        CaseWhenExpr c = (CaseWhenExpr) s.getSelectItems().get(0).getExpression();
        assertThat(c.getCaseExpr()).isNull(); // searched CASE
        assertThat(c.getWhenClauses()).hasSize(2);
        assertThat(c.getElseExpr()).isNotNull();
    }

    @Test
    void testCast() {
        Statement stmt = SQLParser.parse("SELECT CAST(a AS BIGINT) FROM t");
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getSelectItems().get(0).getExpression()).isInstanceOf(CastExpr.class);
        CastExpr c = (CastExpr) s.getSelectItems().get(0).getExpression();
        assertThat(c.getTargetType()).isEqualTo("BIGINT");
    }

    @Test
    void testFunctionCall() {
        Statement stmt = SQLParser.parse("SELECT COUNT(*), SUM(DISTINCT amount), MAX(id) FROM orders");
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getSelectItems()).hasSize(3);

        FunctionCallExpr count = (FunctionCallExpr) s.getSelectItems().get(0).getExpression();
        assertThat(count.getName()).isEqualTo("COUNT");
        assertThat(count.getArgs().get(0)).isInstanceOf(StarExpr.class);

        FunctionCallExpr sum = (FunctionCallExpr) s.getSelectItems().get(1).getExpression();
        assertThat(sum.getName()).isEqualTo("SUM");
        assertThat(sum.isDistinct()).isTrue();

        FunctionCallExpr max = (FunctionCallExpr) s.getSelectItems().get(2).getExpression();
        assertThat(max.getName()).isEqualTo("MAX");
    }

    // ========== Utility ==========

    @Test
    void testUse() {
        Statement stmt = SQLParser.parse("USE mydb");
        assertThat(stmt).isInstanceOf(UseStmt.class);
        assertThat(((UseStmt) stmt).getDatabase()).isEqualTo("mydb");
    }

    @Test
    void testShowDatabases() {
        Statement stmt = SQLParser.parse("SHOW DATABASES");
        assertThat(stmt).isInstanceOf(ShowStmt.class);
        assertThat(((ShowStmt) stmt).getType()).isEqualTo(ShowStmt.ShowType.DATABASES);
    }

    @Test
    void testShowTables() {
        Statement stmt = SQLParser.parse("SHOW TABLES");
        assertThat(stmt).isInstanceOf(ShowStmt.class);
        assertThat(((ShowStmt) stmt).getType()).isEqualTo(ShowStmt.ShowType.TABLES);
    }

    @Test
    void testShowColumns() {
        Statement stmt = SQLParser.parse("SHOW COLUMNS FROM users");
        assertThat(stmt).isInstanceOf(ShowStmt.class);
        ShowStmt s = (ShowStmt) stmt;
        assertThat(s.getType()).isEqualTo(ShowStmt.ShowType.COLUMNS);
        assertThat(s.getTableName()).isEqualTo("users");
    }

    @Test
    void testShowCreateTable() {
        Statement stmt = SQLParser.parse("SHOW CREATE TABLE users");
        assertThat(stmt).isInstanceOf(ShowStmt.class);
        assertThat(((ShowStmt) stmt).getType()).isEqualTo(ShowStmt.ShowType.CREATE_TABLE);
    }

    @Test
    void testExplain() {
        Statement stmt = SQLParser.parse("EXPLAIN SELECT * FROM users WHERE id = 1");
        assertThat(stmt).isInstanceOf(ExplainStmt.class);
        assertThat(((ExplainStmt) stmt).getStatement()).isInstanceOf(SelectStmt.class);
    }

    @Test
    void testBeginCommitRollback() {
        assertThat(SQLParser.parse("BEGIN")).isInstanceOf(BeginStmt.class);
        assertThat(SQLParser.parse("BEGIN WORK")).isInstanceOf(BeginStmt.class);
        assertThat(SQLParser.parse("START TRANSACTION")).isInstanceOf(BeginStmt.class);
        assertThat(SQLParser.parse("COMMIT")).isInstanceOf(CommitStmt.class);
        assertThat(SQLParser.parse("ROLLBACK")).isInstanceOf(RollbackStmt.class);
    }

    @Test
    void testSet() {
        Statement stmt = SQLParser.parse("SET SESSION autocommit = 1");
        assertThat(stmt).isInstanceOf(SetStmt.class);
        SetStmt s = (SetStmt) stmt;
        assertThat(s.getVariable()).isEqualTo("autocommit");
        assertThat(s.getScope()).isEqualTo(SetStmt.Scope.SESSION);
    }

    @Test
    void testSetSystemVariable() {
        Statement stmt = SQLParser.parse("SET @@autocommit = 1");
        assertThat(stmt).isInstanceOf(SetStmt.class);
        SetStmt s = (SetStmt) stmt;
        assertThat(s.getVariable()).isEqualTo("autocommit");
        assertThat(s.getScope()).isEqualTo(SetStmt.Scope.SESSION);
    }

    @Test
    void testDescribe() {
        Statement stmt = SQLParser.parse("DESCRIBE users");
        assertThat(stmt).isInstanceOf(DescribeStmt.class);
        assertThat(((DescribeStmt) stmt).getTableName()).isEqualTo("users");
    }

    @Test
    void testDescWithDescKeyword() {
        Statement stmt = SQLParser.parse("DESC users");
        assertThat(stmt).isInstanceOf(DescribeStmt.class);
        assertThat(((DescribeStmt) stmt).getTableName()).isEqualTo("users");
    }

    // ========== Multi-statement ==========

    @Test
    void testMultiStatement() {
        List<Statement> stmts = SQLParser.parseMulti("USE mydb; SELECT 1; SHOW TABLES");
        assertThat(stmts).hasSize(3);
        assertThat(stmts.get(0)).isInstanceOf(UseStmt.class);
        assertThat(stmts.get(1)).isInstanceOf(SelectStmt.class);
        assertThat(stmts.get(2)).isInstanceOf(ShowStmt.class);
    }

    // ========== Error handling ==========

    @Test
    void testSyntaxError() {
        assertThatThrownBy(() -> SQLParser.parse("SELCT * FROM t"))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("syntax error");
    }

    // ========== Backtick identifiers and keywords as identifiers ==========

    @Test
    void testBacktickIdentifier() {
        Statement stmt = SQLParser.parse("SELECT `select`, `from` FROM `table`");
        assertThat(stmt).isInstanceOf(SelectStmt.class);
    }

    @Test
    void testKeywordAsIdentifier() {
        Statement stmt = SQLParser.parse("CREATE TABLE t (status INT, comment VARCHAR(100))");
        assertThat(stmt).isInstanceOf(CreateTableStmt.class);
        CreateTableStmt s = (CreateTableStmt) stmt;
        assertThat(s.getColumns().get(0).getName()).isEqualTo("status");
        assertThat(s.getColumns().get(1).getName()).isEqualTo("comment");
    }

    // ========== Subquery ==========

    @Test
    void testSubqueryInFrom() {
        String sql = "SELECT * FROM (SELECT id, name FROM users) sub WHERE sub.id > 1";
        Statement stmt = SQLParser.parse(sql);
        assertThat(stmt).isInstanceOf(SelectStmt.class);
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getFrom()).isInstanceOf(TableRef.SubqueryTableRef.class);
        TableRef.SubqueryTableRef sub = (TableRef.SubqueryTableRef) s.getFrom();
        assertThat(sub.getAlias()).isEqualTo("sub");
    }

    @Test
    void testSubqueryInWhere() {
        String sql = "SELECT * FROM t WHERE id IN (SELECT id FROM t2)";
        Statement stmt = SQLParser.parse(sql);
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getWhere()).isInstanceOf(InExpr.class);
    }

    // ========== LIMIT with comma syntax ==========

    @Test
    void testLimitComma() {
        Statement stmt = SQLParser.parse("SELECT * FROM t LIMIT 5, 10");
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getLimit()).isInstanceOf(LiteralExpr.class);
        assertThat(((LiteralExpr) s.getLimit()).getValue()).isEqualTo(10L);
        assertThat(s.getOffset()).isInstanceOf(LiteralExpr.class);
        assertThat(((LiteralExpr) s.getOffset()).getValue()).isEqualTo(5L);
    }

    // ========== String unescaping ==========

    @Test
    void testStringUnescaping() {
        Statement stmt = SQLParser.parse("SELECT 'hello\\'s world'");
        SelectStmt s = (SelectStmt) stmt;
        LiteralExpr lit = (LiteralExpr) s.getSelectItems().get(0).getExpression();
        assertThat(lit.getValue()).isEqualTo("hello's world");
    }

    @Test
    void testForUpdate() {
        Statement stmt = SQLParser.parse("SELECT * FROM t WHERE id = 1 FOR UPDATE");
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.isForUpdate()).isTrue();
    }

    // ========== Cross join via comma ==========

    @Test
    void testImplicitCrossJoin() {
        Statement stmt = SQLParser.parse("SELECT * FROM t1, t2 WHERE t1.id = t2.id");
        SelectStmt s = (SelectStmt) stmt;
        assertThat(s.getFrom()).isInstanceOf(TableRef.JoinTableRef.class);
        TableRef.JoinTableRef j = (TableRef.JoinTableRef) s.getFrom();
        assertThat(j.getJoinType()).isEqualTo(JoinType.CROSS);
    }
}
