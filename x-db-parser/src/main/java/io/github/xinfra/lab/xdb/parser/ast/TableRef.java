package io.github.xinfra.lab.xdb.parser.ast;

/**
 * Represents a table reference in FROM clauses.
 */
public interface TableRef {

    class SimpleTableRef implements TableRef {
        private final String name;
        private final String alias;

        public SimpleTableRef(String name, String alias) {
            this.name = name;
            this.alias = alias;
        }

        public String getName() { return name; }
        public String getAlias() { return alias; }
    }

    class JoinTableRef implements TableRef {
        private final TableRef left;
        private final TableRef right;
        private final JoinType joinType;
        private final ExprNode condition;

        public JoinTableRef(TableRef left, TableRef right, JoinType joinType, ExprNode condition) {
            this.left = left;
            this.right = right;
            this.joinType = joinType;
            this.condition = condition;
        }

        public TableRef getLeft() { return left; }
        public TableRef getRight() { return right; }
        public JoinType getJoinType() { return joinType; }
        public ExprNode getCondition() { return condition; }
    }

    class SubqueryTableRef implements TableRef {
        private final SelectStmt query;
        private final String alias;

        public SubqueryTableRef(SelectStmt query, String alias) {
            this.query = query;
            this.alias = alias;
        }

        public SelectStmt getQuery() { return query; }
        public String getAlias() { return alias; }
    }
}
