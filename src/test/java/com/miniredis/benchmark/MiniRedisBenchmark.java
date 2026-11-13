package com.miniredis.benchmark;

import com.miniredis.concurrency.StripedLockManager;
import com.miniredis.storage.StorageEngine;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark harness comparing Global-Lock (1 stripe) vs Striped-Lock (256 stripes)
 * across 1, 10, 25, 50, and 100 threads.
 *
 * Workload: 50,000 mixed operations (50% GET, 30% SET, 10% INCR, 10% LPUSH/LPOP)
 * across a working set of 1,000 keys.
 */
public class MiniRedisBenchmark {

    private static final int TOTAL_OPERATIONS = 50_000;
    private static final int KEY_SPACE_SIZE = 1_000;
    private static final int WARMUP_OPERATIONS = 5_000;
    private static final int[] THREAD_COUNTS = {1, 10, 25, 50, 100};

    public static class BenchmarkResult {
        public final String configName;
        public final int threads;
        public final int totalOps;
        public final double durationSeconds;
        public final double throughputOpsPerSec;
        public final double avgLatencyMicros;
        public final double p95LatencyMicros;
        public final double p99LatencyMicros;
        public final long failedOps;
        public final long contentionCount;

        public BenchmarkResult(String configName, int threads, int totalOps, double durationSeconds,
                               double throughputOpsPerSec, double avgLatencyMicros,
                               double p95LatencyMicros, double p99LatencyMicros,
                               long failedOps, long contentionCount) {
            this.configName = configName;
            this.threads = threads;
            this.totalOps = totalOps;
            this.durationSeconds = durationSeconds;
            this.throughputOpsPerSec = throughputOpsPerSec;
            this.avgLatencyMicros = avgLatencyMicros;
            this.p95LatencyMicros = p95LatencyMicros;
            this.p99LatencyMicros = p99LatencyMicros;
            this.failedOps = failedOps;
            this.contentionCount = contentionCount;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("                 MiniRedis Phase 5 Concurrency Benchmark Suite                  ");
        System.out.println(" Workload: 50,000 ops (50% GET, 30% SET, 10% INCR, 10% LPUSH/LPOP) | 1,000 keys ");
        System.out.println("================================================================================");

        List<BenchmarkResult> results = new ArrayList<>();

        for (int threads : THREAD_COUNTS) {
            System.out.printf("Running benchmark for %d threads...%n", threads);
            // Global Lock (1 stripe)
            results.add(runBenchmark("Global Lock (1 stripe)", 1, threads));
            // Striped Lock (256 stripes)
            results.add(runBenchmark("Striped Lock (256 stripes)", 256, threads));
        }

        printReport(results);
    }

    public static BenchmarkResult runBenchmark(String name, int stripes, int threads) throws Exception {
        StripedLockManager lockManager = new StripedLockManager(stripes);
        StorageEngine engine = new StorageEngine(Clock.systemUTC(), lockManager);

        // Pre-populate keyspace
        for (int i = 0; i < KEY_SPACE_SIZE; i++) {
            String key = "key:" + i;
            engine.set(key, "val:" + i);
            engine.incr("counter:" + i);
            engine.rpush("list:" + i, List.of("item1", "item2"));
        }

        // Warmup
        runWorkload(engine, 1, WARMUP_OPERATIONS, null);

        // Reset counters
        long initialContention = lockManager.getContentionCount();

        // Actual benchmark
        long[] latenciesNanos = new long[TOTAL_OPERATIONS];
        AtomicInteger opIndex = new AtomicInteger(0);
        AtomicInteger failedOps = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);

        long startTime = System.nanoTime();

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ThreadLocalRandom rand = ThreadLocalRandom.current();
                    while (true) {
                        int idx = opIndex.getAndIncrement();
                        if (idx >= TOTAL_OPERATIONS) {
                            break;
                        }
                        int keyId = rand.nextInt(KEY_SPACE_SIZE);
                        int opType = rand.nextInt(100);

                        long opStart = System.nanoTime();
                        try {
                            if (opType < 50) {
                                // 50% GET
                                engine.get("key:" + keyId);
                            } else if (opType < 80) {
                                // 30% SET
                                engine.set("key:" + keyId, "new_val:" + idx);
                            } else if (opType < 90) {
                                // 10% INCR
                                engine.incr("counter:" + keyId);
                            } else if (opType < 95) {
                                // 5% LPUSH
                                engine.lpush("list:" + keyId, List.of("item:" + idx));
                            } else {
                                // 5% LPOP
                                engine.lpop("list:" + keyId);
                            }
                        } catch (Exception e) {
                            failedOps.incrementAndGet();
                        } finally {
                            latenciesNanos[idx] = System.nanoTime() - opStart;
                        }
                    }
                } catch (Exception e) {
                    failedOps.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await(60, TimeUnit.SECONDS);
        long durationNanos = System.nanoTime() - startTime;
        executor.shutdown();

        double durationSeconds = durationNanos / 1_000_000_000.0;
        double throughput = TOTAL_OPERATIONS / durationSeconds;
        long contention = lockManager.getContentionCount() - initialContention;

        // Latency percentiles
        Arrays.sort(latenciesNanos);
        long sumLatencies = 0;
        for (long l : latenciesNanos) sumLatencies += l;
        double avgLatencyMicros = (sumLatencies / (double) TOTAL_OPERATIONS) / 1000.0;
        double p95LatencyMicros = latenciesNanos[(int) (TOTAL_OPERATIONS * 0.95)] / 1000.0;
        double p99LatencyMicros = latenciesNanos[(int) (TOTAL_OPERATIONS * 0.99)] / 1000.0;

        return new BenchmarkResult(name, threads, TOTAL_OPERATIONS, durationSeconds,
                throughput, avgLatencyMicros, p95LatencyMicros, p99LatencyMicros,
                failedOps.get(), contention);
    }

    private static void runWorkload(StorageEngine engine, int threads, int ops, long[] latencies) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger counter = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            ex.submit(() -> {
                ThreadLocalRandom rand = ThreadLocalRandom.current();
                while (counter.getAndIncrement() < ops) {
                    int keyId = rand.nextInt(KEY_SPACE_SIZE);
                    int opType = rand.nextInt(100);
                    if (opType < 50) engine.get("key:" + keyId);
                    else if (opType < 80) engine.set("key:" + keyId, "warmup");
                    else if (opType < 90) engine.incr("counter:" + keyId);
                    else if (opType < 95) engine.lpush("list:" + keyId, List.of("val"));
                    else engine.lpop("list:" + keyId);
                }
                latch.countDown();
            });
        }
        latch.await();
        ex.shutdown();
    }

    private static void printReport(List<BenchmarkResult> results) {
        System.out.println("\n");
        System.out.println("========================================================================================================================");
        System.out.println("                                      PHASE 5 BENCHMARK COMPARISON REPORT                                                ");
        System.out.println("========================================================================================================================");
        System.out.printf("%-26s | %7s | %13s | %13s | %12s | %12s | %11s | %10s%n",
                "Lock Strategy", "Threads", "Throughput", "Avg Latency", "p95 Latency", "p99 Latency", "Contention", "Failed Ops");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");

        for (BenchmarkResult r : results) {
            System.out.printf("%-26s | %7d | %10.1f op/s | %10.2f us | %9.2f us | %9.2f us | %11d | %10d%n",
                    r.configName,
                    r.threads,
                    r.throughputOpsPerSec,
                    r.avgLatencyMicros,
                    r.p95LatencyMicros,
                    r.p99LatencyMicros,
                    r.contentionCount,
                    r.failedOps);
        }
        System.out.println("========================================================================================================================");
    }
}
