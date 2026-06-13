package io.github.xinfra.lab.xdb.parser.ast;

public class ColumnDef {
    private final String name;
    private final String dataType;
    private final String typeName;
    private final Integer length;
    private final Integer decimals;
    private final boolean unsigned;
    private final boolean nullable;
    private final ExprNode defaultValue;
    private final boolean autoIncrement;
    private final boolean primaryKey;
    private final String comment;

    private ColumnDef(Builder builder) {
        this.name = builder.name;
        this.dataType = builder.dataType;
        this.typeName = builder.typeName;
        this.length = builder.length;
        this.decimals = builder.decimals;
        this.unsigned = builder.unsigned;
        this.nullable = builder.nullable;
        this.defaultValue = builder.defaultValue;
        this.autoIncrement = builder.autoIncrement;
        this.primaryKey = builder.primaryKey;
        this.comment = builder.comment;
    }

    public String getName() { return name; }
    public String getDataType() { return dataType; }
    public String getTypeName() { return typeName; }
    public Integer getLength() { return length; }
    public Integer getDecimals() { return decimals; }
    public boolean isUnsigned() { return unsigned; }
    public boolean isNullable() { return nullable; }
    public ExprNode getDefaultValue() { return defaultValue; }
    public boolean isAutoIncrement() { return autoIncrement; }
    public boolean isPrimaryKey() { return primaryKey; }
    public String getComment() { return comment; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String name;
        private String dataType;
        private String typeName;
        private Integer length;
        private Integer decimals;
        private boolean unsigned;
        private boolean nullable = true;
        private ExprNode defaultValue;
        private boolean autoIncrement;
        private boolean primaryKey;
        private String comment;

        public Builder name(String name) { this.name = name; return this; }
        public Builder dataType(String dataType) { this.dataType = dataType; return this; }
        public Builder typeName(String typeName) { this.typeName = typeName; return this; }
        public Builder length(Integer length) { this.length = length; return this; }
        public Builder decimals(Integer decimals) { this.decimals = decimals; return this; }
        public Builder unsigned(boolean unsigned) { this.unsigned = unsigned; return this; }
        public Builder nullable(boolean nullable) { this.nullable = nullable; return this; }
        public Builder defaultValue(ExprNode defaultValue) { this.defaultValue = defaultValue; return this; }
        public Builder autoIncrement(boolean autoIncrement) { this.autoIncrement = autoIncrement; return this; }
        public Builder primaryKey(boolean primaryKey) { this.primaryKey = primaryKey; return this; }
        public Builder comment(String comment) { this.comment = comment; return this; }

        public ColumnDef build() { return new ColumnDef(this); }
    }
}
