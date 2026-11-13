package com.miniredis.commands.hashes;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HSetCommand implements Command {
    @Override
    public String getName() {
        return "HSET";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() < 3 || (args.size() - 1) % 2 != 0) {
            return RedisResponse.error("ERR wrong number of arguments for 'hset' command");
        }
        String key = args.get(0);
        Map<String, String> fieldValues = new LinkedHashMap<>();
        for (int i = 1; i < args.size(); i += 2) {
            fieldValues.put(args.get(i), args.get(i + 1));
        }
        try {
            int newFields = storage.hset(key, fieldValues);
            return RedisResponse.integer(newFields);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}