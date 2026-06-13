package io.github.xinfra.lab.xdb.expression;

public enum DataType {
    TINYINT(1, true), SMALLINT(2, true), INT(4, true), BIGINT(8, true),
    FLOAT(4, true), DOUBLE(8, true), DECIMAL(-1, true),
    CHAR(-1, false), VARCHAR(-1, false), TEXT(-1, false),
    BINARY(-1, false), VARBINARY(-1, false), BLOB(-1, false),
    DATE(3, false), DATETIME(8, false), TIMESTAMP(4, false), TIME(3, false), YEAR(1, true),
    BOOLEAN(1, true), NULL(0, false);

    private final int fixedLength;
    private final boolean numeric;

    DataType(int fixedLength, boolean numeric) {
        this.fixedLength = fixedLength;
        this.numeric = numeric;
    }

    public int fixedLength() { return fixedLength; }
    public boolean isNumeric() { return numeric; }
    public boolean isInteger() {
        return this == TINYINT || this == SMALLINT || this == INT || this == BIGINT
                || this == YEAR || this == BOOLEAN;
    }
    public boolean isFloat() { return this == FLOAT || this == DOUBLE; }
    public boolean isString() { return this == CHAR || this == VARCHAR || this == TEXT; }
    public boolean isBinary() { return this == BINARY || this == VARBINARY || this == BLOB; }
    public boolean isTemporal() { return this == DATE || this == DATETIME || this == TIMESTAMP || this == TIME; }

    public static DataType fromMySQLType(String typeName) {
        return switch (typeName.toUpperCase()) {
            case "TINYINT" -> TINYINT;
            case "SMALLINT" -> SMALLINT;
            case "INT", "INTEGER", "MEDIUMINT" -> INT;
            case "BIGINT" -> BIGINT;
            case "FLOAT" -> FLOAT;
            case "DOUBLE", "REAL" -> DOUBLE;
            case "DECIMAL", "NUMERIC", "DEC" -> DECIMAL;
            case "CHAR" -> CHAR;
            case "VARCHAR" -> VARCHAR;
            case "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT" -> TEXT;
            case "BINARY" -> BINARY;
            case "VARBINARY" -> VARBINARY;
            case "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB" -> BLOB;
            case "DATE" -> DATE;
            case "DATETIME" -> DATETIME;
            case "TIMESTAMP" -> TIMESTAMP;
            case "TIME" -> TIME;
            case "YEAR" -> YEAR;
            case "BOOLEAN", "BOOL", "BIT" -> BOOLEAN;
            default -> throw new IllegalArgumentException("Unknown type: " + typeName);
        };
    }
}
