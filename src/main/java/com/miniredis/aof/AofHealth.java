package com.miniredis.aof;

/**
 * Health and operational status report for MiniRedis AOF persistence.
 */
public class AofHealth {

    public enum Status {
        HEALTHY,
        DEGRADED,
        FAILING
    }

    private final Status status;
    private final String lastError;
    private final FsyncPolicy fsyncPolicy;
    private final long lastSuccessfulFsyncTime;
    private final long lastFsyncDurationMs;
    private final int pendingQueueSize;
    private final long totalLoggedCommands;
    private final long totalFsyncs;
    private final boolean isRewriting;
    private final long lastRewriteDurationMs;
    private final long recoveryDurationMs;
    private final int recoveryErrors;
    private final long fileSizeBytes;

    public AofHealth(Status status, String lastError, FsyncPolicy fsyncPolicy,
                     long lastSuccessfulFsyncTime, long lastFsyncDurationMs,
                     int pendingQueueSize, long totalLoggedCommands, long totalFsyncs,
                     boolean isRewriting, long lastRewriteDurationMs,
                     long recoveryDurationMs, int recoveryErrors, long fileSizeBytes) {
        this.status = status;
        this.lastError = lastError;
        this.fsyncPolicy = fsyncPolicy;
        this.lastSuccessfulFsyncTime = lastSuccessfulFsyncTime;
        this.lastFsyncDurationMs = lastFsyncDurationMs;
        this.pendingQueueSize = pendingQueueSize;
        this.totalLoggedCommands = totalLoggedCommands;
        this.totalFsyncs = totalFsyncs;
        this.isRewriting = isRewriting;
        this.lastRewriteDurationMs = lastRewriteDurationMs;
        this.recoveryDurationMs = recoveryDurationMs;
        this.recoveryErrors = recoveryErrors;
        this.fileSizeBytes = fileSizeBytes;
    }

    public Status getStatus() {
        return status;
    }

    public String getLastError() {
        return lastError;
    }

    public FsyncPolicy getFsyncPolicy() {
        return fsyncPolicy;
    }

    public long getLastSuccessfulFsyncTime() {
        return lastSuccessfulFsyncTime;
    }

    public long getLastFsyncDurationMs() {
        return lastFsyncDurationMs;
    }

    public int getPendingQueueSize() {
        return pendingQueueSize;
    }

    public long getTotalLoggedCommands() {
        return totalLoggedCommands;
    }

    public long getTotalFsyncs() {
        return totalFsyncs;
    }

    public boolean isRewriting() {
        return isRewriting;
    }

    public long getLastRewriteDurationMs() {
        return lastRewriteDurationMs;
    }

    public long getRecoveryDurationMs() {
        return recoveryDurationMs;
    }

    public int getRecoveryErrors() {
        return recoveryErrors;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }
}
