# MiniRedis Changelog

All notable changes to the MiniRedis project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-11-13

### Summary
Initial production-grade release of MiniRedis: a high-performance, Redis-compatible in-memory database server engineered completely from scratch in pure Java 17 with zero external runtime dependencies.

---

### Added

#### Phase 1: In-Memory Core Key-Value Engine
- In-memory key-value storage engine using concurrent hash structures.
- Core commands: `SET`, `GET`, `DEL`, `EXISTS`, `KEYS`, `FLUSHDB`, `DBSIZE`.
- Case-insensitive command parsing.
- Interactive Command Line Interface (CLI REPL).

#### Phase 2: Rich Data Structures & Type System
- Strongly-typed storage subsystem (`STRING`, `LIST`, `SET`, `HASH`).
- **Strings**: `MSET`, `MGET`, `INCR`, `DECR`, `APPEND`, `STRLEN`.
- **Lists**: `LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LLEN`, `LRANGE` (supporting negative indices).
- **Sets**: `SADD`, `SREM`, `SISMEMBER`, `SMEMBERS`, `SCARD`.
- **Hashes**: `HSET`, `HGET`, `HGETALL`, `HDEL`, `HEXISTS`.
- Strict Redis `WRONGTYPE Operation against a key holding the wrong kind of value` error enforcement.

#### Phase 3: Expiration & TTL Subsystem
- Precise millisecond-granularity key expiration.
- TTL commands: `EXPIRE`, `PEXPIRE`, `PEXPIREAT`, `TTL`, `PTTL`, `PERSIST`, `SETEX`.
- Dual eviction model:
  - **Passive (Lazy)**: Expired keys detected and removed on-access.
  - **Active (Background)**: Randomized 100ms background sweep with probabilistic stopping criterion.

#### Phase 4: Multithreaded TCP Networking & Embedded Web Console
- Multi-client TCP server listening on port 6379 (`RedisServer`, `RedisClientHandler`).
- Asynchronous client thread pool supporting concurrent connections.
- Embedded HTTP management server on port 8080 (`MiniRedisWebServer`).
- Responsive Web Console GUI with live server telemetry, key browser, and interactive terminal.

#### Phase 5: High-Performance 256-Stripe Concurrency Engine
- 256-stripe read-write lock manager (`StripedLockManager`) eliminating global synchronization bottlenecks.
- Canonical ascending lock index sorting (`writeLocks`, `readLocks`) guaranteeing zero multi-key deadlocks.
- Deep-copied snapshot isolation for multi-key reads (`MGET`) eliminating torn reads under concurrent writes.

#### Phase 6: Append-Only File (AOF) Persistence Engine
- Write-Ahead Logging (`AofManager`) for durable state persistence across server restarts.
- Configurable fsync policies: `ALWAYS` (immediate sync), `EVERYSEC` (background daemon thread every 1000ms), and `NO` (OS page cache flush).
- Absolute expiration normalization converting relative TTLs to unix epochs (`PEXPIREAT`).
- Background compaction (`BGREWRITEAOF`) utilizing dual-buffer diff tracking during compaction.
- Robust crash recovery reconstructing Strings, Lists, Sets, Hashes, and TTL states.

#### Phase 7: RESP2 Wire Protocol Compliance
- Full Redis Serialization Protocol v2 implementation (`RespParser`, `RespSerializer`).
- Support for Simple Strings (`+`), Errors (`-`), Integers (`:`), Bulk Strings (`$`), and Arrays (`*`).
- Strict differentiation between Null Bulk Strings (`$-1\r\n`) and Empty Bulk Strings (`$0\r\n\r\n`).
- Command pipelining support for batch execution.
- Protocol compatibility commands: `HELLO 2`, `AUTH`, `CLIENT SETINFO`, `SELECT`.

#### Phase 8: Extended Command Suite, CAS Transactions & Telemetry
- Additional commands: `ECHO`, `TYPE`, `RENAME`, `RANDOMKEY`, `INCRBY`, `DECRBY`, `SAVE`, `SHUTDOWN [NOSAVE|SAVE]`.
- Optimistic CAS Transactions: `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `UNWATCH`.
- Transaction compilation error tracking and `EXECABORT` error propagation.
- Multi-key transactional locking in canonical order ensuring atomicity and isolation across concurrent TCP clients.
- Thread-safe runtime telemetry (`ServerMetrics`) and categorized `INFO` command output (`server`, `clients`, `memory`, `persistence`, `stats`, `keyspace`).
- Connection management telemetry via `CLIENT LIST`.

#### Phase 9: Integration, Fuzz Testing, Crash Recovery & Python redis-py Verification
- Wire-level crash recovery tests with automatic truncation of corrupt trailing transactions.
- Protocol fuzz testing handling malformed framing, oversized bulk strings, and abrupt connection drops.
- End-to-end integration testing using standard Python `redis-py 8.1.0` (`protocol=2`).
- Multi-client concurrency benchmarks evaluating throughput and p50/p95/p99 latency under 1, 10, 25, and 50 clients.

#### Phase 10: Packaging, CI/CD & Production Polish
- Strict Java 17 compilation target (`--release 17`).
- Zero external runtime dependencies preserved.
- Complete 145-test suite passing with 0 failures and 0 errors.
- Comprehensive GitHub Actions CI/CD workflow (`.github/workflows/ci.yml`).
- Repository cleanup, `.gitignore`, MIT `LICENSE`, `CONTRIBUTING.md`, and complete architecture documentation in `README.md`.
