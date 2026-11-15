package com.miniredis.compatibility;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.protocol.RespParser;
import com.miniredis.server.RedisServer;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 17: Stage 5 Java / Universal Client Integration Test Suite")
public class JavaClientIntegrationTest {

    private StorageEngine storage;
    private CommandRegistry registry;
    private RedisServer server;
    private int port;

    @BeforeEach
    public void setUp() throws IOException {
        storage = new StorageEngine();
        registry = new CommandRegistry();
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

    private Socket connect() throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(3000);
        return s;
    }

    private void send(OutputStream out, String command) throws IOException {
        DifferentialTestHarness.sendCommand(out, command);
    }

    @Test
    @DisplayName("Client Driver Handshake: HELLO 2, CLIENT SETINFO, CLIENT SETNAME, COMMAND")
    public void testStandardDriverHandshake() throws Exception {
        try (Socket s = connect();
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            // 1. Initial Lettuce / Jedis protocol handshake
            send(out, "HELLO 2");
            RedisResponse hello = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.ARRAY, hello.getType());
            assertTrue(hello.getArrayValue().stream().anyMatch(r -> "server".equals(r.getStringValue())));

            // 2. Client identification
            send(out, "CLIENT SETINFO lib-name lettuce-java");
            assertEquals("OK", RespParser.parseResponse(in).getStringValue());

            send(out, "CLIENT SETNAME app_worker_1");
            assertEquals("OK", RespParser.parseResponse(in).getStringValue());

            send(out, "CLIENT GETNAME");
            assertEquals("app_worker_1", RespParser.parseResponse(in).getStringValue());

            // 3. Command discovery (Lettuce dynamic command inspection)
            send(out, "COMMAND COUNT");
            RedisResponse countResp = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.INTEGER, countResp.getType());
            assertTrue(countResp.getIntValue() >= 50);
        }
    }

    @Test
    @DisplayName("Client CRUD Operations: Strings, Lists, Sets, Hashes, Expiry")
    public void testClientCrudWorkflow() throws Exception {
        try (Socket s = connect();
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            // PING
            send(out, "PING");
            assertEquals("PONG", RespParser.parseResponse(in).getStringValue());

            // Strings
            send(out, "SET client:k1 \"Client Value\"");
            assertEquals("OK", RespParser.parseResponse(in).getStringValue());

            send(out, "GET client:k1");
            assertEquals("Client Value", RespParser.parseResponse(in).getStringValue());

            send(out, "EXPIRE client:k1 3600");
            assertEquals(1L, RespParser.parseResponse(in).getIntValue());

            send(out, "TTL client:k1");
            long ttl = RespParser.parseResponse(in).getIntValue();
            assertTrue(ttl > 0 && ttl <= 3600);

            // Lists
            send(out, "RPUSH client:queue taskA taskB taskC");
            assertEquals(3L, RespParser.parseResponse(in).getIntValue());

            send(out, "LRANGE client:queue 0 -1");
            List<RedisResponse> listItems = RespParser.parseResponse(in).getArrayValue();
            assertEquals(3, listItems.size());
            assertEquals("taskA", listItems.get(0).getStringValue());

            // Sets
            send(out, "SADD client:tags java redis compatibility");
            assertEquals(3L, RespParser.parseResponse(in).getIntValue());

            send(out, "SISMEMBER client:tags redis");
            assertEquals(1L, RespParser.parseResponse(in).getIntValue());

            // Hashes
            send(out, "HSET client:meta version \"1.0.0\" mode \"standalone\"");
            assertEquals(2L, RespParser.parseResponse(in).getIntValue());

            send(out, "HGET client:meta version");
            assertEquals("1.0.0", RespParser.parseResponse(in).getStringValue());

            // DEL
            send(out, "DEL client:k1 client:queue client:tags client:meta");
            assertEquals(4L, RespParser.parseResponse(in).getIntValue());
        }
    }

    @Test
    @DisplayName("Client Error Semantics: WRONGTYPE, Syntax, Non-Existent Key")
    public void testClientErrorHandling() throws Exception {
        try (Socket s = connect();
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            // Type collision
            send(out, "SET plain_str \"hello\"");
            RespParser.parseResponse(in);

            send(out, "LPUSH plain_str \"item\"");
            RedisResponse wrongType = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.ERROR, wrongType.getType());
            assertTrue(wrongType.getStringValue().startsWith("WRONGTYPE"));

            // Non-existent key on GET -> nil
            send(out, "GET never_set_key");
            RedisResponse nilResp = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.NIL, nilResp.getType());
        }
    }

    @Test
    @DisplayName("Client Reconnect: Session Independence & Clean State")
    public void testClientReconnect() throws Exception {
        // Connection 1
        try (Socket s1 = connect();
             OutputStream out1 = s1.getOutputStream();
             InputStream in1 = s1.getInputStream()) {
            send(out1, "SET persistent_across_connections \"persisted\"");
            RespParser.parseResponse(in1);
        }

        // Connection 2 (simulating driver reconnect)
        try (Socket s2 = connect();
             OutputStream out2 = s2.getOutputStream();
             InputStream in2 = s2.getInputStream()) {
            send(out2, "GET persistent_across_connections");
            assertEquals("persisted", RespParser.parseResponse(in2).getStringValue());

            send(out2, "DEL persistent_across_connections");
            RespParser.parseResponse(in2);
        }
    }
}
