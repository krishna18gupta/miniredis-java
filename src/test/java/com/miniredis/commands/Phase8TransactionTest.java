package com.miniredis.commands;

import com.miniredis.aof.AofHealth;
import com.miniredis.aof.AofManager;
import com.miniredis.aof.FsyncPolicy;
import com.miniredis.protocol.RespParser;
import com.miniredis.protocol.RespSerializer;
import com.miniredis.server.TransactionContext;
import com.miniredis.storage.ExpirationManager;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 8: Optimistic Transactions (MULTI / EXEC / DISCARD / WATCH / UNWATCH)")
class Phase8TransactionTest {

    static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advanceSeconds(long seconds) {
            this.instant = this.instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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

    private MutableClock clock;
    private StorageEngine storage;
    private CommandRegistry registry;
    private TransactionContext tx;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
        storage = new StorageEngine(clock);
        registry = new CommandRegistry();
        tx = new TransactionContext();
    }

    @Test
    @DisplayName("MULTI queues commands with +QUEUED and EXEC executes in FIFO order returning array of replies")
    void testBasicMultiAndExec() {
        assertEquals("OK", registry.dispatch("MULTI", storage, tx).getStringValue());
        assertTrue(tx.isInMulti());

        assertEquals("QUEUED", registry.dispatch("SET k1 v1", storage, tx).getStringValue());
        assertEquals("QUEUED", registry.dispatch("INCRBY counter 5", storage, tx).getStringValue());
        assertEquals("QUEUED", registry.dispatch("INCRBY counter 3", storage, tx).getStringValue());
        assertEquals("QUEUED", registry.dispatch("TYPE k1", storage, tx).getStringValue());
        assertEquals("QUEUED", registry.dispatch("LPUSH items a b", storage, tx).getStringValue());
        assertEquals("QUEUED", registry.dispatch("GET k1", storage, tx).getStringValue());

        // Not yet applied before EXEC
        assertNull(storage.get("k1"));
        assertNull(storage.get("counter"));

        RedisResponse execResp = registry.dispatch("EXEC", storage, tx);
        assertFalse(tx.isInMulti());
        assertEquals(RedisResponse.ResponseType.ARRAY, execResp.getType());
        List<RedisResponse> replies = execResp.getArrayValue();
        assertNotNull(replies);
        assertEquals(6, replies.size());
        assertEquals("OK", replies.get(0).getStringValue());
        assertEquals(5L, replies.get(1).getIntValue());
        assertEquals(8L, replies.get(2).getIntValue());
        assertEquals("string", replies.get(3).getStringValue());
        assertEquals(2L, replies.get(4).getIntValue());
        assertEquals("v1", replies.get(5).getStringValue());
    }

    @Test
    @DisplayName("DISCARD clears queued commands and clears watched keys")
    void testDiscardClearsQueueAndWatches() {
        registry.dispatch("SET balance 100", storage);
        registry.dispatch("WATCH balance", storage, tx);

        // Another client mutates watched key
        registry.dispatch("SET balance 200", storage);

        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET balance 999", storage, tx);
        assertEquals("OK", registry.dispatch("DISCARD", storage, tx).getStringValue());

        assertFalse(tx.isInMulti());
        assertTrue(tx.getQueuedCommands().isEmpty());
        assertTrue(tx.getWatchedKeys().isEmpty());
        assertEquals("200", storage.get("balance").getAsString());

        // New transaction without WATCH commits cleanly
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET balance 300", storage, tx);
        RedisResponse execResp = registry.dispatch("EXEC", storage, tx);
        assertNotNull(execResp.getArrayValue());
        assertEquals("300", storage.get("balance").getAsString());
    }

    @Test
    @DisplayName("Invalid transaction states and EXECABORT on queue-time syntax/arity errors")
    void testTransactionStateErrorsAndExecAbort() {
        // EXEC & DISCARD without MULTI
        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch("EXEC", storage, tx).getType());
        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch("DISCARD", storage, tx).getType());

        // Nested MULTI disallowed (without aborting existing MULTI), WATCH inside MULTI disallowed
        registry.dispatch("SET clean ok", storage);
        registry.dispatch("MULTI", storage, tx);
        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch("MULTI", storage, tx).getType());
        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch("WATCH clean", storage, tx).getType());
        registry.dispatch("SET inside_ok yes", storage, tx);
        RedisResponse nestedExec = registry.dispatch("EXEC", storage, tx);
        assertNotNull(nestedExec.getArrayValue());
        assertEquals("yes", storage.get("inside_ok").getAsString());

        // Queue-time unknown command triggers EXECABORT
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET should_not_apply bad", storage, tx);
        RedisResponse unknownErr = registry.dispatch("NOSUCHCMD foo", storage, tx);
        assertEquals(RedisResponse.ResponseType.ERROR, unknownErr.getType());
        assertTrue(tx.isExecAbort());

        RedisResponse aborted = registry.dispatch("EXEC", storage, tx);
        assertEquals(RedisResponse.ResponseType.ERROR, aborted.getType());
        assertTrue(aborted.getStringValue().startsWith("EXECABORT"));
        assertNull(storage.get("should_not_apply"));

        // Queue-time wrong arity triggers EXECABORT
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET never_applied 1", storage, tx);
        RedisResponse arityErr = registry.dispatch("SET onlyKey", storage, tx);
        assertEquals(RedisResponse.ResponseType.ERROR, arityErr.getType());
        RedisResponse aborted2 = registry.dispatch("EXEC", storage, tx);
        assertTrue(aborted2.getStringValue().startsWith("EXECABORT"));
        assertNull(storage.get("never_applied"));
    }

    @Test
    @DisplayName("Runtime errors inside EXEC return per-item errors without rolling back valid siblings")
    void testRuntimeErrorsInsideExec() {
        registry.dispatch("SET textVal hello", storage);

        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET step1 ok", storage, tx);
        registry.dispatch("INCR textVal", storage, tx);   // runtime error (non-integer)
        registry.dispatch("LPUSH textVal x", storage, tx); // runtime WRONGTYPE
        registry.dispatch("SET step4 ok", storage, tx);

        RedisResponse execResp = registry.dispatch("EXEC", storage, tx);
        List<RedisResponse> items = execResp.getArrayValue();
        assertEquals(4, items.size());
        assertEquals("OK", items.get(0).getStringValue());
        assertEquals(RedisResponse.ResponseType.ERROR, items.get(1).getType());
        assertEquals(RedisResponse.ResponseType.ERROR, items.get(2).getType());
        assertEquals("OK", items.get(3).getStringValue());

        assertEquals("ok", storage.get("step1").getAsString());
        assertEquals("ok", storage.get("step4").getAsString());
    }

    @Test
    @DisplayName("WATCH detects writes (even ABA), DEL, RENAME, HSET update, FLUSHDB, and passive/active TTL expiry")
    void testWatchInvalidationAndUnwatch() throws IOException {
        TransactionContext otherClient = new TransactionContext();

        // 1. Overwrite with SAME value (ABA problem) still aborts
        registry.dispatch("SET k1 same", storage);
        registry.dispatch("WATCH k1", storage, tx);
        registry.dispatch("SET k1 same", storage); // different client overwrites same string
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET result shouldFail", storage, tx);
        RedisResponse abaResp = registry.dispatch("EXEC", storage, tx);
        assertEquals(RedisResponse.ResponseType.ARRAY, abaResp.getType());
        assertNull(abaResp.getArrayValue(), "ABA modification must return RESP2 null array (*-1)");
        assertEquals("*-1\r\n", new String(RespSerializer.serializeResponse(abaResp), StandardCharsets.UTF_8));
        assertNull(storage.get("result"));

        // Verify null array round-trips cleanly through RespParser
        RedisResponse parsed = RespParser.parseResponse(new ByteArrayInputStream("*-1\r\n".getBytes(StandardCharsets.UTF_8)));
        assertNull(parsed.getArrayValue());

        // 2. Missing watched key created later aborts
        registry.dispatch("WATCH futureKey", storage, tx);
        registry.dispatch("SET futureKey created", storage);
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET checkFuture bad", storage, tx);
        assertNull(registry.dispatch("EXEC", storage, tx).getArrayValue());
        assertNull(storage.get("checkFuture"));

        // 3. UNWATCH clears dirtied watches
        registry.dispatch("WATCH k1", storage, tx);
        registry.dispatch("DEL k1", storage);
        registry.dispatch("UNWATCH", storage, tx);
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET afterUnwatch good", storage, tx);
        assertNotNull(registry.dispatch("EXEC", storage, tx).getArrayValue());
        assertEquals("good", storage.get("afterUnwatch").getAsString());

        // 4. Hash existing field update invalidates WATCH
        registry.dispatch("HSET myHash f1 v1", storage);
        registry.dispatch("WATCH myHash", storage, tx);
        registry.dispatch("HSET myHash f1 v2", storage);
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET hCheck bad", storage, tx);
        assertNull(registry.dispatch("EXEC", storage, tx).getArrayValue());

        // 5. Passive expiration without GET or ExpirationManager invalidates WATCH
        registry.dispatch("SETEX ttlKey 10 alive", storage);
        registry.dispatch("WATCH ttlKey", storage, tx);
        clock.advanceSeconds(11);
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET ttlApplied no", storage, tx);
        assertNull(registry.dispatch("EXEC", storage, tx).getArrayValue());
        assertNull(storage.get("ttlApplied"));

        // 6. Active expiration by ExpirationManager invalidates WATCH
        registry.dispatch("SETEX activeKey 10 alive", storage);
        registry.dispatch("WATCH activeKey", storage, tx);
        clock.advanceSeconds(11);
        ExpirationManager reaper = new ExpirationManager(storage, 100, 50);
        assertTrue(reaper.runActiveExpireCycle() >= 1);
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET activeApplied no", storage, tx);
        assertNull(registry.dispatch("EXEC", storage, tx).getArrayValue());

        // 7. EXPIRE metadata change & FLUSHDB invalidate WATCH
        registry.dispatch("SET persistKey keep", storage);
        registry.dispatch("WATCH persistKey", storage, tx);
        registry.dispatch("EXPIRE persistKey 60", storage);
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET expireApplied no", storage, tx);
        assertNull(registry.dispatch("EXEC", storage, tx).getArrayValue());

        registry.dispatch("WATCH anyKey", storage, tx);
        registry.dispatch("FLUSHDB", storage);
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET flushApplied no", storage, tx);
        assertNull(registry.dispatch("EXEC", storage, tx).getArrayValue());

        // 8. RENAME source/target invalidation
        registry.dispatch("SET src val", storage);
        registry.dispatch("WATCH src", storage, tx);
        registry.dispatch("RENAME src dst", storage);
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET renameApplied no", storage, tx);
        assertNull(registry.dispatch("EXEC", storage, tx).getArrayValue());
        assertNull(otherClient.getWatchedKeys().get("src"));
    }

    @Test
    @DisplayName("Deadlock-free concurrent WATCH/MULTI/EXEC bank transfers preserve exact conservation invariant")
    void testConcurrentMultiKeyTransactionsZeroDeadlock() throws Exception {
        registry.dispatch("SET acc:A 1000", storage);
        registry.dispatch("SET acc:B 1000", storage);

        int workerCount = 8;
        int transfersPerWorker = 40;
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger committedTransfers = new AtomicInteger(0);
        AtomicInteger abortedCasRetries = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < workerCount; w++) {
            final boolean forward = (w % 2 == 0);
            futures.add(pool.submit(() -> {
                startGate.await();
                TransactionContext clientTx = new TransactionContext();
                for (int i = 0; i < transfersPerWorker; i++) {
                    while (true) {
                        // Watch keys in opposite orders across workers to stress deadlock avoidance
                        if (forward) {
                            registry.dispatch("WATCH acc:A acc:B", storage, clientTx);
                        } else {
                            registry.dispatch("WATCH acc:B acc:A", storage, clientTx);
                        }

                        List<RedisResponse> snap = registry.dispatch("MGET acc:A acc:B", storage).getArrayValue();
                        long balA = Long.parseLong(snap.get(0).getStringValue());
                        long balB = Long.parseLong(snap.get(1).getStringValue());
                        // Linearizability check: atomic MGET must NEVER observe torn intermediate transfer state
                        assertEquals(2000L, balA + balB, "Atomic MGET sum must remain conserved at 2000");

                        registry.dispatch("MULTI", storage, clientTx);
                        if (forward) {
                            registry.dispatch("DECRBY acc:A 1", storage, clientTx);
                            registry.dispatch("INCRBY acc:B 1", storage, clientTx);
                        } else {
                            registry.dispatch("INCRBY acc:A 1", storage, clientTx);
                            registry.dispatch("DECRBY acc:B 1", storage, clientTx);
                        }
                        RedisResponse res = registry.dispatch("EXEC", storage, clientTx);
                        if (res.getType() == RedisResponse.ResponseType.ARRAY && res.getArrayValue() != null) {
                            committedTransfers.incrementAndGet();
                            break;
                        }
                        abortedCasRetries.incrementAndGet();
                    }
                }
                return null;
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS); // Fails test on deadlock
        }
        pool.shutdown();

        assertEquals(workerCount * transfersPerWorker, committedTransfers.get());
        assertTrue(abortedCasRetries.get() > 0, "Concurrent WATCH contention should trigger optimistic aborts & retries");
        long finalA = Long.parseLong(storage.get("acc:A").getAsString());
        long finalB = Long.parseLong(storage.get("acc:B").getAsString());
        assertEquals(2000L, finalA + finalB);
        assertEquals(1000L, finalA);
        assertEquals(1000L, finalB);
    }

    @Test
    @DisplayName("AOF persists committed transactions atomically and discards incomplete trailing MULTI blocks on crash recovery")
    void testAofTransactionPersistenceAndCrashRecovery(@TempDir Path tempDir) throws IOException {
        File aofFile = tempDir.resolve("tx.aof").toFile();
        AofManager aof = new AofManager(aofFile, FsyncPolicy.ALWAYS, storage, registry);
        storage.setAofManager(aof);
        aof.start();

        // Committed transaction
        registry.dispatch("MULTI", storage, tx);
        registry.dispatch("SET txKey1 val1", storage, tx);
        registry.dispatch("INCRBY txCounter 15", storage, tx);
        registry.dispatch("SETEX txExp 50 expiring", storage, tx);
        RedisResponse committed = registry.dispatch("EXEC", storage, tx);
        assertNotNull(committed.getArrayValue());
        aof.close();

        // Simulate crash mid-transaction by appending MULTI + SET without EXEC
        try (FileOutputStream fos = new FileOutputStream(aofFile, true)) {
            fos.write(RespSerializer.encodeCommand(List.of("MULTI")));
            fos.write(RespSerializer.encodeCommand(List.of("SET", "txKey1", "CORRUPTED_PARTIAL")));
            fos.write(RespSerializer.encodeCommand(List.of("SET", "incompleteOnly", "shouldNotExist")));
        }

        StorageEngine freshStorage = new StorageEngine(clock);
        CommandRegistry freshRegistry = new CommandRegistry();
        try (AofManager recoveryAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, freshStorage, freshRegistry)) {
            int replayed = recoveryAof.recover();
            assertEquals(3, replayed, "Only the 3 commands from the committed transaction may be replayed");
            assertTrue(recoveryAof.getHealth().getRecoveryErrors() >= 1);
            assertEquals(AofHealth.Status.DEGRADED, recoveryAof.getHealth().getStatus());
        }

        assertEquals("val1", freshStorage.get("txKey1").getAsString());
        assertEquals("15", freshStorage.get("txCounter").getAsString());
        assertEquals(50L, freshStorage.ttl("txExp"));
        assertNull(freshStorage.get("incompleteOnly"), "Incomplete trailing transaction must never replay");
    }
}
