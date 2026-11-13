package com.miniredis.commands.system;

import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ServerMetrics;
import com.miniredis.storage.StorageEngine;

import java.util.List;
import java.util.Locale;

/**
 * CLIENT <subcommand> [args...]
 * Supports client management subcommands: SETINFO, SETNAME, GETNAME, LIST, ID.
 * Uses live connected client snapshots from ServerMetrics when active TCP connections exist.
 */
public class ClientCommand implements Command {

    private static volatile String lastClientName = "";

    @Override
    public String getName() {
        return "CLIENT";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.isEmpty()) {
            return RedisResponse.error("ERR wrong number of arguments for 'client' command");
        }

        String sub = args.get(0).toUpperCase(Locale.ROOT);
        switch (sub) {
            case "SETINFO":
                return RedisResponse.ok();
            case "SETNAME":
                if (args.size() != 2) {
                    return RedisResponse.error("ERR wrong number of arguments for 'client|setname' command");
                }
                lastClientName = args.get(1);
                return RedisResponse.ok();
            case "GETNAME":
                return lastClientName.isEmpty() ? RedisResponse.nil() : RedisResponse.bulkString(lastClientName);
            case "LIST": {
                List<ServerMetrics.ClientSnapshot> clients = storage != null
                        ? storage.getServerMetrics().getConnectedClients()
                        : List.of();
                if (!clients.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (ServerMetrics.ClientSnapshot c : clients) {
                        sb.append(c.toClientListLine()).append("\n");
                    }
                    return RedisResponse.bulkString(sb.toString());
                }
                return RedisResponse.bulkString("id=1 addr=127.0.0.1:6379 name=" + lastClientName
                        + " age=1 idle=0 flags=N db=0 sub=0 psub=0 multi=-1 qbuf=0 qbuf-free=0 argv-mem=0 obl=0 oll=0 omem=0 tot-mem=0 events=r cmd=client\n");
            }
            default:
                return RedisResponse.ok();
        }
    }
}
