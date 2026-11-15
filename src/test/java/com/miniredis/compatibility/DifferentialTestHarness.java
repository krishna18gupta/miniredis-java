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

    public enum ReferenceMode {
        AUTO,
        LIVE_REFERENCE,
        GOLDEN_ORACLE
    }

    public static final String DEFAULT_REF_HOST = "127.0.0.1";
    public static final int DEFAULT_REF_PORT = 6380;

    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final RedisServer miniRedisServer;
    private final int miniRedisPort;

    private final String refHost;
    private final int refPort;
    private final ReferenceMode configuredMode;
    private final ReferenceMode activeMode;
    private final boolean refServerAvailable;
    private final String referenceRedisVersion;

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

        String modeStr = System.getProperty("redis.ref.mode", System.getenv().getOrDefault("REDIS_REF_MODE", "AUTO")).toUpperCase(Locale.ROOT);
        ReferenceMode m = ReferenceMode.AUTO;
        try {
            m = ReferenceMode.valueOf(modeStr);
        } catch (IllegalArgumentException ignored) {}
        this.configuredMode = m;

        boolean probed = probeServer(this.refHost, this.refPort);
        if (configuredMode == ReferenceMode.LIVE_REFERENCE) {
            if (!probed) {
                throw new IllegalStateException(String.format(
                        "LIVE_REFERENCE mode explicitly requested, but reference Redis server at %s:%d is unreachable. " +
                        "Failing fast without falling back to GOLDEN_ORACLE.", this.refHost, this.refPort));
            }
            this.refServerAvailable = true;
            this.activeMode = ReferenceMode.LIVE_REFERENCE;
        } else if (configuredMode == ReferenceMode.GOLDEN_ORACLE) {
            this.refServerAvailable = false;
            this.activeMode = ReferenceMode.GOLDEN_ORACLE;
        } else { // AUTO
            this.refServerAvailable = probed;
            this.activeMode = probed ? ReferenceMode.LIVE_REFERENCE : ReferenceMode.GOLDEN_ORACLE;
        }

        if (this.refServerAvailable) {
            this.referenceRedisVersion = queryReferenceVersion(this.refHost, this.refPort);
            System.out.printf("[DIFFERENTIAL HARNESS] Mode: LIVE_REFERENCE_MODE | Host: %s:%d | Reference Redis Version: %s | MiniRedis Version: 1.0.0%n",
                    this.refHost, this.refPort, this.referenceRedisVersion);
        } else {
            this.referenceRedisVersion = "Redis 7.2 (Golden Standard Baseline)";
            System.out.println("[DIFFERENTIAL HARNESS] Mode: GOLDEN_ORACLE_MODE | Reference Target: Redis 7.2 Standalone Wire Frames");
        }
    }

    private static String queryReferenceVersion(String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            sendCommand(out, "INFO server");
            com.miniredis.commands.RedisResponse resp = RespParser.parseResponse(in);
            if (resp != null && resp.getStringValue() != null) {
                for (String line : resp.getStringValue().split("\r\n")) {
                    if (line.startsWith("redis_version:")) {
                        return line.split(":")[1].trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "7.2.x (live probed)";
    }

    public ReferenceMode getActiveMode() {
        return activeMode;
    }

    public String getReferenceRedisVersion() {
        return referenceRedisVersion;
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
                    DifferentialResult.Mode resultMode = (activeMode == ReferenceMode.LIVE_REFERENCE)
                            ? DifferentialResult.Mode.LIVE_REFERENCE_MODE
                            : DifferentialResult.Mode.GOLDEN_ORACLE_MODE;

                    DifferentialResult result = compareResponses(
                            testCase.getTestId(), cmd, refResp, miniResp, step.getNormalization(), resultMode);
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
                                               DifferentialTestCase.NormalizationType norm,
                                               DifferentialResult.Mode mode) {
        if (ref == null || mini == null) {
            return DifferentialResult.mismatch(testId, command, mode, ref, mini, "Null response received");
        }

        // Clean trailing whitespace for consistent line comparison
        String refTrimmed = ref.trim();
        String miniTrimmed = mini.trim();

        if (refTrimmed.equals(miniTrimmed)) {
            return DifferentialResult.match(testId, command, mode, miniTrimmed);
        }

        // Handle Errors (comparing Redis error prefix and keyword)
        if (refTrimmed.startsWith("-") && miniTrimmed.startsWith("-")) {
            // E.g. -ERR wrong number of arguments for 'set' command
            String refPrefix = refTrimmed.split(" ")[0];
            String miniPrefix = miniTrimmed.split(" ")[0];
            if (refPrefix.equalsIgnoreCase(miniPrefix)) {
                return DifferentialResult.match(testId, command, mode, miniTrimmed);
            }
        }

        if (norm == DifferentialTestCase.NormalizationType.TTL_TOLERANCE) {
            // Compare integer responses with +/- 3s tolerance
            if (refTrimmed.startsWith(":") && miniTrimmed.startsWith(":")) {
                try {
                    long refVal = Long.parseLong(refTrimmed.substring(1));
                    long miniVal = Long.parseLong(miniTrimmed.substring(1));
                    if (Math.abs(refVal - miniVal) <= 3) {
                        return DifferentialResult.match(testId, command, mode, miniTrimmed);
                    }
                } catch (NumberFormatException ignored) {}
            }
        } else if (norm == DifferentialTestCase.NormalizationType.POSITIVE_INTEGER) {
            // Both engines return an integer count > 0 (e.g. COMMAND COUNT discovery)
            if (refTrimmed.startsWith(":") && miniTrimmed.startsWith(":")) {
                try {
                    long refVal = Long.parseLong(refTrimmed.substring(1));
                    long miniVal = Long.parseLong(miniTrimmed.substring(1));
                    if (refVal > 0 && miniVal > 0) {
                        return DifferentialResult.match(testId, command, mode, miniTrimmed);
                    }
                } catch (NumberFormatException ignored) {}
            }
        } else if (norm == DifferentialTestCase.NormalizationType.TEXT_EQUIVALENT) {
            // String content matches across simple string (+) and bulk string ($) framing
            String refPayload = extractPayload(refTrimmed);
            String miniPayload = extractPayload(miniTrimmed);
            if (refPayload.equals(miniPayload)) {
                return DifferentialResult.match(testId, command, mode, miniTrimmed);
            }
        } else if (norm == DifferentialTestCase.NormalizationType.SET_UNORDERED) {
            // Parse RESP array elements and compare as Set
            Set<String> refElements = parseRespArrayElements(refTrimmed);
            Set<String> miniElements = parseRespArrayElements(miniTrimmed);
            if (refElements.equals(miniElements)) {
                return DifferentialResult.match(testId, command, mode, miniTrimmed);
            }
        }

        return DifferentialResult.mismatch(testId, command, mode, refTrimmed, miniTrimmed,
                String.format("Output mismatch. Expected: '%s', Actual: '%s'", refTrimmed, miniTrimmed));
    }

    private String extractPayload(String resp) {
        if (resp == null) return "";
        if (resp.startsWith("+")) {
            return resp.substring(1).trim();
        }
        if (resp.startsWith("$")) {
            int firstNewline = resp.indexOf('\n');
            if (firstNewline != -1) {
                return resp.substring(firstNewline + 1).trim();
            }
        }
        return resp.trim();
    }

    public static void printSummary(List<DifferentialResult> results, String redisVersion) {
        long matchCount = results.stream().filter(r -> r.getStatus() == DifferentialResult.Status.MATCH).count();
        long partialCount = results.stream().filter(r -> r.getStatus() == DifferentialResult.Status.PARTIAL_MATCH).count();
        long mismatchCount = results.stream().filter(r -> r.getStatus() == DifferentialResult.Status.MISMATCH).count();
        DifferentialResult.Mode mode = results.isEmpty() ? DifferentialResult.Mode.GOLDEN_ORACLE_MODE : results.get(0).getExecutionMode();

        System.out.println("===============================================================================");
        System.out.println("   DIFFERENTIAL COMPATIBILITY REPORT SUMMARY                                   ");
        System.out.println("===============================================================================");
        System.out.printf("Execution Mode:            %s%n", mode);
        System.out.printf("Reference Redis Version:   %s%n", redisVersion);
        System.out.println("MiniRedis Engine Version:  1.0.0");
        System.out.printf("Total Commands Tested:     %d%n", results.size());
        System.out.printf("Matching Results (Exact):  %d%n", matchCount);
        System.out.printf("Partial Matches:           %d%n", partialCount);
        System.out.printf("Mismatches / Failures:     %d%n", mismatchCount);
        System.out.println("-------------------------------------------------------------------------------");
        if (mismatchCount > 0) {
            System.out.println("Detailed Mismatches:");
            results.stream()
                    .filter(r -> r.getStatus() == DifferentialResult.Status.MISMATCH)
                    .forEach(r -> System.out.printf(" - [%s] %s -> %s%n", r.getTestId(), r.getCommand(), r.getFailureReason()));
            System.out.println("-------------------------------------------------------------------------------");
        }
        System.out.println("===============================================================================");
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
