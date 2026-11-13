package com.miniredis.commands.system;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.List;

/**
 * TYPE key
 * Returns the string representation of the type of value stored at key
 * (string, list, set, hash, or none).
 */
public class TypeCommand implements Command {

    @Override
    public String getName() {
        return "TYPE";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'type' command");
        }
        return RedisResponse.simpleString(storage.typeOf(args.get(0)));
    }
}
