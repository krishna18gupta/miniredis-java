package com.miniredis;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.server.RedisServer;
import com.miniredis.server.TransactionContext;
import com.miniredis.storage.ExpirationManager;
import com.miniredis.storage.StorageEngine;
import com.miniredis.web.MiniRedisWebServer;

import java.io.File;
import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interactive REPL, Background Expiration Manager, TCP Server, and Web Console launcher for MiniRedis.
 */
public class Main {

    private static final int DEFAULT_TCP_PORT = 6379;
    private static final int DEFAULT_WEB_PORT = 8080;

    public static void main(String[] args) {
        int tcpPort = DEFAULT_TCP_PORT;
        int webPort = DEFAULT_WEB_PORT;
        String aofPathStr = System.getenv("MINIREDIS_AOF_PATH");
        String aofDirStr = System.getenv("MINIREDIS_AOF_DIR");
        boolean demoMode = "true".equalsIgnoreCase(System.getenv("MINIREDIS_DEMO_MODE"));
        String adminToken = System.getenv("MINIREDIS_ADMIN_TOKEN");

        if (System.getenv("MINIREDIS_TCP_PORT") != null) {
            try { tcpPort = Integer.parseInt(System.getenv("MINIREDIS_TCP_PORT")); } catch (NumberFormatException ignored) {}
        }
        if (System.getenv("PORT") != null) {
            try { webPort = Integer.parseInt(System.getenv("PORT")); } catch (NumberFormatException ignored) {}
        }
        if (System.getenv("MINIREDIS_WEB_PORT") != null) {
            try { webPort = Integer.parseInt(System.getenv("MINIREDIS_WEB_PORT")); } catch (NumberFormatException ignored) {}
        }

        for (int i = 0; i < args.length; i++) {
            if ("--port".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try { tcpPort = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if ("--web-port".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try { webPort = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if ("--aof".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                aofPathStr = args[++i];
            } else if ("--dir".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                aofDirStr = args[++i];
            } else if ("--demo".equalsIgnoreCase(args[i])) {
                demoMode = true;
            } else if ("--admin-token".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                adminToken = args[++i];
            }
        }

        StorageEngine storage = new StorageEngine();
        CommandRegistry registry = new CommandRegistry();

        // 1. Initialize and recover Append-Only File (AOF) persistence
        File aofFile;
        if (aofPathStr != null && !aofPathStr.trim().isEmpty()) {
            aofFile = new File(aofPathStr.trim());
        } else if (aofDirStr != null && !aofDirStr.trim().isEmpty()) {
            File dir = new File(aofDirStr.trim());
            if (!dir.exists()) dir.mkdirs();
            aofFile = new File(dir, "appendonly.aof");
        } else {
            aofFile = new File("appendonly.aof");
        }

        com.miniredis.aof.AofManager aofManager = new com.miniredis.aof.AofManager(
                aofFile, com.miniredis.aof.FsyncPolicy.EVERYSEC, storage, registry);
        storage.setAofManager(aofManager);

        try {
            int recovered = aofManager.recover();
            if (recovered > 0) {
                System.out.printf(">> AOF Recovery: Successfully replayed %d commands from %s <<%n",
                        recovered, aofFile.getAbsolutePath());
            }
            aofManager.start();
        } catch (IOException e) {
            System.err.println("Warning: AOF startup/recovery issue: " + e.getMessage());
        }

        // 2. Start background active expiration reaper
        ExpirationManager expirationManager = new ExpirationManager(storage);
        expirationManager.start();

        // 3. Start multithreaded TCP Server (Port 6379)
        RedisServer tcpServer = null;
        try {
            tcpServer = new RedisServer(tcpPort, storage, registry);
            tcpServer.start();
        } catch (IOException e) {
            System.err.println("Note: Could not bind TCP Server to port " + tcpPort + ": " + e.getMessage());
        }

        // 4. Start Web Console & REST API (Port 8080)
        MiniRedisWebServer webServer = null;
        try {
            webServer = new MiniRedisWebServer(webPort, storage, registry, tcpServer, demoMode, adminToken);
            webServer.start();
        } catch (IOException e) {
            System.err.println("Note: Could not bind Web Console to port " + webPort + ": " + e.getMessage());
        }

        printBanner(
                tcpServer != null && tcpServer.isRunning() ? tcpServer.getPort() : -1,
                webServer != null ? webServer.getPort() : -1,
                demoMode
        );

        final RedisServer finalTcpServer = tcpServer;
        final MiniRedisWebServer finalWebServer = webServer;
        final com.miniredis.aof.AofManager finalAofManager = aofManager;
        final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
        final AtomicBoolean stopped = new AtomicBoolean(false);
        final CountDownLatch shutdownLatch = new CountDownLatch(1);

        Runnable stopAll = () -> {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            shutdownRequested.set(true);
            expirationManager.stop();
            if (finalTcpServer != null) {
                finalTcpServer.stop();
            }
            if (finalWebServer != null) {
                finalWebServer.stop();
            }
            if (finalAofManager != null && !finalAofManager.isClosed()) {
                finalAofManager.close();
            }
            shutdownLatch.countDown();
        };

        storage.getServerMetrics().setShutdownCoordinator((saveSnapshot, skipPersistence) -> {
            shutdownRequested.set(true);
            Thread t = new Thread(() -> {
                // Allow in-flight TCP/HTTP response (+OK) to flush cleanly before tearing down sockets
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                stopAll.run();
            }, "miniredis-main-shutdown");
            t.setDaemon(true);
            t.start();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(stopAll, "miniredis-shutdown-hook"));

        TransactionContext replTx = new TransactionContext();
        Thread replThread = new Thread(() -> {
            try (Scanner scanner = new Scanner(System.in)) {
                while (!shutdownRequested.get()) {
                    System.out.print("miniredis> ");
                    System.out.flush();
                    if (!scanner.hasNextLine()) {
                        break;
                    }
                    if (shutdownRequested.get()) {
                        break;
                    }
                    String line = scanner.nextLine().trim();

                    if (line.isEmpty()) {
                        continue;
                    }

                    if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                        System.out.println("Stopping MiniRedis... Bye!");
                        stopAll.run();
                        break;
                    }

                    RedisResponse response = registry.dispatch(line, storage, replTx);
                    System.out.println(response.toCliString());
                }
            } catch (Exception ignored) {}
        }, "miniredis-repl");
        replThread.setDaemon(true);
        replThread.start();

        try {
            shutdownLatch.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printBanner(int tcpPort, int webPort, boolean demoMode) {
        System.out.println("  __  __ _       _ _____          _ _     ");
        System.out.println(" |  \\/  (_)     (_)  __ \\        | (_)    ");
        System.out.println(" | \\  / |_ _ __  _| |__) |___  __| |_ ___ ");
        System.out.println(" | |\\/| | | '_ \\| |  _  // _ \\/ _` | / __|");
        System.out.println(" | |  | | | | | | | | \\ \\  __/ (_| | \\__ \\");
        System.out.println(" |_|  |_|_|_| |_|_|_|  \\_\\___|\\__,_|_|___/");
        System.out.println(" MiniRedis In-Memory Database Server v1.0.0 (AOF Persistence & RESP2 Protocol Online)");
        if (demoMode) {
            System.out.println(" >> Mode: PUBLIC DEMO (Destructive commands restricted & rate limiting active) <<");
        }
        if (tcpPort > 0) {
            System.out.println(" >> TCP Server listening at: localhost:" + tcpPort + " <<");
        }
        if (webPort > 0) {
            System.out.println(" >> Web Console running at:  http://localhost:" + webPort + " <<");
            System.out.println(" (Open the link above in Chrome, Edge, or Firefox to run in your browser!)");
        }
        System.out.println(" Ready to accept commands. Type 'exit' or 'quit' to terminate.\n");
    }
}
