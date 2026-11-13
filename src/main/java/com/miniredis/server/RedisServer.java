package com.miniredis.server;

import com.miniredis.aof.AofManager;
import com.miniredis.commands.CommandRegistry;
import com.miniredis.storage.ServerMetrics;
import com.miniredis.storage.StorageEngine;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multithreaded TCP Server for MiniRedis.
 * Listens on a configured port, delegates connections to a bounded worker pool of RedisClientHandlers,
 * tracks live runtime telemetry, and maintains clean lifecycle shutdown coordination.
 */
public class RedisServer {

    public static final int DEFAULT_PORT = 6379;
    public static final int DEFAULT_MAX_WORKERS = 50;

    private final int port;
    private final int maxWorkers;
    private final StorageEngine storage;
    private final CommandRegistry registry;

    private ServerSocket serverSocket;
    private ThreadPoolExecutor workerPool;
    private final Set<RedisClientHandler> activeClients = ConcurrentHashMap.newKeySet();
    private final AtomicLong clientIdSequence = new AtomicLong(1);
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);
    private Thread acceptThread;
    private volatile boolean running = false;

    public RedisServer(int port, StorageEngine storage, CommandRegistry registry) {
        this(port, DEFAULT_MAX_WORKERS, storage, registry);
    }

    public RedisServer(int port, int maxWorkers, StorageEngine storage, CommandRegistry registry) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port out of range: " + port);
        }
        if (maxWorkers <= 0) {
            throw new IllegalArgumentException("maxWorkers must be positive: " + maxWorkers);
        }
        this.port = port;
        this.maxWorkers = maxWorkers;
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
    }

    /**
     * Starts the TCP server and begins accepting client connections.
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        shutdownInitiated.set(false);
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new java.net.InetSocketAddress(port));

        AtomicInteger workerCounter = new AtomicInteger(1);
        workerPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(maxWorkers, r -> {
            Thread t = new Thread(r, "miniredis-worker-" + workerCounter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });

        running = true;

        // Wire live server telemetry into StorageEngine ServerMetrics
        ServerMetrics metrics = storage.getServerMetrics();
        metrics.resetStartTime(storage.now());
        metrics.setListeningPort(getPort());
        metrics.setClientDirectory(this::getConnectedClientSnapshots);
        if (metrics.getShutdownCoordinator() == null) {
            metrics.setShutdownCoordinator((saveSnapshot, skipPersistence) -> initiateDefaultShutdown(skipPersistence));
        }

        acceptThread = new Thread(this::runAcceptLoop, "miniredis-tcp-acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void initiateDefaultShutdown(boolean skipPersistence) {
        if (!shutdownInitiated.compareAndSet(false, true)) {
            return;
        }
        Thread shutdownThread = new Thread(() -> {
            // Brief yield so in-flight TCP reply (+OK) flushes cleanly
            try {
                Thread.sleep(15);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            stop();
            AofManager aof = storage.getAofManager();
            if (aof != null && !aof.isClosed()) {
                aof.close();
            }
        }, "miniredis-shutdown");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
    }

    private void runAcceptLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                storage.getServerMetrics().incrementConnectionsReceived();
                long id = clientIdSequence.getAndIncrement();
                RedisClientHandler handler = new RedisClientHandler(
                        id,
                        clientSocket,
                        storage,
                        registry,
                        activeClients::remove
                );
                activeClients.add(handler);
                workerPool.submit(handler);
            } catch (SocketException e) {
                // ServerSocket was closed during stop()
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("MiniRedis TCP accept error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Stops the TCP server, closes all active client connections, and shuts down the worker pool.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;

        // 1. Close ServerSocket to unblock accept()
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }

        // 2. Close all active client connections
        for (RedisClientHandler client : activeClients) {
            client.close();
        }
        activeClients.clear();

        // 3. Shut down worker pool
        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(2, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 4. Wait for acceptor thread to terminate
        if (acceptThread != null) {
            try {
                acceptThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public List<ServerMetrics.ClientSnapshot> getConnectedClientSnapshots() {
        long now = storage.now();
        List<ServerMetrics.ClientSnapshot> snapshots = new ArrayList<>(activeClients.size());
        for (RedisClientHandler client : activeClients) {
            snapshots.add(client.toSnapshot(now));
        }
        snapshots.sort((a, b) -> Long.compare(a.id, b.id));
        return snapshots;
    }

    /**
     * Returns the port the server is listening on.
     * Useful when port 0 was passed to obtain the OS-allocated ephemeral port.
     */
    public int getPort() {
        if (serverSocket != null && serverSocket.isBound()) {
            return serverSocket.getLocalPort();
        }
        return port;
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    public int getActiveClientCount() {
        return activeClients.size();
    }

    public int getActiveWorkerThreads() {
        return workerPool != null ? workerPool.getActiveCount() : 0;
    }

    public int getAvailableWorkerThreads() {
        return workerPool != null ? Math.max(0, maxWorkers - workerPool.getActiveCount()) : maxWorkers;
    }

    public int getPendingQueueTasks() {
        return workerPool != null ? workerPool.getQueue().size() : 0;
    }

    public int getMaxWorkers() {
        return maxWorkers;
    }

    public StorageEngine getStorage() {
        return storage;
    }

    public CommandRegistry getRegistry() {
        return registry;
    }
}
