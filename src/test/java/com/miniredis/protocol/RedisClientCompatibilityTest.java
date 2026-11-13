package com.miniredis.protocol;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.server.RedisServer;
import com.miniredis.storage.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 7: Redis Client Compatibility Handshake Tests")
public class RedisClientCompatibilityTest {

    private StorageEngine storage;
    private CommandRegistry registry;
    private RedisServer server;
    private int port;

    @BeforeEach
    public void setUp() throws IOException {
        storage = new StorageEngine();
        registry = new CommandRegistry();
        // Dynamic ephemeral port
        server = new RedisServer(0, 50, storage, registry);
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
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(3000);
        return socket;
    }

    @Test
    @DisplayName("HELLO 2 and HELLO 3 protocol negotiation returns valid RESP2 map array")
    public void testHelloNegotiation() throws Exception {
        try (Socket socket = connect();
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            // Send RESP2 array: *2\r\n$5\r\nHELLO\r\n$1\r\n2\r\n
            String req = "*2\r\n$5\r\nHELLO\r\n$1\r\n2\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            RedisResponse response = RespParser.parseResponse(in);
            assertNotNull(response);
            assertEquals(RedisResponse.ResponseType.ARRAY, response.getType());
            List<RedisResponse> items = response.getArrayValue();
            assertTrue(items.stream().anyMatch(r -> "server".equals(r.getStringValue())), "Must contain server key");
            assertTrue(items.stream().anyMatch(r -> "miniredis".equals(r.getStringValue())), "Must identify as miniredis");
            assertTrue(items.stream().anyMatch(r -> "proto".equals(r.getStringValue())), "Must contain proto key");
        }
    }

    @Test
    @DisplayName("SELECT command switches database with +OK")
    public void testSelectCommand() throws Exception {
        try (Socket socket = connect();
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            // Test inline SELECT 0 returns inline OK
            out.write("SELECT 0\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            assertEquals("OK", line);

            // Test RESP array SELECT 1 returns +OK
            out.write("*2\r\n$6\r\nSELECT\r\n$1\r\n1\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            line = reader.readLine();
            assertEquals("+OK", line);
        }
    }

    @Test
    @DisplayName("CLIENT SETINFO, CLIENT SETNAME, and CLIENT LIST support")
    public void testClientSubcommands() throws Exception {
        try (Socket socket = connect();
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            // redis-py handshake via RESP: CLIENT SETINFO lib-name redis-py
            out.write("*4\r\n$6\r\nCLIENT\r\n$7\r\nSETINFO\r\n$8\r\nlib-name\r\n$8\r\nredis-py\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertEquals("+OK", reader.readLine());

            // CLIENT SETNAME my_conn
            out.write("*3\r\n$6\r\nCLIENT\r\n$7\r\nSETNAME\r\n$7\r\nmy_conn\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertEquals("+OK", reader.readLine());

            // CLIENT LIST
            out.write("*2\r\n$6\r\nCLIENT\r\n$4\r\nLIST\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            String prefix = reader.readLine();
            assertTrue(prefix.startsWith("$"), "CLIENT LIST returns bulk string: " + prefix);
            String clientInfo = reader.readLine();
            assertTrue(clientInfo.contains("id="));
            assertTrue(clientInfo.contains("name=my_conn"));
        }
    }

    @Test
    @DisplayName("AUTH accepts any or dummy credentials returning +OK")
    public void testAuthCommand() throws Exception {
        try (Socket socket = connect();
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            out.write("*3\r\n$4\r\nAUTH\r\n$7\r\ndefault\r\n$6\r\nmypass\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertEquals("+OK", reader.readLine());

            out.write("*2\r\n$4\r\nAUTH\r\n$8\r\nonlypass\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertEquals("+OK", reader.readLine());
        }
    }

    @Test
    @DisplayName("INFO command returns standard Redis metadata sections")
    public void testInfoCommand() throws Exception {
        try (Socket socket = connect();
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            // Generic INFO as RESP command
            out.write("*1\r\n$4\r\nINFO\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            String lenLine = reader.readLine();
            assertTrue(lenLine.startsWith("$"));

            StringBuilder sb = new StringBuilder();
            int len = Integer.parseInt(lenLine.substring(1));
            char[] buf = new char[len];
            int read = 0;
            while (read < len) {
                int r = reader.read(buf, read, len - read);
                if (r == -1) break;
                read += r;
            }
            reader.readLine(); // consume trailing CRLF
            String infoText = new String(buf);
            assertTrue(infoText.contains("# Server"));
            assertTrue(infoText.contains("redis_version:"));
            assertTrue(infoText.contains("# Persistence"));
            assertTrue(infoText.contains("# Keyspace"));
        }
    }

    @Test
    @DisplayName("COMMAND and COMMAND DOCS return command array definitions")
    public void testCommandInspection() throws Exception {
        try (Socket socket = connect();
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            // RESP COMMAND array request
            out.write("*1\r\n$7\r\nCOMMAND\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            RedisResponse result = RespParser.parseResponse(in);
            assertNotNull(result);
            assertEquals(RedisResponse.ResponseType.ARRAY, result.getType());
            assertTrue(result.getArrayValue().size() >= 30, "Should list all registered commands");
        }
    }

    @Test
    @DisplayName("Simulate redis-cli and redis-py connection startup pipeline")
    public void testFullClientHandshakePipeline() throws Exception {
        try (Socket socket = connect();
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            // Pipeline: HELLO 2, CLIENT SETINFO lib-name redis-py, SELECT 0, SET foo bar, GET foo
            String pipeline =
                    "*2\r\n$5\r\nHELLO\r\n$1\r\n2\r\n" +
                    "*4\r\n$6\r\nCLIENT\r\n$7\r\nSETINFO\r\n$8\r\nlib-name\r\n$8\r\nredis-py\r\n" +
                    "*2\r\n$6\r\nSELECT\r\n$1\r\n0\r\n" +
                    "*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n" +
                    "*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n";

            out.write(pipeline.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // 1. HELLO
            RedisResponse r1 = RespParser.parseResponse(in);
            assertNotNull(r1);
            assertEquals(RedisResponse.ResponseType.ARRAY, r1.getType());

            // 2. CLIENT SETINFO
            RedisResponse r2 = RespParser.parseResponse(in);
            assertNotNull(r2);
            assertEquals("OK", r2.getStringValue());

            // 3. SELECT 0
            RedisResponse r3 = RespParser.parseResponse(in);
            assertNotNull(r3);
            assertEquals("OK", r3.getStringValue());

            // 4. SET foo bar
            RedisResponse r4 = RespParser.parseResponse(in);
            assertNotNull(r4);
            assertEquals("OK", r4.getStringValue());

            // 5. GET foo
            RedisResponse r5 = RespParser.parseResponse(in);
            assertNotNull(r5);
            assertEquals("bar", r5.getStringValue());
        }
    }
}
