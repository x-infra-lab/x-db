package io.github.xinfra.lab.xdb.parser.ast;

public class SelectItem {
    private final ExprNode expression;
    private final String alias;
    private final boolean wildcard;
    private final String tableName;

    public SelectItem(ExprNode expression, String alias, boolean wildcard, String tableName) {
        this.expression = expression;
        this.alias = alias;
        this.wildcard = wildcard;
        this.tableName = tableName;
    }

    /** Creates a wildcard (*) select item. */
    public static SelectItem wildcard() {
        return new SelectItem(null, null, true, null);
    }

    /** Creates a table.* select item. */
    public static SelectItem tableWildcard(String tableName) {
        return new SelectItem(null, null, true, tableName);
    }

    /** Creates an expression select item with optional alias. */
    public static SelectItem expression(ExprNode expr, String alias) {
        return new SelectItem(expr, alias, false, null);
    }

    public ExprNode getExpression() { return expression; }
    public String getAlias() { return alias; }
    public boolean isWildcard() { return wildcard; }
    public String getTableName() { return tableName; }
}
