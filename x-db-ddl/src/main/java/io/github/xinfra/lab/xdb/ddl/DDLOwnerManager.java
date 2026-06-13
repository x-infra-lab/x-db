package io.github.xinfra.lab.xdb.ddl;

import io.github.xinfra.lab.xdb.table.MetaCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Lease-based DDL owner election. Only one node executes DDL at a time.
 *
 * <p>The owner information is stored in KV as:
 * key: m_DDLOwner -> value: {ownerId}:{leaseExpireTs}
 */
public class DDLOwnerManager {
    private static final Logger log = LoggerFactory.getLogger(DDLOwnerManager.class);

    private static final long LEASE_DURATION_MS = 10_000;
    private static final long RENEW_INTERVAL_MS = 2_000;

    private static final String SEPARATOR = ":";

    @FunctionalInterface
    public interface CASOperation {
        /**
         * Compare-and-swap: returns true if oldValue matched and was replaced with newValue.
         * If expectedOldValue is null, succeeds only if the key does not exist.
         */
        boolean cas(byte[] key, byte[] expectedOldValue, byte[] newValue);
    }

    @FunctionalInterface
    public interface KVGetter {
        byte[] get(byte[] key);
    }

    @FunctionalInterface
    public interface KVPutter {
        void put(byte[] key, byte[] value);
    }

    private final String ownerId;
    private final KVGetter getter;
    private final KVPutter putter;
    private final CASOperation casOp;
    private volatile boolean isOwner;
    private volatile long leaseExpireTime;

    public DDLOwnerManager(String ownerId, KVGetter getter, KVPutter putter, CASOperation casOp) {
        this.ownerId = ownerId;
        this.getter = getter;
        this.putter = putter;
        this.casOp = casOp;
        this.isOwner = false;
        this.leaseExpireTime = 0;
    }

    public DDLOwnerManager(String ownerId, KVGetter getter, KVPutter putter) {
        this(ownerId, getter, putter, null);
    }

    /**
     * Returns true if this node is currently the DDL owner.
     */
    public boolean isOwner() {
        if (!isOwner) {
            return false;
        }
        return isLeaseValid();
    }

    /**
     * Try to become the DDL owner. Succeeds if there is no current owner
     * or the current owner's lease has expired.
     */
    public boolean tryBecomeOwner() {
        byte[] key = MetaCodec.ddlOwnerKey();
        byte[] currentValue = getter.get(key);

        if (currentValue != null) {
            String current = new String(currentValue, StandardCharsets.UTF_8);
            String[] parts = current.split(SEPARATOR, 2);
            if (parts.length == 2) {
                String currentOwnerId = parts[0];
                long expireTs = Long.parseLong(parts[1]);

                if (currentOwnerId.equals(this.ownerId)) {
                    // We are already the owner, renew the lease
                    return renewLease();
                }

                if (System.currentTimeMillis() < expireTs) {
                    // Another node's lease is still valid
                    return false;
                }
            }
        }

        // No owner or lease expired, try to claim ownership
        long newExpireTs = System.currentTimeMillis() + LEASE_DURATION_MS;
        String newValue = ownerId + SEPARATOR + newExpireTs;
        byte[] newBytes = newValue.getBytes(StandardCharsets.UTF_8);

        if (casOp != null) {
            boolean success = casOp.cas(key, currentValue, newBytes);
            if (success) {
                this.isOwner = true;
                this.leaseExpireTime = newExpireTs;
                log.info("Became DDL owner: ownerId={}, leaseExpireTime={}", ownerId, newExpireTs);
                return true;
            }
            return false;
        } else {
            // Fallback: simple put (no CAS support)
            putter.put(key, newBytes);
            this.isOwner = true;
            this.leaseExpireTime = newExpireTs;
            log.info("Became DDL owner (no CAS): ownerId={}, leaseExpireTime={}", ownerId, newExpireTs);
            return true;
        }
    }

    /**
     * Renew the lease if we are the current owner.
     */
    public boolean renewLease() {
        if (!isOwner) {
            return false;
        }

        byte[] key = MetaCodec.ddlOwnerKey();
        byte[] currentValue = getter.get(key);

        if (currentValue == null) {
            isOwner = false;
            return false;
        }

        String current = new String(currentValue, StandardCharsets.UTF_8);
        String[] parts = current.split(SEPARATOR, 2);
        if (parts.length != 2 || !parts[0].equals(ownerId)) {
            // Someone else is the owner now
            isOwner = false;
            return false;
        }

        long newExpireTs = System.currentTimeMillis() + LEASE_DURATION_MS;
        String newValue = ownerId + SEPARATOR + newExpireTs;
        byte[] newBytes = newValue.getBytes(StandardCharsets.UTF_8);

        if (casOp != null) {
            boolean success = casOp.cas(key, currentValue, newBytes);
            if (success) {
                this.leaseExpireTime = newExpireTs;
                log.debug("Renewed DDL owner lease: ownerId={}, leaseExpireTime={}", ownerId, newExpireTs);
                return true;
            }
            isOwner = false;
            return false;
        } else {
            putter.put(key, newBytes);
            this.leaseExpireTime = newExpireTs;
            log.debug("Renewed DDL owner lease (no CAS): ownerId={}, leaseExpireTime={}", ownerId, newExpireTs);
            return true;
        }
    }

    /**
     * Voluntarily release ownership.
     */
    public void resign() {
        if (!isOwner) {
            return;
        }

        byte[] key = MetaCodec.ddlOwnerKey();
        // Set expiry to 0 to immediately expire the lease
        String newValue = ownerId + SEPARATOR + "0";
        putter.put(key, newValue.getBytes(StandardCharsets.UTF_8));
        isOwner = false;
        leaseExpireTime = 0;

        log.info("Resigned DDL ownership: ownerId={}", ownerId);
    }

    /**
     * Check if the current lease is still valid.
     */
    public boolean isLeaseValid() {
        return System.currentTimeMillis() < leaseExpireTime;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public static long getLeaseDurationMs() {
        return LEASE_DURATION_MS;
    }

    public static long getRenewIntervalMs() {
        return RENEW_INTERVAL_MS;
    }
}
