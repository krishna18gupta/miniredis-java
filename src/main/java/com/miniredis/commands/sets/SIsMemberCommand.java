package com.miniredis.commands.sets;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class SIsMemberCommand implements Command {
    @Override
    public String getName() {
        return "SISMEMBER";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'sismember' command");
        }
        try {
            boolean exists = storage.sismember(args.get(0), args.get(1));
            return RedisResponse.integer(exists ? 1 : 0);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'sismember' command");
        }
        try {
            boolean exists = storage.sismember(args.get(0), args.get(1));
            return RedisResponse.integer(exists ? 1 : 0);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}