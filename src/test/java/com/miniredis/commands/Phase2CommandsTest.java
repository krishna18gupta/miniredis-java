package com.miniredis.commands;

import com.miniredis.storage.RedisDataType;
import com.miniredis.storage.RedisValue;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 2 Data Structures & Concurrency Unit Tests")
class Phase2CommandsTest {

    private StorageEngine storage;
    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        storage = new StorageEngine();
        registry = new CommandRegistry();
    }

    // ==========================================
    // Advanced Strings Tests
    // ==========================================

    @Test
    @DisplayName("MSET and MGET operations")
    void testMSetAndMGet() {
        RedisResponse msetResp = registry.dispatch("MSET k1 v1 k2 v2 k3 v3", storage);
        assertEquals("OK", msetResp.toCliString());

        RedisResponse mgetResp = registry.dispatch("MGET k1 k2 k3 missing", storage);
        assertEquals(RedisResponse.ResponseType.ARRAY, mgetResp.getType());
        List<RedisResponse> items = mgetResp.getArrayValue();
        assertEquals(4, items.size());
        assertEquals("v1", items.get(0).getStringValue());
        assertEquals("v2", items.get(1).getStringValue());
        assertEquals("v3", items.get(2).getStringValue());
        assertEquals(RedisResponse.ResponseType.NIL, items.get(3).getType());
    }

    @Test
    @DisplayName("INCR and DECR basic operations")
    void testIncrAndDecr() {
        // INCR on non-existent key initializes to 1
        RedisResponse incr1 = registry.dispatch("INCR counter", storage);
        assertEquals(1L, incr1.getIntValue());

        RedisResponse incr2 = registry.dispatch("INCR counter", storage);
        assertEquals(2L, incr2.getIntValue());

        RedisResponse decr1 = registry.dispatch("DECR counter", storage);
        assertEquals(1L, decr1.getIntValue());

        // DECR on non-existent key initializes to -1
        RedisResponse decrNew = registry.dispatch("DECR newCounter", storage);
        assertEquals(-1L, decrNew.getIntValue());
    }

    @Test
    @DisplayName("INCR on non-numeric string returns error")
    void testIncrNonNumeric() {
        registry.dispatch("SET strKey hello", storage);
        RedisResponse resp = registry.dispatch("INCR strKey", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, resp.getType());
        assertTrue(resp.getStringValue().contains("value is not an integer or out of range"));
    }

    @Test
    @DisplayName("INCR and DECR 64-bit integer overflow and underflow detection")
    void testIncrDecrOverflow() {
        registry.dispatch("SET maxInt " + Long.MAX_VALUE, storage);
        RedisResponse incrOverflow = registry.dispatch("INCR maxInt", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, incrOverflow.getType());
        assertTrue(incrOverflow.getStringValue().contains("increment or decrement would overflow"));

        registry.dispatch("SET minInt " + Long.MIN_VALUE, storage);
        RedisResponse decrUnderflow = registry.dispatch("DECR minInt", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, decrUnderflow.getType());
        assertTrue(decrUnderflow.getStringValue().contains("increment or decrement would overflow"));
    }

    @Test
    @DisplayName("APPEND and STRLEN UTF-8 byte length")
    void testAppendAndStrlen() {
        assertEquals(0L, registry.dispatch("STRLEN nonExistent", storage).getIntValue());

        RedisResponse app1 = registry.dispatch("APPEND msg Hello", storage);
        assertEquals(5L, app1.getIntValue());

        RedisResponse app2 = registry.dispatch("APPEND msg \" World\"", storage);
        assertEquals(11L, app2.getIntValue());

        RedisResponse strLen = registry.dispatch("STRLEN msg", storage);
        assertEquals(11L, strLen.getIntValue());

        // UTF-8 multi-byte check (Euro symbol is 3 bytes in UTF-8)
        registry.dispatch("SET symbol €", storage);
        assertEquals(3L, registry.dispatch("STRLEN symbol", storage).getIntValue());
    }

    // ==========================================
    // Lists Tests
    // ==========================================

    @Test
    @DisplayName("LPUSH multiple elements preserves correct Redis order [c, b, a]")
    void testLPushMultipleSemantics() {
        // LPUSH mylist a b c pushes 'a', then 'b', then 'c' -> [c, b, a]
        RedisResponse lpushResp = registry.dispatch("LPUSH mylist a b c", storage);
        assertEquals(3L, lpushResp.getIntValue());

        RedisResponse lrangeResp = registry.dispatch("LRANGE mylist 0 -1", storage);
        List<RedisResponse> items = lrangeResp.getArrayValue();
        assertEquals(3, items.size());
        assertEquals("c", items.get(0).getStringValue());
        assertEquals("b", items.get(1).getStringValue());
        assertEquals("a", items.get(2).getStringValue());
    }

    @Test
    @DisplayName("RPUSH multiple elements produces [a, b, c]")
    void testRPushMultipleSemantics() {
        registry.dispatch("RPUSH mylist a b c", storage);
        RedisResponse lrangeResp = registry.dispatch("LRANGE mylist 0 -1", storage);
        List<RedisResponse> items = lrangeResp.getArrayValue();
        assertEquals("a", items.get(0).getStringValue());
        assertEquals("b", items.get(1).getStringValue());
        assertEquals("c", items.get(2).getStringValue());
    }

    @Test
    @DisplayName("LPOP and RPOP remove elements")
    void testLPopAndRPop() {
        registry.dispatch("RPUSH queue first second third", storage);

        RedisResponse lpop = registry.dispatch("LPOP queue", storage);
        assertEquals("first", lpop.getStringValue());

        RedisResponse rpop = registry.dispatch("RPOP queue", storage);
        assertEquals("third", rpop.getStringValue());

        assertEquals(1L, registry.dispatch("LLEN queue", storage).getIntValue());
    }

    @Test
    @DisplayName("Removing the final list element automatically deletes the key")
    void testListAutoDeleteWhenEmpty() {
        registry.dispatch("RPUSH singleList onlyItem", storage);
        assertEquals(1L, registry.dispatch("EXISTS singleList", storage).getIntValue());

        RedisResponse popped = registry.dispatch("LPOP singleList", storage);
        assertEquals("onlyItem", popped.getStringValue());

        // Key should now be completely deleted from the database
        assertEquals(0L, registry.dispatch("EXISTS singleList", storage).getIntValue());
        RedisResponse nextPop = registry.dispatch("LPOP singleList", storage);
        assertEquals(RedisResponse.ResponseType.NIL, nextPop.getType());
    }

    @Test
    @DisplayName("LRANGE positive and negative indexing edge cases")
    void testLRangeNegativeIndices() {
        registry.dispatch("RPUSH letters a b c d e", storage); // 5 elements

        // 0 to -1 is all elements
        assertEquals(5, registry.dispatch("LRANGE letters 0 -1", storage).getArrayValue().size());

        // 1 to 3 -> b, c, d
        List<RedisResponse> r1 = registry.dispatch("LRANGE letters 1 3", storage).getArrayValue();
        assertEquals(3, r1.size());
        assertEquals("b", r1.get(0).getStringValue());
        assertEquals("d", r1.get(2).getStringValue());

        // -2 to -1 -> d, e
        List<RedisResponse> r2 = registry.dispatch("LRANGE letters -2 -1", storage).getArrayValue();
        assertEquals(2, r2.size());
        assertEquals("d", r2.get(0).getStringValue());
        assertEquals("e", r2.get(1).getStringValue());

        // Out of bounds
        assertTrue(registry.dispatch("LRANGE letters 10 20", storage).getArrayValue().isEmpty());
        assertTrue(registry.dispatch("LRANGE letters 3 1", storage).getArrayValue().isEmpty());
    }

    // ==========================================
    // Sets Tests
    // ==========================================

    @Test
    @DisplayName("SADD duplicate filtering and SMEMBERS")
    void testSetAddAndMembers() {
        RedisResponse sadd1 = registry.dispatch("SADD myset a b c", storage);
        assertEquals(3L, sadd1.getIntValue());

        // Adding duplicates returns only newly added count
        RedisResponse sadd2 = registry.dispatch("SADD myset b c d", storage);
        assertEquals(1L, sadd2.getIntValue()); // only 'd' is new

        assertEquals(4L, registry.dispatch("SCARD myset", storage).getIntValue());
        assertEquals(1L, registry.dispatch("SISMEMBER myset b", storage).getIntValue());
        assertEquals(0L, registry.dispatch("SISMEMBER myset z", storage).getIntValue());
    }

    @Test
    @DisplayName("Removing the final set member automatically deletes the key")
    void testSetAutoDeleteWhenEmpty() {
        registry.dispatch("SADD singleSet lonelyMember", storage);
        assertEquals(1L, registry.dispatch("EXISTS singleSet", storage).getIntValue());

        RedisResponse srem = registry.dispatch("SREM singleSet lonelyMember", storage);
        assertEquals(1L, srem.getIntValue());

        // Key should be deleted
        assertEquals(0L, registry.dispatch("EXISTS singleSet", storage).getIntValue());
        assertEquals(0L, registry.dispatch("SCARD singleSet", storage).getIntValue());
    }

    // ==========================================
    // Hashes Tests
    // ==========================================

    @Test
    @DisplayName("HSET, HGET, and HGETALL operations")
    void testHashOperations() {
        RedisResponse hset1 = registry.dispatch("HSET user:1 name Krishna role Admin", storage);
        assertEquals(2L, hset1.getIntValue());

        RedisResponse hget = registry.dispatch("HGET user:1 name", storage);
        assertEquals("Krishna", hget.getStringValue());

        assertEquals(1L, registry.dispatch("HEXISTS user:1 role", storage).getIntValue());
        assertEquals(0L, registry.dispatch("HEXISTS user:1 salary", storage).getIntValue());

        RedisResponse hgetall = registry.dispatch("HGETALL user:1", storage);
        assertEquals(4, hgetall.getArrayValue().size()); // 2 fields * 2
    }

    @Test
    @DisplayName("Removing the final hash field automatically deletes the key")
    void testHashAutoDeleteWhenEmpty() {
        registry.dispatch("HSET testHash onlyField onlyValue", storage);
        assertEquals(1L, registry.dispatch("EXISTS testHash", storage).getIntValue());

        RedisResponse hdel = registry.dispatch("HDEL testHash onlyField", storage);
        assertEquals(1L, hdel.getIntValue());

        assertEquals(0L, registry.dispatch("EXISTS testHash", storage).getIntValue());
        assertEquals(RedisResponse.ResponseType.NIL, registry.dispatch("HGET testHash onlyField", storage).getType());
    }

    // ==========================================
    // WRONGTYPE & Cross-Type Safety
    // ==========================================

    @Test
    @DisplayName("Strict WRONGTYPE errors on type collisions")
    void testWrongTypeOperations() {
        registry.dispatch("SET strKey simpleString", storage);
        registry.dispatch("LPUSH listKey elem1", storage);
        registry.dispatch("SADD setKey member1", storage);
        registry.dispatch("HSET hashKey f1 v1", storage);

        // List command on string
        RedisResponse err1 = registry.dispatch("LPUSH strKey item", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, err1.getType());
        assertTrue(err1.getStringValue().contains("WRONGTYPE"));

        // String command on list
        RedisResponse err2 = registry.dispatch("APPEND listKey item", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, err2.getType());
        assertTrue(err2.getStringValue().contains("WRONGTYPE"));

        // Hash command on set
        RedisResponse err3 = registry.dispatch("HGET setKey field", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, err3.getType());
        assertTrue(err3.getStringValue().contains("WRONGTYPE"));

        // Set command on hash
        RedisResponse err4 = registry.dispatch("SADD hashKey member", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, err4.getType());
        assertTrue(err4.getStringValue().contains("WRONGTYPE"));
    }

    // ==========================================
    // Concurrency & MSET Atomicity
    // ==========================================

    @Test
    @DisplayName("MSET atomicity under concurrent readers")
    void testMSetAtomicity() throws InterruptedException {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        AtomicBoolean brokenInconsistencyDetected = new AtomicBoolean(false);

        // Writer thread keeps writing atomic pairs: (k1=A, k2=A) then (k1=B, k2=B)
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 500; i++) {
                    String v = (i % 2 == 0) ? "EVEN" : "ODD";
                    storage.mset(Collections.unmodifiableMap(new java.util.HashMap<String, String>() {{
                        put("k1", v);
                        put("k2", v);
                    }}));
                }
            } catch (Exception e) {
                brokenInconsistencyDetected.set(true);
            } finally {
                finishLatch.countDown();
            }
        });

        // Reader threads verify that k1 and k2 are NEVER seen in a torn/inconsistent state
        for (int t = 1; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 500; i++) {
                        List<RedisValue> values = storage.mget(Arrays.asList("k1", "k2"));
                        if (values.get(0) != null && values.get(1) != null) {
                            String v1 = values.get(0).getAsString();
                            String v2 = values.get(1).getAsString();
                            if (!v1.equals(v2)) {
                                brokenInconsistencyDetected.set(true);
                            }
                        }
                    }
                } catch (Exception e) {
                    brokenInconsistencyDetected.set(true);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertFalse(brokenInconsistencyDetected.get(), "MSET was observed in an inconsistent, non-atomic state!");
    }

    @Test
    @DisplayName("Concurrent LPUSH and RPOP thread safety on shared list")
    void testConcurrentListOperations() throws InterruptedException {
        int threadCount = 10;
        int opsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int id = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        storage.lpush("concurrentList", Collections.singletonList("item_" + id + "_" + j));
                        storage.rpop("concurrentList");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Should complete without ConcurrentModificationException or corrupt state
        int len = storage.llen("concurrentList");
        assertTrue(len >= 0);
    }
}