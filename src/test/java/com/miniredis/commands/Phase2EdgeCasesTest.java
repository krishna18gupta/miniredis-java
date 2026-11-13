package com.miniredis.commands;

import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 2 Exhaustive Edge Cases and Boundary Tests")
class Phase2EdgeCasesTest {

    private StorageEngine storage;
    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        storage = new StorageEngine();
        registry = new CommandRegistry();
    }

    @Test
    @DisplayName("HSET updating existing field returns 0 newly added fields")
    void testHSetUpdateExistingField() {
        // Initial insert: 1 new field
        RedisResponse r1 = registry.dispatch("HSET profile age 25", storage);
        assertEquals(1L, r1.getIntValue());

        // Update existing field: 0 new fields added
        RedisResponse r2 = registry.dispatch("HSET profile age 26", storage);
        assertEquals(0L, r2.getIntValue());

        // Verify value updated
        RedisResponse getAge = registry.dispatch("HGET profile age", storage);
        assertEquals("26", getAge.getStringValue());
    }

    @Test
    @DisplayName("SREM on non-existent member returns 0")
    void testSRemNonExistentMember() {
        registry.dispatch("SADD mySet itemA itemB", storage);
        RedisResponse resp = registry.dispatch("SREM mySet itemC", storage);
        assertEquals(0L, resp.getIntValue());
        assertEquals(2L, registry.dispatch("SCARD mySet", storage).getIntValue());
    }

    @Test
    @DisplayName("LPOP and RPOP on missing key returns nil")
    void testLPopRPopMissingKey() {
        RedisResponse lpop = registry.dispatch("LPOP nonExistentList", storage);
        assertEquals(RedisResponse.ResponseType.NIL, lpop.getType());

        RedisResponse rpop = registry.dispatch("RPOP nonExistentList", storage);
        assertEquals(RedisResponse.ResponseType.NIL, rpop.getType());
    }

    @Test
    @DisplayName("HDEL on non-existent hash returns 0")
    void testHDelMissingHash() {
        RedisResponse resp = registry.dispatch("HDEL missingHash field1", storage);
        assertEquals(0L, resp.getIntValue());
    }

    @Test
    @DisplayName("MSET with invalid odd number of arguments returns error")
    void testMSetOddArguments() {
        RedisResponse resp = registry.dispatch("MSET k1 v1 k2", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, resp.getType());
        assertTrue(resp.getStringValue().contains("wrong number of arguments"));
    }

    @Test
    @DisplayName("LRANGE edge cases: missing key, start > stop, start >= size")
    void testLRangeEdgeCases() {
        // Missing key returns empty array
        RedisResponse missing = registry.dispatch("LRANGE missingList 0 -1", storage);
        assertEquals(RedisResponse.ResponseType.ARRAY, missing.getType());
        assertTrue(missing.getArrayValue().isEmpty());

        registry.dispatch("RPUSH testList a b c", storage);

        // start > stop returns empty array
        RedisResponse reversed = registry.dispatch("LRANGE testList 2 1", storage);
        assertTrue(reversed.getArrayValue().isEmpty());

        // start >= size returns empty array
        RedisResponse outOfBounds = registry.dispatch("LRANGE testList 5 10", storage);
        assertTrue(outOfBounds.getArrayValue().isEmpty());
    }

    @Test
    @DisplayName("STRLEN on multi-byte emoji counts exact UTF-8 bytes")
    void testStrLenMultiByte() {
        // Unicode grinning face emoji is 4 UTF-8 bytes
        registry.dispatch("SET emoji 😀", storage);
        RedisResponse resp = registry.dispatch("STRLEN emoji", storage);
        assertEquals(4L, resp.getIntValue());
    }

    @Test
    @DisplayName("DEL works across all data structures (String, List, Set, Hash)")
    void testDelAcrossAllDataTypes() {
        registry.dispatch("SET sKey hello", storage);
        registry.dispatch("LPUSH lKey item1", storage);
        registry.dispatch("SADD setKey member1", storage);
        registry.dispatch("HSET hKey field1 val1", storage);

        assertEquals(4L, registry.dispatch("DBSIZE", storage).getIntValue());

        RedisResponse delResp = registry.dispatch("DEL sKey lKey setKey hKey", storage);
        assertEquals(4L, delResp.getIntValue());
        assertEquals(0L, registry.dispatch("DBSIZE", storage).getIntValue());
    }
}