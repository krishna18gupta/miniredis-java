package com.miniredis.commands;

import com.miniredis.storage.StorageEngine;
import java.util.List;

public class ExistsCommand implements Command {
    @Override
    public String getName() {
        return "EXISTS";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.isEmpty()) {
            return RedisResponse.error("ERR wrong number of arguments for 'exists' command");
        }
        int count = storage.exists(args);
        return RedisResponse.integer(count);
    }
}
