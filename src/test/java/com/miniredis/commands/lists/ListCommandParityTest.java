package com.miniredis.commands.lists;

import com.miniredis.aof.AofManager;
import com.miniredis.aof.FsyncPolicy;
import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.server.TransactionContext;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
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

@DisplayName("Phase 18 Stage C: Complete Redis 7.2 List Command Parity Test Suite")
public class ListCommandParityTest {

    private StorageEngine storage;
    private CommandRegistry registry;
    private Clock fixedClock;
    private long baseTimeMillis;

    @BeforeEach
    public void setUp() {
        baseTimeMillis = 1700000000000L;
        fixedClock = Clock.fixed(Instant.ofEpochMilli(baseTimeMillis), ZoneOffset.UTC);
        storage = new StorageEngine(fixedClock);
        registry = new CommandRegistry();
    }

    // =========================================================================
    // 1. LPOP & RPOP: Single Pop vs COUNT Form (RESP2 distinctions)
    // =========================================================================

    @Test
    @DisplayName("LPOP single pop returns bulk string, not array")
    public void testLPopSingle() {
        registry.dispatch(List.of("RPUSH", "mylist", "a", "b", "c"), storage);

        RedisResponse resp = registry.dispatch(List.of("LPOP", "mylist"), storage);
        assertEquals(RedisResponse.ResponseType.BULK_STRING, resp.getType());
        assertEquals("a", resp.getStringValue());
        assertEquals("$1\r\na\r\n", resp.toRespWireFormat());
        assertTrue(resp.isMutated());

        RedisResponse lenResp = registry.dispatch(List.of("LLEN", "mylist"), storage);
        assertEquals(2L, lenResp.getIntValue());
    }

    @Test
    @DisplayName("RPOP single pop returns bulk string, not array")
    public void testRPopSingle() {
        registry.dispatch(List.of("RPUSH", "mylist", "a", "b", "c"), storage);

        RedisResponse resp = registry.dispatch(List.of("RPOP", "mylist"), storage);
        assertEquals(RedisResponse.ResponseType.BULK_STRING, resp.getType());
        assertEquals("c", resp.getStringValue());
        assertEquals("$1\r\nc\r\n", resp.toRespWireFormat());
        assertTrue(resp.isMutated());

        RedisResponse lenResp = registry.dispatch(List.of("LLEN", "mylist"), storage);
        assertEquals(2L, lenResp.getIntValue());
    }

    @Test
    @DisplayName("LPOP with COUNT=1 returns array of 1 element")
    public void testLPopCountOne() {
        registry.dispatch(List.of("RPUSH", "mylist", "a", "b", "c"), storage);

        RedisResponse resp = registry.dispatch(List.of("LPOP", "mylist", "1"), storage);
        assertEquals(RedisResponse.ResponseType.ARRAY, resp.getType());
        assertEquals("*1\r\n$1\r\na\r\n", resp.toRespWireFormat());
        assertEquals(1, resp.getArrayValue().size());
        assertEquals("a", resp.getArrayValue().get(0).getStringValue());
    }

    @Test
    @DisplayName("LPOP with COUNT>1 pops multiple elements in order")
    public void testLPopCountMultiple() {
        registry.dispatch(List.of("RPUSH", "mylist", "one", "two", "three", "four"), storage);

        RedisResponse resp = registry.dispatch(List.of("LPOP", "mylist", "2"), storage);
        assertEquals(RedisResponse.ResponseType.ARRAY, resp.getType());
        assertEquals(2, resp.getArrayValue().size());
        assertEquals("one", resp.getArrayValue().get(0).getStringValue());
        assertEquals("two", resp.getArrayValue().get(1).getStringValue());
        assertEquals("*2\r\n$3\r\none\r\n$3\r\ntwo\r\n", resp.toRespWireFormat());

        // Remaining elements
        RedisResponse rangeResp = registry.dispatch(List.of("LRANGE", "mylist", "0", "-1"), storage);
        assertEquals(2, rangeResp.getArrayValue().size());
        assertEquals("three", rangeResp.getArrayValue().get(0).getStringValue());
        assertEquals("four", rangeResp.getArrayValue().get(1).getStringValue());
    }

    @Test
    @DisplayName("RPOP with COUNT>1 pops multiple elements in reverse tail order")
    public void testRPopCountMultiple() {
        registry.dispatch(List.of("RPUSH", "mylist", "one", "two", "three", "four"), storage);

        RedisResponse resp = registry.dispatch(List.of("RPOP", "mylist", "2"), storage);
        assertEquals(RedisResponse.ResponseType.ARRAY, resp.getType());
        assertEquals(2, resp.getArrayValue().size());
        assertEquals("four", resp.getArrayValue().get(0).getStringValue());
        assertEquals("three", resp.getArrayValue().get(1).getStringValue());
        assertEquals("*2\r\n$4\r\nfour\r\n$5\r\nthree\r\n", resp.toRespWireFormat());

        // Remaining elements
        RedisResponse rangeResp = registry.dispatch(List.of("LRANGE", "mylist", "0", "-1"), storage);
        assertEquals(2, rangeResp.getArrayValue().size());
        assertEquals("one", rangeResp.getArrayValue().get(0).getStringValue());
        assertEquals("two", rangeResp.getArrayValue().get(1).getStringValue());
    }

    @Test
    @DisplayName("LPOP/RPOP with COUNT greater than list length pops all and removes key")
    public void testPopCountExceedingLength() {
        registry.dispatch(List.of("RPUSH", "mylist", "x", "y"), storage);

        RedisResponse resp = registry.dispatch(List.of("LPOP", "mylist", "10"), storage);
        assertEquals(RedisResponse.ResponseType.ARRAY, resp.getType());
        assertEquals(2, resp.getArrayValue().size());
        assertEquals("x", resp.getArrayValue().get(0).getStringValue());
        assertEquals("y", resp.getArrayValue().get(1).getStringValue());
        assertFalse(storage.exists("mylist"));

        // Repeating on now-empty key returns null array
        RedisResponse emptyResp = registry.dispatch(List.of("LPOP", "mylist", "10"), storage);
        assertEquals(RedisResponse.ResponseType.ARRAY, emptyResp.getType());
        assertEquals("*-1\r\n", emptyResp.toRespWireFormat());
        assertFalse(emptyResp.isMutated());
    }

    @Test
    @DisplayName("LPOP/RPOP on missing key: single returns nil, count returns null array")
    public void testPopMissingKeyDistinction() {
        // Without count: nil bulk string $-1\r\n
        RedisResponse singleResp = registry.dispatch(List.of("LPOP", "no_such_list"), storage);
        assertEquals(RedisResponse.ResponseType.NIL, singleResp.getType());
        assertEquals("$-1\r\n", singleResp.toRespWireFormat());
        assertFalse(singleResp.isMutated());

        // With count: null array *-1\r\n
        RedisResponse countResp = registry.dispatch(List.of("LPOP", "no_such_list", "5"), storage);
        assertEquals(RedisResponse.ResponseType.ARRAY, countResp.getType());
        assertEquals("*-1\r\n", countResp.toRespWireFormat());
        assertFalse(countResp.isMutated());
    }

    @Test
    @DisplayName("LPOP/RPOP with COUNT=0: existing list returns empty array *0, missing key returns null array *-1")
    public void testPopCountZero() {
        registry.dispatch(List.of("RPUSH", "exist_list", "v1"), storage);

        // Existing list with count 0 -> empty array *0\r\n
        RedisResponse respExist = registry.dispatch(List.of("LPOP", "exist_list", "0"), storage);
        assertEquals(RedisResponse.ResponseType.ARRAY, respExist.getType());
        assertEquals("*0\r\n", respExist.toRespWireFormat());
        assertEquals(0, respExist.getArrayValue().size());
        assertFalse(respExist.isMutated());
        assertEquals(1L, registry.dispatch(List.of("LLEN", "exist_list"), storage).getIntValue());

        // Missing key with count 0 -> null array *-1\r\n
        RedisResponse respMissing = registry.dispatch(List.of("LPOP", "missing_list", "0"), storage);
        assertEquals(RedisResponse.ResponseType.ARRAY, respMissing.getType());
        assertEquals("*-1\r\n", respMissing.toRespWireFormat());
        assertFalse(respMissing.isMutated());
    }

    @Test
    @DisplayName("LPOP/RPOP error handling: negative count, non-integer count, wrong type")
    public void testPopValidationErrors() {
        registry.dispatch(List.of("RPUSH", "mylist", "a"), storage);
        registry.dispatch(List.of("SET", "strkey", "hello"), storage);

        // Negative count
        RedisResponse negResp = registry.dispatch(List.of("LPOP", "mylist", "-1"), storage);
        assertEquals(RedisResponse.ResponseType.ERROR, negResp.getType());
        assertEquals("ERR value is out of range, must be positive", negResp.getStringValue());

        // Non-integer count
        RedisResponse nanResp = registry.dispatch(List.of("LPOP", "mylist", "abc"), storage);
        assertEquals(RedisResponse.ResponseType.ERROR, nanResp.getType());
        assertEquals("ERR value is not an integer or out of range", nanResp.getStringValue());

        // Wrong type with and without count
        RedisResponse wt1 = registry.dispatch(List.of("LPOP", "strkey"), storage);
        assertEquals(RedisResponse.ResponseType.ERROR, wt1.getType());
        assertTrue(wt1.getStringValue().contains("WRONGTYPE"));

        RedisResponse wt2 = registry.dispatch(List.of("LPOP", "strkey", "2"), storage);
        assertEquals(RedisResponse.ResponseType.ERROR, wt2.getType());
        assertTrue(wt2.getStringValue().contains("WRONGTYPE"));

        RedisResponse wt0 = registry.dispatch(List.of("LPOP", "strkey", "0"), storage);
        assertEquals(RedisResponse.ResponseType.ERROR, wt0.getType());
        assertTrue(wt0.getStringValue().contains("WRONGTYPE"));

        // Wrong argument arity
        RedisResponse arity = registry.dispatch(List.of("LPOP", "mylist", "1", "extra"), storage);
        assertEquals("ERR wrong number of arguments for 'lpop' command", arity.getStringValue());
    }

    @Test
    @DisplayName("LPOP/RPOP preserves key TTL when elements remain, deletes TTL when list emptied")
    public void testPopTtlPreservation() {
        registry.dispatch(List.of("RPUSH", "ttl_list", "first", "second"), storage);
        registry.dispatch(List.of("EXPIRE", "ttl_list", "300"), storage);
        assertTrue(storage.ttl("ttl_list") > 0);

        // Pop one element: TTL preserved
        registry.dispatch(List.of("LPOP", "ttl_list"), storage);
        assertTrue(storage.ttl("ttl_list") > 0);

        // Pop last element: key deleted, TTL is -2 (key does not exist)
        registry.dispatch(List.of("LPOP", "ttl_list"), storage);
        assertEquals(-2L, storage.ttl("ttl_list"));
        assertFalse(storage.exists("ttl_list"));
    }

    // =========================================================================
    // 2. LINDEX: Index-based element retrieval
    // =========================================================================

    @Test
    @DisplayName("LINDEX positive and negative index retrieval")
    public void testLIndex() {
        registry.dispatch(List.of("RPUSH", "idxlist", "zero", "one", "two", "three"), storage);

        // Positive indices
        assertEquals("zero", registry.dispatch(List.of("LINDEX", "idxlist", "0"), storage).getStringValue());
        assertEquals("one", registry.dispatch(List.of("LINDEX", "idxlist", "1"), storage).getStringValue());
        assertEquals("two", registry.dispatch(List.of("LINDEX", "idxlist", "2"), storage).getStringValue());
        assertEquals("three", registry.dispatch(List.of("LINDEX", "idxlist", "3"), storage).getStringValue());

        // Negative indices
        assertEquals("three", registry.dispatch(List.of("LINDEX", "idxlist", "-1"), storage).getStringValue());
        assertEquals("two", registry.dispatch(List.of("LINDEX", "idxlist", "-2"), storage).getStringValue());
        assertEquals("zero", registry.dispatch(List.of("LINDEX", "idxlist", "-4"), storage).getStringValue());

        // Out of range indices return nil
        assertEquals(RedisResponse.ResponseType.NIL, registry.dispatch(List.of("LINDEX", "idxlist", "4"), storage).getType());
        assertEquals(RedisResponse.ResponseType.NIL, registry.dispatch(List.of("LINDEX", "idxlist", "100"), storage).getType());
        assertEquals(RedisResponse.ResponseType.NIL, registry.dispatch(List.of("LINDEX", "idxlist", "-5"), storage).getType());
        assertEquals(RedisResponse.ResponseType.NIL, registry.dispatch(List.of("LINDEX", "idxlist", "-100"), storage).getType());

        // Missing key returns nil
        assertEquals(RedisResponse.ResponseType.NIL, registry.dispatch(List.of("LINDEX", "nonexistent", "0"), storage).getType());
    }

    @Test
    @DisplayName("LINDEX extreme numeric inputs and error handling")
    public void testLIndexErrorsAndExtremes() {
        registry.dispatch(List.of("RPUSH", "idxlist", "val"), storage);
        registry.dispatch(List.of("SET", "strkey", "scalar"), storage);

        // Extreme 64-bit indices return nil without overflow
        assertEquals(RedisResponse.ResponseType.NIL, registry.dispatch(List.of("LINDEX", "idxlist", String.valueOf(Long.MAX_VALUE)), storage).getType());
        assertEquals(RedisResponse.ResponseType.NIL, registry.dispatch(List.of("LINDEX", "idxlist", String.valueOf(Long.MIN_VALUE)), storage).getType());

        // Non-integer index
        RedisResponse err1 = registry.dispatch(List.of("LINDEX", "idxlist", "invalid"), storage);
        assertEquals("ERR value is not an integer or out of range", err1.getStringValue());

        // Wrong type
        RedisResponse wt = registry.dispatch(List.of("LINDEX", "strkey", "0"), storage);
        assertTrue(wt.getStringValue().contains("WRONGTYPE"));

        // Arity errors
        assertEquals("ERR wrong number of arguments for 'lindex' command", registry.dispatch(List.of("LINDEX", "idxlist"), storage).getStringValue());
        assertEquals("ERR wrong number of arguments for 'lindex' command", registry.dispatch(List.of("LINDEX", "idxlist", "0", "extra"), storage).getStringValue());
    }

    // =========================================================================
    // 3. LSET: Replace element by index
    // =========================================================================

    @Test
    @DisplayName("LSET replaces elements at positive and negative indices")
    public void testLSet() {
        registry.dispatch(List.of("RPUSH", "setlist", "a", "b", "c", "d"), storage);
        registry.dispatch(List.of("EXPIRE", "setlist", "400"), storage);

        // Positive index
        RedisResponse r1 = registry.dispatch(List.of("LSET", "setlist", "1", "beta"), storage);
        assertEquals("OK", r1.getStringValue());
        assertEquals("beta", registry.dispatch(List.of("LINDEX", "setlist", "1"), storage).getStringValue());

        // Negative index
        RedisResponse r2 = registry.dispatch(List.of("LSET", "setlist", "-1", "delta"), storage);
        assertEquals("OK", r2.getStringValue());
        assertEquals("delta", registry.dispatch(List.of("LINDEX", "setlist", "-1"), storage).getStringValue());

        // TTL preserved
        assertTrue(storage.ttl("setlist") > 0);
    }

    @Test
    @DisplayName("LSET error semantics: no such key, index out of range, wrongtype")
    public void testLSetErrors() {
        registry.dispatch(List.of("RPUSH", "setlist", "only"), storage);
        registry.dispatch(List.of("SET", "strkey", "scalar"), storage);

        // Missing key -> ERR no such key
        RedisResponse noKey = registry.dispatch(List.of("LSET", "missing_key", "0", "val"), storage);
        assertEquals("ERR no such key", noKey.getStringValue());

        // Index out of range
        RedisResponse outPos = registry.dispatch(List.of("LSET", "setlist", "1", "val"), storage);
        assertEquals("ERR index out of range", outPos.getStringValue());

        RedisResponse outNeg = registry.dispatch(List.of("LSET", "setlist", "-2", "val"), storage);
        assertEquals("ERR index out of range", outNeg.getStringValue());

        // Non-integer index
        RedisResponse nan = registry.dispatch(List.of("LSET", "setlist", "abc", "val"), storage);
        assertEquals("ERR value is not an integer or out of range", nan.getStringValue());

        // Wrong type
        RedisResponse wt = registry.dispatch(List.of("LSET", "strkey", "0", "val"), storage);
        assertTrue(wt.getStringValue().contains("WRONGTYPE"));

        // Arity errors
        assertEquals("ERR wrong number of arguments for 'lset' command", registry.dispatch(List.of("LSET", "setlist", "0"), storage).getStringValue());
    }

    // =========================================================================
    // 4. LINSERT: Insert relative to pivot
    // =========================================================================

    @Test
    @DisplayName("LINSERT BEFORE and AFTER insertions with case-insensitivity")
    public void testLInsert() {
        registry.dispatch(List.of("RPUSH", "inslist", "alpha", "gamma"), storage);
        registry.dispatch(List.of("EXPIRE", "inslist", "600"), storage);

        // Insert BEFORE gamma (lowercase "before")
        RedisResponse r1 = registry.dispatch(List.of("LINSERT", "inslist", "before", "gamma", "beta"), storage);
        assertEquals(3L, r1.getIntValue());
        assertTrue(r1.isMutated());

        // Insert AFTER gamma (mixed-case "AfTeR")
        RedisResponse r2 = registry.dispatch(List.of("LINSERT", "inslist", "AfTeR", "gamma", "delta"), storage);
        assertEquals(4L, r2.getIntValue());
        assertTrue(r2.isMutated());

        // Verify ordering: alpha, beta, gamma, delta
        RedisResponse range = registry.dispatch(List.of("LRANGE", "inslist", "0", "-1"), storage);
        assertEquals(4, range.getArrayValue().size());
        assertEquals("alpha", range.getArrayValue().get(0).getStringValue());
        assertEquals("beta", range.getArrayValue().get(1).getStringValue());
        assertEquals("gamma", range.getArrayValue().get(2).getStringValue());
        assertEquals("delta", range.getArrayValue().get(3).getStringValue());

        // TTL preserved
        assertTrue(storage.ttl("inslist") > 0);
    }

    @Test
    @DisplayName("LINSERT with duplicate pivots inserts relative to the first pivot from head")
    public void testLInsertDuplicatePivots() {
        registry.dispatch(List.of("RPUSH", "duplist", "target", "middle", "target", "end"), storage);

        RedisResponse resp = registry.dispatch(List.of("LINSERT", "duplist", "AFTER", "target", "inserted"), storage);
        assertEquals(5L, resp.getIntValue());

        // Result: target, inserted, middle, target, end
        RedisResponse range = registry.dispatch(List.of("LRANGE", "duplist", "0", "-1"), storage);
        assertEquals("target", range.getArrayValue().get(0).getStringValue());
        assertEquals("inserted", range.getArrayValue().get(1).getStringValue());
        assertEquals("middle", range.getArrayValue().get(2).getStringValue());
        assertEquals("target", range.getArrayValue().get(3).getStringValue());
        assertEquals("end", range.getArrayValue().get(4).getStringValue());
    }

    @Test
    @DisplayName("LINSERT edge cases: missing key returns 0, missing pivot returns -1, syntax error, wrongtype")
    public void testLInsertEdgeCases() {
        registry.dispatch(List.of("RPUSH", "inslist", "item"), storage);
        registry.dispatch(List.of("SET", "strkey", "scalar"), storage);

        // Missing key returns 0 (not mutated, key not created)
        RedisResponse rMissing = registry.dispatch(List.of("LINSERT", "no_key", "BEFORE", "any", "elem"), storage);
        assertEquals(0L, rMissing.getIntValue());
        assertFalse(rMissing.isMutated());
        assertFalse(storage.exists("no_key"));

        // Missing pivot returns -1 (not mutated, list unchanged)
        RedisResponse rNoPivot = registry.dispatch(List.of("LINSERT", "inslist", "BEFORE", "nonexistent_pivot", "elem"), storage);
        assertEquals(-1L, rNoPivot.getIntValue());
        assertFalse(rNoPivot.isMutated());
        assertEquals(1L, registry.dispatch(List.of("LLEN", "inslist"), storage).getIntValue());

        // Invalid position token returns syntax error
        RedisResponse rSyntax = registry.dispatch(List.of("LINSERT", "inslist", "MIDDLE", "item", "elem"), storage);
        assertEquals("ERR syntax error", rSyntax.getStringValue());

        // Wrong type returns WRONGTYPE error
        RedisResponse rWrong = registry.dispatch(List.of("LINSERT", "strkey", "BEFORE", "a", "b"), storage);
        assertTrue(rWrong.getStringValue().contains("WRONGTYPE"));

        // Arity errors
        assertEquals("ERR wrong number of arguments for 'linsert' command", registry.dispatch(List.of("LINSERT", "inslist", "BEFORE", "item"), storage).getStringValue());
    }

    // =========================================================================
    // 5. Binary Safety: byte sequences with 0x00, CRLF, and 0xFF
    // =========================================================================

    @Test
    @DisplayName("Binary list keys, elements, and pivots with null bytes and CRLF")
    public void testBinarySafety() {
        ByteSequence bKey = ByteSequence.of(new byte[]{0x00, 'b', 'l', 'i', 's', 't', 0x01, '\r', '\n', (byte) 0xFF});
        ByteSequence elem1 = ByteSequence.of(new byte[]{(byte) 0xAA, 0x00, '\r', '\n', (byte) 0xBB});
        ByteSequence elem2 = ByteSequence.of(new byte[]{(byte) 0xCC, 0x00, (byte) 0xDD});
        ByteSequence elem3 = ByteSequence.of(new byte[]{(byte) 0xEE, (byte) 0xFF});

        // RPUSH binary
        registry.dispatchBytes(List.of(ByteSequence.ofAscii("RPUSH"), bKey, elem1, elem3), storage);

        // LINSERT binary pivot
        RedisResponse insResp = registry.dispatchBytes(List.of(ByteSequence.ofAscii("LINSERT"), bKey, ByteSequence.ofAscii("BEFORE"), elem3, elem2), storage);
        assertEquals(3L, insResp.getIntValue());

        // LINDEX binary
        RedisResponse idxResp = registry.dispatchBytes(List.of(ByteSequence.ofAscii("LINDEX"), bKey, ByteSequence.ofAscii("1")), storage);
        assertEquals(elem2, idxResp.getByteValue());

        // LSET binary
        ByteSequence elem2Replacement = ByteSequence.of(new byte[]{0x01, 0x02, 0x00, 0x03});
        registry.dispatchBytes(List.of(ByteSequence.ofAscii("LSET"), bKey, ByteSequence.ofAscii("1"), elem2Replacement), storage);
        assertEquals(elem2Replacement, registry.dispatchBytes(List.of(ByteSequence.ofAscii("LINDEX"), bKey, ByteSequence.ofAscii("1")), storage).getByteValue());

        // LPOP binary with count
        RedisResponse popResp = registry.dispatchBytes(List.of(ByteSequence.ofAscii("LPOP"), bKey, ByteSequence.ofAscii("2")), storage);
        assertEquals(2, popResp.getArrayValue().size());
        assertEquals(elem1, popResp.getArrayValue().get(0).getByteValue());
        assertEquals(elem2Replacement, popResp.getArrayValue().get(1).getByteValue());
    }

    // =========================================================================
    // 6. Transactions (MULTI / EXEC / WATCH)
    // =========================================================================

    @Test
    @DisplayName("List commands execute atomically inside MULTI/EXEC transaction")
    public void testTransactions() {
        TransactionContext tx = new TransactionContext();

        registry.dispatch(List.of("MULTI"), storage, tx);
        registry.dispatch(List.of("RPUSH", "tx_list", "item1", "item2", "item3"), storage, tx);
        registry.dispatch(List.of("LSET", "tx_list", "1", "item2_updated"), storage, tx);
        registry.dispatch(List.of("LINSERT", "tx_list", "BEFORE", "item3", "item2_5"), storage, tx);
        registry.dispatch(List.of("LPOP", "tx_list", "2"), storage, tx);
        registry.dispatch(List.of("LINDEX", "tx_list", "0"), storage, tx);

        RedisResponse execResp = registry.dispatch(List.of("EXEC"), storage, tx);
        assertEquals(RedisResponse.ResponseType.ARRAY, execResp.getType());
        List<RedisResponse> items = execResp.getArrayValue();
        assertEquals(5, items.size());

        assertEquals(3L, items.get(0).getIntValue()); // RPUSH
        assertEquals("OK", items.get(1).getStringValue()); // LSET
        assertEquals(4L, items.get(2).getIntValue()); // LINSERT
        assertEquals(2, items.get(3).getArrayValue().size()); // LPOP 2
        assertEquals("item2_5", items.get(4).getStringValue()); // LINDEX 0
    }

    // =========================================================================
    // 7. AOF Persistence & Recovery
    // =========================================================================

    @Test
    @DisplayName("AOF persistence records mutating list operations and recovers exact state")
    public void testAofPersistenceAndRecovery(@TempDir Path tempDir) throws IOException {
        File aofFile = new File(tempDir.toFile(), "appendonly.aof");
        AofManager aof = new AofManager(aofFile, FsyncPolicy.ALWAYS, storage, registry);
        aof.start();
        storage.setAofManager(aof);

        // Mutating sequence
        registry.dispatch(List.of("RPUSH", "aof_list", "v1", "v2", "v3"), storage);
        registry.dispatch(List.of("LSET", "aof_list", "1", "v2_mod"), storage);
        registry.dispatch(List.of("LINSERT", "aof_list", "AFTER", "v1", "v1_5"), storage);
        registry.dispatch(List.of("EXPIRE", "aof_list", "300"), storage);

        // Non-mutating commands (must not cause issues or drift in AOF)
        registry.dispatch(List.of("LINDEX", "aof_list", "0"), storage);
        registry.dispatch(List.of("LPOP", "aof_list", "0"), storage);
        registry.dispatch(List.of("LINSERT", "aof_list", "BEFORE", "missing_pivot", "val"), storage);

        // One pop
        registry.dispatch(List.of("LPOP", "aof_list"), storage); // pops v1

        aof.close();

        // Fresh recovery
        StorageEngine freshStorage = new StorageEngine(fixedClock);
        CommandRegistry freshRegistry = new CommandRegistry();
        AofManager freshAof = new AofManager(aofFile, FsyncPolicy.ALWAYS, freshStorage, freshRegistry);
        freshAof.start();
        freshAof.recover();

        assertEquals(3, freshStorage.llen("aof_list"));
        assertEquals("v1_5", freshStorage.lindex("aof_list", 0));
        assertEquals("v2_mod", freshStorage.lindex("aof_list", 1));
        assertEquals("v3", freshStorage.lindex("aof_list", 2));
        assertEquals(300L, freshStorage.ttl("aof_list"));

        freshAof.close();
    }
}
