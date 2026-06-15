package io.github.xinfra.lab.xdb.common;

public class XDBException extends RuntimeException {
    private final int errorCode;
    private final String sqlState;

    public XDBException(int errorCode, String sqlState, String message) {
        super(message);
        this.errorCode = errorCode;
        this.sqlState = sqlState;
    }

    public XDBException(int errorCode, String sqlState, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.sqlState = sqlState;
    }

    public int errorCode() { return errorCode; }
    public String sqlState() { return sqlState; }

    public static final int ER_DUP_KEY = 1022;
    public static final int ER_NO_DB = 1046;
    public static final int ER_BAD_NULL = 1048;
    public static final int ER_BAD_DB = 1049;
    public static final int ER_TABLE_EXISTS = 1050;
    public static final int ER_BAD_TABLE = 1051;
    public static final int ER_BAD_FIELD = 1054;
    public static final int ER_DUP_ENTRY = 1062;
    public static final int ER_PARSE_ERROR = 1064;
    public static final int ER_EMPTY_QUERY = 1065;
    public static final int ER_INDEX_NOT_FOUND = 1091;
    public static final int ER_UNKNOWN_ERROR = 1105;
    public static final int ER_WRONG_VALUE_COUNT = 1136;
    public static final int ER_NO_SUCH_TABLE = 1146;
    public static final int ER_SYNTAX_ERROR = 1149;
    public static final int ER_LOCK_WAIT_TIMEOUT = 1205;
    public static final int ER_LOCK_DEADLOCK = 1213;
    public static final int ER_TRUNCATED_WRONG_VALUE = 1292;
    public static final int ER_SCHEMA_CHANGED = 1815;
    public static final int ER_DB_EXISTS = 1007;
    public static final int ER_DB_DROP_EXISTS = 1008;
    public static final int ER_DATA_OUT_OF_RANGE = 1690;
    public static final int ER_MEM_EXCEEDED = 8175;

    public static XDBException parseError(String message) {
        return new XDBException(ER_PARSE_ERROR, "42000", message);
    }

    public static XDBException noDatabase() {
        return new XDBException(ER_NO_DB, "3D000", "No database selected");
    }

    public static XDBException badDatabase(String name) {
        return new XDBException(ER_BAD_DB, "42000", "Unknown database '" + name + "'");
    }

    public static XDBException tableExists(String name) {
        return new XDBException(ER_TABLE_EXISTS, "42S01", "Table '" + name + "' already exists");
    }

    public static XDBException tableNotFound(String name) {
        return new XDBException(ER_NO_SUCH_TABLE, "42S02", "Table '" + name + "' doesn't exist");
    }

    public static XDBException badField(String name) {
        return new XDBException(ER_BAD_FIELD, "42S22", "Unknown column '" + name + "'");
    }

    public static XDBException dupEntry(String key, String indexName) {
        return new XDBException(ER_DUP_ENTRY, "23000",
            "Duplicate entry '" + key + "' for key '" + indexName + "'");
    }

    public static XDBException internal(String message) {
        return new XDBException(ER_UNKNOWN_ERROR, "HY000", message);
    }

    public static XDBException internal(String message, Throwable cause) {
        return new XDBException(ER_UNKNOWN_ERROR, "HY000", message, cause);
    }

    public static XDBException memoryExceeded(String operator, long consumed, long limit) {
        return new XDBException(ER_MEM_EXCEEDED, "HY000",
            String.format("Operator '%s' exceeded memory limit: %d bytes (limit: %d bytes)",
                operator, consumed, limit));
    }

    public static XDBException dataOutOfRange(String message) {
        return new XDBException(ER_DATA_OUT_OF_RANGE, "22003", message);
    }

    public static XDBException txnTimeout(long elapsedMs, long limitMs) {
        return new XDBException(ER_LOCK_WAIT_TIMEOUT, "40001",
            String.format("Transaction has timed out (elapsed: %dms, limit: %dms)", elapsedMs, limitMs));
    }

    public static XDBException dbExists(String name) {
        return new XDBException(ER_DB_EXISTS, "HY000",
            "Can't create database '" + name + "'; database exists");
    }
}
