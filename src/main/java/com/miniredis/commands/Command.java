package com.miniredis.commands;

import com.miniredis.storage.StorageEngine;
import java.util.List;

/**
 * Interface representing an executable Redis command.
 */
public interface Command {
    /**
     * @return Uppercase command name (e.g. "SET", "GET").
     */
    String getName();

    /**
     * Executes the command with the provided arguments against the storage engine.
     * @param args Command arguments (excluding the command name itself).
     * @param storage The database storage engine.
     * @return RedisResponse representing the result.
     */
    RedisResponse execute(List<String> args, StorageEngine storage);
}
