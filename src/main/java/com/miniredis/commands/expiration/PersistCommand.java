package com.miniredis.commands.expiration;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.List;

public class PersistCommand implements Command {
    @Override
    public String getName() {
        return "PERSIST";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'persist' command");
        }
        int result = storage.persist(args.get(0));
        return RedisResponse.integer(result);
    }
}