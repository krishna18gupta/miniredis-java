package com.miniredis.commands.sets;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class SAddCommand implements Command {
    @Override
    public String getName() {
        return "SADD";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() < 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'sadd' command");
        }
        String key = args.get(0);
        List<String> members = args.subList(1, args.size());
        try {
            int added = storage.sadd(key, members);
            return RedisResponse.integer(added);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.size() < 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'sadd' command");
        }
        ByteSequence key = args.get(0);
        List<ByteSequence> members = args.subList(1, args.size());
        try {
            int added = storage.saddBytes(key, members);
            return RedisResponse.integer(added);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}