package com.miniredis.commands.hashes;

import com.miniredis.aof.AofManager;
import com.miniredis.aof.FsyncPolicy;
import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.server.TransactionContext;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.RedisDataType;
import com.miniredis.storage.RedisValue;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 18 Stage D: Redis 7.2 Hash Command Parity (HSET & HDEL)")
public class HashCommandParityTest {

    private StorageEngine storage;
    private CommandRegistry registry;

    @BeforeEach
    public void setUp() {
        storage = new StorageEngine();
        registry = new CommandRegistry();
    }

    // =========================================================================
    // 1. HSET Parity Tests
    // =========================================================================

    @Test
    @DisplayName("HSET: Single new field insertion returns 1 and stores value")
    public void testHSetSingleNewField() {
        RedisResponse resp = registry.dispatch("HSET user:1 name Alice", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp.getType());
        assertEquals(1L, resp.getIntValue());

        assertEquals("Alice", storage.hget("user:1", "name"));
        assertTrue(storage.hexists("user:1", "name"));
    }

    @Test
    @DisplayName("HSET: Multiple new fields insertion returns count of new fields")
    public void testHSetMultipleNewFields() {
        RedisResponse resp = registry.dispatch("HSET user:2 name Bob age 30 role Developer", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp.getType());
        assertEquals(3L, resp.getIntValue());

        assertEquals("Bob", storage.hget("user:2", "name"));
        assertEquals("30", storage.hget("user:2", "age"));
        assertEquals("Developer", storage.hget("user:2", "role"));
    }

    @Test
    @DisplayName("HSET: Overwriting existing field returns 0 and updates value")
    public void testHSetOverwriteExistingField() {
        registry.dispatch("HSET user:3 status active", storage);

        RedisResponse overwriteResp = registry.dispatch("HSET user:3 status inactive", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, overwriteResp.getType());
        assertEquals(0L, overwriteResp.getIntValue());
        assertEquals("inactive", storage.hget("user:3", "status"));
    }

    @Test
    @DisplayName("HSET: Combination of new and existing fields returns only new field count")
    public void testHSetMixedNewAndExistingFields() {
        registry.dispatch("HSET user:4 f1 v1 f2 v2", storage);

        // f1 and f2 are overwritten, f3 is new
        RedisResponse resp = registry.dispatch("HSET user:4 f1 v1_new f3 v3 f2 v2_new", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp.getType());
        assertEquals(1L, resp.getIntValue()); // Only f3 is new

        assertEquals("v1_new", storage.hget("user:4", "f1"));
        assertEquals("v2_new", storage.hget("user:4", "f2"));
        assertEquals("v3", storage.hget("user:4", "f3"));
    }

    @Test
    @DisplayName("HSET: Duplicate fields in single command processed sequentially with last value winning")
    public void testHSetDuplicateFieldsInSingleInvocation() {
        // Case 1: Fresh key, duplicate field in same command
        RedisResponse resp1 = registry.dispatch("HSET user:5 tag alpha tag beta", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp1.getType());
        assertEquals(1L, resp1.getIntValue(), "Should count newly created field only once");
        assertEquals("beta", storage.hget("user:5", "tag"), "Last value must win");

        // Case 2: Existing key, duplicate field in same command
        RedisResponse resp2 = registry.dispatch("HSET user:5 tag gamma tag delta", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp2.getType());
        assertEquals(0L, resp2.getIntValue(), "Field already exists so returns 0");
        assertEquals("delta", storage.hget("user:5", "tag"), "Last value must win");

        // Case 3: Multiple duplicates mixed with new fields
        RedisResponse resp3 = registry.dispatch("HSET user:5 a 1 b 2 a 3 c 4 b 5", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp3.getType());
        assertEquals(3L, resp3.getIntValue(), "Fields a, b, c are new -> count 3");
        assertEquals("3", storage.hget("user:5", "a"));
        assertEquals("5", storage.hget("user:5", "b"));
        assertEquals("4", storage.hget("user:5", "c"));
    }

    @Test
    @DisplayName("HSET: Binary-safe keys, fields, and values with nulls, CRLF and high-bit bytes")
    public void testHSetBinarySafety() {
        byte[] rawKey = new byte[] { 'h', 'a', 's', 'h', ':', 0x00, 0x01, (byte) 0xFF };
        byte[] rawField1 = new byte[] { 0x00, 'f', 'i', 'e', 'l', 'd', '\r', '\n', 0x00 };
        byte[] rawVal1 = new byte[] { (byte) 0xFE, (byte) 0xED, (byte) 0xFA, (byte) 0xCE, 0x00, 0x01 };
        byte[] rawField2 = "normalField".getBytes();
        byte[] rawVal2 = new byte[0]; // Empty value

        ByteSequence bKey = ByteSequence.of(rawKey);
        ByteSequence bField1 = ByteSequence.of(rawField1);
        ByteSequence bVal1 = ByteSequence.of(rawVal1);
        ByteSequence bField2 = ByteSequence.of(rawField2);
        ByteSequence bVal2 = ByteSequence.of(rawVal2);

        RedisResponse resp = registry.dispatchBytes(List.of(ByteSequence.ofAscii("HSET"), bKey, bField1, bVal1, bField2, bVal2), storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp.getType());
        assertEquals(2L, resp.getIntValue());

        ByteSequence fetchedVal1 = storage.hget(bKey, bField1);
        assertNotNull(fetchedVal1);
        assertArrayEquals(rawVal1, fetchedVal1.toByteArray());

        ByteSequence fetchedVal2 = storage.hget(bKey, bField2);
        assertNotNull(fetchedVal2);
        assertArrayEquals(rawVal2, fetchedVal2.toByteArray());
    }

    @Test
    @DisplayName("HSET: Rejects malformed argument counts with Redis 7.2 exact error message")
    public void testHSetMalformedArguments() {
        // Missing field/value
        RedisResponse resp1 = registry.dispatch("HSET", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, resp1.getType());
        assertEquals("ERR wrong number of arguments for 'hset' command", resp1.getStringValue());

        RedisResponse resp2 = registry.dispatch("HSET myhash", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, resp2.getType());
        assertEquals("ERR wrong number of arguments for 'hset' command", resp2.getStringValue());

        RedisResponse resp3 = registry.dispatch("HSET myhash field1", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, resp3.getType());
        assertEquals("ERR wrong number of arguments for 'hset' command", resp3.getStringValue());

        // Odd number of field/value arguments
        RedisResponse resp4 = registry.dispatch("HSET myhash f1 v1 f2", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, resp4.getType());
        assertEquals("ERR wrong number of arguments for 'hset' command", resp4.getStringValue());
    }

    @Test
    @DisplayName("HSET: Fails with WRONGTYPE if key holds String, List or Set")
    public void testHSetWrongType() {
        // String
        storage.set("str_key", "string_val");
        RedisResponse r1 = registry.dispatch("HSET str_key f v", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, r1.getType());
        assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", r1.getStringValue());
        assertEquals("string_val", storage.get("str_key").getAsString());

        // List
        storage.lpush("list_key", List.of("e1"));
        RedisResponse r2 = registry.dispatch("HSET list_key f v", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, r2.getType());
        assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", r2.getStringValue());

        // Set
        storage.sadd("set_key", List.of("m1"));
        RedisResponse r3 = registry.dispatch("HSET set_key f v", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, r3.getType());
        assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", r3.getStringValue());
    }

    @Test
    @DisplayName("HSET: Preserves existing key TTL and expiration metadata")
    public void testHSetPreservesTtl() {
        registry.dispatch("HSET hash:ttl f1 v1", storage);
        registry.dispatch("EXPIRE hash:ttl 60", storage);

        long ttl1 = storage.ttl("hash:ttl");
        assertTrue(ttl1 > 0 && ttl1 <= 60);

        // Mutate hash via HSET
        registry.dispatch("HSET hash:ttl f2 v2 f1 v1_updated", storage);

        long ttl2 = storage.ttl("hash:ttl");
        assertTrue(ttl2 > 0 && ttl2 <= 60, "TTL must be preserved across HSET mutations");
        assertEquals("v1_updated", storage.hget("hash:ttl", "f1"));
        assertEquals("v2", storage.hget("hash:ttl", "f2"));
    }

    // =========================================================================
    // 2. HDEL Parity Tests
    // =========================================================================

    @Test
    @DisplayName("HDEL: Single existing field deletion returns 1 and removes field")
    public void testHDelSingleExistingField() {
        registry.dispatch("HSET h1 a 1 b 2", storage);

        RedisResponse resp = registry.dispatch("HDEL h1 a", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp.getType());
        assertEquals(1L, resp.getIntValue());
        assertTrue(resp.isMutated(), "HDEL removing field must be marked as mutated");

        assertNull(storage.hget("h1", "a"));
        assertEquals("2", storage.hget("h1", "b"));
        assertTrue(storage.exists("h1"));
    }

    @Test
    @DisplayName("HDEL: Multiple existing fields deletion returns count of removed fields")
    public void testHDelMultipleExistingFields() {
        registry.dispatch("HSET h2 a 1 b 2 c 3 d 4", storage);

        RedisResponse resp = registry.dispatch("HDEL h2 a c d", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp.getType());
        assertEquals(3L, resp.getIntValue());
        assertTrue(resp.isMutated());

        assertNull(storage.hget("h2", "a"));
        assertNull(storage.hget("h2", "c"));
        assertNull(storage.hget("h2", "d"));
        assertEquals("2", storage.hget("h2", "b"));
    }

    @Test
    @DisplayName("HDEL: Mixture of existing and nonexistent fields returns only actually removed count")
    public void testHDelMixedFields() {
        registry.dispatch("HSET h3 x 10 y 20", storage);

        RedisResponse resp = registry.dispatch("HDEL h3 x missing1 y missing2", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp.getType());
        assertEquals(2L, resp.getIntValue());
        assertTrue(resp.isMutated());

        assertNull(storage.hget("h3", "x"));
        assertNull(storage.hget("h3", "y"));
        assertFalse(storage.exists("h3"), "Key should be deleted after all fields removed");
    }

    @Test
    @DisplayName("HDEL: Duplicate field arguments in single invocation only counted once")
    public void testHDelDuplicateFieldArguments() {
        registry.dispatch("HSET h4 f1 v1 f2 v2", storage);

        RedisResponse resp = registry.dispatch("HDEL h4 f1 f1 f1", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp.getType());
        assertEquals(1L, resp.getIntValue(), "Duplicate field argument should only be counted once");
        assertTrue(resp.isMutated());

        assertNull(storage.hget("h4", "f1"));
        assertEquals("v2", storage.hget("h4", "f2"));
    }

    @Test
    @DisplayName("HDEL: Deletion against missing key returns 0 with mutated=false")
    public void testHDelMissingKey() {
        RedisResponse resp = registry.dispatch("HDEL non_existent_hash field1 field2", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp.getType());
        assertEquals(0L, resp.getIntValue());
        assertFalse(resp.isMutated(), "HDEL removing 0 fields must not be marked mutated");
        assertFalse(storage.exists("non_existent_hash"));
    }

    @Test
    @DisplayName("HDEL: Deletion of nonexistent fields from existing hash returns 0 with mutated=false")
    public void testHDelNonExistentFields() {
        registry.dispatch("HSET h5 existing val", storage);

        RedisResponse resp = registry.dispatch("HDEL h5 no_such_f1 no_such_f2", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, resp.getType());
        assertEquals(0L, resp.getIntValue());
        assertFalse(resp.isMutated(), "HDEL removing 0 fields must not be marked mutated");
        assertTrue(storage.exists("h5"));
    }

    @Test
    @DisplayName("HDEL: Deleting final field completely prunes key and expiration from store")
    public void testHDelPrunesKeyAndExpirationWhenEmpty() {
        registry.dispatch("HSET h6 only_field val", storage);
        registry.dispatch("EXPIRE h6 120", storage);
        assertTrue(storage.exists("h6"));

        RedisResponse resp = registry.dispatch("HDEL h6 only_field", storage);
        assertEquals(1L, resp.getIntValue());
        assertTrue(resp.isMutated());

        assertFalse(storage.exists("h6"), "Hash key must be deleted when last field is removed");
        assertEquals(-2L, storage.ttl("h6"), "TTL must return -2 for deleted key");
    }

    @Test
    @DisplayName("HDEL: Returns WRONGTYPE when invoked against non-hash keys")
    public void testHDelWrongType() {
        storage.set("my_str", "value");
        RedisResponse resp = registry.dispatch("HDEL my_str f", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, resp.getType());
        assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", resp.getStringValue());
    }

    @Test
    @DisplayName("HDEL: Rejects missing field arguments with exact Redis error")
    public void testHDelSyntaxError() {
        RedisResponse r1 = registry.dispatch("HDEL", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, r1.getType());
        assertEquals("ERR wrong number of arguments for 'hdel' command", r1.getStringValue());

        RedisResponse r2 = registry.dispatch("HDEL myhash", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, r2.getType());
        assertEquals("ERR wrong number of arguments for 'hdel' command", r2.getStringValue());
    }

    @Test
    @DisplayName("HDEL: Binary-safe field deletion")
    public void testHDelBinarySafety() {
        ByteSequence key = ByteSequence.of(new byte[] { 'b', 'i', 'n', ':', 0x01 });
        ByteSequence f1 = ByteSequence.of(new byte[] { 0x00, '\n', (byte) 0xFF });
        ByteSequence f2 = ByteSequence.of(new byte[] { 'k', 0x00, 'e' });
        ByteSequence v = ByteSequence.ofAscii("val");

        storage.hsetBytes(key, List.of(f1, v, f2, v));

        List<ByteSequence> delTokens = List.of(ByteSequence.ofAscii("HDEL"), key, f1);
        RedisResponse resp = registry.dispatchBytes(delTokens, storage);
        assertEquals(1L, resp.getIntValue());

        assertNull(storage.hget(key, f1));
        assertNotNull(storage.hget(key, f2));
    }

    // =========================================================================
    // 3. WATCH / Concurrency / Version Bumping Parity
    // =========================================================================

    @Test
    @DisplayName("WATCH: HSET overwriting field with 0 new fields still triggers WATCH abort")
    public void testHSetZeroNewFieldsBumpsWatch() {
        registry.dispatch("HSET watch:h1 field existing_val", storage);

        TransactionContext client1 = new TransactionContext();
        registry.dispatch("WATCH watch:h1", storage, client1);
        registry.dispatch("MULTI", storage, client1);
        registry.dispatch("GET dummy", storage, client1);

        // Client 2 overwrites existing field with SAME value (returns 0 new fields)
        RedisResponse hsetResp = registry.dispatch("HSET watch:h1 field existing_val", storage);
        assertEquals(0L, hsetResp.getIntValue());

        // Client 1 executes transaction: must abort because key was touched
        RedisResponse execResp = registry.dispatch("EXEC", storage, client1);
        assertNull(execResp.getArrayValue(), "Transaction must abort (nil) when watched hash is touched by HSET");
    }

    @Test
    @DisplayName("WATCH: HDEL removing 0 fields DOES NOT trigger WATCH abort")
    public void testHDelZeroRemovedDoesNotTriggerWatch() {
        registry.dispatch("HSET watch:h2 field val", storage);

        TransactionContext client1 = new TransactionContext();
        registry.dispatch("WATCH watch:h2", storage, client1);
        registry.dispatch("MULTI", storage, client1);
        registry.dispatch("HGET watch:h2 field", storage, client1);

        // Client 2 attempts to delete non-existent field (removes 0 fields)
        RedisResponse hdelResp = registry.dispatch("HDEL watch:h2 no_such_field", storage);
        assertEquals(0L, hdelResp.getIntValue());
        assertFalse(hdelResp.isMutated());

        // Client 1 executes transaction: must SUCCEED because no mutation occurred
        RedisResponse execResp = registry.dispatch("EXEC", storage, client1);
        assertNotNull(execResp.getArrayValue(), "Transaction should not abort when HDEL removed 0 fields");
        assertEquals(1, execResp.getArrayValue().size());
        assertEquals("val", execResp.getArrayValue().get(0).getStringValue());
    }

    @Test
    @DisplayName("WATCH: HDEL removing 1 field triggers WATCH abort")
    public void testHDelRemovingFieldTriggersWatch() {
        registry.dispatch("HSET watch:h3 f1 v1 f2 v2", storage);

        TransactionContext client1 = new TransactionContext();
        registry.dispatch("WATCH watch:h3", storage, client1);
        registry.dispatch("MULTI", storage, client1);
        registry.dispatch("PING", storage, client1);

        // Client 2 deletes f1
        registry.dispatch("HDEL watch:h3 f1", storage);

        // Client 1 executes transaction: must abort
        RedisResponse execResp = registry.dispatch("EXEC", storage, client1);
        assertNull(execResp.getArrayValue(), "Transaction must abort when watched hash had a field deleted");
    }

    // =========================================================================
    // 4. Atomicity & Concurrency
    // =========================================================================

    @Test
    @DisplayName("Concurrency: Concurrent multi-field HSET and HDEL do not lose fields")
    public void testConcurrentHSetAndHDel() throws InterruptedException, ExecutionException {
        int threads = 8;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                latch.await();
                for (int i = 0; i < operationsPerThread; i++) {
                    String field = "t" + threadId + "_f" + i;
                    registry.dispatch("HSET conc:hash " + field + " val_" + i, storage);
                    if (i % 2 == 0) {
                        registry.dispatch("HDEL conc:hash " + field, storage);
                    }
                }
                return null;
            }));
        }

        latch.countDown();
        for (Future<Void> f : futures) {
            f.get();
        }
        executor.shutdown();

        // Verify state consistency: odd numbered fields must exist, even numbered must not
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < operationsPerThread; i++) {
                String field = "t" + t + "_f" + i;
                if (i % 2 != 0) {
                    assertEquals("val_" + i, storage.hget("conc:hash", field));
                } else {
                    assertNull(storage.hget("conc:hash", field));
                }
            }
        }
    }

    // =========================================================================
    // 5. AOF Persistence & Recovery Parity
    // =========================================================================

    @Test
    @DisplayName("AOF: HSET and HDEL mutation logging and recovery with binary data")
    public void testAofHSetAndHDel(@TempDir Path tempDir) throws IOException {
        File aofFile = tempDir.resolve("test_hash.aof").toFile();
        AofManager aofManager = new AofManager(aofFile, FsyncPolicy.ALWAYS, storage, registry);
        storage.setAofManager(aofManager);
        aofManager.start();

        // 1. Mutating HSET (creates fields)
        registry.dispatch("HSET aof:h f1 v1 f2 v2", storage);

        // 2. Mutating HSET (overwrites fields, returns 0)
        registry.dispatch("HSET aof:h f1 v1_overwritten", storage);

        // 3. Non-mutating HDEL (0 fields removed) - should NOT create AOF entry
        registry.dispatch("HDEL aof:h missing_field", storage);

        // 4. Mutating HDEL (removes f2)
        registry.dispatch("HDEL aof:h f2", storage);

        // 5. Failed HSET (wrong arity) - should NOT create AOF entry
        registry.dispatch("HSET aof:h bad_arity", storage);

        aofManager.close();

        // 6. Replay into a fresh storage engine
        StorageEngine replayStorage = new StorageEngine();
        CommandRegistry replayRegistry = new CommandRegistry();
        AofManager replayAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, replayStorage, replayRegistry);
        replayStorage.setAofManager(replayAof);
        int replayed = replayAof.recover();
        assertTrue(replayed >= 3, "Should have replayed at least 3 mutating operations");

        assertEquals("v1_overwritten", replayStorage.hget("aof:h", "f1"));
        assertNull(replayStorage.hget("aof:h", "f2"), "f2 was deleted before AOF close");
        assertFalse(replayStorage.hexists("aof:h", "f2"));
        replayAof.close();
    }
}
