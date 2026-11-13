package com.miniredis.commands.sets;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class SRemCommand implements Command {
    @Override
    public String getName() {
        return "SREM";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() < 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'srem' command");
        }
        String key = args.get(0);
        List<String> members = args.subList(1, args.size());
        try {
            int removed = storage.srem(key, members);
            return RedisResponse.integer(removed);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}