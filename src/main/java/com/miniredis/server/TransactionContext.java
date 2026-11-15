package com.miniredis.server;

import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;

import java.util.*;

/**
 * Per-connection transaction state for Redis MULTI / EXEC / DISCARD / WATCH / UNWATCH.
 * Each client connection maintains independent command queuing and optimistic CAS watch snapshots.
 * Supports both binary ByteSequence tokens and legacy String tokens.
 */
public class TransactionContext {

    private boolean inMulti = false;
    private boolean execAbort = false;
    private final List<List<ByteSequence>> queuedCommandsBytes = new ArrayList<>();
    private final List<List<String>> queuedCommands = new ArrayList<>();
    private final Map<ByteSequence, StorageEngine.WatchSnapshot> watchedKeys = new LinkedHashMap<>();

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
        return inMulti ? queuedCommandsBytes.size() : -1;
    }

    public synchronized void beginMulti() {
        this.inMulti = true;
        this.execAbort = false;
        this.queuedCommandsBytes.clear();
        this.queuedCommands.clear();
    }

    public synchronized void queueCommandBytes(List<ByteSequence> tokens) {
        this.queuedCommandsBytes.add(List.copyOf(tokens));
        List<String> strTokens = new ArrayList<>(tokens.size());
        for (ByteSequence b : tokens) {
            strTokens.add(b.toUtf8String());
        }
        this.queuedCommands.add(Collections.unmodifiableList(strTokens));
    }

    public synchronized void queueCommand(List<String> tokens) {
        this.queuedCommands.add(List.copyOf(tokens));
        List<ByteSequence> byteTokens = new ArrayList<>(tokens.size());
        for (String s : tokens) {
            byteTokens.add(ByteSequence.ofUtf8(s));
        }
        this.queuedCommandsBytes.add(Collections.unmodifiableList(byteTokens));
    }

    public synchronized void markExecAbort() {
        this.execAbort = true;
    }

    public synchronized void addWatch(ByteSequence key, StorageEngine.WatchSnapshot snapshot) {
        this.watchedKeys.putIfAbsent(key, snapshot);
    }

    public synchronized void addWatch(String key, StorageEngine.WatchSnapshot snapshot) {
        if (key != null) {
            addWatch(ByteSequence.ofUtf8(key), snapshot);
        }
    }

    public synchronized void clearWatches() {
        this.watchedKeys.clear();
    }

    public synchronized Map<String, StorageEngine.WatchSnapshot> getWatchedKeys() {
        Map<String, StorageEngine.WatchSnapshot> map = new LinkedHashMap<>();
        for (Map.Entry<ByteSequence, StorageEngine.WatchSnapshot> entry : watchedKeys.entrySet()) {
            map.put(entry.getKey().toUtf8String(), entry.getValue());
        }
        return Collections.unmodifiableMap(map);
    }

    public synchronized Map<ByteSequence, StorageEngine.WatchSnapshot> getWatchedByteKeys() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(watchedKeys));
    }

    public synchronized List<List<String>> getQueuedCommands() {
        return Collections.unmodifiableList(new ArrayList<>(queuedCommands));
    }

    public synchronized List<List<ByteSequence>> getQueuedCommandsBytes() {
        return Collections.unmodifiableList(new ArrayList<>(queuedCommandsBytes));
    }

    public synchronized void resetAfterExecOrDiscard() {
        this.inMulti = false;
        this.execAbort = false;
        this.queuedCommandsBytes.clear();
        this.queuedCommands.clear();
        this.watchedKeys.clear();
    }
}
