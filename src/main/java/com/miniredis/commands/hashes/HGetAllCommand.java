package com.miniredis.commands.hashes;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HGetAllCommand implements Command {
    @Override
    public String getName() {
        return "HGETALL";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'hgetall' command");
        }
        try {
            Map<String, String> map = storage.hgetall(args.get(0));
            List<RedisResponse> items = new ArrayList<>(map.size() * 2);
            for (Map.Entry<String, String> entry : map.entrySet()) {
                items.add(RedisResponse.bulkString(entry.getKey()));
                items.add(RedisResponse.bulkString(entry.getValue()));
            }
            return RedisResponse.array(items);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'hgetall' command");
        }
        try {
            Map<ByteSequence, ByteSequence> map = storage.hgetallBytes(args.get(0));
            List<RedisResponse> items = new ArrayList<>(map.size() * 2);
            for (Map.Entry<ByteSequence, ByteSequence> entry : map.entrySet()) {
                items.add(RedisResponse.bulkString(entry.getKey()));
                items.add(RedisResponse.bulkString(entry.getValue()));
            }
            return RedisResponse.array(items);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}