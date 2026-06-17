package io.github.xinfra.lab.xdb.ddl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DDLOwnerManagerTest {

    private ConcurrentHashMap<String, byte[]> kvStore;

    @BeforeEach
    void setUp() {
        kvStore = new ConcurrentHashMap<>();
    }

    private DDLOwnerManager createManager(String ownerId) {
        return new DDLOwnerManager(
                ownerId,
                key -> kvStore.get(new String(key)),
                (key, value) -> kvStore.put(new String(key), value),
                (key, expected, newValue) -> {
                    String k = new String(key);
                    synchronized (kvStore) {
                        byte[] current = kvStore.get(k);
                        if (expected == null && current == null) {
                            kvStore.put(k, newValue);
                            return true;
                        }
                        if (expected != null && current != null && Arrays.equals(expected, current)) {
                            kvStore.put(k, newValue);
                            return true;
                        }
                        return false;
                    }
                }
        );
    }

    @Test
    @DisplayName("constructor rejects null CASOperation")
    void nullCasThrows() {
        assertThatThrownBy(() -> new DDLOwnerManager("node1",
                key -> null, (key, value) -> {}, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("Ownership acquisition")
    class Acquisition {

        @Test
        @DisplayName("initially not owner")
        void initiallyNotOwner() {
            DDLOwnerManager mgr = createManager("node1");
            assertThat(mgr.isOwner()).isFalse();
        }

        @Test
        @DisplayName("tryBecomeOwner succeeds when no current owner")
        void becomeOwnerWhenNone() {
            DDLOwnerManager mgr = createManager("node1");
            assertThat(mgr.tryBecomeOwner()).isTrue();
            assertThat(mgr.isOwner()).isTrue();
        }

        @Test
        @DisplayName("tryBecomeOwner returns true when already owner (renews)")
        void becomeOwnerWhenAlreadyOwner() {
            DDLOwnerManager mgr = createManager("node1");
            mgr.tryBecomeOwner();
            assertThat(mgr.tryBecomeOwner()).isTrue();
            assertThat(mgr.isOwner()).isTrue();
        }

        @Test
        @DisplayName("second node cannot become owner while first lease is valid")
        void secondNodeBlocked() {
            DDLOwnerManager mgr1 = createManager("node1");
            DDLOwnerManager mgr2 = createManager("node2");

            assertThat(mgr1.tryBecomeOwner()).isTrue();
            assertThat(mgr2.tryBecomeOwner()).isFalse();
            assertThat(mgr2.isOwner()).isFalse();
        }

        @Test
        @DisplayName("second node can take over after lease expires")
        void takeOverAfterExpiry() {
            DDLOwnerManager mgr1 = createManager("node1");
            mgr1.tryBecomeOwner();

            // Simulate expired lease by directly writing an expired value
            String ownerKey = new String(io.github.xinfra.lab.xdb.table.MetaCodec.ddlOwnerKey());
            kvStore.put(ownerKey, ("node1:0").getBytes());

            DDLOwnerManager mgr2 = createManager("node2");
            assertThat(mgr2.tryBecomeOwner()).isTrue();
            assertThat(mgr2.isOwner()).isTrue();
        }
    }

    @Nested
    @DisplayName("Lease renewal")
    class LeaseRenewal {

        @Test
        @DisplayName("renewLease succeeds when owner")
        void renewWhenOwner() {
            DDLOwnerManager mgr = createManager("node1");
            mgr.tryBecomeOwner();
            assertThat(mgr.renewLease()).isTrue();
            assertThat(mgr.isOwner()).isTrue();
        }

        @Test
        @DisplayName("renewLease returns false when not owner")
        void renewWhenNotOwner() {
            DDLOwnerManager mgr = createManager("node1");
            assertThat(mgr.renewLease()).isFalse();
        }

        @Test
        @DisplayName("renewLease detects ownership stolen by another node")
        void renewDetectsStolenOwnership() {
            DDLOwnerManager mgr1 = createManager("node1");
            mgr1.tryBecomeOwner();

            // Another node steals ownership by directly writing KV
            String ownerKey = new String(io.github.xinfra.lab.xdb.table.MetaCodec.ddlOwnerKey());
            long futureTs = System.currentTimeMillis() + 100_000;
            kvStore.put(ownerKey, ("node2:" + futureTs).getBytes());

            assertThat(mgr1.renewLease()).isFalse();
            assertThat(mgr1.isOwner()).isFalse();
        }

        @Test
        @DisplayName("renewLease returns false when key deleted")
        void renewWhenKeyDeleted() {
            DDLOwnerManager mgr = createManager("node1");
            mgr.tryBecomeOwner();

            kvStore.clear();

            assertThat(mgr.renewLease()).isFalse();
            assertThat(mgr.isOwner()).isFalse();
        }
    }

    @Nested
    @DisplayName("Resign")
    class Resign {

        @Test
        @DisplayName("resign releases ownership")
        void resignReleasesOwnership() {
            DDLOwnerManager mgr = createManager("node1");
            mgr.tryBecomeOwner();
            assertThat(mgr.isOwner()).isTrue();

            mgr.resign();
            assertThat(mgr.isOwner()).isFalse();
            assertThat(mgr.isLeaseValid()).isFalse();
        }

        @Test
        @DisplayName("resign when not owner is safe")
        void resignWhenNotOwner() {
            DDLOwnerManager mgr = createManager("node1");
            mgr.resign();
            assertThat(mgr.isOwner()).isFalse();
        }

        @Test
        @DisplayName("after resign, another node can become owner")
        void anotherNodeAfterResign() {
            DDLOwnerManager mgr1 = createManager("node1");
            DDLOwnerManager mgr2 = createManager("node2");

            mgr1.tryBecomeOwner();
            mgr1.resign();

            assertThat(mgr2.tryBecomeOwner()).isTrue();
            assertThat(mgr2.isOwner()).isTrue();
        }
    }

    @Nested
    @DisplayName("Lease validity")
    class LeaseValidity {

        @Test
        @DisplayName("lease is valid immediately after becoming owner")
        void validAfterAcquisition() {
            DDLOwnerManager mgr = createManager("node1");
            mgr.tryBecomeOwner();
            assertThat(mgr.isLeaseValid()).isTrue();
        }

        @Test
        @DisplayName("lease is invalid before becoming owner")
        void invalidBeforeAcquisition() {
            DDLOwnerManager mgr = createManager("node1");
            assertThat(mgr.isLeaseValid()).isFalse();
        }
    }

    @Nested
    @DisplayName("Accessors")
    class Accessors {

        @Test
        @DisplayName("getOwnerId returns the configured ID")
        void ownerId() {
            DDLOwnerManager mgr = createManager("my-node");
            assertThat(mgr.getOwnerId()).isEqualTo("my-node");
        }

        @Test
        @DisplayName("static lease duration is accessible")
        void leaseDuration() {
            assertThat(DDLOwnerManager.getLeaseDurationMs()).isGreaterThan(0);
        }

        @Test
        @DisplayName("static renew interval is accessible")
        void renewInterval() {
            assertThat(DDLOwnerManager.getRenewIntervalMs()).isGreaterThan(0);
            assertThat(DDLOwnerManager.getRenewIntervalMs())
                    .isLessThan(DDLOwnerManager.getLeaseDurationMs());
        }
    }
}
