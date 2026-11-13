package com.miniredis.commands.system;

import com.miniredis.aof.AofException;
import com.miniredis.aof.AofHealth;
import com.miniredis.aof.AofManager;
import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.io.IOException;
import java.util.List;

/**
 * SAVE
 * Performs a synchronous snapshot rewrite and fsync of database state to disk.
 */
public class SaveCommand implements Command {

    @Override
    public String getName() {
        return "SAVE";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (!args.isEmpty()) {
            return RedisResponse.error("ERR wrong number of arguments for 'save' command");
        }
        AofManager aof = storage.getAofManager();
        if (aof == null) {
            return RedisResponse.error("ERR AOF persistence is not enabled");
        }
        try {
            aof.rewrite();
            aof.sync();
            if (aof.getHealth().getStatus() == AofHealth.Status.FAILING) {
                return RedisResponse.error("ERR synchronous save failed: " + aof.getHealth().getLastError());
            }
            return RedisResponse.ok();
        } catch (IOException | AofException e) {
            return RedisResponse.error("ERR synchronous save failed: " + e.getMessage());
        }
    }
}
