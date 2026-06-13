package io.github.xinfra.lab.xdb.server;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.session.ExecuteResult;
import io.github.xinfra.lab.xdb.session.Session;
import io.github.xinfra.lab.xdb.session.SessionManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimal {@link SessionManager} for standalone startup.
 * <p>
 * Sessions created by this manager respond to every query with an error
 * indicating that no backend is configured.  This is useful for verifying
 * that the MySQL protocol layer works without requiring a running x-kv cluster.
 */
class StubSessionManager implements SessionManager {

    private static final AtomicLong idGen = new AtomicLong(0);

    @Override
    public Session createSession() {
        return new StubSession(idGen.incrementAndGet());
    }

    @Override
    public void close() {
        // nothing to clean up
    }

    // -------------------------------------------------------------------

    private static class StubSession implements Session {

        private final long id;
        private String currentDatabase;

        StubSession(long id) {
            this.id = id;
        }

        @Override
        public ExecuteResult execute(String sql) {
            throw XDBException.internal(
                    "Standalone mode: no backend configured. "
                            + "Use x-db-test for a fully wired server.");
        }

        @Override
        public void begin(boolean optimistic) {
            throw XDBException.internal("Standalone mode: transactions not available.");
        }

        @Override
        public void commit() {
            throw XDBException.internal("Standalone mode: transactions not available.");
        }

        @Override
        public void rollback() {
            throw XDBException.internal("Standalone mode: transactions not available.");
        }

        @Override
        public void useDatabase(String dbName) {
            this.currentDatabase = dbName;
        }

        @Override
        public String currentDatabase() {
            return currentDatabase;
        }

        @Override
        public void close() {
            // nothing to clean up
        }

        @Override
        public long id() {
            return id;
        }

        @Override
        public io.github.xinfra.lab.xdb.session.SessionState state() {
            return null;
        }
    }
}
