package io.github.xinfra.lab.xdb.session;

/**
 * A client session that processes SQL statements.
 * <p>
 * Each MySQL connection is backed by one Session instance which holds the
 * current database, transaction state, and session variables.
 */
public interface Session {

    /**
     * Execute a single SQL statement and return the result.
     *
     * @param sql the SQL text to execute
     * @return the execution result
     */
    ExecuteResult execute(String sql);

    /**
     * Begin an explicit transaction.
     *
     * @param optimistic whether to use optimistic concurrency control
     */
    void begin(boolean optimistic);

    /**
     * Commit the current transaction.
     */
    void commit();

    /**
     * Rollback the current transaction.
     */
    void rollback();

    /**
     * Switch the current database (USE statement).
     *
     * @param dbName database name
     */
    void useDatabase(String dbName);

    /**
     * Return the current database name, or {@code null} if none is selected.
     */
    String currentDatabase();

    /**
     * Close this session and release resources.
     */
    void close();

    /**
     * Return the unique session ID.
     */
    long id();

    /**
     * Return the current session state.
     */
    SessionState state();
}
