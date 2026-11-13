package com.miniredis.aof;

import com.miniredis.commands.CommandRegistry;
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

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 6: AOF Crash Recovery & Fault Injection Tests")
public class AofCrashRecoveryTest {

    @TempDir
    Path tempDir;

    private File aofFile;
    private StorageEngine storage;
    private CommandRegistry registry;
    private AofManager aofManager;

    @BeforeEach
    public void setup() throws IOException {
        aofFile = tempDir.resolve("crash-recovery.aof").toFile();
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
    @DisplayName("Normal crash and restart: complete state restoration")
    public void testNormalCrashAndRestart() throws IOException {
        registry.dispatch(List.of("SET", "k1", "v1"), storage);
        registry.dispatch(List.of("LPUSH", "l1", "item1", "item2"), storage);
        registry.dispatch(List.of("SADD", "s1", "m1", "m2"), storage);
        registry.dispatch(List.of("HSET", "h1", "f1", "val1"), storage);

        aofManager.close();

        // Fresh database start
        StorageEngine freshStorage = new StorageEngine();
        CommandRegistry freshRegistry = new CommandRegistry();
        AofManager freshAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, freshStorage, freshRegistry);

        int replayed = freshAof.recover();
        assertEquals(4, replayed);

        assertEquals("v1", freshStorage.get("k1").getAsString());
        assertEquals(List.of("item2", "item1"), freshStorage.lrange("l1", 0, -1));
        assertEquals(2, freshStorage.scard("s1"));
        assertEquals("val1", freshStorage.hget("h1", "f1"));

        freshAof.close();
    }

    @Test
    @DisplayName("Incomplete final record: tail truncation handled without crashing")
    public void testIncompleteFinalRecord() throws IOException {
        registry.dispatch(List.of("SET", "safe_1", "val1"), storage);
        registry.dispatch(List.of("SET", "safe_2", "val2"), storage);
        aofManager.close();

        // Simulate half-written record at end of file
        try (FileOutputStream out = new FileOutputStream(aofFile, true)) {
            out.write("*3\r\n$3\r\nSET\r\n$6\r\npartia".getBytes(StandardCharsets.UTF_8));
        }

        StorageEngine freshStorage = new StorageEngine();
        CommandRegistry freshRegistry = new CommandRegistry();
        AofManager freshAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, freshStorage, freshRegistry);

        int replayed = freshAof.recover();
        assertEquals(2, replayed);
        assertEquals("val1", freshStorage.get("safe_1").getAsString());
        assertEquals("val2", freshStorage.get("safe_2").getAsString());
        assertNull(freshStorage.get("partia"));

        freshAof.close();
    }

    @Test
    @DisplayName("Corrupted final record: garbage at EOF skipped safely")
    public void testCorruptedFinalRecord() throws IOException {
        registry.dispatch(List.of("SET", "alpha", "omega"), storage);
        aofManager.close();

        // Append non-RESP garbage bytes
        try (FileOutputStream out = new FileOutputStream(aofFile, true)) {
            out.write("!!!GARBAGE_PAYLOAD_EOF\r\n".getBytes(StandardCharsets.UTF_8));
        }

        StorageEngine freshStorage = new StorageEngine();
        CommandRegistry freshRegistry = new CommandRegistry();
        AofManager freshAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, freshStorage, freshRegistry);

        int replayed = freshAof.recover();
        assertEquals(1, replayed);
        assertEquals("omega", freshStorage.get("alpha").getAsString());

        freshAof.close();
    }

    @Test
    @DisplayName("Interrupted rewrite temporary file cleanup")
    public void testInterruptedRewriteTempFileCleanup() throws IOException {
        // Create leftover .tmp files as if previous BGREWRITEAOF process crashed abruptly
        File staleTmp1 = tempDir.resolve("crash-recovery.aof.99999.tmp").toFile();
        File staleTmp2 = tempDir.resolve("crash-recovery.aof.88888.tmp").toFile();
        assertTrue(staleTmp1.createNewFile());
        assertTrue(staleTmp2.createNewFile());
        assertTrue(staleTmp1.exists());
        assertTrue(staleTmp2.exists());

        // Initializing AofManager should clean up stale rewrite temp files
        StorageEngine freshStorage = new StorageEngine();
        CommandRegistry freshRegistry = new CommandRegistry();
        AofManager freshAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, freshStorage, freshRegistry);
        freshAof.start();

        assertFalse(staleTmp1.exists(), "Stale tmp1 file should be cleaned up on startup");
        assertFalse(staleTmp2.exists(), "Stale tmp2 file should be cleaned up on startup");

        freshAof.close();
    }

    @Test
    @DisplayName("Expired keys during recovery: past absolute expiration is expired immediately")
    public void testExpiredKeysDuringRecovery() throws IOException {
        long now = System.currentTimeMillis();
        long expiredAt = now - 10_000; // 10 seconds ago
        long validAt = now + 60_000;   // 60 seconds from now

        // Manually write SET key val PXAT commands
        try (FileOutputStream out = new FileOutputStream(aofFile, false)) {
            // SET expired_key val PXAT expiredAt
            String cmd1 = "*5\r\n$3\r\nSET\r\n$11\r\nexpired_key\r\n$3\r\nval\r\n$4\r\nPXAT\r\n$" +
                    String.valueOf(expiredAt).length() + "\r\n" + expiredAt + "\r\n";
            // SET valid_key val PXAT validAt
            String cmd2 = "*5\r\n$3\r\nSET\r\n$9\r\nvalid_key\r\n$3\r\nval\r\n$4\r\nPXAT\r\n$" +
                    String.valueOf(validAt).length() + "\r\n" + validAt + "\r\n";
            out.write(cmd1.getBytes(StandardCharsets.UTF_8));
            out.write(cmd2.getBytes(StandardCharsets.UTF_8));
        }

        StorageEngine freshStorage = new StorageEngine();
        CommandRegistry freshRegistry = new CommandRegistry();
        AofManager freshAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, freshStorage, freshRegistry);

        int replayed = freshAof.recover();
        assertEquals(2, replayed);

        // expired_key should be detected as expired immediately upon access
        assertNull(freshStorage.get("expired_key"), "Expired key should not be accessible");
        assertEquals(-2, freshStorage.ttl("expired_key"));

        // valid_key should be active with positive TTL
        assertNotNull(freshStorage.get("valid_key"));
        assertTrue(freshStorage.ttl("valid_key") > 50);

        freshAof.close();
    }
}
