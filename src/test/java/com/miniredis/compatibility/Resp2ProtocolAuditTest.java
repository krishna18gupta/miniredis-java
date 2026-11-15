package com.miniredis.compatibility;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.protocol.RespParser;
import com.miniredis.protocol.RespProtocolException;
import com.miniredis.protocol.RespSerializer;
import com.miniredis.server.RedisServer;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 17: Stage 4 RESP2 Wire Protocol Compatibility Audit")
public class Resp2ProtocolAuditTest {

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
    @DisplayName("Protocol 1-5: Simple Strings, Errors, Integers, Bulk Strings, Nulls")
    public void testBasicDataTypes() throws Exception {
        try (Socket s = connect();
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            // 1. Simple String (+PONG\r\n)
            send(out, "PING");
            RedisResponse r1 = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.SIMPLE_STRING, r1.getType());
            assertEquals("PONG", r1.getStringValue());

            // 2. Error (-ERR unknown command...)
            send(out, "UNKNOWNCOMMAND foo bar");
            RedisResponse r2 = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.ERROR, r2.getType());
            assertTrue(r2.getStringValue().startsWith("ERR"));

            // 3. Integer (:1\r\n)
            send(out, "INCR numkey");
            RedisResponse r3 = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.INTEGER, r3.getType());
            assertEquals(1L, r3.getIntValue());

            // 4. Bulk String ($5\r\nhello\r\n)
            send(out, "SET strkey hello");
            RespParser.parseResponse(in); // skip +OK

            send(out, "GET strkey");
            RedisResponse r4 = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.BULK_STRING, r4.getType());
            assertEquals("hello", r4.getStringValue());

            // 5. Null Bulk String ($-1\r\n)
            send(out, "GET nonexistentkey");
            RedisResponse r5 = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.NIL, r5.getType());
            assertNull(r5.getStringValue());
        }
    }

    @Test
    @DisplayName("Protocol 6-9: Arrays, Nested Arrays, Empty Arrays, Empty Bulk Strings")
    public void testArrayAndBoundaryTypes() throws Exception {
        try (Socket s = connect();
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            // 6. Array (*2\r\n$2\r\nk1\r\n$2\r\nk2\r\n)
            send(out, "RPUSH listkey v1 v2 v3");
            RespParser.parseResponse(in);

            send(out, "LRANGE listkey 0 1");
            RedisResponse rArray = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.ARRAY, rArray.getType());
            assertEquals(2, rArray.getArrayValue().size());

            // 7. Empty Array (*0\r\n)
            send(out, "LRANGE listkey 100 200");
            RedisResponse rEmptyArray = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.ARRAY, rEmptyArray.getType());
            assertTrue(rEmptyArray.getArrayValue().isEmpty());

            // 8. Empty Bulk String ($0\r\n\r\n)
            send(out, "SET emptykey \"\"");
            RespParser.parseResponse(in);

            send(out, "GET emptykey");
            RedisResponse rEmptyBulk = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.BULK_STRING, rEmptyBulk.getType());
            assertEquals("", rEmptyBulk.getStringValue());
        }
    }

    @Test
    @DisplayName("Protocol 11-13: Pipelining and Persistent Connection Execution")
    public void testPipeliningAndKeepAlive() throws Exception {
        try (Socket s = connect();
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            // Send 5 pipelined commands in one TCP write buffer using RESP format
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DifferentialTestHarness.sendCommand(baos, "SET pipe1 val1");
            DifferentialTestHarness.sendCommand(baos, "SET pipe2 val2");
            DifferentialTestHarness.sendCommand(baos, "GET pipe1");
            DifferentialTestHarness.sendCommand(baos, "GET pipe2");
            DifferentialTestHarness.sendCommand(baos, "DBSIZE");

            out.write(baos.toByteArray());
            out.flush();

            // Sequentially read 5 replies from the persistent connection
            RedisResponse resp1 = RespParser.parseResponse(in);
            RedisResponse resp2 = RespParser.parseResponse(in);
            RedisResponse resp3 = RespParser.parseResponse(in);
            RedisResponse resp4 = RespParser.parseResponse(in);
            RedisResponse resp5 = RespParser.parseResponse(in);

            assertEquals("OK", resp1.getStringValue());
            assertEquals("OK", resp2.getStringValue());
            assertEquals("val1", resp3.getStringValue());
            assertEquals("val2", resp4.getStringValue());
            assertEquals(RedisResponse.ResponseType.INTEGER, resp5.getType());
            assertTrue(resp5.getIntValue() >= 2);
        }
    }

    @Test
    @DisplayName("Protocol 14: Partial Fragmented Chunks")
    public void testFragmentedPacketHandling() throws Exception {
        try (Socket s = connect();
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            // Simulate fragmented TCP delivery of: *3\r\n$3\r\nSET\r\n$5\r\nfragk\r\n$5\r\nfragv\r\n
            String command = "*3\r\n$3\r\nSET\r\n$5\r\nfragk\r\n$5\r\nfragv\r\n";
            byte[] bytes = command.getBytes(StandardCharsets.UTF_8);

            // Send in small 4-byte chunks with sleeps
            for (int i = 0; i < bytes.length; i += 4) {
                int end = Math.min(i + 4, bytes.length);
                out.write(bytes, i, end - i);
                out.flush();
                Thread.sleep(10);
            }

            RedisResponse resp = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.SIMPLE_STRING, resp.getType());
            assertEquals("OK", resp.getStringValue());
        }
    }

    @Test
    @DisplayName("Protocol 15-16: Malformed Frames & Large Bounded Payloads")
    public void testMalformedAndBoundedFrames() throws Exception {
        try (Socket s = connect();
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            // Send 100 KB payload within bounded limits
            String largeVal = "A".repeat(100_000);
            out.write(("*3\r\n$3\r\nSET\r\n$8\r\nlargekey\r\n$" + largeVal.length() + "\r\n" + largeVal + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            RedisResponse ok = RespParser.parseResponse(in);
            assertEquals("OK", ok.getStringValue());

            send(out, "STRLEN largekey");
            RedisResponse len = RespParser.parseResponse(in);
            assertEquals(100_000L, len.getIntValue());
        }
    }
}
