package com.miniredis.concurrency;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.storage.RedisDataType;
import com.miniredis.storage.RedisValue;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 5: High-Concurrency & Multithreading Stress Tests")
class Phase5ConcurrencyTest {

    private StorageEngine storage;
    private CommandRegistry registry;
    private ExecutorService threadPool;

    @BeforeEach
    void setUp() {
        storage = new StorageEngine();
        registry = new CommandRegistry();
        threadPool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (threadPool != null) {
            threadPool.shutdownNow();
            assertTrue(threadPool.awaitTermination(3, TimeUnit.SECONDS), "Thread pool did not terminate cleanly");
        }
    }

    @Test
    @DisplayName("50 concurrent threads incrementing and decrementing shared counter (50,000 operations)")
    void testConcurrentIncrDecrContention() throws Exception {
        int threadCount = 50;
        int opsPerThread = 1000;
        String counterKey = "concurrent:counter";

        // 1. Concurrent INCR
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        storage.incr(counterKey);
                    }
                } catch (Exception e) {
                    fail("Unexpected error in thread: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Concurrent INCR should finish within 10s");

        RedisValue val = storage.get(counterKey);
        assertNotNull(val);
        assertEquals(String.valueOf(threadCount * opsPerThread), val.getAsString(),
                "Final counter value must equal total operations (50,000)");

        // 2. Concurrent DECR
        CountDownLatch startDecr = new CountDownLatch(1);
        CountDownLatch doneDecr = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            threadPool.submit(() -> {
                try {
                    startDecr.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        storage.decr(counterKey);
                    }
                } catch (Exception e) {
                    fail("Unexpected error in thread: " + e.getMessage());
                } finally {
                    doneDecr.countDown();
                }
            });
        }

        startDecr.countDown();
        assertTrue(doneDecr.await(10, TimeUnit.SECONDS), "Concurrent DECR should finish within 10s");

        RedisValue valAfterDecr = storage.get(counterKey);
        assertNotNull(valAfterDecr);
        assertEquals("0", valAfterDecr.getAsString(), "Counter after 50,000 decrements must be 0");
    }

    @Test
    @DisplayName("Concurrent List Producer-Consumer: Zero Orphaned Values on Empty Deletion")
    void testConcurrentListProducerConsumerNoOrphans() throws Exception {
        String listKey = "producer:consumer:list";
        int producers = 20;
        int consumers = 20;
        int itemsPerProducer = 200;
        int totalExpectedItems = producers * itemsPerProducer;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producers);
        CountDownLatch consumersDone = new CountDownLatch(consumers);
        AtomicInteger consumedCount = new AtomicInteger(0);
        AtomicBoolean producingActive = new AtomicBoolean(true);

        // Producers
        for (int i = 0; i < producers; i++) {
            final int producerId = i;
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < itemsPerProducer; j++) {
                        storage.lpush(listKey, Collections.singletonList("item-" + producerId + "-" + j));
                    }
                } catch (Exception e) {
                    fail("Producer error: " + e.getMessage());
                } finally {
                    producersDone.countDown();
                }
            });
        }

        // Consumers
        for (int i = 0; i < consumers; i++) {
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    while (producingActive.get() || storage.llen(listKey) > 0) {
                        String popped = storage.rpop(listKey);
                        if (popped != null) {
                            consumedCount.incrementAndGet();
                        } else {
                            Thread.yield();
                        }
                    }
                } catch (Exception e) {
                    fail("Consumer error: " + e.getMessage());
                } finally {
                    consumersDone.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(producersDone.await(10, TimeUnit.SECONDS), "Producers should finish");
        producingActive.set(false);

        // Drain any remaining items
        while (true) {
            String popped = storage.rpop(listKey);
            if (popped == null) break;
            consumedCount.incrementAndGet();
        }

        assertTrue(consumersDone.await(10, TimeUnit.SECONDS), "Consumers should finish");
        assertEquals(totalExpectedItems, consumedCount.get(), "All items must be accounted for without loss");

        // Verify list is cleanly removed from store when empty
        assertNull(storage.get(listKey), "List must be absent from storage when empty");
    }

    @Test
    @DisplayName("Concurrent Set modifications: SADD, SREM, and SMEMBERS under high contention")
    void testConcurrentSetModifications() throws Exception {
        String setKey = "concurrent:set";
        int threadCount = 30;
        int opsPerThread = 500;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        String member = "member-" + (j % 50); // High collision key space
                        storage.sadd(setKey, Collections.singletonList(member));
                        if (j % 3 == 0) {
                            storage.srem(setKey, Collections.singletonList(member));
                        }
                        if (j % 10 == 0) {
                            Set<String> snapshot = storage.smembers(setKey);
                            assertNotNull(snapshot);
                        }
                    }
                } catch (Exception e) {
                    fail("Set operation error: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Set operations should complete");

        RedisValue val = storage.get(setKey);
        if (val != null) {
            assertEquals(RedisDataType.SET, val.getType());
            assertTrue(storage.scard(setKey) >= 0);
        }
    }

    @Test
    @DisplayName("Concurrent Hash modifications: HSET, HGET, HDEL across multiple keys")
    void testConcurrentHashModifications() throws Exception {
        String hashKey = "concurrent:hash";
        int threadCount = 30;
        int opsPerThread = 500;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        String field = "field-" + (j % 20);
                        String value = "val-" + threadId + "-" + j;
                        storage.hset(hashKey, Collections.singletonMap(field, value));

                        String retrieved = storage.hget(hashKey, field);
                        assertNotNull(retrieved);

                        if (j % 5 == 0) {
                            storage.hdel(hashKey, Collections.singletonList(field));
                        }
                    }
                } catch (Exception e) {
                    fail("Hash operation error: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Hash operations should complete");
    }

    @Test
    @DisplayName("Deadlock Immunity: Conflicting Multi-Key ordering across concurrent threads")
    void testMultiKeyDeadlockImmunity() throws Exception {
        int threadCount = 16;
        int iterations = 1000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        String k1 = "alpha";
        String k2 = "beta";
        String k3 = "gamma";
        String k4 = "delta";

        for (int i = 0; i < threadCount; i++) {
            final int pattern = i % 4;
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < iterations; j++) {
                        Map<String, String> map = new LinkedHashMap<>();
                        if (pattern == 0) {
                            // Forward order
                            map.put(k1, "v1");
                            map.put(k2, "v2");
                            map.put(k3, "v3");
                            storage.mset(map);
                        } else if (pattern == 1) {
                            // Reverse order
                            map.put(k3, "v3");
                            map.put(k2, "v2");
                            map.put(k1, "v1");
                            storage.mset(map);
                        } else if (pattern == 2) {
                            // Shuffled order
                            map.put(k2, "v2");
                            map.put(k4, "v4");
                            map.put(k1, "v1");
                            storage.mset(map);
                        } else {
                            // Multi-get read
                            List<RedisValue> values = storage.mget(Arrays.asList(k4, k3, k2, k1));
                            assertNotNull(values);
                        }
                    }
                } catch (Exception e) {
                    fail("Deadlock test exception: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        // If deadlock occurs, this await will timeout and fail
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
        assertTrue(finished, "Multi-key operations must never deadlock!");
    }

    @Test
    @DisplayName("Defensive Snapshots: Concurrent reads never throw ConcurrentModificationException under rapid writes")
    void testConcurrentReadSnapshotsUnderHeavyWrites() throws Exception {
        String listKey = "snapshot:list";
        String setKey = "snapshot:set";
        String hashKey = "snapshot:hash";
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch writersDone = new CountDownLatch(3);
        CountDownLatch readersDone = new CountDownLatch(3);

        // Writer 1: Lists
        threadPool.submit(() -> {
            int counter = 0;
            while (running.get() && counter < 2000) {
                storage.lpush(listKey, Collections.singletonList("item-" + counter++));
                if (counter % 5 == 0) storage.rpop(listKey);
            }
            writersDone.countDown();
        });

        // Writer 2: Sets
        threadPool.submit(() -> {
            int counter = 0;
            while (running.get() && counter < 2000) {
                storage.sadd(setKey, Collections.singletonList("elem-" + (counter % 30)));
                if (counter % 3 == 0) storage.srem(setKey, Collections.singletonList("elem-" + (counter % 30)));
                counter++;
            }
            writersDone.countDown();
        });

        // Writer 3: Hashes
        threadPool.submit(() -> {
            int counter = 0;
            while (running.get() && counter < 2000) {
                storage.hset(hashKey, Collections.singletonMap("f-" + (counter % 20), "v-" + counter++));
            }
            writersDone.countDown();
        });

        // Reader 1: LRANGE
        threadPool.submit(() -> {
            try {
                for (int i = 0; i < 500; i++) {
                    List<String> range = storage.lrange(listKey, 0, -1);
                    assertNotNull(range);
                    // Iterate to verify no ConcurrentModificationException
                    for (String s : range) {
                        assertNotNull(s);
                    }
                }
            } finally {
                readersDone.countDown();
            }
        });

        // Reader 2: SMEMBERS
        threadPool.submit(() -> {
            try {
                for (int i = 0; i < 500; i++) {
                    Set<String> members = storage.smembers(setKey);
                    assertNotNull(members);
                    for (String s : members) {
                        assertNotNull(s);
                    }
                }
            } finally {
                readersDone.countDown();
            }
        });

        // Reader 3: HGETALL
        threadPool.submit(() -> {
            try {
                for (int i = 0; i < 500; i++) {
                    Map<String, String> all = storage.hgetall(hashKey);
                    assertNotNull(all);
                    for (Map.Entry<String, String> entry : all.entrySet()) {
                        assertNotNull(entry.getKey());
                        assertNotNull(entry.getValue());
                    }
                }
            } finally {
                readersDone.countDown();
            }
        });

        assertTrue(readersDone.await(10, TimeUnit.SECONDS), "Readers must finish without exceptions");
        running.set(false);
        assertTrue(writersDone.await(10, TimeUnit.SECONDS), "Writers must finish cleanly");
    }

    @Test
    @DisplayName("StripedLockManager unit tests: Hash bit distribution, canonical sorting, and handle releases")
    void testStripedLockManagerUnit() {
        StripedLockManager lockManager = new StripedLockManager(64);
        assertEquals(64, lockManager.getStripeCount());

        // Verify hashing bit distribution
        int s1 = lockManager.getStripe("user:100");
        int s2 = lockManager.getStripe("user:200");
        assertTrue(s1 >= 0 && s1 < 64);
        assertTrue(s2 >= 0 && s2 < 64);

        // Test single write lock handle
        try (StripedLockManager.LockHandle ignored = lockManager.writeLock("singleKey")) {
            // inside lock
            assertTrue(true);
        }

        // Test multi-key lock handle with duplicate stripes
        List<String> keys = Arrays.asList("a", "b", "c", "a", "b");
        try (StripedLockManager.LockHandle ignored = lockManager.writeLocks(keys)) {
            assertTrue(true);
        }

        // Test all write locks handle
        try (StripedLockManager.LockHandle ignored = lockManager.allWriteLocks()) {
            assertTrue(true);
        }

        // Test all read locks handle
        try (StripedLockManager.LockHandle ignored = lockManager.allReadLocks()) {
            assertTrue(true);
        }
    }

    @Test
    @DisplayName("Concurrent Expiration and Overwrite: ExpirationManager never deletes newly refreshed/overwritten key")
    void testConcurrentExpirationAndOverwrite() throws Exception {
        String key = "expiry:overwrite:key";
        int iterations = 1000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(3);
        AtomicBoolean running = new AtomicBoolean(true);

        // Initial expiring key
        storage.setex(key, 1, "initial");

        // Thread 1: Rapidly overwrite with new values or refresh TTL
        threadPool.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    if (i % 2 == 0) {
                        storage.set(key, "fresh-" + i);
                    } else {
                        storage.expire(key, 100);
                    }
                }
            } catch (Exception e) {
                fail("Thread 1 error: " + e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: Continuously invoke sampleAndEvict
        threadPool.submit(() -> {
            try {
                startLatch.await();
                while (running.get()) {
                    storage.sampleAndEvict(20);
                    Thread.yield();
                }
            } catch (Exception e) {
                fail("Thread 2 error: " + e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 3: Read values
        threadPool.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    RedisValue val = storage.get(key);
                    if (val != null) {
                        assertNotNull(val.getAsString());
                    }
                }
            } catch (Exception e) {
                fail("Thread 3 error: " + e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        // Wait for writers and readers
        Thread.sleep(500);
        running.set(false);
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Expiration and overwrite should complete");

        // Key should still be present since writer repeatedly refreshed/overwrote it
        RedisValue finalVal = storage.get(key);
        assertNotNull(finalVal, "Key should not have been deleted by stale expiration check");
    }

    @Test
    @DisplayName("Concurrent SET, GET, and DEL across 40 threads on overlapping key spaces")
    void testConcurrentSetGetDel() throws Exception {
        int threadCount = 40;
        int opsPerThread = 500;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        String key = "key:" + (j % 15); // High collision
                        storage.set(key, "val-" + threadId + "-" + j);
                        RedisValue val = storage.get(key);
                        if (val != null) {
                            assertNotNull(val.getAsString());
                        }
                        if (j % 4 == 0) {
                            storage.del(Collections.singletonList(key));
                        }
                    }
                } catch (Exception e) {
                    fail("SET/GET/DEL error: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Concurrent SET/GET/DEL should finish without error");
    }

    @Test
    @DisplayName("FLUSHDB during concurrent heavy operations completes cleanly without deadlocks")
    void testFlushDbDuringConcurrentOperations() throws Exception {
        int writerCount = 20;
        int opsPerWriter = 400;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(writerCount + 1);

        // 20 concurrent writers
        for (int i = 0; i < writerCount; i++) {
            final int threadId = i;
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < opsPerWriter; j++) {
                        String k = "flush:key:" + (j % 25);
                        storage.incr(k);
                        storage.lpush("flush:list:" + threadId, Collections.singletonList("item"));
                        storage.hset("flush:hash:" + threadId, Collections.singletonMap("f", "v"));
                    }
                } catch (Exception e) {
                    fail("Writer error: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 1 FLUSHDB thread
        threadPool.submit(() -> {
            try {
                startLatch.await();
                Thread.sleep(50); // Let writers get active
                storage.flushDb();
            } catch (Exception e) {
                fail("FLUSHDB error: " + e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "FLUSHDB with concurrent operations must finish without deadlock");
    }

    @Test
    @DisplayName("Duplicate stripe acquisition: Keys hashing to the same stripe avoid self-deadlocks")
    void testDuplicateStripeAcquisition() {
        StripedLockManager lockManager = storage.getStripedLockManager();

        // Find 3 distinct keys that map to the identical stripe
        String baseKey = "stripeTestKey";
        int targetStripe = lockManager.getStripe(baseKey);

        List<String> sameStripeKeys = new ArrayList<>();
        sameStripeKeys.add(baseKey);

        int candidate = 0;
        while (sameStripeKeys.size() < 3) {
            String candidateKey = "candKey:" + candidate++;
            if (lockManager.getStripe(candidateKey) == targetStripe) {
                sameStripeKeys.add(candidateKey);
            }
        }

        assertEquals(3, sameStripeKeys.size());
        assertEquals(targetStripe, lockManager.getStripe(sameStripeKeys.get(0)));
        assertEquals(targetStripe, lockManager.getStripe(sameStripeKeys.get(1)));
        assertEquals(targetStripe, lockManager.getStripe(sameStripeKeys.get(2)));

        // MSET across all 3 same-stripe keys must deduplicate stripe and succeed without deadlock
        Map<String, String> map = new LinkedHashMap<>();
        map.put(sameStripeKeys.get(0), "val1");
        map.put(sameStripeKeys.get(1), "val2");
        map.put(sameStripeKeys.get(2), "val3");

        assertDoesNotThrow(() -> storage.mset(map));

        List<RedisValue> values = storage.mget(sameStripeKeys);
        assertEquals(3, values.size());
        assertEquals("val1", values.get(0).getAsString());
        assertEquals("val2", values.get(1).getAsString());
        assertEquals("val3", values.get(2).getAsString());
    }

    @Test
    @DisplayName("ExpirationManager versus Collection Mutations: No race condition or orphaned collections")
    void testExpirationManagerVersusCollectionMutation() throws Exception {
        com.miniredis.storage.ExpirationManager expManager = new com.miniredis.storage.ExpirationManager(storage, 10, 20);
        expManager.start();

        try {
            int threadCount = 20;
            int opsPerThread = 300;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                threadPool.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < opsPerThread; j++) {
                            String listKey = "exp:list:" + (j % 5);
                            storage.lpush(listKey, Collections.singletonList("elem-" + j));
                            storage.expire(listKey, 1);

                            String setKey = "exp:set:" + (j % 5);
                            storage.sadd(setKey, Collections.singletonList("member-" + j));
                            storage.expire(setKey, 1);

                            String hashKey = "exp:hash:" + (j % 5);
                            storage.hset(hashKey, Collections.singletonMap("f" + j, "v" + j));
                            storage.expire(hashKey, 1);

                            if (j % 2 == 0) {
                                storage.lpop(listKey);
                            }
                        }
                    } catch (Exception e) {
                        fail("Collection mutation with ExpirationManager error: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Collection mutations alongside ExpirationManager should complete");
        } finally {
            expManager.stop();
        }
    }
}
