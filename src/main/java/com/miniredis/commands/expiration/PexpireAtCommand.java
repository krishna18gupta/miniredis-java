package com.miniredis.commands.expiration;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.List;

public class PexpireAtCommand implements Command {
    @Override
    public String getName() {
        return "PEXPIREAT";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'pexpireat' command");
        }
        long epochMillis;
        try {
            epochMillis = Long.parseLong(args.get(1));
        } catch (NumberFormatException e) {
            return RedisResponse.error("ERR value is not an integer or out of range");
        }
        int result = storage.pexpireAt(args.get(0), epochMillis);
        return RedisResponse.integer(result);
    }
}
