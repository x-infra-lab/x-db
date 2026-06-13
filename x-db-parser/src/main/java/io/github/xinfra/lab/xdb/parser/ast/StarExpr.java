package io.github.xinfra.lab.xdb.parser.ast;

public class StarExpr implements ExprNode {
    private final String tableName;

    public StarExpr(String tableName) {
        this.tableName = tableName;
    }

    /** Null for plain *, non-null for table.* */
    public String getTableName() { return tableName; }
}
