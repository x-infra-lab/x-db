package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.ddl.DDLExecutor;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of {@link SessionManager}.
 * <p>
 * Manages the lifecycle of all active sessions. Thread-safe: multiple
 * connections can create/close sessions concurrently.
 */
public class SessionManagerImpl implements SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManagerImpl.class);

    private final AtomicLong nextSessionId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, Session> sessions = new ConcurrentHashMap<>();

    private final InfoSchemaHolder schemaHolder;
    private final DDLExecutor ddlExecutor;
    private final MetaStore metaStore;
    private final TransactionManager.TxnStarter txnStarter;
    private final TransactionManager.TxnCommitter txnCommitter;
    private final TransactionManager.TxnRollbacker txnRollbacker;
    private final TransactionManager.TxnContextFactory txnContextFactory;

    public SessionManagerImpl(InfoSchemaHolder schemaHolder,
                              DDLExecutor ddlExecutor,
                              MetaStore metaStore,
                              TransactionManager.TxnStarter txnStarter,
                              TransactionManager.TxnCommitter txnCommitter,
                              TransactionManager.TxnRollbacker txnRollbacker,
                              TransactionManager.TxnContextFactory txnContextFactory) {
        this.schemaHolder = schemaHolder;
        this.ddlExecutor = ddlExecutor;
        this.metaStore = metaStore;
        this.txnStarter = txnStarter;
        this.txnCommitter = txnCommitter;
        this.txnRollbacker = txnRollbacker;
        this.txnContextFactory = txnContextFactory;
    }

    @Override
    public Session createSession() {
        long id = nextSessionId.getAndIncrement();
        TransactionManager txnManager = new TransactionManager(
                txnStarter, txnCommitter, txnRollbacker, txnContextFactory);
        SessionImpl session = new SessionImpl(id, schemaHolder, ddlExecutor,
                txnManager, metaStore, () -> removeSession(id));
        sessions.put(id, session);
        log.info("Session created: id={}", id);
        return session;
    }

    /**
     * Retrieve an existing session by ID.
     *
     * @param id session ID
     * @return the session, or {@code null} if not found
     */
    public Session getSession(long id) {
        return sessions.get(id);
    }

    /**
     * Remove a session from management (typically after it is closed).
     *
     * @param id session ID
     */
    public void removeSession(long id) {
        Session session = sessions.remove(id);
        if (session != null) {
            log.info("Session removed: id={}", id);
        }
    }

    /**
     * Return an unmodifiable view of all active sessions.
     */
    public Collection<Session> allSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * Return the number of active sessions.
     */
    public int activeCount() {
        return sessions.size();
    }

    @Override
    public void close() {
        log.info("Closing all sessions (count={})", sessions.size());
        for (Session session : sessions.values()) {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("Failed to close session {}", session.id(), e);
            }
        }
        sessions.clear();
    }
}
