package com.miniredis.commands.system;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.List;

/**
 * ECHO message
 * Returns the given message as a bulk string.
 */
public class EchoCommand implements Command {

    @Override
    public String getName() {
        return "ECHO";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'echo' command");
        }
        return RedisResponse.bulkString(args.get(0));
    }
}
