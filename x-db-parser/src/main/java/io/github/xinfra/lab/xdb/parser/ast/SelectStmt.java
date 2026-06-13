package io.github.xinfra.lab.xdb.parser.ast;

import java.util.List;

public class SelectStmt implements Statement {
    private final boolean distinct;
    private final List<SelectItem> selectItems;
    private final TableRef from;
    private final ExprNode where;
    private final List<ExprNode> groupBy;
    private final ExprNode having;
    private final List<OrderByItem> orderBy;
    private final ExprNode limit;
    private final ExprNode offset;
    private final boolean forUpdate;

    private SelectStmt(Builder builder) {
        this.distinct = builder.distinct;
        this.selectItems = builder.selectItems;
        this.from = builder.from;
        this.where = builder.where;
        this.groupBy = builder.groupBy;
        this.having = builder.having;
        this.orderBy = builder.orderBy;
        this.limit = builder.limit;
        this.offset = builder.offset;
        this.forUpdate = builder.forUpdate;
    }

    public boolean isDistinct() { return distinct; }
    public List<SelectItem> getSelectItems() { return selectItems; }
    public TableRef getFrom() { return from; }
    public ExprNode getWhere() { return where; }
    public List<ExprNode> getGroupBy() { return groupBy; }
    public ExprNode getHaving() { return having; }
    public List<OrderByItem> getOrderBy() { return orderBy; }
    public ExprNode getLimit() { return limit; }
    public ExprNode getOffset() { return offset; }
    public boolean isForUpdate() { return forUpdate; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private boolean distinct;
        private List<SelectItem> selectItems = List.of();
        private TableRef from;
        private ExprNode where;
        private List<ExprNode> groupBy = List.of();
        private ExprNode having;
        private List<OrderByItem> orderBy = List.of();
        private ExprNode limit;
        private ExprNode offset;
        private boolean forUpdate;

        public Builder distinct(boolean distinct) { this.distinct = distinct; return this; }
        public Builder selectItems(List<SelectItem> selectItems) { this.selectItems = selectItems; return this; }
        public Builder from(TableRef from) { this.from = from; return this; }
        public Builder where(ExprNode where) { this.where = where; return this; }
        public Builder groupBy(List<ExprNode> groupBy) { this.groupBy = groupBy; return this; }
        public Builder having(ExprNode having) { this.having = having; return this; }
        public Builder orderBy(List<OrderByItem> orderBy) { this.orderBy = orderBy; return this; }
        public Builder limit(ExprNode limit) { this.limit = limit; return this; }
        public Builder offset(ExprNode offset) { this.offset = offset; return this; }
        public Builder forUpdate(boolean forUpdate) { this.forUpdate = forUpdate; return this; }

        public SelectStmt build() { return new SelectStmt(this); }
    }
}
