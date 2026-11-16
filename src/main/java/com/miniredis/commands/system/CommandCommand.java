package com.miniredis.commands.system;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * COMMAND [DOCS | COUNT ...]
 * Dummy command responder providing compatibility with redis-cli and official Redis drivers
 * during handshake and discovery.
 */
public class CommandCommand implements Command {

    private static final List<String> ALL_COMMANDS = List.of(
            "SET", "GET", "DEL", "EXISTS", "KEYS", "FLUSHDB", "DBSIZE",
            "APPEND", "STRLEN", "MSET", "MGET", "INCR", "DECR", "INCRBY", "DECRBY",
            "LPUSH", "RPUSH", "LPOP", "RPOP", "LLEN", "LRANGE", "LINDEX", "LSET", "LINSERT",
            "SADD", "SMEMBERS", "SISMEMBER", "SREM", "SCARD",
            "HSET", "HGET", "HGETALL", "HDEL", "HEXISTS",
            "EXPIRE", "PEXPIRE", "PEXPIREAT", "TTL", "PTTL", "PERSIST", "SETEX",
            "PING", "ECHO", "TYPE", "RENAME", "RANDOMKEY",
            "SAVE", "BGREWRITEAOF", "SHUTDOWN",
            "MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH",
            "COMMAND", "HELLO", "SELECT", "CLIENT", "AUTH", "INFO"
    );

    @Override
    public String getName() {
        return "COMMAND";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (!args.isEmpty() && args.get(0).equalsIgnoreCase("COUNT")) {
            return RedisResponse.integer(ALL_COMMANDS.size());
        }
        List<RedisResponse> list = new ArrayList<>(ALL_COMMANDS.size());
        for (String cmd : ALL_COMMANDS) {
            list.add(RedisResponse.bulkString(cmd.toLowerCase(Locale.ROOT)));
        }
        return RedisResponse.array(list);
    }
}
