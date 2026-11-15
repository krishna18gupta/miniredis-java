package com.miniredis.benchmark;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.storage.StorageEngine;
import com.miniredis.web.DemoSecurityContext;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 16 Local Bounded Reliability & Performance Benchmark.
 * Evaluates memory growth, thread counts, command latencies (Avg, P50, P95, P99),
 * error rates, and AOF behavior under concurrent simulated visitors with session isolation.
 */
public class Phase16ReliabilityBenchmark {

    private static final int CONCURRENT_VISITORS = 10;
    private static final int OPS_PER_VISITOR = 100;

    public static void main(String[] args) throws Exception {
        System.out.println("===============================================================================");
        System.out.println("   MiniRedis Phase 16 Bounded Reliability & Performance Benchmark (Local)   ");
        System.out.println("===============================================================================");

        File aofFile = new File("target/benchmark_phase16.aof");
        if (aofFile.exists()) aofFile.delete();

        StorageEngine storage = new StorageEngine();
        CommandRegistry registry = new CommandRegistry();
        com.miniredis.aof.AofManager aofManager = new com.miniredis.aof.AofManager(
                aofFile, com.miniredis.aof.FsyncPolicy.EVERYSEC, storage, registry);
        storage.setAofManager(aofManager);
        aofManager.start();

        DemoSecurityContext demoContext = new DemoSecurityContext(storage, registry);

        // 1. Initial State Measurement
        System.gc();
        Thread.sleep(100);
        Runtime rt = Runtime.getRuntime();
        long initialHeapUsed = rt.totalMemory() - rt.freeMemory();
        int initialThreads = Thread.activeCount();

        System.out.printf("Initial JVM Heap Used: %.2f MB | Max Heap: %.2f MB%n",
                initialHeapUsed / (1024.0 * 1024.0), rt.maxMemory() / (1024.0 * 1024.0));
        System.out.printf("Initial Thread Count: %d%n", initialThreads);
        System.out.printf("Starting workload: %d concurrent visitors x %d operations = %d total requests...%n%n",
                CONCURRENT_VISITORS, OPS_PER_VISITOR, CONCURRENT_VISITORS * OPS_PER_VISITOR);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_VISITORS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_VISITORS);

        List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());
        AtomicLong errorCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);

        long startWallTime = System.currentTimeMillis();

        for (int v = 0; v < CONCURRENT_VISITORS; v++) {
            final int visitorId = v;
            final String sessionId = UUID.randomUUID().toString();

            pool.submit(() -> {
                try {
                    startLatch.await();
                    String keyPrefix = "usr_" + visitorId + "_";

                    for (int i = 0; i < OPS_PER_VISITOR; i++) {
                        String cmd;
                        int mod = i % 8;
                        switch (mod) {
                            case 0 -> cmd = "SET " + keyPrefix + "k1 Val" + i;
                            case 1 -> cmd = "GET " + keyPrefix + "k1";
                            case 2 -> cmd = "INCR " + keyPrefix + "counter";
                            case 3 -> cmd = "LPUSH " + keyPrefix + "queue job" + i;
                            case 4 -> cmd = "LRANGE " + keyPrefix + "queue 0 -1";
                            case 5 -> cmd = "HSET " + keyPrefix + "profile field" + i + " v" + i;
                            case 6 -> cmd = "HGETALL " + keyPrefix + "profile";
                            case 7 -> cmd = "DEL " + keyPrefix + "k1";
                            default -> cmd = "PING";
                        }

                        long t0 = System.nanoTime();
                        try {
                            RedisResponse resp = demoContext.executeDemoCommand(sessionId, cmd);
                            long t1 = System.nanoTime();
                            latenciesNanos.add(t1 - t0);

                            if (resp != null && resp.getType() != RedisResponse.ResponseType.ERROR) {
                                successCount.incrementAndGet();
                            } else {
                                errorCount.incrementAndGet();
                            }
                        } catch (Exception ex) {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        long endWallTime = System.currentTimeMillis();
        pool.shutdown();

        // 2. Post-Workload Measurements
        long durationMs = endWallTime - startWallTime;
        int totalRequests = latenciesNanos.size();
        double rps = durationMs > 0 ? (totalRequests * 1000.0) / durationMs : 0;

        List<Long> sortedLatencies = new ArrayList<>(latenciesNanos);
        Collections.sort(sortedLatencies);

        double avgMs = sortedLatencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double p50Ms = sortedLatencies.get((int) (sortedLatencies.size() * 0.50)) / 1_000_000.0;
        double p95Ms = sortedLatencies.get((int) (sortedLatencies.size() * 0.95)) / 1_000_000.0;
        double p99Ms = sortedLatencies.get((int) (sortedLatencies.size() * 0.99)) / 1_000_000.0;

        long postHeapUsed = rt.totalMemory() - rt.freeMemory();
        int postThreads = Thread.activeCount();

        System.out.println("-------------------------------------------------------------------------------");
        System.out.println("   BENCHMARK RESULTS & METRICS SUMMARY                                         ");
        System.out.println("-------------------------------------------------------------------------------");
        System.out.printf("Total Completed Requests: %d / %d%n", successCount.get(), totalRequests);
        System.out.printf("Failed Requests (Errors): %d (%.2f%%)%n", errorCount.get(),
                totalRequests > 0 ? (errorCount.get() * 100.0 / totalRequests) : 0);
        System.out.printf("Total Duration:           %d ms%n", durationMs);
        System.out.printf("Throughput:               %.2f requests/sec%n", rps);
        System.out.printf("Average Latency:          %.3f ms%n", avgMs);
        System.out.printf("P50 (Median) Latency:     %.3f ms%n", p50Ms);
        System.out.printf("P95 Latency:              %.3f ms%n", p95Ms);
        System.out.printf("P99 Latency:              %.3f ms%n", p99Ms);
        System.out.println("-------------------------------------------------------------------------------");
        System.out.printf("Post-Test Heap Used:      %.2f MB (Delta: %+.2f MB)%n",
                postHeapUsed / (1024.0 * 1024.0), (postHeapUsed - initialHeapUsed) / (1024.0 * 1024.0));
        System.out.printf("Peak Active Threads:      %d (Baseline: %d)%n", postThreads, initialThreads);
        System.out.printf("AOF Logged Commands:      %d commands%n", aofManager.getHealth().getTotalLoggedCommands());
        System.out.printf("AOF File Size on Disk:    %d bytes (%.2f KB)%n",
                aofFile.length(), aofFile.length() / 1024.0);
        System.out.println("===============================================================================");

        aofManager.close();
        if (aofFile.exists()) aofFile.delete();
    }
}
