package com.miniredis.web;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MiniRedis Demo Mode Security, Visitor Isolation & Resource Protection Tests")
class DemoSecurityAndIsolationTest {

    private static final int TEST_PORT = 8102;
    private static StorageEngine storage;
    private static CommandRegistry registry;
    private static MiniRedisWebServer server;
    private static DemoSecurityContext demoSecurity;
    private static HttpClient client;

    @BeforeAll
    static void startServer() throws IOException {
        storage = new StorageEngine();
        registry = new CommandRegistry();
        // Start server in demoMode=true without admin token (public visitor mode)
        server = new MiniRedisWebServer(TEST_PORT, storage, registry, null, true, null);
        server.start();
        demoSecurity = server.getDemoSecurity();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("1. Session ID validation: Valid 128-bit UUID passes, invalid or short ID fails")
    void testSessionIdValidation() throws Exception {
        String validUuid = UUID.randomUUID().toString();
        String validHex = "0123456789abcdef0123456789abcdef"; // 32 hex chars (128 bits)
        String shortId = "invalid-short-id";

        assertTrue(DemoSecurityContext.isValidSessionId(validUuid), "UUID must be valid");
        assertTrue(DemoSecurityContext.isValidSessionId(validHex), "32-char hex token must be valid");
        assertFalse(DemoSecurityContext.isValidSessionId(shortId), "Short string must be invalid");
        assertFalse(DemoSecurityContext.isValidSessionId(null), "null must be invalid");
        assertFalse(DemoSecurityContext.isValidSessionId(""), "empty must be invalid");

        // Verify HTTP layer rejects invalid session ID with 400
        HttpRequest reqInvalid = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/keys"))
                .header("X-Session-ID", shortId)
                .GET()
                .build();
        HttpResponse<String> resInvalid = client.send(reqInvalid, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resInvalid.statusCode());
        assertTrue(resInvalid.body().contains("X-Session-ID"));

        // Verify valid session ID is accepted with 200
        HttpRequest reqValid = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/keys"))
                .header("X-Session-ID", validUuid)
                .GET()
                .build();
        HttpResponse<String> resValid = client.send(reqValid, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resValid.statusCode());
    }

    @Test
    @DisplayName("2. Visitor isolation: Two visitors with same key name have isolated data")
    void testVisitorDataIsolation() throws Exception {
        String sessionA = UUID.randomUUID().toString();
        String sessionB = UUID.randomUUID().toString();

        // Session A sets user_name = Alice
        HttpRequest reqSetA = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", sessionA)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("SET user_name Alice"))
                .build();
        HttpResponse<String> resSetA = client.send(reqSetA, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resSetA.statusCode());
        assertTrue(resSetA.body().contains("OK"));

        // Session B sets user_name = Bob
        HttpRequest reqSetB = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", sessionB)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("SET user_name Bob"))
                .build();
        HttpResponse<String> resSetB = client.send(reqSetB, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resSetB.statusCode());
        assertTrue(resSetB.body().contains("OK"));

        // Session A gets user_name -> returns Alice
        HttpRequest reqGetA = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", sessionA)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("GET user_name"))
                .build();
        HttpResponse<String> resGetA = client.send(reqGetA, HttpResponse.BodyHandlers.ofString());
        assertTrue(resGetA.body().contains("Alice"), "Session A must read Alice");
        assertFalse(resGetA.body().contains("Bob"), "Session A must NOT see Bob");

        // Session B gets user_name -> returns Bob
        HttpRequest reqGetB = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", sessionB)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("GET user_name"))
                .build();
        HttpResponse<String> resGetB = client.send(reqGetB, HttpResponse.BodyHandlers.ofString());
        assertTrue(resGetB.body().contains("Bob"), "Session B must read Bob");
        assertFalse(resGetB.body().contains("Alice"), "Session B must NOT see Alice");

        // Session A deletes user_name via DELETE /api/keys?key=user_name
        HttpRequest reqDelA = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/keys?key=user_name"))
                .header("X-Session-ID", sessionA)
                .DELETE()
                .build();
        HttpResponse<String> resDelA = client.send(reqDelA, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resDelA.statusCode());

        // Session A GET user_name is now nil
        HttpResponse<String> resGetA2 = client.send(reqGetA, HttpResponse.BodyHandlers.ofString());
        assertTrue(resGetA2.body().contains("(nil)"));

        // Session B GET user_name remains Bob
        HttpResponse<String> resGetB2 = client.send(reqGetB, HttpResponse.BodyHandlers.ofString());
        assertTrue(resGetB2.body().contains("Bob"), "Session B's key must not be deleted by Session A");
    }

    @Test
    @DisplayName("3. Immutable Demo Keys: All mutation attempts on demo:* keys are rejected")
    void testImmutableDemoKeys() throws Exception {
        String session = UUID.randomUUID().toString();

        // Attempt SET demo:welcome
        HttpRequest reqSet = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", session)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("SET demo:welcome Defaced"))
                .build();
        HttpResponse<String> resSet = client.send(reqSet, HttpResponse.BodyHandlers.ofString());
        assertTrue(resSet.body().contains("immutable system demo key"), "SET demo:welcome must be rejected");

        // Attempt DEL demo:welcome via terminal
        HttpRequest reqDelCmd = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", session)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("DEL demo:welcome"))
                .build();
        HttpResponse<String> resDelCmd = client.send(reqDelCmd, HttpResponse.BodyHandlers.ofString());
        assertTrue(resDelCmd.body().contains("immutable system demo key"), "DEL demo:welcome must be rejected");

        // Attempt DELETE /api/keys?key=demo:welcome (HTTP 403 Forbidden)
        HttpRequest reqDelRest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/keys?key=demo:welcome"))
                .header("X-Session-ID", session)
                .DELETE()
                .build();
        HttpResponse<String> resDelRest = client.send(reqDelRest, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, resDelRest.statusCode(), "REST DELETE on demo key must return 403");

        // Attempt LPUSH on demo:pipeline
        HttpRequest reqLpush = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", session)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("LPUSH demo:pipeline MaliciousPayload"))
                .build();
        HttpResponse<String> resLpush = client.send(reqLpush, HttpResponse.BodyHandlers.ofString());
        assertTrue(resLpush.body().contains("immutable system demo key"), "LPUSH demo:pipeline must be rejected");

        // Attempt EXPIRE on demo:welcome
        HttpRequest reqExpire = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", session)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("EXPIRE demo:welcome 1"))
                .build();
        HttpResponse<String> resExpire = client.send(reqExpire, HttpResponse.BodyHandlers.ofString());
        assertTrue(resExpire.body().contains("immutable system demo key"), "EXPIRE demo:welcome must be rejected");

        // Verify demo:welcome is intact
        assertEquals("Welcome to MiniRedis v1.0.0!", storage.get("demo:welcome").getAsString());
    }

    @Test
    @DisplayName("4. Command Allowlist: Dangerous admin commands are blocked in public demo")
    void testCommandAllowlist() throws Exception {
        String session = UUID.randomUUID().toString();

        String[] blockedCommands = {
                "SHUTDOWN", "CONFIG GET *", "FLUSHDB", "FLUSHALL", "SAVE", "BGSAVE", "BGREWRITEAOF"
        };

        for (String cmd : blockedCommands) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                    .header("X-Session-ID", session)
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(cmd))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertTrue(res.body().contains("public demo mode") || res.body().contains("not permitted") || res.body().contains("disabled"),
                    "Command '" + cmd + "' must be rejected in public demo");
        }

        // Safe commands must work
        HttpRequest reqPing = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", session)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("PING"))
                .build();
        HttpResponse<String> resPing = client.send(reqPing, HttpResponse.BodyHandlers.ofString());
        assertTrue(resPing.body().contains("PONG"));
    }

    @Test
    @DisplayName("5. Resource Limits: Oversized keys, values, and collections are rejected")
    void testResourceLimits() throws Exception {
        String session = UUID.randomUUID().toString();

        // Key > 64 bytes
        String hugeKey = "k".repeat(65);
        HttpRequest reqHugeKey = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", session)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("SET " + hugeKey + " val"))
                .build();
        HttpResponse<String> resHugeKey = client.send(reqHugeKey, HttpResponse.BodyHandlers.ofString());
        assertTrue(resHugeKey.body().contains("exceeds maximum length of 64 bytes"));

        // Value > 2048 bytes
        String hugeVal = "v".repeat(2049);
        HttpRequest reqHugeVal = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", session)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("SET valid_key " + hugeVal))
                .build();
        HttpResponse<String> resHugeVal = client.send(reqHugeVal, HttpResponse.BodyHandlers.ofString());
        assertTrue(resHugeVal.body().contains("exceeds maximum allowed size of 2048 bytes"));

        // Collection > 50 items
        StringBuilder sb = new StringBuilder("RPUSH mylist");
        for (int i = 0; i < 55; i++) {
            sb.append(" item").append(i);
        }
        HttpRequest reqHugeList = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", session)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                .build();
        HttpResponse<String> resHugeList = client.send(reqHugeList, HttpResponse.BodyHandlers.ofString());
        assertTrue(resHugeList.body().contains("maximum 50 items allowed per operation"));
    }

    @Test
    @DisplayName("6. Request body size limit: Payload exceeding 16 KB returns HTTP 413")
    void testPayloadSizeLimit() throws Exception {
        String session = UUID.randomUUID().toString();
        byte[] payload17k = new byte[17 * 1024];

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", session)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload17k))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(413, res.statusCode(), "Payload > 16 KB must return HTTP 413");
    }

    @Test
    @DisplayName("7. Session cleanup: Abandoned sessions older than 30 minutes are safely reaped")
    void testSessionCleanup() throws Exception {
        String abandonedSession = UUID.randomUUID().toString();
        String activeSession = UUID.randomUUID().toString();

        // Populate keys for abandoned session and active session
        storage.set(DemoSecurityContext.toInternalKey(abandonedSession, "abandoned_key"), "some_old_data");
        storage.set(DemoSecurityContext.toInternalKey(activeSession, "active_key"), "fresh_data");

        // Touch active session now
        demoSecurity.touchSession(activeSession);

        // Simulate abandoned session touched 35 minutes ago using reflection on activeSessions
        Field sessionsField = DemoSecurityContext.class.getDeclaredField("activeSessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> activeSessions = (ConcurrentHashMap<String, Long>) sessionsField.get(demoSecurity);
        activeSessions.put(abandonedSession, System.currentTimeMillis() - (35L * 60L * 1000L));

        // Trigger cleanup
        demoSecurity.cleanupExpiredSessions();

        // Verify abandoned session key was deleted
        assertNull(storage.get(DemoSecurityContext.toInternalKey(abandonedSession, "abandoned_key")),
                "Abandoned session keys must be reaped");

        // Verify active session key is intact
        assertNotNull(storage.get(DemoSecurityContext.toInternalKey(activeSession, "active_key")),
                "Active session keys must NOT be deleted");

        // Verify system demo keys are completely untouched
        assertNotNull(storage.get("demo:welcome"), "System demo:welcome must remain untouched");
        assertNotNull(storage.get("demo:pipeline"), "System demo:pipeline must remain untouched");
    }

    @Test
    @DisplayName("8. Multi-key operations (MGET, MSET, DEL, KEYS) cannot cross visitor namespaces")
    void testMultiKeyNamespaceIsolation() throws Exception {
        String sessionA = UUID.randomUUID().toString();
        String sessionB = UUID.randomUUID().toString();

        // Session A sets mkey1 and mkey2
        HttpRequest reqMsetA = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", sessionA)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("MSET mkey1 alpha mkey2 bravo"))
                .build();
        HttpResponse<String> resMsetA = client.send(reqMsetA, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resMsetA.statusCode());

        // Session B sets mkey1 and mkey3
        HttpRequest reqMsetB = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", sessionB)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("MSET mkey1 charlie mkey3 delta"))
                .build();
        HttpResponse<String> resMsetB = client.send(reqMsetB, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resMsetB.statusCode());

        // Session A calls MGET mkey1 mkey2 mkey3 -> must get alpha, bravo, and nil (cannot see charlie or delta)
        HttpRequest reqMgetA = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", sessionA)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("MGET mkey1 mkey2 mkey3"))
                .build();
        HttpResponse<String> resMgetA = client.send(reqMgetA, HttpResponse.BodyHandlers.ofString());
        assertTrue(resMgetA.body().contains("alpha"));
        assertTrue(resMgetA.body().contains("bravo"));
        assertTrue(resMgetA.body().contains("(nil)"));
        assertFalse(resMgetA.body().contains("charlie"));
        assertFalse(resMgetA.body().contains("delta"));

        // Session A calls KEYS mkey* -> returns mkey1, mkey2 (never mkey3 from session B)
        HttpRequest reqKeysA = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", sessionA)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("KEYS mkey*"))
                .build();
        HttpResponse<String> resKeysA = client.send(reqKeysA, HttpResponse.BodyHandlers.ofString());
        assertTrue(resKeysA.body().contains("mkey1"));
        assertTrue(resKeysA.body().contains("mkey2"));
        assertFalse(resKeysA.body().contains("mkey3"));

        // Multi-key immutable demo key protection: DEL mkey1 demo:welcome must be rejected
        HttpRequest reqDelMulti = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", sessionA)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("DEL mkey1 demo:welcome"))
                .build();
        HttpResponse<String> resDelMulti = client.send(reqDelMulti, HttpResponse.BodyHandlers.ofString());
        assertTrue(resDelMulti.body().contains("immutable system demo key"));
        // Verify demo:welcome was not deleted
        assertNotNull(storage.get("demo:welcome"));

        // Multi-key immutable demo key protection: MSET mkey1 newVal demo:server hacked must be rejected
        HttpRequest reqMsetDemo = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("X-Session-ID", sessionA)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("MSET mkey1 newVal demo:server hacked"))
                .build();
        HttpResponse<String> resMsetDemo = client.send(reqMsetDemo, HttpResponse.BodyHandlers.ofString());
        assertTrue(resMsetDemo.body().contains("immutable system demo key"));
    }

    @Test
    @DisplayName("9. Dual-tier rate limiting: Rotating session IDs cannot bypass the physical IP rate limit")
    void testDualTierRateLimitingPreventsSessionRotationBypass() {
        // Create an isolated limiter: burst 5, refill 0.1/sec
        ApiRateLimiter limiter = new ApiRateLimiter(5, 0.1);
        String ip = "192.168.1.50";

        // Consume 10 allowed requests (minimum burst capacity is 10) using different session IDs from the SAME IP
        for (int i = 0; i < 10; i++) {
            String rotatedSessionId = UUID.randomUUID().toString();
            assertTrue(limiter.allowClient(ip, rotatedSessionId),
                    "Request " + i + " within IP burst capacity should be allowed");
        }

        // 11th request with a brand new, unused session ID MUST be blocked because IP tokens are exhausted
        String brandNewSessionId = UUID.randomUUID().toString();
        assertFalse(limiter.allowClient(ip, brandNewSessionId),
                "New session ID must be rejected once the IP limit has been reached");
    }
}
