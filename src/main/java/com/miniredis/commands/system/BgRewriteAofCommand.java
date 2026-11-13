package com.miniredis.commands.system;

import com.miniredis.aof.AofManager;
import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.List;

/**
 * BGREWRITEAOF
 * Triggers background compaction of the Append-Only File.
 */
public class BgRewriteAofCommand implements Command {

    @Override
    public String getName() {
        return "BGREWRITEAOF";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        AofManager aof = storage.getAofManager();
        if (aof == null) {
            return RedisResponse.error("ERR AOF persistence is not enabled");
        }
        aof.bgRewrite();
        return RedisResponse.simpleString("Background append only file rewriting started");
    }
}
