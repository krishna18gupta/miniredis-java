package com.miniredis.commands;

import com.miniredis.storage.StorageEngine;
import java.util.List;

public class FlushDbCommand implements Command {
    @Override
    public String getName() {
        return "FLUSHDB";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        storage.flushDb();
        return RedisResponse.ok();
    }
}
