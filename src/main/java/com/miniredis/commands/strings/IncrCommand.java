package com.miniredis.commands.strings;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class IncrCommand implements Command {
    @Override
    public String getName() {
        return "INCR";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'incr' command");
        }
        try {
            long result = storage.incr(args.get(0));
            return RedisResponse.integer(result);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        } catch (IllegalArgumentException | ArithmeticException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}