# MiniRedis v1.0.0 Release Notes

**Release Date:** November 13, 2026  
**Artifact:** `miniredis-java-1.0.0.jar`  
**Compatibility:** Java 17+, RESP2 Protocol, Python `redis-py` 8.x (`protocol=2`), `redis-cli`, Jedis / Lettuce  
**Dependencies:** Zero external runtime dependencies (`java.base`, `jdk.httpserver`)  

---

## Executive Summary

MiniRedis v1.0.0 is the inaugural production-ready release of an in-memory, thread-safe, Redis-compatible database server engineered from first principles in Java 17. MiniRedis implements 57 Redis commands across strings, lists, sets, hashes, keyspace management, millisecond-precision expirations, optimistic multi-key transactions (`WATCH`/`MULTI`/`EXEC`), write-ahead AOF persistence with background compaction (`BGREWRITEAOF`), RESP2 wire protocol compliance, live telemetry, and an embedded Web Console GUI.

---

## Key Highlights & Capabilities

### 1. In-Memory Key-Value Core & Data Structures
- **Supported Data Types**: `STRING`, `LIST`, `SET`, `HASH`.
- **Strict Typing**: Enforces Redis-compliant `WRONGTYPE` error semantics.
- **57 Supported Commands**: Covering full data structure manipulation, key querying, pattern matching (`KEYS *`), database clearing (`FLUSHDB`), and sizing (`DBSIZE`).

### 2. Precise Expiration Engine (Dual Eviction Model)
- Sub-millisecond expiration tracking supporting both relative and absolute TTLs (`EXPIRE`, `PEXPIRE`, `PEXPIREAT`, `SETEX`, `PERSIST`, `TTL`, `PTTL`).
- Dual Eviction Architecture:
  - **Passive / Lazy Eviction**: Expired keys are intercepted and evicted on read or write access.
  - **Active Background Eviction**: A 100ms background sweep randomly samples keys with expirations and probabilistically continues until the expired key ratio falls below 25%.

### 3. Concurrency Architecture (256-Stripe Lock Manager)
- Replaces global server synchronization with a 256-stripe read-write lock pool (`StripedLockManager`).
- **Deadlock-Free Canonical Lock Ordering**: All multi-key operations (`MSET`, `MGET`, `RENAME`, and transactional execution) acquire locks sorted strictly by their stripe index in ascending order.
- **Linearizable Read Snapshots**: Multi-key queries (`MGET`) take deep copies under read locks, guaranteeing linearizable reads without torn state under heavy concurrent write load.

### 4. Append-Only File (AOF) Persistence & Compaction
- Write-Ahead Logging (WAL) ensuring durability across abrupt crashes and restarts.
- Three Fsync Policies:
  - `ALWAYS`: Synchronously flushes and fsyncs every mutating command to disk immediately.
  - `EVERYSEC`: Asynchronously buffers writes and fsyncs once per second via a dedicated daemon thread.
  - `NO`: Delegates fsync scheduling to the host operating system kernel.
- **Absolute Timestamp Normalization**: Automatically converts relative TTLs to absolute timestamps (`PEXPIREAT`) in the AOF stream, preventing TTL extensions on server restarts.
- **Background Compaction (`BGREWRITEAOF`)**: Non-blocking compaction forks an in-memory state snapshot while capturing concurrent incoming mutations into a diff buffer.
- **Crash Recovery & Truncation**: Automatically detects and safely truncates corrupt or incomplete trailing commands/transactions upon startup.

### 5. Optimistic CAS Transactions (`MULTI` / `EXEC` / `WATCH`)
- Full support for Redis transactions (`MULTI`, `EXEC`, `DISCARD`, `WATCH`, `UNWATCH`).
- **Compile-Time Error Tracking (`EXECABORT`)**: Automatically marks a transaction invalid if a command fails syntax or argument validation during queuing, preventing partial execution.
- **Optimistic Concurrency Control**: Tracks key mutation versioning; if any key monitored by `WATCH` is modified by an external client, `EXEC` aborts and yields a null array (`*-1\r\n`), matching standard Redis CAS semantics.

### 6. RESP2 Wire Protocol & Native Networking
- Implements standard Redis Serialization Protocol v2 over TCP port 6379.
- Distinguishes accurately between Null Bulk Strings (`$-1\r\n`), Empty Bulk Strings (`$0\r\n\r\n`), and Empty Arrays (`*0\r\n`).
- Supports protocol pipelining for high-throughput batching.
- Wire compatibility with official tools (`redis-cli`), Python `redis-py` (with `protocol=2`), Java Jedis, and Lettuce.

### 7. Embedded Web Management Console
- Built-in lightweight HTTP server listening on port 8080 (`MiniRedisWebServer`).
- Modern HTML5/CSS3/JavaScript single-page dashboard featuring:
  - Real-time telemetry cards (uptime, memory, ops/sec, connected clients, keyspace hits/misses).
  - Visual keyspace browser with type badges and TTL countdowns.
  - Interactive terminal REPL with command history and keyboard shortcuts.

---

## Measured Performance & Verification Summary

### 1. Test Suite Results
- **Total Tests Run:** 145
- **Passed:** 145 (100%)
- **Failures:** 0
- **Errors:** 0
- **Skipped:** 0
- **Test Categories:** Unit tests, concurrency fuzzing, networking, transactions, AOF persistence, crash recovery, RESP2 protocol compliance, and integration.

### 2. Live Performance Benchmarks (Windows 11, 8 Cores, Java 17 / 25)

#### In-Process Engine Dispatch (40,000 ops/run)
| Workload | Clients | Throughput | Avg Latency | P95 Latency | P99 Latency |
|---|---|---|---|---|---|
| **GET only** | 1 | 543,391 op/s | 1.53 μs | 1.50 μs | 2.50 μs |
| **GET only** | 10 | 716,222 op/s | 5.85 μs | 1.40 μs | 3.70 μs |
| **GET only** | 25 | 1,423,649 op/s | 0.93 μs | 0.60 μs | 1.20 μs |
| **GET only** | 50 | 1,479,629 op/s | 0.44 μs | 0.40 μs | 1.00 μs |
| **SET only** | 1 | 213,649 op/s | 3.67 μs | 4.10 μs | 20.50 μs |
| **SET only** | 10 | 312,435 op/s | 21.63 μs | 3.40 μs | 18.20 μs |
| **SET only** | 25 | 407,436 op/s | 1.79 μs | 1.60 μs | 2.80 μs |
| **SET only** | 50 | 822,831 op/s | 10.07 μs | 1.20 μs | 3.00 μs |
| **Mixed (50G/30S/10I/10L)** | 50 | 376,639 op/s | 51.51 μs | 2.80 μs | 12.20 μs |

#### Live TCP + RESP2 Persistent Sockets (12,000 reqs/run)
| Workload | Clients | Throughput | Avg Latency | P95 Latency | P99 Latency |
|---|---|---|---|---|---|
| **GET only** | 25 | 68,670 op/s | 172.83 μs | 229.70 μs | 647.90 μs |
| **SET only** | 10 | 66,622 op/s | 147.92 μs | 171.50 μs | 409.30 μs |
| **Mixed R/W** | 10 | 58,456 op/s | 166.31 μs | 172.10 μs | 436.80 μs |
| **MULTI/INCRBY/EXEC** | 25 | 38,965 tx/s | 377.59 μs | 293.50 μs | 1,879.40 μs |

---

## GitHub Release Checklist

Prior to publishing a public release on GitHub, perform the following verification steps:

- [x] Clean repository checkout with no leftover `.aof`, `.class`, or temporary files.
- [x] `.gitignore` verified and ignoring build outputs and IDE files.
- [x] Strict Java 17 compliance configured (`<release>17</release>`).
- [x] All 145 automated tests pass (`mvn clean test`).
- [x] Executable JAR builds cleanly (`mvn clean package` -> `miniredis-java-1.0.0.jar`).
- [x] Python `redis-py 8.1.0` verified end-to-end against packaged JAR.
- [x] GitHub Actions CI workflow configured (`.github/workflows/ci.yml`).
- [x] Documentation complete (`README.md`, `CONTRIBUTING.md`, `LICENSE`, `CHANGELOG.md`).
- [ ] User approval granted for remote git creation or publishing.
