package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionManagerTest {

    private AtomicInteger beginCount;
    private AtomicInteger commitCount;
    private AtomicInteger rollbackCount;
    private TransactionManager txnManager;

    @BeforeEach
    void setUp() {
        beginCount = new AtomicInteger();
        commitCount = new AtomicInteger();
        rollbackCount = new AtomicInteger();

        txnManager = new TransactionManager(
                pessimistic -> { beginCount.incrementAndGet(); return new Object(); },
                txn -> commitCount.incrementAndGet(),
                txn -> rollbackCount.incrementAndGet(),
                (txn, evalCtx) -> new TransactionContext(
                        (s, e, l) -> java.util.List.of(),
                        k -> null, (k, v) -> {}, k -> {}, evalCtx)
        );
    }

    @Test
    void beginCommitLifecycle() {
        assertThat(txnManager.isActive()).isFalse();

        txnManager.begin(true);
        assertThat(txnManager.isActive()).isTrue();
        assertThat(txnManager.currentContext()).isNotNull();
        assertThat(beginCount.get()).isEqualTo(1);

        txnManager.commit();
        assertThat(txnManager.isActive()).isFalse();
        assertThat(commitCount.get()).isEqualTo(1);
    }

    @Test
    void beginRollbackLifecycle() {
        txnManager.begin(false);
        assertThat(txnManager.isActive()).isTrue();

        txnManager.rollback();
        assertThat(txnManager.isActive()).isFalse();
        assertThat(rollbackCount.get()).isEqualTo(1);
    }

    @Test
    void commitWithoutActiveTxnThrows() {
        assertThatThrownBy(() -> txnManager.commit())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rollbackWithoutActiveTxnThrows() {
        assertThatThrownBy(() -> txnManager.rollback())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rollbackQuietlyWithoutActiveTxnIsNoOp() {
        txnManager.rollbackQuietly();
        assertThat(rollbackCount.get()).isEqualTo(0);
    }

    @Test
    void beginIfNeededStartsTxn() {
        TransactionContext ctx = txnManager.beginIfNeeded(true);
        assertThat(ctx).isNotNull();
        assertThat(txnManager.isActive()).isTrue();
        assertThat(beginCount.get()).isEqualTo(1);
    }

    @Test
    void beginIfNeededReusesExistingTxn() {
        txnManager.begin(true);
        TransactionContext first = txnManager.currentContext();
        TransactionContext second = txnManager.beginIfNeeded(true);
        assertThat(second).isSameAs(first);
        assertThat(beginCount.get()).isEqualTo(1);
    }

    @Test
    void beginWhileActiveImplicitlyCommits() {
        txnManager.begin(true);
        txnManager.begin(false);
        assertThat(commitCount.get()).isEqualTo(1);
        assertThat(beginCount.get()).isEqualTo(2);
    }

    @Test
    void txnTimeoutTriggersRollback() throws Exception {
        txnManager.setTxnTimeout(1);
        txnManager.begin(true);
        Thread.sleep(10);

        assertThatThrownBy(() -> txnManager.currentContext())
                .isInstanceOf(XDBException.class)
                .hasMessageContaining("timed out");

        assertThat(txnManager.isActive()).isFalse();
        assertThat(rollbackCount.get()).isEqualTo(1);
    }

    @Test
    void txnTimeoutZeroDisablesCheck() throws Exception {
        txnManager.setTxnTimeout(0);
        txnManager.begin(true);
        Thread.sleep(5);

        assertThat(txnManager.currentContext()).isNotNull();
        assertThat(txnManager.isActive()).isTrue();
        txnManager.rollback();
    }

    @Test
    void txnTimeoutInBeginIfNeeded() throws Exception {
        txnManager.setTxnTimeout(1);
        txnManager.begin(true);
        Thread.sleep(10);

        assertThatThrownBy(() -> txnManager.beginIfNeeded(true))
                .isInstanceOf(XDBException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void concurrentRollbackQuietlyOnlyRollsBackOnce() throws Exception {
        txnManager.begin(true);

        int threads = 10;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException ignored) {}
                txnManager.rollbackQuietly();
                done.countDown();
            }).start();
        }

        ready.await();
        go.countDown();
        done.await();

        assertThat(rollbackCount.get()).isEqualTo(1);
    }

    @Test
    void setAndGetTxnTimeout() {
        assertThat(txnManager.getTxnTimeout()).isEqualTo(60_000);
        txnManager.setTxnTimeout(30_000);
        assertThat(txnManager.getTxnTimeout()).isEqualTo(30_000);
    }
}
