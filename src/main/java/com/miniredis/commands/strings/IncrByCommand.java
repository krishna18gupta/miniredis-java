package com.miniredis.commands.strings;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

/**
 * INCRBY key increment
 * Increments the number stored at key by increment.
 */
public class IncrByCommand implements Command {

    @Override
    public String getName() {
        return "INCRBY";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'incrby' command");
        }
        long delta;
        try {
            delta = Long.parseLong(args.get(1));
        } catch (NumberFormatException e) {
            return RedisResponse.error("ERR value is not an integer or out of range");
        }
        try {
            long result = storage.incrBy(args.get(0), delta);
            return RedisResponse.integer(result);
        } catch (WrongTypeException | IllegalArgumentException | ArithmeticException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}
