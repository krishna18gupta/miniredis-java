package com.miniredis.commands;

import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.RedisDataType;
import com.miniredis.storage.RedisValue;
import com.miniredis.storage.StorageEngine;
import java.util.List;

public class GetCommand implements Command {
    @Override
    public String getName() {
        return "GET";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'get' command");
        }
        String key = args.get(0);
        RedisValue val = storage.get(key);
        if (val == null) {
            return RedisResponse.nil();
        }
        if (val.getType() != RedisDataType.STRING) {
            return RedisResponse.error("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return RedisResponse.bulkString(val.getAsByteSequence());
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'get' command");
        }
        ByteSequence key = args.get(0);
        RedisValue val = storage.get(key);
        if (val == null) {
            return RedisResponse.nil();
        }
        if (val.getType() != RedisDataType.STRING) {
            return RedisResponse.error("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return RedisResponse.bulkString(val.getAsByteSequence());
    }
}
