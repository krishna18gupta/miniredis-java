package com.miniredis.commands;

import com.miniredis.commands.expiration.*;
import com.miniredis.commands.hashes.*;
import com.miniredis.commands.lists.*;
import com.miniredis.commands.sets.*;
import com.miniredis.commands.strings.*;
import com.miniredis.commands.system.*;
import com.miniredis.concurrency.StripedLockManager;
import com.miniredis.server.TransactionContext;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.*;

/**
 * Registry and dispatcher for all Redis commands.
 * Handles case-insensitive lookup, command-line tokenization with quoted strings,
 * striped locking, AOF persistence logging, and transactional MULTI/EXEC queuing.
 *
 * Binary Safety:
 * Dispatches commands natively as immutable ByteSequence tokens without lossy String conversion.
 * String-based callers adapt seamlessly through UTF-8 encoding at the public boundary.
 */
public class CommandRegistry {
    private final Map<String, Command> commands = new HashMap<>();

    public CommandRegistry() {
        registerDefaultCommands();
    }

    private void registerDefaultCommands() {
        // Phase 1 Commands (7)
        register(new SetCommand());
        register(new GetCommand());
        register(new DelCommand());
        register(new ExistsCommand());
        register(new KeysCommand());
        register(new FlushDbCommand());
        register(new DbSizeCommand());

        // Phase 2 & Phase 8: Advanced Strings (8)
        register(new MSetCommand());
        register(new MGetCommand());
        register(new IncrCommand());
        register(new DecrCommand());
        register(new IncrByCommand());
        register(new DecrByCommand());
        register(new AppendCommand());
        register(new StrLenCommand());

        // Phase 2: Lists (6)
        register(new LPushCommand());
        register(new RPushCommand());
        register(new LPopCommand());
        register(new RPopCommand());
        register(new LLenCommand());
        register(new LRangeCommand());

        // Phase 2: Sets (5)
        register(new SAddCommand());
        register(new SRemCommand());
        register(new SIsMemberCommand());
        register(new SMembersCommand());
        register(new SCardCommand());

        // Phase 2: Hashes (5)
        register(new HSetCommand());
        register(new HGetCommand());
        register(new HGetAllCommand());
        register(new HDelCommand());
        register(new HExistsCommand());

        // Phase 3: Expiration & TTL (7)
        register(new ExpireCommand());
        register(new PExpireCommand());
        register(new PexpireAtCommand());
        register(new TtlCommand());
        register(new PTtlCommand());
        register(new PersistCommand());
        register(new SetExCommand());

        // Phase 4, Phase 7 & Phase 8: System, Utilities, Lifecycle & Compatibility Commands (15)
        register(new PingCommand());
        register(new EchoCommand());
        register(new TypeCommand());
        register(new RenameCommand());
        register(new RandomKeyCommand());
        register(new SaveCommand());
        register(new ShutdownCommand());
        register(new CommandCommand());
        register(new BgRewriteAofCommand());
        register(new HelloCommand());
        register(new SelectCommand());
        register(new ClientCommand());
        register(new AuthCommand());
        register(new InfoCommand());
    }

    public void register(Command command) {
        Objects.requireNonNull(command, "command cannot be null");
        commands.put(command.getName().toUpperCase(Locale.ROOT), command);
    }

    public Optional<Command> getCommand(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(commands.get(name.toUpperCase(Locale.ROOT)));
    }

    public RedisResponse dispatch(List<String> tokens, StorageEngine storage) {
        return dispatch(tokens, storage, null);
    }

    public RedisResponse dispatch(String rawCommandLine, StorageEngine storage) {
        return dispatch(rawCommandLine, storage, null);
    }

    public RedisResponse dispatch(String rawCommandLine, StorageEngine storage, TransactionContext tx) {
        if (rawCommandLine == null || rawCommandLine.trim().isEmpty()) {
            return RedisResponse.error("ERR empty command");
        }
        List<String> tokens = parseCommandLine(rawCommandLine);
        return dispatch(tokens, storage, tx);
    }

    public RedisResponse dispatch(List<String> tokens, StorageEngine storage, TransactionContext tx) {
        if (tokens == null || tokens.isEmpty()) {
            return RedisResponse.error("ERR empty command");
        }
        List<ByteSequence> byteTokens = new ArrayList<>(tokens.size());
        for (String s : tokens) {
            byteTokens.add(ByteSequence.ofUtf8(s));
        }
        return dispatchBytes(byteTokens, storage, tx);
    }

    public RedisResponse dispatchBytes(List<ByteSequence> tokens, StorageEngine storage) {
        return dispatchBytes(tokens, storage, null);
    }

    public RedisResponse dispatchBytes(List<ByteSequence> tokens, StorageEngine storage, TransactionContext tx) {
        if (tokens == null || tokens.isEmpty()) {
            return RedisResponse.error("ERR empty command");
        }

        String commandName = tokens.get(0).toUtf8String().toUpperCase(Locale.ROOT);
        List<ByteSequence> args = tokens.subList(1, tokens.size());

        // Transaction Control Commands
        switch (commandName) {
            case "WATCH":
                return handleWatchBytes(args, storage, tx);
            case "UNWATCH":
                return handleUnwatchBytes(tokens, args, storage, tx);
            case "MULTI":
                return handleMultiBytes(args, storage, tx);
            case "DISCARD":
                return handleDiscardBytes(args, storage, tx);
            case "EXEC":
                return handleExecBytes(args, storage, tx);
            default:
                break;
        }

        // If inside MULTI block, validate & queue instead of executing immediately
        if (tx != null && tx.isInMulti()) {
            Command cmd = commands.get(commandName);
            if (cmd == null) {
                tx.markExecAbort();
                if (storage != null) storage.incrementFailedOperations();
                return RedisResponse.error("ERR unknown command '" + tokens.get(0).toUtf8String() + "'");
            }
            RedisResponse syntaxError = validateQueueSyntaxBytes(commandName, args);
            if (syntaxError != null) {
                tx.markExecAbort();
                if (storage != null) storage.incrementFailedOperations();
                return syntaxError;
            }
            tx.queueCommandBytes(tokens);
            return RedisResponse.simpleString("QUEUED");
        }

        return executeSingleCommandBytes(tokens, commandName, args, storage);
    }

    private RedisResponse handleWatchBytes(List<ByteSequence> args, StorageEngine storage, TransactionContext tx) {
        if (tx == null || storage == null) {
            return RedisResponse.error("ERR WATCH requires a stateful client connection");
        }
        if (tx.isInMulti()) {
            storage.incrementFailedOperations();
            return RedisResponse.error("ERR WATCH inside MULTI is not allowed");
        }
        if (args.isEmpty()) {
            storage.incrementFailedOperations();
            return RedisResponse.error("ERR wrong number of arguments for 'watch' command");
        }
        for (ByteSequence key : args) {
            tx.addWatch(key, storage.captureWatch(key));
        }
        storage.incrementCompletedOperations();
        return RedisResponse.ok();
    }

    private RedisResponse handleUnwatchBytes(List<ByteSequence> tokens, List<ByteSequence> args, StorageEngine storage, TransactionContext tx) {
        if (tx != null && tx.isInMulti()) {
            if (!args.isEmpty()) {
                tx.markExecAbort();
                if (storage != null) storage.incrementFailedOperations();
                return RedisResponse.error("ERR wrong number of arguments for 'unwatch' command");
            }
            tx.queueCommandBytes(tokens);
            return RedisResponse.simpleString("QUEUED");
        }
        if (!args.isEmpty()) {
            if (storage != null) storage.incrementFailedOperations();
            return RedisResponse.error("ERR wrong number of arguments for 'unwatch' command");
        }
        if (tx != null) {
            tx.clearWatches();
        }
        if (storage != null) {
            storage.incrementCompletedOperations();
        }
        return RedisResponse.ok();
    }

    private RedisResponse handleMultiBytes(List<ByteSequence> args, StorageEngine storage, TransactionContext tx) {
        if (tx == null || storage == null) {
            return RedisResponse.error("ERR MULTI requires a stateful client connection");
        }
        if (!args.isEmpty()) {
            if (tx.isInMulti()) {
                tx.markExecAbort();
            }
            storage.incrementFailedOperations();
            return RedisResponse.error("ERR wrong number of arguments for 'multi' command");
        }
        if (tx.isInMulti()) {
            storage.incrementFailedOperations();
            return RedisResponse.error("ERR MULTI calls can not be nested");
        }
        tx.beginMulti();
        storage.incrementCompletedOperations();
        return RedisResponse.ok();
    }

    private RedisResponse handleDiscardBytes(List<ByteSequence> args, StorageEngine storage, TransactionContext tx) {
        if (tx == null || !tx.isInMulti()) {
            if (storage != null) storage.incrementFailedOperations();
            return RedisResponse.error("ERR DISCARD without MULTI");
        }
        if (!args.isEmpty()) {
            if (storage != null) storage.incrementFailedOperations();
            return RedisResponse.error("ERR wrong number of arguments for 'discard' command");
        }
        tx.resetAfterExecOrDiscard();
        if (storage != null) storage.incrementCompletedOperations();
        return RedisResponse.ok();
    }

    private RedisResponse handleExecBytes(List<ByteSequence> args, StorageEngine storage, TransactionContext tx) {
        if (tx == null || !tx.isInMulti()) {
            if (storage != null) storage.incrementFailedOperations();
            return RedisResponse.error("ERR EXEC without MULTI");
        }
        if (!args.isEmpty()) {
            if (storage != null) storage.incrementFailedOperations();
            return RedisResponse.error("ERR wrong number of arguments for 'exec' command");
        }

        if (tx.isExecAbort()) {
            tx.resetAfterExecOrDiscard();
            if (storage != null) storage.incrementFailedOperations();
            return RedisResponse.error("EXECABORT Transaction discarded because of previous errors.");
        }

        List<List<ByteSequence>> queue = new ArrayList<>(tx.getQueuedCommandsBytes());
        List<StorageEngine.WatchSnapshot> watches = new ArrayList<>(tx.getWatchedByteKeys().values());
        tx.resetAfterExecOrDiscard();

        Set<ByteSequence> allKeys = new LinkedHashSet<>();
        for (StorageEngine.WatchSnapshot w : watches) {
            allKeys.add(w.keyBytes != null ? w.keyBytes : ByteSequence.ofUtf8(w.key));
        }

        boolean needsGlobalWriteLock = false;
        for (List<ByteSequence> queued : queue) {
            String cmdName = queued.get(0).toUtf8String().toUpperCase(Locale.ROOT);
            if (requiresGlobalLock(cmdName)) {
                needsGlobalWriteLock = true;
            } else {
                allKeys.addAll(extractKeysForCommandBytes(cmdName, queued));
            }
        }

        StripedLockManager.LockHandle lock = null;
        if (storage != null && storage.getStripedLockManager() != null) {
            if (needsGlobalWriteLock) {
                lock = storage.getStripedLockManager().allWriteLocks();
            } else if (!allKeys.isEmpty()) {
                // Always acquire exclusive write locks upfront in canonical ascending stripe order
                // to guarantee isolation and prevent lock-upgrade deadlocks.
                lock = storage.getStripedLockManager().writeLocksBytes(allKeys);
            }
        }

        try {
            if (storage != null) {
                for (StorageEngine.WatchSnapshot w : watches) {
                    if (!storage.isWatchValid(w)) {
                        storage.incrementCompletedOperations();
                        return RedisResponse.nullArray();
                    }
                }
            }

            List<RedisResponse> responses = new ArrayList<>(queue.size());
            List<List<ByteSequence>> mutatingAofCommands = new ArrayList<>();

            for (List<ByteSequence> queued : queue) {
                String cmdName = queued.get(0).toUtf8String().toUpperCase(Locale.ROOT);
                List<ByteSequence> cmdArgs = queued.subList(1, queued.size());

                if ("UNWATCH".equals(cmdName)) {
                    responses.add(RedisResponse.ok());
                    if (storage != null) storage.incrementCompletedOperations();
                    continue;
                }

                Command cmd = commands.get(cmdName);
                if (cmd == null) {
                    responses.add(RedisResponse.error("ERR unknown command '" + queued.get(0).toUtf8String() + "'"));
                    if (storage != null) storage.incrementFailedOperations();
                    continue;
                }

                try {
                    RedisResponse resp = cmd.executeBytes(cmdArgs, storage);
                    responses.add(resp);
                    if (resp.getType() == RedisResponse.ResponseType.ERROR) {
                        if (storage != null) storage.incrementFailedOperations();
                    } else {
                        if (storage != null) {
                            storage.incrementCompletedOperations();
                            if (isMutatingCommand(cmdName) && resp.isMutated()) {
                                List<ByteSequence> normalized = normalizeForAofBytes(queued, storage);
                                if (normalized != null) {
                                    mutatingAofCommands.add(normalized);
                                }
                            }
                        }
                    }
                } catch (WrongTypeException e) {
                    if (storage != null) storage.incrementFailedOperations();
                    responses.add(RedisResponse.error(e.getMessage()));
                } catch (Exception e) {
                    if (storage != null) storage.incrementFailedOperations();
                    responses.add(RedisResponse.error("ERR " + e.getMessage()));
                }
            }

            if (storage != null && storage.getAofManager() != null && !mutatingAofCommands.isEmpty()) {
                storage.getAofManager().appendTransactionBytes(mutatingAofCommands);
            }

            if (storage != null) {
                storage.incrementCompletedOperations();
            }
            return RedisResponse.array(responses);
        } catch (com.miniredis.aof.AofException e) {
            if (storage != null) storage.incrementFailedOperations();
            return RedisResponse.error("ERR AOF persistence failed: " + e.getMessage());
        } finally {
            if (lock != null) {
                lock.close();
            }
        }
    }

    private RedisResponse executeSingleCommandBytes(List<ByteSequence> tokens, String commandName,
                                                    List<ByteSequence> args, StorageEngine storage) {
        Command cmd = commands.get(commandName);
        if (cmd == null) {
            if (storage != null) {
                storage.incrementFailedOperations();
            }
            return RedisResponse.error("ERR unknown command '" + tokens.get(0).toUtf8String() + "'");
        }

        StripedLockManager.LockHandle lock = null;
        if (storage != null && storage.getStripedLockManager() != null && isMutatingCommand(commandName)) {
            if ("FLUSHDB".equals(commandName)) {
                lock = storage.getStripedLockManager().allWriteLocks();
            } else {
                Collection<ByteSequence> keys = getMutatingKeysBytes(commandName, tokens);
                if (!keys.isEmpty()) {
                    lock = storage.getStripedLockManager().writeLocksBytes(keys);
                }
            }
        }

        try {
            RedisResponse resp;
            try {
                resp = cmd.executeBytes(args, storage);
                if (resp.getType() == RedisResponse.ResponseType.ERROR) {
                    if (storage != null) storage.incrementFailedOperations();
                } else {
                    if (storage != null) {
                        storage.incrementCompletedOperations();
                        if (resp.isMutated()) {
                            logToAofIfMutatingBytes(tokens, storage);
                        }
                    }
                }
                return resp;
            } finally {
                if (lock != null) {
                    lock.close();
                }
            }
        } catch (com.miniredis.aof.AofException e) {
            if (storage != null) storage.incrementFailedOperations();
            return RedisResponse.error("ERR AOF persistence failed: " + e.getMessage());
        } catch (WrongTypeException e) {
            if (storage != null) storage.incrementFailedOperations();
            return RedisResponse.error(e.getMessage());
        } catch (Exception e) {
            if (storage != null) storage.incrementFailedOperations();
            return RedisResponse.error("ERR internal server error: " + e.getMessage());
        }
    }

    private RedisResponse validateQueueSyntaxBytes(String commandName, List<ByteSequence> args) {
        String lower = commandName.toLowerCase(Locale.ROOT);
        switch (commandName) {
            case "FLUSHDB":
            case "DBSIZE":
            case "BGREWRITEAOF":
            case "RANDOMKEY":
            case "SAVE":
                if (!args.isEmpty()) {
                    return RedisResponse.error("ERR wrong number of arguments for '" + lower + "' command");
                }
                return null;
            case "PING":
            case "HELLO":
            case "INFO":
                if (args.size() > 1) {
                    return RedisResponse.error("ERR wrong number of arguments for '" + lower + "' command");
                }
                return null;
            case "SHUTDOWN":
                if (args.size() > 1) {
                    return RedisResponse.error("ERR syntax error");
                }
                if (args.size() == 1) {
                    String mod = args.get(0).toUtf8String().toUpperCase(Locale.ROOT);
                    if (!"SAVE".equals(mod) && !"NOSAVE".equals(mod)) {
                        return RedisResponse.error("ERR syntax error");
                    }
                }
                return null;
            case "GET":
            case "KEYS":
            case "INCR":
            case "DECR":
            case "STRLEN":
            case "LPOP":
            case "RPOP":
            case "LLEN":
            case "SMEMBERS":
            case "SCARD":
            case "HGETALL":
            case "PERSIST":
            case "TTL":
            case "PTTL":
            case "SELECT":
            case "ECHO":
            case "TYPE":
                if (args.size() != 1) {
                    return RedisResponse.error("ERR wrong number of arguments for '" + lower + "' command");
                }
                return null;
            case "INCRBY":
            case "DECRBY":
            case "APPEND":
            case "SISMEMBER":
            case "HGET":
            case "HEXISTS":
            case "EXPIRE":
            case "PEXPIRE":
            case "PEXPIREAT":
            case "RENAME":
                if (args.size() != 2) {
                    return RedisResponse.error("ERR wrong number of arguments for '" + lower + "' command");
                }
                return null;
            case "LRANGE":
            case "SETEX":
                if (args.size() != 3) {
                    return RedisResponse.error("ERR wrong number of arguments for '" + lower + "' command");
                }
                return null;
            case "DEL":
            case "EXISTS":
            case "MGET":
            case "CLIENT":
            case "AUTH":
                if (args.isEmpty()) {
                    return RedisResponse.error("ERR wrong number of arguments for '" + lower + "' command");
                }
                return null;
            case "SET":
                return SetCommand.validateSyntaxBytes(args);
            case "LPUSH":
            case "RPUSH":
            case "SADD":
            case "SREM":
            case "HDEL":
                if (args.size() < 2) {
                    return RedisResponse.error("ERR wrong number of arguments for '" + lower + "' command");
                }
                return null;
            case "MSET":
                if (args.size() < 2 || args.size() % 2 != 0) {
                    return RedisResponse.error("ERR wrong number of arguments for 'mset' command");
                }
                return null;
            case "HSET":
                if (args.size() < 3 || (args.size() - 1) % 2 != 0) {
                    return RedisResponse.error("ERR wrong number of arguments for 'hset' command");
                }
                return null;
            case "COMMAND":
            default:
                return null;
        }
    }

    private boolean isMutatingCommand(String commandName) {
        return switch (commandName) {
            case "SET", "SETEX", "DEL", "FLUSHDB", "MSET", "INCR", "DECR", "INCRBY", "DECRBY", "APPEND",
                 "LPUSH", "RPUSH", "LPOP", "RPOP", "SADD", "SREM", "HSET", "HDEL",
                 "EXPIRE", "PEXPIRE", "PEXPIREAT", "PERSIST", "RENAME" -> true;
            default -> false;
        };
    }

    private boolean requiresGlobalLock(String commandName) {
        return switch (commandName) {
            case "FLUSHDB", "KEYS", "RANDOMKEY", "SAVE", "BGREWRITEAOF", "SHUTDOWN", "INFO", "DBSIZE" -> true;
            default -> false;
        };
    }

    private Collection<ByteSequence> getMutatingKeysBytes(String commandName, List<ByteSequence> tokens) {
        if (tokens.size() < 2) return Collections.emptyList();
        if ("DEL".equals(commandName)) {
            return tokens.subList(1, tokens.size());
        }
        if ("RENAME".equals(commandName)) {
            return tokens.size() >= 3 ? List.of(tokens.get(1), tokens.get(2)) : Collections.emptyList();
        }
        if ("MSET".equals(commandName)) {
            List<ByteSequence> keys = new ArrayList<>((tokens.size() - 1) / 2);
            for (int i = 1; i < tokens.size(); i += 2) {
                keys.add(tokens.get(i));
            }
            return keys;
        }
        if ("FLUSHDB".equals(commandName)) {
            return Collections.emptyList();
        }
        return List.of(tokens.get(1));
    }

    private Collection<ByteSequence> extractKeysForCommandBytes(String commandName, List<ByteSequence> tokens) {
        if (tokens.size() < 2) return Collections.emptyList();
        switch (commandName) {
            case "DEL":
            case "EXISTS":
            case "MGET":
                return tokens.subList(1, tokens.size());
            case "RENAME":
                return tokens.size() >= 3 ? List.of(tokens.get(1), tokens.get(2)) : Collections.emptyList();
            case "MSET": {
                List<ByteSequence> keys = new ArrayList<>((tokens.size() - 1) / 2);
                for (int i = 1; i < tokens.size(); i += 2) {
                    keys.add(tokens.get(i));
                }
                return keys;
            }
            case "PING":
            case "ECHO":
            case "COMMAND":
            case "HELLO":
            case "SELECT":
            case "CLIENT":
            case "AUTH":
            case "UNWATCH":
                return Collections.emptyList();
            default:
                return List.of(tokens.get(1));
        }
    }

    private void logToAofIfMutatingBytes(List<ByteSequence> tokens, StorageEngine storage) {
        if (storage == null || storage.getAofManager() == null || tokens.isEmpty()) {
            return;
        }
        List<ByteSequence> normalized = normalizeForAofBytes(tokens, storage);
        if (normalized != null) {
            storage.getAofManager().appendCommandBytes(normalized);
        }
    }

    private List<ByteSequence> normalizeForAofBytes(List<ByteSequence> tokens, StorageEngine storage) {
        String name = tokens.get(0).toUtf8String().toUpperCase(Locale.ROOT);
        long now = storage.getClock().millis();

        switch (name) {
            case "SETEX":
                if (tokens.size() >= 4) {
                    try {
                        long seconds = Long.parseLong(tokens.get(2).toUtf8String());
                        return List.of(ByteSequence.ofAscii("SET"), tokens.get(1), tokens.get(3),
                                       ByteSequence.ofAscii("PXAT"), ByteSequence.ofAscii(String.valueOf(now + seconds * 1000L)));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                return null;
            case "EXPIRE":
                if (tokens.size() >= 3) {
                    try {
                        long seconds = Long.parseLong(tokens.get(2).toUtf8String());
                        return List.of(ByteSequence.ofAscii("PEXPIREAT"), tokens.get(1),
                                       ByteSequence.ofAscii(String.valueOf(now + seconds * 1000L)));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                return null;
            case "PEXPIRE":
                if (tokens.size() >= 3) {
                    try {
                        long millis = Long.parseLong(tokens.get(2).toUtf8String());
                        return List.of(ByteSequence.ofAscii("PEXPIREAT"), tokens.get(1),
                                       ByteSequence.ofAscii(String.valueOf(now + millis)));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                return null;
            case "SET":
                if (tokens.size() < 3) return null;
                ByteSequence setKey = tokens.get(1);
                ByteSequence setVal = tokens.get(2);
                if (storage.exists(setKey)) {
                    Long exp = storage.getExpireAt(setKey);
                    if (exp != null) {
                        return List.of(ByteSequence.ofAscii("SET"), setKey, setVal,
                                       ByteSequence.ofAscii("PXAT"), ByteSequence.ofAscii(String.valueOf(exp)));
                    } else {
                        return List.of(ByteSequence.ofAscii("SET"), setKey, setVal);
                    }
                } else {
                    return List.of(ByteSequence.ofAscii("DEL"), setKey);
                }
            case "DEL":
            case "FLUSHDB":
            case "MSET":
            case "INCR":
            case "DECR":
            case "INCRBY":
            case "DECRBY":
            case "APPEND":
            case "LPUSH":
            case "RPUSH":
            case "LPOP":
            case "RPOP":
            case "SADD":
            case "SREM":
            case "HSET":
            case "HDEL":
            case "PERSIST":
            case "PEXPIREAT":
            case "RENAME":
                return tokens;
            default:
                return null;
        }
    }

    public static List<String> parseCommandLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inDoubleQuotes = false;
        boolean inSingleQuotes = false;
        boolean escaping = false;
        boolean hasToken = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (escaping) {
                sb.append(c);
                escaping = false;
                hasToken = true;
                continue;
            }

            if (c == '\\') {
                escaping = true;
                hasToken = true;
                continue;
            }

            if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                hasToken = true;
                continue;
            }

            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                hasToken = true;
                continue;
            }

            if (Character.isWhitespace(c) && !inDoubleQuotes && !inSingleQuotes) {
                if (hasToken || sb.length() > 0) {
                    tokens.add(sb.toString());
                    sb.setLength(0);
                    hasToken = false;
                }
            } else {
                sb.append(c);
                hasToken = true;
            }
        }

        if (hasToken || sb.length() > 0) {
            tokens.add(sb.toString());
        }

        return tokens;
    }
}
