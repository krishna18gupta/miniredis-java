package com.miniredis.commands;

import com.miniredis.storage.StorageEngine;
import java.util.List;
import java.util.Set;

public class KeysCommand implements Command {
    @Override
    public String getName() {
        return "KEYS";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'keys' command");
        }
        Set<String> keys = storage.keys(args.get(0));
        return RedisResponse.stringList(keys);
    }
}
