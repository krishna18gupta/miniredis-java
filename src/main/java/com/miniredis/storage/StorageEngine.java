package com.miniredis.storage;

import com.miniredis.commands.RedisResponse;
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
 * Backed by ConcurrentHashMap<ByteSequence, RedisValue> and StripedLockManager for fine-grained,
 * deadlock-free concurrency across 256 lock stripes.
 *
 * Binary Safety:
 * Keys and String/List/Set/Hash values are stored and manipulated natively as immutable ByteSequence objects.
 * Arbitrary byte sequences including 0x00, CRLF, and non-UTF-8 bytes are completely preserved.
 * Backward-compatible String overloads transcode on demand at the boundary for legacy callers.
 *
 * Timing Model:
 * Relies on a standard wall-clock model via java.time.Clock (defaulting to system UTC).
 * Injectable Clock allows completely deterministic time simulation in tests.
 */
public class StorageEngine {
    private final ConcurrentHashMap<ByteSequence, RedisValue> store;
    private final ConcurrentHashMap<ByteSequence, AtomicLong> keyVersions = new ConcurrentHashMap<>();
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

    public void bumpKeyVersion(ByteSequence key) {
        if (key != null) {
            keyVersions.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public void bumpKeyVersion(String key) {
        if (key != null) {
            bumpKeyVersion(ByteSequence.ofUtf8(key));
        }
    }

    public long getKeyVersion(ByteSequence key) {
        if (key == null) return 0L;
        AtomicLong counter = keyVersions.get(key);
        return counter != null ? counter.get() : 0L;
    }

    public long getKeyVersion(String key) {
        if (key == null) return 0L;
        return getKeyVersion(ByteSequence.ofUtf8(key));
    }

    public static class WatchSnapshot {
        public final ByteSequence keyBytes;
        public final String key;
        public final long version;
        public final long flushEpoch;
        public final boolean activeAtWatch;

        public WatchSnapshot(ByteSequence key, long version, long flushEpoch, boolean activeAtWatch) {
            this.keyBytes = key;
            this.key = key != null ? key.toUtf8String() : null;
            this.version = version;
            this.flushEpoch = flushEpoch;
            this.activeAtWatch = activeAtWatch;
        }

        public WatchSnapshot(String key, long version, long flushEpoch, boolean activeAtWatch) {
            this(key != null ? ByteSequence.ofUtf8(key) : null, version, flushEpoch, activeAtWatch);
        }

        public String getKey() {
            return key;
        }

        public ByteSequence getKeyBytes() {
            return keyBytes;
        }
    }

    public WatchSnapshot captureWatch(ByteSequence key) {
        Objects.requireNonNull(key, "key cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            boolean active = getInternal(key) != null;
            return new WatchSnapshot(key, getKeyVersion(key), globalFlushEpoch.get(), active);
        }
    }

    public WatchSnapshot captureWatch(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        return captureWatch(ByteSequence.ofUtf8(key));
    }

    /**
     * Checks watch validity. Caller should hold appropriate lock covering the watched key.
     */
    public boolean isWatchValid(WatchSnapshot snapshot) {
        if (snapshot == null) return false;
        if (snapshot.flushEpoch != globalFlushEpoch.get()) {
            return false;
        }
        ByteSequence k = snapshot.keyBytes != null ? snapshot.keyBytes : ByteSequence.ofUtf8(snapshot.key);
        boolean currentlyActive = getInternal(k) != null;
        if (snapshot.activeAtWatch && !currentlyActive) {
            return false;
        }
        return getKeyVersion(k) == snapshot.version;
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
    private RedisValue getInternal(ByteSequence key) {
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

    private RedisValue getAndCheckType(ByteSequence key, RedisDataType expectedType) {
        RedisValue val = getInternal(key);
        if (val != null && val.getType() != expectedType) {
            throw new WrongTypeException();
        }
        return val;
    }

    // ==========================================
    // Phase 1 Operations (Respecting Expiration)
    // ==========================================

    public void set(ByteSequence key, ByteSequence value) {
        set(key, value, null);
    }

    public void set(ByteSequence key, ByteSequence value, Long expireAt) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue val = RedisValue.ofByteSequence(value);
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

    public void set(String key, String value) {
        set(key, value, null);
    }

    public void set(String key, String value, Long expireAt) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        set(ByteSequence.ofUtf8(key), ByteSequence.ofUtf8(value), expireAt);
    }

    public static class SetOptions {
        public final boolean nx;
        public final boolean xx;
        public final boolean get;
        public final boolean keepTtl;
        public final Long expireAt;
        public final boolean hasExpire;

        public SetOptions(boolean nx, boolean xx, boolean get, boolean keepTtl, Long expireAt, boolean hasExpire) {
            this.nx = nx;
            this.xx = xx;
            this.get = get;
            this.keepTtl = keepTtl;
            this.expireAt = expireAt;
            this.hasExpire = hasExpire;
        }
    }

    public RedisResponse setExtended(ByteSequence key, ByteSequence value, SetOptions options) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue existing = getInternal(key);

            ByteSequence oldValue = null;
            if (options.get) {
                if (existing != null) {
                    if (existing.getType() != RedisDataType.STRING) {
                        throw new WrongTypeException();
                    }
                    oldValue = existing.getAsByteSequence();
                }
            }

            if (options.nx && existing != null) {
                if (options.get) {
                    return RedisResponse.bulkString(oldValue).withMutated(false);
                }
                return RedisResponse.nil().withMutated(false);
            }

            if (options.xx && existing == null) {
                return RedisResponse.nil().withMutated(false);
            }

            Long newExpireAt = null;
            if (options.keepTtl) {
                if (existing != null) {
                    newExpireAt = existing.getExpireAt();
                }
            } else if (options.hasExpire) {
                newExpireAt = options.expireAt;
            }

            long now = now();
            if (newExpireAt != null && newExpireAt <= now) {
                boolean removed = (store.remove(key) != null);
                if (removed) {
                    bumpKeyVersion(key);
                }
                RedisResponse resp = options.get
                        ? (oldValue != null ? RedisResponse.bulkString(oldValue) : RedisResponse.nil())
                        : RedisResponse.ok();
                return resp.withMutated(removed);
            }

            RedisValue newVal = RedisValue.ofByteSequence(value);
            if (newExpireAt != null) {
                newVal.setExpireAt(newExpireAt);
            }
            store.put(key, newVal);
            bumpKeyVersion(key);

            if (options.get) {
                return (oldValue != null) ? RedisResponse.bulkString(oldValue) : RedisResponse.nil();
            }
            return RedisResponse.ok();
        }
    }

    public RedisResponse setExtended(String key, String value, SetOptions options) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        return setExtended(ByteSequence.ofUtf8(key), ByteSequence.ofUtf8(value), options);
    }

    public Long getExpireAt(ByteSequence key) {
        if (key == null) return null;
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getInternal(key);
            return (val != null) ? val.getExpireAt() : null;
        }
    }

    public Long getExpireAt(String key) {
        if (key == null) return null;
        return getExpireAt(ByteSequence.ofUtf8(key));
    }

    public void put(ByteSequence key, RedisValue value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.put(key, value);
            bumpKeyVersion(key);
        }
    }

    public void put(String key, RedisValue value) {
        Objects.requireNonNull(key, "key cannot be null");
        put(ByteSequence.ofUtf8(key), value);
    }

    public RedisValue get(ByteSequence key) {
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

    public RedisValue get(String key) {
        if (key == null) return null;
        return get(ByteSequence.ofUtf8(key));
    }

    public int delBytes(List<ByteSequence> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLocksBytes(keys)) {
            int count = 0;
            for (ByteSequence key : keys) {
                RedisValue existing = getInternal(key);
                if (existing != null && store.remove(key) != null) {
                    bumpKeyVersion(key);
                    count++;
                }
            }
            return count;
        }
    }

    public int del(List<String> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        List<ByteSequence> byteKeys = new ArrayList<>(keys.size());
        for (String k : keys) {
            byteKeys.add(ByteSequence.ofUtf8(k));
        }
        return delBytes(byteKeys);
    }

    public int existsBytes(List<ByteSequence> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLocksBytes(keys)) {
            int count = 0;
            for (ByteSequence key : keys) {
                if (getInternal(key) != null) {
                    count++;
                }
            }
            return count;
        }
    }

    public int exists(List<String> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        List<ByteSequence> byteKeys = new ArrayList<>(keys.size());
        for (String k : keys) {
            byteKeys.add(ByteSequence.ofUtf8(k));
        }
        return existsBytes(byteKeys);
    }

    public boolean exists(ByteSequence key) {
        if (key == null) return false;
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            return getInternal(key) != null;
        }
    }

    public boolean exists(String key) {
        if (key == null) return false;
        return exists(ByteSequence.ofUtf8(key));
    }

    public Set<String> keys(String globPattern) {
        if (globPattern == null || globPattern.isEmpty()) {
            return Collections.emptySet();
        }

        try (StripedLockManager.LockHandle ignored = stripedLocks.allReadLocks()) {
            Pattern regex = compileGlobToRegex(globPattern);
            Set<String> matched = new TreeSet<>();
            long currentNow = now();

            for (Map.Entry<ByteSequence, RedisValue> entry : store.entrySet()) {
                ByteSequence key = entry.getKey();
                RedisValue val = entry.getValue();
                if (val.isExpired(currentNow)) {
                    if (store.remove(key, val)) {
                        bumpKeyVersion(key);
                    }
                    continue;
                }
                String keyStr = key.toUtf8String();
                if (regex.matcher(keyStr).matches()) {
                    matched.add(keyStr);
                }
            }
            return matched;
        }
    }

    public Set<ByteSequence> keysBytes(String globPattern) {
        if (globPattern == null || globPattern.isEmpty()) {
            return Collections.emptySet();
        }

        try (StripedLockManager.LockHandle ignored = stripedLocks.allReadLocks()) {
            Pattern regex = compileGlobToRegex(globPattern);
            Set<ByteSequence> matched = new LinkedHashSet<>();
            long currentNow = now();

            for (Map.Entry<ByteSequence, RedisValue> entry : store.entrySet()) {
                ByteSequence key = entry.getKey();
                RedisValue val = entry.getValue();
                if (val.isExpired(currentNow)) {
                    if (store.remove(key, val)) {
                        bumpKeyVersion(key);
                    }
                    continue;
                }
                String keyStr = key.toUtf8String();
                if (regex.matcher(keyStr).matches()) {
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
            for (ByteSequence key : store.keySet()) {
                bumpKeyVersion(key);
            }
            globalFlushEpoch.incrementAndGet();
            store.clear();
        }
    }

    public int cleanExpiredKeys() {
        int removed = 0;
        long currentNow = now();
        for (Map.Entry<ByteSequence, RedisValue> entry : store.entrySet()) {
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
        Map<String, RedisValue> map = new HashMap<>();
        for (Map.Entry<ByteSequence, RedisValue> entry : store.entrySet()) {
            map.put(entry.getKey().toUtf8String(), entry.getValue());
        }
        return Collections.unmodifiableMap(map);
    }

    public Map<ByteSequence, RedisValue> getRawByteStore() {
        return Collections.unmodifiableMap(store);
    }

    // ==========================================
    // Phase 2: Advanced Strings
    // ==========================================

    public void msetBytes(Map<ByteSequence, ByteSequence> keyValues) {
        Objects.requireNonNull(keyValues, "keyValues cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLocksBytes(keyValues.keySet())) {
            for (Map.Entry<ByteSequence, ByteSequence> entry : keyValues.entrySet()) {
                // Fresh value clears any previous TTL
                store.put(entry.getKey(), RedisValue.ofByteSequence(entry.getValue()));
                bumpKeyVersion(entry.getKey());
            }
        }
    }

    public void mset(Map<String, String> keyValues) {
        Objects.requireNonNull(keyValues, "keyValues cannot be null");
        Map<ByteSequence, ByteSequence> byteMap = new LinkedHashMap<>(keyValues.size());
        for (Map.Entry<String, String> entry : keyValues.entrySet()) {
            byteMap.put(ByteSequence.ofUtf8(entry.getKey()), ByteSequence.ofUtf8(entry.getValue()));
        }
        msetBytes(byteMap);
    }

    public List<RedisValue> mgetBytes(List<ByteSequence> keys) {
        Objects.requireNonNull(keys, "keys cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLocksBytes(keys)) {
            List<RedisValue> results = new ArrayList<>(keys.size());
            for (ByteSequence key : keys) {
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

    public List<RedisValue> mget(List<String> keys) {
        Objects.requireNonNull(keys, "keys cannot be null");
        List<ByteSequence> byteKeys = new ArrayList<>(keys.size());
        for (String k : keys) {
            byteKeys.add(ByteSequence.ofUtf8(k));
        }
        return mgetBytes(byteKeys);
    }

    public long incr(ByteSequence key) {
        return adjustInteger(key, 1L, false);
    }

    public long incr(String key) {
        return incr(ByteSequence.ofUtf8(key));
    }

    public long decr(ByteSequence key) {
        return adjustInteger(key, 1L, true);
    }

    public long decr(String key) {
        return decr(ByteSequence.ofUtf8(key));
    }

    public long incrBy(ByteSequence key, long delta) {
        return adjustInteger(key, delta, false);
    }

    public long incrBy(String key, long delta) {
        return incrBy(ByteSequence.ofUtf8(key), delta);
    }

    public long decrBy(ByteSequence key, long delta) {
        return adjustInteger(key, delta, true);
    }

    public long decrBy(String key, long delta) {
        return decrBy(ByteSequence.ofUtf8(key), delta);
    }

    private long adjustInteger(ByteSequence key, long delta, boolean subtract) {
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
                    return RedisValue.ofByteSequence(ByteSequence.ofAscii(String.valueOf(initVal)));
                }
                if (existing.getType() != RedisDataType.STRING) {
                    throw new WrongTypeException();
                }
                String raw = existing.getAsByteSequence().toUtf8String();
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

                existing.setData(ByteSequence.ofAscii(String.valueOf(updated)));
                return existing;
            });

            bumpKeyVersion(key);
            return Long.parseLong(computed.getAsByteSequence().toUtf8String());
        }
    }

    public int append(ByteSequence key, ByteSequence value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        long currentNow = now();

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue computed = store.compute(key, (k, existing) -> {
                if (existing != null && existing.isExpired(currentNow)) {
                    existing = null;
                }
                if (existing == null) {
                    return RedisValue.ofByteSequence(value);
                }
                if (existing.getType() != RedisDataType.STRING) {
                    throw new WrongTypeException();
                }
                ByteSequence newBytes = existing.getAsByteSequence().concat(value);
                existing.setData(newBytes);
                return existing;
            });

            bumpKeyVersion(key);
            return computed.getAsByteSequence().length();
        }
    }

    public int append(String key, String value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        return append(ByteSequence.ofUtf8(key), ByteSequence.ofUtf8(value));
    }

    public int strlen(ByteSequence key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.STRING);
            if (val == null) return 0;
            return val.getAsByteSequence().length();
        }
    }

    public int strlen(String key) {
        return strlen(ByteSequence.ofUtf8(key));
    }

    // ==========================================
    // Phase 2: Lists
    // ==========================================

    public int lpushBytes(ByteSequence key, List<ByteSequence> elements) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(elements, "elements cannot be null");
        long currentNow = now();
        AtomicInteger newSize = new AtomicInteger(0);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing != null && existing.isExpired(currentNow)) existing = null;
                if (existing == null) existing = RedisValue.ofByteList();
                if (existing.getType() != RedisDataType.LIST) throw new WrongTypeException();
                Deque<ByteSequence> list = existing.getAsByteList();
                for (ByteSequence elem : elements) {
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

    public int lpush(String key, List<String> elements) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(elements, "elements cannot be null");
        List<ByteSequence> byteElems = new ArrayList<>(elements.size());
        for (String e : elements) {
            byteElems.add(ByteSequence.ofUtf8(e));
        }
        return lpushBytes(ByteSequence.ofUtf8(key), byteElems);
    }

    public int rpushBytes(ByteSequence key, List<ByteSequence> elements) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(elements, "elements cannot be null");
        long currentNow = now();
        AtomicInteger newSize = new AtomicInteger(0);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing != null && existing.isExpired(currentNow)) existing = null;
                if (existing == null) existing = RedisValue.ofByteList();
                if (existing.getType() != RedisDataType.LIST) throw new WrongTypeException();
                Deque<ByteSequence> list = existing.getAsByteList();
                for (ByteSequence elem : elements) {
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

    public int rpush(String key, List<String> elements) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(elements, "elements cannot be null");
        List<ByteSequence> byteElems = new ArrayList<>(elements.size());
        for (String e : elements) {
            byteElems.add(ByteSequence.ofUtf8(e));
        }
        return rpushBytes(ByteSequence.ofUtf8(key), byteElems);
    }

    public ByteSequence lpop(ByteSequence key) {
        Objects.requireNonNull(key, "key cannot be null");
        long currentNow = now();
        AtomicReference<ByteSequence> popped = new AtomicReference<>(null);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing == null) return null;
                if (existing.isExpired(currentNow)) return null;
                if (existing.getType() != RedisDataType.LIST) throw new WrongTypeException();
                Deque<ByteSequence> list = existing.getAsByteList();
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

    public String lpop(String key) {
        ByteSequence res = lpop(ByteSequence.ofUtf8(key));
        return res != null ? res.toUtf8String() : null;
    }

    public ByteSequence rpop(ByteSequence key) {
        Objects.requireNonNull(key, "key cannot be null");
        long currentNow = now();
        AtomicReference<ByteSequence> popped = new AtomicReference<>(null);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing == null) return null;
                if (existing.isExpired(currentNow)) return null;
                if (existing.getType() != RedisDataType.LIST) throw new WrongTypeException();
                Deque<ByteSequence> list = existing.getAsByteList();
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

    public String rpop(String key) {
        ByteSequence res = rpop(ByteSequence.ofUtf8(key));
        return res != null ? res.toUtf8String() : null;
    }

    public List<ByteSequence> lpopBytes(ByteSequence key, int count) {
        Objects.requireNonNull(key, "key cannot be null");
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue existing = getInternal(key);
            if (existing == null) {
                return null;
            }
            if (existing.getType() != RedisDataType.LIST) {
                throw new WrongTypeException();
            }
            if (count == 0) {
                return Collections.emptyList();
            }
            LinkedList<ByteSequence> list = existing.getAsByteList();
            int toPop = Math.min(count, list.size());
            List<ByteSequence> popped = new ArrayList<>(toPop);
            for (int i = 0; i < toPop; i++) {
                popped.add(list.pollFirst());
            }
            if (list.isEmpty()) {
                store.remove(key);
            }
            if (toPop > 0) {
                bumpKeyVersion(key);
            }
            return popped;
        }
    }

    public List<String> lpop(String key, int count) {
        List<ByteSequence> bytes = lpopBytes(ByteSequence.ofUtf8(key), count);
        if (bytes == null) return null;
        List<String> result = new ArrayList<>(bytes.size());
        for (ByteSequence b : bytes) {
            result.add(b != null ? b.toUtf8String() : null);
        }
        return result;
    }

    public List<ByteSequence> rpopBytes(ByteSequence key, int count) {
        Objects.requireNonNull(key, "key cannot be null");
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue existing = getInternal(key);
            if (existing == null) {
                return null;
            }
            if (existing.getType() != RedisDataType.LIST) {
                throw new WrongTypeException();
            }
            if (count == 0) {
                return Collections.emptyList();
            }
            LinkedList<ByteSequence> list = existing.getAsByteList();
            int toPop = Math.min(count, list.size());
            List<ByteSequence> popped = new ArrayList<>(toPop);
            for (int i = 0; i < toPop; i++) {
                popped.add(list.pollLast());
            }
            if (list.isEmpty()) {
                store.remove(key);
            }
            if (toPop > 0) {
                bumpKeyVersion(key);
            }
            return popped;
        }
    }

    public List<String> rpop(String key, int count) {
        List<ByteSequence> bytes = rpopBytes(ByteSequence.ofUtf8(key), count);
        if (bytes == null) return null;
        List<String> result = new ArrayList<>(bytes.size());
        for (ByteSequence b : bytes) {
            result.add(b != null ? b.toUtf8String() : null);
        }
        return result;
    }

    public ByteSequence lindexBytes(ByteSequence key, long index) {
        Objects.requireNonNull(key, "key cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.LIST);
            if (val == null) return null;
            LinkedList<ByteSequence> list = val.getAsByteList();
            int size = list.size();
            long targetIdx = (index >= 0) ? index : (size + index);
            if (targetIdx < 0 || targetIdx >= size) {
                return null;
            }
            return list.get((int) targetIdx);
        }
    }

    public String lindex(String key, long index) {
        ByteSequence res = lindexBytes(ByteSequence.ofUtf8(key), index);
        return res != null ? res.toUtf8String() : null;
    }

    public enum LSetStatus {
        OK,
        NO_SUCH_KEY,
        INDEX_OUT_OF_RANGE
    }

    public LSetStatus lsetBytes(ByteSequence key, long index, ByteSequence element) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(element, "element cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue val = getInternal(key);
            if (val == null) {
                return LSetStatus.NO_SUCH_KEY;
            }
            if (val.getType() != RedisDataType.LIST) {
                throw new WrongTypeException();
            }
            LinkedList<ByteSequence> list = val.getAsByteList();
            int size = list.size();
            long targetIdx = (index >= 0) ? index : (size + index);
            if (targetIdx < 0 || targetIdx >= size) {
                return LSetStatus.INDEX_OUT_OF_RANGE;
            }
            list.set((int) targetIdx, element);
            bumpKeyVersion(key);
            return LSetStatus.OK;
        }
    }

    public LSetStatus lset(String key, long index, String element) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(element, "element cannot be null");
        return lsetBytes(ByteSequence.ofUtf8(key), index, ByteSequence.ofUtf8(element));
    }

    public int linsertBytes(ByteSequence key, boolean before, ByteSequence pivot, ByteSequence element) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(pivot, "pivot cannot be null");
        Objects.requireNonNull(element, "element cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue val = getInternal(key);
            if (val == null) {
                return 0;
            }
            if (val.getType() != RedisDataType.LIST) {
                throw new WrongTypeException();
            }
            LinkedList<ByteSequence> list = val.getAsByteList();
            int pivotIdx = -1;
            int i = 0;
            for (ByteSequence item : list) {
                if (item.equals(pivot)) {
                    pivotIdx = i;
                    break;
                }
                i++;
            }
            if (pivotIdx == -1) {
                return -1;
            }
            int insertIdx = before ? pivotIdx : pivotIdx + 1;
            list.add(insertIdx, element);
            bumpKeyVersion(key);
            return list.size();
        }
    }

    public int linsert(String key, boolean before, String pivot, String element) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(pivot, "pivot cannot be null");
        Objects.requireNonNull(element, "element cannot be null");
        return linsertBytes(ByteSequence.ofUtf8(key), before, ByteSequence.ofUtf8(pivot), ByteSequence.ofUtf8(element));
    }

    public int llen(ByteSequence key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.LIST);
            if (val == null) return 0;
            return val.getAsByteList().size();
        }
    }

    public int llen(String key) {
        return llen(ByteSequence.ofUtf8(key));
    }

    public List<ByteSequence> lrangeBytes(ByteSequence key, int start, int stop) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.LIST);
            if (val == null) return Collections.emptyList();

            List<ByteSequence> list = new ArrayList<>(val.getAsByteList());
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

    public List<String> lrange(String key, int start, int stop) {
        List<ByteSequence> bytesList = lrangeBytes(ByteSequence.ofUtf8(key), start, stop);
        if (bytesList.isEmpty()) return Collections.emptyList();
        List<String> res = new ArrayList<>(bytesList.size());
        for (ByteSequence b : bytesList) {
            res.add(b.toUtf8String());
        }
        return Collections.unmodifiableList(res);
    }

    // ==========================================
    // Phase 2: Sets
    // ==========================================

    public int saddBytes(ByteSequence key, List<ByteSequence> members) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(members, "members cannot be null");
        long currentNow = now();
        AtomicInteger addedCount = new AtomicInteger(0);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing != null && existing.isExpired(currentNow)) existing = null;
                if (existing == null) existing = RedisValue.ofByteSet();
                if (existing.getType() != RedisDataType.SET) throw new WrongTypeException();
                Set<ByteSequence> set = existing.getAsByteSet();
                int added = 0;
                for (ByteSequence member : members) {
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

    public int sadd(String key, List<String> members) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(members, "members cannot be null");
        List<ByteSequence> byteMembers = new ArrayList<>(members.size());
        for (String m : members) {
            byteMembers.add(ByteSequence.ofUtf8(m));
        }
        return saddBytes(ByteSequence.ofUtf8(key), byteMembers);
    }

    public int sremBytes(ByteSequence key, List<ByteSequence> members) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(members, "members cannot be null");
        long currentNow = now();
        AtomicInteger removedCount = new AtomicInteger(0);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing == null) return null;
                if (existing.isExpired(currentNow)) return null;
                if (existing.getType() != RedisDataType.SET) throw new WrongTypeException();
                Set<ByteSequence> set = existing.getAsByteSet();
                int removed = 0;
                for (ByteSequence member : members) {
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

    public int srem(String key, List<String> members) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(members, "members cannot be null");
        List<ByteSequence> byteMembers = new ArrayList<>(members.size());
        for (String m : members) {
            byteMembers.add(ByteSequence.ofUtf8(m));
        }
        return sremBytes(ByteSequence.ofUtf8(key), byteMembers);
    }

    public boolean sismember(ByteSequence key, ByteSequence member) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.SET);
            if (val == null) {
                serverMetrics.recordMiss();
                return false;
            }
            boolean contains = val.getAsByteSet().contains(member);
            if (contains) {
                serverMetrics.recordHit();
            } else {
                serverMetrics.recordMiss();
            }
            return contains;
        }
    }

    public boolean sismember(String key, String member) {
        return sismember(ByteSequence.ofUtf8(key), ByteSequence.ofUtf8(member));
    }

    public Set<ByteSequence> smembersBytes(ByteSequence key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.SET);
            if (val == null) return Collections.emptySet();
            return Collections.unmodifiableSet(new LinkedHashSet<>(val.getAsByteSet()));
        }
    }

    public Set<String> smembers(String key) {
        Set<ByteSequence> byteSet = smembersBytes(ByteSequence.ofUtf8(key));
        if (byteSet.isEmpty()) return Collections.emptySet();
        Set<String> res = new LinkedHashSet<>();
        for (ByteSequence b : byteSet) {
            res.add(b.toUtf8String());
        }
        return Collections.unmodifiableSet(res);
    }

    public int scard(ByteSequence key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.SET);
            if (val == null) return 0;
            return val.getAsByteSet().size();
        }
    }

    public int scard(String key) {
        return scard(ByteSequence.ofUtf8(key));
    }

    // ==========================================
    // Phase 2: Hashes
    // ==========================================

    public int hsetBytes(ByteSequence key, List<ByteSequence> fieldPairs) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(fieldPairs, "fieldPairs cannot be null");
        if (fieldPairs.isEmpty() || fieldPairs.size() % 2 != 0) {
            throw new IllegalArgumentException("fieldPairs must contain a non-empty even number of elements");
        }
        long currentNow = now();
        AtomicInteger newFields = new AtomicInteger(0);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing != null && existing.isExpired(currentNow)) existing = null;
                if (existing == null) existing = RedisValue.ofByteHash();
                if (existing.getType() != RedisDataType.HASH) throw new WrongTypeException();
                Map<ByteSequence, ByteSequence> map = existing.getAsByteHash();
                int count = 0;
                for (int i = 0; i < fieldPairs.size(); i += 2) {
                    ByteSequence field = fieldPairs.get(i);
                    ByteSequence value = fieldPairs.get(i + 1);
                    ByteSequence prev = map.put(field, value);
                    if (prev == null) {
                        count++;
                    }
                }
                newFields.set(count);
                return existing;
            });
            // Redis 7.2: signalModifiedKey is called whenever HSET completes on a hash key
            bumpKeyVersion(key);
            return newFields.get();
        }
    }

    public int hsetBytes(ByteSequence key, Map<ByteSequence, ByteSequence> fieldValues) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(fieldValues, "fieldValues cannot be null");
        List<ByteSequence> pairs = new ArrayList<>(fieldValues.size() * 2);
        for (Map.Entry<ByteSequence, ByteSequence> entry : fieldValues.entrySet()) {
            pairs.add(entry.getKey());
            pairs.add(entry.getValue());
        }
        return hsetBytes(key, pairs);
    }

    public int hsetBytes(ByteSequence key, ByteSequence field, ByteSequence value) {
        return hsetBytes(key, List.of(field, value));
    }

    public int hset(String key, Map<String, String> fieldValues) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(fieldValues, "fieldValues cannot be null");
        List<ByteSequence> pairs = new ArrayList<>(fieldValues.size() * 2);
        for (Map.Entry<String, String> entry : fieldValues.entrySet()) {
            pairs.add(ByteSequence.ofUtf8(entry.getKey()));
            pairs.add(ByteSequence.ofUtf8(entry.getValue()));
        }
        return hsetBytes(ByteSequence.ofUtf8(key), pairs);
    }

    public int hset(String key, String field, String value) {
        return hset(key, Map.of(field, value));
    }

    public ByteSequence hget(ByteSequence key, ByteSequence field) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.HASH);
            if (val == null) {
                serverMetrics.recordMiss();
                return null;
            }
            ByteSequence res = val.getAsByteHash().get(field);
            if (res != null) {
                serverMetrics.recordHit();
            } else {
                serverMetrics.recordMiss();
            }
            return res;
        }
    }

    public String hget(String key, String field) {
        ByteSequence res = hget(ByteSequence.ofUtf8(key), ByteSequence.ofUtf8(field));
        return res != null ? res.toUtf8String() : null;
    }

    public Map<ByteSequence, ByteSequence> hgetallBytes(ByteSequence key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.HASH);
            if (val == null) return Collections.emptyMap();
            return Collections.unmodifiableMap(new LinkedHashMap<>(val.getAsByteHash()));
        }
    }

    public Map<String, String> hgetall(String key) {
        Map<ByteSequence, ByteSequence> byteMap = hgetallBytes(ByteSequence.ofUtf8(key));
        if (byteMap.isEmpty()) return Collections.emptyMap();
        Map<String, String> res = new LinkedHashMap<>(byteMap.size());
        for (Map.Entry<ByteSequence, ByteSequence> entry : byteMap.entrySet()) {
            res.put(entry.getKey().toUtf8String(), entry.getValue().toUtf8String());
        }
        return Collections.unmodifiableMap(res);
    }

    public int hdelBytes(ByteSequence key, List<ByteSequence> fields) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(fields, "fields cannot be null");
        long currentNow = now();
        AtomicInteger removedCount = new AtomicInteger(0);

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            store.compute(key, (k, existing) -> {
                if (existing == null) return null;
                if (existing.isExpired(currentNow)) return null;
                if (existing.getType() != RedisDataType.HASH) throw new WrongTypeException();
                Map<ByteSequence, ByteSequence> map = existing.getAsByteHash();
                int removed = 0;
                for (ByteSequence field : fields) {
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

    public int hdel(String key, List<String> fields) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(fields, "fields cannot be null");
        List<ByteSequence> byteFields = new ArrayList<>(fields.size());
        for (String f : fields) {
            byteFields.add(ByteSequence.ofUtf8(f));
        }
        return hdelBytes(ByteSequence.ofUtf8(key), byteFields);
    }

    public boolean hexists(ByteSequence key, ByteSequence field) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getAndCheckType(key, RedisDataType.HASH);
            if (val == null) return false;
            return val.getAsByteHash().containsKey(field);
        }
    }

    public boolean hexists(String key, String field) {
        return hexists(ByteSequence.ofUtf8(key), ByteSequence.ofUtf8(field));
    }

    // ==========================================
    // Phase 3: Centralized Expiration & TTL
    // ==========================================

    public int expire(ByteSequence key, long seconds) {
        if (seconds <= 0) {
            return delBytes(Collections.singletonList(key));
        }
        return setExpirationMillis(key, seconds * 1000L);
    }

    public int expire(String key, long seconds) {
        return expire(ByteSequence.ofUtf8(key), seconds);
    }

    public int pexpire(ByteSequence key, long milliseconds) {
        if (milliseconds <= 0) {
            return delBytes(Collections.singletonList(key));
        }
        return setExpirationMillis(key, milliseconds);
    }

    public int pexpire(String key, long milliseconds) {
        return pexpire(ByteSequence.ofUtf8(key), milliseconds);
    }

    public int pexpireAt(ByteSequence key, long epochMillis) {
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

    public int pexpireAt(String key, long epochMillis) {
        return pexpireAt(ByteSequence.ofUtf8(key), epochMillis);
    }

    private int setExpirationMillis(ByteSequence key, long durationMillis) {
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

    public long ttl(ByteSequence key) {
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

    public long ttl(String key) {
        return ttl(ByteSequence.ofUtf8(key));
    }

    public long pttl(ByteSequence key) {
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

    public long pttl(String key) {
        return pttl(ByteSequence.ofUtf8(key));
    }

    public int persist(ByteSequence key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            RedisValue val = getInternal(key);
            if (val == null) return 0;
            if (val.getExpireAt() == null) return 0;
            val.setExpireAt(null);
            bumpKeyVersion(key);
            return 1;
        }
    }

    public int persist(String key) {
        return persist(ByteSequence.ofUtf8(key));
    }

    public void setex(ByteSequence key, long seconds, ByteSequence value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        if (seconds <= 0) {
            throw new IllegalArgumentException("ERR invalid expire time in 'setex' command");
        }

        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
            long expireAt = calculateExpireAt(now(), seconds * 1000L);
            RedisValue val = RedisValue.ofByteSequence(value);
            val.setExpireAt(expireAt);
            store.put(key, val);
            bumpKeyVersion(key);
        }
    }

    public void setex(String key, long seconds, String value) {
        setex(ByteSequence.ofUtf8(key), seconds, ByteSequence.ofUtf8(value));
    }

    public boolean isExpired(ByteSequence key) {
        RedisValue val = store.get(key);
        if (val == null) return true;
        return val.isExpired(now());
    }

    public boolean isExpired(String key) {
        return isExpired(ByteSequence.ofUtf8(key));
    }

    // ==========================================
    // Phase 8: Key Utilities & Keyspace Stats
    // ==========================================

    public String typeOf(ByteSequence key) {
        try (StripedLockManager.LockHandle ignored = stripedLocks.readLock(key)) {
            RedisValue val = getInternal(key);
            return val != null ? val.getType().getTypeName() : "none";
        }
    }

    public String typeOf(String key) {
        return typeOf(ByteSequence.ofUtf8(key));
    }

    public void rename(ByteSequence oldKey, ByteSequence newKey) {
        Objects.requireNonNull(oldKey, "oldKey cannot be null");
        Objects.requireNonNull(newKey, "newKey cannot be null");
        try (StripedLockManager.LockHandle ignored = stripedLocks.writeLocksBytes(List.of(oldKey, newKey))) {
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

    public void rename(String oldKey, String newKey) {
        rename(ByteSequence.ofUtf8(oldKey), ByteSequence.ofUtf8(newKey));
    }

    public ByteSequence randomKeyBytes() {
        try (StripedLockManager.LockHandle ignored = stripedLocks.allReadLocks()) {
            long currentNow = now();
            ByteSequence selected = null;
            int seen = 0;
            for (Map.Entry<ByteSequence, RedisValue> entry : store.entrySet()) {
                ByteSequence k = entry.getKey();
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

    public String randomKey() {
        ByteSequence k = randomKeyBytes();
        return k != null ? k.toUtf8String() : null;
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

            for (Map.Entry<ByteSequence, RedisValue> entry : store.entrySet()) {
                ByteSequence key = entry.getKey();
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

                // Estimate entry overhead: Map.Entry + RedisValue wrapper + Key bytes
                estimatedBytes += 64L + key.length();
                switch (val.getType()) {
                    case STRING:
                        estimatedBytes += 40L + val.getAsByteSequence().length();
                        break;
                    case LIST:
                        estimatedBytes += 48L;
                        for (ByteSequence item : val.getAsByteList()) {
                            estimatedBytes += 32L + item.length();
                        }
                        break;
                    case SET:
                        estimatedBytes += 48L;
                        for (ByteSequence member : val.getAsByteSet()) {
                            estimatedBytes += 32L + member.length();
                        }
                        break;
                    case HASH:
                        estimatedBytes += 48L;
                        for (Map.Entry<ByteSequence, ByteSequence> field : val.getAsByteHash().entrySet()) {
                            estimatedBytes += 48L + field.getKey().length() + field.getValue().length();
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

    public SampleResult sampleAndEvict(int sampleSize) {
        if (store.isEmpty()) {
            return new SampleResult(0, 0);
        }

        List<Map.Entry<ByteSequence, RedisValue>> expiringEntries = new ArrayList<>();
        long currentNow = now();

        for (Map.Entry<ByteSequence, RedisValue> entry : store.entrySet()) {
            if (entry.getValue().getExpireAt() != null) {
                expiringEntries.add(entry);
            }
        }

        if (expiringEntries.isEmpty()) {
            return new SampleResult(0, 0);
        }

        Collections.shuffle(expiringEntries, ThreadLocalRandom.current());
        int countToSample = Math.min(sampleSize, expiringEntries.size());
        int purged = 0;

        for (int i = 0; i < countToSample; i++) {
            Map.Entry<ByteSequence, RedisValue> entry = expiringEntries.get(i);
            ByteSequence key = entry.getKey();
            RedisValue candidate = entry.getValue();

            try (StripedLockManager.LockHandle ignored = stripedLocks.writeLock(key)) {
                RedisValue current = store.get(key);
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

    public static class SnapshotEntry {
        public final ByteSequence keyBytes;
        public final String key;
        public final RedisValue value;
        public final Long expireAt;

        public SnapshotEntry(ByteSequence key, RedisValue value, Long expireAt) {
            this.keyBytes = key;
            this.key = key != null ? key.toUtf8String() : null;
            this.value = value;
            this.expireAt = expireAt;
        }

        public SnapshotEntry(String key, RedisValue value, Long expireAt) {
            this(key != null ? ByteSequence.ofUtf8(key) : null, value, expireAt);
        }

        public ByteSequence getKeyBytes() {
            return keyBytes;
        }

        public String getKey() {
            return key;
        }
    }

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
            for (Map.Entry<ByteSequence, RedisValue> entry : store.entrySet()) {
                ByteSequence key = entry.getKey();
                RedisValue val = entry.getValue();
                if (val != null && !val.isExpired(currentNow)) {
                    list.add(new SnapshotEntry(key, val.deepCopy(), val.getExpireAt()));
                }
            }
        }
        return list;
    }

    public static Pattern compileGlobToRegex(String glob) {
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
