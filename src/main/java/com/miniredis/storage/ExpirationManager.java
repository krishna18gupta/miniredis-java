package com.miniredis.storage;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated background reaper for active key expiration in MiniRedis.
 * Uses a ScheduledExecutorService with daemon threads running an adaptive
 * randomized sampling algorithm modeled after Redis activeExpireCycle.
 */
public class ExpirationManager {
    public static final long DEFAULT_INTERVAL_MS = 100;
    public static final int DEFAULT_SAMPLE_SIZE = 20;

    private final StorageEngine storage;
    private final long intervalMs;
    private final int sampleSize;
    private final AtomicBoolean isRunning;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;

    public ExpirationManager(StorageEngine storage) {
        this(storage, DEFAULT_INTERVAL_MS, DEFAULT_SAMPLE_SIZE);
    }

    public ExpirationManager(StorageEngine storage, long intervalMs, int sampleSize) {
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.intervalMs = intervalMs;
        this.sampleSize = sampleSize;
        this.isRunning = new AtomicBoolean(false);
    }

    /**
     * Starts the background expiration reaper.
     * Prevents duplicate scheduler initialization.
     */
    public synchronized void start() {
        if (!isRunning.compareAndSet(false, true)) {
            return; // Already running
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "miniredis-expiration-reaper");
            thread.setDaemon(true); // Daemon thread ensures clean JVM exit
            return thread;
        });

        this.scheduledTask = this.scheduler.scheduleWithFixedDelay(
                this::runSafeCycle,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stops the background expiration reaper.
     */
    public synchronized void stop() {
        if (!isRunning.compareAndSet(true, false)) {
            return; // Already stopped
        }

        if (scheduledTask != null) {
            scheduledTask.cancel(true);
            scheduledTask = null;
        }

        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    private void runSafeCycle() {
        try {
            runActiveExpireCycle();
        } catch (Throwable t) {
            // Catch unexpected exceptions to ensure the scheduler continues executing
            System.err.println("WARN: ExpirationManager active cycle encountered an error: " + t.getMessage());
        }
    }

    /**
     * Executes an active expiration cycle using adaptive sampling.
     * If more than 25% of sampled keys are expired, it immediately loops again
     * (up to maxIterations) to aggressively reclaim memory without blocking the server.
     *
     * @return total count of expired keys removed during this cycle.
     */
    public int runActiveExpireCycle() {
        int totalPurged = 0;
        int iterations = 0;
        int maxIterations = 10;

        while (iterations++ < maxIterations) {
            StorageEngine.SampleResult result = storage.sampleAndEvict(sampleSize);
            totalPurged += result.purgedCount;

            // If no keys were sampled, or <= 25% were expired, we have cleared the bulk of expired keys
            if (result.sampledCount == 0 || ((double) result.purgedCount / result.sampledCount) <= 0.25) {
                break;
            }
        }

        return totalPurged;
    }
}