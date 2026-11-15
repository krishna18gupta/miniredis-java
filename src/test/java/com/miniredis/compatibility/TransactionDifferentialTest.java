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

@DisplayName("Phase 17: Stage 6 Transaction Differential Testing (MULTI / EXEC / WATCH)")
public class TransactionDifferentialTest {

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
    @DisplayName("Transaction: Clean MULTI / EXEC Atomicity")
    public void testCleanMultiExec() throws Exception {
        try (Socket s = connect();
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            send(out, "MULTI");
            assertEquals("OK", RespParser.parseResponse(in).getStringValue());

            send(out, "SET tx_k1 val1");
            assertEquals("QUEUED", RespParser.parseResponse(in).getStringValue());

            send(out, "SET tx_k2 val2");
            assertEquals("QUEUED", RespParser.parseResponse(in).getStringValue());

            send(out, "GET tx_k1");
            assertEquals("QUEUED", RespParser.parseResponse(in).getStringValue());

            send(out, "EXEC");
            RedisResponse execResp = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.ARRAY, execResp.getType());
            List<RedisResponse> results = execResp.getArrayValue();
            assertEquals(3, results.size());
            assertEquals("OK", results.get(0).getStringValue());
            assertEquals("OK", results.get(1).getStringValue());
            assertEquals("val1", results.get(2).getStringValue());
        }
    }

    @Test
    @DisplayName("Transaction: DISCARD Rolls Back Queued Commands")
    public void testDiscardTransaction() throws Exception {
        try (Socket s = connect();
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            send(out, "SET discard_key original");
            RespParser.parseResponse(in);

            send(out, "MULTI");
            assertEquals("OK", RespParser.parseResponse(in).getStringValue());

            send(out, "SET discard_key mutated");
            assertEquals("QUEUED", RespParser.parseResponse(in).getStringValue());

            send(out, "DISCARD");
            assertEquals("OK", RespParser.parseResponse(in).getStringValue());

            send(out, "GET discard_key");
            RedisResponse getResp = RespParser.parseResponse(in);
            assertEquals("original", getResp.getStringValue());
        }
    }

    @Test
    @DisplayName("Transaction: Compile-time Syntax Error Triggers EXECABORT")
    public void testExecAbortOnQueueSyntaxError() throws Exception {
        try (Socket s = connect();
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            send(out, "MULTI");
            RespParser.parseResponse(in);

            send(out, "SET k1 val1");
            RespParser.parseResponse(in); // QUEUED

            // Bad command syntax (SET requires at least 2 args)
            send(out, "SET badsyntax");
            RedisResponse err = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.ERROR, err.getType());

            // EXEC must abort with EXECABORT
            send(out, "EXEC");
            RedisResponse execResp = RespParser.parseResponse(in);
            assertEquals(RedisResponse.ResponseType.ERROR, execResp.getType());
            assertTrue(execResp.getStringValue().contains("EXECABORT"),
                    "Must return EXECABORT error, got: " + execResp.getStringValue());
        }
    }

    @Test
    @DisplayName("Transaction: Optimistic Locking with WATCH Aborts On Modification")
    public void testWatchOptimisticLockingAbort() throws Exception {
        try (Socket clientA = connect();
             Socket clientB = connect()) {

            OutputStream outA = clientA.getOutputStream();
            InputStream inA = clientA.getInputStream();

            OutputStream outB = clientB.getOutputStream();
            InputStream inB = clientB.getInputStream();

            // Client A sets initial key
            send(outA, "SET balance 100");
            RespParser.parseResponse(inA);

            // Client A watches 'balance'
            send(outA, "WATCH balance");
            assertEquals("OK", RespParser.parseResponse(inA).getStringValue());

            // Client A begins MULTI
            send(outA, "MULTI");
            RespParser.parseResponse(inA);

            send(outA, "SET balance 110");
            RespParser.parseResponse(inA); // QUEUED

            // Client B concurrently modifies 'balance'
            send(outB, "SET balance 200");
            assertEquals("OK", RespParser.parseResponse(inB).getStringValue());

            // Client A attempts EXEC -> MUST abort and return null array (nil in Redis)
            send(outA, "EXEC");
            RedisResponse execResp = RespParser.parseResponse(inA);
            assertNull(execResp.getArrayValue(), "EXEC must return nil/null array on watch failure");

            // Final state in DB should be client B's value (200), not client A's aborted write (110)
            send(outA, "GET balance");
            assertEquals("200", RespParser.parseResponse(inA).getStringValue());
        }
    }

    @Test
    @DisplayName("Transaction: UNWATCH Clears Watch Registrations")
    public void testUnwatchClearsState() throws Exception {
        try (Socket clientA = connect();
             Socket clientB = connect()) {

            OutputStream outA = clientA.getOutputStream();
            InputStream inA = clientA.getInputStream();
            OutputStream outB = clientB.getOutputStream();
            InputStream inB = clientB.getInputStream();

            send(outA, "SET unwatch_k 50");
            RespParser.parseResponse(inA);

            send(outA, "WATCH unwatch_k");
            RespParser.parseResponse(inA);

            // Clear watches
            send(outA, "UNWATCH");
            assertEquals("OK", RespParser.parseResponse(inA).getStringValue());

            send(outA, "MULTI");
            RespParser.parseResponse(inA);

            send(outA, "SET unwatch_k 99");
            RespParser.parseResponse(inA);

            // Interleaved client modification
            send(outB, "SET unwatch_k 75");
            RespParser.parseResponse(inB);

            // Because clientA called UNWATCH, the transaction succeeds!
            send(outA, "EXEC");
            RedisResponse execResp = RespParser.parseResponse(inA);
            assertNotNull(execResp.getArrayValue(), "EXEC should succeed after UNWATCH");
            assertEquals(1, execResp.getArrayValue().size());

            send(outA, "GET unwatch_k");
            assertEquals("99", RespParser.parseResponse(inA).getStringValue());
        }
    }
}
