package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the transaction lifecycle for a single {@link Session}.
 * <p>
 * Transaction operations are decoupled from the concrete KV client through
 * functional interfaces so that the session module does not depend on
 * internal x-kv types.
 */
public class TransactionManager {

    private static final Logger log = LoggerFactory.getLogger(TransactionManager.class);

    // ---------------------------------------------------------------
    // Functional interfaces that bridge to the actual KV client.
    // The {@code Object txn} parameter is the opaque KV transaction
    // handle (e.g. {@code Transaction} from x-kv).
    // ---------------------------------------------------------------

    @FunctionalInterface
    public interface TxnStarter {
        /**
         * Begin a new KV transaction.
         *
         * @param pessimistic {@code true} for pessimistic mode
         * @return opaque transaction handle
         */
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
        /**
         * Create a {@link TransactionContext} from the opaque transaction handle.
         */
        TransactionContext createContext(Object txn, EvalContext evalContext);
    }

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    private final TxnStarter starter;
    private final TxnCommitter committer;
    private final TxnRollbacker rollbacker;
    private final TxnContextFactory contextFactory;

    private Object currentTxn;
    private TransactionContext currentContext;
    private boolean active;

    public TransactionManager(TxnStarter starter,
                              TxnCommitter committer,
                              TxnRollbacker rollbacker,
                              TxnContextFactory contextFactory) {
        this.starter = starter;
        this.committer = committer;
        this.rollbacker = rollbacker;
        this.contextFactory = contextFactory;
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Begin a transaction if one is not already active.
     *
     * @param pessimistic whether to use pessimistic locking
     * @return the {@link TransactionContext} for the (possibly new) transaction
     */
    public TransactionContext beginIfNeeded(boolean pessimistic) {
        if (!active) {
            begin(pessimistic);
        }
        return currentContext;
    }

    /**
     * Explicitly begin a new transaction.
     *
     * @param pessimistic whether to use pessimistic locking
     * @throws IllegalStateException if a transaction is already active
     */
    public void begin(boolean pessimistic) {
        if (active) {
            // Implicitly commit the current transaction before starting a new one,
            // matching MySQL behaviour for nested BEGIN.
            commit();
        }
        try {
            this.currentTxn = starter.beginTxn(pessimistic);
            this.currentContext = contextFactory.createContext(currentTxn, new EvalContext());
            this.active = true;
            log.debug("Transaction started (pessimistic={})", pessimistic);
        } catch (Exception e) {
            throw new RuntimeException("Failed to begin transaction", e);
        }
    }

    /**
     * Commit the current transaction.
     *
     * @throws IllegalStateException if no transaction is active
     */
    public void commit() {
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

    /**
     * Rollback the current transaction.
     *
     * @throws IllegalStateException if no transaction is active
     */
    public void rollback() {
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

    /**
     * Rollback the current transaction silently (used during error recovery).
     * Does nothing if no transaction is active.
     */
    public void rollbackQuietly() {
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

    /**
     * Return whether a transaction is currently active.
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Return the {@link TransactionContext} for the current transaction,
     * or {@code null} if none is active.
     */
    public TransactionContext currentContext() {
        return currentContext;
    }

    // ---------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------

    private void reset() {
        this.currentTxn = null;
        this.currentContext = null;
        this.active = false;
    }
}
