# Contributing to MiniRedis

Thank you for your interest in contributing to MiniRedis! MiniRedis is a high-performance, Redis-compatible in-memory database server implemented completely from scratch in Java 17+.

---

## 1. Core Architectural Principles

Before contributing, please keep the following non-negotiable architectural tenets in mind:

1. **Zero External Runtime Dependencies**:
   - The production runtime (`src/main`) must depend solely on the standard Java 17+ Class Library (`java.base`, `jdk.httpserver`).
   - Third-party libraries (Netty, Jackson, Spring, Jedis, Guava, Commons) are strictly prohibited in runtime scope.
   - Testing libraries (JUnit 5) are allowed only in `test` scope.
2. **Deterministic Concurrency & Deadlock Avoidance**:
   - Concurrent access is governed by the 256-stripe `StripedLockManager`.
   - Multi-key operations (e.g. `MSET`, `MGET`, `RENAME`, transactions) must acquire locks strictly in ascending canonical lock index order to prevent deadlocks.
3. **Linearizability & Snapshot Isolation**:
   - Multi-key reads (`MGET`) must return deep-copied snapshots taken under lock to prevent torn reads.
4. **RESP2 Protocol Adherence**:
   - All network responses must conform to the RESP2 wire specification, distinguishing clearly between null bulk strings (`$-1\r\n`) and empty bulk strings (`$0\r\n\r\n`).

---

## 2. Prerequisites & Environment Setup

- **Java Development Kit (JDK)**: OpenJDK 17 or higher (tested with Eclipse Temurin / Oracle OpenJDK).
- **Apache Maven**: Version 3.8 or higher.
- **Python (Optional for integration testing)**: Python 3.8+ with `redis-py` installed (`pip install redis`).

### Verify Environment

```bash
java -version
mvn -version
```

---

## 3. How to Build & Run Tests

### Standard Compilation and Test Suite

MiniRedis includes an exhaustive suite of 145 unit, concurrency, networking, transaction, and crash recovery tests:

```bash
mvn clean test
```

### Build Executable Fat-Jar

```bash
mvn clean package
```

The resulting executable artifact will be located at:
`target/miniredis-java-1.0.0.jar`

### Run Performance Benchmarks

To execute the multi-client microbenchmarks:

```bash
mvn test -Dtest=Phase9PerformanceBenchmark
```

---

## 4. How to Add a New Redis Command

To add a new command to MiniRedis:

1. **Implement the `Command` Interface**:
   Create a new class under `com.miniredis.commands.<category>`:
   ```java
   package com.miniredis.commands.strings;

   import com.miniredis.commands.Command;
   import com.miniredis.commands.RedisResponse;
   import com.miniredis.storage.StorageEngine;
   import java.util.List;

   public class CustomCommand implements Command {
       @Override
       public RedisResponse execute(List<String> args, StorageEngine storage) {
           if (args.size() != 2) {
               return RedisResponse.error("ERR wrong number of arguments for 'custom' command");
           }
           // Implementation using storage engine
           return RedisResponse.simpleString("OK");
       }
   }
   ```

2. **Register in `CommandRegistry`**:
   Add the command to `com.miniredis.commands.CommandRegistry`:
   ```java
   register("CUSTOM", new CustomCommand());
   ```

3. **Multi-Key Locking**:
   If the command accesses or mutates multiple keys, coordinate with `storage.getLockManager()` and acquire locks in ascending order using `lockManager.writeLocks(keys)` or `lockManager.readLocks(keys)`.

4. **AOF Persistence (if mutating)**:
   Ensure mutating operations are logged via `storage.getAofManager().log(...)` and handled during AOF rewrite if applicable.

5. **Unit & Protocol Tests**:
   Add comprehensive tests in `src/test/java/com/miniredis/commands/` verifying:
   - Happy paths.
   - Missing / excess arguments.
   - Wrong data type handling (`WRONGTYPE Operation against a key holding the wrong kind of value`).
   - Inline and RESP protocol serialization.

---

## 5. Coding & Pull Request Standards

- Follow standard Java naming conventions and clean code practices.
- Ensure all public APIs are documented with concise Javadoc.
- No warnings or lint errors under Java 17 `--release 17`.
- Every pull request must:
  1. Pass the complete test suite (`mvn clean test`).
  2. Include corresponding unit tests for any new features or bug fixes.
  3. Include a concise summary in `CHANGELOG.md`.
