package com.miniredis.benchmark;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.protocol.RespParser;
import com.miniredis.protocol.RespSerializer;
import com.miniredis.server.RedisServer;
import com.miniredis.storage.StorageEngine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 9 Comprehensive Performance Benchmark Suite.
 * Evaluates real throughput and latency distributions (Avg, P95, P99) across:
 * 1) Dedicated SET, Dedicated GET, and Mixed Read/Write workloads under varying client counts.
 * 2) End-to-End Live TCP + RESP2 Socket connections across 1, 10, 25, and 50 concurrent clients.
 * 3) Optimistic Transactions (MULTI / INCRBY / EXEC) over live TCP.
 */
public class Phase9PerformanceBenchmark {

    private static final int KEY_SPACE = 1_000;
    private static final int ENGINE_OPS = 40_000;
    private static final int TCP_OPS = 12_000;
    private static final int[] CLIENT_COUNTS = {1, 10, 25, 50};

    private enum WorkloadType {
        SET_ONLY("SET only"),
        GET_ONLY("GET only"),
        MIXED_RW("Mixed 50G/30S/10I/10L"),
        TX_MULTI("MULTI/INCRBY/EXEC");

        final String label;

        WorkloadType(String label) {
            this.label = label;
        }
    }

    private static class BenchResult {
        final String mode;
        final String workload;
        final int clients;
        final int ops;
        final double throughput;
        final double avgUs;
        final double p95Us;
        final double p99Us;
        final long failed;

        BenchResult(String mode, String workload, int clients, int ops,
                    double throughput, double avgUs, double p95Us, double p99Us, long failed) {
            this.mode = mode;
            this.workload = workload;
            this.clients = clients;
            this.ops = ops;
            this.throughput = throughput;
            this.avgUs = avgUs;
            this.p95Us = p95Us;
            this.p99Us = p99Us;
            this.failed = failed;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================================================");
        System.out.println("                          MiniRedis Phase 9 Throughput & Latency Benchmark Suite                                ");
        System.out.printf(" OS: %s (%s) | CPUs: %d | Java: %s | Max Heap: %d MB%n",
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(),
                System.getProperty("java.version"),
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.println("================================================================================================================");

        List<BenchResult> engineResults = new ArrayList<>();
        for (WorkloadType w : List.of(WorkloadType.SET_ONLY, WorkloadType.GET_ONLY, WorkloadType.MIXED_RW)) {
            for (int clients : CLIENT_COUNTS) {
                engineResults.add(runEngineDispatchBenchmark(w, clients));
            }
        }
        printTable("IN-PROCESS COMMAND DISPATCH BENCHMARK (40,000 ops/run, 256 Striped Locks)", engineResults);

        List<BenchResult> tcpResults = new ArrayList<>();
        for (WorkloadType w : List.of(WorkloadType.SET_ONLY, WorkloadType.GET_ONLY, WorkloadType.MIXED_RW, WorkloadType.TX_MULTI)) {
            for (int clients : CLIENT_COUNTS) {
                tcpResults.add(runTcpRespBenchmark(w, clients));
            }
        }
        printTable("LIVE TCP + RESP2 SOCKET BENCHMARK (12,000 reqs/run, persistent sockets)", tcpResults);
    }

    private static BenchResult runEngineDispatchBenchmark(WorkloadType workload, int threads) throws Exception {
        StorageEngine storage = new StorageEngine();
        CommandRegistry registry = new CommandRegistry();
        seedStorage(storage);

        // Warmup
        for (int i = 0; i < 4_000; i++) {
            execEngineOp(registry, storage, workload, i, ThreadLocalRandom.current());
        }

        long[] latencies = new long[ENGINE_OPS];
        AtomicInteger idxGen = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        long tStart = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    while (true) {
                        int idx = idxGen.getAndIncrement();
                        if (idx >= ENGINE_OPS) break;
                        long op0 = System.nanoTime();
                        try {
                            RedisResponse resp = execEngineOp(registry, storage, workload, idx, rnd);
                            if (resp.getType() == RedisResponse.ResponseType.ERROR) {
                                failures.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        } finally {
                            latencies[idx] = System.nanoTime() - op0;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        long totalNanos = System.nanoTime() - tStart;
        pool.shutdownNow();

        return summarize("Engine", workload.label, threads, ENGINE_OPS, latencies, totalNanos, failures.get());
    }

    private static RedisResponse execEngineOp(CommandRegistry registry, StorageEngine storage,
                                              WorkloadType workload, int idx, ThreadLocalRandom rnd) {
        int keyId = rnd.nextInt(KEY_SPACE);
        return switch (workload) {
            case SET_ONLY -> registry.dispatch(List.of("SET", "k:" + keyId, "val:" + idx), storage);
            case GET_ONLY -> registry.dispatch(List.of("GET", "k:" + keyId), storage);
            case MIXED_RW -> {
                int p = rnd.nextInt(100);
                if (p < 50) {
                    yield registry.dispatch(List.of("GET", "k:" + keyId), storage);
                } else if (p < 80) {
                    yield registry.dispatch(List.of("SET", "k:" + keyId, "val:" + idx), storage);
                } else if (p < 90) {
                    yield registry.dispatch(List.of("INCRBY", "c:" + keyId, "1"), storage);
                } else if (p < 95) {
                    yield registry.dispatch(List.of("LPUSH", "l:" + keyId, "item:" + idx), storage);
                } else {
                    yield registry.dispatch(List.of("LPOP", "l:" + keyId), storage);
                }
            }
            case TX_MULTI -> RedisResponse.ok();
        };
    }

    private static BenchResult runTcpRespBenchmark(WorkloadType workload, int clients) throws Exception {
        StorageEngine storage = new StorageEngine();
        CommandRegistry registry = new CommandRegistry();
        seedStorage(storage);

        RedisServer server = new RedisServer(0, Math.max(64, clients + 16), storage, registry);
        server.start();
        int port = server.getPort();

        try {
            // Warmup on 1 TCP connection
            try (Socket wSock = new Socket("127.0.0.1", port)) {
                wSock.setTcpNoDelay(true);
                OutputStream out = new BufferedOutputStream(wSock.getOutputStream());
                InputStream in = new BufferedInputStream(wSock.getInputStream());
                for (int i = 0; i < 1_000; i++) {
                    sendTcpOp(out, in, WorkloadType.MIXED_RW, i, ThreadLocalRandom.current());
                }
            }

            long[] latencies = new long[TCP_OPS];
            AtomicInteger idxGen = new AtomicInteger(0);
            AtomicInteger failures = new AtomicInteger(0);
            ExecutorService pool = Executors.newFixedThreadPool(clients);
            CountDownLatch ready = new CountDownLatch(clients);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(clients);

            for (int c = 0; c < clients; c++) {
                pool.submit(() -> {
                    try (Socket sock = new Socket("127.0.0.1", port)) {
                        sock.setTcpNoDelay(true);
                        sock.setSoTimeout(15_000);
                        OutputStream out = new BufferedOutputStream(sock.getOutputStream());
                        InputStream in = new BufferedInputStream(sock.getInputStream());
                        ready.countDown();
                        start.await();

                        ThreadLocalRandom rnd = ThreadLocalRandom.current();
                        while (true) {
                            int idx = idxGen.getAndIncrement();
                            if (idx >= TCP_OPS) break;
                            long op0 = System.nanoTime();
                            try {
                                boolean ok = sendTcpOp(out, in, workload, idx, rnd);
                                if (!ok) failures.incrementAndGet();
                            } catch (Exception e) {
                                failures.incrementAndGet();
                            } finally {
                                latencies[idx] = System.nanoTime() - op0;
                            }
                        }
                    } catch (Exception e) {
                        ready.countDown();
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await(10, TimeUnit.SECONDS);
            long tStart = System.nanoTime();
            start.countDown();
            done.await(90, TimeUnit.SECONDS);
            long totalNanos = System.nanoTime() - tStart;
            pool.shutdownNow();

            return summarize("TCP/RESP2", workload.label, clients, TCP_OPS, latencies, totalNanos, failures.get());
        } finally {
            server.stop();
        }
    }

    private static boolean sendTcpOp(OutputStream out, InputStream in, WorkloadType workload,
                                     int idx, ThreadLocalRandom rnd) throws Exception {
        int keyId = rnd.nextInt(KEY_SPACE);
        if (workload == WorkloadType.TX_MULTI) {
            byte[] pipe = concat(
                    RespSerializer.encodeCommand(List.of("MULTI")),
                    RespSerializer.encodeCommand(List.of("INCRBY", "c:" + keyId, "1")),
                    RespSerializer.encodeCommand(List.of("EXEC"))
            );
            out.write(pipe);
            out.flush();
            RedisResponse r1 = RespParser.parseResponse(in);
            RedisResponse r2 = RespParser.parseResponse(in);
            RedisResponse r3 = RespParser.parseResponse(in);
            return r1 != null && r2 != null && r3 != null && r3.getType() == RedisResponse.ResponseType.ARRAY;
        }

        List<String> cmd;
        if (workload == WorkloadType.SET_ONLY) {
            cmd = List.of("SET", "k:" + keyId, "val:" + idx);
        } else if (workload == WorkloadType.GET_ONLY) {
            cmd = List.of("GET", "k:" + keyId);
        } else {
            int p = rnd.nextInt(100);
            if (p < 50) cmd = List.of("GET", "k:" + keyId);
            else if (p < 80) cmd = List.of("SET", "k:" + keyId, "val:" + idx);
            else cmd = List.of("INCRBY", "c:" + keyId, "1");
        }

        out.write(RespSerializer.encodeCommand(cmd));
        out.flush();
        RedisResponse resp = RespParser.parseResponse(in);
        return resp != null && resp.getType() != RedisResponse.ResponseType.ERROR;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static void seedStorage(StorageEngine storage) {
        for (int i = 0; i < KEY_SPACE; i++) {
            storage.set("k:" + i, "val:" + i);
            storage.set("c:" + i, "0");
            storage.rpush("l:" + i, List.of("a", "b"));
        }
    }

    private static BenchResult summarize(String mode, String workload, int clients, int ops,
                                         long[] latencies, long totalNanos, long failed) {
        double secs = Math.max(0.000001, totalNanos / 1_000_000_000.0);
        double tput = ops / secs;
        long[] copy = Arrays.copyOf(latencies, ops);
        Arrays.sort(copy);
        long sum = 0;
        for (long l : copy) sum += l;
        double avgUs = (sum / (double) ops) / 1_000.0;
        double p95Us = copy[Math.min(ops - 1, (int) (ops * 0.95))] / 1_000.0;
        double p99Us = copy[Math.min(ops - 1, (int) (ops * 0.99))] / 1_000.0;
        return new BenchResult(mode, workload, clients, ops, tput, avgUs, p95Us, p99Us, failed);
    }

    private static void printTable(String title, List<BenchResult> rows) {
        System.out.println("\n----------------------------------------------------------------------------------------------------------------");
        System.out.println(" " + title);
        System.out.println("----------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-22s | %7s | %15s | %12s | %12s | %12s | %8s%n",
                "Workload", "Clients", "Throughput", "Avg Lat (us)", "P95 Lat (us)", "P99 Lat (us)", "Failed");
        System.out.println("-----------------------+---------+-----------------+--------------+--------------+--------------+---------");
        for (BenchResult r : rows) {
            System.out.printf("%-22s | %7d | %10.1f op/s | %12.2f | %12.2f | %12.2f | %8d%n",
                    r.workload, r.clients, r.throughput, r.avgUs, r.p95Us, r.p99Us, r.failed);
        }
        System.out.println("----------------------------------------------------------------------------------------------------------------");
    }
}
