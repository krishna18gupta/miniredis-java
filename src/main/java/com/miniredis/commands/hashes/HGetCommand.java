package com.miniredis.commands.hashes;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class HGetCommand implements Command {
    @Override
    public String getName() {
        return "HGET";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'hget' command");
        }
        try {
            String val = storage.hget(args.get(0), args.get(1));
            return val != null ? RedisResponse.bulkString(val) : RedisResponse.nil();
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'hget' command");
        }
        try {
            ByteSequence val = storage.hget(args.get(0), args.get(1));
            return val != null ? RedisResponse.bulkString(val) : RedisResponse.nil();
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}