package io.github.xinfra.lab.xdb.ddl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryDDLJobQueue implements DDLJobQueue {

    private final ConcurrentHashMap<Long, DDLJob> queue = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, DDLJob> history = new ConcurrentHashMap<>();
    private final AtomicLong jobIdGen = new AtomicLong(0);

    @Override
    public void enqueue(DDLJob job) {
        if (job.getId() == 0) {
            job.setId(allocJobId());
        }
        if (job.getState() == null || job.getState() == DDLState.NONE) {
            job.setState(DDLState.QUEUED);
        }
        job.setStartTs(System.currentTimeMillis());
        queue.put(job.getId(), job);
    }

    @Override
    public DDLJob dequeue() {
        return queue.values().stream()
                .filter(j -> j.getState() == DDLState.QUEUED)
                .min(Comparator.comparingLong(DDLJob::getId))
                .orElse(null);
    }

    @Override
    public DDLJob getJob(long jobId) {
        DDLJob job = queue.get(jobId);
        return job != null ? job : history.get(jobId);
    }

    @Override
    public void updateJob(DDLJob job) {
        queue.put(job.getId(), job);
    }

    @Override
    public List<DDLJob> listJobs() {
        return new ArrayList<>(queue.values());
    }

    @Override
    public void moveToHistory(DDLJob job) {
        queue.remove(job.getId());
        history.put(job.getId(), job);
    }

    @Override
    public List<DDLJob> listHistory(int limit) {
        return history.values().stream()
                .sorted(Comparator.comparingLong(DDLJob::getFinishTs).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public long allocJobId() {
        return jobIdGen.incrementAndGet();
    }
}
