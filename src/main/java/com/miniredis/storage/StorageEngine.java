package com.miniredis.storage;

import com.miniredis.concurrency.StripedLockManager;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Thread-safe In-Memory Storage Engine for MiniRedis.
 * Backed by ConcurrentHashMap<String, RedisValue> and StripedLockManager for fine-grained,
 * deadlock-free concurrency across 256 lock stripes.
 *
 * Timing Model:
 * Relies on a standard wall-clock model via java.time.Clock (defaulting to system UTC).
 * Limitations: System clock skews, NTP step adjustments, or leap seconds can alter remaining
 * TTL calculations. Injectable Clock allows completely deterministic time simulation in tests.
 */
public class StorageEngine {
    private final ConcurrentHashMap<String, RedisValue> store;
    private final ConcurrentHashMap<String, AtomicLong> keyVersions = new ConcurrentHashMap<>();
    private final AtomicLong globalFlushEpoch = new AtomicLong(0);
    private final StripedLockManager stripedLocks;
    private final java.util.concurrent.atomic.LongAdder totalCompletedOperations = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder failedOperations = new java.util.concurrent.atomic.LongAdder();
    private volatile Clock clock;
    private final ServerMetrics serverMetrics;
    private volatile com.miniredis.aof.AofManager aofManager;

    public StorageEngine() {
        this(Clock.systemUTC(), new StripedLockManager());
    }

    public StorageEngine(Clock clock) {
        this(clock, new StripedLockManager());
    }

    public StorageEngine(Clock clock, StripedLockManager stripedLocks) {
        this.store = new ConcurrentHashMap<>();
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.stripedLocks = Objects.requireNonNull(stripedLocks, "stripedLocks cannot be null");
        this.serverMetrics = new ServerMetrics(this::now);
    }

    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    public Clock getClock() {
        return clock;
    }

    public ServerMetrics getServerMetrics() {
        return serverMetrics;
    }

    public void setAofManager(com.miniredis.aof.AofManager aofManager) {
        this.aofManager = aofManager;
    }

    public com.miniredis.aof.AofManager getAofManager() {
        return aofManager;
    }

    public StripedLockManager getStripedLockManager() {
        return stripedLocks;
    }

    public long getTotalCompletedOperations() {
        return totalCompletedOperations.sum();
    }

    public long getFailedOperations() {
        return failedOperations.sum();
    }

    public void incrementCompletedOperations() {
        totalCompletedOperations.increment();
    }

    public void incrementFailedOperations() {
        failedOperations.increment();
    }

    public long now() {
        return clock.millis();
    }

    public void bumpKeyVersion(String key) {
        if (key != null) {
            keyVersions.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public long getKeyVersion(String key) {
        if (key == null) return 0L;
        AtomicLong counter = keyVersions.get(key);
        return counter != null ? counter.get() : 0L;
    }

    public static class WatchSnapshot {
        public final String key;
        public final long version;
        public final long flushEpoch;
        public final boolean activeAtWatch;

        public WatchSnapshot(String key, long version, long flushEpoch, boolean activeAtWatch) {
            this.key = key;
            this.version = version;
            this.flushEpoch = flushEpoch;
            this.activeAtWatch = activeAtWatch;
        }
    }

    public WatchSnapshot captureWatch(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            boolean active = getInternal(key) != null;
            return new WatchSnapshot(key, getKeyVersion(key), globalFlushEpoch.get(), active);
        }
    }

    /**
     * Checks watch validity. Caller should hold appropriate lock covering the watched key.
     */
    public boolean isWatchValid(WatchSnapshot snapshot) {
        if (snapshot == null) return false;
        if (snapshot.flushEpoch != globalFlushEpoch.get()) {
            return false;
        }
        boolean currentlyActive = getInternal(snapshot.key) != null;
        if (snapshot.activeAtWatch && !currentlyActive) {
            return false;
        }
        return getKeyVersion(snapshot.key) == snapshot.version;
    }

    /**
     * Safely adds duration to current timestamp with 64-bit overflow protection.
     */
    public static long calculateExpireAt(long now, long durationMillis) {
        if (durationMillis <= 0) return now;
        try {
            return Math.addExact(now, durationMillis);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Internal lookup with lazy expiration eviction.
     */
    private RedisValue getInternal(String key) {
        if (key == null) return null;
        RedisValue value = store.get(key);
        if (value == null) return null;

        if (value.isExpired(now())) {
            if (store.remove(key, value)) { // Atomic CAS removal
                bumpKeyVersion(key);
            }
            return null;
        }
        return value;
    }

    private RedisValue getAndCheckType(String key, RedisDataType expectedType) {
        RedisValue val = getInternal(key);
        if (val != null && val.getType() != expectedType) {
            throw new WrongTypeException();
        }
        return val;
    }

    // ==========================================
    // Phase 1 Operations (Respecting Expiration)
    // ==========================================

    public void set(String key, String value) {
        set(key, value, null);
    }

    public void set(String key, String value, Long expireAt) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue val = RedisValue.ofString(value);
            if (expireAt != null) {
                if (expireAt <= now()) {
                    if (store.remove(key) != null) {
                        bumpKeyVersion(key);
                    }
                    return;
                }
                val.setExpireAt(expireAt);
            }
            store.put(key, val);
            bumpKeyVersion(key);
        }
    }

    public void put(String key, RedisValue value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.put(key, value);
            bumpKeyVersion(key);
        }
    }

    public RedisValue get(String key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getInternal(key);
            if (val != null) {
                serverMetrics.recordHit();
            } else {
                serverMetrics.recordMiss();
            }
            return val;
        }
    }

    public int del(List<String> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLocks(keys)) {
            int count = 0;
            for (String key : keys) {
                RedisValue existing = getInternal(key);
                if (existing != null && store.remove(key) != null) {
                    bumpKeyVersion(key);
                    count++;
                }
            }
            return count;
        }
    }

    public int exists(List<String> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLocks(keys)) {
            int count = 0;
            for (String key : keys) {
                if (getInternal(key) != null) {
                    count++;
                }
            }
            return count;
        }
    }

    public Set<String> keys(String globPattern) {
        if (globPattern == null || globPattern.isEmpty()) {
            return Collections.emptySet();
        }

        try (StripedLockManager.LockHandle ignored = stripedLocks.allReadLocks()) {
            Pattern regex = compileGlobToRegex(globPattern);
            Set<String> matched = new TreeSet<>();
            long currentNow = now();

            for (Map.Entry<String, RedisValue> entry : store.entrySet()) {
                String key = entry.getKey();
                RedisValue val = entry.getValue();
                if (val.isExpired(currentNow)) {
                    if (store.remove(key, val)) {
                        bumpKeyVersion(key);
                    }
                    continue;
                }
                if (regex.matcher(key).matches()) {
                    matched.add(key);
                }
            }
            return matched;
        }
    }

    public int dbSize() {
        cleanExpiredKeys();
        return store.size();
    }

    public int dbsize() {
        return dbSize();
    }

    public void flushDb() {
        try (StripedLockManager.LockHandle ignored = stripedLocks.allWriteLocks()) {
            for (String key : store.keySet()) {
                bumpKeyVersion(key);
            }
            globalFlushEpoch.incrementAndGet();
            store.clear();
        }
    }

    public int cleanExpiredKeys() {
        int removed = 0;
        long currentNow = now();
        for (Map.Entry<String, RedisValue> entry : store.entrySet()) {
            if (entry.getValue().isExpired(currentNow)) {
                if (store.remove(entry.getKey(), entry.getValue())) {
                    bumpKeyVersion(entry.getKey());
                    removed++;
                }
            }
        }
        return removed;
    }

    public Map<String, RedisValue> getRawStore() {
        return Collections.unmodifiableMap(store);
    }

    // ==========================================
    // Phase 2: Advanced Strings
    // ==========================================

    public void mset(Map<String, String> keyValues) {
        Objects.requireNonNull(keyValues, "keyValues cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLocks(keyValues.keySet())) {
            for (Map.Entry<String, String> entry : keyValues.entrySet()) {
                // Fresh value clears any previous TTL
                store.put(entry.getKey(), RedisValue.ofString(entry.getValue()));
                bumpKeyVersion(entry.getKey());
            }
        }
    }

    public List<RedisValue> mget(List<String> keys) {
        Objects.requireNonNull(keys, "keys cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLocks(keys)) {
            List<RedisValue> results = new ArrayList<>(keys.size());
            for (String key : keys) {
                RedisValue val = getInternal(key);
                if (val != null) {
                    serverMetrics.recordHit();
                    results.add(val.deepCopy());
                } else {
                    serverMetrics.recordMiss();
                    results.add(null);
                }
            }
            return results;
        }
    }

    public long incr(String key) {
        return adjustInteger(key, 1L, false);
    }

    public long decr(String key) {
        return adjustInteger(key, 1L, true);
    }

    public long incrBy(String key, long delta) {
        return adjustInteger(key, delta, false);
    }

    public long decrBy(String key, long delta) {
        return adjustInteger(key, delta, true);
    }

    private long adjustInteger(String key, long delta, boolean subtract) {
        Objects.requireNonNull(key, "key cannot be null");
        long currentNow = now();

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue computed = store.compute(key, (k, existing) -> {
                if (existing != null && existing.isExpired(currentNow)) {
                    existing = null;
                }
                if (existing == null) {
                    long initVal;
                    try {
                        initVal = subtract ? Math.subtractExact(0L, delta) : delta;
                    } catch (ArithmeticException e) {
                        throw new ArithmeticException("ERR increment or decrement would overflow");
                    }
                    return RedisValue.ofString(String.valueOf(initVal));
                }
                if (existing.getType() != RedisDataType.STRING) {
                    throw new WrongTypeException();
                }
                String raw = existing.getAsString();
                long current;
                try {
                    current = Long.parseLong(raw);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("ERR value is not an integer or out of range");
                }

                long updated;
                try {
                    updated = subtract ? Math.subtractExact(current, delta) : Math.addExact(current, delta);
                } catch (ArithmeticException e) {
                    throw new ArithmeticException("ERR increment or decrement would overflow");
                }

                existing.setData(String.valueOf(updated));
                return existing;
            });

            bumpKeyVersion(key);
            return Long.parseLong(computed.getAsString());
        }
    }

    public int append(String key, String value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        long currentNow = now();

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue computed = store.compute(key, (k, existing) -> {
                if (existing != null && existing.isExpired(currentNow)) {
                    existing = null;
                }
                if (existing == null) {
                    return RedisValue.ofString(value);
                }
                if (existing.getType() != RedisDataType.STRING) {
                    throw new WrongTypeException();
                }
                String newStr = existing.getAsString() + value;
                existing.setData(newStr);
                return existing;
            });

            bumpKeyVersion(key);
            return computed.getAsString().getBytes(StandardCharsets.UTF_8).length;
        }
    }

    public int strlen(String key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.STRING);
            if (val == null) return 0;
            return val.getAsString().getBytes(StandardCharsets.UTF_8).length;
        }
    }

    // ==========================================
    // Phase 2: Lists
    // ==========================================

    public int lpush(String key, List<String> elements) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(elements, "elements cannot be null");
        long currentNow = now();
        AtomicInteger newSize = new AtomicInteger(0);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing != null && existing.isExpired(currentNow)) existing = null;
                if (existing == null) existing = RedisValue.ofList();
                if (existing.getType() != RedisDataType.LIST) throw new WrongTypeException();
                Deque<String> list = existing.getAsList();
                for (String elem : elements) {
                    list.addFirst(elem);
                }
                newSize.set(list.size());
                return existing;
            });
            if (!elements.isEmpty()) {
                bumpKeyVersion(key);
            }
            return newSize.get();
        }
    }

    public int rpush(String key, List<String> elements) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(elements, "elements cannot be null");
        long currentNow = now();
        AtomicInteger newSize = new AtomicInteger(0);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing != null && existing.isExpired(currentNow)) existing = null;
                if (existing == null) existing = RedisValue.ofList();
                if (existing.getType() != RedisDataType.LIST) throw new WrongTypeException();
                Deque<String> list = existing.getAsList();
                for (String elem : elements) {
                    list.addLast(elem);
                }
                newSize.set(list.size());
                return existing;
            });
            if (!elements.isEmpty()) {
                bumpKeyVersion(key);
            }
            return newSize.get();
        }
    }

    public String lpop(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        long currentNow = now();
        AtomicReference<String> popped = new AtomicReference<>(null);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing == null) return null;
                if (existing.isExpired(currentNow)) return null;
                if (existing.getType() != RedisDataType.LIST) throw new WrongTypeException();
                Deque<String> list = existing.getAsList();
                popped.set(list.pollFirst());
                if (list.isEmpty()) {
                    return null; // Atomically deletes empty list from map
                }
                return existing;
            });
            if (popped.get() != null) {
                bumpKeyVersion(key);
            }
            return popped.get();
        }
    }

    public String rpop(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        long currentNow = now();
        AtomicReference<String> popped = new AtomicReference<>(null);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing == null) return null;
                if (existing.isExpired(currentNow)) return null;
                if (existing.getType() != RedisDataType.LIST) throw new WrongTypeException();
                Deque<String> list = existing.getAsList();
                popped.set(list.pollLast());
                if (list.isEmpty()) {
                    return null; // Atomically deletes empty list from map
                }
                return existing;
            });
            if (popped.get() != null) {
                bumpKeyVersion(key);
            }
            return popped.get();
        }
    }

    public int llen(String key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.LIST);
            if (val == null) return 0;
            return val.getAsList().size();
        }
    }

    public List<String> lrange(String key, int start, int stop) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.LIST);
            if (val == null) return Collections.emptyList();

            List<String> list = new ArrayList<>(val.getAsList());
            int size = list.size();
            if (size == 0) return Collections.emptyList();

            if (start < 0) start = size + start;
            if (start < 0) start = 0;
            if (stop < 0) stop = size + stop;
            if (stop >= size) stop = size - 1;

            if (start > stop || start >= size) {
                return Collections.emptyList();
            }

            return Collections.unmodifiableList(new ArrayList<>(list.subList(start, stop + 1)));
        }
    }

    // ==========================================
    // Phase 2: Sets
    // ==========================================

    public int sadd(String key, List<String> members) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(members, "members cannot be null");
        long currentNow = now();
        AtomicInteger addedCount = new AtomicInteger(0);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing != null && existing.isExpired(currentNow)) existing = null;
                if (existing == null) existing = RedisValue.ofSet();
                if (existing.getType() != RedisDataType.SET) throw new WrongTypeException();
                Set<String> set = existing.getAsSet();
                int added = 0;
                for (String member : members) {
                    if (set.add(member)) {
                        added++;
                    }
                }
                addedCount.set(added);
                return existing;
            });
            if (addedCount.get() > 0) {
                bumpKeyVersion(key);
            }
            return addedCount.get();
        }
    }

    public int srem(String key, List<String> members) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(members, "members cannot be null");
        long currentNow = now();
        AtomicInteger removedCount = new AtomicInteger(0);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing == null) return null;
                if (existing.isExpired(currentNow)) return null;
                if (existing.getType() != RedisDataType.SET) throw new WrongTypeException();
                Set<String> set = existing.getAsSet();
                int removed = 0;
                for (String member : members) {
                    if (set.remove(member)) {
                        removed++;
                    }
                }
                removedCount.set(removed);
                if (set.isEmpty()) {
                    return null; // Atomically deletes empty set from map
                }
                return existing;
            });
            if (removedCount.get() > 0) {
                bumpKeyVersion(key);
            }
            return removedCount.get();
        }
    }

    public boolean sismember(String key, String member) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.SET);
            if (val == null) {
                serverMetrics.recordMiss();
                return false;
            }
            boolean contains = val.getAsSet().contains(member);
            if (contains) {
                serverMetrics.recordHit();
            } else {
                serverMetrics.recordMiss();
            }
            return contains;
        }
    }

    public Set<String> smembers(String key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.SET);
            if (val == null) return Collections.emptySet();
            return Collections.unmodifiableSet(new LinkedHashSet<>(val.getAsSet()));
        }
    }

    public int scard(String key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.SET);
            if (val == null) return 0;
            return val.getAsSet().size();
        }
    }

    // ==========================================
    // Phase 2: Hashes
    // ==========================================

    public int hset(String key, Map<String, String> fieldValues) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(fieldValues, "fieldValues cannot be null");
        long currentNow = now();
        AtomicInteger newFields = new AtomicInteger(0);
        AtomicBoolean anyChanged = new AtomicBoolean(false);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing != null && existing.isExpired(currentNow)) existing = null;
                if (existing == null) existing = RedisValue.ofHash();
                if (existing.getType() != RedisDataType.HASH) throw new WrongTypeException();
                Map<String, String> map = existing.getAsHash();
                int count = 0;
                for (Map.Entry<String, String> entry : fieldValues.entrySet()) {
                    String prev = map.put(entry.getKey(), entry.getValue());
                    if (prev == null) {
                        count++;
                    }
                    if (!Objects.equals(prev, entry.getValue())) {
                        anyChanged.set(true);
                    }
                }
                newFields.set(count);
                return existing;
            });
            if (anyChanged.get()) {
                bumpKeyVersion(key);
            }
            return newFields.get();
        }
    }

    public String hget(String key, String field) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.HASH);
            if (val == null) {
                serverMetrics.recordMiss();
                return null;
            }
            String res = val.getAsHash().get(field);
            if (res != null) {
                serverMetrics.recordHit();
            } else {
                serverMetrics.recordMiss();
            }
            return res;
        }
    }

    public Map<String, String> hgetall(String key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.HASH);
            if (val == null) return Collections.emptyMap();
            return Collections.unmodifiableMap(new LinkedHashMap<>(val.getAsHash()));
        }
    }

    public int hdel(String key, List<String> fields) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(fields, "fields cannot be null");
        long currentNow = now();
        AtomicInteger removedCount = new AtomicInteger(0);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing == null) return null;
                if (existing.isExpired(currentNow)) return null;
                if (existing.getType() != RedisDataType.HASH) throw new WrongTypeException();
                Map<String, String> map = existing.getAsHash();
                int removed = 0;
                for (String field : fields) {
                    if (map.remove(field) != null) {
                        removed++;
                    }
                }
                removedCount.set(removed);
                if (map.isEmpty()) {
                    return null; // Atomically deletes empty hash from map
                }
                return existing;
            });
            if (removedCount.get() > 0) {
                bumpKeyVersion(key);
            }
            return removedCount.get();
        }
    }

    public boolean hexists(String key, String field) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.HASH);
            if (val == null) return false;
            return val.getAsHash().containsKey(field);
        }
    }

    // ==========================================
    // Phase 3: Centralized Expiration & TTL
    // ==========================================

    /**
     * Sets expiration in seconds on key.
     * If seconds <= 0, immediately deletes the key.
     * Returns 1 if timeout was set/deleted, 0 if key does not exist.
     */
    public int expire(String key, long seconds) {
        if (seconds <= 0) {
            return del(Collections.singletonList(key));
        }
        return setExpirationMillis(key, seconds * 1000L);
    }

    /**
     * Sets expiration in milliseconds on key.
     * If milliseconds <= 0, immediately deletes the key.
     * Returns 1 if timeout was set/deleted, 0 if key does not exist.
     */
    public int pexpire(String key, long milliseconds) {
        if (milliseconds <= 0) {
            return del(Collections.singletonList(key));
        }
        return setExpirationMillis(key, milliseconds);
    }

    /**
     * Sets expiration at an absolute unix timestamp in milliseconds.
     * If epochMillis <= clock.millis(), immediately deletes the key.
     * Returns 1 if timeout was set or key was deleted, 0 if key does not exist.
     */
    public int pexpireAt(String key, long epochMillis) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue val = getInternal(key);
            if (val == null) {
                return 0;
            }
            if (epochMillis <= now()) {
                if (store.remove(key, val)) {
                    bumpKeyVersion(key);
                }
                return 1;
            }
            val.setExpireAt(epochMillis);
            bumpKeyVersion(key);
            return 1;
        }
    }

    private int setExpirationMillis(String key, long durationMillis) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue val = getInternal(key);
            if (val == null) {
                return 0;
            }
            long expireAt = calculateExpireAt(now(), durationMillis);
            val.setExpireAt(expireAt);
            bumpKeyVersion(key);
            return 1;
        }
    }

    /**
     * Returns remaining TTL in seconds.
     * Positive integer if TTL exists.
     * -1 if key exists without TTL (persistent).
     * -2 if key does not exist (or expired).
     */
    public long ttl(String key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getInternal(key);
            if (val == null) return -2L;

            Long expireAt = val.getExpireAt();
            if (expireAt == null) return -1L;

            long remainingMillis = expireAt - now();
            if (remainingMillis <= 0) {
                if (store.remove(key, val)) {
                    bumpKeyVersion(key);
                }
                return -2L;
            }

            // Redis rounds up remaining seconds: (millis + 999) / 1000
            return (remainingMillis + 999) / 1000;
        }
    }

    /**
     * Returns remaining TTL in milliseconds.
     * Positive integer if TTL exists.
     * -1 if key exists without TTL.
     * -2 if key does not exist (or expired).
     */
    public long pttl(String key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getInternal(key);
            if (val == null) return -2L;

            Long expireAt = val.getExpireAt();
            if (expireAt == null) return -1L;

            long remainingMillis = expireAt - now();
            if (remainingMillis <= 0) {
                if (store.remove(key, val)) {
                    bumpKeyVersion(key);
                }
                return -2L;
            }

            return remainingMillis;
        }
    }

    /**
     * Removes the existing timeout on key.
     * Returns 1 if timeout removed, 0 if key has no timeout or does not exist.
     */
    public int persist(String key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue val = getInternal(key);
            if (val == null) return 0;
            if (val.getExpireAt() == null) return 0;
            val.setExpireAt(null);
            bumpKeyVersion(key);
            return 1;
        }
    }

    /**
     * Atomically sets the string value and assigns the expiration timeout in seconds.
     * Rejects seconds <= 0.
     */
    public void setex(String key, long seconds, String value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        if (seconds <= 0) {
            throw new IllegalArgumentException("ERR invalid expire time in 'setex' command");
        }

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            long expireAt = calculateExpireAt(now(), seconds * 1000L);
            RedisValue val = RedisValue.ofString(value);
            val.setExpireAt(expireAt);
            store.put(key, val);
            bumpKeyVersion(key);
        }
    }

    public boolean isExpired(String key) {
        RedisValue val = store.get(key);
        if (val == null) return true;
        return val.isExpired(now());
    }

    // ==========================================
    // Phase 8: Key Utilities & Keyspace Stats
    // ==========================================

    /**
     * Returns the type name ("string", "list", "set", "hash") or "none" if missing/expired.
     */
    public String typeOf(String key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getInternal(key);
            return val != null ? val.getType().getTypeName() : "none";
        }
    }

    /**
     * Atomically renames oldKey to newKey under canonical striped write locks,
     * preserving data and absolute TTL expiration metadata.
     */
    public void rename(String oldKey, String newKey) {
        Objects.requireNonNull(oldKey, "oldKey cannot be null");
        Objects.requireNonNull(newKey, "newKey cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLocks(List.of(oldKey, newKey))) {
            RedisValue oldVal = getInternal(oldKey);
            if (oldVal == null) {
                throw new NoSuchElementException("ERR no such key");
            }
            if (oldKey.equals(newKey)) {
                return;
            }
            store.put(newKey, oldVal);
            store.remove(oldKey);
            bumpKeyVersion(oldKey);
            bumpKeyVersion(newKey);
        }
    }

    /**
     * Returns a uniformly sampled active (non-expired) key from the database, or null if empty.
     */
    public String randomKey() {
        try (StripedLockManager.LockHandle ignored = stripedLocks.allReadLocks()) {
            long currentNow = now();
            String selected = null;
            int seen = 0;
            for (Map.Entry<String, RedisValue> entry : store.entrySet()) {
                String k = entry.getKey();
                RedisValue val = entry.getValue();
                if (val == null || val.isExpired(currentNow)) {
                    if (val != null && store.remove(k, val)) {
                        bumpKeyVersion(k);
                    }
                    continue;
                }
                seen++;
                if (ThreadLocalRandom.current().nextInt(seen) == 0) {
                    selected = k;
                }
            }
            return selected;
        }
    }

    public static class KeyspaceStats {
        public final int activeKeys;
        public final int expiringKeys;
        public final long avgTtlMs;
        public final long estimatedMemoryBytes;

        public KeyspaceStats(int activeKeys, int expiringKeys, long avgTtlMs, long estimatedMemoryBytes) {
            this.activeKeys = activeKeys;
            this.expiringKeys = expiringKeys;
            this.avgTtlMs = avgTtlMs;
            this.estimatedMemoryBytes = estimatedMemoryBytes;
        }
    }

    public KeyspaceStats computeKeyspaceStats() {
        try (StripedLockManager.LockHandle ignored = stripedLocks.allReadLocks()) {
            long currentNow = now();
            int activeKeys = 0;
            int expiringKeys = 0;
            long totalTtlMs = 0L;
            long estimatedBytes = 0L;

            for (Map.Entry<String, RedisValue> entry : store.entrySet()) {
                String key = entry.getKey();
                RedisValue val = entry.getValue();
                if (val == null || val.isExpired(currentNow)) {
                    continue;
                }
                activeKeys++;
                Long expireAt = val.getExpireAt();
                if (expireAt != null) {
                    expiringKeys++;
                    totalTtlMs += Math.max(0L, expireAt - currentNow);
                }

                // Estimate entry overhead: Map.Entry + RedisValue wrapper + Key UTF-8 bytes
                estimatedBytes += 64L + key.getBytes(StandardCharsets.UTF_8).length;
                switch (val.getType()) {
                    case STRING:
                        estimatedBytes += 40L + val.getAsString().getBytes(StandardCharsets.UTF_8).length;
                        break;
                    case LIST:
                        estimatedBytes += 48L;
                        for (String item : val.getAsList()) {
                            estimatedBytes += 32L + item.getBytes(StandardCharsets.UTF_8).length;
                        }
                        break;
                    case SET:
                        estimatedBytes += 48L;
                        for (String member : val.getAsSet()) {
                            estimatedBytes += 32L + member.getBytes(StandardCharsets.UTF_8).length;
                        }
                        break;
                    case HASH:
                        estimatedBytes += 48L;
                        for (Map.Entry<String, String> field : val.getAsHash().entrySet()) {
                            estimatedBytes += 48L
                                    + field.getKey().getBytes(StandardCharsets.UTF_8).length
                                    + field.getValue().getBytes(StandardCharsets.UTF_8).length;
                        }
                        break;
                }
            }

            long avgTtl = expiringKeys > 0 ? (totalTtlMs / expiringKeys) : 0L;
            return new KeyspaceStats(activeKeys, expiringKeys, avgTtl, estimatedBytes);
        }
    }

    public static class SampleResult {
        public final int sampledCount;
        public final int purgedCount;

        public SampleResult(int sampledCount, int purgedCount) {
            this.sampledCount = sampledCount;
            this.purgedCount = purgedCount;
        }
    }

    /**
     * Active sampling algorithm used by ExpirationManager:
     * Samples up to sampleSize keys that have active TTL metadata,
     * tests them against current epoch time, and purges expired entries.
     */
    public SampleResult sampleAndEvict(int sampleSize) {
        if (store.isEmpty()) {
            return new SampleResult(0, 0);
        }

        List<Map.Entry<String, RedisValue>> expiringEntries = new ArrayList<>();
        long currentNow = now();

        for (Map.Entry<String, RedisValue> entry : store.entrySet()) {
            if (entry.getValue().getExpireAt() != null) {
                expiringEntries.add(entry);
            }
        }

        if (expiringEntries.isEmpty()) {
            return new SampleResult(0, 0);
        }

        // Shuffle / sample up to sampleSize
        Collections.shuffle(expiringEntries, ThreadLocalRandom.current());
        int countToSample = Math.min(sampleSize, expiringEntries.size());
        int purged = 0;

        for (int i = 0; i < countToSample; i++) {
            Map.Entry<String, RedisValue> entry = expiringEntries.get(i);
            String key = entry.getKey();
            RedisValue candidate = entry.getValue();

            try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
                RedisValue current = store.get(key);
                // Ensure key still points to the candidate instance and is still expired
                if (current == candidate && current.isExpired(currentNow)) {
                    if (store.remove(key, candidate)) {
                        bumpKeyVersion(key);
                        purged++;
                    }
                }
            }
        }

        return new SampleResult(countToSample, purged);
    }

    /**
     * Snapshot entry representing an active, non-expired key and value for AOF compaction.
     */
    public static class SnapshotEntry {
        public final String key;
        public final RedisValue value;
        public final Long expireAt;

        public SnapshotEntry(String key, RedisValue value, Long expireAt) {
            this.key = key;
            this.value = value;
            this.expireAt = expireAt;
        }
    }

    /**
     * Returns a snapshot of all active, non-expired entries in the store for AOF compaction.
     * Takes striped read locks across all stripes, and executes the onLocked hook while locked.
     */
    public List<SnapshotEntry> createSnapshot() {
        return createSnapshot(null);
    }

    public List<SnapshotEntry> createSnapshot(Runnable onLocked) {
        long currentNow = now();
        List<SnapshotEntry> list = new ArrayList<>();
        try (StripedLockManager.LockHandle ignored = stripedLocks.allReadLocks()) {
            if (onLocked != null) {
                onLocked.run();
            }
            for (Map.Entry<String, RedisValue> entry : store.entrySet()) {
                String key = entry.getKey();
                RedisValue val = entry.getValue();
                if (val != null && !val.isExpired(currentNow)) {
                    list.add(new SnapshotEntry(key, val.deepCopy(), val.getExpireAt()));
                }
            }
        }
        return list;
    }

    // ==========================================
    // Glob Pattern Utility
    // ==========================================

    private Pattern compileGlobToRegex(String glob) {
        if (glob.equals("*")) {
            return Pattern.compile(".*");
        }
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    sb.append(".*");
                    break;
                case '?':
                    sb.append(".");
                    break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                    sb.append("\\").append(c);
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }
}