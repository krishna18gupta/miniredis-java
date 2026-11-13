package com.miniredis.commands.lists;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class LPopCommand implements Command {
    @Override
    public String getName() {
        return "LPOP";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'lpop' command");
        }
        try {
            String val = storage.lpop(args.get(0));
            return val != null ? RedisResponse.bulkString(val) : RedisResponse.nil();
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}