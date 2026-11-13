package com.miniredis.commands.system;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.List;

/**
 * SELECT <index>
 * Selects database index. Supported by standard Redis drivers (defaulting to 0).
 */
public class SelectCommand implements Command {

    @Override
    public String getName() {
        return "SELECT";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.isEmpty()) {
            return RedisResponse.error("ERR wrong number of arguments for 'select' command");
        }
        try {
            int db = Integer.parseInt(args.get(0));
            if (db < 0) {
                return RedisResponse.error("ERR DB index is out of range");
            }
            // MiniRedis operates in single-database mode (database 0); accepts index >= 0
            return RedisResponse.ok();
        } catch (NumberFormatException e) {
            return RedisResponse.error("ERR value is not an integer or out of range");
        }
    }
}
