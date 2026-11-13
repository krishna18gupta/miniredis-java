package com.miniredis.server;

import com.miniredis.client.RedisClient;
import com.miniredis.commands.CommandRegistry;
import com.miniredis.storage.StorageEngine;
import com.miniredis.web.MiniRedisWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Phase4NetworkingTest {

    private StorageEngine storage;
    private CommandRegistry registry;
    private RedisServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        storage = new StorageEngine();
        registry = new CommandRegistry();
        // Dynamic port 0 to prevent conflicts
        server = new RedisServer(0, 50, storage, registry);
        server.start();
        port = server.getPort();
        assertTrue(port > 0, "Server should be bound to an ephemeral port > 0");
        assertTrue(server.isRunning(), "Server should be running");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testPingNoArgs() throws IOException {
        try (RedisClient client = new RedisClient("127.0.0.1", port)) {
            client.connect();
            String response = client.sendCommand("PING");
            assertEquals("PONG", response);
        }
    }

    @Test
    void testPingWithMessage() throws IOException {
        try (RedisClient client = new RedisClient("127.0.0.1", port)) {
            client.connect();
            String response = client.sendCommand("PING \"Hello MiniRedis!\"");
            assertEquals("Hello MiniRedis!", response);
        }
    }

    @Test
    void testPingWrongArity() throws IOException {
        try (RedisClient client = new RedisClient("127.0.0.1", port)) {
            client.connect();
            String response = client.sendCommand("PING first second");
            assertEquals("(error) ERR wrong number of arguments for 'ping' command", response);
        }
    }

    @Test
    void testBasicKeyValueOverTcp() throws IOException {
        try (RedisClient client = new RedisClient("127.0.0.1", port)) {
            client.connect();

            assertEquals("OK", client.sendCommand("SET message \"Hello TCP\""));
            assertEquals("\"Hello TCP\"", client.sendCommand("GET message"));
            assertEquals("(integer) 1", client.sendCommand("EXISTS message"));
            assertEquals("(integer) 1", client.sendCommand("DEL message"));
            assertEquals("(nil)", client.sendCommand("GET message"));
            assertEquals("(integer) 0", client.sendCommand("EXISTS message"));
        }
    }

    @Test
    void testDataStructuresOverTcp() throws IOException {
        try (RedisClient client = new RedisClient("127.0.0.1", port)) {
            client.connect();

            // List test
            assertEquals("(integer) 3", client.sendCommand("LPUSH mylist a b c"));
            assertEquals("\"c\"", client.sendCommand("LPOP mylist"));
            assertEquals("(integer) 2", client.sendCommand("LLEN mylist"));

            // Set test
            assertEquals("(integer) 2", client.sendCommand("SADD myset apple banana"));
            assertEquals("(integer) 1", client.sendCommand("SISMEMBER myset apple"));
            assertEquals("(integer) 0", client.sendCommand("SISMEMBER myset orange"));
            assertEquals("(integer) 2", client.sendCommand("SCARD myset"));

            // Hash test
            assertEquals("(integer) 2", client.sendCommand("HSET user:1 name Alice age 28"));
            assertEquals("\"Alice\"", client.sendCommand("HGET user:1 name"));
            assertEquals("\"28\"", client.sendCommand("HGET user:1 age"));
            assertEquals("(integer) 1", client.sendCommand("HEXISTS user:1 name"));
        }
    }

    @Test
    void testExpirationOverTcp() throws IOException {
        try (RedisClient client = new RedisClient("127.0.0.1", port)) {
            client.connect();

            assertEquals("OK", client.sendCommand("SETEX temporary:key 30 \"still_alive\""));
            String ttlResp = client.sendCommand("TTL temporary:key");
            assertTrue(ttlResp.startsWith("(integer) ") && !ttlResp.equals("(integer) -1") && !ttlResp.equals("(integer) -2"),
                    "TTL should be positive: " + ttlResp);

            assertEquals("(integer) 1", client.sendCommand("PERSIST temporary:key"));
            assertEquals("(integer) -1", client.sendCommand("TTL temporary:key"));
            assertEquals("\"still_alive\"", client.sendCommand("GET temporary:key"));
        }
    }

    @Test
    void testSharedStateTcpAndWebConsole() throws Exception {
        // Start web console on port 0 sharing the exact same storage and registry
        MiniRedisWebServer webServer = new MiniRedisWebServer(0, storage, registry);
        webServer.start();
        int webPort = webServer.getPort();
        assertTrue(webPort > 0, "Web server should be bound to port > 0");

        try {
            HttpClient httpClient = HttpClient.newHttpClient();

            // 1. Write via TCP client
            try (RedisClient tcpClient = new RedisClient("127.0.0.1", port)) {
                tcpClient.connect();
                assertEquals("OK", tcpClient.sendCommand("SET shared:cross:key \"written_via_tcp\""));
            }

            // 2. Read via Web Console HTTP API (/api/exec)
            HttpRequest req1 = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + webPort + "/api/exec"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("GET shared:cross:key"))
                    .build();
            HttpResponse<String> resp1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp1.statusCode());
            assertTrue(resp1.body().contains("written_via_tcp"),
                    "HTTP console must see key written via TCP. Body was: " + resp1.body());

            // 3. Write via Web Console HTTP API (/api/exec)
            HttpRequest req2 = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + webPort + "/api/exec"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("SET shared:cross:key \"updated_via_http\""))
                    .build();
            HttpResponse<String> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp2.statusCode());

            // 4. Read via TCP client
            try (RedisClient tcpClient = new RedisClient("127.0.0.1", port)) {
                tcpClient.connect();
                assertEquals("\"updated_via_http\"", tcpClient.sendCommand("GET shared:cross:key"));
            }
        } finally {
            webServer.stop();
        }
    }

    @Test
    void testConcurrentClients() throws Exception {
        int clientCount = 10;
        int incrementsPerClient = 10;
        CountDownLatch latch = new CountDownLatch(clientCount);
        AtomicInteger failures = new AtomicInteger(0);

        ExecutorService testExecutor = Executors.newFixedThreadPool(clientCount);
        for (int i = 0; i < clientCount; i++) {
            testExecutor.submit(() -> {
                try (RedisClient client = new RedisClient("127.0.0.1", port)) {
                    client.connect();
                    for (int j = 0; j < incrementsPerClient; j++) {
                        String resp = client.sendCommand("INCR concurrent_counter");
                        if (!resp.startsWith("(integer) ")) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent clients should finish within 10 seconds");
        testExecutor.shutdown();
        assertEquals(0, failures.get(), "No client operations should have failed");

        try (RedisClient client = new RedisClient("127.0.0.1", port)) {
            client.connect();
            assertEquals("\"100\"", client.sendCommand("GET concurrent_counter"));
        }
    }

    @Test
    void testClientDisconnectAndReconnect() throws IOException {
        // First connection sets value
        try (RedisClient client1 = new RedisClient("127.0.0.1", port)) {
            client1.connect();
            assertEquals("OK", client1.sendCommand("SET persistent:user \"Alice\""));
            client1.send("QUIT");
            assertEquals("OK", client1.readLine());
        }

        // Second connection verifies value persists
        try (RedisClient client2 = new RedisClient("127.0.0.1", port)) {
            client2.connect();
            assertEquals("\"Alice\"", client2.sendCommand("GET persistent:user"));
        }
    }

    @Test
    void testMalformedInputAndEmptyLines() throws IOException {
        try (RedisClient client = new RedisClient("127.0.0.1", port)) {
            client.connect();

            // Send empty line - should be skipped/ignored without server crashing
            client.send("");
            // Send unknown command
            String errResp = client.sendCommand("NONEXISTENTCOMMAND foo bar");
            assertTrue(errResp.startsWith("(error) ERR unknown command"), "Expected error response: " + errResp);

            // Follow-up valid command works normally
            assertEquals("PONG", client.sendCommand("PING"));
        }
    }

    @Test
    void testAbruptClientDisconnect() throws IOException, InterruptedException {
        // Open connection and close abruptly without QUIT
        RedisClient abruptClient = new RedisClient("127.0.0.1", port);
        abruptClient.connect();
        assertEquals("PONG", abruptClient.sendCommand("PING"));
        abruptClient.close(); // Abrupt socket closure

        // Allow worker to observe EOF / socket closure
        Thread.sleep(100);

        // Subsequent client should connect and operate cleanly
        try (RedisClient healthyClient = new RedisClient("127.0.0.1", port)) {
            healthyClient.connect();
            assertEquals("PONG", healthyClient.sendCommand("PING"));
        }
    }

    @Test
    void testServerLifecycleStopAndRestart() throws IOException {
        // Verify current server works
        try (RedisClient client = new RedisClient("127.0.0.1", port)) {
            client.connect();
            assertEquals("PONG", client.sendCommand("PING"));
        }

        // Stop server
        server.stop();
        assertFalse(server.isRunning(), "Server should report running = false");

        // Verify connection fails on stopped port
        assertThrows(IOException.class, () -> {
            try (RedisClient client = new RedisClient("127.0.0.1", port)) {
                client.connect();
                client.sendCommand("PING");
            }
        });

        // Start new server on dynamic port 0
        RedisServer newServer = new RedisServer(0, storage, registry);
        newServer.start();
        int newPort = newServer.getPort();
        assertTrue(newPort > 0);
        assertTrue(newServer.isRunning());

        try (RedisClient client = new RedisClient("127.0.0.1", newPort)) {
            client.connect();
            assertEquals("PONG", client.sendCommand("PING"));
        } finally {
            newServer.stop();
        }
    }

    @Test
    void testRespWireProtocolOverTcpSocket() throws IOException {
        try (java.net.Socket sock = new java.net.Socket("127.0.0.1", port);
             java.io.OutputStream out = sock.getOutputStream();
             java.io.InputStream in = sock.getInputStream()) {

            byte[] cmd = "*3\r\n$3\r\nSET\r\n$7\r\nrespkey\r\n$7\r\nrespval\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(cmd);
            out.flush();

            byte[] buf = new byte[32];
            int read = in.read(buf);
            assertTrue(read > 0);
            String resp = new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("+OK\r\n", resp);

            byte[] getCmd = "*2\r\n$3\r\nGET\r\n$7\r\nrespkey\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(getCmd);
            out.flush();

            read = in.read(buf);
            assertTrue(read > 0);
            String getResp = new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("$7\r\nrespval\r\n", getResp);
        }
    }
}
