package com.miniredis.commands.system;

import com.miniredis.aof.AofHealth;
import com.miniredis.aof.AofManager;
import com.miniredis.commands.Command;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.ServerMetrics;
import com.miniredis.storage.StorageEngine;

import java.util.List;
import java.util.Locale;

/**
 * INFO [section]
 * Returns live server telemetry and state formatted as standard Redis INFO sections.
 * Supports optional section filtering: server, clients, memory, persistence, stats, keyspace, all, default.
 */
public class InfoCommand implements Command {

    @Override
    public String getName() {
        return "INFO";
    }

    @Override
    public RedisResponse execute(List<String> args, StorageEngine storage) {
        if (args.size() > 1) {
            return RedisResponse.error("ERR wrong number of arguments for 'info' command");
        }

        String section = args.isEmpty() ? "all" : args.get(0).toLowerCase(Locale.ROOT);
        boolean all = "all".equals(section) || "default".equals(section);
        boolean incServer = all || "server".equals(section);
        boolean incClients = all || "clients".equals(section);
        boolean incMemory = all || "memory".equals(section);
        boolean incPersistence = all || "persistence".equals(section);
        boolean incStats = all || "stats".equals(section);
        boolean incKeyspace = all || "keyspace".equals(section);

        ServerMetrics metrics = storage.getServerMetrics();
        StorageEngine.KeyspaceStats ks = (incMemory || incKeyspace) ? storage.computeKeyspaceStats() : null;

        StringBuilder sb = new StringBuilder();

        if (incServer) {
            sb.append("# Server\r\n");
            sb.append("redis_version:1.0.0\r\n");
            sb.append("redis_mode:standalone\r\n");
            sb.append("os:").append(System.getProperty("os.name")).append("\r\n");
            sb.append("java_version:").append(System.getProperty("java.version")).append("\r\n");
            sb.append("tcp_port:").append(metrics.getListeningPort()).append("\r\n");
            sb.append("uptime_in_seconds:").append(metrics.getUptimeSeconds()).append("\r\n");
            sb.append("uptime_in_days:").append(metrics.getUptimeDays()).append("\r\n\r\n");
        }

        if (incClients) {
            sb.append("# Clients\r\n");
            sb.append("connected_clients:").append(metrics.getConnectedClientCount()).append("\r\n\r\n");
        }

        if (incMemory && ks != null) {
            Runtime rt = Runtime.getRuntime();
            long jvmUsed = rt.totalMemory() - rt.freeMemory();
            sb.append("# Memory\r\n");
            sb.append("used_memory:").append(ks.estimatedMemoryBytes).append("\r\n");
            sb.append("used_memory_human:").append(formatBytesHuman(ks.estimatedMemoryBytes)).append("\r\n");
            sb.append("jvm_used_memory:").append(jvmUsed).append("\r\n");
            sb.append("jvm_max_memory:").append(rt.maxMemory()).append("\r\n\r\n");
        }

        if (incPersistence) {
            sb.append("# Persistence\r\n");
            AofManager aof = storage.getAofManager();
            if (aof != null) {
                AofHealth health = aof.getHealth();
                sb.append("aof_enabled:1\r\n");
                sb.append("aof_rewrite_in_progress:").append(health.isRewriting() ? "1" : "0").append("\r\n");
                sb.append("aof_current_size:").append(health.getFileSizeBytes()).append("\r\n");
                sb.append("aof_fsync_policy:").append(health.getFsyncPolicy().name().toLowerCase(Locale.ROOT)).append("\r\n");
                sb.append("aof_total_logged_commands:").append(health.getTotalLoggedCommands()).append("\r\n");
                sb.append("aof_total_fsyncs:").append(health.getTotalFsyncs()).append("\r\n");
                sb.append("aof_health:").append(health.getStatus().name()).append("\r\n");
            } else {
                sb.append("aof_enabled:0\r\n");
            }
            sb.append("\r\n");
        }

        if (incStats) {
            sb.append("# Stats\r\n");
            sb.append("total_connections_received:").append(metrics.getTotalConnectionsReceived()).append("\r\n");
            sb.append("total_commands_processed:").append(storage.getTotalCompletedOperations()).append("\r\n");
            sb.append("total_failed_commands:").append(storage.getFailedOperations()).append("\r\n");
            sb.append("keyspace_hits:").append(metrics.getKeyspaceHits()).append("\r\n");
            sb.append("keyspace_misses:").append(metrics.getKeyspaceMisses()).append("\r\n\r\n");
        }

        if (incKeyspace && ks != null) {
            sb.append("# Keyspace\r\n");
            sb.append("db0:keys=").append(ks.activeKeys)
              .append(",expires=").append(ks.expiringKeys)
              .append(",avg_ttl=").append(ks.avgTtlMs).append("\r\n");
        }

        return RedisResponse.bulkString(sb.toString());
    }

    private static String formatBytesHuman(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.2fK", bytes / 1024.0);
        } else if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.2fM", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.2fG", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
