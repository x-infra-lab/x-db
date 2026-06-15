package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the transaction lifecycle for a single {@link Session}.
 * <p>
 * All public methods are {@code synchronized} to guard against concurrent
 * access from the query-execution thread and the server-shutdown thread
 * (which calls {@link Session#close()}).
 * <p>
 * Transaction operations are decoupled from the concrete KV client through
 * functional interfaces so that the session module does not depend on
 * internal x-kv types.
 */
public class TransactionManager {

    private static final Logger log = LoggerFactory.getLogger(TransactionManager.class);

    @FunctionalInterface
    public interface TxnStarter {
        Object beginTxn(boolean pessimistic) throws Exception;
    }

    @FunctionalInterface
    public interface TxnCommitter {
        void commit(Object txn) throws Exception;
    }

    @FunctionalInterface
    public interface TxnRollbacker {
        void rollback(Object txn) throws Exception;
    }

    @FunctionalInterface
    public interface TxnContextFactory {
        TransactionContext createContext(Object txn, EvalContext evalContext);
    }

    private final TxnStarter starter;
    private final TxnCommitter committer;
    private final TxnRollbacker rollbacker;
    private final TxnContextFactory contextFactory;

    private Object currentTxn;
    private TransactionContext currentContext;
    private boolean active;
    private long txnStartTimeNanos;
    private long txnTimeoutMs = 60_000;

    public TransactionManager(TxnStarter starter,
                              TxnCommitter committer,
                              TxnRollbacker rollbacker,
                              TxnContextFactory contextFactory) {
        this.starter = starter;
        this.committer = committer;
        this.rollbacker = rollbacker;
        this.contextFactory = contextFactory;
    }

    public synchronized void setTxnTimeout(long timeoutMs) {
        this.txnTimeoutMs = timeoutMs;
    }

    public synchronized long getTxnTimeout() {
        return txnTimeoutMs;
    }

    public synchronized TransactionContext beginIfNeeded(boolean pessimistic) {
        if (active) {
            checkTimeout();
        } else {
            begin(pessimistic);
        }
        return currentContext;
    }

    public synchronized void begin(boolean pessimistic) {
        if (active) {
            commit();
        }
        try {
            this.currentTxn = starter.beginTxn(pessimistic);
            this.currentContext = contextFactory.createContext(currentTxn, new EvalContext());
            this.txnStartTimeNanos = System.nanoTime();
            this.active = true;
            log.debug("Transaction started (pessimistic={})", pessimistic);
        } catch (Exception e) {
            throw new RuntimeException("Failed to begin transaction", e);
        }
    }

    public synchronized void commit() {
        if (!active) {
            throw new IllegalStateException("No active transaction to commit");
        }
        try {
            committer.commit(currentTxn);
            log.debug("Transaction committed");
        } catch (Exception e) {
            throw new RuntimeException("Failed to commit transaction", e);
        } finally {
            reset();
        }
    }

    public synchronized void rollback() {
        if (!active) {
            throw new IllegalStateException("No active transaction to rollback");
        }
        try {
            rollbacker.rollback(currentTxn);
            log.debug("Transaction rolled back");
        } catch (Exception e) {
            throw new RuntimeException("Failed to rollback transaction", e);
        } finally {
            reset();
        }
    }

    public synchronized void rollbackQuietly() {
        if (!active) {
            return;
        }
        try {
            rollbacker.rollback(currentTxn);
            log.debug("Transaction rolled back (quiet)");
        } catch (Exception e) {
            log.warn("Failed to rollback transaction during cleanup", e);
        } finally {
            reset();
        }
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized TransactionContext currentContext() {
        checkTimeout();
        return currentContext;
    }

    private void checkTimeout() {
        if (active && txnTimeoutMs > 0) {
            long elapsedMs = (System.nanoTime() - txnStartTimeNanos) / 1_000_000;
            if (elapsedMs > txnTimeoutMs) {
                log.warn("Transaction timed out after {}ms (limit: {}ms)", elapsedMs, txnTimeoutMs);
                rollbackQuietly();
                throw XDBException.txnTimeout(elapsedMs, txnTimeoutMs);
            }
        }
    }

    private void reset() {
        this.currentTxn = null;
        this.currentContext = null;
        this.active = false;
    }
}
