package com.miniredis.compatibility;

import com.miniredis.aof.AofManager;
import com.miniredis.aof.FsyncPolicy;
import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.protocol.RespParser;
import com.miniredis.protocol.RespSerializer;
import com.miniredis.server.RedisServer;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.RedisValue;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 18 Stage A: Binary-Safe Storage Integration Test")
public class BinarySafetyIntegrationTest {

    private StorageEngine storage;
    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        this.storage = new StorageEngine();
        this.registry = new CommandRegistry();
    }

    @Test
    @DisplayName("1. Binary strings: arbitrary bytes with 0x00, CRLF, and non-UTF-8 bytes (0xFF, 0xFE)")
    void testBinaryStringsPreservation() {
        byte[] binaryData = new byte[] {
                0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, '\r', '\n', 0x00,
                'H', 'e', 'l', 'l', 'o', 0x00, 'W', 'o', 'r', 'l', 'd', (byte) 0x80
        };
        ByteSequence key = ByteSequence.of(new byte[] { 'b', 'i', 'n', ':', 's', 't', 'r', 0x00, 0x01 });
        ByteSequence val = ByteSequence.of(binaryData);

        RedisResponse setResp = registry.dispatchBytes(List.of(ByteSequence.ofAscii("SET"), key, val), storage);
        assertEquals(RedisResponse.ResponseType.SIMPLE_STRING, setResp.getType());
        assertEquals("OK", setResp.getStringValue());

        RedisResponse getResp = registry.dispatchBytes(List.of(ByteSequence.ofAscii("GET"), key), storage);
        assertEquals(RedisResponse.ResponseType.BULK_STRING, getResp.getType());
        assertNotNull(getResp.getByteValue());
        assertArrayEquals(binaryData, getResp.getByteValue().toByteArray());

        // APPEND test
        byte[] appendBytes = new byte[] { (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF };
        RedisResponse appendResp = registry.dispatchBytes(
                List.of(ByteSequence.ofAscii("APPEND"), key, ByteSequence.of(appendBytes)), storage);
        assertEquals(RedisResponse.ResponseType.INTEGER, appendResp.getType());
        assertEquals(binaryData.length + appendBytes.length, appendResp.getIntValue());

        RedisResponse getAfterAppend = registry.dispatchBytes(List.of(ByteSequence.ofAscii("GET"), key), storage);
        byte[] expectedAppended = new byte[binaryData.length + appendBytes.length];
        System.arraycopy(binaryData, 0, expectedAppended, 0, binaryData.length);
        System.arraycopy(appendBytes, 0, expectedAppended, binaryData.length, appendBytes.length);
        assertArrayEquals(expectedAppended, getAfterAppend.getByteValue().toByteArray());

        // STRLEN test
        RedisResponse strlenResp = registry.dispatchBytes(List.of(ByteSequence.ofAscii("STRLEN"), key), storage);
        assertEquals(expectedAppended.length, strlenResp.getIntValue());
    }

    @Test
    @DisplayName("2. Binary MSET and MGET preserve raw byte arrays across multiple keys")
    void testBinaryMsetAndMget() {
        byte[] k1 = new byte[] { 'm', ':', 0x00, '1' };
        byte[] v1 = new byte[] { 0x00, (byte) 0xFF, 'A' };
        byte[] k2 = new byte[] { 'm', ':', 0x00, '2' };
        byte[] v2 = new byte[] { '\r', '\n', 0x00, (byte) 0xFE };

        RedisResponse msetResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("MSET"),
                ByteSequence.of(k1), ByteSequence.of(v1),
                ByteSequence.of(k2), ByteSequence.of(v2)
        ), storage);
        assertEquals("OK", msetResp.getStringValue());

        RedisResponse mgetResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("MGET"),
                ByteSequence.of(k1), ByteSequence.of(k2)
        ), storage);
        assertEquals(RedisResponse.ResponseType.ARRAY, mgetResp.getType());
        List<RedisResponse> items = mgetResp.getArrayValue();
        assertEquals(2, items.size());
        assertArrayEquals(v1, items.get(0).getByteValue().toByteArray());
        assertArrayEquals(v2, items.get(1).getByteValue().toByteArray());
    }

    @Test
    @DisplayName("3. Binary Lists: LPUSH, RPUSH, LPOP, RPOP, LRANGE preserve arbitrary bytes")
    void testBinaryLists() {
        ByteSequence listKey = ByteSequence.of(new byte[] { 'l', 'i', 's', 't', 0x00 });
        byte[] elem1 = new byte[] { 0x00, 0x01, (byte) 0xFF };
        byte[] elem2 = new byte[] { '\r', '\n', 'e', 'l', 'e', 'm', '2' };
        byte[] elem3 = new byte[] { (byte) 0x80, (byte) 0x81, 0x00 };

        // RPUSH elem1, elem2
        RedisResponse rpushResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("RPUSH"), listKey, ByteSequence.of(elem1), ByteSequence.of(elem2)
        ), storage);
        assertEquals(2L, rpushResp.getIntValue());

        // LPUSH elem3
        RedisResponse lpushResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("LPUSH"), listKey, ByteSequence.of(elem3)
        ), storage);
        assertEquals(3L, lpushResp.getIntValue());

        // LLEN
        RedisResponse llenResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("LLEN"), listKey
        ), storage);
        assertEquals(3L, llenResp.getIntValue());

        // LRANGE 0 -1
        RedisResponse lrangeResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("LRANGE"), listKey, ByteSequence.ofAscii("0"), ByteSequence.ofAscii("-1")
        ), storage);
        List<RedisResponse> range = lrangeResp.getArrayValue();
        assertEquals(3, range.size());
        assertArrayEquals(elem3, range.get(0).getByteValue().toByteArray());
        assertArrayEquals(elem1, range.get(1).getByteValue().toByteArray());
        assertArrayEquals(elem2, range.get(2).getByteValue().toByteArray());

        // LPOP
        RedisResponse lpopResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("LPOP"), listKey
        ), storage);
        assertArrayEquals(elem3, lpopResp.getByteValue().toByteArray());

        // RPOP
        RedisResponse rpopResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("RPOP"), listKey
        ), storage);
        assertArrayEquals(elem2, rpopResp.getByteValue().toByteArray());
    }

    @Test
    @DisplayName("4. Binary Sets: SADD, SISMEMBER, SMEMBERS, SREM, SCARD preserve arbitrary bytes")
    void testBinarySets() {
        ByteSequence setKey = ByteSequence.of(new byte[] { 's', 'e', 't', 0x00 });
        byte[] m1 = new byte[] { 0x00, (byte) 0xFF };
        byte[] m2 = new byte[] { '\r', '\n', 'm', '2' };

        // SADD
        RedisResponse saddResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("SADD"), setKey, ByteSequence.of(m1), ByteSequence.of(m2)
        ), storage);
        assertEquals(2L, saddResp.getIntValue());

        // SCARD
        RedisResponse scardResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("SCARD"), setKey
        ), storage);
        assertEquals(2L, scardResp.getIntValue());

        // SISMEMBER
        RedisResponse sismember1 = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("SISMEMBER"), setKey, ByteSequence.of(m1)
        ), storage);
        assertEquals(1L, sismember1.getIntValue());

        RedisResponse sismemberFake = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("SISMEMBER"), setKey, ByteSequence.of(new byte[] { 0x00 })
        ), storage);
        assertEquals(0L, sismemberFake.getIntValue());

        // SMEMBERS
        RedisResponse smembersResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("SMEMBERS"), setKey
        ), storage);
        List<RedisResponse> members = smembersResp.getArrayValue();
        assertEquals(2, members.size());

        // SREM
        RedisResponse sremResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("SREM"), setKey, ByteSequence.of(m1)
        ), storage);
        assertEquals(1L, sremResp.getIntValue());
    }

    @Test
    @DisplayName("5. Binary Hashes: HSET, HGET, HGETALL, HEXISTS, HDEL preserve arbitrary bytes in keys, fields and values")
    void testBinaryHashes() {
        ByteSequence hashKey = ByteSequence.of(new byte[] { 'h', 'a', 's', 'h', 0x00 });
        byte[] f1 = new byte[] { 0x00, 'f', '1' };
        byte[] v1 = new byte[] { (byte) 0xFF, (byte) 0xFE, 0x00 };
        byte[] f2 = new byte[] { '\r', '\n', 'f', '2' };
        byte[] v2 = new byte[] { 'v', 'a', 'l', 0x00, '2' };

        // HSET
        RedisResponse hsetResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("HSET"), hashKey,
                ByteSequence.of(f1), ByteSequence.of(v1),
                ByteSequence.of(f2), ByteSequence.of(v2)
        ), storage);
        assertEquals(2L, hsetResp.getIntValue());

        // HGET
        RedisResponse hget1 = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("HGET"), hashKey, ByteSequence.of(f1)
        ), storage);
        assertArrayEquals(v1, hget1.getByteValue().toByteArray());

        // HEXISTS
        RedisResponse hexists1 = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("HEXISTS"), hashKey, ByteSequence.of(f2)
        ), storage);
        assertEquals(1L, hexists1.getIntValue());

        // HGETALL
        RedisResponse hgetallResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("HGETALL"), hashKey
        ), storage);
        List<RedisResponse> kvPairs = hgetallResp.getArrayValue();
        assertEquals(4, kvPairs.size());

        // HDEL
        RedisResponse hdelResp = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("HDEL"), hashKey, ByteSequence.of(f1)
        ), storage);
        assertEquals(1L, hdelResp.getIntValue());
    }

    @Test
    @DisplayName("6. AOF persistence & crash recovery preserve exact binary sequences across restarts")
    void testAofBinaryRecoveryAndRewrite(@TempDir Path tempDir) throws Exception {
        File aofFile = tempDir.resolve("binary_test.aof").toFile();
        AofManager aof = new AofManager(aofFile, FsyncPolicy.ALWAYS, storage, registry);
        storage.setAofManager(aof);
        aof.start();

        byte[] binKey = new byte[] { 'a', 'o', 'f', ':', 0x00, 'k' };
        byte[] binVal = new byte[] { (byte) 0xFF, 0x00, '\r', '\n', (byte) 0xFE, 'e', 'n', 'd' };

        registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("SET"), ByteSequence.of(binKey), ByteSequence.of(binVal)
        ), storage);

        byte[] listKey = new byte[] { 'a', 'o', 'f', ':', 0x00, 'l' };
        byte[] listElem = new byte[] { 0x01, 0x02, 0x00, 0x03 };
        registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("RPUSH"), ByteSequence.of(listKey), ByteSequence.of(listElem)
        ), storage);

        // Rewrite AOF to test snapshot binary serialization
        aof.rewrite();
        aof.close();

        // New storage engine simulating restart
        StorageEngine restartedStorage = new StorageEngine();
        AofManager recoveryAof = new AofManager(aofFile, FsyncPolicy.EVERYSEC, restartedStorage, registry);
        restartedStorage.setAofManager(recoveryAof);
        int replayed = recoveryAof.recover();
        assertTrue(replayed >= 2, "Expected at least 2 replayed commands");

        // Verify recovered data
        RedisResponse getVal = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("GET"), ByteSequence.of(binKey)
        ), restartedStorage);
        assertNotNull(getVal.getByteValue());
        assertArrayEquals(binVal, getVal.getByteValue().toByteArray());

        RedisResponse getList = registry.dispatchBytes(List.of(
                ByteSequence.ofAscii("LRANGE"), ByteSequence.of(listKey), ByteSequence.ofAscii("0"), ByteSequence.ofAscii("-1")
        ), restartedStorage);
        assertEquals(1, getList.getArrayValue().size());
        assertArrayEquals(listElem, getList.getArrayValue().get(0).getByteValue().toByteArray());

        recoveryAof.close();
    }

    @Test
    @DisplayName("7. Real TCP socket client preserves binary bytes across RESP2 wire encoding and decoding")
    void testTcpSocketBinaryRoundtrip() throws Exception {
        int testPort = 16399;
        RedisServer server = new RedisServer(testPort, storage, registry);
        server.start();

        try {
            try (Socket socket = new Socket("127.0.0.1", testPort)) {
                socket.setTcpNoDelay(true);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());

                byte[] key = new byte[] { 't', 'c', 'p', ':', 0x00, 'k', 'e', 'y' };
                byte[] rawBinary = new byte[] {
                        0x00, (byte) 0xFF, '\r', '\n', 0x00, 'T', 'C', 'P', (byte) 0xFE, 0x00, 0x7F
                };

                // Send SET command using RespSerializer
                byte[] setCommand = RespSerializer.encodeCommandBytes(List.of(
                        ByteSequence.ofAscii("SET"), ByteSequence.of(key), ByteSequence.of(rawBinary)
                ));
                out.write(setCommand);
                out.flush();

                // Read response
                RedisResponse setResponse = RespParser.parseResponse(in);
                assertEquals(RedisResponse.ResponseType.SIMPLE_STRING, setResponse.getType());
                assertEquals("OK", setResponse.getStringValue());

                // Send GET command
                byte[] getCommand = RespSerializer.encodeCommandBytes(List.of(
                        ByteSequence.ofAscii("GET"), ByteSequence.of(key)
                ));
                out.write(getCommand);
                out.flush();

                // Read binary response
                RedisResponse getResponse = RespParser.parseResponse(in);
                assertEquals(RedisResponse.ResponseType.BULK_STRING, getResponse.getType());
                assertNotNull(getResponse.getByteValue());
                assertArrayEquals(rawBinary, getResponse.getByteValue().toByteArray());
            }
        } finally {
            server.stop();
        }
    }
}
