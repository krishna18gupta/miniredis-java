package com.miniredis.commands.lists;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class LPushCommand implements Command {
    @Override
    public String getName() {
        return "LPUSH";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() < 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'lpush' command");
        }
        String key = args.get(0);
        List<String> elements = args.subList(1, args.size());
        try {
            int len = storage.lpush(key, elements);
            return RedisResponse.integer(len);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.size() < 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'lpush' command");
        }
        ByteSequence key = args.get(0);
        List<ByteSequence> elements = args.subList(1, args.size());
        try {
            int len = storage.lpushBytes(key, elements);
            return RedisResponse.integer(len);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}