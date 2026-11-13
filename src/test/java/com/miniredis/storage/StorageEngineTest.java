package com.miniredis.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StorageEngine Phase 1 Unit Tests")
class StorageEngineTest {

    private StorageEngine storage;

    @BeforeEach
    void setUp() {
        storage = new StorageEngine();
    }

    @Test
    @DisplayName("SET and GET string values")
    void testSetAndGet() {
        storage.set("username", "Krishna");
        RedisValue val = storage.get("username");

        assertNotNull(val);
        assertEquals(RedisDataType.STRING, val.getType());
        assertEquals("Krishna", val.getAsString());
    }

    @Test
    @DisplayName("GET non-existent key returns null")
    void testGetMissingKey() {
        assertNull(storage.get("nonexistent"));
    }

    @Test
    @DisplayName("SET overwrites previous value")
    void testOverwriteKey() {
        storage.set("lang", "Python");
        storage.set("lang", "Java");

        RedisValue val = storage.get("lang");
        assertNotNull(val);
        assertEquals("Java", val.getAsString());
        assertEquals(1, storage.dbSize());
    }

    @Test
    @DisplayName("DEL removes single and multiple keys")
    void testDeleteKeys() {
        storage.set("k1", "v1");
        storage.set("k2", "v2");
        storage.set("k3", "v3");

        int deleted = storage.del(Arrays.asList("k1", "k2", "missing"));
        assertEquals(2, deleted);

        assertNull(storage.get("k1"));
        assertNull(storage.get("k2"));
        assertNotNull(storage.get("k3"));
        assertEquals(1, storage.dbSize());
    }

    @Test
    @DisplayName("EXISTS correctly counts existing keys")
    void testExists() {
        storage.set("k1", "v1");
        storage.set("k2", "v2");

        assertEquals(1, storage.exists(Collections.singletonList("k1")));
        assertEquals(2, storage.exists(Arrays.asList("k1", "k2")));
        assertEquals(1, storage.exists(Arrays.asList("k1", "missing")));
        assertEquals(0, storage.exists(Collections.singletonList("missing")));
    }

    @Test
    @DisplayName("KEYS pattern matching with wildcard and prefixes")
    void testKeysPatternMatching() {
        storage.set("user:1", "Alice");
        storage.set("user:2", "Bob");
        storage.set("admin:1", "Root");

        Set<String> allKeys = storage.keys("*");
        assertEquals(3, allKeys.size());
        assertTrue(allKeys.containsAll(Arrays.asList("user:1", "user:2", "admin:1")));

        Set<String> userKeys = storage.keys("user:*");
        assertEquals(2, userKeys.size());
        assertTrue(userKeys.containsAll(Arrays.asList("user:1", "user:2")));

        Set<String> noKeys = storage.keys("guest:*");
        assertTrue(noKeys.isEmpty());
    }

    @Test
    @DisplayName("FLUSHDB clears all keys")
    void testFlushDb() {
        storage.set("a", "1");
        storage.set("b", "2");
        assertEquals(2, storage.dbSize());

        storage.flushDb();
        assertEquals(0, storage.dbSize());
        assertNull(storage.get("a"));
        assertNull(storage.get("b"));
    }
}