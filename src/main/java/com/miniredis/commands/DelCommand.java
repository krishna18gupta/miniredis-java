package com.miniredis.commands;

import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import java.util.List;

public class DelCommand implements Command {
    @Override
    public String getName() {
        return "DEL";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.isEmpty()) {
            return RedisResponse.error("ERR wrong number of arguments for 'del' command");
        }
        int deleted = storage.del(args);
        return RedisResponse.integer(deleted);
    }

    @Override
    public RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        if (args.isEmpty()) {
            return RedisResponse.error("ERR wrong number of arguments for 'del' command");
        }
        int deleted = storage.delBytes(args);
        return RedisResponse.integer(deleted);
    }
}
