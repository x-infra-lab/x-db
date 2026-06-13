package io.github.xinfra.lab.xdb.session;

import java.util.HashMap;
import java.util.Map;

/**
 * Session-scoped variables that mirror MySQL system variables.
 * <p>
 * Well-known variables (charset, collation, autocommit, etc.) are stored as
 * typed fields for efficient access. User-defined or unrecognised variables
 * are kept in a general {@code Map}.
 */
public class SessionVariable {

    private String charset = "utf8mb4";
    private String collation = "utf8mb4_general_ci";
    private boolean autoCommit = true;
    private String transactionIsolation = "REPEATABLE-READ";
    private long queryTimeout = 0;
    private boolean readOnly = false;

    /** Catch-all map for user-defined / unrecognised variables. */
    private final Map<String, String> variables = new HashMap<>();

    // ----------------------------------------------------------------
    // Generic get / set — routes well-known names to typed fields.
    // ----------------------------------------------------------------

    /**
     * Retrieve a variable value by name (case-insensitive).
     *
     * @param name variable name
     * @return the current value as a string, or {@code null} if not set
     */
    public String get(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase();
        switch (lower) {
            case "charset":
            case "character_set_client":
            case "character_set_connection":
            case "character_set_results":
                return charset;
            case "collation":
            case "collation_connection":
                return collation;
            case "autocommit":
                return autoCommit ? "ON" : "OFF";
            case "transaction_isolation":
            case "tx_isolation":
                return transactionIsolation;
            case "query_timeout":
            case "max_execution_time":
                return Long.toString(queryTimeout);
            case "transaction_read_only":
            case "tx_read_only":
                return readOnly ? "ON" : "OFF";
            default:
                return variables.get(lower);
        }
    }

    /**
     * Set a variable value by name (case-insensitive).
     *
     * @param name  variable name
     * @param value new value
     */
    public void set(String name, String value) {
        if (name == null) {
            return;
        }
        String lower = name.toLowerCase();
        switch (lower) {
            case "charset":
            case "character_set_client":
            case "character_set_connection":
            case "character_set_results":
                this.charset = value;
                break;
            case "collation":
            case "collation_connection":
                this.collation = value;
                break;
            case "autocommit":
                this.autoCommit = toBool(value);
                break;
            case "transaction_isolation":
            case "tx_isolation":
                this.transactionIsolation = value;
                break;
            case "query_timeout":
            case "max_execution_time":
                this.queryTimeout = Long.parseLong(value);
                break;
            case "transaction_read_only":
            case "tx_read_only":
                this.readOnly = toBool(value);
                break;
            default:
                variables.put(lower, value);
                break;
        }
    }

    // ----------------------------------------------------------------
    // Typed getters and setters for well-known variables.
    // ----------------------------------------------------------------

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public String getCollation() {
        return collation;
    }

    public void setCollation(String collation) {
        this.collation = collation;
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    public String getTransactionIsolation() {
        return transactionIsolation;
    }

    public void setTransactionIsolation(String transactionIsolation) {
        this.transactionIsolation = transactionIsolation;
    }

    public long getQueryTimeout() {
        return queryTimeout;
    }

    public void setQueryTimeout(long queryTimeout) {
        this.queryTimeout = queryTimeout;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    // ----------------------------------------------------------------
    // Helper
    // ----------------------------------------------------------------

    private static boolean toBool(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toUpperCase();
        return "1".equals(v) || "ON".equals(v) || "TRUE".equals(v) || "YES".equals(v);
    }
}
