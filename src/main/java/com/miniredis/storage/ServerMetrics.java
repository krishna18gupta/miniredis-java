package com.miniredis.storage;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe runtime telemetry and lifecycle registry for MiniRedis.
 * Shared cleanly across StorageEngine, RedisServer, INFO/CLIENT commands,
 * and the HTTP Web Console without introducing lock contention.
 */
public class ServerMetrics {

    @FunctionalInterface
    public interface ClientDirectory {
        List<ClientSnapshot> getConnectedClients();
    }

    @FunctionalInterface
    public interface ShutdownCoordinator {
        /**
         * Initiates graceful server termination.
         *
         * @param saveSnapshot     true if a synchronous snapshot/rewrite compaction was requested (SHUTDOWN SAVE)
         * @param skipPersistence  true if persistence save/flush should be skipped (SHUTDOWN NOSAVE)
         */
        void initiateShutdown(boolean saveSnapshot, boolean skipPersistence);
    }

    public static class ClientSnapshot {
        public final long id;
        public final String address;
        public final String name;
        public final long ageSeconds;
        public final long idleSeconds;
        public final int multiQueueSize;
        public final String lastCommand;

        public ClientSnapshot(long id, String address, String name, long ageSeconds,
                              long idleSeconds, int multiQueueSize, String lastCommand) {
            this.id = id;
            this.address = address;
            this.name = name != null ? name : "";
            this.ageSeconds = Math.max(0, ageSeconds);
            this.idleSeconds = Math.max(0, idleSeconds);
            this.multiQueueSize = multiQueueSize;
            this.lastCommand = lastCommand != null ? lastCommand : "none";
        }

        public String toClientListLine() {
            String flags = multiQueueSize >= 0 ? "x" : "N";
            return String.format(
                    "id=%d addr=%s name=%s age=%d idle=%d flags=%s db=0 sub=0 psub=0 multi=%d qbuf=0 qbuf-free=0 argv-mem=0 obl=0 oll=0 omem=0 tot-mem=0 events=r cmd=%s",
                    id, address, name, ageSeconds, idleSeconds, flags, multiQueueSize, lastCommand
            );
        }
    }

    private final java.util.function.LongSupplier timeSource;
    private volatile long startTimeMillis;
    private final LongAdder totalConnectionsReceived = new LongAdder();
    private final LongAdder keyspaceHits = new LongAdder();
    private final LongAdder keyspaceMisses = new LongAdder();

    private volatile ClientDirectory clientDirectory;
    private volatile ShutdownCoordinator shutdownCoordinator;
    private volatile int listeningPort = 6379;

    public ServerMetrics(Clock clock) {
        this(() -> Objects.requireNonNull(clock, "clock cannot be null").millis());
    }

    public ServerMetrics(java.util.function.LongSupplier timeSource) {
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource cannot be null");
        this.startTimeMillis = timeSource.getAsLong();
    }

    public void resetStartTime(long startTimeMillis) {
        this.startTimeMillis = startTimeMillis;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public long getUptimeSeconds() {
        return Math.max(0L, (timeSource.getAsLong() - startTimeMillis) / 1000L);
    }

    public long getUptimeDays() {
        return getUptimeSeconds() / 86_400L;
    }

    public void incrementConnectionsReceived() {
        totalConnectionsReceived.increment();
    }

    public long getTotalConnectionsReceived() {
        return totalConnectionsReceived.sum();
    }

    public void recordHit() {
        keyspaceHits.increment();
    }

    public void recordMiss() {
        keyspaceMisses.increment();
    }

    public long getKeyspaceHits() {
        return keyspaceHits.sum();
    }

    public long getKeyspaceMisses() {
        return keyspaceMisses.sum();
    }

    public int getListeningPort() {
        return listeningPort;
    }

    public void setListeningPort(int listeningPort) {
        this.listeningPort = listeningPort;
    }

    public void setClientDirectory(ClientDirectory clientDirectory) {
        this.clientDirectory = clientDirectory;
    }

    public List<ClientSnapshot> getConnectedClients() {
        ClientDirectory dir = clientDirectory;
        if (dir == null) {
            return Collections.emptyList();
        }
        return dir.getConnectedClients();
    }

    public int getConnectedClientCount() {
        return getConnectedClients().size();
    }

    public void setShutdownCoordinator(ShutdownCoordinator shutdownCoordinator) {
        this.shutdownCoordinator = shutdownCoordinator;
    }

    public ShutdownCoordinator getShutdownCoordinator() {
        return shutdownCoordinator;
    }
}
