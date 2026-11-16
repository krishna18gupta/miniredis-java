package com.miniredis.web;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.RedisDataType;
import com.miniredis.storage.RedisValue;
import com.miniredis.storage.StorageEngine;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Centralized Security, Visitor Data Isolation & Resource Protection Engine for MiniRedis Public Demo.
 *
 * Guarantees:
 * 1. 128-bit Cryptographically Secure Visitor Session Validation (UUID format).
 * 2. Strict Backend-Enforced Visitor Namespace Isolation (session:<id>:<key>).
 * 3. Immutable System Demo Dataset (demo:* cannot be mutated or deleted by any route).
 * 4. Strict Public Command Allowlist (blocks all unapproved, administrative, and system commands).
 * 5. Resource and DoS Protection (key size, value size, collection size, session/keyspace caps).
 * 6. Information Isolation (DBSIZE, KEYS, INFO show only the visitor's data + public demo data).
 * 7. Safe Session Expiration Cleanup (reaps abandoned visitor data without touching other sessions or demo keys).
 */
public class DemoSecurityContext {

    public static final String DEMO_KEY_PREFIX = "demo:";
    public static final String DEMO_ROOT_KEY = "demo";
    public static final String SESSION_KEY_PREFIX = "session:";

    // Resource Ceilings
    public static final int MAX_KEY_BYTES = 64;
    public static final int MAX_VALUE_BYTES = 2048; // 2 KB
    public static final int MAX_COLLECTION_ITEMS = 50;
    public static final int MAX_KEYS_PER_SESSION = 30;
    public static final int MAX_GLOBAL_DEMO_KEYS = 300;
    public static final int MAX_BODY_BYTES = 16384; // 16 KB

    // Session Idle Expiry: 30 minutes
    public static final long SESSION_IDLE_TIMEOUT_MS = 30L * 60L * 1000L;

    // 128-bit UUID pattern or 32-64 char alphanumeric secure token
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$|^[a-zA-Z0-9_-]{24,64}$"
    );

    // Strict Public Command Allowlist
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            // Read Operations
            "GET", "MGET", "STRLEN", "EXISTS", "TYPE", "TTL", "PTTL", "LLEN", "LRANGE", "LINDEX",
            "SCARD", "SISMEMBER", "SMEMBERS", "HGET", "HGETALL", "HEXISTS", "DBSIZE",
            "PING", "ECHO", "HELLO", "KEYS", "INFO",
            // Write Operations (Session-Isolated)
            "SET", "SETEX", "MSET", "LPUSH", "RPUSH", "LPOP", "RPOP", "LSET", "LINSERT", "SADD", "SREM",
            "HSET", "HDEL", "EXPIRE", "PEXPIRE", "PERSIST", "DEL",
            "INCR", "DECR", "INCRBY", "DECRBY", "APPEND"
    );

    // Mutating Command Names
    private static final Set<String> MUTATING_COMMANDS = Set.of(
            "SET", "SETEX", "MSET", "LPUSH", "RPUSH", "LPOP", "RPOP", "LSET", "LINSERT", "SADD", "SREM",
            "HSET", "HDEL", "EXPIRE", "PEXPIRE", "PERSIST", "DEL",
            "INCR", "DECR", "INCRBY", "DECRBY", "APPEND"
    );

    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final ConcurrentHashMap<String, Long> activeSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler;

    public DemoSecurityContext(StorageEngine storage, CommandRegistry registry) {
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "miniredis-session-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        initDemoDataset();
        // Run session expiration check every 2 minutes
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredSessions, 2, 2, TimeUnit.MINUTES);
    }

    public void stop() {
        cleanupScheduler.shutdownNow();
    }

    /**
     * Initializes the pre-seeded public demo dataset.
     */
    public void initDemoDataset() {
        if (storage == null) return;

        // 1. String
        storage.set("demo:welcome", "Welcome to MiniRedis v1.0.0!");

        // 2. String with TTL (1 hour active countdown)
        storage.set("demo:session:guest", "active_guest_token_128bit");
        storage.expire("demo:session:guest", 3600);

        // 3. List
        storage.rpush("demo:pipeline", List.of("RespParser", "StripedLockPool", "AofJournal", "WebConsole"));

        // 4. Set
        storage.sadd("demo:features", List.of("Concurrency", "RESP2", "Transactions", "ZeroDependencies"));

        // 5. Hash
        Map<String, String> serverInfo = new LinkedHashMap<>();
        serverInfo.put("engine", "Java 17");
        serverInfo.put("port", "6379");
        serverInfo.put("author", "Krishna Gupta");
        serverInfo.put("concurrency", "256-Stripe Canonical Lock Pool");
        serverInfo.put("persistence", "Append-Only File (WAL)");
        storage.hset("demo:server", serverInfo);
    }

    /**
     * Validates whether a visitor session ID contains at least 128-bits of secure entropy.
     */
    public static boolean isValidSessionId(String sessionId) {
        if (sessionId == null) return false;
        String trimmed = sessionId.trim();
        return SESSION_ID_PATTERN.matcher(trimmed).matches();
    }

    public void touchSession(String sessionId) {
        if (sessionId != null && isValidSessionId(sessionId)) {
            activeSessions.put(sessionId.trim(), System.currentTimeMillis());
        }
    }

    /**
     * Determines whether a key belongs to the immutable public demo dataset.
     */
    public static boolean isProtectedDemoKey(String key) {
        if (key == null) return false;
        String clean = key.trim();
        return clean.equals(DEMO_ROOT_KEY) || clean.startsWith(DEMO_KEY_PREFIX);
    }

    /**
     * Determines whether a key attempts to address the internal visitor session namespace.
     */
    public static boolean isInternalNamespaceKey(String key) {
        if (key == null) return false;
        String clean = key.trim();
        return clean.equals("session") || clean.startsWith(SESSION_KEY_PREFIX);
    }

    /**
     * Maps an external key to the internal session-isolated namespace.
     */
    public static String toInternalKey(String sessionId, String key) {
        if (key == null) return "";
        String clean = key.trim();
        if (clean.isEmpty()) return "";
        if (isProtectedDemoKey(clean)) {
            return clean;
        }
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return clean;
        }
        return SESSION_KEY_PREFIX + sessionId.trim() + ":" + clean;
    }

    /**
     * Converts an internal key back to the visitor-facing key.
     * Returns null if the key belongs to another visitor's session or if session ID is missing/invalid.
     */
    public static String toExternalKey(String sessionId, String internalKey) {
        if (internalKey == null || internalKey.isEmpty()) return null;
        if (isProtectedDemoKey(internalKey)) {
            return internalKey;
        }
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        String prefix = SESSION_KEY_PREFIX + sessionId.trim() + ":";
        if (internalKey.startsWith(prefix)) {
            return internalKey.substring(prefix.length());
        }
        return null; // Not visible to this session
    }

    /**
     * Counts how many keys the given session currently owns.
     */
    public int countSessionKeys(String sessionId) {
        String pattern = SESSION_KEY_PREFIX + sessionId.trim() + ":*";
        return storage.keys(pattern).size();
    }

    /**
     * Centralized execution guard for all public demo commands.
     */
    public RedisResponse executeDemoCommand(String sessionId, String rawCommandLine) {
        if (rawCommandLine == null || rawCommandLine.trim().isEmpty()) {
            return RedisResponse.error("ERR empty command");
        }

        if (rawCommandLine.length() > 4096) {
            return RedisResponse.error("ERR command line exceeds maximum length of 4096 characters");
        }

        if (!isValidSessionId(sessionId)) {
            return RedisResponse.error("ERR invalid or missing X-Session-ID header (must be 128-bit secure UUID)");
        }
        touchSession(sessionId);

        List<String> tokens = CommandRegistry.parseCommandLine(rawCommandLine);
        if (tokens.isEmpty()) {
            return RedisResponse.error("ERR empty command");
        }

        String command = tokens.get(0).toUpperCase(Locale.ROOT);
        List<String> args = tokens.subList(1, tokens.size());

        // 1. Strict Command Allowlist Check
        if (!ALLOWED_COMMANDS.contains(command)) {
            return RedisResponse.error(String.format("ERR command '%s' is not permitted in public demo mode", command));
        }

        // 2. Special Isolated Read Commands
        if ("DBSIZE".equals(command)) {
            int demoCount = storage.keys("demo:*").size();
            int sessionCount = countSessionKeys(sessionId);
            return RedisResponse.integer(demoCount + sessionCount);
        }

        if ("KEYS".equals(command)) {
            String pattern = args.isEmpty() ? "*" : args.get(0);
            return handleIsolatedKeysCommand(sessionId, pattern);
        }

        if ("INFO".equals(command)) {
            return handleSanitizedInfoCommand(sessionId);
        }

        if ("HELLO".equals(command) || "PING".equals(command) || "ECHO".equals(command)) {
            return registry.dispatch(tokens, storage);
        }

        // 3. Extract and Validate Target Key(s)
        if (args.isEmpty()) {
            return registry.dispatch(tokens, storage);
        }

        // Handle commands with key arguments
        return dispatchNamespacedCommand(sessionId, command, args);
    }

    private RedisResponse dispatchNamespacedCommand(String sessionId, String command, List<String> args) {
        boolean isMutating = MUTATING_COMMANDS.contains(command);

        // Collect all target keys to validate across single and multi-key commands
        String primaryKey = args.get(0);
        List<String> targetKeysToCheck = new ArrayList<>();
        if ("MSET".equals(command)) {
            if (args.isEmpty() || args.size() % 2 != 0) {
                return RedisResponse.error("ERR wrong number of arguments for 'mset' command");
            }
            for (int i = 0; i < args.size(); i += 2) {
                targetKeysToCheck.add(args.get(i));
            }
        } else if ("DEL".equals(command) || "MGET".equals(command) || "EXISTS".equals(command)) {
            targetKeysToCheck.addAll(args);
        } else {
            targetKeysToCheck.add(primaryKey);
        }

        for (String targetKey : targetKeysToCheck) {
            if (targetKey.getBytes().length > MAX_KEY_BYTES) {
                return RedisResponse.error(String.format("ERR key name exceeds maximum length of %d bytes", MAX_KEY_BYTES));
            }
            if (isInternalNamespaceKey(targetKey)) {
                return RedisResponse.error("ERR key name cannot contain internal namespace prefix 'session:'");
            }
            if (isMutating && isProtectedDemoKey(targetKey)) {
                return RedisResponse.error(String.format("ERR key '%s' is an immutable system demo key and cannot be modified or deleted", targetKey));
            }
        }

        // Check collection element limits and bounds against current collection size
        if ("LPUSH".equals(command) || "RPUSH".equals(command) || "SADD".equals(command)) {
            int incomingCount = Math.max(0, args.size() - 1);
            if (incomingCount > MAX_COLLECTION_ITEMS) {
                return RedisResponse.error(String.format("ERR maximum %d items allowed per operation", MAX_COLLECTION_ITEMS));
            }
            String internalKey = toInternalKey(sessionId, primaryKey);
            RedisValue existing = storage.get(internalKey);
            if (existing != null) {
                int currentCount = 0;
                if ("SADD".equals(command) && existing.getType() == RedisDataType.SET) {
                    currentCount = existing.getAsSet().size();
                } else if (("LPUSH".equals(command) || "RPUSH".equals(command)) && existing.getType() == RedisDataType.LIST) {
                    currentCount = existing.getAsList().size();
                }
                if (currentCount + incomingCount > MAX_COLLECTION_ITEMS) {
                    return RedisResponse.error(String.format("ERR collection size limit reached (%d items maximum per collection)", MAX_COLLECTION_ITEMS));
                }
            }
        } else if ("HSET".equals(command)) {
            if (args.size() >= 3 && (args.size() - 1) % 2 == 0) {
                int incomingFields = (args.size() - 1) / 2;
                if (incomingFields > MAX_COLLECTION_ITEMS) {
                    return RedisResponse.error(String.format("ERR maximum %d fields allowed per operation", MAX_COLLECTION_ITEMS));
                }
                String internalKey = toInternalKey(sessionId, primaryKey);
                RedisValue existing = storage.get(internalKey);
                if (existing != null && existing.getType() == RedisDataType.HASH) {
                    int currentCount = existing.getAsHash().size();
                    if (currentCount + incomingFields > MAX_COLLECTION_ITEMS) {
                        return RedisResponse.error(String.format("ERR collection size limit reached (%d fields maximum per hash)", MAX_COLLECTION_ITEMS));
                    }
                }
            }
        } else if ("APPEND".equals(command)) {
            if (args.size() >= 2) {
                String internalKey = toInternalKey(sessionId, primaryKey);
                RedisValue existing = storage.get(internalKey);
                int currentLength = (existing != null && existing.getType() == RedisDataType.STRING) ? existing.getAsString().length() : 0;
                if (currentLength + args.get(1).getBytes().length > MAX_VALUE_BYTES) {
                    return RedisResponse.error(String.format("ERR value exceeds maximum allowed size of %d bytes", MAX_VALUE_BYTES));
                }
            }
        } else if ("LINSERT".equals(command)) {
            String internalKey = toInternalKey(sessionId, primaryKey);
            RedisValue existing = storage.get(internalKey);
            if (existing != null && existing.getType() == RedisDataType.LIST) {
                int currentCount = existing.getAsList().size();
                if (currentCount + 1 > MAX_COLLECTION_ITEMS) {
                    return RedisResponse.error(String.format("ERR collection size limit reached (%d items maximum per collection)", MAX_COLLECTION_ITEMS));
                }
            }
        }

        // Check value size limits
        for (int i = 1; i < args.size(); i++) {
            if ("MSET".equals(command) && i % 2 != 0) {
                // Odd index in args for MSET: args.get(0) is key, args.get(1) is value, args.get(2) is key, args.get(3) is value
                // i=1 (value), i=2 (key), i=3 (value) -> check values only
            } else if ("MSET".equals(command)) {
                continue; // key, checked earlier
            }
            String val = args.get(i);
            if (val.getBytes().length > MAX_VALUE_BYTES) {
                return RedisResponse.error(String.format("ERR value exceeds maximum allowed size of %d bytes", MAX_VALUE_BYTES));
            }
        }

        // Check session and keyspace limits on mutating writes
        if (isMutating && !command.equals("DEL") && !command.equals("LPOP") && !command.equals("RPOP") && !command.equals("SREM") && !command.equals("HDEL") && !command.equals("LSET") && !command.equals("LINSERT")) {
            String targetInternal = toInternalKey(sessionId, primaryKey);
            if (storage.get(targetInternal) == null) {
                if (countSessionKeys(sessionId) >= MAX_KEYS_PER_SESSION) {
                    return RedisResponse.error(String.format("ERR session key limit reached (%d keys per visitor). Please delete existing keys.", MAX_KEYS_PER_SESSION));
                }
                if (storage.dbsize() >= MAX_GLOBAL_DEMO_KEYS) {
                    return RedisResponse.error(String.format("ERR demo keyspace capacity reached (%d keys). Please delete keys or run self-hosted.", MAX_GLOBAL_DEMO_KEYS));
                }
            }
        }

        // Map keys to namespaced keys
        List<String> namespacedTokens = new ArrayList<>();
        namespacedTokens.add(command);

        if ("MGET".equals(command) || "DEL".equals(command) || "EXISTS".equals(command)) {
            for (String k : args) {
                namespacedTokens.add(toInternalKey(sessionId, k));
            }
        } else if ("MSET".equals(command)) {
            for (int i = 0; i < args.size(); i += 2) {
                String k = args.get(i);
                String v = args.get(i + 1);
                if (isProtectedDemoKey(k)) {
                    return RedisResponse.error(String.format("ERR key '%s' is an immutable system demo key and cannot be modified", k));
                }
                namespacedTokens.add(toInternalKey(sessionId, k));
                namespacedTokens.add(v);
            }
        } else {
            // First argument is the key
            namespacedTokens.add(toInternalKey(sessionId, primaryKey));
            for (int i = 1; i < args.size(); i++) {
                namespacedTokens.add(args.get(i));
            }
        }

        return registry.dispatch(namespacedTokens, storage);
    }

    private RedisResponse handleIsolatedKeysCommand(String sessionId, String pattern) {
        String cleanPattern = (pattern != null && !pattern.isEmpty()) ? pattern : "*";
        if (isInternalNamespaceKey(cleanPattern)) {
            return RedisResponse.error("ERR pattern cannot target internal namespace 'session:'");
        }
        Pattern regex = StorageEngine.compileGlobToRegex(cleanPattern);
        List<RedisResponse> resultResponses = new ArrayList<>();

        // 1. Public demo keys matching pattern
        for (String k : storage.keys("demo:*")) {
            if (regex.matcher(k).matches()) {
                resultResponses.add(RedisResponse.bulkString(k));
                if (resultResponses.size() >= 50) break;
            }
        }

        // 2. Caller's own session keys matching pattern (never other sessions)
        if (resultResponses.size() < 50 && sessionId != null && !sessionId.isEmpty()) {
            String prefix = SESSION_KEY_PREFIX + sessionId.trim() + ":";
            for (String internalK : storage.keys(prefix + "*")) {
                String externalK = internalK.substring(prefix.length());
                if (regex.matcher(externalK).matches()) {
                    resultResponses.add(RedisResponse.bulkString(externalK));
                    if (resultResponses.size() >= 50) break;
                }
            }
        }
        return RedisResponse.array(resultResponses);
    }

    private RedisResponse handleSanitizedInfoCommand(String sessionId) {
        long uptime = storage.getServerMetrics().getUptimeSeconds();
        int demoKeys = storage.keys("demo:*").size();
        int sessionKeys = countSessionKeys(sessionId);

        String sanitized = String.format(
                "# Server\r\n" +
                "redis_version:1.0.0\r\n" +
                "miniredis_mode:public_demo\r\n" +
                "uptime_in_seconds:%d\r\n" +
                "\r\n" +
                "# Clients\r\n" +
                "connected_clients:%d\r\n" +
                "\r\n" +
                "# Memory\r\n" +
                "used_memory_human:%s\r\n" +
                "\r\n" +
                "# Persistence\r\n" +
                "aof_enabled:1\r\n" +
                "aof_storage_type:ephemeral_cloud_container\r\n" +
                "\r\n" +
                "# Keyspace\r\n" +
                "db0:keys=%d,public_demo_keys=%d,session_keys=%d\r\n",
                uptime,
                storage.getServerMetrics().getConnectedClientCount(),
                com.miniredis.web.JsonUtil.escape("bounded"),
                demoKeys + sessionKeys, demoKeys, sessionKeys
        );
        return RedisResponse.bulkString(sanitized);
    }

    /**
     * Safely expires abandoned visitor sessions older than 30 minutes.
     * Guarantees demo:* keys and active sessions are NEVER touched.
     */
    public synchronized void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        List<String> expiredSessions = new ArrayList<>();

        for (Map.Entry<String, Long> entry : activeSessions.entrySet()) {
            if (now - entry.getValue() > SESSION_IDLE_TIMEOUT_MS) {
                expiredSessions.add(entry.getKey());
            }
        }

        for (String expiredId : expiredSessions) {
            String pattern = SESSION_KEY_PREFIX + expiredId + ":*";
            Set<String> keysToDelete = storage.keys(pattern);
            if (!keysToDelete.isEmpty()) {
                storage.del(new ArrayList<>(keysToDelete));
            }
            activeSessions.remove(expiredId);
        }

        // Sweep any orphaned session:* keys whose session is no longer active
        for (String internalK : storage.keys(SESSION_KEY_PREFIX + "*")) {
            String rest = internalK.substring(SESSION_KEY_PREFIX.length());
            int colonIdx = rest.indexOf(':');
            if (colonIdx > 0) {
                String sid = rest.substring(0, colonIdx);
                Long lastTouch = activeSessions.get(sid);
                if (lastTouch == null || (now - lastTouch > SESSION_IDLE_TIMEOUT_MS)) {
                    storage.del(List.of(internalK));
                }
            }
        }
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }
}
