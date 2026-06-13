package io.github.xinfra.lab.xdb.server;

/**
 * MySQL wire-protocol constants.
 */
public final class MySQLConstants {

    private MySQLConstants() {}

    // ---- Command types ----
    public static final int COM_SLEEP = 0x00;
    public static final int COM_QUIT = 0x01;
    public static final int COM_INIT_DB = 0x02;
    public static final int COM_QUERY = 0x03;
    public static final int COM_FIELD_LIST = 0x04;
    public static final int COM_PING = 0x0E;
    public static final int COM_STMT_PREPARE = 0x16;
    public static final int COM_STMT_EXECUTE = 0x17;
    public static final int COM_STMT_CLOSE = 0x19;

    // ---- Packet type markers ----
    public static final int OK_PACKET = 0x00;
    public static final int EOF_PACKET = 0xFE;
    public static final int ERR_PACKET = 0xFF;

    // ---- Column types (MySQL wire format) ----
    public static final int MYSQL_TYPE_DECIMAL = 0x00;
    public static final int MYSQL_TYPE_TINY = 0x01;
    public static final int MYSQL_TYPE_SHORT = 0x02;
    public static final int MYSQL_TYPE_LONG = 0x03;
    public static final int MYSQL_TYPE_FLOAT = 0x04;
    public static final int MYSQL_TYPE_DOUBLE = 0x05;
    public static final int MYSQL_TYPE_NULL = 0x06;
    public static final int MYSQL_TYPE_TIMESTAMP = 0x07;
    public static final int MYSQL_TYPE_LONGLONG = 0x08;
    public static final int MYSQL_TYPE_INT24 = 0x09;
    public static final int MYSQL_TYPE_DATE = 0x0A;
    public static final int MYSQL_TYPE_DATETIME = 0x0C;
    public static final int MYSQL_TYPE_VARCHAR = 0x0F;
    public static final int MYSQL_TYPE_NEWDECIMAL = 0xF6;
    public static final int MYSQL_TYPE_BLOB = 0xFC;
    public static final int MYSQL_TYPE_VAR_STRING = 0xFD;
    public static final int MYSQL_TYPE_STRING = 0xFE;

    // ---- Server status flags ----
    public static final int SERVER_STATUS_IN_TRANS = 0x0001;
    public static final int SERVER_STATUS_AUTOCOMMIT = 0x0002;
    public static final int SERVER_MORE_RESULTS_EXISTS = 0x0008;

    // ---- Capability flags ----
    public static final int CLIENT_LONG_PASSWORD = 0x00000001;
    public static final int CLIENT_PROTOCOL_41 = 0x00000200;
    public static final int CLIENT_SECURE_CONNECTION = 0x00008000;
    public static final int CLIENT_PLUGIN_AUTH = 0x00080000;
    public static final int CLIENT_CONNECT_WITH_DB = 0x00000008;
    public static final int CLIENT_DEPRECATE_EOF = 0x01000000;

    // ---- Character sets ----
    public static final int CHARSET_UTF8MB4 = 45;

    // ---- Column flags ----
    public static final int COLUMN_FLAG_NOT_NULL = 0x0001;
    public static final int COLUMN_FLAG_PRI_KEY = 0x0002;
    public static final int COLUMN_FLAG_UNIQUE_KEY = 0x0004;
    public static final int COLUMN_FLAG_BLOB = 0x0010;
    public static final int COLUMN_FLAG_UNSIGNED = 0x0020;
    public static final int COLUMN_FLAG_AUTO_INCREMENT = 0x0200;
    public static final int COLUMN_FLAG_BINARY = 0x0080;
}
