package com.miniredis.aof;

/**
 * Fsync synchronization policies for MiniRedis AOF persistence.
 */
public enum FsyncPolicy {
    /**
     * Fsync to disk after every write operation.
     * Maximum durability, lowest throughput.
     */
    ALWAYS,

    /**
     * Flush OS buffers every second via a dedicated background daemon thread.
     * Balanced durability (max 1s data loss) and high throughput. Default.
     */
    EVERYSEC,

    /**
     * Do not force fsync; rely entirely on OS filesystem page cache write-backs.
     * Maximum throughput, durability depends on OS.
     */
    NO
}
