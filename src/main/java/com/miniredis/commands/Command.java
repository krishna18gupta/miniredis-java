package com.miniredis.commands;

import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.StorageEngine;
import java.util.ArrayList;
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
     * Executes the command with the provided string arguments against the storage engine.
     * @param args Command arguments (excluding the command name itself).
     * @param storage The database storage engine.
     * @return RedisResponse representing the result.
     */
    RedisResponse execute(List<String> args, StorageEngine storage);

    /**
     * Executes the command with the provided binary ByteSequence arguments against the storage engine.
     * Default implementation adapts to String arguments for commands that have not yet overridden it.
     * @param args Command arguments (excluding the command name itself).
     * @param storage The database storage engine.
     * @return RedisResponse representing the result.
     */
    default RedisResponse executeBytes(List<ByteSequence> args, StorageEngine storage) {
        List<String> stringArgs = new ArrayList<>(args.size());
        for (ByteSequence b : args) {
            stringArgs.add(b.toUtf8String());
        }
        return execute(stringArgs, storage);
    }
}
