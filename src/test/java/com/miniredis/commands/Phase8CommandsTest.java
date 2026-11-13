package com.miniredis.commands;

import com.miniredis.aof.AofManager;
import com.miniredis.aof.FsyncPolicy;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 8: New Commands, Lifecycle & Live Telemetry Tests")
class Phase8CommandsTest {

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

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
        storage = new StorageEngine(clock);
        registry = new CommandRegistry();
    }

    @AfterEach
    void tearDown() {
        if (storage.getAofManager() != null && !storage.getAofManager().isClosed()) {
            storage.getAofManager().close();
        }
    }

    @Test
    @DisplayName("ECHO returns bulk string and enforces arity")
    void testEcho() {
        RedisResponse r1 = registry.dispatch(List.of("ECHO", "Hello MiniRedis!"), storage);
        assertEquals(RedisResponse.ResponseType.BULK_STRING, r1.getType());
        assertEquals("Hello MiniRedis!", r1.getStringValue());

        RedisResponse empty = registry.dispatch(List.of("ECHO", ""), storage);
        assertEquals(RedisResponse.ResponseType.BULK_STRING, empty.getType());
        assertEquals("", empty.getStringValue());

        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch(List.of("ECHO"), storage).getType());
        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch(List.of("ECHO", "a", "b"), storage).getType());
    }

    @Test
    @DisplayName("TYPE reports string, list, set, hash, and none (including after expiry)")
    void testType() {
        assertEquals("none", registry.dispatch("TYPE missing", storage).getStringValue());

        registry.dispatch("SET sKey hello", storage);
        registry.dispatch("LPUSH lKey item", storage);
        registry.dispatch("SADD setKey elem", storage);
        registry.dispatch("HSET hKey field val", storage);

        RedisResponse sType = registry.dispatch("TYPE sKey", storage);
        assertEquals(RedisResponse.ResponseType.SIMPLE_STRING, sType.getType());
        assertEquals("string", sType.getStringValue());
        assertEquals("list", registry.dispatch("TYPE lKey", storage).getStringValue());
        assertEquals("set", registry.dispatch("TYPE setKey", storage).getStringValue());
        assertEquals("hash", registry.dispatch("TYPE hKey", storage).getStringValue());

        registry.dispatch("EXPIRE sKey 5", storage);
        assertEquals("string", registry.dispatch("TYPE sKey", storage).getStringValue());
        clock.advanceSeconds(6);
        assertEquals("none", registry.dispatch("TYPE sKey", storage).getStringValue());

        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch("TYPE", storage).getType());
    }

    @Test
    @DisplayName("RENAME preserves value and TTL, overwrites destination, handles missing/self rename, and replays via AOF")
    void testRename(@TempDir Path tempDir) throws IOException {
        File aofFile = tempDir.resolve("rename.aof").toFile();
        AofManager aof = new AofManager(aofFile, FsyncPolicy.ALWAYS, storage, registry);
        storage.setAofManager(aof);
        aof.start();

        // Missing source fails
        RedisResponse err = registry.dispatch("RENAME ghost target", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, err.getType());
        assertTrue(err.getStringValue().contains("no such key"));

        // Preserves value + remaining TTL across rename & overwrites old target
        registry.dispatch("SET oldTarget legacy", storage);
        registry.dispatch("SETEX session 30 secret_tok", storage);
        clock.advanceSeconds(10); // 20s remaining

        RedisResponse ok = registry.dispatch("RENAME session oldTarget", storage);
        assertEquals("OK", ok.getStringValue());
        assertNull(storage.get("session"));
        assertEquals("secret_tok", storage.get("oldTarget").getAsString());
        assertEquals(20L, registry.dispatch("TTL oldTarget", storage).getIntValue());

        // Self-rename on existing key preserves key and TTL
        assertEquals("OK", registry.dispatch("RENAME oldTarget oldTarget", storage).getStringValue());
        assertEquals("secret_tok", storage.get("oldTarget").getAsString());
        assertEquals(20L, registry.dispatch("TTL oldTarget", storage).getIntValue());

        // Self-rename on missing key errors
        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch("RENAME missing missing", storage).getType());

        // Non-string types can also be renamed
        registry.dispatch("LPUSH myList a b", storage);
        assertEquals("OK", registry.dispatch("RENAME myList movedList", storage).getStringValue());
        assertEquals(List.of("b", "a"), storage.lrange("movedList", 0, -1));

        aof.close();

        // Replay AOF into fresh storage
        StorageEngine recoveredStorage = new StorageEngine(clock);
        CommandRegistry recoveredRegistry = new CommandRegistry();
        try (AofManager recAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, recoveredStorage, recoveredRegistry)) {
            recAof.recover();
        }
        assertNull(recoveredStorage.get("session"));
        assertEquals("secret_tok", recoveredStorage.get("oldTarget").getAsString());
        assertEquals(20L, recoveredStorage.ttl("oldTarget"));
        assertEquals(List.of("b", "a"), recoveredStorage.lrange("movedList", 0, -1));
    }

    @Test
    @DisplayName("RANDOMKEY returns nil when empty or all expired, and active key otherwise")
    void testRandomKey() {
        assertEquals(RedisResponse.ResponseType.NIL, registry.dispatch("RANDOMKEY", storage).getType());

        registry.dispatch("SETEX temp 5 v1", storage);
        clock.advanceSeconds(6);
        assertEquals(RedisResponse.ResponseType.NIL, registry.dispatch("RANDOMKEY", storage).getType());

        registry.dispatch("SET k1 v1", storage);
        registry.dispatch("SET k2 v2", storage);
        registry.dispatch("SET k3 v3", storage);

        Set<String> valid = Set.of("k1", "k2", "k3");
        for (int i = 0; i < 10; i++) {
            RedisResponse r = registry.dispatch("RANDOMKEY", storage);
            assertEquals(RedisResponse.ResponseType.BULK_STRING, r.getType());
            assertTrue(valid.contains(r.getStringValue()));
        }

        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch("RANDOMKEY extra", storage).getType());
    }

    @Test
    @DisplayName("INCRBY and DECRBY handle initialization, negative deltas, overflow, wrongtype, and AOF replay")
    void testIncrByAndDecrBy(@TempDir Path tempDir) throws IOException {
        File aofFile = tempDir.resolve("incrby.aof").toFile();
        AofManager aof = new AofManager(aofFile, FsyncPolicy.ALWAYS, storage, registry);
        storage.setAofManager(aof);
        aof.start();

        assertEquals(10L, registry.dispatch("INCRBY counter 10", storage).getIntValue());
        assertEquals(7L, registry.dispatch("INCRBY counter -3", storage).getIntValue());
        assertEquals(2L, registry.dispatch("DECRBY counter 5", storage).getIntValue());
        assertEquals(6L, registry.dispatch("DECRBY counter -4", storage).getIntValue());

        // Overflow checks including 0 - Long.MIN_VALUE
        assertEquals(RedisResponse.ResponseType.ERROR,
                registry.dispatch("DECRBY newMin " + Long.MIN_VALUE, storage).getType());
        registry.dispatch("SET maxKey " + Long.MAX_VALUE, storage);
        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch("INCRBY maxKey 1", storage).getType());
        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch("INCRBY badDelta nope", storage).getType());

        // WRONGTYPE
        registry.dispatch("LPUSH myList a", storage);
        RedisResponse wt = registry.dispatch("INCRBY myList 2", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, wt.getType());
        assertTrue(wt.getStringValue().contains("WRONGTYPE"));

        aof.close();

        StorageEngine recovered = new StorageEngine(clock);
        CommandRegistry recReg = new CommandRegistry();
        try (AofManager recAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, recovered, recReg)) {
            recAof.recover();
        }
        assertEquals("6", recovered.get("counter").getAsString());
    }

    @Test
    @DisplayName("SAVE compacts snapshot to AOF and fails gracefully when AOF is disabled")
    void testSaveCommand(@TempDir Path tempDir) throws IOException {
        // Without AOF enabled
        RedisResponse disabledErr = registry.dispatch("SAVE", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, disabledErr.getType());

        File aofFile = tempDir.resolve("save.aof").toFile();
        AofManager aof = new AofManager(aofFile, FsyncPolicy.ALWAYS, storage, registry);
        storage.setAofManager(aof);
        aof.start();

        for (int i = 0; i < 40; i++) {
            registry.dispatch("SET hotKey v" + i, storage);
        }
        registry.dispatch("SETEX expKey 100 expVal", storage);
        long uncompactedSize = aofFile.length();

        RedisResponse saveResp = registry.dispatch("SAVE", storage);
        assertEquals("OK", saveResp.getStringValue());
        assertTrue(aofFile.length() < uncompactedSize, "SAVE should compact redundant history");

        aof.close();

        StorageEngine fresh = new StorageEngine(clock);
        try (AofManager recAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, fresh, new CommandRegistry())) {
            recAof.recover();
        }
        assertEquals("v39", fresh.get("hotKey").getAsString());
        assertEquals("expVal", fresh.get("expKey").getAsString());
        assertEquals(100L, fresh.ttl("expKey"));
    }

    @Test
    @DisplayName("SHUTDOWN coordinates SAVE, NOSAVE, default persistence, and syntax validation")
    void testShutdownCommand(@TempDir Path tempDir) throws IOException {
        File aofFile = tempDir.resolve("shutdown.aof").toFile();
        AofManager aof = new AofManager(aofFile, FsyncPolicy.EVERYSEC, storage, registry);
        storage.setAofManager(aof);
        aof.start();

        AtomicBoolean invoked = new AtomicBoolean(false);
        AtomicBoolean savedFlag = new AtomicBoolean(false);
        AtomicBoolean skippedFlag = new AtomicBoolean(false);
        storage.getServerMetrics().setShutdownCoordinator((saveSnapshot, skipPersistence) -> {
            invoked.set(true);
            savedFlag.set(saveSnapshot);
            skippedFlag.set(skipPersistence);
        });

        // Invalid modifier rejected without calling coordinator
        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch("SHUTDOWN INVALID", storage).getType());
        assertFalse(invoked.get());

        // SHUTDOWN SAVE compacts snapshot and passes flags
        for (int i = 0; i < 15; i++) {
            registry.dispatch("SET k v" + i, storage);
        }
        assertEquals("OK", registry.dispatch("SHUTDOWN SAVE", storage).getStringValue());
        assertTrue(invoked.get());
        assertTrue(savedFlag.get());
        assertFalse(skippedFlag.get());

        // SHUTDOWN NOSAVE passes skipPersistence=true
        invoked.set(false);
        assertEquals("OK", registry.dispatch("SHUTDOWN NOSAVE", storage).getStringValue());
        assertTrue(invoked.get());
        assertFalse(savedFlag.get());
        assertTrue(skippedFlag.get());
    }

    @Test
    @DisplayName("INFO exposes live telemetry counters and supports section filtering")
    void testInfoSectionsAndLiveTelemetry() {
        storage.getServerMetrics().setListeningPort(16379);
        storage.getServerMetrics().incrementConnectionsReceived();
        storage.getServerMetrics().incrementConnectionsReceived();

        registry.dispatch("SET alpha 12345", storage);
        registry.dispatch("SETEX beta 100 tempval", storage);
        registry.dispatch("GET alpha", storage);      // hit
        registry.dispatch("GET missingKey", storage); // miss

        clock.advanceSeconds(42);

        String allInfo = registry.dispatch("INFO", storage).getStringValue();
        assertTrue(allInfo.contains("# Server"));
        assertTrue(allInfo.contains("# Clients"));
        assertTrue(allInfo.contains("# Memory"));
        assertTrue(allInfo.contains("# Persistence"));
        assertTrue(allInfo.contains("# Stats"));
        assertTrue(allInfo.contains("# Keyspace"));

        String serverOnly = registry.dispatch("INFO server", storage).getStringValue();
        assertTrue(serverOnly.contains("tcp_port:16379"));
        assertTrue(serverOnly.contains("uptime_in_seconds:42"));
        assertFalse(serverOnly.contains("# Stats"));

        String statsOnly = registry.dispatch("INFO stats", storage).getStringValue();
        assertTrue(statsOnly.contains("total_connections_received:2"));
        assertTrue(statsOnly.contains("keyspace_hits:1"));
        assertTrue(statsOnly.contains("keyspace_misses:1"));
        assertFalse(statsOnly.contains("# Keyspace"));

        String memoryOnly = registry.dispatch("INFO memory", storage).getStringValue();
        assertTrue(memoryOnly.contains("used_memory:"));
        assertFalse(memoryOnly.contains("used_memory:0\r\n"));
        assertFalse(memoryOnly.contains("# Server"));

        String keyspaceOnly = registry.dispatch("INFO keyspace", storage).getStringValue();
        assertTrue(keyspaceOnly.contains("db0:keys=2,expires=1,avg_ttl=58000"));
        assertFalse(keyspaceOnly.contains("# Clients"));

        String unknownSection = registry.dispatch("INFO nonexistent", storage).getStringValue();
        assertEquals("", unknownSection);

        assertEquals(RedisResponse.ResponseType.ERROR, registry.dispatch("INFO a b", storage).getType());

        // Command inspection reflects newly registered Phase 8 commands
        assertTrue(registry.dispatch("COMMAND COUNT", storage).getIntValue() >= 57);
    }
}
