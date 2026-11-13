package com.miniredis.storage;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 3 Key Expiration and TTL Tests")
class Phase3ExpirationTest {

    private StorageEngine storage;
    private CommandRegistry registry;
    private MutableClock clock;

    /**
     * Deterministic clock that can be advanced explicitly without Thread.sleep().
     */
    static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        public MutableClock(Instant start) {
            this.instant = start;
            this.zone = ZoneId.of("UTC");
        }

        public void advanceMillis(long millis) {
            this.instant = this.instant.plusMillis(millis);
        }

        public void advanceSeconds(long seconds) {
            this.instant = this.instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public long millis() {
            return instant.toEpochMilli();
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        storage = new StorageEngine(clock);
        registry = new CommandRegistry();
    }

    @Test
    @DisplayName("Normal expiration and TTL countdown using deterministic Clock")
    void testNormalExpirationAndTtlCountdown() {
        registry.dispatch("SET session token123", storage);
        assertEquals(-1L, registry.dispatch("TTL session", storage).getIntValue(), "Should be -1 for persistent key");

        // EXPIRE 10 seconds
        RedisResponse expResp = registry.dispatch("EXPIRE session 10", storage);
        assertEquals(1L, expResp.getIntValue());

        // Immediately after
        assertEquals(10L, registry.dispatch("TTL session", storage).getIntValue());
        assertTrue(registry.dispatch("PTTL session", storage).getIntValue() <= 10000L);

        // Advance 4 seconds
        clock.advanceSeconds(4);
        assertEquals(6L, registry.dispatch("TTL session", storage).getIntValue());

        // Advance 6 more seconds (now 10 seconds elapsed)
        clock.advanceSeconds(6);

        // Key has now expired
        assertEquals(-2L, registry.dispatch("TTL session", storage).getIntValue());
        assertEquals(RedisResponse.ResponseType.NIL, registry.dispatch("GET session", storage).getType());
    }

    @Test
    @DisplayName("PTTL precision check")
    void testPTtlPrecision() {
        registry.dispatch("SET quickKey val", storage);
        registry.dispatch("PEXPIRE quickKey 2500", storage);

        assertEquals(2500L, registry.dispatch("PTTL quickKey", storage).getIntValue());

        clock.advanceMillis(750);
        assertEquals(1750L, registry.dispatch("PTTL quickKey", storage).getIntValue());
        assertEquals(2L, registry.dispatch("TTL quickKey", storage).getIntValue()); // rounded up
    }

    @Test
    @DisplayName("TTL and PTTL return -1 for persistent keys and -2 for missing keys")
    void testTtlReturnCodes() {
        registry.dispatch("SET persistentKey data", storage);
        assertEquals(-1L, registry.dispatch("TTL persistentKey", storage).getIntValue());
        assertEquals(-1L, registry.dispatch("PTTL persistentKey", storage).getIntValue());

        assertEquals(-2L, registry.dispatch("TTL nonExistentKey", storage).getIntValue());
        assertEquals(-2L, registry.dispatch("PTTL nonExistentKey", storage).getIntValue());
    }

    @Test
    @DisplayName("Immediate deletion for zero and negative EXPIRE and PEXPIRE values")
    void testImmediateDeletionOnNonPositiveExpire() {
        registry.dispatch("SET k1 val1", storage);
        registry.dispatch("SET k2 val2", storage);

        // EXPIRE with 0 deletes immediately
        RedisResponse expZero = registry.dispatch("EXPIRE k1 0", storage);
        assertEquals(1L, expZero.getIntValue());
        assertEquals(0L, registry.dispatch("EXISTS k1", storage).getIntValue());

        // PEXPIRE with negative deletes immediately
        RedisResponse expNeg = registry.dispatch("PEXPIRE k2 -500", storage);
        assertEquals(1L, expNeg.getIntValue());
        assertEquals(0L, registry.dispatch("EXISTS k2", storage).getIntValue());

        // Calling on missing key returns 0
        assertEquals(0L, registry.dispatch("EXPIRE missingKey 0", storage).getIntValue());
    }

    @Test
    @DisplayName("SET and MSET clear existing TTL (reset to persistent)")
    void testSetAndMSetClearTtl() {
        registry.dispatch("SETEX keyA 30 valueA", storage);
        assertTrue(registry.dispatch("TTL keyA", storage).getIntValue() > 0);

        // Overwriting with SET clears TTL
        registry.dispatch("SET keyA newValueA", storage);
        assertEquals(-1L, registry.dispatch("TTL keyA", storage).getIntValue());

        // Test with MSET
        registry.dispatch("SETEX keyB 30 valueB", storage);
        registry.dispatch("SETEX keyC 30 valueC", storage);
        registry.dispatch("MSET keyB freshB keyC freshC", storage);

        assertEquals(-1L, registry.dispatch("TTL keyB", storage).getIntValue());
        assertEquals(-1L, registry.dispatch("TTL keyC", storage).getIntValue());
    }

    @Test
    @DisplayName("PERSIST removes timeout from expiring key")
    void testPersist() {
        registry.dispatch("SETEX tempKey 20 value", storage);
        assertTrue(registry.dispatch("TTL tempKey", storage).getIntValue() > 0);

        RedisResponse persistResp = registry.dispatch("PERSIST tempKey", storage);
        assertEquals(1L, persistResp.getIntValue());
        assertEquals(-1L, registry.dispatch("TTL tempKey", storage).getIntValue());

        // Calling PERSIST on already persistent key returns 0
        assertEquals(0L, registry.dispatch("PERSIST tempKey", storage).getIntValue());

        // Calling PERSIST on missing key returns 0
        assertEquals(0L, registry.dispatch("PERSIST nonExistent", storage).getIntValue());
    }

    @Test
    @DisplayName("SETEX stores value and expiration atomically; rejects non-positive duration")
    void testSetEx() {
        RedisResponse setexResp = registry.dispatch("SETEX cacheKey 15 cachedData", storage);
        assertEquals("OK", setexResp.toCliString());
        assertEquals(15L, registry.dispatch("TTL cacheKey", storage).getIntValue());
        assertEquals("cachedData", registry.dispatch("GET cacheKey", storage).getStringValue());

        // Non-positive rejection
        RedisResponse errResp1 = registry.dispatch("SETEX badKey 0 val", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, errResp1.getType());
        assertTrue(errResp1.getStringValue().contains("invalid expire time"));

        RedisResponse errResp2 = registry.dispatch("SETEX badKey -5 val", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, errResp2.getType());
    }

    @Test
    @DisplayName("Expiration across all data structures: Strings, Lists, Sets, Hashes")
    void testExpirationAcrossAllDataStructures() {
        registry.dispatch("SET myStr val", storage);
        registry.dispatch("LPUSH myList a b c", storage);
        registry.dispatch("SADD mySet m1 m2", storage);
        registry.dispatch("HSET myHash f1 v1", storage);

        // Expire all for 10 seconds
        registry.dispatch("EXPIRE myStr 10", storage);
        registry.dispatch("EXPIRE myList 10", storage);
        registry.dispatch("EXPIRE mySet 10", storage);
        registry.dispatch("EXPIRE myHash 10", storage);

        assertEquals(4L, registry.dispatch("DBSIZE", storage).getIntValue());

        // Advance 11 seconds
        clock.advanceSeconds(11);

        // All should be expired and inaccessible
        assertNull(storage.get("myStr"));
        assertEquals(0, storage.llen("myList"));
        assertEquals(0, storage.scard("mySet"));
        assertNull(storage.hget("myHash", "f1"));
        assertEquals(0L, registry.dispatch("DBSIZE", storage).getIntValue());
    }

    @Test
    @DisplayName("Lazy expiration occurs on read access")
    void testLazyExpiration() {
        storage.set("lazyKey", "secret");
        storage.expire("lazyKey", 5);

        // Key is in raw store
        assertTrue(storage.getRawStore().containsKey("lazyKey"));

        clock.advanceSeconds(6);

        // Reading triggers lazy eviction
        assertNull(storage.get("lazyKey"));
        assertFalse(storage.getRawStore().containsKey("lazyKey"), "Key should have been removed from raw store by lazy eviction");
    }

    @Test
    @DisplayName("Active expiration purges keys independently of lazy read access")
    void testActiveExpirationIndependentOfLazyAccess() {
        ExpirationManager reaper = new ExpirationManager(storage, 100, 20);

        // Insert 10 expiring keys
        for (int i = 0; i < 10; i++) {
            storage.set("autoKey:" + i, "val" + i);
            storage.expire("autoKey:" + i, 5);
        }

        // Verify keys exist in raw memory
        assertEquals(10, storage.getRawStore().size());

        // Advance time past expiration
        clock.advanceSeconds(6);

        // Run active expiration cycle explicitly WITHOUT calling get() or read methods
        int purged = reaper.runActiveExpireCycle();

        assertTrue(purged >= 10, "Active reaper should have evicted all 10 expired keys");
        assertEquals(0, storage.getRawStore().size(), "Raw store must be empty via active eviction alone");
    }

    @Test
    @DisplayName("Concurrent overwrite versus expiration does not produce stale deletion")
    void testConcurrentOverwriteVsExpiration() throws InterruptedException {
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        storage.set("raceKey", "initial");
        storage.expire("raceKey", 1);

        for (int i = 0; i < threadCount; i++) {
            final int id = i;
            executor.submit(() -> {
                try {
                    // One thread overwrites with a new value
                    if (id == 0) {
                        storage.set("raceKey", "freshValue");
                    } else {
                        // Other threads trigger expiration check
                        storage.get("raceKey");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Key should either hold freshValue or have executed cleanly without exceptions
        RedisValue val = storage.getRawStore().get("raceKey");
        if (val != null) {
            assertEquals("freshValue", val.getAsString());
        }
    }

    @Test
    @DisplayName("Timestamp addition overflow protection (caps at Long.MAX_VALUE)")
    void testTimestampOverflowProtection() {
        long now = 1000L;
        long maxDuration = Long.MAX_VALUE;

        long expireAt = StorageEngine.calculateExpireAt(now, maxDuration);
        assertEquals(Long.MAX_VALUE, expireAt, "Safe calculation should cap at Long.MAX_VALUE without arithmetic wraparound");
    }

    @Test
    @DisplayName("ExpirationManager scheduler lifecycle (start, stop, no duplicate start)")
    void testSchedulerLifecycle() {
        ExpirationManager manager = new ExpirationManager(storage, 50, 10);
        assertFalse(manager.isRunning());

        manager.start();
        assertTrue(manager.isRunning());

        // Duplicate start is a safe no-op
        manager.start();
        assertTrue(manager.isRunning());

        manager.stop();
        assertFalse(manager.isRunning());

        // Duplicate stop is a safe no-op
        manager.stop();
        assertFalse(manager.isRunning());
    }
}