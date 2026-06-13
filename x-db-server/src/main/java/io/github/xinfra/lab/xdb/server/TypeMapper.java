package io.github.xinfra.lab.xdb.server;

import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

/**
 * Maps the internal {@link DataType} to MySQL wire-protocol column types,
 * display lengths, and column flags.
 */
public final class TypeMapper {

    private TypeMapper() {}

    /**
     * Convert an internal {@link DataType} to the corresponding MySQL wire-protocol
     * column type constant.
     */
    public static int toMySQLType(DataType type) {
        switch (type) {
            case TINYINT:
            case BOOLEAN:
                return MySQLConstants.MYSQL_TYPE_TINY;
            case SMALLINT:
                return MySQLConstants.MYSQL_TYPE_SHORT;
            case INT:
                return MySQLConstants.MYSQL_TYPE_LONG;
            case BIGINT:
                return MySQLConstants.MYSQL_TYPE_LONGLONG;
            case FLOAT:
                return MySQLConstants.MYSQL_TYPE_FLOAT;
            case DOUBLE:
                return MySQLConstants.MYSQL_TYPE_DOUBLE;
            case DECIMAL:
                return MySQLConstants.MYSQL_TYPE_NEWDECIMAL;
            case CHAR:
                return MySQLConstants.MYSQL_TYPE_STRING;
            case VARCHAR:
                return MySQLConstants.MYSQL_TYPE_VAR_STRING;
            case TEXT:
                return MySQLConstants.MYSQL_TYPE_BLOB;
            case BINARY:
            case VARBINARY:
            case BLOB:
                return MySQLConstants.MYSQL_TYPE_BLOB;
            case DATE:
                return MySQLConstants.MYSQL_TYPE_DATE;
            case DATETIME:
                return MySQLConstants.MYSQL_TYPE_DATETIME;
            case TIMESTAMP:
                return MySQLConstants.MYSQL_TYPE_TIMESTAMP;
            case TIME:
                return MySQLConstants.MYSQL_TYPE_TIMESTAMP; // TIME maps to TIMESTAMP on wire
            case YEAR:
                return MySQLConstants.MYSQL_TYPE_SHORT;
            case NULL:
                return MySQLConstants.MYSQL_TYPE_NULL;
            default:
                return MySQLConstants.MYSQL_TYPE_VAR_STRING;
        }
    }

    /**
     * Return the default display length (in characters/bytes) for a column type.
     * Used in the column-definition packet.
     */
    public static int columnLength(DataType type) {
        switch (type) {
            case TINYINT:
            case BOOLEAN:
                return 4;    // -128..127 => 4 chars
            case SMALLINT:
                return 6;
            case INT:
                return 11;
            case BIGINT:
                return 20;
            case FLOAT:
                return 12;
            case DOUBLE:
                return 22;
            case DECIMAL:
                return 65;
            case CHAR:
            case VARCHAR:
                return 255;
            case TEXT:
                return 65535;
            case BINARY:
            case VARBINARY:
                return 255;
            case BLOB:
                return 65535;
            case DATE:
                return 10;   // yyyy-MM-dd
            case DATETIME:
            case TIMESTAMP:
                return 19;   // yyyy-MM-dd HH:mm:ss
            case TIME:
                return 10;
            case YEAR:
                return 4;
            case NULL:
                return 0;
            default:
                return 255;
        }
    }

    /**
     * Compute MySQL column flags from a {@link ColumnInfo}.
     */
    public static int columnFlags(ColumnInfo col) {
        int flags = 0;
        if (!col.isNullable()) {
            flags |= MySQLConstants.COLUMN_FLAG_NOT_NULL;
        }
        if (col.isUnsigned()) {
            flags |= MySQLConstants.COLUMN_FLAG_UNSIGNED;
        }
        if (col.isAutoIncrement()) {
            flags |= MySQLConstants.COLUMN_FLAG_AUTO_INCREMENT;
        }
        if (col.getType().isBinary()) {
            flags |= MySQLConstants.COLUMN_FLAG_BINARY;
        }
        return flags;
    }
}
