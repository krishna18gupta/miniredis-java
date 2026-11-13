package com.miniredis.commands.expiration;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.List;

public class PExpireCommand implements Command {
    @Override
    public String getName() {
        return "PEXPIRE";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'pexpire' command");
        }
        long millis;
        try {
            millis = Long.parseLong(args.get(1));
        } catch (NumberFormatException e) {
            return RedisResponse.error("ERR value is not an integer or out of range");
        }
        int result = storage.pexpire(args.get(0), millis);
        return RedisResponse.integer(result);
    }
}