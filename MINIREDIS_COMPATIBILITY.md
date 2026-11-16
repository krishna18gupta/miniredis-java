# MiniRedis Compatibility Specification & Command Inventory

**Target Reference Baseline:** Redis Open Source 7.2 (Standalone Mode)  
**Primary Protocol Target:** RESP2  
**Future Protocol Target:** RESP3  
**Document Version:** 1.1.0 (Phase 17 Stage 8 - Real Client & Reference Verification)  
**Latest Engine Commit:** `abfc286` (Updated in Stage 8)

---

## 1. Compatibility Levels & Terminology

To avoid conflating partial implementations with true compatibility, MiniRedis strictly defines three distinct operational tiers:

1. **Implemented (Engine-Level):** The command syntax is recognized and parsed by `CommandRegistry`, and an execution handler exists in MiniRedis.
2. **Verified Compatible:** The command's response values, error semantics, boundary conditions, and state mutations have been validated against Redis 7.2 reference behavior via automated test suites.
3. **Public-Demo Available:** The command is permitted through the public-facing HTTP REST console (`/api/exec`) subject to 128-bit session isolation, immutable demo key guards, and rate limits.

> [!IMPORTANT]
> A command being **Implemented** does NOT imply full drop-in Redis compatibility. Many Redis commands feature secondary flags (e.g. `NX`, `XX`, `GET`, `KEEPTTL` in `SET`, negative indexing variants, or multiple key/field deletions) that require rigorous differential validation.

---

## 2. Command Compatibility Inventory Matrix

Below is the comprehensive audit of all **60 commands implemented in MiniRedis**, organized by functional category against Redis 7.2 standalone behavior.

### 2.1 String Commands

| Command | Redis 7.2 Expected Behaviour | MiniRedis Implementation | Compatibility Tier | Test Status | Known Differences / Gaps |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`SET`** | `SET key val [NX\|XX] [GET] [EX s\|PX ms\|EXAT ts\|PXAT ts\|KEEPTTL]` | `SetCommand` in `com.miniredis.commands` | Implemented, Public-Demo | Passed (38 unit tests + live differential) | Verified 100% compatible with Redis 7.2.17. Supports all syntax combinations: `NX`, `XX`, `GET`, `KEEPTTL`, `EX`, `PX`, `EXAT`, `PXAT`, mutual exclusivity validations, binary safety, past timestamp deletion, and canonical AOF persistence normalization. |
| **`GET`** | Returns bulk string or nil. Error on non-string. | `GetCommand` | Implemented, Public-Demo | Passed (Phase 1, 2, live) | Fully compatible with Redis 7.2 string retrieval. |
| **`MSET`** | Atomic multi-key set. Replaces existing keys. Syntax error on odd args. | `MSetCommand` | Implemented, Public-Demo | Passed (Phase 2, EdgeCases, live) | Multi-key atomic lock via `StripedLockManager`. Rejects odd arguments with `ERR wrong number of arguments for 'mset' command`. |
| **`MGET`** | Returns array of values. Nil for missing/expired or wrong-type keys. | `MGetCommand` | Implemented, Public-Demo | Passed (Phase 2, live) | Fully compatible. Returns `nil` for non-string or non-existent keys without throwing an error. |
| **`STRLEN`** | Returns byte length of string. 0 if key does not exist. Error on wrong type. | `StrLenCommand` | Implemented, Public-Demo | Passed (Phase 8, live) | Calculates UTF-8 byte length. Redis 7.2 treats raw bytes; in MiniRedis, text is stored as UTF-8 Java strings. |
| **`APPEND`** | Appends value to string. Creates key if not exists. Returns new length. | `AppendCommand` | Implemented, Public-Demo | Passed (Phase 8, live) | Returns new UTF-8 byte length. Compatible for text strings. |
| **`INCR`** | Increments 64-bit integer by 1. Error if value not integer or overflows. | `IncrCommand` | Implemented, Public-Demo | Passed (Phase 2, EdgeCases, live) | Uses `Math.addExact`. Returns `ERR value is not an integer or out of range` on overflow, matching Redis 7.2. |
| **`DECR`** | Decrements 64-bit integer by 1. Error if value not integer or underflows. | `DecrCommand` | Implemented, Public-Demo | Passed (Phase 2, EdgeCases, live) | Uses `Math.subtractExact`. Returns `ERR value is not an integer or out of range` on underflow. |
| **`INCRBY`** | Increments 64-bit integer by given step. | `IncrByCommand` | Implemented, Public-Demo | Passed (Phase 8, live) | Validates step and target. Safe 64-bit arithmetic. |
| **`DECRBY`** | Decrements 64-bit integer by given step. | `DecrByCommand` | Implemented, Public-Demo | Passed (Phase 8, live) | Validates step and target. Safe 64-bit arithmetic. |
| **`SETEX`** | Sets key to value with expiration timeout in seconds. | `SetExCommand` | Implemented, Public-Demo | Passed (Phase 3, live) | Atomically sets string value and TTL. Compatible with Redis 7.2. |

---

### 2.2 List Commands

| Command | Redis 7.2 Expected Behaviour | MiniRedis Implementation | Compatibility Tier | Test Status | Known Differences / Gaps |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`LPUSH`** | Prepends one or multiple elements. Last argument is at the new head. | `LPushCommand` | Implemented, Public-Demo | Passed (Phase 2, EdgeCases, live) | Matches Redis multi-argument insertion order: `LPUSH k a b` results in `[b, a]`. Auto-creates key. |
| **`RPUSH`** | Appends one or multiple elements to list tail. | `RPushCommand` | Implemented, Public-Demo | Passed (Phase 2, live) | Appends in order. Auto-creates list. |
| **`LPOP`** | Removes and returns first element(s). Optional `[count]` returns array. | `LPopCommand` | Implemented, Public-Demo | Passed (ListCommandParityTest, live) | Fully compatible with Redis 7.2.17. Single form returns bulk string; COUNT form returns array (null array on missing key, empty array on count 0). Removes key and TTL if list becomes empty. |
| **`RPOP`** | Removes and returns last element(s). Optional `[count]` returns array. | `RPopCommand` | Implemented, Public-Demo | Passed (ListCommandParityTest, live) | Fully compatible with Redis 7.2.17. Single form returns bulk string; COUNT form returns array (null array on missing key, empty array on count 0). Removes key and TTL if list becomes empty. |
| **`LLEN`** | Returns length of list. 0 if key does not exist. Error on wrong type. | `LLenCommand` | Implemented, Public-Demo | Passed (Phase 2, live) | Fully compatible. |
| **`LRANGE`** | Returns slice of list. Supports 0-based and negative indices. | `LRangeCommand` | Implemented, Public-Demo | Passed (Phase 2, EdgeCases, live) | Fully compatible with 0-based, negative (`-1` = last), and out-of-bound index normalization. |
| **`LINDEX`** | Returns element at index. Negative indices count from tail. | `LIndexCommand` | Implemented, Public-Demo | Passed (ListCommandParityTest, live) | Fully compatible with Redis 7.2.17. 0-based and negative indexing. Returns nil for out-of-range or missing key. |
| **`LSET`** | Sets list element at index. Error on missing key or out of range. | `LSetCommand` | Implemented, Public-Demo | Passed (ListCommandParityTest, live) | Fully compatible with Redis 7.2.17. Supports negative index. Returns `ERR no such key` and `ERR index out of range`. |
| **`LINSERT`**| Inserts element BEFORE or AFTER pivot. Case-insensitive position token. | `LInsertCommand` | Implemented, Public-Demo | Passed (ListCommandParityTest, live) | Fully compatible with Redis 7.2.17. Binary pivot matching via `ByteSequence.equals`. Returns 0 if key missing, -1 if pivot not found. |

---

### 2.3 Set Commands

| Command | Redis 7.2 Expected Behaviour | MiniRedis Implementation | Compatibility Tier | Test Status | Known Differences / Gaps |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`SADD`** | Adds one or more members. Returns count of added members. | `SAddCommand` | Implemented, Public-Demo | Passed (Phase 2, live) | Duplicates ignored. Auto-creates set. |
| **`SREM`** | Removes one or more members. Returns count of removed members. | `SRemCommand` | Implemented, Public-Demo | Passed (Phase 2, live) | Auto-removes key if set becomes empty. |
| **`SISMEMBER`** | Returns 1 if member, 0 if not or key missing. Error on wrong type. | `SIsMemberCommand` | Implemented, Public-Demo | Passed (Phase 2, live) | Fully compatible. |
| **`SMEMBERS`** | Returns all members of set. Order is arbitrary. | `SMembersCommand` | Implemented, Public-Demo | Passed (Phase 2, live) | Uses `LinkedHashSet` internally for deterministic testing, but set is conceptually unordered. |
| **`SCARD`** | Returns cardinality of set. 0 if key does not exist. | `SCardCommand` | Implemented, Public-Demo | Passed (Phase 2, live) | Fully compatible. |

---

### 2.4 Hash Commands

| Command | Redis 7.2 Expected Behaviour | MiniRedis Implementation | Compatibility Tier | Test Status | Known Differences / Gaps |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`HSET`** | `HSET key field val [field val ...]` | `HSetCommand` | Implemented, Public-Demo | Passed (HashCommandParityTest, live) | Fully verified Redis 7.2.17 compatible. Multi-field atomicity, duplicate field sequential resolution, returns count of newly created fields, overwrites return 0 but touch watched keys and log to AOF, preserves TTL, binary safe. |
| **`HGET`** | Returns value of field in hash. Nil if field or key missing. | `HGetCommand` | Implemented, Public-Demo | Passed (Phase 2, live) | Fully compatible. |
| **`HGETALL`** | Returns flat array of field, value pairs. Empty array if missing. | `HGetAllCommand` | Implemented, Public-Demo | Passed (Phase 2, live) | Fully compatible. Returns alternating field/value pairs in RESP array. |
| **`HDEL`** | `HDEL key field [field ...]` | `HDelCommand` | Implemented, Public-Demo | Passed (HashCommandParityTest, live) | Fully verified Redis 7.2.17 compatible. Multi-field deletion, duplicate field handling, auto-deletes key and TTL when last field is removed, returns count of actually deleted fields, 0-deletion operations do not create false AOF mutations or trigger WATCH aborts. |
| **`HEXISTS`** | Returns 1 if field exists in hash, 0 otherwise. | `HExistsCommand` | Implemented, Public-Demo | Passed (Phase 2, live) | Fully compatible. |

---

### 2.5 Expiration & Lifecycle Commands

| Command | Redis 7.2 Expected Behaviour | MiniRedis Implementation | Compatibility Tier | Test Status | Known Differences / Gaps |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`EXPIRE`** | Sets key TTL in seconds. Options `[NX\|XX\|GT\|LT]` (Redis 7.0+). | `ExpireCommand` | Implemented, Public-Demo | Passed (Phase 3, live) | Supports `EXPIRE key seconds`. Returns 1 on success, 0 if missing. Secondary flags `NX/XX/GT/LT` not implemented. |
| **`PEXPIRE`** | Sets key TTL in milliseconds. Options `[NX\|XX\|GT\|LT]`. | `PExpireCommand` | Implemented, Public-Demo | Passed (Phase 3, live) | Supports `PEXPIRE key ms`. Secondary flags not implemented. |
| **`PEXPIREAT`**| Sets expiration timestamp in unix epoch milliseconds. | `PexpireAtCommand` | Implemented (Restricted) | Passed (Phase 3) | Implemented in engine; restricted in public demo to prevent arbitrary timestamp tampering. |
| **`TTL`** | Returns TTL in seconds. -2 if missing, -1 if no expiry. | `TtlCommand` | Implemented, Public-Demo | Passed (Phase 3, live) | Fully compatible with Redis 7.2 return semantics (-2 / -1). |
| **`PTTL`** | Returns TTL in milliseconds. -2 if missing, -1 if no expiry. | `PTtlCommand` | Implemented, Public-Demo | Passed (Phase 3, live) | Fully compatible with Redis 7.2 return semantics (-2 / -1). |
| **`PERSIST`** | Removes expiration, making key persistent. Returns 1 if cleared, 0 if not set. | `PersistCommand` | Implemented, Public-Demo | Passed (Phase 3, live) | Fully compatible. |

---

### 2.6 Key Management Commands

| Command | Redis 7.2 Expected Behaviour | MiniRedis Implementation | Compatibility Tier | Test Status | Known Differences / Gaps |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`EXISTS`** | Returns count of existing keys among arguments. | `ExistsCommand` | Implemented, Public-Demo | Passed (Phase 1, live) | Supports multiple keys: `EXISTS k1 k2 k3`. Returns total existing count. |
| **`DEL`** | Deletes one or more keys. Returns number of keys removed. | `DelCommand` | Implemented, Public-Demo | Passed (Phase 1, live) | Supports multiple keys. In public demo, protected from deleting `demo:*` keys. |
| **`KEYS`** | Returns all keys matching glob pattern (`*`, `?`, `[...]`). | `KeysCommand` | Implemented, Public-Demo | Passed (Phase 1, live) | Supports `*` and `?`. In public demo, filtered to current visitor session + demo dataset. |
| **`DBSIZE`** | Returns number of keys in current database. | `DbSizeCommand` | Implemented, Public-Demo | Passed (Phase 1, live) | In public demo, isolated to show only caller's keys + demo dataset count. |
| **`TYPE`** | Returns string, list, set, hash, or none. | `TypeCommand` | Implemented, Public-Demo | Passed (Phase 4, live) | Fully compatible with Redis 7.2 type names. |
| **`RENAME`** | Renames key to newkey. Overwrites newkey if exists. | `RenameCommand` | Implemented (Restricted) | Passed (Phase 4) | Implemented in engine; restricted in public demo to prevent cross-namespace leakage. |
| **`RANDOMKEY`**| Returns a random key from database, or nil if empty. | `RandomKeyCommand` | Implemented (Restricted) | Passed (Phase 4) | Implemented in engine; restricted in public demo. |

---

### 2.7 Transaction Commands

| Command | Redis 7.2 Expected Behaviour | MiniRedis Implementation | Compatibility Tier | Test Status | Known Differences / Gaps |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`MULTI`** | Marks start of transaction block. Subsequent commands queued. | `CommandRegistry.handleMulti` | Implemented (TCP only) | Passed (Phase 8, 7 tests) | Fully stateful per client connection. Restricted in stateless HTTP demo. |
| **`EXEC`** | Executes queued commands atomically. Returns array of responses or nil if WATCH failed. | `CommandRegistry.handleExec` | Implemented (TCP only) | Passed (Phase 8, 7 tests) | Supports atomic execution and optimistic locking check. Rejects with `-EXECABORT` on queue-time syntax errors. |
| **`DISCARD`** | Flushes queued commands and rolls back transaction state. | `CommandRegistry.handleDiscard` | Implemented (TCP only) | Passed (Phase 8, 7 tests) | Fully compatible with Redis 7.2. |
| **`WATCH`** | Marks keys for conditional execution. Aborts EXEC if modified. | `CommandRegistry.handleWatch` | Implemented (TCP only) | Passed (Phase 8, 7 tests) | Tracks key modification versions in `StorageEngine.captureWatch`. Compatible. |
| **`UNWATCH`** | Clears all watched keys for the connection. | `CommandRegistry.handleUnwatch` | Implemented (TCP only) | Passed (Phase 8, 7 tests) | Fully compatible with Redis 7.2. |

---

### 2.8 System, Protocol & Server Lifecycle Commands

| Command | Redis 7.2 Expected Behaviour | MiniRedis Implementation | Compatibility Tier | Test Status | Known Differences / Gaps |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`PING`** | Returns PONG or echoed message. | `PingCommand` | Implemented, Public-Demo | Passed (Phase 4, live) | Fully compatible: `PING` -> `+PONG`, `PING msg` -> `+"msg"`. |
| **`ECHO`** | Echoes the given message as bulk string. | `EchoCommand` | Implemented, Public-Demo | Passed (Phase 4, live) | Fully compatible. |
| **`HELLO`** | Protocol handshake. Returns server/proto info or switches protocol. | `HelloCommand` | Implemented, Public-Demo | Passed (Phase 8, live) | Returns RESP2 server map (`server: miniredis`, `version: 1.0.0`, `proto: 2`, `mode: standalone`). Does not switch to RESP3. |
| **`INFO`** | Returns server statistics in key:value text format. | `InfoCommand` | Implemented, Public-Demo | Passed (Phase 8, live) | Returns Redis-compatible INFO sections (`Server`, `Clients`, `Memory`, `Persistence`, `Stats`, `Keyspace`). Sanitized in demo. |
| **`SELECT`** | Selects logical database (0-15). | `SelectCommand` | Implemented (TCP only) | Passed (Phase 8) | Accepts index 0 with OK. Multi-database indexing (1..15) is not yet supported (only DB 0 is active). |
| **`CLIENT`** | Client connection management (`SETNAME`, `GETNAME`, `LIST`, `ID`). | `ClientCommand` | Implemented (TCP only) | Passed (Phase 8) | Supports `CLIENT SETNAME`, `GETNAME`, `LIST`, `ID`. Advanced subcommands (`KILL`, `PAUSE`, `TRACKING`) unsupported. |
| **`AUTH`** | Authenticates client connection against password. | `AuthCommand` | Implemented (TCP only) | Passed (Phase 8) | Supports optional password authentication. |
| **`COMMAND`**| Returns Redis command details / counts for client discovery. | `CommandCommand` | Implemented (TCP only) | Passed (Phase 7) | Supports `COMMAND`, `COMMAND COUNT`, `COMMAND DOCS`. Useful for driver discovery (Lettuce, redis-py). |
| **`FLUSHDB`** | Flushes keys in current database. | `FlushDbCommand` | Implemented (Restricted) | Passed (Phase 1) | Flushes store. Strictly disabled in public demo to prevent vandalism. |
| **`SAVE`** | Synchronously saves database to disk. | `SaveCommand` | Implemented (Restricted) | Passed (Phase 7) | Flushes AOF buffer. Restricted in public demo. |
| **`BGREWRITEAOF`** | Triggers background AOF compaction. | `BgRewriteAofCommand` | Implemented (Restricted) | Passed (Phase 7, AofRewriteTest) | Full asynchronous background rewrite with diff buffer. Restricted in demo. |
| **`SHUTDOWN`** | Gracefully flushes and stops server. | `ShutdownCommand` | Implemented (Restricted) | Passed (Phase 4) | Stops server. Strictly disabled in public demo. |

---

## 3. Explicit Profile Exclusions & Out-of-Scope Redis 7.2 Capabilities

To establish clear standalone database boundaries, the following major Redis capabilities are categorized as **Architectural Profile Exclusions** (features deliberately scoped out of the single-node in-memory key-value core) or **Future Capabilities**, rather than third-party optional modules:

1. **Profile Exclusions (Separate Subsystems / Architectural Paradigms):**
   - **Pub/Sub Messaging:** `SUBSCRIBE`, `UNSUBSCRIBE`, `PUBLISH`, `PSUBSCRIBE`, `PUNSUBSCRIBE`, `PUBSUB` (Broker messaging layer).
   - **Streams:** `XADD`, `XREAD`, `XRANGE`, `XREVRANGE`, `XLEN`, `XDEL`, `XTRIM`, `XGROUP`, `XREADGROUP`, `XACK` (Log append abstraction).
   - **Scripting & Functions:** `EVAL`, `EVALSHA`, `SCRIPT LOAD`, `SCRIPT FLUSH`, `FUNCTION *` (Embedded Lua runtime).
   - **Cluster Topology & Distributed Replication:** `CLUSTER *`, `REPLICAOF`, `PSYNC`, `ROLE`, `SENTINEL *` (Multi-node consensus & distribution).
   - **External RedisStack Modules:** JSON (`JSON.*`), Search (`FT.*`), Bloom filters (`BF.*`), Graph, TimeSeries.

2. **Core Standalone Feature Gaps (In-Scope for Future MiniRedis Phases):**
   - **Sorted Sets (ZSets):** `ZADD`, `ZREM`, `ZSCORE`, `ZRANGE`, `ZCARD`, `ZCOUNT`, `ZINCRBY`, `ZPOPMIN`, `ZPOPMAX`, `ZRANK`, `ZREVRANK`, `ZINTERSTORE`, `ZUNIONSTORE`.
   - **Bitmaps & Bitfields:** `SETBIT`, `GETBIT`, `BITCOUNT`, `BITOP`, `BITPOS`, `BITFIELD`.
   - **HyperLogLog:** `PFADD`, `PFCOUNT`, `PFMERGE`.
   - **Geospatial:** `GEOADD`, `GEODIST`, `GEOHASH`, `GEOPOS`, `GEORADIUS`, `GEOSEARCH`.
   - **Advanced Core Operations:** `BLPOP`, `BRPOP`, `LMOVE`, `BLMOVE`, `LINSERT`, `LSET`, `LREM`, `SDIFF`, `SINTER`, `SUNION`, `SMOVE`, `HINCRBY`, `HINCRBYFLOAT`, `HMGET`, `HMSET`, `HKEYS`, `HVALS`, `HSTRLEN`.

---

## 4. Key Behavioral Gaps & Architectural Insights

1. **Binary Safety:**
   - MiniRedis now natively preserves arbitrary binary payloads (`\x00`, image data, compressed blobs, CRLF, non-UTF-8 bytes) throughout the entire pipeline using immutable `ByteSequence` in `RespParser`, `CommandRegistry`, `RedisValue`, `StorageEngine`, and `RespSerializer`.
2. **String Option Matrix (`SET`):**
   - MiniRedis fully implements Redis 7.2.17 standard `SET key val [NX | XX] [GET] [EX seconds | PX milliseconds | EXAT timestamp | PXAT timestamp | KEEPTTL]`.
   - All options, mutual exclusions, expiration calculations, binary preservation, WRONGTYPE validations, and AOF PXAT normalization are 100% complete and verified.
3. **Logical Database Partitioning (`SELECT`):**
   - Redis 7.2 supports 16 databases (`0` to `15`) by default.
   - MiniRedis accepts `SELECT 0` with `+OK`, but returns an error for indices `> 0`.
4. **Hash Multiple Deletions & Increments:**
   - Redis 7.2 supports `HDEL key field1 field2...` and `HINCRBY key field increment`.
   - MiniRedis supports single and multi-field `HDEL`, but does not yet implement `HINCRBY`.
5. **Collection Cardinality & Limit Policies:**
   - In native standalone TCP mode, MiniRedis memory is bounded only by the JVM heap.
   - In public demo mode, defensive ceilings (max 64 bytes key, 2048 bytes value, 50 collection items, 30 keys/session) intentionally apply.
