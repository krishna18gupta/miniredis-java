package com.miniredis.commands.strings;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class AppendCommand implements Command {
    @Override
    public String getName() {
        return "APPEND";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'append' command");
        }
        try {
            int newLength = storage.append(args.get(0), args.get(1));
            return RedisResponse.integer(newLength);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}