package com.miniredis.integration;

import com.miniredis.aof.AofManager;
import com.miniredis.aof.FsyncPolicy;
import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.protocol.RespParser;
import com.miniredis.protocol.RespSerializer;
import com.miniredis.server.RedisServer;
import com.miniredis.storage.ExpirationManager;
import com.miniredis.storage.StorageEngine;
import com.miniredis.web.MiniRedisWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 9: End-to-End Integration, Reliability, Malformed RESP2 & Shutdown Tests")
class Phase9IntegrationAndReliabilityTest {

    private static RedisResponse sendResp(OutputStream out, InputStream in, String... tokens) throws IOException {
        out.write(RespSerializer.encodeCommand(List.of(tokens)));
        out.flush();
        return RespParser.parseResponse(in);
    }

    @Test
    @DisplayName("End-to-end TCP RESP2 commands, pipelining, CLIENT LIST telemetry, and live INFO/HTTP metrics")
    void testEndToEndTcpAndTelemetry(@TempDir Path tempDir) throws Exception {
        File aofFile = tempDir.resolve("e2e.aof").toFile();
        StorageEngine storage = new StorageEngine();
        CommandRegistry registry = new CommandRegistry();
        AofManager aof = new AofManager(aofFile, FsyncPolicy.EVERYSEC, storage, registry);
        storage.setAofManager(aof);
        aof.start();

        ExpirationManager expManager = new ExpirationManager(storage);
        expManager.start();

        RedisServer tcpServer = new RedisServer(0, 50, storage, registry);
        tcpServer.start();
        int tcpPort = tcpServer.getPort();

        MiniRedisWebServer webServer = new MiniRedisWebServer(0, storage, registry, tcpServer);
        webServer.start();
        int webPort = webServer.getPort();

        try (Socket sock1 = new Socket("127.0.0.1", tcpPort);
             Socket sock2 = new Socket("127.0.0.1", tcpPort)) {
            sock1.setSoTimeout(3000);
            sock2.setSoTimeout(3000);

            OutputStream out1 = new BufferedOutputStream(sock1.getOutputStream());
            InputStream in1 = new BufferedInputStream(sock1.getInputStream());
            OutputStream out2 = new BufferedOutputStream(sock2.getOutputStream());
            InputStream in2 = new BufferedInputStream(sock2.getInputStream());

            assertEquals("OK", sendResp(out1, in1, "CLIENT", "SETNAME", "alpha-client").getStringValue());
            assertEquals("OK", sendResp(out2, in2, "CLIENT", "SETNAME", "beta-client").getStringValue());
            assertEquals("alpha-client", sendResp(out1, in1, "CLIENT", "GETNAME").getStringValue());
            assertEquals("beta-client", sendResp(out2, in2, "CLIENT", "GETNAME").getStringValue());

            // Put sock2 inside MULTI and check CLIENT LIST shows multi=1 on beta-client and multi=-1 on alpha-client
            assertEquals("OK", sendResp(out2, in2, "MULTI").getStringValue());
            assertEquals("QUEUED", sendResp(out2, in2, "SET", "k:pipe", "from-beta").getStringValue());

            String clientList = sendResp(out1, in1, "CLIENT", "LIST").getStringValue();
            assertTrue(clientList.contains("name=alpha-client"));
            assertTrue(clientList.contains("name=beta-client"));
            assertTrue(clientList.contains("multi=-1"));
            assertTrue(clientList.contains("multi=1"));

            RedisResponse betaExec = sendResp(out2, in2, "EXEC");
            assertEquals("OK", betaExec.getArrayValue().get(0).getStringValue());

            // Pipelined batch over sock1
            StringBuilder pipeline = new StringBuilder();
            pipeline.append(new String(RespSerializer.encodeCommand(List.of("SET", "e2e:str", "hello")), StandardCharsets.UTF_8));
            pipeline.append(new String(RespSerializer.encodeCommand(List.of("TYPE", "e2e:str")), StandardCharsets.UTF_8));
            pipeline.append(new String(RespSerializer.encodeCommand(List.of("INCRBY", "e2e:cnt", "12")), StandardCharsets.UTF_8));
            pipeline.append(new String(RespSerializer.encodeCommand(List.of("DECRBY", "e2e:cnt", "5")), StandardCharsets.UTF_8));
            pipeline.append(new String(RespSerializer.encodeCommand(List.of("GET", "e2e:str")), StandardCharsets.UTF_8));
            pipeline.append(new String(RespSerializer.encodeCommand(List.of("GET", "e2e:miss")), StandardCharsets.UTF_8));
            out1.write(pipeline.toString().getBytes(StandardCharsets.UTF_8));
            out1.flush();

            assertEquals("OK", RespParser.parseResponse(in1).getStringValue());
            assertEquals("string", RespParser.parseResponse(in1).getStringValue());
            assertEquals(12L, RespParser.parseResponse(in1).getIntValue());
            assertEquals(7L, RespParser.parseResponse(in1).getIntValue());
            assertEquals("hello", RespParser.parseResponse(in1).getStringValue());
            assertEquals(RedisResponse.ResponseType.NIL, RespParser.parseResponse(in1).getType());

            String infoStats = sendResp(out1, in1, "INFO", "stats").getStringValue();
            assertTrue(infoStats.contains("total_connections_received:2"));
            assertTrue(infoStats.contains("keyspace_hits:1"));
            assertTrue(infoStats.contains("keyspace_misses:1"));

            // Web console /api/state exposes new telemetry fields while preserving existing schema
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> state = http.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + webPort + "/api/state")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, state.statusCode());
            assertTrue(state.body().contains("\"totalConnectionsReceived\":2"));
            assertTrue(state.body().contains("\"keyspaceHits\":1"));
            assertTrue(state.body().contains("\"keyspaceMisses\":1"));
            assertTrue(state.body().contains("\"estimatedMemoryBytes\":"));
        } finally {
            expManager.stop();
            tcpServer.stop();
            webServer.stop();
            aof.close();
        }
    }

    @Test
    @DisplayName("Malformed RESP2 protocol inputs return explicit -ERR Protocol error without crashing healthy clients")
    void testMalformedProtocolInputsOverLiveTcp() throws Exception {
        StorageEngine storage = new StorageEngine();
        CommandRegistry registry = new CommandRegistry();
        RedisServer server = new RedisServer(0, 20, storage, registry);
        server.start();
        int port = server.getPort();

        List<String> malformedPayloads = List.of(
                "*notanumber\r\n",
                "*-5\r\n",
                "*2000000\r\n",
                "*1\r\n$-9\r\n",
                "*1\r\n$3\r\nabcXX"
        );

        try {
            for (String badPayload : malformedPayloads) {
                try (Socket badSock = new Socket("127.0.0.1", port)) {
                    badSock.setSoTimeout(3000);
                    OutputStream out = badSock.getOutputStream();
                    InputStream in = badSock.getInputStream();
                    out.write(badPayload.getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    RedisResponse resp = RespParser.parseResponse(in);
                    assertNotNull(resp, "Expected protocol error response for payload: " + badPayload);
                    assertEquals(RedisResponse.ResponseType.ERROR, resp.getType());
                    assertTrue(resp.getStringValue().startsWith("ERR Protocol error:"),
                            "Unexpected error text: " + resp.getStringValue());
                }
            }

            // Healthy client must operate normally after all malformed connections
            try (Socket goodSock = new Socket("127.0.0.1", port)) {
                goodSock.setSoTimeout(3000);
                OutputStream out = new BufferedOutputStream(goodSock.getOutputStream());
                InputStream in = new BufferedInputStream(goodSock.getInputStream());
                assertEquals("PONG", sendResp(out, in, "PING").getStringValue());
                assertEquals("OK", sendResp(out, in, "SET", "healthy", "yes").getStringValue());
                assertEquals("yes", sendResp(out, in, "GET", "healthy").getStringValue());
            }
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("Multi-client concurrent TCP transactions with WATCH/MULTI/EXEC and EXECABORT")
    void testConcurrentTcpTransactions(@TempDir Path tempDir) throws Exception {
        File aofFile = tempDir.resolve("concurrent-tx.aof").toFile();
        StorageEngine storage = new StorageEngine();
        CommandRegistry registry = new CommandRegistry();
        AofManager aof = new AofManager(aofFile, FsyncPolicy.EVERYSEC, storage, registry);
        storage.setAofManager(aof);
        aof.start();

        RedisServer server = new RedisServer(0, 50, storage, registry);
        server.start();
        int port = server.getPort();

        try {
            try (Socket setup = new Socket("127.0.0.1", port)) {
                OutputStream out = new BufferedOutputStream(setup.getOutputStream());
                InputStream in = new BufferedInputStream(setup.getInputStream());
                assertEquals("OK", sendResp(out, in, "SET", "pool:a", "500").getStringValue());
                assertEquals("OK", sendResp(out, in, "SET", "pool:b", "500").getStringValue());

                // Verify EXECABORT over real TCP socket
                assertEquals("OK", sendResp(out, in, "MULTI").getStringValue());
                assertEquals("QUEUED", sendResp(out, in, "SET", "never:written", "1").getStringValue());
                assertEquals(RedisResponse.ResponseType.ERROR,
                        sendResp(out, in, "BOGUSCMD", "1").getType());
                RedisResponse abortResp = sendResp(out, in, "EXEC");
                assertEquals(RedisResponse.ResponseType.ERROR, abortResp.getType());
                assertTrue(abortResp.getStringValue().startsWith("EXECABORT"));
                assertNull(storage.get("never:written"));
            }

            int clients = 6;
            int txPerClient = 25;
            ExecutorService pool = Executors.newFixedThreadPool(clients);
            CountDownLatch startGate = new CountDownLatch(1);
            AtomicInteger committed = new AtomicInteger(0);
            List<Future<?>> futures = new ArrayList<>();

            for (int c = 0; c < clients; c++) {
                final boolean dir = (c % 2 == 0);
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try (Socket s = new Socket("127.0.0.1", port)) {
                        s.setSoTimeout(5000);
                        OutputStream out = new BufferedOutputStream(s.getOutputStream());
                        InputStream in = new BufferedInputStream(s.getInputStream());

                        for (int i = 0; i < txPerClient; i++) {
                            while (true) {
                                assertEquals("OK", sendResp(out, in, "WATCH", "pool:a", "pool:b").getStringValue());
                                assertEquals("OK", sendResp(out, in, "MULTI").getStringValue());
                                assertEquals("QUEUED", sendResp(out, in,
                                        dir ? "DECRBY" : "INCRBY", "pool:a", "1").getStringValue());
                                assertEquals("QUEUED", sendResp(out, in,
                                        dir ? "INCRBY" : "DECRBY", "pool:b", "1").getStringValue());
                                RedisResponse exec = sendResp(out, in, "EXEC");
                                if (exec.getType() == RedisResponse.ResponseType.ARRAY && exec.getArrayValue() != null) {
                                    committed.incrementAndGet();
                                    break;
                                }
                            }
                        }
                    }
                    return null;
                }));
            }

            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
            pool.shutdown();

            assertEquals(clients * txPerClient, committed.get());
            long a = Long.parseLong(storage.get("pool:a").getAsString());
            long b = Long.parseLong(storage.get("pool:b").getAsString());
            assertEquals(1000L, a + b);
        } finally {
            server.stop();
            aof.close();
        }
    }

    @Test
    @DisplayName("Full-stack crash recovery and graceful SHUTDOWN SAVE / restart lifecycle")
    void testCrashRecoveryAndGracefulShutdown(@TempDir Path tempDir) throws Exception {
        File aofFile = tempDir.resolve("lifecycle.aof").toFile();

        // Boot 1: Execute commands + transaction, save, then simulate abrupt crash with trailing incomplete MULTI
        StorageEngine s1 = new StorageEngine();
        CommandRegistry r1 = new CommandRegistry();
        AofManager aof1 = new AofManager(aofFile, FsyncPolicy.ALWAYS, s1, r1);
        s1.setAofManager(aof1);
        aof1.start();
        RedisServer tcp1 = new RedisServer(0, 20, s1, r1);
        tcp1.start();

        try (Socket sock = new Socket("127.0.0.1", tcp1.getPort())) {
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());
            InputStream in = new BufferedInputStream(sock.getInputStream());
            assertEquals("OK", sendResp(out, in, "SET", "persist:k", "durable").getStringValue());
            assertEquals("OK", sendResp(out, in, "SETEX", "ttl:k", "120", "temp").getStringValue());
            assertEquals("OK", sendResp(out, in, "MULTI").getStringValue());
            assertEquals("QUEUED", sendResp(out, in, "INCRBY", "tx:sum", "42").getStringValue());
            assertEquals(42L, sendResp(out, in, "EXEC").getArrayValue().get(0).getIntValue());
            assertEquals("OK", sendResp(out, in, "RENAME", "persist:k", "renamed:k").getStringValue());
        } finally {
            tcp1.stop();
            aof1.close();
        }

        // Corrupt tail with incomplete transaction + half frame simulating sudden power loss
        try (FileOutputStream fos = new FileOutputStream(aofFile, true)) {
            fos.write(RespSerializer.encodeCommand(List.of("MULTI")));
            fos.write(RespSerializer.encodeCommand(List.of("SET", "ghost:tx", "bad")));
            fos.write("*3\r\n$3\r\nSET\r\n$5\r\ntrunc".getBytes(StandardCharsets.UTF_8));
        }

        // Boot 2: Full startup sequence -> recover -> expiration -> TCP + Web -> SHUTDOWN SAVE
        StorageEngine s2 = new StorageEngine();
        CommandRegistry r2 = new CommandRegistry();
        AofManager aof2 = new AofManager(aofFile, FsyncPolicy.EVERYSEC, s2, r2);
        s2.setAofManager(aof2);
        aof2.recover();
        aof2.start();

        ExpirationManager exp2 = new ExpirationManager(s2);
        exp2.start();
        RedisServer tcp2 = new RedisServer(0, 20, s2, r2);
        tcp2.start();
        MiniRedisWebServer web2 = new MiniRedisWebServer(0, s2, r2, tcp2);
        web2.start();

        int tcpPort2 = tcp2.getPort();
        s2.getServerMetrics().setShutdownCoordinator((saveSnapshot, skipPersistence) -> {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {}
                exp2.stop();
                tcp2.stop();
                web2.stop();
                aof2.close();
            }, "test-shutdown-coord");
            t.setDaemon(true);
            t.start();
        });

        try (Socket sock2 = new Socket("127.0.0.1", tcpPort2)) {
            sock2.setSoTimeout(3000);
            OutputStream out = new BufferedOutputStream(sock2.getOutputStream());
            InputStream in = new BufferedInputStream(sock2.getInputStream());

            assertEquals("durable", sendResp(out, in, "GET", "renamed:k").getStringValue());
            assertEquals("42", sendResp(out, in, "GET", "tx:sum").getStringValue());
            assertEquals(RedisResponse.ResponseType.NIL, sendResp(out, in, "GET", "ghost:tx").getType());
            assertTrue(sendResp(out, in, "TTL", "ttl:k").getIntValue() > 90);

            assertEquals("OK", sendResp(out, in, "SET", "beforeShutdown", "saved").getStringValue());
            RedisResponse shutdownResp = sendResp(out, in, "SHUTDOWN", "SAVE");
            assertNull(shutdownResp, "SHUTDOWN closes TCP connection cleanly on success");
        }

        long deadline = System.currentTimeMillis() + 3000;
        while ((tcp2.isRunning() || exp2.isRunning() || !aof2.isClosed()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertFalse(tcp2.isRunning(), "TCP server must stop after SHUTDOWN");
        assertFalse(exp2.isRunning(), "ExpirationManager must stop after SHUTDOWN");
        assertTrue(aof2.isClosed(), "AofManager must be closed after SHUTDOWN");

        assertThrows(IOException.class, () -> {
            try (Socket refused = new Socket("127.0.0.1", tcpPort2)) {
                refused.getInputStream();
            }
        });

        // Boot 3: Verify SHUTDOWN SAVE persisted compacted clean state
        StorageEngine s3 = new StorageEngine();
        try (AofManager aof3 = new AofManager(aofFile, FsyncPolicy.EVERYSEC, s3, new CommandRegistry())) {
            int cleanCount = aof3.recover();
            assertEquals(4, cleanCount, "Compacted AOF after SHUTDOWN SAVE should contain exact 4 active keys");
        }
        assertEquals("durable", s3.get("renamed:k").getAsString());
        assertEquals("saved", s3.get("beforeShutdown").getAsString());
        assertEquals("42", s3.get("tx:sum").getAsString());
        assertTrue(s3.ttl("ttl:k") > 90);
    }
}
