package com.miniredis.concurrency;

import com.miniredis.storage.ByteSequence;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Fine-grained striped lock manager for MiniRedis.
 * Distributes keys across an array of ReentrantReadWriteLocks to allow concurrent execution
 * on disjoint key spaces while providing canonical ascending lock ordering for multi-key operations
 * to prevent deadlocks.
 *
 * Tracks contention count using lightweight LongAdder.
 */
public class StripedLockManager {

    public static final int DEFAULT_STRIPES = 256;

    private final ReentrantReadWriteLock[] locks;
    private final int mask;
    private final LongAdder contentionCount = new LongAdder();

    @FunctionalInterface
    public interface LockHandle extends AutoCloseable {
        @Override
        void close();
    }

    public StripedLockManager() {
        this(DEFAULT_STRIPES);
    }

    public StripedLockManager(int stripeCount) {
        if (stripeCount <= 0 || (stripeCount & (stripeCount - 1)) != 0) {
            throw new IllegalArgumentException("stripeCount must be a positive power of two: " + stripeCount);
        }
        this.locks = new ReentrantReadWriteLock[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            this.locks[i] = new ReentrantReadWriteLock();
        }
        this.mask = stripeCount - 1;
    }

    /**
     * Maps a key to a stripe index using bit spreading.
     */
    public int getStripe(String key) {
        if (key == null) return 0;
        return getStripe(ByteSequence.ofUtf8(key));
    }

    /**
     * Maps a ByteSequence key to a stripe index using bit spreading.
     */
    public int getStripe(ByteSequence key) {
        if (key == null) return 0;
        int h = key.hashCode();
        h ^= (h >>> 16);
        return (h & 0x7FFFFFFF) & mask;
    }

    private void lockWriteWithContention(ReentrantReadWriteLock.WriteLock lock) {
        if (!lock.tryLock()) {
            contentionCount.increment();
            lock.lock();
        }
    }

    private void lockReadWithContention(ReentrantReadWriteLock.ReadLock lock) {
        if (!lock.tryLock()) {
            contentionCount.increment();
            lock.lock();
        }
    }

    /**
     * Acquires write lock for a single key.
     */
    public LockHandle writeLock(String key) {
        int stripe = getStripe(key);
        ReentrantReadWriteLock.WriteLock lock = locks[stripe].writeLock();
        lockWriteWithContention(lock);
        return lock::unlock;
    }

    /**
     * Acquires write lock for a single ByteSequence key.
     */
    public LockHandle writeLock(ByteSequence key) {
        int stripe = getStripe(key);
        ReentrantReadWriteLock.WriteLock lock = locks[stripe].writeLock();
        lockWriteWithContention(lock);
        return lock::unlock;
    }

    /**
     * Acquires read lock for a single key.
     */
    public LockHandle readLock(String key) {
        int stripe = getStripe(key);
        ReentrantReadWriteLock.ReadLock lock = locks[stripe].readLock();
        lockReadWithContention(lock);
        return lock::unlock;
    }

    /**
     * Acquires read lock for a single ByteSequence key.
     */
    public LockHandle readLock(ByteSequence key) {
        int stripe = getStripe(key);
        ReentrantReadWriteLock.ReadLock lock = locks[stripe].readLock();
        lockReadWithContention(lock);
        return lock::unlock;
    }

    /**
     * Acquires write locks for a collection of ByteSequence keys in strictly ascending stripe order.
     */
    public LockHandle writeLocksBytes(Collection<ByteSequence> keys) {
        if (keys == null || keys.isEmpty()) {
            return () -> {};
        }

        SortedSet<Integer> stripes = new TreeSet<>();
        for (ByteSequence key : keys) {
            if (key != null) {
                stripes.add(getStripe(key));
            }
        }

        List<Integer> sortedStripes = new ArrayList<>(stripes);
        for (int stripe : sortedStripes) {
            lockWriteWithContention(locks[stripe].writeLock());
        }

        return () -> {
            for (int i = sortedStripes.size() - 1; i >= 0; i--) {
                locks[sortedStripes.get(i)].writeLock().unlock();
            }
        };
    }

    /**
     * Acquires write locks for a collection of keys in strictly ascending stripe order.
     * Prevents deadlocks regardless of the order keys are passed in by concurrent clients.
     */
    public LockHandle writeLocks(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return () -> {};
        }

        SortedSet<Integer> stripes = new TreeSet<>();
        for (String key : keys) {
            if (key != null) {
                stripes.add(getStripe(key));
            }
        }

        List<Integer> sortedStripes = new ArrayList<>(stripes);
        for (int stripe : sortedStripes) {
            lockWriteWithContention(locks[stripe].writeLock());
        }

        return () -> {
            for (int i = sortedStripes.size() - 1; i >= 0; i--) {
                locks[sortedStripes.get(i)].writeLock().unlock();
            }
        };
    }

    /**
     * Acquires read locks for a collection of ByteSequence keys in strictly ascending stripe order.
     */
    public LockHandle readLocksBytes(Collection<ByteSequence> keys) {
        if (keys == null || keys.isEmpty()) {
            return () -> {};
        }

        SortedSet<Integer> stripes = new TreeSet<>();
        for (ByteSequence key : keys) {
            if (key != null) {
                stripes.add(getStripe(key));
            }
        }

        List<Integer> sortedStripes = new ArrayList<>(stripes);
        for (int stripe : sortedStripes) {
            lockReadWithContention(locks[stripe].readLock());
        }

        return () -> {
            for (int i = sortedStripes.size() - 1; i >= 0; i--) {
                locks[sortedStripes.get(i)].readLock().unlock();
            }
        };
    }

    /**
     * Acquires read locks for a collection of keys in strictly ascending stripe order.
     */
    public LockHandle readLocks(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return () -> {};
        }

        SortedSet<Integer> stripes = new TreeSet<>();
        for (String key : keys) {
            if (key != null) {
                stripes.add(getStripe(key));
            }
        }

        List<Integer> sortedStripes = new ArrayList<>(stripes);
        for (int stripe : sortedStripes) {
            lockReadWithContention(locks[stripe].readLock());
        }

        return () -> {
            for (int i = sortedStripes.size() - 1; i >= 0; i--) {
                locks[sortedStripes.get(i)].readLock().unlock();
            }
        };
    }

    /**
     * Acquires write locks across all stripes for global operations (e.g. FLUSHDB).
     */
    public LockHandle allWriteLocks() {
        for (ReentrantReadWriteLock lock : locks) {
            lockWriteWithContention(lock.writeLock());
        }
        return () -> {
            for (int i = locks.length - 1; i >= 0; i--) {
                locks[i].writeLock().unlock();
            }
        };
    }

    /**
     * Acquires read locks across all stripes for global reads (e.g. KEYS *).
     */
    public LockHandle allReadLocks() {
        for (ReentrantReadWriteLock lock : locks) {
            lockReadWithContention(lock.readLock());
        }
        return () -> {
            for (int i = locks.length - 1; i >= 0; i--) {
                locks[i].readLock().unlock();
            }
        };
    }

    public int getStripeCount() {
        return locks.length;
    }

    public long getContentionCount() {
        return contentionCount.sum();
    }

    public void resetContentionCount() {
        contentionCount.reset();
    }
}
