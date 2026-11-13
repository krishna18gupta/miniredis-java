package com.miniredis.commands.system;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * RENAME key newkey
 * Renames key to newkey, overwriting newkey if it already exists and preserving TTL.
 */
public class RenameCommand implements Command {

    @Override
    public String getName() {
        return "RENAME";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RedisResponse.error("ERR wrong number of arguments for 'rename' command");
        }
        try {
            storage.rename(args.get(0), args.get(1));
            return RedisResponse.ok();
        } catch (NoSuchElementException e) {
            return RedisResponse.error(e.getMessage());
        }
    }
}
