package com.miniredis.commands.lists;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class LRangeCommand implements Command {
    @Override
    public String getName() {
        return "LRANGE";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 3) {
            return RedisResponse.error("ERR wrong number of arguments for 'lrange' command");
        }
        String key = args.get(0);
        int start;
        int stop;
        try {
            start = Integer.parseInt(args.get(1));
            stop = Integer.parseInt(args.get(2));
        } catch (NumberFormatException e) {
            return RedisResponse.error("ERR value is not an integer or out of range");
        }

        try {
            List<String> range = storage.lrange(key, start, stop);
            return RedisResponse.stringList(range);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}