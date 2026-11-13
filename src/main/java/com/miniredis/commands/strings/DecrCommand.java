package com.miniredis.commands.strings;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import com.miniredis.storage.WrongTypeException;

import java.util.List;

public class DecrCommand implements Command {
    @Override
    public String getName() {
        return "DECR";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'decr' command");
        }
        try {
            long result = storage.decr(args.get(0));
            return RedisResponse.integer(result);
        } catch (WrongTypeException e) {
            return RedisResponse.error(e.getMessage());
        } catch (IllegalArgumentException | ArithmeticException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}