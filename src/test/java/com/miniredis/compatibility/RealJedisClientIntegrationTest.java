package com.miniredis.compatibility;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.server.RedisServer;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.*;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.SetParams;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 17: Stage E Real Jedis 5.1.2 Client Library Interoperability")
public class RealJedisClientIntegrationTest {

    private StorageEngine storage;
    private CommandRegistry registry;
    private RedisServer server;
    private int port;

    @BeforeEach
    public void setUp() throws IOException {
        storage = new StorageEngine();
        registry = new CommandRegistry();
        // Dynamic ephemeral port
        server = new RedisServer(0, 20, storage, registry);
        server.start();
        port = server.getPort();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private Jedis createJedisClient() {
        DefaultJedisClientConfig config = DefaultJedisClientConfig.builder()
                .protocol(RedisProtocol.RESP2)
                .timeoutMillis(3000)
                .build();
        return new Jedis(new HostAndPort("127.0.0.1", port), config);
    }

    @Test
    @DisplayName("Jedis: Connect, PING, SET, GET, STRLEN, INCR, DEL Operations")
    public void testStringLifecycle() {
        try (Jedis jedis = createJedisClient()) {
            // 1. PING
            assertEquals("PONG", jedis.ping());

            // 2. SET and GET
            String setRes = jedis.set("jedis:user:1", "Alice");
            assertEquals("OK", setRes);

            String getRes = jedis.get("jedis:user:1");
            assertEquals("Alice", getRes);

            // 3. STRLEN
            long strlen = jedis.strlen("jedis:user:1");
            assertEquals(5L, strlen);

            // 4. INCR
            jedis.set("jedis:counter", "100");
            long incrRes = jedis.incr("jedis:counter");
            assertEquals(101L, incrRes);

            // 5. DEL
            long delRes = jedis.del("jedis:user:1", "jedis:counter");
            assertEquals(2L, delRes);
            assertNull(jedis.get("jedis:user:1"));
        }
    }

    @Test
    @DisplayName("Jedis: List (LPUSH, LRANGE) & Set (SADD, SMEMBERS) Collections")
    public void testCollections() {
        try (Jedis jedis = createJedisClient()) {
            // Lists: LPUSH elements (note: last argument becomes head in Redis)
            long lpushRes = jedis.lpush("jedis:queue", "item1", "item2", "item3");
            assertEquals(3L, lpushRes);

            List<String> items = jedis.lrange("jedis:queue", 0, -1);
            assertEquals(3, items.size());
            assertEquals("item3", items.get(0));
            assertEquals("item2", items.get(1));
            assertEquals("item1", items.get(2));

            // Sets: SADD and SMEMBERS
            long saddRes = jedis.sadd("jedis:tags", "java", "redis", "jedis");
            assertEquals(3L, saddRes);

            Set<String> tags = jedis.smembers("jedis:tags");
            assertEquals(3, tags.size());
            assertTrue(tags.contains("java"));
            assertTrue(tags.contains("redis"));
            assertTrue(tags.contains("jedis"));

            jedis.del("jedis:queue", "jedis:tags");
        }
    }

    @Test
    @DisplayName("Jedis: Hash (HSET, HGETALL) and Expiration (EXPIRE, TTL)")
    public void testHashesAndExpiry() {
        try (Jedis jedis = createJedisClient()) {
            // Hashes
            long hsetRes = jedis.hset("jedis:profile", Map.of(
                    "username", "krishna",
                    "role", "architect",
                    "status", "active"
            ));
            assertEquals(3L, hsetRes);

            Map<String, String> profile = jedis.hgetAll("jedis:profile");
            assertEquals(3, profile.size());
            assertEquals("krishna", profile.get("username"));
            assertEquals("architect", profile.get("role"));

            // Expiration: EXPIRE and TTL
            long expireRes = jedis.expire("jedis:profile", 3600);
            assertEquals(1L, expireRes);

            long ttl = jedis.ttl("jedis:profile");
            assertTrue(ttl > 0 && ttl <= 3600, "TTL must be between 1 and 3600 seconds, was: " + ttl);

            jedis.del("jedis:profile");
        }
    }

    @Test
    @DisplayName("Jedis: WRONGTYPE Exception and Disconnect/Reconnect Resilience")
    public void testErrorHandlingAndReconnect() {
        // Step 1: WRONGTYPE error throwing standard JedisDataException
        try (Jedis jedis1 = createJedisClient()) {
            jedis1.set("jedis:scalar", "string_val");
            JedisDataException ex = assertThrows(JedisDataException.class, () -> jedis1.lpush("jedis:scalar", "bad_item"));
            assertTrue(ex.getMessage().contains("WRONGTYPE"), "Must contain WRONGTYPE in error message: " + ex.getMessage());

            // Disconnect
            jedis1.disconnect();
            assertFalse(jedis1.isConnected());
        }

        // Step 2: New connection (reconnect) accessing persisted state
        try (Jedis jedis2 = createJedisClient()) {
            assertTrue(jedis2.isConnected() || jedis2.ping().equals("PONG"));
            assertEquals("string_val", jedis2.get("jedis:scalar"));
            jedis2.del("jedis:scalar");
        }
    }

    @Test
    @DisplayName("Jedis: SET with Extended Options (NX, XX, GET, EX, PX, KEEPTTL)")
    public void testSetExtendedOptions() {
        try (Jedis jedis = createJedisClient()) {
            // 1. SET with NX on new key
            String res1 = jedis.set("jedis:opt:1", "val1", SetParams.setParams().nx());
            assertEquals("OK", res1);

            // 2. SET with NX on existing key -> null (nil)
            String res2 = jedis.set("jedis:opt:1", "val2", SetParams.setParams().nx());
            assertNull(res2);
            assertEquals("val1", jedis.get("jedis:opt:1"));

            // 3. SET with XX on existing key
            String res3 = jedis.set("jedis:opt:1", "val3", SetParams.setParams().xx());
            assertEquals("OK", res3);
            assertEquals("val3", jedis.get("jedis:opt:1"));

            // 4. SET with EX and GET
            String res4 = jedis.setGet("jedis:opt:1", "val4", SetParams.setParams().ex(120));
            assertEquals("val3", res4);
            assertEquals("val4", jedis.get("jedis:opt:1"));
            assertTrue(jedis.ttl("jedis:opt:1") > 0);

            // 5. SET with KEEPTTL
            String res5 = jedis.set("jedis:opt:1", "val5", SetParams.setParams().keepttl());
            assertEquals("OK", res5);
            assertEquals("val5", jedis.get("jedis:opt:1"));
            assertTrue(jedis.ttl("jedis:opt:1") > 0);

            jedis.del("jedis:opt:1");
        }
    }

    @Test
    @DisplayName("Jedis: List Parity (LPOP/RPOP COUNT, LINDEX, LSET, LINSERT)")
    public void testListExtendedOperations() {
        try (Jedis jedis = createJedisClient()) {
            jedis.rpush("jedis:list", "first", "second", "third", "fourth");

            // LINDEX
            assertEquals("first", jedis.lindex("jedis:list", 0));
            assertEquals("fourth", jedis.lindex("jedis:list", -1));
            assertNull(jedis.lindex("jedis:list", 100));

            // LSET
            assertEquals("OK", jedis.lset("jedis:list", 1, "SECOND_MOD"));
            assertEquals("SECOND_MOD", jedis.lindex("jedis:list", 1));

            // LINSERT
            long insRes = jedis.linsert("jedis:list", redis.clients.jedis.args.ListPosition.BEFORE, "SECOND_MOD", "between");
            assertEquals(5L, insRes);
            assertEquals("between", jedis.lindex("jedis:list", 1));

            // LPOP with count
            List<String> poppedL = jedis.lpop("jedis:list", 2);
            assertEquals(2, poppedL.size());
            assertEquals("first", poppedL.get(0));
            assertEquals("between", poppedL.get(1));

            // RPOP with count
            List<String> poppedR = jedis.rpop("jedis:list", 2);
            assertEquals(2, poppedR.size());
            assertEquals("fourth", poppedR.get(0));
            assertEquals("third", poppedR.get(1));

            // 1 element remaining: SECOND_MOD
            assertEquals(1L, jedis.llen("jedis:list"));
            assertEquals("SECOND_MOD", jedis.lpop("jedis:list"));
            assertNull(jedis.lpop("jedis:list"));
        }
    }

    @Test
    @DisplayName("Jedis: Hash Parity (HSET multi-field, overwrites, HDEL multi-field, non-existent)")
    public void testHashExtendedOperations() {
        try (Jedis jedis = createJedisClient()) {
            // Multi-field HSET
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("f1", "v1");
            fields.put("f2", "v2");
            fields.put("f3", "v3");
            long added = jedis.hset("jedis:hash", fields);
            assertEquals(3L, added);

            // Overwrite existing field
            long overwrites = jedis.hset("jedis:hash", "f1", "v1_mod");
            assertEquals(0L, overwrites);
            assertEquals("v1_mod", jedis.hget("jedis:hash", "f1"));

            // Multi-field HDEL
            long deleted = jedis.hdel("jedis:hash", "f1", "f2", "missing_field");
            assertEquals(2L, deleted);

            // Non-existent HDEL returns 0
            long delMissing = jedis.hdel("jedis:hash", "missing_field");
            assertEquals(0L, delMissing);

            // Delete final field
            long delFinal = jedis.hdel("jedis:hash", "f3");
            assertEquals(1L, delFinal);
            assertFalse(jedis.exists("jedis:hash"));

            // Binary safety with byte arrays
            byte[] bKey = new byte[] { 'b', 'h', 0x00, 0x01 };
            byte[] bField = new byte[] { 0x00, 'x', '\r', '\n' };
            byte[] bVal = new byte[] { (byte) 0xFF, (byte) 0xFE, 0x00 };
            jedis.hset(bKey, bField, bVal);
            assertArrayEquals(bVal, jedis.hget(bKey, bField));
            jedis.hdel(bKey, bField);
            assertFalse(jedis.exists(bKey));
        }
    }
}
