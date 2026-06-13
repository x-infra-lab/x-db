package io.github.xinfra.lab.xdb.session;

/**
 * Factory and registry for {@link Session} instances.
 */
public interface SessionManager {

    /**
     * Create a new session.
     *
     * @return a fresh session
     */
    Session createSession();

    /**
     * Close all active sessions and release resources.
     */
    void close();
}
