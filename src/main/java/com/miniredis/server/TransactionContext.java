package com.miniredis.server;

import com.miniredis.storage.StorageEngine;

import java.util.*;

/**
 * Per-connection transaction state for Redis MULTI / EXEC / DISCARD / WATCH / UNWATCH.
 * Each client connection maintains independent command queuing and optimistic CAS watch snapshots.
 */
public class TransactionContext {

    private boolean inMulti = false;
    private boolean execAbort = false;
    private final List<List<String>> queuedCommands = new ArrayList<>();
    private final Map<String, StorageEngine.WatchSnapshot> watchedKeys = new LinkedHashMap<>();

    public synchronized boolean isInMulti() {
        return inMulti;
    }

    public synchronized boolean isExecAbort() {
        return execAbort;
    }

    /**
     * Returns queued command count if inside MULTI, or -1 otherwise (matching Redis CLIENT LIST format).
     */
    public synchronized int getMultiQueueDepth() {
        return inMulti ? queuedCommands.size() : -1;
    }

    public synchronized void beginMulti() {
        this.inMulti = true;
        this.execAbort = false;
        this.queuedCommands.clear();
    }

    public synchronized void queueCommand(List<String> tokens) {
        this.queuedCommands.add(List.copyOf(tokens));
    }

    public synchronized void markExecAbort() {
        this.execAbort = true;
    }

    public synchronized void addWatch(String key, StorageEngine.WatchSnapshot snapshot) {
        // Preserve the earliest active watch baseline in the current transaction cycle
        this.watchedKeys.putIfAbsent(key, snapshot);
    }

    public synchronized void clearWatches() {
        this.watchedKeys.clear();
    }

    public synchronized Map<String, StorageEngine.WatchSnapshot> getWatchedKeys() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(watchedKeys));
    }

    public synchronized List<List<String>> getQueuedCommands() {
        return Collections.unmodifiableList(new ArrayList<>(queuedCommands));
    }

    public synchronized void resetAfterExecOrDiscard() {
        this.inMulti = false;
        this.execAbort = false;
        this.queuedCommands.clear();
        this.watchedKeys.clear();
    }
}
