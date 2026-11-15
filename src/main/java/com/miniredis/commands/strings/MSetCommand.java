package com.miniredis.commands.strings;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MSetCommand implements Command {
    @Override
    public String getName() {
        return "MSET";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.isEmpty() || args.size() % 2 != 0) {
            return RedisResponse.error("ERR wrong number of arguments for 'mset' command");
        }
        Map<String, String> pairs = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i += 2) {
            pairs.put(args.get(i), args.get(i + 1));
        }
        storage.mset(pairs);
        return RedisResponse.ok();
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.isEmpty() || args.size() % 2 != 0) {
            return RedisResponse.error("ERR wrong number of arguments for 'mset' command");
        }
        Map<ByteSequence, ByteSequence> pairs = new LinkedHashMap<>(args.size() / 2);
        for (int i = 0; i < args.size(); i += 2) {
            pairs.put(args.get(i), args.get(i + 1));
        }
        storage.msetBytes(pairs);
        return RedisResponse.ok();
    }
}