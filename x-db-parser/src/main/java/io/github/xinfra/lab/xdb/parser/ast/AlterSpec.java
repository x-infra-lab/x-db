package io.github.xinfra.lab.xdb.parser.ast;

import java.util.List;

/**
 * Represents a single alteration specification within an ALTER TABLE statement.
 */
public interface AlterSpec {

    class AddColumn implements AlterSpec {
        private final ColumnDef columnDef;
        private final String afterColumn;
        private final boolean first;

        public AddColumn(ColumnDef columnDef, String afterColumn, boolean first) {
            this.columnDef = columnDef;
            this.afterColumn = afterColumn;
            this.first = first;
        }

        public ColumnDef getColumnDef() { return columnDef; }
        public String getAfterColumn() { return afterColumn; }
        public boolean isFirst() { return first; }
    }

    class DropColumn implements AlterSpec {
        private final String columnName;

        public DropColumn(String columnName) {
            this.columnName = columnName;
        }

        public String getColumnName() { return columnName; }
    }

    class AddIndex implements AlterSpec {
        private final String indexName;
        private final List<IndexColumn> columns;

        public AddIndex(String indexName, List<IndexColumn> columns) {
            this.indexName = indexName;
            this.columns = columns;
        }

        public String getIndexName() { return indexName; }
        public List<IndexColumn> getColumns() { return columns; }
    }

    class DropIndex implements AlterSpec {
        private final String indexName;

        public DropIndex(String indexName) {
            this.indexName = indexName;
        }

        public String getIndexName() { return indexName; }
    }
}
