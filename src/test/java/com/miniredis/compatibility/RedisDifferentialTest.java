package com.miniredis.compatibility;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 17: Differential Compatibility Test Suite (Redis 7.2 vs MiniRedis)")
public class RedisDifferentialTest {

    private static final List<DifferentialResult> allResults = new ArrayList<>();
    private static String recordedRedisVersion = "Redis 7.2 Standalone";

    private DifferentialTestHarness harness;

    @BeforeEach
    public void setUp() throws IOException {
        harness = new DifferentialTestHarness();
        recordedRedisVersion = harness.getReferenceRedisVersion();
    }

    @AfterEach
    public void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @AfterAll
    public static void tearDownAll() {
        DifferentialTestHarness.printSummary(allResults, recordedRedisVersion);
    }

    private void runAndAssert(DifferentialTestCase testCase) throws IOException {
        List<DifferentialResult> results = harness.runTestCase(testCase);
        assertFalse(results.isEmpty(), "Test case must produce at least one step result");
        allResults.addAll(results);
        for (DifferentialResult res : results) {
            assertTrue(res.isPass(), res.toString());
        }
    }

    // =========================================================================
    // 1. STRINGS
    // =========================================================================

    @Test
    @DisplayName("STRINGS: SET, GET, STRLEN, APPEND, DEL Lifecycle")
    public void testStringLifecycle() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("STR_01", "STRINGS", "Standard String Lifecycle")
                .withSetup("FLUSHDB")
                .addStep("SET mykey \"Hello World\"", "+OK\r\n")
                .addStep("GET mykey", "$11\r\nHello World\r\n")
                .addStep("STRLEN mykey", ":11\r\n")
                .addStep("APPEND mykey \" Redis\"", ":17\r\n")
                .addStep("GET mykey", "$17\r\nHello World Redis\r\n")
                .addStep("DEL mykey", ":1\r\n")
                .addStep("GET mykey", "$-1\r\n");

        runAndAssert(tc);
    }

    @Test
    @DisplayName("STRINGS: Atomic 64-bit Integer Arithmetic (INCR, INCRBY, DECR, DECRBY)")
    public void testStringArithmetic() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("STR_02", "STRINGS", "Integer Arithmetic Operations")
                .withSetup("FLUSHDB")
                .addStep("SET counter 10", "+OK\r\n")
                .addStep("INCR counter", ":11\r\n")
                .addStep("INCRBY counter 5", ":16\r\n")
                .addStep("DECR counter", ":15\r\n")
                .addStep("DECRBY counter 10", ":5\r\n")
                .addStep("GET counter", "$1\r\n5\r\n")
                .addStep("SET notanum \"abc\"", "+OK\r\n")
                .addStep("INCR notanum", "-ERR value is not an integer or out of range\r\n");

        runAndAssert(tc);
    }

    @Test
    @DisplayName("STRINGS: MSET and MGET Multi-key Atomicity")
    public void testMSetMGet() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("STR_03", "STRINGS", "MSET and MGET Operations")
                .withSetup("FLUSHDB")
                .addStep("MSET k1 v1 k2 v2 k3 v3", "+OK\r\n")
                .addStep("MGET k1 k2 k3 k_missing", "*4\r\n$2\r\nv1\r\n$2\r\nv2\r\n$2\r\nv3\r\n$-1\r\n")
                .addStep("MSET oddarg1 oddval1 extrakey", "-ERR wrong number of arguments for 'mset' command\r\n");

        runAndAssert(tc);
    }

    @Test
    @DisplayName("STRINGS: SET Extended Parity (NX, XX, GET, KEEPTTL, Syntax Validation)")
    public void testSetExtendedParity() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("STR_04", "STRINGS", "SET Extended Parity Options")
                .withSetup("FLUSHDB")
                .addStep("SET diff_k1 v1 NX", "+OK\r\n")
                .addStep("SET diff_k1 v2 NX", "$-1\r\n")
                .addStep("GET diff_k1", "$2\r\nv1\r\n")
                .addStep("SET diff_k1 v3 XX", "+OK\r\n")
                .addStep("GET diff_k1", "$2\r\nv3\r\n")
                .addStep("SET diff_k2 v_miss XX", "$-1\r\n")
                .addStep("SET diff_k1 v4 GET", "$2\r\nv3\r\n")
                .addStep("GET diff_k1", "$2\r\nv4\r\n")
                .addStep("SET diff_k3 v_first GET", "$-1\r\n")
                .addStep("GET diff_k3", "$7\r\nv_first\r\n")
                .addStep("SET diff_k1 v5 NX XX", "-ERR syntax error\r\n")
                .addStep("SET diff_k1 v5 EX 0", "-ERR invalid expire time in 'set' command\r\n")
                .addStep("SET diff_k1 v5 EX abc", "-ERR value is not an integer or out of range\r\n")
                .addStep("SET diff_k1 v5 KEEPTTL", "+OK\r\n");

        runAndAssert(tc);
    }

    // =========================================================================
    // 2. LISTS
    // =========================================================================

    @Test
    @DisplayName("LISTS: LPUSH, RPUSH, LPOP, RPOP, LLEN, LRANGE Ordering & Edge Cases")
    public void testListOperations() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("LIST_01", "LISTS", "List Queues and Deques")
                .withSetup("FLUSHDB")
                // In Redis LPUSH a b results in [b, a]
                .addStep("LPUSH mylist item1 item2", ":2\r\n")
                .addStep("RPUSH mylist item3", ":3\r\n")
                .addStep("LLEN mylist", ":3\r\n")
                .addStep("LRANGE mylist 0 -1", "*3\r\n$5\r\nitem2\r\n$5\r\nitem1\r\n$5\r\nitem3\r\n")
                .addStep("LPOP mylist", "$5\r\nitem2\r\n")
                .addStep("RPOP mylist", "$5\r\nitem3\r\n")
                .addStep("LLEN mylist", ":1\r\n")
                .addStep("LPOP mylist", "$5\r\nitem1\r\n")
                .addStep("LPOP mylist", "$-1\r\n") // Empty list key is auto-deleted
                .addStep("EXISTS mylist", ":0\r\n");

        runAndAssert(tc);
    }

    @Test
    @DisplayName("LISTS: Wrong Type Operations Guard")
    public void testListWrongType() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("LIST_02", "LISTS", "Type Mismatch on Lists")
                .withSetup("FLUSHDB")
                .addStep("SET strkey \"I am a string\"", "+OK\r\n")
                .addStep("LPUSH strkey elem", "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n")
                .addStep("LLEN strkey", "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n");

        runAndAssert(tc);
    }

    @Test
    @DisplayName("LISTS: Complete Redis 7.2 Parity (LPOP/RPOP COUNT, LINDEX, LSET, LINSERT)")
    public void testListExtendedParity() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("LIST_03", "LISTS", "Extended List Operations Parity")
                .withSetup("FLUSHDB")
                .addStep("RPUSH diff_list one two three four", ":4\r\n")
                .addStep("LINDEX diff_list 0", "$3\r\none\r\n")
                .addStep("LINDEX diff_list -1", "$4\r\nfour\r\n")
                .addStep("LINDEX diff_list 10", "$-1\r\n")
                .addStep("LINDEX diff_list -10", "$-1\r\n")
                .addStep("LSET diff_list 1 TWO_MOD", "+OK\r\n")
                .addStep("LINDEX diff_list 1", "$7\r\nTWO_MOD\r\n")
                .addStep("LSET diff_list -1 FOUR_MOD", "+OK\r\n")
                .addStep("LINDEX diff_list -1", "$8\r\nFOUR_MOD\r\n")
                .addStep("LSET diff_list 10 val", "-ERR index out of range\r\n")
                .addStep("LSET no_list 0 val", "-ERR no such key\r\n")
                .addStep("LINSERT diff_list BEFORE TWO_MOD one_and_half", ":5\r\n")
                .addStep("LINSERT diff_list AFTER FOUR_MOD five", ":6\r\n")
                .addStep("LINSERT diff_list BEFORE nonexistent_pivot val", ":-1\r\n")
                .addStep("LINSERT no_list BEFORE pivot val", ":0\r\n")
                .addStep("LINSERT diff_list INVALID_POS pivot val", "-ERR syntax error\r\n")
                .addStep("LPOP diff_list 2", "*2\r\n$3\r\none\r\n$12\r\none_and_half\r\n")
                .addStep("RPOP diff_list 2", "*2\r\n$4\r\nfive\r\n$8\r\nFOUR_MOD\r\n")
                .addStep("LPOP diff_list 0", "*0\r\n")
                .addStep("LPOP missing_list 0", "*-1\r\n")
                .addStep("LPOP diff_list -1", "-ERR value is out of range, must be positive\r\n")
                .addStep("LPOP diff_list 10", "*2\r\n$7\r\nTWO_MOD\r\n$5\r\nthree\r\n")
                .addStep("EXISTS diff_list", ":0\r\n");

        runAndAssert(tc);
    }

    // =========================================================================
    // 3. SETS
    // =========================================================================

    @Test
    @DisplayName("SETS: SADD, SREM, SISMEMBER, SMEMBERS, SCARD")
    public void testSetOperations() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("SET_01", "SETS", "Set Uniqueness and Membership")
                .withSetup("FLUSHDB")
                .addStep("SADD myset apple banana cherry", ":3\r\n")
                .addStep("SADD myset apple", ":0\r\n") // Duplicate
                .addStep("SCARD myset", ":3\r\n")
                .addStep("SISMEMBER myset banana", ":1\r\n")
                .addStep("SISMEMBER myset mango", ":0\r\n")
                .addStep("SMEMBERS myset", "*3\r\n$5\r\napple\r\n$6\r\nbanana\r\n$6\r\ncherry\r\n",
                        DifferentialTestCase.NormalizationType.SET_UNORDERED)
                .addStep("SREM myset cherry", ":1\r\n")
                .addStep("SCARD myset", ":2\r\n");

        runAndAssert(tc);
    }

    // =========================================================================
    // 4. HASHES
    // =========================================================================

    @Test
    @DisplayName("HASHES: HSET, HGET, HEXISTS, HGETALL, HDEL")
    public void testHashOperations() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("HASH_01", "HASHES", "Hash Field Storage")
                .withSetup("FLUSHDB")
                .addStep("HSET user:101 name \"Alice\" role \"Engineer\"", ":2\r\n")
                .addStep("HGET user:101 name", "$5\r\nAlice\r\n")
                .addStep("HGET user:101 missing_field", "$-1\r\n")
                .addStep("HEXISTS user:101 role", ":1\r\n")
                .addStep("HEXISTS user:101 age", ":0\r\n")
                .addStep("HGETALL user:101", "*4\r\n$4\r\nname\r\n$5\r\nAlice\r\n$4\r\nrole\r\n$8\r\nEngineer\r\n")
                .addStep("HDEL user:101 role", ":1\r\n")
                .addStep("HEXISTS user:101 role", ":0\r\n")
                .addStep("HDEL user:101 name", ":1\r\n")
                .addStep("EXISTS user:101", ":0\r\n"); // Auto-deleted when empty

        runAndAssert(tc);
    }

    // =========================================================================
    // 5. EXPIRATION & LIFECYCLE
    // =========================================================================

    @Test
    @DisplayName("EXPIRATION: EXPIRE, TTL, PERSIST, SETEX")
    public void testExpirationCommands() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("EXP_01", "EXPIRATION", "TTL and Expiration Semantics")
                .withSetup("FLUSHDB")
                .addStep("SETEX temp_token 100 \"active\"", "+OK\r\n")
                .addStep("TTL temp_token", ":100\r\n", DifferentialTestCase.NormalizationType.TTL_TOLERANCE)
                .addStep("PERSIST temp_token", ":1\r\n")
                .addStep("TTL temp_token", ":-1\r\n") // Persistent key
                .addStep("EXPIRE temp_token 50", ":1\r\n")
                .addStep("TTL temp_token", ":50\r\n", DifferentialTestCase.NormalizationType.TTL_TOLERANCE)
                .addStep("TTL non_existent", ":-2\r\n"); // Missing key

        runAndAssert(tc);
    }

    @Test
    @DisplayName("EXPIRATION: Millisecond Operations (PEXPIRE, PTTL, PEXPIREAT)")
    public void testMillisecondExpiration() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("EXP_02", "EXPIRATION", "Millisecond TTL Precision")
                .withSetup("FLUSHDB")
                .addStep("SET ms_key \"precise\"", "+OK\r\n")
                .addStep("PEXPIRE ms_key 60000", ":1\r\n")
                .addStep("PTTL ms_key", ":60000\r\n", DifferentialTestCase.NormalizationType.TTL_TOLERANCE)
                .addStep("PTTL non_existing_ms", ":-2\r\n");

        runAndAssert(tc);
    }

    // =========================================================================
    // 6. KEY MANAGEMENT
    // =========================================================================

    @Test
    @DisplayName("KEYS: EXISTS, DEL, KEYS Glob Matching, RENAME, RANDOMKEY")
    public void testKeyManagementCommands() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("KEY_01", "KEYS", "Key Space Inspection and Mutations")
                .withSetup("FLUSHDB")
                .addStep("SET user:1001:name Alice", "+OK\r\n")
                .addStep("SET user:1002:name Bob", "+OK\r\n")
                .addStep("EXISTS user:1001:name user:1002:name missing_user", ":2\r\n")
                .addStep("KEYS user:*:name", "*2\r\n$14\r\nuser:1001:name\r\n$14\r\nuser:1002:name\r\n",
                        DifferentialTestCase.NormalizationType.SET_UNORDERED)
                .addStep("RENAME user:1001:name user:1001:fullname", "+OK\r\n")
                .addStep("EXISTS user:1001:name", ":0\r\n")
                .addStep("GET user:1001:fullname", "$5\r\nAlice\r\n");

        runAndAssert(tc);
    }

    // =========================================================================
    // 7. SYSTEM & CLIENT PROTOCOL
    // =========================================================================

    @Test
    @DisplayName("SYSTEM: PING, ECHO, TYPE, DBSIZE, KEYS")
    public void testSystemCommands() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("SYS_01", "SYSTEM", "Server Utility Commands")
                .withSetup("FLUSHDB")
                .addStep("PING", "+PONG\r\n")
                .addStep("PING \"Hello Differential\"", "$18\r\nHello Differential\r\n") // Exact Redis 7.2 RESP2 bulk string frame
                .addStep("ECHO \"Echo Test\"", "$9\r\nEcho Test\r\n")
                .addStep("SET s1 val", "+OK\r\n")
                .addStep("LPUSH l1 elem", ":1\r\n")
                .addStep("SADD set1 member", ":1\r\n")
                .addStep("HSET h1 field val", ":1\r\n")
                .addStep("TYPE s1", "+string\r\n")
                .addStep("TYPE l1", "+list\r\n")
                .addStep("TYPE set1", "+set\r\n")
                .addStep("TYPE h1", "+hash\r\n")
                .addStep("TYPE missing", "+none\r\n")
                .addStep("DBSIZE", ":4\r\n");

        runAndAssert(tc);
    }

    @Test
    @DisplayName("SYSTEM: SELECT, CLIENT Protocol Management")
    public void testProtocolDiscoveryCommands() throws IOException {
        DifferentialTestCase tc = new DifferentialTestCase("SYS_02", "SYSTEM", "Client Management and Protocol Discovery")
                .withSetup("FLUSHDB")
                .addStep("SELECT 0", "+OK\r\n")
                .addStep("CLIENT SETNAME test_diff_worker", "+OK\r\n")
                .addStep("CLIENT GETNAME", "$16\r\ntest_diff_worker\r\n");

        runAndAssert(tc);
    }
}
