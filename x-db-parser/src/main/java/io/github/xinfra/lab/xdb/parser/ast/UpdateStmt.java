package io.github.xinfra.lab.xdb.parser.ast;

import java.util.List;

public class UpdateStmt implements Statement {
    private final String tableName;
    private final List<Assignment> assignments;
    private final ExprNode where;

    public UpdateStmt(String tableName, List<Assignment> assignments, ExprNode where) {
        this.tableName = tableName;
        this.assignments = assignments;
        this.where = where;
    }

    public String getTableName() { return tableName; }
    public List<Assignment> getAssignments() { return assignments; }
    public ExprNode getWhere() { return where; }
}
