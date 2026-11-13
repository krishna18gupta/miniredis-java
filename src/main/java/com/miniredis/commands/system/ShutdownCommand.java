package com.miniredis.commands.system;

import com.miniredis.aof.AofException;
import com.miniredis.aof.AofHealth;
import com.miniredis.aof.AofManager;
import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ServerMetrics;
import com.miniredis.storage.StorageEngine;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * SHUTDOWN [NOSAVE | SAVE]
 * Safely persists state (unless NOSAVE) and gracefully shuts down active server components.
 */
public class ShutdownCommand implements Command {

    @Override
    public String getName() {
        return "SHUTDOWN";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        boolean saveSnapshot = false;
        boolean skipPersistence = false;

        if (args.size() > 1) {
            return RedisResponse.error("ERR syntax error");
        }
        if (args.size() == 1) {
            String modifier = args.get(0).toUpperCase(Locale.ROOT);
            if ("SAVE".equals(modifier)) {
                saveSnapshot = true;
            } else if ("NOSAVE".equals(modifier)) {
                skipPersistence = true;
            } else {
                return RedisResponse.error("ERR syntax error");
            }
        }

        AofManager aof = storage.getAofManager();
        if (aof != null && !skipPersistence) {
            try {
                if (saveSnapshot) {
                    aof.rewrite();
                }
                aof.sync();
                if (saveSnapshot && aof.getHealth().getStatus() == AofHealth.Status.FAILING) {
                    return RedisResponse.error("ERR Errors trying to SHUTDOWN (SAVE failed): " + aof.getHealth().getLastError());
                }
            } catch (IOException | AofException e) {
                return RedisResponse.error("ERR Errors trying to SHUTDOWN (SAVE failed): " + e.getMessage());
            }
        }

        ServerMetrics.ShutdownCoordinator coordinator = storage.getServerMetrics().getShutdownCoordinator();
        if (coordinator != null) {
            coordinator.initiateShutdown(saveSnapshot, skipPersistence);
        } else if (aof != null) {
            aof.close();
        }

        return RedisResponse.ok();
    }
}
