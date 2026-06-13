package io.github.xinfra.lab.xdb.session;

/**
 * Lifecycle states of a {@link Session}.
 */
public enum SessionState {

    /** The session is idle (not in a transaction). */
    IDLE,

    /** The session is inside an explicit transaction. */
    IN_TRANSACTION,

    /** The session is being closed. */
    CLOSING,

    /** The session has been closed. */
    CLOSED
}
