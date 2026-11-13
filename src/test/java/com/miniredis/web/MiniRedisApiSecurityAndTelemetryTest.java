package com.miniredis.web;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MiniRedis REST API, Security, Telemetry & Demo Mode Tests")
class MiniRedisApiSecurityAndTelemetryTest {

    private static final int API_TEST_PORT = 8101;
    private static final String ADMIN_TOKEN = "superSecretAdminToken123";
    private static StorageEngine storage;
    private static CommandRegistry registry;
    private static MiniRedisWebServer server;
    private static HttpClient client;

    @BeforeAll
    static void startServer() throws IOException {
        storage = new StorageEngine();
        registry = new CommandRegistry();
        // Start server in demoMode=true with admin token configured
        server = new MiniRedisWebServer(API_TEST_PORT, storage, registry, null, true, ADMIN_TOKEN);
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("GET /api/health returns 200 with service version, uptime and status UP")
    void testHealthEndpoint() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + API_TEST_PORT + "/api/health"))
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        String body = res.body();
        assertTrue(body.contains("\"status\":\"UP\""));
        assertTrue(body.contains("\"service\":\"MiniRedis\""));
        assertTrue(body.contains("\"version\":\"1.0.0\""));
        assertTrue(body.contains("\"uptimeSeconds\""));
        assertTrue(body.contains("\"demoMode\":true"));
    }

    @Test
    @DisplayName("GET /api/info returns full keyspace, memory, operations, AOF, and rolling telemetry")
    void testInfoEndpoint() throws Exception {
        storage.set("info:k1", "val1");
        storage.set("info:k2", "val2");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + API_TEST_PORT + "/api/info"))
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        String body = res.body();
        assertTrue(body.contains("\"status\":\"ok\""));
        assertTrue(body.contains("\"keyspace\""));
        assertTrue(body.contains("\"totalKeys\""));
        assertTrue(body.contains("\"memory\""));
        assertTrue(body.contains("\"jvmUsedBytes\""));
        assertTrue(body.contains("\"operations\""));
        assertTrue(body.contains("\"stripedLockCount\":256"));
        assertTrue(body.contains("\"telemetry\""));
    }

    @Test
    @DisplayName("GET /api/keys lists keys with pattern, type filtering, and pagination")
    void testKeysExplorationAndFiltering() throws Exception {
        storage.set("user:101", "Alice");
        storage.set("user:102", "Bob");
        storage.lpush("tasks:urgent", List.of("deploy", "verify"));

        // 1. Pattern filter for user:*
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + API_TEST_PORT + "/api/keys?pattern=user:*"))
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        String body = res.body();
        assertTrue(body.contains("\"key\":\"user:101\""));
        assertTrue(body.contains("\"key\":\"user:102\""));
        assertFalse(body.contains("\"key\":\"tasks:urgent\""));

        // 2. Type filter for list only
        HttpRequest reqType = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + API_TEST_PORT + "/api/keys?type=list"))
                .GET()
                .build();

        HttpResponse<String> resType = client.send(reqType, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resType.statusCode());
        String bodyType = resType.body();
        assertTrue(bodyType.contains("\"key\":\"tasks:urgent\""));
        assertFalse(bodyType.contains("\"key\":\"user:101\""));
    }

    @Test
    @DisplayName("GET /api/keys?key=... returns structured key inspection")
    void testKeyInspectionDetails() throws Exception {
        storage.set("config:theme", "dark");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + API_TEST_PORT + "/api/keys?key=config:theme"))
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        String body = res.body();
        assertTrue(body.contains("\"status\":\"ok\""));
        assertTrue(body.contains("\"key\":\"config:theme\""));
        assertTrue(body.contains("\"type\":\"string\""));
        assertTrue(body.contains("\"value\":\"dark\""));
    }

    @Test
    @DisplayName("DELETE /api/keys deletes key or returns 404 when missing")
    void testDeleteKeyEndpoint() throws Exception {
        storage.set("toDelete", "temp");

        HttpRequest delReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + API_TEST_PORT + "/api/keys?key=toDelete"))
                .DELETE()
                .build();

        HttpResponse<String> delRes = client.send(delReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, delRes.statusCode());
        assertTrue(delRes.body().contains("\"deleted\":true"));
        assertNull(storage.get("toDelete"));

        // Subsequent delete of same key returns 404
        HttpResponse<String> delRes2 = client.send(delReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, delRes2.statusCode());
    }

    @Test
    @DisplayName("Public Demo Mode blocks destructive commands (SHUTDOWN, FLUSHDB, BGREWRITEAOF)")
    void testDemoModeBlocksDestructiveCommands() throws Exception {
        // 1. Attempt SHUTDOWN in demo mode
        HttpRequest shutdownReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + API_TEST_PORT + "/api/exec"))
                .POST(HttpRequest.BodyPublishers.ofString("SHUTDOWN"))
                .build();

        HttpResponse<String> shutdownRes = client.send(shutdownReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, shutdownRes.statusCode());
        assertTrue(shutdownRes.body().contains("disabled in public demo mode"));

        // 2. Attempt FLUSHDB without token
        HttpRequest flushReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + API_TEST_PORT + "/api/exec"))
                .POST(HttpRequest.BodyPublishers.ofString("FLUSHDB"))
                .build();

        HttpResponse<String> flushRes = client.send(flushReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, flushRes.statusCode());
        assertTrue(flushRes.body().contains("disabled in public demo mode"));

        // 3. Attempt FLUSHDB WITH Admin Token -> authorized!
        HttpRequest adminFlushReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + API_TEST_PORT + "/api/exec"))
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString("FLUSHDB"))
                .build();

        HttpResponse<String> adminFlushRes = client.send(adminFlushReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, adminFlushRes.statusCode());
        assertTrue(adminFlushRes.body().contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("CORS headers and OPTIONS preflight respond correctly")
    void testCorsHeadersAndOptions() throws Exception {
        HttpRequest optionsReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + API_TEST_PORT + "/api/info"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<Void> optionsRes = client.send(optionsReq, HttpResponse.BodyHandlers.discarding());
        assertEquals(204, optionsRes.statusCode());
        assertEquals("*", optionsRes.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertTrue(optionsRes.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("GET"));
        assertTrue(optionsRes.headers().firstValue("Access-Control-Allow-Headers").orElse("").contains("Authorization"));
    }
}
