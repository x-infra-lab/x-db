package io.github.xinfra.lab.xdb.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryTrackerTest {

    @Test
    void consumeAndRelease() {
        MemoryTracker tracker = new MemoryTracker("test", null, MemoryTracker.UNLIMITED);
        tracker.consume(100);
        assertThat(tracker.bytesConsumed()).isEqualTo(100);

        tracker.consume(200);
        assertThat(tracker.bytesConsumed()).isEqualTo(300);

        tracker.release(150);
        assertThat(tracker.bytesConsumed()).isEqualTo(150);
    }

    @Test
    void parentChildPropagation() {
        MemoryTracker parent = new MemoryTracker("parent", null, MemoryTracker.UNLIMITED);
        MemoryTracker child = parent.newChild("child");

        child.consume(100);
        assertThat(child.bytesConsumed()).isEqualTo(100);
        assertThat(parent.bytesConsumed()).isEqualTo(100);

        child.consume(200);
        assertThat(child.bytesConsumed()).isEqualTo(300);
        assertThat(parent.bytesConsumed()).isEqualTo(300);

        child.release(50);
        assertThat(child.bytesConsumed()).isEqualTo(250);
        assertThat(parent.bytesConsumed()).isEqualTo(250);
    }

    @Test
    void cancelActionThrowsOnExceed() {
        MemoryTracker tracker = new MemoryTracker("sort", null, 1000);
        tracker.setActionOnExceed(new MemoryTracker.CancelAction());

        tracker.consume(500);
        assertThat(tracker.bytesConsumed()).isEqualTo(500);

        assertThatThrownBy(() -> tracker.consume(600))
                .isInstanceOf(XDBException.class)
                .hasMessageContaining("sort")
                .hasMessageContaining("exceeded memory limit");
    }

    @Test
    void throwsWithoutActionConfigured() {
        MemoryTracker tracker = new MemoryTracker("hash_agg", null, 100);

        assertThatThrownBy(() -> tracker.consume(200))
                .isInstanceOf(XDBException.class)
                .hasMessageContaining("hash_agg");
    }

    @Test
    void spillDiskActionInvokesCallback() {
        AtomicBoolean spilled = new AtomicBoolean(false);
        MemoryTracker tracker = new MemoryTracker("sort", null, 1000);
        tracker.setActionOnExceed(new MemoryTracker.SpillDiskAction(() -> {
            spilled.set(true);
            tracker.release(500);
        }));

        tracker.consume(800);
        assertThat(spilled.get()).isFalse();

        tracker.consume(300);
        assertThat(spilled.get()).isTrue();
        assertThat(tracker.bytesConsumed()).isEqualTo(600);
    }

    @Test
    void logOnlyActionDoesNotThrow() {
        MemoryTracker tracker = new MemoryTracker("test", null, 100);
        tracker.setActionOnExceed(new MemoryTracker.LogOnlyAction());

        tracker.consume(200);
        assertThat(tracker.bytesConsumed()).isEqualTo(200);
    }

    @Test
    void noopTrackerDoesNotTrack() {
        MemoryTracker noop = MemoryTracker.noopTracker();
        noop.consume(Long.MAX_VALUE);
        assertThat(noop.bytesConsumed()).isEqualTo(0);
    }

    @Test
    void parentLimitTriggersBeforeChild() {
        MemoryTracker parent = new MemoryTracker("statement", null, 500);
        parent.setActionOnExceed(new MemoryTracker.CancelAction());
        MemoryTracker child = parent.newChild("sort");

        assertThatThrownBy(() -> child.consume(600))
                .isInstanceOf(XDBException.class)
                .hasMessageContaining("statement");
    }

    @Test
    void multipleSiblingChildren() {
        MemoryTracker parent = new MemoryTracker("statement", null, 1000);
        parent.setActionOnExceed(new MemoryTracker.CancelAction());
        MemoryTracker sortTracker = parent.newChild("sort");
        MemoryTracker joinTracker = parent.newChild("hash_join");

        sortTracker.consume(400);
        joinTracker.consume(400);
        assertThat(parent.bytesConsumed()).isEqualTo(800);

        assertThatThrownBy(() -> joinTracker.consume(300))
                .isInstanceOf(XDBException.class)
                .hasMessageContaining("statement");
    }

    @Test
    void resetClearsConsumed() {
        MemoryTracker tracker = new MemoryTracker("test", null, MemoryTracker.UNLIMITED);
        tracker.consume(500);
        assertThat(tracker.bytesConsumed()).isEqualTo(500);

        tracker.reset();
        assertThat(tracker.bytesConsumed()).isEqualTo(0);
    }

    @Test
    void zeroOrNegativeConsumeIsIgnored() {
        MemoryTracker tracker = new MemoryTracker("test", null, 100);
        tracker.consume(0);
        tracker.consume(-10);
        assertThat(tracker.bytesConsumed()).isEqualTo(0);
    }

    @Test
    void errorCodeIsCorrect() {
        MemoryTracker tracker = new MemoryTracker("sort", null, 100);
        try {
            tracker.consume(200);
        } catch (XDBException e) {
            assertThat(e.errorCode()).isEqualTo(XDBException.ER_MEM_EXCEEDED);
            assertThat(e.sqlState()).isEqualTo("HY000");
        }
    }
}
