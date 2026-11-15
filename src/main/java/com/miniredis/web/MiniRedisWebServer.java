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
 */
public class MiniRedisWebServer {
    public static final String VERSION = "1.0.0";
    public static final int MAX_REQUEST_BODY_BYTES = DemoSecurityContext.MAX_BODY_BYTES;

    private final int port;
    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final com.miniredis.server.RedisServer tcpServer;
    private final TelemetrySampler telemetrySampler;
    private final ApiRateLimiter rateLimiter;
    private final DemoSecurityContext demoSecurity;
    private final long startTimeMillis;

    private volatile boolean demoMode;
    private volatile String adminToken;
    private volatile String corsOrigin;

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
        this.rateLimiter = new ApiRateLimiter(60, 2.0); // 60 burst, 2 req/sec refill (120 req/min)
        this.demoSecurity = new DemoSecurityContext(storage, registry);

        String envCors = System.getenv("MINIREDIS_CORS_ORIGIN");
        this.corsOrigin = (envCors != null && !envCors.trim().isEmpty()) ? envCors.trim() : "*";
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

    public DemoSecurityContext getDemoSecurity() {
        return demoSecurity;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new DashboardHandler());
        server.createContext("/api/health", new HealthHandler(this));
        server.createContext("/api/info", new InfoHandler(this, storage, tcpServer, telemetrySampler, demoSecurity));
        server.createContext("/api/keys", new KeysHandler(this, storage, rateLimiter, demoSecurity));
        server.createContext("/api/exec", new ExecHandler(this, storage, registry, rateLimiter, demoSecurity));
        server.createContext("/api/state", new StateHandler(this, storage, tcpServer, demoSecurity));

        httpExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "miniredis-http-worker");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(httpExecutor);
        telemetrySampler.start();

        if (demoMode) {
            demoSecurity.start();
        }

        server.start();
    }

    public void stop() {
        if (demoSecurity != null) {
            demoSecurity.stop();
        }
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

    public static String getSessionId(HttpExchange exchange) {
        List<String> sess = exchange.getRequestHeaders().get("X-Session-ID");
        if (sess != null && !sess.isEmpty()) {
            return sess.get(0).trim();
        }
        return null;
    }

    public void sendResponse(HttpExchange exchange, int statusCode, String contentType, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Admin-Token, X-Session-ID");
        // Defensive HTTP Security & Caching Headers
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        if (exchange.getRequestURI() != null && exchange.getRequestURI().getPath() != null && exchange.getRequestURI().getPath().startsWith("/api/")) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        }

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        exchange.sendResponseHeaders(statusCode, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    public static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        int total = 0;
        while ((read = is.read(buffer)) != -1) {
            total += read;
            if (total > MAX_REQUEST_BODY_BYTES) {
                throw new PayloadTooLargeException("Request body exceeds 16 KB limit");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    public static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException(String msg) {
            super(msg);
        }
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
        private final DemoSecurityContext demoSecurity;

        public InfoHandler(MiniRedisWebServer webServer, StorageEngine storage,
                           com.miniredis.server.RedisServer tcpServer, TelemetrySampler telemetrySampler,
                           DemoSecurityContext demoSecurity) {
            this.webServer = webServer;
            this.storage = storage;
            this.tcpServer = tcpServer;
            this.telemetrySampler = telemetrySampler;
            this.demoSecurity = demoSecurity;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                webServer.sendResponse(exchange, 204, "text/plain", new byte[0]);
                return;
            }

            String sessionId = getSessionId(exchange);
            boolean isAdmin = webServer.isAdminAuthenticated(exchange);
            boolean isDemo = webServer.isDemoMode() && !isAdmin;

            int activeConns = tcpServer != null ? tcpServer.getActiveClientCount() : 0;
            int totalConns = (int) storage.getServerMetrics().getTotalConnectionsReceived();
            long uptimeSec = (System.currentTimeMillis() - webServer.getStartTimeMillis()) / 1000L;
            long completedOps = storage.getTotalCompletedOperations();
            long failedOps = storage.getFailedOperations();
            long opsPerSec = telemetrySampler != null ? telemetrySampler.getCurrentOpsPerSec() : 0;

            StorageEngine.KeyspaceStats ks = storage.computeKeyspaceStats();

            int totalKeys;
            int stringKeys = 0, listKeys = 0, setKeys = 0, hashKeys = 0;
            long now = storage.now();

            if (isDemo && sessionId != null && DemoSecurityContext.isValidSessionId(sessionId)) {
                demoSecurity.touchSession(sessionId);
                // Return counts of demo keys + visitor's session keys
                Set<String> visibleKeys = new HashSet<>(storage.keys("demo:*"));
                visibleKeys.addAll(storage.keys(DemoSecurityContext.SESSION_KEY_PREFIX + sessionId + ":*"));
                totalKeys = visibleKeys.size();

                for (String k : visibleKeys) {
                    RedisValue val = storage.getRawStore().get(k);
                    if (val == null || val.isExpired(now)) continue;
                    switch (val.getType()) {
                        case STRING -> stringKeys++;
                        case LIST -> listKeys++;
                        case SET -> setKeys++;
                        case HASH -> hashKeys++;
                    }
                }
            } else {
                totalKeys = storage.dbsize();
                for (Map.Entry<String, RedisValue> entry : storage.getRawStore().entrySet()) {
                    if (entry.getValue().isExpired(now)) continue;
                    switch (entry.getValue().getType()) {
                        case STRING -> stringKeys++;
                        case LIST -> listKeys++;
                        case SET -> setKeys++;
                        case HASH -> hashKeys++;
                    }
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
            // Top-level legacy telemetry fields for backward compatibility
            sb.append(String.format("\"totalConnectionsReceived\":%d,", totalConns));
            sb.append(String.format("\"keyspaceHits\":%d,", hits));
            sb.append(String.format("\"keyspaceMisses\":%d,", misses));
            sb.append(String.format("\"estimatedMemoryBytes\":%d,", ks.estimatedMemoryBytes));

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
        private final DemoSecurityContext demoSecurity;

        public KeysHandler(MiniRedisWebServer webServer, StorageEngine storage, ApiRateLimiter rateLimiter, DemoSecurityContext demoSecurity) {
            this.webServer = webServer;
            this.storage = storage;
            this.rateLimiter = rateLimiter;
            this.demoSecurity = demoSecurity;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                webServer.sendResponse(exchange, 204, "text/plain", new byte[0]);
                return;
            }

            String clientIp = getClientIp(exchange);
            String sessionId = getSessionId(exchange);
            boolean isAdmin = webServer.isAdminAuthenticated(exchange);
            boolean isDemo = webServer.isDemoMode() && !isAdmin;

            if (isDemo) {
                if (!rateLimiter.allowClient(clientIp, sessionId)) {
                    webServer.sendResponse(exchange, 429, "application/json",
                            "{\"status\":\"error\",\"message\":\"Rate limit exceeded. Please wait a moment.\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (sessionId == null || !DemoSecurityContext.isValidSessionId(sessionId)) {
                    webServer.sendResponse(exchange, 400, "application/json",
                            "{\"status\":\"error\",\"message\":\"Missing or invalid X-Session-ID header (must be 128-bit secure UUID)\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                demoSecurity.touchSession(sessionId);
            }

            String method = exchange.getRequestMethod().toUpperCase();
            Map<String, String> params = parseQueryParams(exchange.getRequestURI());

            if ("DELETE".equals(method)) {
                handleDeleteKey(exchange, params, isDemo, sessionId);
                return;
            }

            if ("POST".equals(method)) {
                String body;
                try {
                    body = readRequestBody(exchange).trim();
                } catch (PayloadTooLargeException e) {
                    webServer.sendResponse(exchange, 413, "application/json",
                            "{\"status\":\"error\",\"message\":\"Payload exceeds 16 KB limit\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (body.contains("\"action\":\"delete\"") || body.contains("\"delete\"")) {
                    handleDeleteKey(exchange, params, isDemo, sessionId);
                    return;
                }
            }

            if ("GET".equals(method)) {
                String singleKey = params.get("key");
                if (singleKey != null && !singleKey.isEmpty()) {
                    handleInspectKey(exchange, singleKey, isDemo, sessionId);
                    return;
                }
                handleListKeys(exchange, params, isDemo, sessionId);
                return;
            }

            webServer.sendResponse(exchange, 405, "application/json",
                    "{\"status\":\"error\",\"message\":\"Method Not Allowed\"}".getBytes(StandardCharsets.UTF_8));
        }

        private void handleInspectKey(HttpExchange exchange, String key, boolean isDemo, String sessionId) throws IOException {
            String cleanKey = key != null ? key.trim() : "";
            if (cleanKey.isEmpty()) {
                webServer.sendResponse(exchange, 400, "application/json",
                        "{\"status\":\"error\",\"message\":\"Missing 'key' parameter\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (isDemo) {
                if (DemoSecurityContext.isInternalNamespaceKey(cleanKey)) {
                    webServer.sendResponse(exchange, 400, "application/json",
                            "{\"status\":\"error\",\"message\":\"Access to internal session namespace is prohibited\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            String internalKey = (isDemo && sessionId != null) ? DemoSecurityContext.toInternalKey(sessionId, cleanKey) : cleanKey;
            RedisValue val = storage.get(internalKey);

            if (val == null) {
                webServer.sendResponse(exchange, 404, "application/json",
                        "{\"status\":\"error\",\"message\":\"Key not found\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            long ttl = storage.ttl(internalKey);
            String ttlFormatted = (ttl == -1) ? "Persistent" : (ttl + "s");
            String type = val.getType().getTypeName();

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"status\":\"ok\",");
            sb.append(String.format("\"key\":\"%s\",", JsonUtil.escape(cleanKey)));
            sb.append(String.format("\"type\":\"%s\",", type));
            sb.append(String.format("\"ttl\":%d,", ttl));
            sb.append(String.format("\"ttlFormatted\":\"%s\",", ttlFormatted));
            sb.append(String.format("\"isExpiring\":%b,", ttl > 0));

            synchronized (val) {
                switch (val.getType()) {
                    case STRING -> {
                        com.miniredis.storage.ByteSequence bs = val.getAsByteSequence();
                        boolean isBinary = !bs.isStrictUtf8();
                        sb.append(String.format("\"length\":%d,", bs.length()));
                        sb.append(String.format("\"isBinary\":%b,", isBinary));
                        if (isBinary) {
                            sb.append(String.format("\"encoding\":\"base64\",\"value\":\"%s\",\"display\":\"%s\"",
                                    JsonUtil.escape(bs.toBase64()), JsonUtil.escape(bs.toDisplayString())));
                        } else {
                            sb.append(String.format("\"encoding\":\"utf8\",\"value\":\"%s\"", JsonUtil.escape(bs.toUtf8String())));
                        }
                    }
                    case LIST -> {
                        Deque<com.miniredis.storage.ByteSequence> list = val.getAsByteList();
                        sb.append(String.format("\"length\":%d,", list.size()));
                        sb.append("\"value\":[");
                        boolean first = true;
                        for (com.miniredis.storage.ByteSequence item : list) {
                            if (!first) sb.append(",");
                            first = false;
                            if (!item.isStrictUtf8()) {
                                sb.append(String.format("{\"isBinary\":true,\"base64\":\"%s\",\"display\":\"%s\"}",
                                        JsonUtil.escape(item.toBase64()), JsonUtil.escape(item.toDisplayString())));
                            } else {
                                sb.append(String.format("\"%s\"", JsonUtil.escape(item.toUtf8String())));
                            }
                        }
                        sb.append("]");
                    }
                    case SET -> {
                        Set<com.miniredis.storage.ByteSequence> set = val.getAsByteSet();
                        sb.append(String.format("\"length\":%d,", set.size()));
                        sb.append("\"value\":[");
                        boolean first = true;
                        for (com.miniredis.storage.ByteSequence m : set) {
                            if (!first) sb.append(",");
                            first = false;
                            if (!m.isStrictUtf8()) {
                                sb.append(String.format("{\"isBinary\":true,\"base64\":\"%s\",\"display\":\"%s\"}",
                                        JsonUtil.escape(m.toBase64()), JsonUtil.escape(m.toDisplayString())));
                            } else {
                                sb.append(String.format("\"%s\"", JsonUtil.escape(m.toUtf8String())));
                            }
                        }
                        sb.append("]");
                    }
                    case HASH -> {
                        Map<com.miniredis.storage.ByteSequence, com.miniredis.storage.ByteSequence> map = val.getAsByteHash();
                        sb.append(String.format("\"length\":%d,", map.size()));
                        sb.append("\"value\":{");
                        boolean first = true;
                        for (Map.Entry<com.miniredis.storage.ByteSequence, com.miniredis.storage.ByteSequence> e : map.entrySet()) {
                            if (!first) sb.append(",");
                            first = false;
                            String kStr = e.getKey().isStrictUtf8() ? e.getKey().toUtf8String() : e.getKey().toDisplayString();
                            String vStr = e.getValue().isStrictUtf8() ? e.getValue().toUtf8String() : e.getValue().toDisplayString();
                            sb.append(String.format("\"%s\":\"%s\"", JsonUtil.escape(kStr), JsonUtil.escape(vStr)));
                        }
                        sb.append("}");
                    }
                    default -> sb.append("\"length\":0,\"value\":\"\"");
                }
            }

            sb.append("}");
            webServer.sendResponse(exchange, 200, "application/json", sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void handleListKeys(HttpExchange exchange, Map<String, String> params, boolean isDemo, String sessionId) throws IOException {
            String pattern = params.getOrDefault("pattern", "*");
            if (isDemo && DemoSecurityContext.isInternalNamespaceKey(pattern)) {
                webServer.sendResponse(exchange, 400, "application/json",
                        "{\"status\":\"error\",\"message\":\"Access to internal session namespace is prohibited\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String typeFilter = params.getOrDefault("type", "all").toLowerCase();
            int limit = 50;
            int offset = 0;
            try {
                if (params.containsKey("limit")) limit = Math.min(50, Math.max(1, Integer.parseInt(params.get("limit"))));
                if (params.containsKey("offset")) offset = Math.max(0, Integer.parseInt(params.get("offset")));
            } catch (NumberFormatException ignored) {}

            List<String> visibleKeys = new ArrayList<>();
            long now = storage.now();

            if (isDemo && sessionId != null) {
                // Collect demo:* keys + session keys
                Set<String> pool = new HashSet<>(storage.keys("demo:*"));
                pool.addAll(storage.keys(DemoSecurityContext.SESSION_KEY_PREFIX + sessionId + ":*"));
                java.util.regex.Pattern regex = StorageEngine.compileGlobToRegex(pattern);

                for (String internalK : pool) {
                    RedisValue v = storage.get(internalK);
                    if (v == null || v.isExpired(now)) continue;
                    if (!"all".equals(typeFilter) && !v.getType().getTypeName().equalsIgnoreCase(typeFilter)) {
                        continue;
                    }
                    String external = DemoSecurityContext.toExternalKey(sessionId, internalK);
                    if (external != null && regex.matcher(external).matches()) {
                        visibleKeys.add(external);
                    }
                }
            } else {
                Set<String> allKeys = storage.keys(pattern);
                for (String k : allKeys) {
                    RedisValue v = storage.get(k);
                    if (v == null || v.isExpired(now)) continue;
                    if (!"all".equals(typeFilter) && !v.getType().getTypeName().equalsIgnoreCase(typeFilter)) {
                        continue;
                    }
                    visibleKeys.add(k);
                }
            }

            Collections.sort(visibleKeys);
            int total = visibleKeys.size();
            int end = Math.min(offset + limit, total);

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"status\":\"ok\",");
            sb.append(String.format("\"totalKeys\":%d,", total));
            sb.append(String.format("\"matchingKeys\":%d,", total));
            sb.append(String.format("\"offset\":%d,", offset));
            sb.append(String.format("\"limit\":%d,", limit));
            sb.append("\"keys\":[");

            for (int i = offset; i < end; i++) {
                if (i > offset) sb.append(",");
                String extK = visibleKeys.get(i);
                String internalK = (isDemo && sessionId != null) ? DemoSecurityContext.toInternalKey(sessionId, extK) : extK;
                RedisValue v = storage.getRawStore().get(internalK);
                if (v == null) continue;

                long ttl = storage.ttl(internalK);
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
                        JsonUtil.escape(extK), type, ttl, ttlFormatted, ttl > 0, size, JsonUtil.escape(preview)
                ));
            }

            sb.append("]}");
            webServer.sendResponse(exchange, 200, "application/json", sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void handleDeleteKey(HttpExchange exchange, Map<String, String> params, boolean isDemo, String sessionId) throws IOException {
            String key = params.get("key");
            if (key == null || key.trim().isEmpty()) {
                webServer.sendResponse(exchange, 400, "application/json",
                        "{\"status\":\"error\",\"message\":\"Missing 'key' parameter\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            String cleanKey = key.trim();

            if (isDemo) {
                if (DemoSecurityContext.isInternalNamespaceKey(cleanKey)) {
                    webServer.sendResponse(exchange, 400, "application/json",
                            "{\"status\":\"error\",\"message\":\"Access to internal session namespace is prohibited\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                // Immutable demo key check
                if (DemoSecurityContext.isProtectedDemoKey(cleanKey)) {
                    webServer.sendResponse(exchange, 403, "application/json",
                            String.format("{\"status\":\"error\",\"message\":\"Key '%s' is an immutable system demo key and cannot be deleted\"}",
                                    JsonUtil.escape(cleanKey)).getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }

            String internalKey = (isDemo && sessionId != null) ? DemoSecurityContext.toInternalKey(sessionId, cleanKey) : cleanKey;
            int deleted = storage.del(List.of(internalKey));

            if (deleted > 0) {
                webServer.sendResponse(exchange, 200, "application/json",
                        String.format("{\"status\":\"ok\",\"deleted\":true,\"key\":\"%s\"}", JsonUtil.escape(cleanKey))
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
        private final DemoSecurityContext demoSecurity;

        public ExecHandler(MiniRedisWebServer webServer, StorageEngine storage, CommandRegistry registry,
                           ApiRateLimiter rateLimiter, DemoSecurityContext demoSecurity) {
            this.webServer = webServer;
            this.storage = storage;
            this.registry = registry;
            this.rateLimiter = rateLimiter;
            this.demoSecurity = demoSecurity;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                webServer.sendResponse(exchange, 204, "text/plain", new byte[0]);
                return;
            }

            String clientIp = getClientIp(exchange);
            String sessionId = getSessionId(exchange);
            boolean isAdmin = webServer.isAdminAuthenticated(exchange);
            boolean isDemo = webServer.isDemoMode() && !isAdmin;

            if (isDemo) {
                if (!rateLimiter.allowClient(clientIp, sessionId)) {
                    String errJson = "{\"status\":\"error\",\"type\":\"ERROR\",\"output\":\"(error) ERR Rate limit exceeded. Please wait a moment.\"}";
                    webServer.sendResponse(exchange, 429, "application/json", errJson.getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }

            String cmdLine = null;
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    cmdLine = readRequestBody(exchange).trim();
                } catch (PayloadTooLargeException e) {
                    String errJson = "{\"status\":\"error\",\"type\":\"ERROR\",\"output\":\"(error) ERR request body exceeds 16 KB limit\"}";
                    webServer.sendResponse(exchange, 413, "application/json", errJson.getBytes(StandardCharsets.UTF_8));
                    return;
                }
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

            if (isDemo) {
                String firstToken = cmdLine.split("\\s+")[0].toUpperCase(Locale.ROOT);
                if (firstToken.equals("SHUTDOWN") || firstToken.equals("FLUSHDB") || firstToken.equals("FLUSHALL")
                        || firstToken.equals("CONFIG") || firstToken.equals("SAVE") || firstToken.equals("BGSAVE")
                        || firstToken.equals("BGREWRITEAOF")) {
                    String errJson = String.format("{\"status\":\"error\",\"message\":\"Command '%s' is disabled in public demo mode. Self-host MiniRedis to run administrative operations.\"}", firstToken);
                    webServer.sendResponse(exchange, 403, "application/json", errJson.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (sessionId == null || !DemoSecurityContext.isValidSessionId(sessionId)) {
                    String errJson = "{\"status\":\"error\",\"type\":\"ERROR\",\"output\":\"(error) ERR missing or invalid X-Session-ID header (must be 128-bit secure UUID)\"}";
                    webServer.sendResponse(exchange, 400, "application/json", errJson.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                demoSecurity.touchSession(sessionId);
            }

            RedisResponse response;
            try {
                if (isDemo) {
                    response = demoSecurity.executeDemoCommand(sessionId, cmdLine);
                } else {
                    response = registry.dispatch(cmdLine, storage);
                }

                boolean isError = response.getType() == RedisResponse.ResponseType.ERROR;
                int statusCode = isError ? (response.toCliString().contains("not permitted") || response.toCliString().contains("immutable") ? 403 : 200) : 200;

                String output = JsonUtil.escape(response.toCliString());
                String json = String.format("{\"status\":\"%s\",\"type\":\"%s\",\"output\":\"%s\"}",
                        isError ? "error" : "ok",
                        response.getType().name(),
                        output);

                webServer.sendResponse(exchange, statusCode, "application/json", json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                String errOutput = JsonUtil.escape("(error) ERR internal server error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                String json = String.format("{\"status\":\"error\",\"type\":\"ERROR\",\"output\":\"%s\"}", errOutput);
                webServer.sendResponse(exchange, 500, "application/json", json.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    // ==========================================
    // 5. /api/state (Legacy Compatible)
    // ==========================================
    private static class StateHandler implements HttpHandler {
        private final MiniRedisWebServer webServer;
        private final StorageEngine storage;
        private final com.miniredis.server.RedisServer tcpServer;
        private final DemoSecurityContext demoSecurity;

        public StateHandler(MiniRedisWebServer webServer, StorageEngine storage, com.miniredis.server.RedisServer tcpServer, DemoSecurityContext demoSecurity) {
            this.webServer = webServer;
            this.storage = storage;
            this.tcpServer = tcpServer;
            this.demoSecurity = demoSecurity;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                webServer.sendResponse(exchange, 204, "text/plain", new byte[0]);
                return;
            }

            String sessionId = getSessionId(exchange);
            boolean isAdmin = webServer.isAdminAuthenticated(exchange);
            boolean isDemo = webServer.isDemoMode() && !isAdmin;

            StringBuilder sb = new StringBuilder("{\"keys\":[");
            boolean first = true;
            long now = storage.now();

            Set<String> keySet;
            if (isDemo && sessionId != null && DemoSecurityContext.isValidSessionId(sessionId)) {
                demoSecurity.touchSession(sessionId);
                keySet = new HashSet<>(storage.keys("demo:*"));
                keySet.addAll(storage.keys(DemoSecurityContext.SESSION_KEY_PREFIX + sessionId + ":*"));
            } else {
                keySet = storage.getRawStore().keySet();
            }

            for (String internalK : keySet) {
                RedisValue val = storage.getRawStore().get(internalK);
                if (val == null || val.isExpired(now)) continue;

                if (!first) sb.append(",");
                first = false;

                String displayKey = isDemo ? DemoSecurityContext.toExternalKey(sessionId, internalK) : internalK;
                if (displayKey == null) continue;

                String k = JsonUtil.escape(displayKey);
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
                long ttlSecs = storage.ttl(internalK);
                String ttlStatus = (ttlSecs == -1) ? "Persistent" : (ttlSecs + "s");

                sb.append(String.format("{\"key\":\"%s\",\"type\":\"%s\",\"value\":\"%s\",\"ttl\":\"%s\",\"isExpiring\":%b}",
                        k, type, v, ttlStatus, ttlSecs > 0));
            }
            sb.append("],\"stats\":{");

            int activeConns = tcpServer != null ? tcpServer.getActiveClientCount() : 0;
            int activeWorkers = tcpServer != null ? tcpServer.getActiveWorkerThreads() : 50;
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
