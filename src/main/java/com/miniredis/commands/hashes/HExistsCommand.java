package com.miniredis.commands.hashes;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class HExistsCommand implements Command {
    @Override
    public String getName() {
        return "HEXISTS";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'hexists' command");
        }
        try {
            boolean exists = storage.hexists(args.get(0), args.get(1));
            return RedisResponse.integer(exists ? 1 : 0);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}