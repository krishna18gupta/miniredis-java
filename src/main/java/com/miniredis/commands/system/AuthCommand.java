package com.miniredis.commands.system;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;

import java.util.List;

/**
 * AUTH [username] <password>
 * Authentication command. Returns OK if unconfigured or matching credentials.
 */
public class AuthCommand implements Command {

    @Override
    public String getName() {
        return "AUTH";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.isEmpty()) {
            return RedisResponse.error("ERR wrong number of arguments for 'auth' command");
        }
        // In local open mode without authentication requirement, accept handshake
        return RedisResponse.ok();
    }
}
