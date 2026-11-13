package com.miniredis.commands.system;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.List;

/**
 * RANDOMKEY
 * Returns a random non-expired key from the currently selected database, or nil if empty.
 */
public class RandomKeyCommand implements Command {

    @Override
    public String getName() {
        return "RANDOMKEY";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (!args.isEmpty()) {
            return RedisResponse.error("ERR wrong number of arguments for 'randomkey' command");
        }
        String key = storage.randomKey();
        return key != null ? RedisResponse.bulkString(key) : RedisResponse.nil();
    }
}
