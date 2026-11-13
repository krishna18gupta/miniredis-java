package com.miniredis.benchmark;

import com.miniredis.aof.AofManager;
import com.miniredis.aof.FsyncPolicy;
import com.miniredis.commands.CommandRegistry;
import com.miniredis.storage.StorageEngine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Benchmark runner comparing throughput, latencies, recovery, and compaction
 * across FsyncPolicy.ALWAYS, FsyncPolicy.EVERYSEC, and FsyncPolicy.NO.
 */
public class MiniRedisAofBenchmark {

    public static final int BENCHMARK_ITERATIONS = 3;

    public static void main(String[] args) throws Exception {
        System.out.println("=========================================================================================");
        System.out.println("                 MiniRedis Phase 6 & 7 AOF Multi-Iteration Benchmark                    ");
        System.out.println("=========================================================================================");

        Path benchDir = Files.createTempDirectory("miniredis-bench-");

        try {
            System.out.println("Running " + BENCHMARK_ITERATIONS + " iterations per policy...");

            // 1. Benchmark FsyncPolicy.NO (10,000 ops)
            AggregateResult aggNo = runMultiIterationBenchmark(benchDir, FsyncPolicy.NO, 10_000, BENCHMARK_ITERATIONS);

            // 2. Benchmark FsyncPolicy.EVERYSEC (10,000 ops)
            AggregateResult aggEverysec = runMultiIterationBenchmark(benchDir, FsyncPolicy.EVERYSEC, 10_000, BENCHMARK_ITERATIONS);

            // 3. Benchmark FsyncPolicy.ALWAYS (1,000 ops due to synchronous disk flushes)
            AggregateResult aggAlways = runMultiIterationBenchmark(benchDir, FsyncPolicy.ALWAYS, 1_000, BENCHMARK_ITERATIONS);

            // 4. Compaction & Recovery Multi-Iteration Benchmark
            AggregateCompaction aggCompact = runMultiIterationCompaction(benchDir, BENCHMARK_ITERATIONS);

            // Print Summary Table
            System.out.println("\n------------------------------------------------------------------------------------------------------------------");
            System.out.println("                             MULTI-ITERATION BENCHMARK RESULTS (MEDIAN METRICS)                                   ");
            System.out.println("------------------------------------------------------------------------------------------------------------------");
            System.out.printf("%-10s | %-6s | %-14s | %-12s | %-12s | %-12s | %-14s%n",
                    "Policy", "Runs", "Median Tput", "Avg Latency", "p95 Latency", "p99 Latency", "Avg Fsync Dur");
            System.out.println("-----------+--------+----------------+--------------+--------------+--------------+---------------");
            printRow(aggNo);
            printRow(aggEverysec);
            printRow(aggAlways);
            System.out.println("------------------------------------------------------------------------------------------------------------------");

            System.out.println("\n[AOF Compaction & Recovery Multi-Iteration Metrics]");
            System.out.printf("  Bloated File Size (5,000 updates):   %d KB%n", aggCompact.bloatedSizeBytes / 1024);
            System.out.printf("  Compacted File Size:                 %d KB (%.1f%% reduction)%n",
                    aggCompact.compactedSizeBytes / 1024, aggCompact.reductionPct);
            System.out.printf("  Median Rewrite Duration:             %d ms%n", aggCompact.medianRewriteMs);
            System.out.printf("  Median Recovery Duration:            %d ms (%d cmds/sec)%n",
                    aggCompact.medianRecoveryMs, aggCompact.medianRecoveryThroughput);
            System.out.println("=========================================================================================\n");

        } finally {
            // Clean up benchmark temp files
            deleteDir(benchDir.toFile());
        }
    }

    private static void printRow(AggregateResult r) {
        System.out.printf("%-10s | %-6d | %-11d op/s | %-9.3f ms | %-9.3f ms | %-9.3f ms | %-9.3f ms%n",
                r.policy.name(), r.runs, r.medianThroughput, r.avgLatencyMs, r.medianP95Ms, r.medianP99Ms, r.avgFsyncDurationMs);
    }

    private static AggregateResult runMultiIterationBenchmark(Path dir, FsyncPolicy policy, int totalOps, int iterations) throws IOException {
        List<Long> throughputs = new ArrayList<>();
        List<Double> avgLatencies = new ArrayList<>();
        List<Double> p95Latencies = new ArrayList<>();
        List<Double> p99Latencies = new ArrayList<>();
        List<Long> fsyncDurations = new ArrayList<>();

        for (int it = 0; it < iterations; it++) {
            File aofFile = dir.resolve("bench-" + policy.name().toLowerCase() + "-" + it + ".aof").toFile();
            StorageEngine storage = new StorageEngine();
            CommandRegistry registry = new CommandRegistry();
            AofManager aof = new AofManager(aofFile, policy, storage, registry);
            storage.setAofManager(aof);
            aof.start();

            List<Long> latenciesNanos = new ArrayList<>(totalOps);
            long startTotal = System.nanoTime();

            for (int i = 0; i < totalOps; i++) {
                long t0 = System.nanoTime();
                registry.dispatch(List.of("SET", "k:" + i, "val_" + i), storage);
                latenciesNanos.add(System.nanoTime() - t0);
            }

            long elapsedNanos = System.nanoTime() - startTotal;
            long fsyncDur = aof.getHealth().getLastFsyncDurationMs();
            aof.close();

            Collections.sort(latenciesNanos);
            long sum = 0;
            for (long l : latenciesNanos) sum += l;
            double avgMs = (sum / (double) totalOps) / 1_000_000.0;
            int p95Idx = (int) (totalOps * 0.95);
            int p99Idx = (int) (totalOps * 0.99);
            double p95Ms = latenciesNanos.get(Math.min(p95Idx, totalOps - 1)) / 1_000_000.0;
            double p99Ms = latenciesNanos.get(Math.min(p99Idx, totalOps - 1)) / 1_000_000.0;
            long throughput = (long) (totalOps / (elapsedNanos / 1_000_000_000.0));

            throughputs.add(throughput);
            avgLatencies.add(avgMs);
            p95Latencies.add(p95Ms);
            p99Latencies.add(p99Ms);
            fsyncDurations.add(fsyncDur);

            // Clean up iteration file
            aofFile.delete();
        }

        Collections.sort(throughputs);
        Collections.sort(p95Latencies);
        Collections.sort(p99Latencies);

        long medianTput = throughputs.get(throughputs.size() / 2);
        double medianP95 = p95Latencies.get(p95Latencies.size() / 2);
        double medianP99 = p99Latencies.get(p99Latencies.size() / 2);

        double totalAvg = 0;
        for (double d : avgLatencies) totalAvg += d;
        double overallAvg = totalAvg / iterations;

        double totalFsync = 0;
        for (long f : fsyncDurations) totalFsync += f;
        double overallFsync = totalFsync / iterations;

        return new AggregateResult(policy, iterations, medianTput, overallAvg, medianP95, medianP99, overallFsync);
    }

    private static AggregateCompaction runMultiIterationCompaction(Path dir, int iterations) throws IOException {
        List<Long> rewriteDurations = new ArrayList<>();
        List<Long> recoveryDurations = new ArrayList<>();
        List<Long> recoveryThroughputs = new ArrayList<>();
        long bloatedSize = 0;
        long compactedSize = 0;

        for (int it = 0; it < iterations; it++) {
            File aofFile = dir.resolve("bench-compact-" + it + ".aof").toFile();
            StorageEngine storage = new StorageEngine();
            CommandRegistry registry = new CommandRegistry();
            AofManager aof = new AofManager(aofFile, FsyncPolicy.NO, storage, registry);
            storage.setAofManager(aof);
            aof.start();

            // Bloat with 5,000 updates to single key and ephemeral keys
            for (int i = 0; i < 5_000; i++) {
                registry.dispatch(List.of("INCR", "counter"), storage);
                if (i % 2 == 0) {
                    registry.dispatch(List.of("SET", "temp_" + i, "temp_data"), storage);
                    registry.dispatch(List.of("DEL", "temp_" + i), storage);
                }
            }

            bloatedSize = aofFile.length();
            long rewriteStart = System.currentTimeMillis();
            aof.rewrite();
            long rewriteDuration = System.currentTimeMillis() - rewriteStart;
            compactedSize = aofFile.length();

            aof.close();

            // Recovery benchmark
            StorageEngine recoveryEngine = new StorageEngine();
            CommandRegistry recoveryRegistry = new CommandRegistry();
            AofManager recoveryAof = new AofManager(aofFile, FsyncPolicy.NO, recoveryEngine, recoveryRegistry);

            long recStart = System.currentTimeMillis();
            int replayed = recoveryAof.recover();
            long recDuration = Math.max(1, System.currentTimeMillis() - recStart);
            long recThroughput = (long) (replayed / (recDuration / 1000.0));
            recoveryAof.close();

            rewriteDurations.add(rewriteDuration);
            recoveryDurations.add(recDuration);
            recoveryThroughputs.add(recThroughput);

            aofFile.delete();
        }

        Collections.sort(rewriteDurations);
        Collections.sort(recoveryDurations);
        Collections.sort(recoveryThroughputs);

        long medianRewrite = rewriteDurations.get(rewriteDurations.size() / 2);
        long medianRec = recoveryDurations.get(recoveryDurations.size() / 2);
        long medianRecTput = recoveryThroughputs.get(recoveryThroughputs.size() / 2);
        double reduction = (1.0 - ((double) compactedSize / bloatedSize)) * 100.0;

        return new AggregateCompaction(bloatedSize, compactedSize, reduction, medianRewrite, medianRec, medianRecTput);
    }

    private static void deleteDir(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteDir(c);
        }
        f.delete();
    }

    private static class AggregateResult {
        final FsyncPolicy policy;
        final int runs;
        final long medianThroughput;
        final double avgLatencyMs;
        final double medianP95Ms;
        final double medianP99Ms;
        final double avgFsyncDurationMs;

        AggregateResult(FsyncPolicy policy, int runs, long medianThroughput, double avgLatencyMs,
                        double medianP95Ms, double medianP99Ms, double avgFsyncDurationMs) {
            this.policy = policy;
            this.runs = runs;
            this.medianThroughput = medianThroughput;
            this.avgLatencyMs = avgLatencyMs;
            this.medianP95Ms = medianP95Ms;
            this.medianP99Ms = medianP99Ms;
            this.avgFsyncDurationMs = avgFsyncDurationMs;
        }
    }

    private static class AggregateCompaction {
        final long bloatedSizeBytes;
        final long compactedSizeBytes;
        final double reductionPct;
        final long medianRewriteMs;
        final long medianRecoveryMs;
        final long medianRecoveryThroughput;

        AggregateCompaction(long bloated, long compacted, double red, long rewriteDur, long recDur, long recThroughput) {
            this.bloatedSizeBytes = bloated;
            this.compactedSizeBytes = compacted;
            this.reductionPct = red;
            this.medianRewriteMs = rewriteDur;
            this.medianRecoveryMs = recDur;
            this.medianRecoveryThroughput = recThroughput;
        }
    }
}
