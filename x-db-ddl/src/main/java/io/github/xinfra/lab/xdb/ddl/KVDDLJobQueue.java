package io.github.xinfra.lab.xdb.ddl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.table.MetaCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class KVDDLJobQueue implements DDLJobQueue {
    private static final Logger log = LoggerFactory.getLogger(KVDDLJobQueue.class);

    private static final String DDL_JOB_ID_ALLOC_KEY = "m_DDLJobIdAlloc";

    @FunctionalInterface
    public interface KVGetter {
        byte[] get(byte[] key);
    }

    @FunctionalInterface
    public interface KVPutter {
        void put(byte[] key, byte[] value);
    }

    @FunctionalInterface
    public interface KVDeleter {
        void delete(byte[] key);
    }

    @FunctionalInterface
    public interface KVScanner {
        /**
         * Scan KV pairs in range [start, end), returning up to limit entries.
         * Each entry is a byte[][] where [0] is the key and [1] is the value.
         */
        List<byte[][]> scan(byte[] start, byte[] end, int limit);
    }

    @FunctionalInterface
    public interface KVCas {
        boolean cas(byte[] key, byte[] expected, byte[] newValue);
    }

    private final KVGetter getter;
    private final KVPutter putter;
    private final KVDeleter deleter;
    private final KVScanner scanner;
    private final KVCas cas;
    private final ObjectMapper objectMapper;

    public KVDDLJobQueue(KVGetter getter, KVPutter putter, KVDeleter deleter,
                         KVScanner scanner, KVCas cas) {
        this.getter = getter;
        this.putter = putter;
        this.deleter = deleter;
        this.scanner = scanner;
        this.cas = cas;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void enqueue(DDLJob job) {
        if (job.getId() == 0) {
            job.setId(allocJobId());
        }
        if (job.getState() == null || job.getState() == DDLState.NONE) {
            job.setState(DDLState.QUEUED);
        }
        if (job.getStartTs() == 0) {
            job.setStartTs(System.currentTimeMillis());
        }

        byte[] key = MetaCodec.ddlJobKey(job.getId());
        byte[] value = serializeJob(job);
        putter.put(key, value);

        log.info("Enqueued DDL job: id={}, type={}, dbId={}, tableId={}",
                job.getId(), job.getType(), job.getDbId(), job.getTableId());
    }

    @Override
    public DDLJob dequeue() {
        List<DDLJob> jobs = listJobs();
        for (DDLJob job : jobs) {
            if (job.getState() == DDLState.QUEUED) {
                return job;
            }
        }
        return null;
    }

    @Override
    public DDLJob getJob(long jobId) {
        // Try the job queue first
        byte[] key = MetaCodec.ddlJobKey(jobId);
        byte[] value = getter.get(key);
        if (value != null) {
            return deserializeJob(value);
        }

        // Try history
        byte[] historyKey = MetaCodec.ddlHistoryKey(jobId);
        byte[] historyValue = getter.get(historyKey);
        if (historyValue != null) {
            return deserializeJob(historyValue);
        }

        return null;
    }

    @Override
    public void updateJob(DDLJob job) {
        byte[] key = MetaCodec.ddlJobKey(job.getId());
        byte[] value = serializeJob(job);
        putter.put(key, value);

        log.debug("Updated DDL job: id={}, state={}, schemaState={}",
                job.getId(), job.getState(), job.getSchemaState());
    }

    @Override
    public List<DDLJob> listJobs() {
        byte[] startKey = "m_DDLJob:".getBytes(StandardCharsets.UTF_8);
        // Scan up to m_DDLJob; (';' is the next char after ':' in ASCII)
        byte[] endKey = "m_DDLJob;".getBytes(StandardCharsets.UTF_8);
        List<byte[][]> entries = scanner.scan(startKey, endKey, Integer.MAX_VALUE);

        List<DDLJob> jobs = new ArrayList<>();
        for (byte[][] entry : entries) {
            DDLJob job = deserializeJob(entry[1]);
            jobs.add(job);
        }
        return jobs;
    }

    @Override
    public void moveToHistory(DDLJob job) {
        // Write to history
        byte[] historyKey = MetaCodec.ddlHistoryKey(job.getId());
        byte[] value = serializeJob(job);
        putter.put(historyKey, value);

        // Remove from job queue
        byte[] jobKey = MetaCodec.ddlJobKey(job.getId());
        deleter.delete(jobKey);

        log.info("Moved DDL job to history: id={}, state={}", job.getId(), job.getState());
    }

    @Override
    public List<DDLJob> listHistory(int limit) {
        byte[] startKey = "m_DDLHistory:".getBytes(StandardCharsets.UTF_8);
        byte[] endKey = "m_DDLHistory;".getBytes(StandardCharsets.UTF_8);
        List<byte[][]> entries = scanner.scan(startKey, endKey, limit);

        List<DDLJob> jobs = new ArrayList<>();
        for (byte[][] entry : entries) {
            DDLJob job = deserializeJob(entry[1]);
            jobs.add(job);
        }
        return jobs;
    }

    @Override
    public long allocJobId() {
        byte[] key = DDL_JOB_ID_ALLOC_KEY.getBytes(StandardCharsets.UTF_8);
        while (true) {
            byte[] value = getter.get(key);
            long current = (value != null && value.length > 0)
                    ? ByteBuffer.wrap(value).getLong() : 0;
            long next = current + 1;
            byte[] newValue = ByteBuffer.allocate(Long.BYTES).putLong(next).array();
            if (cas.cas(key, value, newValue)) {
                return next;
            }
        }
    }

    private byte[] serializeJob(DDLJob job) {
        try {
            return objectMapper.writeValueAsBytes(job);
        } catch (JsonProcessingException e) {
            throw XDBException.internal("Failed to serialize DDL job: " + job.getId(), e);
        }
    }

    private DDLJob deserializeJob(byte[] data) {
        try {
            return objectMapper.readValue(data, DDLJob.class);
        } catch (Exception e) {
            throw XDBException.internal("Failed to deserialize DDL job", e);
        }
    }
}
