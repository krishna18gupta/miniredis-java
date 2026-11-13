package com.miniredis.commands;

import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 1 Commands and Dispatcher Unit Tests")
class Phase1CommandsTest {

    private StorageEngine storage;
    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        storage = new StorageEngine();
        registry = new CommandRegistry();
    }

    @Test
    @DisplayName("SET and GET through CommandRegistry")
    void testSetAndGetCommands() {
        RedisResponse setResp = registry.dispatch("SET name Krishna", storage);
        assertEquals(RedisResponse.ResponseType.SIMPLE_STRING, setResp.getType());
        assertEquals("OK", setResp.toCliString());

        RedisResponse getResp = registry.dispatch("GET name", storage);
        assertEquals(RedisResponse.ResponseType.BULK_STRING, getResp.getType());
        assertEquals("\"Krishna\"", getResp.toCliString());
        assertEquals("Krishna", getResp.getStringValue());
    }

    @Test
    @DisplayName("GET missing key returns nil")
    void testGetMissingKey() {
        RedisResponse resp = registry.dispatch("GET unknownKey", storage);
        assertEquals(RedisResponse.ResponseType.NIL, resp.getType());
        assertEquals("(nil)", resp.toCliString());
    }

    @Test
    @DisplayName("Case-insensitivity for command names")
    void testCaseInsensitivity() {
        registry.dispatch("set city Moradabad", storage);
        RedisResponse resp = registry.dispatch("get city", storage);
        assertEquals("Moradabad", resp.getStringValue());

        RedisResponse resp2 = registry.dispatch("GeT city", storage);
        assertEquals("Moradabad", resp2.getStringValue());
    }

    @Test
    @DisplayName("Arguments with spaces inside quotes")
    void testQuotedArguments() {
        RedisResponse setResp = registry.dispatch("SET college \"Galgotias College of Engg\"", storage);
        assertEquals("OK", setResp.toCliString());

        RedisResponse getResp = registry.dispatch("GET college", storage);
        assertEquals("Galgotias College of Engg", getResp.getStringValue());
    }

    @Test
    @DisplayName("DEL command returns deleted count")
    void testDelCommand() {
        registry.dispatch("SET a 1", storage);
        registry.dispatch("SET b 2", storage);

        RedisResponse delResp = registry.dispatch("DEL a b c", storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, delResp.getType());
        assertEquals(2L, delResp.getIntValue());
        assertEquals("(integer) 2", delResp.toCliString());

        RedisResponse getA = registry.dispatch("GET a", storage);
        assertEquals(RedisResponse.ResponseType.NIL, getA.getType());
    }

    @Test
    @DisplayName("EXISTS command returns existing count")
    void testExistsCommand() {
        registry.dispatch("SET x 10", storage);
        RedisResponse resp1 = registry.dispatch("EXISTS x", storage);
        assertEquals(1L, resp1.getIntValue());

        RedisResponse resp2 = registry.dispatch("EXISTS x y z", storage);
        assertEquals(1L, resp2.getIntValue());

        RedisResponse resp3 = registry.dispatch("EXISTS nonExistent", storage);
        assertEquals(0L, resp3.getIntValue());
    }

    @Test
    @DisplayName("KEYS * command returns array of keys")
    void testKeysCommand() {
        registry.dispatch("SET user:1 Alice", storage);
        registry.dispatch("SET user:2 Bob", storage);

        RedisResponse resp = registry.dispatch("KEYS user:*", storage);
        assertEquals(RedisResponse.ResponseType.ARRAY, resp.getType());
        assertEquals(2, resp.getArrayValue().size());
    }

    @Test
    @DisplayName("DBSIZE and FLUSHDB commands")
    void testDbSizeAndFlushDb() {
        assertEquals(0L, registry.dispatch("DBSIZE", storage).getIntValue());

        registry.dispatch("SET k1 v1", storage);
        registry.dispatch("SET k2 v2", storage);
        assertEquals(2L, registry.dispatch("DBSIZE", storage).getIntValue());

        RedisResponse flushResp = registry.dispatch("FLUSHDB", storage);
        assertEquals("OK", flushResp.toCliString());
        assertEquals(0L, registry.dispatch("DBSIZE", storage).getIntValue());
    }

    @Test
    @DisplayName("Argument validation returns error for incorrect argument counts")
    void testArgumentValidation() {
        RedisResponse errSet1 = registry.dispatch("SET", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, errSet1.getType());
        assertTrue(errSet1.getStringValue().contains("wrong number of arguments"));

        RedisResponse errSet2 = registry.dispatch("SET onlyOneArg", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, errSet2.getType());

        RedisResponse errGet = registry.dispatch("GET", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, errGet.getType());

        RedisResponse errDel = registry.dispatch("DEL", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, errDel.getType());
    }

    @Test
    @DisplayName("Unknown command handling")
    void testUnknownCommand() {
        RedisResponse resp = registry.dispatch("FOOBAR baz", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, resp.getType());
        assertTrue(resp.getStringValue().contains("unknown command 'FOOBAR'"));
    }

    @Test
    @DisplayName("Empty command line handling")
    void testEmptyCommandLine() {
        RedisResponse resp = registry.dispatch("", storage);
        assertEquals(RedisResponse.ResponseType.ERROR, resp.getType());
    }
}