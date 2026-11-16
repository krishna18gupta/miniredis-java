package com.miniredis.commands.strings;

import com.miniredis.aof.AofManager;
import com.miniredis.aof.FsyncPolicy;
import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
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
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 18 Stage B: SET Command Parity Test Suite (Redis 7.2.17)")
public class SetCommandParityTest {

    private StorageEngine storage;
    private CommandRegistry registry;
    private Clock fixedClock;
    private long baseTimeMillis;

    @BeforeEach
    public void setUp() {
        baseTimeMillis = 1700000000000L; // 2023-11-14T22:13:20Z
        fixedClock = Clock.fixed(Instant.ofEpochMilli(baseTimeMillis), ZoneOffset.UTC);
        storage = new StorageEngine(fixedClock);
        registry = new CommandRegistry();
    }

    // =========================================================================
    // 1. Basic SET & TTL Clearing
    // =========================================================================

    @Test
    @DisplayName("SET key value without options sets value and returns OK")
    public void testBasicSet() {
        RedisResponse resp = registry.dispatch(List.of("SET", "mykey", "myval"), storage);
        assertEquals(RedisResponse.ResponseType.SIMPLE_STRING, resp.getType());
        assertEquals("OK", resp.getStringValue());
        assertEquals("+OK\r\n", resp.toRespWireFormat());

        RedisResponse getResp = registry.dispatch(List.of("GET", "mykey"), storage);
        assertEquals("myval", getResp.getStringValue());
        assertEquals(-1L, storage.ttl("mykey"));
    }

    @Test
    @DisplayName("SET key value without options clears any prior TTL on existing key")
    public void testSetClearsPriorTtl() {
        registry.dispatch(List.of("SET", "expkey", "initial", "EX", "300"), storage);
        assertTrue(storage.ttl("expkey") > 0);

        // Plain SET clears TTL
        RedisResponse resp = registry.dispatch(List.of("SET", "expkey", "overwritten"), storage);
        assertEquals("OK", resp.getStringValue());
        assertEquals("overwritten", registry.dispatch(List.of("GET", "expkey"), storage).getStringValue());
        assertEquals(-1L, storage.ttl("expkey"));
    }

    // =========================================================================
    // 2. NX (Only set if not exists)
    // =========================================================================

    @Test
    @DisplayName("SET NX on non-existent key sets value and returns OK")
    public void testSetNxNonExistent() {
        RedisResponse resp = registry.dispatch(List.of("SET", "nxkey", "first", "NX"), storage);
        assertEquals("OK", resp.getStringValue());
        assertEquals("first", registry.dispatch(List.of("GET", "nxkey"), storage).getStringValue());
    }

    @Test
    @DisplayName("SET NX on existing key is a no-op, returns nil, preserves value and TTL")
    public void testSetNxExisting() {
        registry.dispatch(List.of("SET", "nxkey", "first", "EX", "600"), storage);
        long originalTtl = storage.ttl("nxkey");

        RedisResponse resp = registry.dispatch(List.of("SET", "nxkey", "second", "NX"), storage);
        assertEquals(RedisResponse.ResponseType.NIL, resp.getType());
        assertEquals("$-1\r\n", resp.toRespWireFormat());
        assertFalse(resp.isMutated());

        assertEquals("first", registry.dispatch(List.of("GET", "nxkey"), storage).getStringValue());
        assertEquals(originalTtl, storage.ttl("nxkey"));
    }

    // =========================================================================
    // 3. XX (Only set if exists)
    // =========================================================================

    @Test
    @DisplayName("SET XX on existing key updates value and returns OK")
    public void testSetXxExisting() {
        registry.dispatch(List.of("SET", "xxkey", "original"), storage);

        RedisResponse resp = registry.dispatch(List.of("SET", "xxkey", "updated", "XX"), storage);
        assertEquals("OK", resp.getStringValue());
        assertEquals("updated", registry.dispatch(List.of("GET", "xxkey"), storage).getStringValue());
    }

    @Test
    @DisplayName("SET XX on non-existent key is a no-op, returns nil, does not create key")
    public void testSetXxNonExistent() {
        RedisResponse resp = registry.dispatch(List.of("SET", "missing_xx", "val", "XX"), storage);
        assertEquals(RedisResponse.ResponseType.NIL, resp.getType());
        assertEquals("$-1\r\n", resp.toRespWireFormat());
        assertFalse(resp.isMutated());

        assertNull(registry.dispatch(List.of("GET", "missing_xx"), storage).getStringValue());
    }

    // =========================================================================
    // 4. GET Option
    // =========================================================================

    @Test
    @DisplayName("SET GET on non-existent key sets value and returns nil")
    public void testSetGetNonExistent() {
        RedisResponse resp = registry.dispatch(List.of("SET", "getkey", "newval", "GET"), storage);
        assertEquals(RedisResponse.ResponseType.NIL, resp.getType());
        assertEquals("$-1\r\n", resp.toRespWireFormat());
        assertTrue(resp.isMutated());

        assertEquals("newval", registry.dispatch(List.of("GET", "getkey"), storage).getStringValue());
    }

    @Test
    @DisplayName("SET GET on existing string key sets value and returns previous value")
    public void testSetGetExistingString() {
        registry.dispatch(List.of("SET", "getkey", "oldval"), storage);

        RedisResponse resp = registry.dispatch(List.of("SET", "getkey", "newval", "GET"), storage);
        assertEquals(RedisResponse.ResponseType.BULK_STRING, resp.getType());
        assertEquals("oldval", resp.getStringValue());
        assertEquals("$6\r\noldval\r\n", resp.toRespWireFormat());
        assertTrue(resp.isMutated());

        assertEquals("newval", registry.dispatch(List.of("GET", "getkey"), storage).getStringValue());
    }

    @Test
    @DisplayName("SET GET on existing non-string key returns WRONGTYPE and does not mutate")
    public void testSetGetExistingNonStringWrongType() {
        registry.dispatch(List.of("LPUSH", "mylist", "item1"), storage);

        RedisResponse resp = registry.dispatch(List.of("SET", "mylist", "strval", "GET"), storage);
        assertEquals(RedisResponse.ResponseType.ERROR, resp.getType());
        assertTrue(resp.getStringValue().contains("WRONGTYPE"));
        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", resp.toRespWireFormat());

        // Verify list remains intact
        RedisResponse typeResp = registry.dispatch(List.of("TYPE", "mylist"), storage);
        assertEquals("list", typeResp.getStringValue());
    }

    // =========================================================================
    // 5. Combinations: NX + GET, XX + GET
    // =========================================================================

    @Test
    @DisplayName("SET NX GET on non-existent key sets value and returns nil")
    public void testSetNxGetNonExistent() {
        RedisResponse resp = registry.dispatch(List.of("SET", "nxget", "hello", "NX", "GET"), storage);
        assertEquals(RedisResponse.ResponseType.NIL, resp.getType());
        assertEquals("$-1\r\n", resp.toRespWireFormat());
        assertTrue(resp.isMutated());

        assertEquals("hello", registry.dispatch(List.of("GET", "nxget"), storage).getStringValue());
    }

    @Test
    @DisplayName("SET NX GET on existing key returns old value without modifying key or TTL")
    public void testSetNxGetExisting() {
        registry.dispatch(List.of("SET", "nxget", "original", "EX", "400"), storage);
        long originalTtl = storage.ttl("nxget");

        RedisResponse resp = registry.dispatch(List.of("SET", "nxget", "attempt2", "NX", "GET"), storage);
        assertEquals(RedisResponse.ResponseType.BULK_STRING, resp.getType());
        assertEquals("original", resp.getStringValue());
        assertFalse(resp.isMutated());

        assertEquals("original", registry.dispatch(List.of("GET", "nxget"), storage).getStringValue());
        assertEquals(originalTtl, storage.ttl("nxget"));
    }

    @Test
    @DisplayName("SET XX GET on existing key updates value and returns old value")
    public void testSetXxGetExisting() {
        registry.dispatch(List.of("SET", "xxget", "v1"), storage);

        RedisResponse resp = registry.dispatch(List.of("SET", "xxget", "v2", "XX", "GET"), storage);
        assertEquals(RedisResponse.ResponseType.BULK_STRING, resp.getType());
        assertEquals("v1", resp.getStringValue());
        assertTrue(resp.isMutated());

        assertEquals("v2", registry.dispatch(List.of("GET", "xxget"), storage).getStringValue());
    }

    @Test
    @DisplayName("SET XX GET on non-existent key returns nil without creating key")
    public void testSetXxGetNonExistent() {
        RedisResponse resp = registry.dispatch(List.of("SET", "missing_xxget", "v1", "XX", "GET"), storage);
        assertEquals(RedisResponse.ResponseType.NIL, resp.getType());
        assertEquals("$-1\r\n", resp.toRespWireFormat());
        assertFalse(resp.isMutated());

        assertNull(registry.dispatch(List.of("GET", "missing_xxget"), storage).getStringValue());
    }

    // =========================================================================
    // 6. Expirations: EX, PX, EXAT, PXAT
    // =========================================================================

    @Test
    @DisplayName("SET EX sets relative expiration in seconds")
    public void testSetEx() {
        RedisResponse resp = registry.dispatch(List.of("SET", "kex", "val", "EX", "60"), storage);
        assertEquals("OK", resp.getStringValue());
        assertEquals(60L, storage.ttl("kex"));
        assertEquals(60000L, storage.pttl("kex"));
    }

    @Test
    @DisplayName("SET PX sets relative expiration in milliseconds")
    public void testSetPx() {
        RedisResponse resp = registry.dispatch(List.of("SET", "kpx", "val", "PX", "45000"), storage);
        assertEquals("OK", resp.getStringValue());
        assertEquals(45L, storage.ttl("kpx"));
        assertEquals(45000L, storage.pttl("kpx"));
    }

    @Test
    @DisplayName("SET EXAT sets absolute expiration in unix timestamp seconds")
    public void testSetExatFuture() {
        long targetSec = (baseTimeMillis / 1000L) + 120L;
        RedisResponse resp = registry.dispatch(List.of("SET", "kexat", "val", "EXAT", String.valueOf(targetSec)), storage);
        assertEquals("OK", resp.getStringValue());
        assertEquals(120L, storage.ttl("kexat"));
    }

    @Test
    @DisplayName("SET PXAT sets absolute expiration in unix timestamp milliseconds")
    public void testSetPxatFuture() {
        long targetMs = baseTimeMillis + 75000L;
        RedisResponse resp = registry.dispatch(List.of("SET", "kpxat", "val", "PXAT", String.valueOf(targetMs)), storage);
        assertEquals("OK", resp.getStringValue());
        assertEquals(75000L, storage.pttl("kpxat"));
    }

    @Test
    @DisplayName("SET EXAT with past timestamp deletes key if existed and returns OK")
    public void testSetExatPastExisting() {
        registry.dispatch(List.of("SET", "kpast", "initial"), storage);
        assertTrue(storage.exists("kpast"));

        long pastSec = (baseTimeMillis / 1000L) - 100L;
        RedisResponse resp = registry.dispatch(List.of("SET", "kpast", "newval", "EXAT", String.valueOf(pastSec)), storage);
        assertEquals("OK", resp.getStringValue());
        assertTrue(resp.isMutated());
        assertFalse(storage.exists("kpast"));
    }

    @Test
    @DisplayName("SET EXAT with past timestamp on non-existent key returns OK without creating key")
    public void testSetExatPastNonExistent() {
        long pastSec = (baseTimeMillis / 1000L) - 100L;
        RedisResponse resp = registry.dispatch(List.of("SET", "kpast2", "newval", "EXAT", String.valueOf(pastSec)), storage);
        assertEquals("OK", resp.getStringValue());
        assertFalse(resp.isMutated());
        assertFalse(storage.exists("kpast2"));
    }

    @Test
    @DisplayName("SET PXAT with past timestamp and GET returns old value and deletes key")
    public void testSetPxatPastWithGet() {
        registry.dispatch(List.of("SET", "kpxpast", "prev"), storage);

        long pastMs = baseTimeMillis - 5000L;
        RedisResponse resp = registry.dispatch(List.of("SET", "kpxpast", "newval", "PXAT", String.valueOf(pastMs), "GET"), storage);
        assertEquals("prev", resp.getStringValue());
        assertTrue(resp.isMutated());
        assertFalse(storage.exists("kpxpast"));
    }

    // =========================================================================
    // 7. KEEPTTL Option
    // =========================================================================

    @Test
    @DisplayName("SET KEEPTTL retains prior TTL on key that had an active TTL")
    public void testSetKeepttlExistingWithTtl() {
        registry.dispatch(List.of("SET", "ktkey", "v1", "EX", "500"), storage);
        long originalTtl = storage.ttl("ktkey");
        assertTrue(originalTtl > 0);

        RedisResponse resp = registry.dispatch(List.of("SET", "ktkey", "v2", "KEEPTTL"), storage);
        assertEquals("OK", resp.getStringValue());
        assertEquals("v2", registry.dispatch(List.of("GET", "ktkey"), storage).getStringValue());
        assertEquals(originalTtl, storage.ttl("ktkey"));
    }

    @Test
    @DisplayName("SET KEEPTTL on key without TTL remains without TTL")
    public void testSetKeepttlExistingWithoutTtl() {
        registry.dispatch(List.of("SET", "ktkey2", "v1"), storage);
        assertEquals(-1L, storage.ttl("ktkey2"));

        RedisResponse resp = registry.dispatch(List.of("SET", "ktkey2", "v2", "KEEPTTL"), storage);
        assertEquals("OK", resp.getStringValue());
        assertEquals(-1L, storage.ttl("ktkey2"));
    }

    @Test
    @DisplayName("SET KEEPTTL on non-existent key creates key without TTL")
    public void testSetKeepttlNonExistent() {
        RedisResponse resp = registry.dispatch(List.of("SET", "ktkey3", "v1", "KEEPTTL"), storage);
        assertEquals("OK", resp.getStringValue());
        assertEquals("v1", registry.dispatch(List.of("GET", "ktkey3"), storage).getStringValue());
        assertEquals(-1L, storage.ttl("ktkey3"));
    }

    // =========================================================================
    // 8. Arbitrary Option Ordering & Case-Insensitivity
    // =========================================================================

    @Test
    @DisplayName("SET options in arbitrary order: GET NX EX")
    public void testArbitraryOrder() {
        RedisResponse resp = registry.dispatch(List.of("SET", "orderkey", "orderval", "get", "nx", "ex", "80"), storage);
        assertEquals(RedisResponse.ResponseType.NIL, resp.getType());
        assertEquals("orderval", registry.dispatch(List.of("GET", "orderkey"), storage).getStringValue());
        assertEquals(80L, storage.ttl("orderkey"));
    }

    @Test
    @DisplayName("SET options in arbitrary order: PX XX GET")
    public void testArbitraryOrder2() {
        registry.dispatch(List.of("SET", "orderkey2", "initial"), storage);

        RedisResponse resp = registry.dispatch(List.of("SET", "orderkey2", "updated", "PX", "90000", "XX", "GET"), storage);
        assertEquals("initial", resp.getStringValue());
        assertEquals("updated", registry.dispatch(List.of("GET", "orderkey2"), storage).getStringValue());
        assertEquals(90000L, storage.pttl("orderkey2"));
    }

    // =========================================================================
    // 9. Syntax Errors & Validation Rules
    // =========================================================================

    @Test
    @DisplayName("Wrong number of arguments returns ERR wrong number of arguments")
    public void testWrongArgumentCount() {
        RedisResponse r1 = registry.dispatch(List.of("SET"), storage);
        assertEquals("ERR wrong number of arguments for 'set' command", r1.getStringValue());

        RedisResponse r2 = registry.dispatch(List.of("SET", "one_arg"), storage);
        assertEquals("ERR wrong number of arguments for 'set' command", r2.getStringValue());
    }

    @Test
    @DisplayName("Conflicting options NX and XX return syntax error")
    public void testConflictNxXx() {
        RedisResponse resp = registry.dispatch(List.of("SET", "k", "v", "NX", "XX"), storage);
        assertEquals("ERR syntax error", resp.getStringValue());
    }

    @Test
    @DisplayName("Conflicting expiration options EX and PX return syntax error")
    public void testConflictExPx() {
        RedisResponse resp = registry.dispatch(List.of("SET", "k", "v", "EX", "10", "PX", "1000"), storage);
        assertEquals("ERR syntax error", resp.getStringValue());
    }

    @Test
    @DisplayName("Conflicting expiration options EX and KEEPTTL return syntax error")
    public void testConflictExKeepttl() {
        RedisResponse resp = registry.dispatch(List.of("SET", "k", "v", "EX", "10", "KEEPTTL"), storage);
        assertEquals("ERR syntax error", resp.getStringValue());

        RedisResponse resp2 = registry.dispatch(List.of("SET", "k", "v", "KEEPTTL", "EX", "10"), storage);
        assertEquals("ERR syntax error", resp2.getStringValue());
    }

    @Test
    @DisplayName("Conflicting expiration options EXAT and PXAT return syntax error")
    public void testConflictExatPxat() {
        RedisResponse resp = registry.dispatch(List.of("SET", "k", "v", "EXAT", "1700000100", "PXAT", "1700000100000"), storage);
        assertEquals("ERR syntax error", resp.getStringValue());
    }

    @Test
    @DisplayName("Duplicate options return syntax error")
    public void testDuplicateOptions() {
        assertEquals("ERR syntax error", registry.dispatch(List.of("SET", "k", "v", "NX", "NX"), storage).getStringValue());
        assertEquals("ERR syntax error", registry.dispatch(List.of("SET", "k", "v", "GET", "GET"), storage).getStringValue());
        assertEquals("ERR syntax error", registry.dispatch(List.of("SET", "k", "v", "KEEPTTL", "KEEPTTL"), storage).getStringValue());
    }

    @Test
    @DisplayName("Unknown option returns syntax error")
    public void testUnknownOption() {
        RedisResponse resp = registry.dispatch(List.of("SET", "k", "v", "UNKNOWN_FLAG"), storage);
        assertEquals("ERR syntax error", resp.getStringValue());
    }

    @Test
    @DisplayName("Missing expiration parameter returns syntax error")
    public void testMissingExpireParam() {
        assertEquals("ERR syntax error", registry.dispatch(List.of("SET", "k", "v", "EX"), storage).getStringValue());
        assertEquals("ERR syntax error", registry.dispatch(List.of("SET", "k", "v", "PX"), storage).getStringValue());
        assertEquals("ERR syntax error", registry.dispatch(List.of("SET", "k", "v", "EXAT"), storage).getStringValue());
        assertEquals("ERR syntax error", registry.dispatch(List.of("SET", "k", "v", "PXAT"), storage).getStringValue());
    }

    @Test
    @DisplayName("Non-integer expiration parameter returns value is not an integer or out of range")
    public void testNonIntegerExpire() {
        assertEquals("ERR value is not an integer or out of range", registry.dispatch(List.of("SET", "k", "v", "EX", "abc"), storage).getStringValue());
        assertEquals("ERR value is not an integer or out of range", registry.dispatch(List.of("SET", "k", "v", "PX", "1.5"), storage).getStringValue());
        assertEquals("ERR value is not an integer or out of range", registry.dispatch(List.of("SET", "k", "v", "EXAT", "foo"), storage).getStringValue());
        assertEquals("ERR value is not an integer or out of range", registry.dispatch(List.of("SET", "k", "v", "PXAT", "bar"), storage).getStringValue());
    }

    @Test
    @DisplayName("Zero or negative expiration for EX/PX returns invalid expire time")
    public void testZeroOrNegativeExpire() {
        assertEquals("ERR invalid expire time in 'set' command", registry.dispatch(List.of("SET", "k", "v", "EX", "0"), storage).getStringValue());
        assertEquals("ERR invalid expire time in 'set' command", registry.dispatch(List.of("SET", "k", "v", "EX", "-5"), storage).getStringValue());
        assertEquals("ERR invalid expire time in 'set' command", registry.dispatch(List.of("SET", "k", "v", "PX", "0"), storage).getStringValue());
        assertEquals("ERR invalid expire time in 'set' command", registry.dispatch(List.of("SET", "k", "v", "PX", "-100"), storage).getStringValue());
    }

    // =========================================================================
    // 10. Binary Safety in Native executeBytes
    // =========================================================================

    @Test
    @DisplayName("Binary keys and values containing 0x00, CRLF, and 0xFF are safely preserved")
    public void testBinarySafety() {
        byte[] binaryKeyBytes = new byte[]{0x00, 'k', 'e', 'y', 0x01, '\r', '\n', (byte) 0xFF};
        byte[] binaryValBytes = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 0x00, '\r', '\n', (byte) 0xFE};

        ByteSequence bKey = ByteSequence.of(binaryKeyBytes);
        ByteSequence bVal = ByteSequence.of(binaryValBytes);

        RedisResponse setResp = registry.dispatchBytes(List.of(ByteSequence.ofAscii("SET"), bKey, bVal, ByteSequence.ofAscii("NX")), storage);
        assertEquals("OK", setResp.getStringValue());

        RedisResponse getResp = registry.dispatchBytes(List.of(ByteSequence.ofAscii("GET"), bKey), storage);
        assertEquals(bVal, getResp.getByteValue());

        // GET with update
        byte[] binaryVal2 = new byte[]{0x01, 0x02, 0x03, 0x04};
        ByteSequence bVal2 = ByteSequence.of(binaryVal2);
        RedisResponse getSetResp = registry.dispatchBytes(List.of(ByteSequence.ofAscii("SET"), bKey, bVal2, ByteSequence.ofAscii("GET")), storage);
        assertEquals(bVal, getSetResp.getByteValue());

        RedisResponse finalGet = registry.dispatchBytes(List.of(ByteSequence.ofAscii("GET"), bKey), storage);
        assertEquals(bVal2, finalGet.getByteValue());
    }

    // =========================================================================
    // 11. Transaction (MULTI / EXEC) Integration
    // =========================================================================

    @Test
    @DisplayName("SET with extended options executes correctly inside MULTI/EXEC transaction")
    public void testTransactionSupport() {
        com.miniredis.server.TransactionContext tx = new com.miniredis.server.TransactionContext();

        registry.dispatchBytes(List.of(ByteSequence.ofAscii("MULTI")), storage, tx);
        registry.dispatchBytes(List.of(ByteSequence.ofAscii("SET"), ByteSequence.ofAscii("tx_k1"), ByteSequence.ofAscii("v1"), ByteSequence.ofAscii("EX"), ByteSequence.ofAscii("100")), storage, tx);
        registry.dispatchBytes(List.of(ByteSequence.ofAscii("SET"), ByteSequence.ofAscii("tx_k1"), ByteSequence.ofAscii("v2"), ByteSequence.ofAscii("GET")), storage, tx);
        registry.dispatchBytes(List.of(ByteSequence.ofAscii("SET"), ByteSequence.ofAscii("tx_k1"), ByteSequence.ofAscii("v3"), ByteSequence.ofAscii("NX")), storage, tx);

        RedisResponse execResp = registry.dispatchBytes(List.of(ByteSequence.ofAscii("EXEC")), storage, tx);
        assertEquals(RedisResponse.ResponseType.ARRAY, execResp.getType());
        List<RedisResponse> items = execResp.getArrayValue();
        assertEquals(3, items.size());

        assertEquals("OK", items.get(0).getStringValue());
        assertEquals("v1", items.get(1).getStringValue());
        assertEquals(RedisResponse.ResponseType.NIL, items.get(2).getType()); // NX failed
    }

    // =========================================================================
    // 12. AOF Persistence & Normalization
    // =========================================================================

    @Test
    @DisplayName("AOF persistence logs mutating SET as normalized PXAT and skips non-mutating SET")
    public void testAofLoggingAndRecovery(@TempDir Path tempDir) throws IOException {
        File aofFile = new File(tempDir.toFile(), "appendonly.aof");
        AofManager aof = new AofManager(aofFile, FsyncPolicy.ALWAYS, storage, registry);
        aof.start();
        storage.setAofManager(aof);

        // 1. Mutating SET with EX
        registry.dispatchBytes(List.of(ByteSequence.ofAscii("SET"), ByteSequence.ofAscii("aof_key"), ByteSequence.ofAscii("val1"), ByteSequence.ofAscii("EX"), ByteSequence.ofAscii("300")), storage);

        // 2. Non-mutating SET with NX (condition fails)
        registry.dispatchBytes(List.of(ByteSequence.ofAscii("SET"), ByteSequence.ofAscii("aof_key"), ByteSequence.ofAscii("val2"), ByteSequence.ofAscii("NX")), storage);

        // 3. Mutating SET with KEEPTTL
        registry.dispatchBytes(List.of(ByteSequence.ofAscii("SET"), ByteSequence.ofAscii("aof_key"), ByteSequence.ofAscii("val_kept"), ByteSequence.ofAscii("KEEPTTL")), storage);

        aof.close();

        // Recover into a fresh storage engine
        StorageEngine freshStorage = new StorageEngine(fixedClock);
        CommandRegistry freshRegistry = new CommandRegistry();
        AofManager freshAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, freshStorage, freshRegistry);
        freshAof.start();
        freshAof.recover();

        assertEquals("val_kept", freshStorage.get("aof_key").getAsString());
        assertEquals(300L, freshStorage.ttl("aof_key"));

        freshAof.close();
    }
}
