package com.miniredis.commands;

import com.miniredis.storage.StorageEngine;
import java.util.List;

public class DbSizeCommand implements Command {
    @Override
    public String getName() {
        return "DBSIZE";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        return RedisResponse.integer(storage.dbSize());
    }
}
