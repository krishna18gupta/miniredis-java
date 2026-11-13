package com.miniredis.commands.lists;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class LLenCommand implements Command {
    @Override
    public String getName() {
        return "LLEN";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'llen' command");
        }
        try {
            int len = storage.llen(args.get(0));
            return RedisResponse.integer(len);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}