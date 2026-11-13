package com.miniredis.commands.lists;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class RPopCommand implements Command {
    @Override
    public String getName() {
        return "RPOP";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'rpop' command");
        }
        try {
            String val = storage.rpop(args.get(0));
            return val != null ? RedisResponse.bulkString(val) : RedisResponse.nil();
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}