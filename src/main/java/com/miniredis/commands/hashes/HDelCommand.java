package com.miniredis.commands.hashes;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class HDelCommand implements Command {
    @Override
    public String getName() {
        return "HDEL";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() < 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'hdel' command");
        }
        String key = args.get(0);
        List<String> fields = args.subList(1, args.size());
        try {
            int removed = storage.hdel(key, fields);
            return RedisResponse.integer(removed);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.size() < 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'hdel' command");
        }
        ByteSequence key = args.get(0);
        List<ByteSequence> fields = args.subList(1, args.size());
        try {
            int removed = storage.hdelBytes(key, fields);
            return RedisResponse.integer(removed);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}