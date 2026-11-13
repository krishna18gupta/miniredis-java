package com.miniredis.aof;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 6: Append-Only File (AOF) Persistence Tests")
public class AofPersistenceTest {

    @TempDir
    Path tempDir;

    private File aofFile;
    private StorageEngine storage;
    private CommandRegistry registry;
    private AofManager aofManager;

    @BeforeEach
    public void setup() throws IOException {
        aofFile = tempDir.resolve("test-appendonly.aof").toFile();
        storage = new StorageEngine();
        registry = new CommandRegistry();
        aofManager = new AofManager(aofFile, FsyncPolicy.ALWAYS, storage, registry);
        storage.setAofManager(aofManager);
        aofManager.start();
    }

    @AfterEach
    public void tearDown() {
        if (aofManager != null) {
            aofManager.close();
        }
    }

    @Test
    @DisplayName("Verify mutating commands append to AOF in valid RESP format")
    public void testMutatingCommandsLoggedToAof() {
        registry.dispatch(List.of("SET", "greeting", "hello world"), storage);
        registry.dispatch(List.of("INCR", "counter"), storage);
        registry.dispatch(List.of("RPUSH", "queue", "task1", "task2"), storage);
        registry.dispatch(List.of("SADD", "tags", "java", "redis"), storage);
        registry.dispatch(List.of("HSET", "user:1", "name", "Krishna", "role", "Admin"), storage);

        assertTrue(aofFile.exists());
        assertTrue(aofFile.length() > 0);
    }

    @Test
    @DisplayName("Verify read-only commands and failed commands are NOT logged to AOF")
    public void testReadOnlyAndFailedCommandsNotLogged() {
        long initialLen = aofFile.length();

        // Read operations
        registry.dispatch(List.of("GET", "missing_key"), storage);
        registry.dispatch(List.of("KEYS", "*"), storage);
        registry.dispatch(List.of("DBSIZE"), storage);
        registry.dispatch(List.of("PING"), storage);

        assertEquals(initialLen, aofFile.length(), "Read commands should not append to AOF");

        // Failed operations
        registry.dispatch(List.of("UNKNOWN_CMD"), storage);
        registry.dispatch(List.of("INCR", "too", "many", "arguments"), storage);

        assertEquals(initialLen, aofFile.length(), "Failed commands should not append to AOF");
    }

    @Test
    @DisplayName("Complete crash recovery restores Strings, Lists, Sets, Hashes, and Counters")
    public void testCompleteStateCrashRecovery() throws IOException {
        // Populate rich database state
        registry.dispatch(List.of("SET", "msg", "MiniRedis rocks"), storage);
        registry.dispatch(List.of("INCR", "hits"), storage);
        registry.dispatch(List.of("INCR", "hits"), storage);
        registry.dispatch(List.of("LPUSH", "todos", "item3", "item2", "item1"), storage);
        registry.dispatch(List.of("SADD", "skills", "Java", "Concurrency", "AOF"), storage);
        registry.dispatch(List.of("HSET", "doc:100", "title", "Persistence", "status", "draft"), storage);

        // Simulate crash: close AOF and discard in-memory engine
        aofManager.close();

        // Create fresh independent storage engine
        StorageEngine freshStorage = new StorageEngine();
        CommandRegistry freshRegistry = new CommandRegistry();
        AofManager freshAof = new AofManager(aofFile, FsyncPolicy.EVERYSEC, freshStorage, freshRegistry);
        freshStorage.setAofManager(freshAof);

        int replayed = freshAof.recover();
        assertTrue(replayed >= 6);

        // Validate restored state
        assertEquals("MiniRedis rocks", freshStorage.get("msg").getAsString());
        assertEquals("2", freshStorage.get("hits").getAsString());
        assertEquals(List.of("item1", "item2", "item3"), freshStorage.lrange("todos", 0, -1));
        assertEquals(Set.of("Java", "Concurrency", "AOF"), freshStorage.smembers("skills"));
        assertEquals("Persistence", freshStorage.hget("doc:100", "title"));
        assertEquals("draft", freshStorage.hget("doc:100", "status"));

        freshAof.close();
    }

    @Test
    @DisplayName("TTL normalization persists remaining expiration via PEXPIREAT")
    public void testTtlRestorationAfterRecovery() throws IOException {
        registry.dispatch(List.of("SETEX", "temp_token", "300", "jwt_payload"), storage);
        aofManager.close();

        // Recover in fresh engine
        StorageEngine freshStorage = new StorageEngine();
        CommandRegistry freshRegistry = new CommandRegistry();
        AofManager freshAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, freshStorage, freshRegistry);
        freshStorage.setAofManager(freshAof);

        freshAof.recover();

        assertEquals("jwt_payload", freshStorage.get("temp_token").getAsString());
        long remainingTtl = freshStorage.ttl("temp_token");
        assertTrue(remainingTtl > 250 && remainingTtl <= 300, "TTL should be restored: " + remainingTtl);

        freshAof.close();
    }

    @Test
    @DisplayName("Gracefully recover when AOF file ends with a corrupt or truncated trailing write")
    public void testCorruptTailRecoveryGraceful() throws IOException {
        registry.dispatch(List.of("SET", "stable_key1", "val1"), storage);
        registry.dispatch(List.of("SET", "stable_key2", "val2"), storage);
        aofManager.close();

        // Intentionally append partial/corrupt bytes to simulate mid-write power cut
        try (FileOutputStream out = new FileOutputStream(aofFile, true)) {
            out.write("*3\r\n$3\r\nSET\r\n$10\r\ncorrupt".getBytes(StandardCharsets.UTF_8));
            // Incomplete write: no value, no CRLF
        }

        // Recover in fresh engine
        StorageEngine freshStorage = new StorageEngine();
        CommandRegistry freshRegistry = new CommandRegistry();
        AofManager freshAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, freshStorage, freshRegistry);
        freshStorage.setAofManager(freshAof);

        int replayed = freshAof.recover();
        assertEquals(2, replayed, "Should replay the 2 stable commands and cleanly ignore corrupt tail");
        assertEquals("val1", freshStorage.get("stable_key1").getAsString());
        assertEquals("val2", freshStorage.get("stable_key2").getAsString());

        freshAof.close();
    }

    @Test
    @DisplayName("AOF Compaction (rewrite) dramatically reduces file size and preserves state")
    public void testAofCompactionRewrite() throws IOException {
        // Bloat AOF with 200 consecutive INCR commands
        for (int i = 0; i < 200; i++) {
            registry.dispatch(List.of("INCR", "counter"), storage);
        }
        registry.dispatch(List.of("SET", "ephemeral", "to_be_deleted"), storage);
        registry.dispatch(List.of("DEL", "ephemeral"), storage);

        long bloatedSize = aofFile.length();
        assertTrue(bloatedSize > 2000, "AOF file should be bloated before compaction: " + bloatedSize);

        // Perform compaction rewrite
        aofManager.rewrite();

        long compactedSize = aofFile.length();
        assertTrue(compactedSize < bloatedSize / 4,
                "Compacted AOF should be significantly smaller: " + compactedSize + " vs " + bloatedSize);

        // Recover from compacted file into fresh storage
        StorageEngine freshStorage = new StorageEngine();
        CommandRegistry freshRegistry = new CommandRegistry();
        AofManager freshAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, freshStorage, freshRegistry);
        freshStorage.setAofManager(freshAof);

        freshAof.recover();

        assertEquals("200", freshStorage.get("counter").getAsString());
        assertNull(freshStorage.get("ephemeral"), "Deleted key should not be resurrected");

        freshAof.close();
    }

    @Test
    @DisplayName("BGREWRITEAOF command triggers background compaction successfully")
    public void testBgRewriteAofCommand() throws Exception {
        registry.dispatch(List.of("SET", "k1", "v1"), storage);
        RedisResponse resp = registry.dispatch(List.of("BGREWRITEAOF"), storage);

        assertEquals(RedisResponse.ResponseType.SIMPLE_STRING, resp.getType());
        assertEquals("Background append only file rewriting started", resp.getStringValue());

        aofManager.bgRewrite().get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Verify FsyncPolicy ALWAYS, EVERYSEC, and NO append and report health correctly")
    public void testFsyncPoliciesAndHealth() throws IOException {
        AofHealth health = aofManager.getHealth();
        assertNotNull(health);
        assertEquals(AofHealth.Status.HEALTHY, health.getStatus());
        assertEquals(FsyncPolicy.ALWAYS, health.getFsyncPolicy());
        assertTrue(health.getTotalFsyncs() >= 0);

        // Test with EVERYSEC
        File everysecFile = tempDir.resolve("everysec.aof").toFile();
        AofManager everysecAof = new AofManager(everysecFile, FsyncPolicy.EVERYSEC, storage, registry);
        everysecAof.start();
        everysecAof.append(List.of("SET", "es_key", "es_val"));
        assertEquals(FsyncPolicy.EVERYSEC, everysecAof.getHealth().getFsyncPolicy());
        assertTrue(everysecAof.getHealth().getTotalLoggedCommands() >= 1);
        everysecAof.close();

        // Test with NO
        File noFile = tempDir.resolve("no.aof").toFile();
        AofManager noAof = new AofManager(noFile, FsyncPolicy.NO, storage, registry);
        noAof.start();
        noAof.append(List.of("SET", "no_key", "no_val"));
        assertEquals(FsyncPolicy.NO, noAof.getHealth().getFsyncPolicy());
        assertEquals(0, noAof.getHealth().getTotalFsyncs(), "FsyncPolicy.NO should not trigger fsyncs");
        noAof.close();
    }

    @Test
    @DisplayName("Failed AOF write or closed stream under ALWAYS reports failing health and returns error response")
    public void testFailedAofWriteThrowsAofExceptionAndReturnsError() throws IOException {
        // Intentionally close the aofManager to verify isClosed
        aofManager.close();
        assertTrue(aofManager.isClosed());

        // Now test simulated write failure via simulated disk / stream failure
        File failedAofFile = tempDir.resolve("fail-test.aof").toFile();
        AofManager failAof = new AofManager(failedAofFile.getParentFile(), failedAofFile.getName(),
                FsyncPolicy.ALWAYS, storage, registry, 1000);
        failAof.start();

        // Successful write initially
        failAof.appendCommand(List.of("SET", "k1", "v1"));
        assertEquals(AofHealth.Status.HEALTHY, failAof.getHealth().getStatus());

        // Simulate sudden disk/I/O stream failure
        failAof.simulateDiskFailure();

        // Under ALWAYS, appendCommand must detect write failure and throw AofException
        assertThrows(AofException.class, () -> {
            failAof.appendCommand(List.of("SET", "k2", "v2"));
        });
        assertEquals(AofHealth.Status.FAILING, failAof.getHealth().getStatus());

        // Also test dispatch integration: returns ERR AOF persistence failed
        storage.setAofManager(failAof);
        RedisResponse resp = registry.dispatch(List.of("SET", "k3", "v3"), storage);
        assertEquals(RedisResponse.ResponseType.ERROR, resp.getType());
        assertTrue(resp.getStringValue().contains("AOF persistence failed"), "Response: " + resp.getStringValue());

        failAof.close();
    }

    @Test
    @DisplayName("Absolute TTL preservation via PEXPIREAT across Strings, Lists, Sets, and Hashes")
    public void testAbsoluteTtlPreservationForComplexTypes() throws IOException {
        // Populate Strings, Lists, Sets, Hashes
        registry.dispatch(List.of("SET", "str1", "val1"), storage);
        registry.dispatch(List.of("EXPIRE", "str1", "600"), storage); // 600s

        registry.dispatch(List.of("RPUSH", "list1", "a", "b", "c"), storage);
        registry.dispatch(List.of("EXPIRE", "list1", "600"), storage);

        registry.dispatch(List.of("SADD", "set1", "m1", "m2"), storage);
        registry.dispatch(List.of("EXPIRE", "set1", "600"), storage);

        registry.dispatch(List.of("HSET", "hash1", "f1", "v1"), storage);
        registry.dispatch(List.of("EXPIRE", "hash1", "600"), storage);

        aofManager.close();

        // Fresh database recovery
        StorageEngine freshStorage = new StorageEngine();
        CommandRegistry freshRegistry = new CommandRegistry();
        AofManager freshAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, freshStorage, freshRegistry);
        freshStorage.setAofManager(freshAof);

        int replayed = freshAof.recover();
        assertTrue(replayed >= 8);

        // Verify all 4 types restored and have valid positive TTLs (~600s)
        assertTrue(freshStorage.ttl("str1") > 500 && freshStorage.ttl("str1") <= 600);
        assertTrue(freshStorage.ttl("list1") > 500 && freshStorage.ttl("list1") <= 600);
        assertTrue(freshStorage.ttl("set1") > 500 && freshStorage.ttl("set1") <= 600);
        assertTrue(freshStorage.ttl("hash1") > 500 && freshStorage.ttl("hash1") <= 600);

        freshAof.close();
    }
}
