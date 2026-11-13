package com.miniredis.commands.system;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * HELLO [protover [AUTH username password] [SETNAME clientname]]
 * Redis 6.0+ handshake command supported by redis-py, Jedis, and Lettuce.
 * Gracefully reports RESP2 protocol capabilities.
 */
public class HelloCommand implements Command {

    @Override
    public String getName() {
        return "HELLO";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        // Return server attributes in key-value list format
        List<RedisResponse> items = new ArrayList<>();
        items.add(RedisResponse.bulkString("server"));
        items.add(RedisResponse.bulkString("miniredis"));
        items.add(RedisResponse.bulkString("version"));
        items.add(RedisResponse.bulkString("1.0.0"));
        items.add(RedisResponse.bulkString("proto"));
        items.add(RedisResponse.integer(2));
        items.add(RedisResponse.bulkString("id"));
        items.add(RedisResponse.integer(1));
        items.add(RedisResponse.bulkString("mode"));
        items.add(RedisResponse.bulkString("standalone"));
        items.add(RedisResponse.bulkString("role"));
        items.add(RedisResponse.bulkString("master"));
        items.add(RedisResponse.bulkString("modules"));
        items.add(RedisResponse.array(List.of()));

        return RedisResponse.array(items);
    }
}
