package com.miniredis.web;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.RedisDataType;
import com.miniredis.storage.RedisValue;
import com.miniredis.storage.StorageEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Production-ready HTTP Web Server & REST API for MiniRedis.
 * Uses JDK's standard HttpServer to maintain zero external runtime dependencies.
 *
 * Provides endpoints for:
 * - /                 -> Built-in single-page Web Console
 * - /api/health       -> Health check probe
 * - /api/info         -> Server metrics, memory, ops/sec, AOF health, rolling telemetry
 * - /api/keys         -> Keyspace exploration (search, type filter, TTL, inspect, delete)
 * - /api/exec         -> Interactive command execution with rate limiting and demo safety
 * - /api/state        -> Complete keyspace dump and telemetry (legacy compatible)
 */
public class MiniRedisWebServer {
    public static final String VERSION = "1.0.0";
    private static final int DEMO_MAX_KEYS = 500;
    private static final Set<String> DEMO_BLOCKED_COMMANDS = Set.of(
            "SHUTDOWN", "SAVE", "BGREWRITEAOF", "FLUSHDB", "FLUSHALL", "CONFIG", "BGSAVE"
    );

    private final int port;
    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final com.miniredis.server.RedisServer tcpServer;
    private final TelemetrySampler telemetrySampler;
    private final ApiRateLimiter rateLimiter;
    private final long startTimeMillis;

    private volatile boolean demoMode;
    private volatile String adminToken;
    private volatile String corsOrigin = "*";

    private HttpServer server;
    private ExecutorService httpExecutor;

    public MiniRedisWebServer(int port, StorageEngine storage, CommandRegistry registry) {
        this(port, storage, registry, null, false, null);
    }

    public MiniRedisWebServer(int port, StorageEngine storage, CommandRegistry registry, com.miniredis.server.RedisServer tcpServer) {
        this(port, storage, registry, tcpServer, false, null);
    }

    public MiniRedisWebServer(int port, StorageEngine storage, CommandRegistry registry,
                              com.miniredis.server.RedisServer tcpServer, boolean demoMode, String adminToken) {
        this.port = port;
        this.storage = storage;
        this.registry = registry;
        this.tcpServer = tcpServer;
        this.demoMode = demoMode;
        this.adminToken = adminToken;
        this.startTimeMillis = System.currentTimeMillis();
        this.telemetrySampler = new TelemetrySampler(storage, 60);
        this.rateLimiter = new ApiRateLimiter(60, 5); // 60 burst, 5 req/sec
    }

    public void setDemoMode(boolean demoMode) {
        this.demoMode = demoMode;
    }

    public boolean isDemoMode() {
        return demoMode;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setCorsOrigin(String corsOrigin) {
        this.corsOrigin = (corsOrigin != null && !corsOrigin.trim().isEmpty()) ? corsOrigin.trim() : "*";
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new DashboardHandler());
        server.createContext("/api/health", new HealthHandler(this));
        server.createContext("/api/info", new InfoHandler(this, storage, tcpServer, telemetrySampler));
        server.createContext("/api/keys", new KeysHandler(this, storage, rateLimiter));
        server.createContext("/api/exec", new ExecHandler(this, storage, registry, rateLimiter));
        server.createContext("/api/state", new StateHandler(this, storage, tcpServer));

        httpExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "miniredis-http-worker");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(httpExecutor);
        telemetrySampler.start();
        server.start();
    }

    public void stop() {
        if (telemetrySampler != null) {
            telemetrySampler.stop();
        }
        if (server != null) {
            server.stop(0);
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
    }

    public int getPort() {
        if (server != null) {
            return server.getAddress().getPort();
        }
        return port;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public boolean isAdminAuthenticated(HttpExchange exchange) {
        if (adminToken == null || adminToken.trim().isEmpty()) {
            return !demoMode;
        }
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders != null) {
            for (String header : authHeaders) {
                if (header.startsWith("Bearer ") && header.substring(7).trim().equals(adminToken.trim())) {
                    return true;
                }
            }
        }
        List<String> tokenHeaders = exchange.getRequestHeaders().get("X-Admin-Token");
        if (tokenHeaders != null) {
            for (String header : tokenHeaders) {
                if (header.trim().equals(adminToken.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String getClientIp(HttpExchange exchange) {
        List<String> xff = exchange.getRequestHeaders().get("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            String ip = xff.get(0).split(",")[0].trim();
            if (!ip.isEmpty()) return ip;
        }
        if (exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null) {
            return exchange.getRemoteAddress().getAddress().getHostAddress();
        }
        return "127.0.0.1";
    }

    public void sendResponse(HttpExchange exchange, int statusCode, String contentType, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Admin-Token");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        exchange.sendResponseHeaders(statusCode, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = is.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    String v = (idx < pair.length() - 1) ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8) : "";
                    params.put(k, v);
                } else if (idx == -1 && !pair.isEmpty()) {
                    params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                }
            }
        }
        return params;
    }

    // ==========================================
    // 1. /api/health
    // ==========================================
    private static class HealthHandler implements HttpHandler {
        private final MiniRedisWebServer webServer;

        public HealthHandler(MiniRedisWebServer webServer) {
            this.webServer = webServer;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                webServer.sendResponse(exchange, 204, "text/plain", new byte[0]);
                return;
            }
            long uptimeSeconds = (System.currentTimeMillis() - webServer.getStartTimeMillis()) / 1000L;
            String json = String.format(
                    "{\"status\":\"UP\",\"service\":\"MiniRedis\",\"version\":\"%s\",\"uptimeSeconds\":%d,\"timestamp\":%d,\"demoMode\":%b}",
                    VERSION, uptimeSeconds, System.currentTimeMillis(), webServer.isDemoMode()
            );
            webServer.sendResponse(exchange, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ==========================================
    // 2. /api/info
    // ==========================================
    private static class InfoHandler implements HttpHandler {
        private final MiniRedisWebServer webServer;
        private final StorageEngine storage;
        private final com.miniredis.server.RedisServer tcpServer;
        private final TelemetrySampler telemetrySampler;

        public InfoHandler(MiniRedisWebServer webServer, StorageEngine storage,
                           com.miniredis.server.RedisServer tcpServer, TelemetrySampler telemetrySampler) {
            this.webServer = webServer;
            this.storage = storage;
            this.tcpServer = tcpServer;
            this.telemetrySampler = telemetrySampler;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                webServer.sendResponse(exchange, 204, "text/plain", new byte[0]);
                return;
            }

            int activeConns = tcpServer != null ? tcpServer.getActiveClientCount() : 0;
            int totalConns = (int) storage.getServerMetrics().getTotalConnectionsReceived();
            long uptimeSec = (System.currentTimeMillis() - webServer.getStartTimeMillis()) / 1000L;
            long completedOps = storage.getTotalCompletedOperations();
            long failedOps = storage.getFailedOperations();
            long opsPerSec = telemetrySampler != null ? telemetrySampler.getCurrentOpsPerSec() : 0;

            StorageEngine.KeyspaceStats ks = storage.computeKeyspaceStats();
            int totalKeys = storage.dbsize();

            // Count keys per type
            int stringKeys = 0, listKeys = 0, setKeys = 0, hashKeys = 0;
            long now = storage.now();
            for (Map.Entry<String, RedisValue> entry : storage.getRawStore().entrySet()) {
                if (entry.getValue().isExpired(now)) continue;
                switch (entry.getValue().getType()) {
                    case STRING -> stringKeys++;
                    case LIST -> listKeys++;
                    case SET -> setKeys++;
                    case HASH -> hashKeys++;
                }
            }

            long hits = storage.getServerMetrics().getKeyspaceHits();
            long misses = storage.getServerMetrics().getKeyspaceMisses();
            double hitRate = (hits + misses > 0) ? ((double) hits / (hits + misses) * 100.0) : 100.0;

            Runtime runtime = Runtime.getRuntime();
            long jvmTotal = runtime.totalMemory();
            long jvmFree = runtime.freeMemory();
            long jvmUsed = jvmTotal - jvmFree;
            long jvmMax = runtime.maxMemory();

            int stripeCount = storage.getStripedLockManager().getStripeCount();
            long contention = storage.getStripedLockManager().getContentionCount();

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"status\":\"ok\",");
            sb.append(String.format("\"version\":\"%s\",", VERSION));
            sb.append(String.format("\"uptimeSeconds\":%d,", uptimeSec));
            sb.append(String.format("\"demoMode\":%b,", webServer.isDemoMode()));
            sb.append(String.format("\"requiresAuth\":%b,", webServer.getAdminToken() != null && !webServer.getAdminToken().isEmpty()));

            // Keyspace
            sb.append("\"keyspace\":{");
            sb.append(String.format("\"totalKeys\":%d,", totalKeys));
            sb.append(String.format("\"stringKeys\":%d,", stringKeys));
            sb.append(String.format("\"listKeys\":%d,", listKeys));
            sb.append(String.format("\"setKeys\":%d,", setKeys));
            sb.append(String.format("\"hashKeys\":%d,", hashKeys));
            sb.append(String.format("\"expiringKeys\":%d,", ks.expiringKeys));
            sb.append(String.format("\"avgTtlMs\":%d,", ks.avgTtlMs));
            sb.append(String.format("\"hits\":%d,", hits));
            sb.append(String.format("\"misses\":%d,", misses));
            sb.append(String.format(Locale.US, "\"hitRatePercent\":%.1f", hitRate));
            sb.append("},");

            // Memory
            sb.append("\"memory\":{");
            sb.append(String.format("\"estimatedDatabaseBytes\":%d,", ks.estimatedMemoryBytes));
            sb.append(String.format("\"jvmUsedBytes\":%d,", jvmUsed));
            sb.append(String.format("\"jvmFreeBytes\":%d,", jvmFree));
            sb.append(String.format("\"jvmTotalBytes\":%d,", jvmTotal));
            sb.append(String.format("\"jvmMaxBytes\":%d", jvmMax));
            sb.append("},");

            // Operations
            sb.append("\"operations\":{");
            sb.append(String.format("\"totalCompleted\":%d,", completedOps));
            sb.append(String.format("\"failed\":%d,", failedOps));
            sb.append(String.format("\"opsPerSecond\":%d,", opsPerSec));
            sb.append(String.format("\"stripedLockCount\":%d,", stripeCount));
            sb.append(String.format("\"contentionCount\":%d", contention));
            sb.append("},");

            // Clients
            sb.append("\"clients\":{");
            sb.append(String.format("\"connectedClients\":%d,", activeConns));
            sb.append(String.format("\"totalConnectionsReceived\":%d", totalConns));
            sb.append("},");

            // AOF
            com.miniredis.aof.AofManager aof = storage.getAofManager();
            if (aof != null) {
                com.miniredis.aof.AofHealth h = aof.getHealth();
                sb.append(String.format(
                        "\"aof\":{\"enabled\":true,\"healthStatus\":\"%s\",\"fileSize\":%d,\"fsyncPolicy\":\"%s\",\"lastSuccessfulFsyncTime\":%d,\"lastFsyncDurationMs\":%d,\"pendingQueueSize\":%d,\"totalLoggedCommands\":%d,\"totalFsyncs\":%d,\"isRewriting\":%b,\"lastRewriteDurationMs\":%d,\"recoveryDurationMs\":%d,\"recoveryErrors\":%d},",
                        h.getStatus().name(), h.getFileSizeBytes(), h.getFsyncPolicy().name(), h.getLastSuccessfulFsyncTime(),
                        h.getLastFsyncDurationMs(), h.getPendingQueueSize(), h.getTotalLoggedCommands(), h.getTotalFsyncs(),
                        h.isRewriting(), h.getLastRewriteDurationMs(), h.getRecoveryDurationMs(), h.getRecoveryErrors()
                ));
            } else {
                sb.append("\"aof\":{\"enabled\":false,\"healthStatus\":\"DISABLED\"},");
            }

            // Rolling Telemetry
            sb.append("\"telemetry\":");
            sb.append(telemetrySampler != null ? telemetrySampler.toJsonArray() : "[]");

            sb.append("}");

            webServer.sendResponse(exchange, 200, "application/json", sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // ==========================================
    // 3. /api/keys
    // ==========================================
    private static class KeysHandler implements HttpHandler {
        private final MiniRedisWebServer webServer;
        private final StorageEngine storage;
        private final ApiRateLimiter rateLimiter;

        public KeysHandler(MiniRedisWebServer webServer, StorageEngine storage, ApiRateLimiter rateLimiter) {
            this.webServer = webServer;
            this.storage = storage;
            this.rateLimiter = rateLimiter;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                webServer.sendResponse(exchange, 204, "text/plain", new byte[0]);
                return;
            }

            String method = exchange.getRequestMethod().toUpperCase();
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());

            if ("DELETE".equals(method)) {
                handleDeleteKey(exchange, params);
                return;
            }

            if ("POST".equals(method)) {
                String body = readRequestBody(exchange).trim();
                if (body.contains("\"action\":\"delete\"") || body.contains("\"delete\"")) {
                    handleDeleteKey(exchange, params);
                    return;
                }
            }

            if ("GET".equals(method)) {
                // If specific key requested, inspect key
                String singleKey = params.get("key");
                if (singleKey != null && !singleKey.isEmpty()) {
                    handleInspectKey(exchange, singleKey);
                    return;
                }

                // Otherwise list keys
                handleListKeys(exchange, params);
                return;
            }

            webServer.sendResponse(exchange, 405, "application/json",
                    "{\"status\":\"error\",\"message\":\"Method Not Allowed\"}".getBytes(StandardCharsets.UTF_8));
        }

        private void handleInspectKey(HttpExchange exchange, String key) throws IOException {
            RedisValue val = storage.get(key);
            if (val == null) {
                webServer.sendResponse(exchange, 404, "application/json",
                        "{\"status\":\"error\",\"message\":\"Key not found\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            long ttl = storage.ttl(key);
            String ttlFormatted = (ttl == -1) ? "Persistent" : (ttl + "s");
            String type = val.getType().getTypeName();

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"status\":\"ok\",");
            sb.append(String.format("\"key\":\"%s\",", JsonUtil.escape(key)));
            sb.append(String.format("\"type\":\"%s\",", type));
            sb.append(String.format("\"ttl\":%d,", ttl));
            sb.append(String.format("\"ttlFormatted\":\"%s\",", ttlFormatted));
            sb.append(String.format("\"isExpiring\":%b,", ttl > 0));

            synchronized (val) {
                switch (val.getType()) {
                    case STRING -> {
                        String s = val.getAsString();
                        sb.append(String.format("\"length\":%d,", s.length()));
                        sb.append(String.format("\"value\":\"%s\"", JsonUtil.escape(s)));
                    }
                    case LIST -> {
                        Deque<String> list = val.getAsList();
                        sb.append(String.format("\"length\":%d,", list.size()));
                        sb.append("\"value\":[");
                        boolean first = true;
                        for (String item : list) {
                            if (!first) sb.append(",");
                            first = false;
                            sb.append(String.format("\"%s\"", JsonUtil.escape(item)));
                        }
                        sb.append("]");
                    }
                    case SET -> {
                        Set<String> set = val.getAsSet();
                        sb.append(String.format("\"length\":%d,", set.size()));
                        sb.append("\"value\":[");
                        boolean first = true;
                        for (String m : set) {
                            if (!first) sb.append(",");
                            first = false;
                            sb.append(String.format("\"%s\"", JsonUtil.escape(m)));
                        }
                        sb.append("]");
                    }
                    case HASH -> {
                        Map<String, String> map = val.getAsHash();
                        sb.append(String.format("\"length\":%d,", map.size()));
                        sb.append("\"value\":{");
                        boolean first = true;
                        for (Map.Entry<String, String> e : map.entrySet()) {
                            if (!first) sb.append(",");
                            first = false;
                            sb.append(String.format("\"%s\":\"%s\"", JsonUtil.escape(e.getKey()), JsonUtil.escape(e.getValue())));
                        }
                        sb.append("}");
                    }
                    default -> {
                        sb.append("\"length\":0,\"value\":\"\"");
                    }
                }
            }

            sb.append("}");
            webServer.sendResponse(exchange, 200, "application/json", sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void handleListKeys(HttpExchange exchange, Map<String, String> params) throws IOException {
            String pattern = params.getOrDefault("pattern", "*");
            String typeFilter = params.getOrDefault("type", "all").toLowerCase();
            int limit = 50;
            int offset = 0;
            try {
                if (params.containsKey("limit")) limit = Math.min(200, Math.max(1, Integer.parseInt(params.get("limit"))));
                if (params.containsKey("offset")) offset = Math.max(0, Integer.parseInt(params.get("offset")));
            } catch (NumberFormatException ignored) {}

            Set<String> allMatchingKeys = storage.keys(pattern);
            List<String> filteredKeys = new ArrayList<>();
            long now = storage.now();

            for (String k : allMatchingKeys) {
                RedisValue v = storage.get(k);
                if (v == null || v.isExpired(now)) continue;
                if (!"all".equals(typeFilter) && !v.getType().getTypeName().equalsIgnoreCase(typeFilter)) {
                    continue;
                }
                filteredKeys.add(k);
            }

            Collections.sort(filteredKeys);
            int total = filteredKeys.size();
            int end = Math.min(offset + limit, total);

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"status\":\"ok\",");
            sb.append(String.format("\"totalKeys\":%d,", storage.dbsize()));
            sb.append(String.format("\"matchingKeys\":%d,", total));
            sb.append(String.format("\"offset\":%d,", offset));
            sb.append(String.format("\"limit\":%d,", limit));
            sb.append("\"keys\":[");

            for (int i = offset; i < end; i++) {
                if (i > offset) sb.append(",");
                String k = filteredKeys.get(i);
                RedisValue v = storage.get(k);
                if (v == null) continue;

                long ttl = storage.ttl(k);
                String ttlFormatted = (ttl == -1) ? "Persistent" : (ttl + "s");
                String type = v.getType().getTypeName();
                String preview = "";
                int size = 0;

                synchronized (v) {
                    switch (v.getType()) {
                        case STRING -> {
                            preview = v.getAsString();
                            size = preview.length();
                            if (preview.length() > 60) preview = preview.substring(0, 57) + "...";
                        }
                        case LIST -> {
                            size = v.getAsList().size();
                            preview = String.format("[%d items]", size);
                        }
                        case SET -> {
                            size = v.getAsSet().size();
                            preview = String.format("{%d members}", size);
                        }
                        case HASH -> {
                            size = v.getAsHash().size();
                            preview = String.format("{%d fields}", size);
                        }
                        default -> preview = "";
                    }
                }

                sb.append(String.format(
                        "{\"key\":\"%s\",\"type\":\"%s\",\"ttl\":%d,\"ttlFormatted\":\"%s\",\"isExpiring\":%b,\"size\":%d,\"preview\":\"%s\"}",
                        JsonUtil.escape(k), type, ttl, ttlFormatted, ttl > 0, size, JsonUtil.escape(preview)
                ));
            }

            sb.append("]}");
            webServer.sendResponse(exchange, 200, "application/json", sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void handleDeleteKey(HttpExchange exchange, Map<String, String> params) throws IOException {
            String clientIp = getClientIp(exchange);
            if (!rateLimiter.allowRequest(clientIp)) {
                webServer.sendResponse(exchange, 429, "application/json",
                        "{\"status\":\"error\",\"message\":\"Rate limit exceeded. Please wait.\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            String key = params.get("key");
            if (key == null || key.trim().isEmpty()) {
                webServer.sendResponse(exchange, 400, "application/json",
                        "{\"status\":\"error\",\"message\":\"Missing 'key' parameter\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            int deleted = storage.del(List.of(key.trim()));
            if (deleted > 0) {
                webServer.sendResponse(exchange, 200, "application/json",
                        String.format("{\"status\":\"ok\",\"deleted\":true,\"key\":\"%s\"}", JsonUtil.escape(key.trim()))
                                .getBytes(StandardCharsets.UTF_8));
            } else {
                webServer.sendResponse(exchange, 404, "application/json",
                        "{\"status\":\"error\",\"message\":\"Key not found or expired\"}".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    // ==========================================
    // 4. /api/exec
    // ==========================================
    private static class ExecHandler implements HttpHandler {
        private final MiniRedisWebServer webServer;
        private final StorageEngine storage;
        private final CommandRegistry registry;
        private final ApiRateLimiter rateLimiter;

        public ExecHandler(MiniRedisWebServer webServer, StorageEngine storage, CommandRegistry registry, ApiRateLimiter rateLimiter) {
            this.webServer = webServer;
            this.storage = storage;
            this.registry = registry;
            this.rateLimiter = rateLimiter;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                webServer.sendResponse(exchange, 204, "text/plain", new byte[0]);
                return;
            }

            String clientIp = getClientIp(exchange);
            boolean isAdmin = webServer.isAdminAuthenticated(exchange);

            if (!isAdmin && webServer.isDemoMode()) {
                if (!rateLimiter.allowRequest(clientIp)) {
                    String errJson = "{\"status\":\"error\",\"type\":\"ERROR\",\"output\":\"(error) ERR Rate limit exceeded. Please wait before executing more commands.\"}";
                    webServer.sendResponse(exchange, 429, "application/json", errJson.getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }

            String cmdLine = null;
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                cmdLine = readRequestBody(exchange).trim();
            } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.startsWith("cmd=")) {
                    cmdLine = URLDecoder.decode(query.substring(4), StandardCharsets.UTF_8);
                }
            }

            if (cmdLine == null || cmdLine.isEmpty()) {
                String errJson = "{\"status\":\"error\",\"type\":\"ERROR\",\"output\":\"(error) ERR empty command\"}";
                webServer.sendResponse(exchange, 400, "application/json", errJson.getBytes(StandardCharsets.UTF_8));
                return;
            }

            String firstToken = cmdLine.trim().split("\\s+")[0].toUpperCase();

            // Enforce Demo Mode safety rules when caller is not authenticated as admin
            if (!isAdmin && webServer.isDemoMode()) {
                if (DEMO_BLOCKED_COMMANDS.contains(firstToken)) {
                    String errJson = String.format(
                            "{\"status\":\"error\",\"type\":\"ERROR\",\"output\":\"(error) ERR command '%s' is disabled in public demo mode to preserve service integrity.\"}",
                            firstToken
                    );
                    webServer.sendResponse(exchange, 403, "application/json", errJson.getBytes(StandardCharsets.UTF_8));
                    return;
                }

                // Check demo keyspace limit on mutating commands
                if (storage.dbsize() >= DEMO_MAX_KEYS && isPotentialWriteCommand(firstToken)) {
                    String errJson = "{\"status\":\"error\",\"type\":\"ERROR\",\"output\":\"(error) ERR demo keyspace limit reached (500 keys). Please delete existing keys or run a private self-hosted instance.\"}";
                    webServer.sendResponse(exchange, 403, "application/json", errJson.getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }

            RedisResponse response = registry.dispatch(cmdLine, storage);
            boolean isError = response.getType() == RedisResponse.ResponseType.ERROR;

            String output = JsonUtil.escape(response.toCliString());
            String json = String.format("{\"status\":\"%s\",\"type\":\"%s\",\"output\":\"%s\"}",
                    isError ? "error" : "ok",
                    response.getType().name(),
                    output);

            webServer.sendResponse(exchange, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }

        private boolean isPotentialWriteCommand(String cmd) {
            return switch (cmd) {
                case "SET", "SETEX", "MSET", "LPUSH", "RPUSH", "SADD", "HSET" -> true;
                default -> false;
            };
        }
    }

    // ==========================================
    // 5. /api/state (Legacy & Web Console Compatible)
    // ==========================================
    private static class StateHandler implements HttpHandler {
        private final MiniRedisWebServer webServer;
        private final StorageEngine storage;
        private final com.miniredis.server.RedisServer tcpServer;

        public StateHandler(MiniRedisWebServer webServer, StorageEngine storage, com.miniredis.server.RedisServer tcpServer) {
            this.webServer = webServer;
            this.storage = storage;
            this.tcpServer = tcpServer;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                webServer.sendResponse(exchange, 204, "text/plain", new byte[0]);
                return;
            }

            StringBuilder sb = new StringBuilder("{\"keys\":[");
            boolean first = true;
            for (Map.Entry<String, RedisValue> entry : storage.getRawStore().entrySet()) {
                RedisValue val = entry.getValue();
                if (val.isExpired(storage.now())) continue;

                if (!first) sb.append(",");
                first = false;

                String k = JsonUtil.escape(entry.getKey());
                String type = val.getType().getTypeName();
                String v;

                synchronized (val) {
                    switch (val.getType()) {
                        case STRING -> v = val.getAsString();
                        case LIST -> v = val.getAsList().toString();
                        case SET -> v = val.getAsSet().toString();
                        case HASH -> v = val.getAsHash().toString();
                        default -> v = val.getData().toString();
                    }
                }

                v = JsonUtil.escape(v.replace("\n", " "));
                long ttlSecs = storage.ttl(entry.getKey());
                String ttlStatus = (ttlSecs == -1) ? "Persistent" : (ttlSecs + "s");

                sb.append(String.format("{\"key\":\"%s\",\"type\":\"%s\",\"value\":\"%s\",\"ttl\":\"%s\",\"isExpiring\":%b}",
                        k, type, v, ttlStatus, ttlSecs > 0));
            }
            sb.append("],\"stats\":{");

            int activeConns = tcpServer != null ? tcpServer.getActiveClientCount() : 0;
            int activeWorkers = tcpServer != null ? tcpServer.getActiveWorkerThreads() : 0;
            int availWorkers = tcpServer != null ? tcpServer.getAvailableWorkerThreads() : 50;
            int pendingQueue = tcpServer != null ? tcpServer.getPendingQueueTasks() : 0;
            long completedOps = storage.getTotalCompletedOperations();
            long failedOps = storage.getFailedOperations();
            long contention = storage.getStripedLockManager().getContentionCount();
            int stripeCount = storage.getStripedLockManager().getStripeCount();
            com.miniredis.storage.ServerMetrics metrics = storage.getServerMetrics();
            StorageEngine.KeyspaceStats ks = storage.computeKeyspaceStats();

            sb.append(String.format(
                    "\"activeConnections\":%d,\"activeWorkerThreads\":%d,\"availableWorkerThreads\":%d,\"pendingQueueTasks\":%d,\"totalCompletedOperations\":%d,\"failedOperations\":%d,\"lockContentionCount\":%d,\"stripedLockCount\":%d,\"uptimeSeconds\":%d,\"totalConnectionsReceived\":%d,\"keyspaceHits\":%d,\"keyspaceMisses\":%d,\"expiringKeys\":%d,\"avgTtlMs\":%d,\"estimatedMemoryBytes\":%d",
                    activeConns, activeWorkers, availWorkers, pendingQueue, completedOps, failedOps, contention, stripeCount,
                    metrics.getUptimeSeconds(), metrics.getTotalConnectionsReceived(), metrics.getKeyspaceHits(),
                    metrics.getKeyspaceMisses(), ks.expiringKeys, ks.avgTtlMs, ks.estimatedMemoryBytes
            ));

            com.miniredis.aof.AofManager aof = storage.getAofManager();
            if (aof != null) {
                com.miniredis.aof.AofHealth health = aof.getHealth();
                sb.append(String.format(
                        ",\"aof\":{\"enabled\":true,\"fileSize\":%d,\"fsyncPolicy\":\"%s\",\"lastSuccessfulFsyncTime\":%d,\"lastFsyncDurationMs\":%d,\"pendingQueueSize\":%d,\"totalLoggedCommands\":%d,\"totalFsyncs\":%d,\"isRewriting\":%b,\"lastRewriteDurationMs\":%d,\"recoveryDurationMs\":%d,\"recoveryErrors\":%d,\"healthStatus\":\"%s\"}",
                        health.getFileSizeBytes(), health.getFsyncPolicy().name(), health.getLastSuccessfulFsyncTime(),
                        health.getLastFsyncDurationMs(), health.getPendingQueueSize(), health.getTotalLoggedCommands(),
                        health.getTotalFsyncs(), health.isRewriting(), health.getLastRewriteDurationMs(),
                        health.getRecoveryDurationMs(), health.getRecoveryErrors(), health.getStatus().name()
                ));
            } else {
                sb.append(",\"aof\":{\"enabled\":false,\"healthStatus\":\"DISABLED\"}");
            }

            sb.append("}}");

            webServer.sendResponse(exchange, 200, "application/json", sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // ==========================================
    // 6. / (Embedded Dashboard Handler)
    // ==========================================
    private static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = MiniRedisWebConsoleHtml.getHtml();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, html.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
