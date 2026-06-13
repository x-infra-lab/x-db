package io.github.xinfra.lab.xdb.ddl;

import java.util.List;

public interface DDLJobQueue {

    /**
     * Enqueue a new DDL job. Assigns a job ID if not already set.
     */
    void enqueue(DDLJob job);

    /**
     * Dequeue the first queued job, or return null if queue is empty.
     */
    DDLJob dequeue();

    /**
     * Get a job by its ID (from either the queue or history).
     */
    DDLJob getJob(long jobId);

    /**
     * Update an existing job in the queue.
     */
    void updateJob(DDLJob job);

    /**
     * List all queued/running jobs.
     */
    List<DDLJob> listJobs();

    /**
     * Move a completed job from the queue to history.
     */
    void moveToHistory(DDLJob job);

    /**
     * List completed jobs from history (most recent first).
     */
    List<DDLJob> listHistory(int limit);

    /**
     * Allocate a new unique job ID.
     */
    long allocJobId();
}
