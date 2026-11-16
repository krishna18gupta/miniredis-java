package com.miniredis.commands.hashes;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.ArrayList;
import java.util.List;

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
        List<ByteSequence> byteArgs = new ArrayList<>(args.size());
        for (String arg : args) {
            byteArgs.add(arg != null ? ByteSequence.ofUtf8(arg) : ByteSequence.EMPTY);
        }
        return executeBytes(byteArgs, storage);
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.size() < 3 || (args.size() - 1) % 2 != 0) {
            return RedisResponse.error("ERR wrong number of arguments for 'hset' command");
        }
        ByteSequence key = args.get(0);
        List<ByteSequence> fieldPairs = args.subList(1, args.size());
        try {
            int newFields = storage.hsetBytes(key, fieldPairs);
            return RedisResponse.integer(newFields);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}
