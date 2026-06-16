package io.github.xinfra.lab.xdb.executor;

import io.github.xinfra.lab.xdb.expression.EvalContext;

import java.util.Iterator;
import java.util.List;

/**
 * Wraps KV transaction operations via functional interfaces, keeping
 * the executor layer decoupled from the concrete KV client.
 */
public class TransactionContext {

    @FunctionalInterface
    public interface KVScanner {
        List<KVPair> scan(byte[] startKey, byte[] endKey, int limit) throws Exception;
    }

    @FunctionalInterface
    public interface KVGetter {
        byte[] get(byte[] key) throws Exception;
    }

    @FunctionalInterface
    public interface KVPutter {
        void put(byte[] key, byte[] value) throws Exception;
    }

    @FunctionalInterface
    public interface KVDeleter {
        void delete(byte[] key) throws Exception;
    }

    @FunctionalInterface
    public interface KVCopProcessor {
        Iterator<CopRegionResult> scanParallel(
                int requestType, byte[] data, long startTs,
                byte[] startKey, byte[] endKey, int concurrency) throws Exception;

        record CopRegionResult(long regionId, byte[] data, String error) {}
    }

    public record KVPair(byte[] key, byte[] value) {}

    private final KVScanner scanner;
    private final KVGetter getter;
    private final KVPutter putter;
    private final KVDeleter deleter;
    private final KVCopProcessor copProcessor;
    private final EvalContext evalContext;

    public TransactionContext(KVScanner scanner, KVGetter getter,
                              KVPutter putter, KVDeleter deleter,
                              EvalContext evalContext) {
        this(scanner, getter, putter, deleter, null, evalContext);
    }

    public TransactionContext(KVScanner scanner, KVGetter getter,
                              KVPutter putter, KVDeleter deleter,
                              KVCopProcessor copProcessor,
                              EvalContext evalContext) {
        this.scanner = scanner;
        this.getter = getter;
        this.putter = putter;
        this.deleter = deleter;
        this.copProcessor = copProcessor;
        this.evalContext = evalContext;
    }

    public KVScanner scanner() {
        return scanner;
    }

    public KVGetter getter() {
        return getter;
    }

    public KVPutter putter() {
        return putter;
    }

    public KVDeleter deleter() {
        return deleter;
    }

    public KVCopProcessor copProcessor() {
        return copProcessor;
    }

    public EvalContext evalContext() {
        return evalContext;
    }
}
