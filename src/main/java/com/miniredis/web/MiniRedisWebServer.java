package com.miniredis.web;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in HTTP Web Console for MiniRedis using JDK's standard HttpServer.
 * Allows users to interact with MiniRedis directly in any web browser.
 */
public class MiniRedisWebServer {
    private final int port;
    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final com.miniredis.server.RedisServer tcpServer;
    private HttpServer server;
    private ExecutorService httpExecutor;

    public MiniRedisWebServer(int port, StorageEngine storage, CommandRegistry registry) {
        this(port, storage, registry, null);
    }

    public MiniRedisWebServer(int port, StorageEngine storage, CommandRegistry registry, com.miniredis.server.RedisServer tcpServer) {
        this.port = port;
        this.storage = storage;
        this.registry = registry;
        this.tcpServer = tcpServer;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new DashboardHandler());
        server.createContext("/api/exec", new ExecHandler(storage, registry));
        server.createContext("/api/state", new StateHandler(storage, tcpServer));
        httpExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "miniredis-http-worker");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(httpExecutor);
        server.start();
    }

    public void stop() {
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

    private static void sendResponse(HttpExchange exchange, int statusCode, String contentType, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
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

    private static class ExecHandler implements HttpHandler {
        private final StorageEngine storage;
        private final CommandRegistry registry;

        public ExecHandler(StorageEngine storage, CommandRegistry registry) {
            this.storage = storage;
            this.registry = registry;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 204, "text/plain", new byte[0]);
                return;
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
                String errJson = "{\"status\":\"error\",\"output\":\"(error) ERR empty command\"}";
                sendResponse(exchange, 400, "application/json", errJson.getBytes(StandardCharsets.UTF_8));
                return;
            }

            RedisResponse response = registry.dispatch(cmdLine, storage);
            boolean isError = response.getType() == RedisResponse.ResponseType.ERROR;

            String output = response.toCliString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            String json = String.format("{\"status\":\"%s\",\"type\":\"%s\",\"output\":\"%s\"}",
                    isError ? "error" : "ok",
                    response.getType().name(),
                    output);

            sendResponse(exchange, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static class StateHandler implements HttpHandler {
        private final StorageEngine storage;
        private final com.miniredis.server.RedisServer tcpServer;

        public StateHandler(StorageEngine storage, com.miniredis.server.RedisServer tcpServer) {
            this.storage = storage;
            this.tcpServer = tcpServer;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 204, "text/plain", new byte[0]);
                return;
            }

            StringBuilder sb = new StringBuilder("{\"keys\":[");
            boolean first = true;
            for (Map.Entry<String, RedisValue> entry : storage.getRawStore().entrySet()) {
                RedisValue val = entry.getValue();
                if (val.isExpired(storage.now())) continue;

                if (!first) sb.append(",");
                first = false;

                String k = entry.getKey().replace("\"", "\\\"");
                String type = val.getType().getTypeName();
                String v;

                synchronized (val) {
                    switch (val.getType()) {
                        case STRING:
                            v = val.getAsString();
                            break;
                        case LIST:
                            v = val.getAsList().toString();
                            break;
                        case SET:
                            v = val.getAsSet().toString();
                            break;
                        case HASH:
                            v = val.getAsHash().toString();
                            break;
                        default:
                            v = val.getData().toString();
                            break;
                    }
                }

                v = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");

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

            sendResponse(exchange, 200, "application/json", sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = MiniRedisWebConsoleHtml.getHtml();
            sendResponse(exchange, 200, "text/html", html.getBytes(StandardCharsets.UTF_8));
        }
    }
}