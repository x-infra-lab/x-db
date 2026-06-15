package io.github.xinfra.lab.xdb.ddl;

import io.github.xinfra.lab.xdb.meta.SchemaState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background worker thread that processes DDL jobs.
 *
 * <p>The DDL worker runs in a loop:
 * <ol>
 *   <li>If this node is the DDL owner, dequeue and process jobs</li>
 *   <li>If not, try to become the owner</li>
 * </ol>
 *
 * <p>The 2-version invariant is maintained by waiting {@link #STATE_WAIT_MS}
 * between each schema state transition, ensuring all nodes have observed the
 * new schema version before advancing to the next state.
 */
public class DDLWorker implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(DDLWorker.class);

    private static final long POLL_INTERVAL_MS = 1000;
    private static final long STATE_WAIT_MS = 4000; // Simplified: 2 * lease duration in practice

    private final DDLOwnerManager ownerManager;
    private final DDLJobQueue jobQueue;
    private final SchemaChangeExecutor schemaChangeExecutor;
    private final Runnable schemaReloadCallback;
    private volatile boolean running = true;

    public DDLWorker(DDLOwnerManager ownerManager, DDLJobQueue jobQueue,
                     SchemaChangeExecutor schemaChangeExecutor,
                     Runnable schemaReloadCallback) {
        this.ownerManager = ownerManager;
        this.jobQueue = jobQueue;
        this.schemaChangeExecutor = schemaChangeExecutor;
        this.schemaReloadCallback = schemaReloadCallback;
    }

    @Override
    public void run() {
        log.info("DDL worker started");
        while (running) {
            try {
                if (ownerManager.isOwner()) {
                    ownerManager.renewLease();
                    processJobs();
                } else {
                    ownerManager.tryBecomeOwner();
                }
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("DDL worker interrupted, shutting down");
                break;
            } catch (Exception e) {
                log.error("DDL worker encountered an error", e);
            }
        }
        log.info("DDL worker stopped");
    }

    private void processJobs() {
        DDLJob job = jobQueue.dequeue();
        if (job == null) {
            return;
        }

        log.info("Processing DDL job: id={}, type={}, dbId={}, tableId={}",
                job.getId(), job.getType(), job.getDbId(), job.getTableId());

        job.setState(DDLState.RUNNING);
        jobQueue.updateJob(job);

        SchemaState stateBeforeTransition = job.getSchemaState();

        try {
            while (job.getSchemaState() != null) {
                if (!ownerManager.renewLease()) {
                    throw new DDLLeaseExpiredException(
                            "Lost DDL owner lease during job " + job.getId());
                }

                stateBeforeTransition = job.getSchemaState();
                schemaChangeExecutor.execute(job);
                schemaReloadCallback.run();

                if (job.getSchemaState() != null) {
                    log.debug("Waiting {}ms for schema version propagation (2-version invariant), job={}",
                            STATE_WAIT_MS, job.getId());
                    Thread.sleep(STATE_WAIT_MS);
                }
            }

            job.setState(DDLState.DONE);
            job.setFinishTs(System.currentTimeMillis());
            log.info("DDL job completed: id={}, type={}, version={}", job.getId(), job.getType(), job.getVersion());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.setError("DDL job interrupted");
            log.warn("DDL job interrupted: id={}", job.getId());
            rollbackSchema(job, stateBeforeTransition);
            if (job.getState() != DDLState.ROLLED_BACK) {
                job.setState(DDLState.FAILED);
            }
        } catch (DDLLeaseExpiredException e) {
            job.setError(e.getMessage());
            log.error("DDL job lost lease: id={}, error={}", job.getId(), e.getMessage());
            rollbackSchema(job, stateBeforeTransition);
            if (job.getState() != DDLState.ROLLED_BACK) {
                job.setState(DDLState.FAILED);
            }
        } catch (Exception e) {
            job.setError(e.getMessage());
            log.error("DDL job failed: id={}, error={}", job.getId(), e.getMessage(), e);
            rollbackSchema(job, stateBeforeTransition);
            if (job.getState() != DDLState.ROLLED_BACK) {
                job.setState(DDLState.FAILED);
            }
        }

        jobQueue.updateJob(job);
        jobQueue.moveToHistory(job);
        schemaReloadCallback.run();
    }

    private void rollbackSchema(DDLJob job, SchemaState failedAt) {
        if (failedAt == null) {
            return;
        }
        try {
            job.setState(DDLState.ROLLING_BACK);
            schemaChangeExecutor.rollback(job, failedAt);
            schemaReloadCallback.run();
            job.setState(DDLState.ROLLED_BACK);
            log.info("DDL job rolled back: id={}, rolledBackFrom={}", job.getId(), failedAt);
        } catch (Exception rollbackErr) {
            log.error("DDL job rollback failed: id={}, state={}, error={}",
                    job.getId(), failedAt, rollbackErr.getMessage(), rollbackErr);
        }
    }

    /**
     * Shutdown the worker gracefully.
     */
    public void shutdown() {
        running = false;
        log.info("DDL worker shutdown requested");
    }

    /**
     * Check if the worker is still running.
     */
    public boolean isRunning() {
        return running;
    }
}
