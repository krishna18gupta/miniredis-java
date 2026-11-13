package com.miniredis.web;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.storage.RedisDataType;
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

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MiniRedis Web Console HTTP Integration Tests")
class MiniRedisIntegrationTest {

    private static final int TEST_PORT = 8099;
    private static StorageEngine storage;
    private static CommandRegistry registry;
    private static MiniRedisWebServer server;
    private static HttpClient client;

    @BeforeAll
    static void startServer() throws IOException {
        storage = new StorageEngine();
        registry = new CommandRegistry();
        server = new MiniRedisWebServer(TEST_PORT, storage, registry);
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
    @DisplayName("HTTP /api/exec routes commands directly to Java StorageEngine")
    void testHttpExecutionMutatesJavaStorage() throws Exception {
        // 1. Execute SET via HTTP POST
        HttpRequest setReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("SET api:user Krishna"))
                .build();

        HttpResponse<String> setRes = client.send(setReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, setRes.statusCode());
        assertTrue(setRes.body().contains("\"status\":\"ok\""));

        // Verify Java StorageEngine directly in memory
        assertNotNull(storage.get("api:user"));
        assertEquals("Krishna", storage.get("api:user").getAsString());

        // 2. Execute LPUSH via HTTP POST
        HttpRequest lpushReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("LPUSH api:list task1 task2"))
                .build();

        HttpResponse<String> lpushRes = client.send(lpushReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, lpushRes.statusCode());
        assertEquals(RedisDataType.LIST, storage.get("api:list").getType());
        assertEquals(2, storage.llen("api:list"));

        // 3. Execute GET /api/state to verify web console payload matches JVM RAM
        HttpRequest stateReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/state"))
                .GET()
                .build();

        HttpResponse<String> stateRes = client.send(stateReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stateRes.statusCode());
        String body = stateRes.body();
        assertTrue(body.contains("\"key\":\"api:user\""));
        assertTrue(body.contains("\"type\":\"string\""));
        assertTrue(body.contains("\"key\":\"api:list\""));
        assertTrue(body.contains("\"type\":\"list\""));
    }

    @Test
    @DisplayName("HTTP /api/exec returns formatted WRONGTYPE error for illegal operations")
    void testHttpWrongTypeError() throws Exception {
        storage.set("strKey", "stringValue");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/exec"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("LPUSH strKey invalidItem"))
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"status\":\"error\""));
        assertTrue(res.body().contains("WRONGTYPE"));
    }
}