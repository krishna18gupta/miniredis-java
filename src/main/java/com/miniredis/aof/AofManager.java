package com.miniredis.aof;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.protocol.RespParser;
import com.miniredis.protocol.RespProtocolException;
import com.miniredis.protocol.RespSerializer;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.RedisDataType;
import com.miniredis.storage.RedisValue;
import com.miniredis.storage.StorageEngine;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production-grade Append-Only File (AOF) Persistence Engine for MiniRedis.
 * Commands are persisted using standard Redis RESP2 wire protocol (*<count>\r\n...).
 *
 * Core Capabilities:
 * - Configurable Fsync Policies: ALWAYS, EVERYSEC, NO with strict acknowledgement guarantees.
 * - Bounded write queue and failure tracking (disk-full, write errors, worker failure).
 * - Safe concurrent AOF rewriting (BGREWRITEAOF) with a Rewrite Diff Buffer for zero lost writes.
 * - Startup crash recovery with corrupt tail truncation resilience and temporary file cleanup.
 * - Comprehensive health reporting (AofHealth).
 */
public class AofManager implements AutoCloseable {

    public static final int DEFAULT_MAX_QUEUE_SIZE = 10_000;

    private final File aofFile;
    private final File persistenceDir;
    private final FsyncPolicy fsyncPolicy;
    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final int maxQueueSize;

    private final Object writeLock = new Object();
    private FileOutputStream fileOutputStream;
    private FileChannel fileChannel;

    private final ScheduledExecutorService fsyncExecutor;
    private final ExecutorService rewriteExecutor;
    private final AtomicBoolean isRewriting = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<byte[]> rewriteDiffBuffer = new ConcurrentLinkedQueue<>();

    private volatile boolean closed = false;
    private volatile boolean suspended = false;
    private volatile CompletableFuture<Void> activeRewriteFuture;

    // Health and Operational Metrics
    private volatile AofHealth.Status healthStatus = AofHealth.Status.HEALTHY;
    private volatile String lastError = null;
    private final AtomicLong totalLoggedCommands = new AtomicLong(0);
    private final AtomicLong totalFsyncs = new AtomicLong(0);
    private final AtomicInteger pendingQueueSize = new AtomicInteger(0);
    private volatile long lastSuccessfulFsyncTime = System.currentTimeMillis();
    private volatile long lastFsyncDurationMs = 0;
    private volatile long lastRewriteDurationMs = 0;
    private volatile long recoveryDurationMs = 0;
    private volatile int recoveryErrors = 0;

    public AofManager(File aofFile, FsyncPolicy fsyncPolicy, StorageEngine storage, CommandRegistry registry) {
        this(aofFile.getParentFile() != null ? aofFile.getParentFile() : new File("."),
             aofFile.getName(), fsyncPolicy, storage, registry, DEFAULT_MAX_QUEUE_SIZE);
    }

    public AofManager(File persistenceDir, String fileName, FsyncPolicy fsyncPolicy,
                      StorageEngine storage, CommandRegistry registry, int maxQueueSize) {
        this.persistenceDir = persistenceDir != null ? persistenceDir : new File(".");
        this.aofFile = new File(this.persistenceDir, fileName != null ? fileName : "appendonly.aof");
        this.fsyncPolicy = fsyncPolicy != null ? fsyncPolicy : FsyncPolicy.EVERYSEC;
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.maxQueueSize = maxQueueSize > 0 ? maxQueueSize : DEFAULT_MAX_QUEUE_SIZE;

        this.fsyncExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "miniredis-aof-fsync");
            t.setDaemon(true);
            return t;
        });

        this.rewriteExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "miniredis-aof-rewrite");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Initializes persistence directory, opens AOF file, and starts background workers.
     */
    public synchronized void start() throws IOException {
        if (!persistenceDir.exists()) {
            persistenceDir.mkdirs();
        }

        // Clean up any stale rewrite temporary files left from an interrupted previous run
        cleanupStaleTempFiles();

        openFile();

        if (fsyncPolicy == FsyncPolicy.EVERYSEC) {
            fsyncExecutor.scheduleAtFixedRate(this::scheduledFsync, 1, 1, TimeUnit.SECONDS);
        }
    }

    private void openFile() throws IOException {
        synchronized (writeLock) {
            this.fileOutputStream = new FileOutputStream(aofFile, true);
            this.fileChannel = fileOutputStream.getChannel();
        }
    }

    private void cleanupStaleTempFiles() {
        File[] stale = persistenceDir.listFiles((dir, name) -> name.endsWith(".tmp"));
        if (stale != null) {
            for (File f : stale) {
                f.delete();
            }
        }
    }

    /**
     * Replays all commands from the AOF file to restore in-memory database state.
     * Guarantees zero duplicate execution and skips/cleans expired keys.
     */
    public int recover() throws IOException {
        long recoveryStart = System.currentTimeMillis();
        recoveryErrors = 0;

        cleanupStaleTempFiles();

        if (!aofFile.exists() || aofFile.length() == 0) {
            recoveryDurationMs = System.currentTimeMillis() - recoveryStart;
            return 0;
        }

        suspended = true;
        int replayedCount = 0;
        boolean inTransaction = false;
        List<List<ByteSequence>> txBufferBytes = new ArrayList<>();

        try (InputStream in = new BufferedInputStream(new FileInputStream(aofFile))) {
            while (true) {
                RespParser.CommandPayload payload;
                try {
                    payload = RespParser.readCommand(in);
                } catch (EOFException e) {
                    // Truncated tail at end of crash: stop replay gracefully
                    break;
                } catch (RespProtocolException e) {
                    // Malformed or corrupted bytes in file
                    recoveryErrors++;
                    healthStatus = AofHealth.Status.DEGRADED;
                    lastError = "AOF Recovery warning: " + e.getMessage();
                    break;
                } catch (IOException e) {
                    recoveryErrors++;
                    healthStatus = AofHealth.Status.DEGRADED;
                    lastError = "AOF I/O warning during replay: " + e.getMessage();
                    break;
                }

                if (payload == null) {
                    break; // Normal EOF
                }

                List<ByteSequence> rawTokens = payload.getRawTokens();
                if (rawTokens != null && !rawTokens.isEmpty()) {
                    String cmdUpper = rawTokens.get(0).toUtf8String().toUpperCase(Locale.ROOT);
                    if ("MULTI".equals(cmdUpper)) {
                        if (inTransaction) {
                            // Previous transaction never finished with EXEC
                            recoveryErrors++;
                        }
                        inTransaction = true;
                        txBufferBytes.clear();
                    } else if ("EXEC".equals(cmdUpper)) {
                        if (!inTransaction) {
                            recoveryErrors++;
                        } else {
                            for (List<ByteSequence> txCmd : txBufferBytes) {
                                RedisResponse resp = registry.dispatchBytes(txCmd, storage);
                                if (resp != null && resp.getType() != RedisResponse.ResponseType.ERROR) {
                                    replayedCount++;
                                } else {
                                    recoveryErrors++;
                                }
                            }
                            txBufferBytes.clear();
                            inTransaction = false;
                        }
                    } else if (inTransaction) {
                        txBufferBytes.add(rawTokens);
                    } else {
                        RedisResponse resp = registry.dispatchBytes(rawTokens, storage);
                        if (resp != null && resp.getType() != RedisResponse.ResponseType.ERROR) {
                            replayedCount++;
                        } else {
                            recoveryErrors++;
                        }
                    }
                }
            }

            // Never replay an incomplete trailing transaction missing EXEC
            if (inTransaction) {
                txBufferBytes.clear();
                recoveryErrors++;
                healthStatus = AofHealth.Status.DEGRADED;
                lastError = "AOF Recovery discarded incomplete trailing transaction";
            }
        } finally {
            suspended = false;
            recoveryDurationMs = System.currentTimeMillis() - recoveryStart;
        }

        return replayedCount;
    }

    /**
     * Appends a mutating command to the AOF log file in RESP format.
     * Enforces strict acknowledgement semantics based on FsyncPolicy.
     *
     * @throws AofException if write queue is full, disk write fails, or fsync fails under ALWAYS.
     */
    public void append(List<String> tokens) {
        appendCommand(tokens);
    }

    public void appendCommand(List<String> tokens) {
        if (closed || suspended || tokens == null || tokens.isEmpty()) {
            return;
        }

        byte[] encoded = RespSerializer.encodeCommand(tokens);
        appendEncodedBlock(encoded, 1);
    }

    public void appendCommandBytes(List<ByteSequence> tokens) {
        if (closed || suspended || tokens == null || tokens.isEmpty()) {
            return;
        }

        byte[] encoded = RespSerializer.encodeCommandBytes(tokens);
        appendEncodedBlock(encoded, 1);
    }

    /**
     * Atomically appends an entire committed transaction wrapped in MULTI ... EXEC
     * under a single writeLock section so concurrent writers never interleave.
     */
    public void appendTransaction(List<List<String>> commands) {
        if (closed || suspended || commands == null || commands.isEmpty()) {
            return;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(commands.size() * 64 + 64);
        try {
            baos.write(RespSerializer.encodeCommand(List.of("MULTI")));
            for (List<String> cmd : commands) {
                if (cmd != null && !cmd.isEmpty()) {
                    baos.write(RespSerializer.encodeCommand(cmd));
                }
            }
            baos.write(RespSerializer.encodeCommand(List.of("EXEC")));
        } catch (IOException e) {
            throw new AofException("Failed to serialize AOF transaction block", e);
        }

        appendEncodedBlock(baos.toByteArray(), commands.size() + 2);
    }

    public void appendTransactionBytes(List<List<ByteSequence>> commands) {
        if (closed || suspended || commands == null || commands.isEmpty()) {
            return;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(commands.size() * 64 + 64);
        try {
            baos.write(RespSerializer.encodeCommand(List.of("MULTI")));
            for (List<ByteSequence> cmd : commands) {
                if (cmd != null && !cmd.isEmpty()) {
                    baos.write(RespSerializer.encodeCommandBytes(cmd));
                }
            }
            baos.write(RespSerializer.encodeCommand(List.of("EXEC")));
        } catch (IOException e) {
            throw new AofException("Failed to serialize AOF transaction block", e);
        }

        appendEncodedBlock(baos.toByteArray(), commands.size() + 2);
    }

    private void appendEncodedBlock(byte[] encoded, int commandCount) {
        if (pendingQueueSize.get() >= maxQueueSize) {
            healthStatus = AofHealth.Status.FAILING;
            lastError = "AOF write queue overflow: " + pendingQueueSize.get() + " >= " + maxQueueSize;
            throw new AofException(lastError);
        }

        pendingQueueSize.incrementAndGet();

        try {
            synchronized (writeLock) {
                if (closed || fileOutputStream == null) return;

                try {
                    fileOutputStream.write(encoded);

                    // If a background rewrite is in progress, capture this write in the diff buffer!
                    if (isRewriting.get()) {
                        rewriteDiffBuffer.offer(encoded);
                    }

                    if (fsyncPolicy == FsyncPolicy.ALWAYS) {
                        long syncStart = System.currentTimeMillis();
                        fileOutputStream.flush();
                        if (fileChannel != null && fileChannel.isOpen()) {
                            fileChannel.force(false);
                        }
                        lastFsyncDurationMs = System.currentTimeMillis() - syncStart;
                        lastSuccessfulFsyncTime = System.currentTimeMillis();
                        totalFsyncs.incrementAndGet();
                    }

                    totalLoggedCommands.addAndGet(commandCount);
                    healthStatus = AofHealth.Status.HEALTHY;
                } catch (IOException e) {
                    healthStatus = AofHealth.Status.FAILING;
                    lastError = "AOF disk write failure: " + e.getMessage();
                    if (fsyncPolicy == FsyncPolicy.ALWAYS) {
                        throw new AofException("AOF persistence failure: " + e.getMessage(), e);
                    }
                }
            }
        } finally {
            pendingQueueSize.decrementAndGet();
        }
    }

    /**
     * Forces immediate fsync to disk.
     */
    public void sync() {
        synchronized (writeLock) {
            if (fileChannel != null && fileChannel.isOpen()) {
                long syncStart = System.currentTimeMillis();
                try {
                    fileOutputStream.flush();
                    fileChannel.force(false);
                    lastFsyncDurationMs = System.currentTimeMillis() - syncStart;
                    lastSuccessfulFsyncTime = System.currentTimeMillis();
                    totalFsyncs.incrementAndGet();
                    healthStatus = AofHealth.Status.HEALTHY;
                } catch (IOException e) {
                    healthStatus = AofHealth.Status.FAILING;
                    lastError = "AOF manual fsync failed: " + e.getMessage();
                }
            }
        }
    }

    private void scheduledFsync() {
        try {
            sync();
        } catch (Exception e) {
            healthStatus = AofHealth.Status.DEGRADED;
            lastError = "AOF background fsync error: " + e.getMessage();
        }
    }

    /**
     * Asynchronously triggers AOF compaction/rewriting in the background.
     * Returns a CompletableFuture that tracks the rewrite lifecycle.
     */
    public synchronized CompletableFuture<Void> bgRewrite() {
        if (activeRewriteFuture != null && !activeRewriteFuture.isDone()) {
            return activeRewriteFuture;
        }

        activeRewriteFuture = CompletableFuture.runAsync(() -> {
            try {
                rewrite();
            } catch (IOException e) {
                healthStatus = AofHealth.Status.DEGRADED;
                lastError = "AOF background rewrite error: " + e.getMessage();
                System.err.println("MiniRedis AOF Rewrite Failed: " + e.getMessage());
            }
        }, rewriteExecutor);
        return activeRewriteFuture;
    }

    /**
     * Synchronously compacts the database into a clean minimal AOF file.
     * Uses a Rewrite Diff Buffer so that concurrent writes occurring during rewriting
     * are safely captured and appended in correct order before atomic file replacement.
     */
    public void rewrite() throws IOException {
        long rewriteStart = System.currentTimeMillis();

        File tempFile = new File(aofFile.getAbsolutePath() + ".rewrite.tmp");
        if (tempFile.exists()) {
            tempFile.delete();
        }

        try {
            // 1. Capture snapshot under allReadLocks and activate diff buffer atomically
            List<StorageEngine.SnapshotEntry> snapshot = storage.createSnapshot(() -> {
                rewriteDiffBuffer.clear();
                isRewriting.set(true);
            });

            // 2. Write snapshot to temporary file
            FileOutputStream tempOut = new FileOutputStream(tempFile);
            BufferedOutputStream bufOut = new BufferedOutputStream(tempOut);
            try {
                for (StorageEngine.SnapshotEntry entry : snapshot) {
                    ByteSequence key = entry.keyBytes != null ? entry.keyBytes : ByteSequence.ofUtf8(entry.key);
                    RedisValue val = entry.value;
                    Long expireAt = entry.expireAt;

                    List<List<ByteSequence>> commandsToLog = new ArrayList<>();

                    if (val.getType() == RedisDataType.STRING) {
                        if (expireAt != null) {
                            commandsToLog.add(List.of(ByteSequence.ofAscii("SET"), key, val.getAsByteSequence(), ByteSequence.ofAscii("PXAT"), ByteSequence.ofAscii(String.valueOf(expireAt))));
                        } else {
                            commandsToLog.add(List.of(ByteSequence.ofAscii("SET"), key, val.getAsByteSequence()));
                        }
                    } else if (val.getType() == RedisDataType.LIST) {
                        Deque<ByteSequence> list = val.getAsByteList();
                        if (!list.isEmpty()) {
                            List<ByteSequence> rpush = new ArrayList<>(list.size() + 2);
                            rpush.add(ByteSequence.ofAscii("RPUSH"));
                            rpush.add(key);
                            rpush.addAll(list);
                            commandsToLog.add(rpush);
                        }
                        if (expireAt != null) {
                            commandsToLog.add(List.of(ByteSequence.ofAscii("PEXPIREAT"), key, ByteSequence.ofAscii(String.valueOf(expireAt))));
                        }
                    } else if (val.getType() == RedisDataType.SET) {
                        Set<ByteSequence> set = val.getAsByteSet();
                        if (!set.isEmpty()) {
                            List<ByteSequence> sadd = new ArrayList<>(set.size() + 2);
                            sadd.add(ByteSequence.ofAscii("SADD"));
                            sadd.add(key);
                            sadd.addAll(set);
                            commandsToLog.add(sadd);
                        }
                        if (expireAt != null) {
                            commandsToLog.add(List.of(ByteSequence.ofAscii("PEXPIREAT"), key, ByteSequence.ofAscii(String.valueOf(expireAt))));
                        }
                    } else if (val.getType() == RedisDataType.HASH) {
                        Map<ByteSequence, ByteSequence> map = val.getAsByteHash();
                        if (!map.isEmpty()) {
                            List<ByteSequence> hset = new ArrayList<>(map.size() * 2 + 2);
                            hset.add(ByteSequence.ofAscii("HSET"));
                            hset.add(key);
                            for (Map.Entry<ByteSequence, ByteSequence> fieldEntry : map.entrySet()) {
                                hset.add(fieldEntry.getKey());
                                hset.add(fieldEntry.getValue());
                            }
                            commandsToLog.add(hset);
                        }
                        if (expireAt != null) {
                            commandsToLog.add(List.of(ByteSequence.ofAscii("PEXPIREAT"), key, ByteSequence.ofAscii(String.valueOf(expireAt))));
                        }
                    }

                    for (List<ByteSequence> cmd : commandsToLog) {
                        byte[] bytes = RespSerializer.encodeCommandBytes(cmd);
                        bufOut.write(bytes);
                    }
                }

                bufOut.flush();

                // 3 & 4. Under writeLock, drain the diff buffer, close handles, atomically replace file, and reopen!
                synchronized (writeLock) {
                    byte[] diffCommand;
                    while ((diffCommand = rewriteDiffBuffer.poll()) != null) {
                        bufOut.write(diffCommand);
                    }
                    bufOut.flush();
                    tempOut.getChannel().force(false);

                    // Close temporary file streams before rename
                    bufOut.close();
                    tempOut.close();

                    // Close active file before atomic replacement
                    if (fileChannel != null) {
                        try {
                            fileChannel.close();
                        } catch (IOException ignored) {}
                        fileChannel = null;
                    }
                    if (fileOutputStream != null) {
                        try {
                            fileOutputStream.close();
                        } catch (IOException ignored) {}
                        fileOutputStream = null;
                    }

                    try {
                        Files.move(tempFile.toPath(), aofFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tempFile.toPath(), aofFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }

                    openFile();
                    isRewriting.set(false);
                    rewriteDiffBuffer.clear();
                }
            } finally {
                try { bufOut.close(); } catch (Exception ignored) {}
                try { tempOut.close(); } catch (Exception ignored) {}
            }

            lastRewriteDurationMs = System.currentTimeMillis() - rewriteStart;
            healthStatus = AofHealth.Status.HEALTHY;
        } catch (Exception e) {
            // Clean up temporary file on failure
            if (tempFile.exists()) {
                tempFile.delete();
            }
            healthStatus = AofHealth.Status.DEGRADED;
            lastError = "AOF rewrite error: " + e.getMessage();
            throw e;
        } finally {
            isRewriting.set(false);
            rewriteDiffBuffer.clear();
        }
    }

    /**
     * Cleanly shuts down workers and flushes remaining buffers before closing file descriptors.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;

        fsyncExecutor.shutdown();
        rewriteExecutor.shutdown();
        try {
            rewriteExecutor.awaitTermination(3, TimeUnit.SECONDS);
            fsyncExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        synchronized (writeLock) {
            try {
                if (fileOutputStream != null) {
                    fileOutputStream.flush();
                    if (fileChannel != null && fileChannel.isOpen()) {
                        fileChannel.force(false);
                    }
                    fileOutputStream.close();
                    fileOutputStream = null;
                    fileChannel = null;
                }
            } catch (IOException ignored) {}
        }
    }

    /**
     * Returns an immutable snapshot of AOF persistence health and operational metrics.
     */
    public AofHealth getHealth() {
        return new AofHealth(
                healthStatus,
                lastError,
                fsyncPolicy,
                lastSuccessfulFsyncTime,
                lastFsyncDurationMs,
                pendingQueueSize.get(),
                totalLoggedCommands.get(),
                totalFsyncs.get(),
                isRewriting.get(),
                lastRewriteDurationMs,
                recoveryDurationMs,
                recoveryErrors,
                getFileSize()
        );
    }

    public File getAofFile() {
        return aofFile;
    }

    public File getPersistenceDir() {
        return persistenceDir;
    }

    public FsyncPolicy getFsyncPolicy() {
        return fsyncPolicy;
    }

    public long getFileSize() {
        return aofFile.exists() ? aofFile.length() : 0;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isRewriting() {
        return isRewriting.get();
    }

    /**
     * Diagnostic and fault-injection utility to simulate sudden I/O / disk failure.
     */
    public void simulateDiskFailure() throws IOException {
        synchronized (writeLock) {
            if (fileOutputStream != null) {
                fileOutputStream.close();
            }
        }
    }
}
