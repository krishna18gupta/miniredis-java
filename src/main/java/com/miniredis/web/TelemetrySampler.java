package com.miniredis.web;

import com.miniredis.storage.StorageEngine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * High-performance, non-blocking telemetry recorder for MiniRedis.
 * Periodically samples throughput, memory consumption, keyspace volume,
 * and concurrency metrics into a fixed-capacity ring buffer to power
 * real-time dashboard analytics without fabricating historical data.
 */
public class TelemetrySampler {

    public static class Sample {
        public final long timestamp;
        public final long opsPerSec;
        public final long estimatedMemoryBytes;
        public final long jvmUsedMemoryBytes;
        public final int keyCount;

        public Sample(long timestamp, long opsPerSec, long estimatedMemoryBytes, long jvmUsedMemoryBytes, int keyCount) {
            this.timestamp = timestamp;
            this.opsPerSec = opsPerSec;
            this.estimatedMemoryBytes = estimatedMemoryBytes;
            this.jvmUsedMemoryBytes = jvmUsedMemoryBytes;
            this.keyCount = keyCount;
        }
    }

    private final StorageEngine storage;
    private final int maxSamples;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final ScheduledExecutorService scheduler;
    private long lastOpsCount;
    private long lastSampleTime;
    private volatile long currentOpsPerSec;

    public TelemetrySampler(StorageEngine storage) {
        this(storage, 60);
    }

    public TelemetrySampler(StorageEngine storage, int maxSamples) {
        this.storage = storage;
        this.maxSamples = Math.max(10, maxSamples);
        this.lastSampleTime = System.currentTimeMillis();
        this.lastOpsCount = storage != null ? storage.getTotalCompletedOperations() : 0;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "miniredis-telemetry-sampler");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::collectSample, 2, 2, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private synchronized void collectSample() {
        if (storage == null) return;
        long now = System.currentTimeMillis();
        long currentOps = storage.getTotalCompletedOperations();
        long deltaOps = Math.max(0, currentOps - lastOpsCount);
        double deltaSec = Math.max(0.1, (now - lastSampleTime) / 1000.0);
        long opsRate = (long) (deltaOps / deltaSec);
        this.currentOpsPerSec = opsRate;
        this.lastOpsCount = currentOps;
        this.lastSampleTime = now;

        Runtime runtime = Runtime.getRuntime();
        long jvmUsed = runtime.totalMemory() - runtime.freeMemory();
        long dbEstimated = storage.computeKeyspaceStats().estimatedMemoryBytes;
        int keyCount = storage.dbsize();

        if (samples.size() >= maxSamples) {
            samples.pollFirst();
        }
        samples.addLast(new Sample(now, opsRate, dbEstimated, jvmUsed, keyCount));
    }

    public long getCurrentOpsPerSec() {
        return currentOpsPerSec;
    }

    public synchronized String toJsonArray() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Sample s : samples) {
            if (!first) sb.append(",");
            first = false;
            sb.append(String.format(
                    "{\"t\":%d,\"ops\":%d,\"mem\":%d,\"jvm\":%d,\"keys\":%d}",
                    s.timestamp, s.opsPerSec, s.estimatedMemoryBytes, s.jvmUsedMemoryBytes, s.keyCount
            ));
        }
        sb.append("]");
        return sb.toString();
    }
}
