package com.miniredis.commands.expiration;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.List;

public class SetExCommand implements Command {
    @Override
    public String getName() {
        return "SETEX";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 3) {
            return RedisResponse.error("ERR wrong number of arguments for 'setex' command");
        }
        long seconds;
        try {
            seconds = Long.parseLong(args.get(1));
        } catch (NumberFormatException e) {
            return RedisResponse.error("ERR value is not an integer or out of range");
        }
        try {
            storage.setex(args.get(0), seconds, args.get(2));
            return RedisResponse.ok();
        } catch (IllegalArgumentException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}