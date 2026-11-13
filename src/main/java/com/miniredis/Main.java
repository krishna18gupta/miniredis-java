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
        StorageEngine storage = new StorageEngine();
        CommandRegistry registry = new CommandRegistry();

        // 1. Initialize and recover Append-Only File (AOF) persistence
        File aofFile = new File("appendonly.aof");
        com.miniredis.aof.AofManager aofManager = new com.miniredis.aof.AofManager(
                aofFile, com.miniredis.aof.FsyncPolicy.EVERYSEC, storage, registry);
        storage.setAofManager(aofManager);

        try {
            int recovered = aofManager.recover();
            if (recovered > 0) {
                System.out.printf(">> AOF Recovery: Successfully replayed %d commands from %s <<%n",
                        recovered, aofFile.getName());
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
            tcpServer = new RedisServer(DEFAULT_TCP_PORT, storage, registry);
            tcpServer.start();
        } catch (IOException e) {
            System.err.println("Note: Could not bind TCP Server to port " + DEFAULT_TCP_PORT + ": " + e.getMessage());
        }

        // 4. Start Web Console (Port 8080)
        MiniRedisWebServer webServer = null;
        try {
            webServer = new MiniRedisWebServer(DEFAULT_WEB_PORT, storage, registry, tcpServer);
            webServer.start();
        } catch (IOException e) {
            System.err.println("Note: Could not bind Web Console to port " + DEFAULT_WEB_PORT + ": " + e.getMessage());
        }

        printBanner(
                tcpServer != null && tcpServer.isRunning() ? tcpServer.getPort() : -1,
                webServer != null ? webServer.getPort() : -1
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

    private static void printBanner(int tcpPort, int webPort) {
        System.out.println("  __  __ _       _ _____          _ _     ");
        System.out.println(" |  \\/  (_)     (_)  __ \\        | (_)    ");
        System.out.println(" | \\  / |_ _ __  _| |__) |___  __| |_ ___ ");
        System.out.println(" | |\\/| | | '_ \\| |  _  // _ \\/ _` | / __|");
        System.out.println(" | |  | | | | | | | | \\ \\  __/ (_| | \\__ \\");
        System.out.println(" |_|  |_|_|_| |_|_|_|  \\_\\___|\\__,_|_|___/");
        System.out.println(" MiniRedis In-Memory Database Server v1.0.0 (AOF Persistence & RESP2 Protocol Online)");
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