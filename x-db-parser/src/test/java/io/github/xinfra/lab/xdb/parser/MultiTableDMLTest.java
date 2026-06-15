package io.github.xinfra.lab.xdb.parser;

import io.github.xinfra.lab.xdb.parser.ast.AnalyzeTableStmt;
import io.github.xinfra.lab.xdb.parser.ast.DeleteStmt;
import io.github.xinfra.lab.xdb.parser.ast.Statement;
import io.github.xinfra.lab.xdb.parser.ast.UpdateStmt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MultiTableDMLTest {

    @Test
    @DisplayName("UPDATE with JOIN parses correctly")
    void parseUpdateWithJoin() {
        Statement stmt = SQLParser.parse(
                "UPDATE users u JOIN orders o ON u.id = o.user_id SET u.name = 'test' WHERE o.status = 'active'");
        assertThat(stmt).isInstanceOf(UpdateStmt.class);
        UpdateStmt upd = (UpdateStmt) stmt;
        assertThat(upd.getTableName()).isEqualTo("users");
        assertThat(upd.getFrom()).isNotNull();
        assertThat(upd.getAssignments()).hasSize(1);
        assertThat(upd.getWhere()).isNotNull();
    }

    @Test
    @DisplayName("Single-table UPDATE still works")
    void parseSingleTableUpdate() {
        Statement stmt = SQLParser.parse("UPDATE users SET name = 'test' WHERE id = 1");
        assertThat(stmt).isInstanceOf(UpdateStmt.class);
        UpdateStmt upd = (UpdateStmt) stmt;
        assertThat(upd.getTableName()).isEqualTo("users");
        assertThat(upd.getFrom()).isNull();
        assertThat(upd.getAssignments()).hasSize(1);
    }

    @Test
    @DisplayName("DELETE with FROM/JOIN parses as multi-table delete")
    void parseDeleteWithJoin() {
        Statement stmt = SQLParser.parse(
                "DELETE u FROM users u JOIN orders o ON u.id = o.user_id WHERE o.status = 'cancelled'");
        assertThat(stmt).isInstanceOf(DeleteStmt.class);
        DeleteStmt del = (DeleteStmt) stmt;
        assertThat(del.getTableName()).isEqualTo("u");
        assertThat(del.getFrom()).isNotNull();
        assertThat(del.getWhere()).isNotNull();
    }

    @Test
    @DisplayName("Single-table DELETE still works")
    void parseSingleTableDelete() {
        Statement stmt = SQLParser.parse("DELETE FROM users WHERE id = 1");
        assertThat(stmt).isInstanceOf(DeleteStmt.class);
        DeleteStmt del = (DeleteStmt) stmt;
        assertThat(del.getTableName()).isEqualTo("users");
        assertThat(del.getFrom()).isNull();
    }

    @Test
    @DisplayName("ANALYZE TABLE parses correctly")
    void parseAnalyzeTable() {
        Statement stmt = SQLParser.parse("ANALYZE TABLE users");
        assertThat(stmt).isInstanceOf(AnalyzeTableStmt.class);
        AnalyzeTableStmt analyze = (AnalyzeTableStmt) stmt;
        assertThat(analyze.getTableName()).isEqualTo("users");
    }
}
