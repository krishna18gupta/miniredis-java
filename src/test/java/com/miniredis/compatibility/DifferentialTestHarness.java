package com.miniredis.compatibility;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.protocol.RespParser;
import com.miniredis.server.RedisServer;
import com.miniredis.storage.StorageEngine;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Core test execution harness for running differential compatibility tests
 * against MiniRedis and optional live Reference Redis 7.2.
 */
public class DifferentialTestHarness implements AutoCloseable {

    public static final String DEFAULT_REF_HOST = "127.0.0.1";
    public static final int DEFAULT_REF_PORT = 6380;

    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final RedisServer miniRedisServer;
    private final int miniRedisPort;

    private final String refHost;
    private final int refPort;
    private final boolean refServerAvailable;

    public DifferentialTestHarness() throws IOException {
        this.storage = new StorageEngine();
        this.registry = new CommandRegistry();
        // Dynamic ephemeral port
        this.miniRedisServer = new RedisServer(0, 20, storage, registry);
        this.miniRedisServer.start();
        this.miniRedisPort = miniRedisServer.getPort();

        String host = System.getProperty("redis.ref.host", System.getenv().getOrDefault("REDIS_REF_HOST", DEFAULT_REF_HOST));
        int port = DEFAULT_REF_PORT;
        try {
            String pStr = System.getProperty("redis.ref.port", System.getenv().getOrDefault("REDIS_REF_PORT", String.valueOf(DEFAULT_REF_PORT)));
            port = Integer.parseInt(pStr);
        } catch (NumberFormatException ignored) {
        }
        this.refHost = host;
        this.refPort = port;
        this.refServerAvailable = probeServer(this.refHost, this.refPort);
    }

    private static boolean probeServer(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 200);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isReferenceServerAvailable() {
        return refServerAvailable;
    }

    public int getMiniRedisPort() {
        return miniRedisPort;
    }

    public StorageEngine getStorage() {
        return storage;
    }

    public CommandRegistry getRegistry() {
        return registry;
    }

    /**
     * Executes a differential test case and returns results for all steps.
     */
    public List<DifferentialResult> runTestCase(DifferentialTestCase testCase) throws IOException {
        List<DifferentialResult> results = new ArrayList<>();

        // 1. MiniRedis Socket Connection
        try (Socket miniSocket = new Socket("127.0.0.1", miniRedisPort)) {
            miniSocket.setSoTimeout(3000);
            OutputStream miniOut = miniSocket.getOutputStream();
            InputStream miniIn = miniSocket.getInputStream();

            // Run MiniRedis setup
            for (String setup : testCase.getSetupCommands()) {
                sendCommand(miniOut, setup);
                readRespFrame(miniIn);
            }

            // Optional: Reference Redis Connection
            Socket refSocket = null;
            OutputStream refOut = null;
            InputStream refIn = null;
            if (refServerAvailable) {
                try {
                    refSocket = new Socket(refHost, refPort);
                    refSocket.setSoTimeout(3000);
                    refOut = refSocket.getOutputStream();
                    refIn = refSocket.getInputStream();
                    for (String setup : testCase.getSetupCommands()) {
                        sendCommand(refOut, setup);
                        readRespFrame(refIn);
                    }
                } catch (Exception e) {
                    refSocket = null;
                }
            }

            try {
                for (DifferentialTestCase.Step step : testCase.getSteps()) {
                    String cmd = step.getCommand();

                    // Send to MiniRedis
                    sendCommand(miniOut, cmd);
                    String miniResp = readRespFrame(miniIn);

                    // Determine reference response (either live or defined golden standard)
                    String refResp;
                    if (refSocket != null) {
                        sendCommand(refOut, cmd);
                        refResp = readRespFrame(refIn);
                    } else {
                        refResp = step.getExpectedReferenceResponse();
                    }

                    // Compare results with normalization
                    DifferentialResult result = compareResponses(
                            testCase.getTestId(), cmd, refResp, miniResp, step.getNormalization());
                    results.add(result);
                }
            } finally {
                if (refSocket != null) {
                    try {
                        refSocket.close();
                    } catch (Exception ignored) {}
                }
            }
        }

        return results;
    }

    /**
     * Compares reference output with MiniRedis output according to normalization rules.
     */
    public DifferentialResult compareResponses(String testId, String command,
                                               String ref, String mini,
                                               DifferentialTestCase.NormalizationType norm) {
        if (ref == null || mini == null) {
            return DifferentialResult.mismatch(testId, command, ref, mini, "Null response received");
        }

        // Clean trailing whitespace for consistent line comparison
        String refTrimmed = ref.trim();
        String miniTrimmed = mini.trim();

        if (refTrimmed.equals(miniTrimmed)) {
            return DifferentialResult.match(testId, command, miniTrimmed);
        }

        // Handle Errors (comparing Redis error prefix and keyword)
        if (refTrimmed.startsWith("-") && miniTrimmed.startsWith("-")) {
            // E.g. -ERR wrong number of arguments for 'set' command
            String refPrefix = refTrimmed.split(" ")[0];
            String miniPrefix = miniTrimmed.split(" ")[0];
            if (refPrefix.equalsIgnoreCase(miniPrefix)) {
                return DifferentialResult.match(testId, command, miniTrimmed);
            }
        }

        if (norm == DifferentialTestCase.NormalizationType.TTL_TOLERANCE) {
            // Compare integer responses with +/- 2s tolerance
            if (refTrimmed.startsWith(":") && miniTrimmed.startsWith(":")) {
                try {
                    long refVal = Long.parseLong(refTrimmed.substring(1));
                    long miniVal = Long.parseLong(miniTrimmed.substring(1));
                    if (Math.abs(refVal - miniVal) <= 3) {
                        return DifferentialResult.match(testId, command, miniTrimmed);
                    }
                } catch (NumberFormatException ignored) {}
            }
        } else if (norm == DifferentialTestCase.NormalizationType.SET_UNORDERED) {
            // Parse RESP array elements and compare as Set
            Set<String> refElements = parseRespArrayElements(refTrimmed);
            Set<String> miniElements = parseRespArrayElements(miniTrimmed);
            if (refElements.equals(miniElements)) {
                return DifferentialResult.match(testId, command, miniTrimmed);
            }
        }

        return DifferentialResult.mismatch(testId, command, refTrimmed, miniTrimmed,
                String.format("Output mismatch. Expected: '%s', Actual: '%s'", refTrimmed, miniTrimmed));
    }

    private static Set<String> parseRespArrayElements(String resp) {
        Set<String> set = new HashSet<>();
        String[] lines = resp.split("\r\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("$") && i + 1 < lines.length) {
                set.add(lines[i + 1]);
                i++;
            }
        }
        return set;
    }

    /**
     * Formats an inline command string into a RESP2 Array of Bulk Strings and sends it.
     */
    public static void sendCommand(OutputStream out, String commandLine) throws IOException {
        List<String> tokens = CommandRegistry.parseCommandLine(commandLine);
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(tokens.size()).append("\r\n");
        for (String t : tokens) {
            byte[] bytes = t.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(bytes.length).append("\r\n").append(t).append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Reads a complete, raw RESP2 frame from an InputStream.
     */
    public static String readRespFrame(InputStream in) throws IOException {
        com.miniredis.commands.RedisResponse resp = RespParser.parseResponse(in);
        if (resp == null) {
            throw new EOFException("Connection closed by server");
        }
        return resp.toRespWireFormat();
    }

    @Override
    public void close() {
        if (miniRedisServer != null) {
            miniRedisServer.stop();
        }
    }
}
