package com.miniredis.commands.system;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.List;

/**
 * PING [message]
 * Returns PONG if no argument is provided, otherwise returns a copy of the argument as a simple string.
 */
public class PingCommand implements Command {

    @Override
    public String getName() {
        return "PING";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.isEmpty()) {
            return RedisResponse.simpleString("PONG");
        } else if (args.size() == 1) {
            return RedisResponse.simpleString(args.get(0));
        } else {
            return RedisResponse.error("ERR wrong number of arguments for 'ping' command");
        }
    }
}
