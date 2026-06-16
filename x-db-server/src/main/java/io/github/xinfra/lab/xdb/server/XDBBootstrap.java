package io.github.xinfra.lab.xdb.server;

import io.github.xinfra.lab.xdb.ddl.DDLExecutor;
import io.github.xinfra.lab.xdb.ddl.DDLOwnerManager;
import io.github.xinfra.lab.xdb.ddl.DDLWorker;
import io.github.xinfra.lab.xdb.ddl.KVDDLJobQueue;
import io.github.xinfra.lab.xdb.ddl.SchemaChangeExecutor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.meta.KVMetaStore;
import io.github.xinfra.lab.xdb.planner.cost.StatsStore;
import io.github.xinfra.lab.xdb.session.InfoSchemaHolder;
import io.github.xinfra.lab.xdb.session.SessionManagerImpl;
import io.github.xinfra.lab.xdb.session.TransactionManager;
import io.github.xinfra.lab.xkv.client.XKvClient;
import io.github.xinfra.lab.xkv.client.config.ClientConfig;
import io.github.xinfra.lab.xkv.client.raw.RawKvClient;
import io.github.xinfra.lab.xkv.client.TxnClient;
import io.github.xinfra.lab.xkv.client.cop.CopClient;
import io.github.xinfra.lab.xkv.client.txn.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

public class XDBBootstrap {

    private static final Logger log = LoggerFactory.getLogger(XDBBootstrap.class);

    public static void run(XDBConfig config) throws Exception {
        List<String> pdEndpoints = Arrays.asList(config.pdAddresses().split(","));
        String nodeId = UUID.randomUUID().toString();

        log.info("Starting x-db node={}, pd={}", nodeId, pdEndpoints);

        ClientConfig clientConfig = ClientConfig.builder()
                .pdEndpoints(pdEndpoints)
                .build();
        XKvClient xkvClient = XKvClient.create(clientConfig);
        RawKvClient rawKv = xkvClient.raw();
        TxnClient txnClient = TxnClient.create(clientConfig);

        KVMetaStore metaStore = new KVMetaStore(
                key -> rawKv.get(key).orElse(null),
                rawKv::put,
                rawKv::delete,
                (key, expected, newVal) ->
                        rawKv.cas(key, Optional.ofNullable(expected), newVal).succeeded()
        );

        InfoSchemaHolder schemaHolder = new InfoSchemaHolder(metaStore);
        StatsStore.getInstance().loadFromMetaStore(metaStore, schemaHolder.get());

        KVDDLJobQueue jobQueue = new KVDDLJobQueue(
                key -> rawKv.get(key).orElse(null),
                rawKv::put,
                rawKv::delete,
                (start, end, limit) -> rawKv.scan(start, end, limit).stream()
                        .map(kp -> new byte[][]{kp.key(), kp.value()})
                        .toList(),
                (key, expected, newVal) ->
                        rawKv.cas(key, Optional.ofNullable(expected), newVal).succeeded()
        );

        DDLOwnerManager ownerManager = new DDLOwnerManager(
                nodeId,
                key -> rawKv.get(key).orElse(null),
                rawKv::put,
                (key, expected, newVal) ->
                        rawKv.cas(key, Optional.ofNullable(expected), newVal).succeeded()
        );

        SchemaChangeExecutor schemaChangeExecutor = new SchemaChangeExecutor(metaStore);

        DDLWorker ddlWorker = new DDLWorker(
                ownerManager,
                jobQueue,
                schemaChangeExecutor,
                schemaHolder::refresh
        );

        DDLExecutor ddlExecutor = new DDLExecutor(jobQueue, 30_000);

        TransactionManager.TxnStarter txnStarter =
                pessimistic -> pessimistic ? txnClient.beginPessimistic() : txnClient.begin();

        TransactionManager.TxnCommitter txnCommitter =
                txn -> ((Transaction) txn).commit();

        TransactionManager.TxnRollbacker txnRollbacker =
                txn -> ((Transaction) txn).rollback();

        CopClient copClient = txnClient.copClient();

        TransactionManager.TxnContextFactory txnContextFactory =
                (txn, evalCtx) -> {
                    Transaction t = (Transaction) txn;
                    TransactionContext.KVCopProcessor copProcessor =
                            (tp, data, startTs, start, end, conc) -> {
                                var it = copClient.sendToRangeParallel(
                                        tp, data, t.startTs(), start, end, conc);
                                return new java.util.Iterator<TransactionContext.KVCopProcessor.CopRegionResult>() {
                                    @Override public boolean hasNext() { return it.hasNext(); }
                                    @Override public TransactionContext.KVCopProcessor.CopRegionResult next() {
                                        var r = it.next();
                                        return new TransactionContext.KVCopProcessor.CopRegionResult(
                                                r.regionId(),
                                                r.response().getData().toByteArray(),
                                                r.response().getOtherError());
                                    }
                                };
                            };
                    return new TransactionContext(
                            (s, e, l) -> StreamSupport.stream(t.scan(s, e, l).spliterator(), false)
                                    .map(kp -> new TransactionContext.KVPair(kp.key(), kp.value()))
                                    .toList(),
                            key -> t.get(key).orElse(null),
                            t::put,
                            t::delete,
                            copProcessor,
                            evalCtx
                    );
                };

        SessionManagerImpl sessionManager = new SessionManagerImpl(
                schemaHolder, ddlExecutor, metaStore,
                txnStarter, txnCommitter, txnRollbacker, txnContextFactory
        );

        Thread ddlThread = new Thread(ddlWorker, "ddl-worker");
        ddlThread.setDaemon(true);
        ddlThread.start();
        log.info("DDL worker started");

        XDBServer server = new XDBServer(config, sessionManager);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            ddlWorker.shutdown();
            server.shutdown();
            try {
                txnClient.close();
                xkvClient.close();
            } catch (Exception e) {
                log.warn("Error closing x-kv clients", e);
            }
            log.info("Shutdown complete");
        }, "shutdown-hook"));

        server.start();
        server.serverChannel().closeFuture().sync();
    }
}
