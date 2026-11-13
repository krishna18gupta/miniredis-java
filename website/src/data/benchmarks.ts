export interface BenchmarkRow {
  workload: string;
  clients: number;
  throughput: string;
  avgLatency: string;
  p95Latency: string;
  p99Latency: string;
  failedOps: number;
}

export interface AofBenchmarkRow {
  policy: 'ALWAYS' | 'EVERYSEC' | 'NO';
  runs: number;
  throughput: string;
  avgLatency: string;
  p95Latency: string;
  p99Latency: string;
  fsyncDuration: string;
  description: string;
}

export const BENCHMARK_ENVIRONMENT = {
  os: 'Windows 11 (amd64)',
  cpus: '8 Logical Cores (Intel/AMD x86_64)',
  jvm: 'OpenJDK 64-Bit Server VM (Java 17/25)',
  heap: '1.5 GB Max Allocated Heap',
  testDate: 'November 2026',
  disclaimer: 'Benchmark numbers reflect real, measured results from the MiniRedis Phase 10 verification suite. Actual performance depends on hardware architecture, CPU clock speed, SSD fsync latency, network interface cards, and JVM garbage collection characteristics.'
};

export const IN_PROCESS_BENCHMARKS: BenchmarkRow[] = [
  { workload: 'GET only', clients: 1, throughput: '543,391 op/s', avgLatency: '1.53 μs', p95Latency: '1.50 μs', p99Latency: '2.50 μs', failedOps: 0 },
  { workload: 'GET only', clients: 10, throughput: '716,222 op/s', avgLatency: '5.85 μs', p95Latency: '1.40 μs', p99Latency: '3.70 μs', failedOps: 0 },
  { workload: 'GET only', clients: 25, throughput: '1,423,649 op/s', avgLatency: '0.93 μs', p95Latency: '0.60 μs', p99Latency: '1.20 μs', failedOps: 0 },
  { workload: 'GET only', clients: 50, throughput: '1,479,629 op/s', avgLatency: '0.44 μs', p95Latency: '0.40 μs', p99Latency: '1.00 μs', failedOps: 0 },

  { workload: 'SET only', clients: 1, throughput: '213,649 op/s', avgLatency: '3.67 μs', p95Latency: '4.10 μs', p99Latency: '20.50 μs', failedOps: 0 },
  { workload: 'SET only', clients: 10, throughput: '312,435 op/s', avgLatency: '21.63 μs', p95Latency: '3.40 μs', p99Latency: '18.20 μs', failedOps: 0 },
  { workload: 'SET only', clients: 25, throughput: '407,436 op/s', avgLatency: '1.79 μs', p95Latency: '1.60 μs', p99Latency: '2.80 μs', failedOps: 0 },
  { workload: 'SET only', clients: 50, throughput: '822,831 op/s', avgLatency: '10.07 μs', p95Latency: '1.20 μs', p99Latency: '3.00 μs', failedOps: 0 },

  { workload: 'Mixed (50% G / 30% S / 10% I / 10% L)', clients: 1, throughput: '350,642 op/s', avgLatency: '2.75 μs', p95Latency: '3.40 μs', p99Latency: '10.70 μs', failedOps: 0 },
  { workload: 'Mixed (50% G / 30% S / 10% I / 10% L)', clients: 10, throughput: '488,802 op/s', avgLatency: '14.57 μs', p95Latency: '5.10 μs', p99Latency: '15.00 μs', failedOps: 0 },
  { workload: 'Mixed (50% G / 30% S / 10% I / 10% L)', clients: 25, throughput: '397,393 op/s', avgLatency: '9.61 μs', p95Latency: '4.10 μs', p99Latency: '9.10 μs', failedOps: 0 },
  { workload: 'Mixed (50% G / 30% S / 10% I / 10% L)', clients: 50, throughput: '376,639 op/s', avgLatency: '51.51 μs', p95Latency: '2.80 μs', p99Latency: '12.20 μs', failedOps: 0 },
];

export const TCP_BENCHMARKS: BenchmarkRow[] = [
  { workload: 'GET only', clients: 1, throughput: '8,885 op/s', avgLatency: '111.79 μs', p95Latency: '265.00 μs', p99Latency: '740.00 μs', failedOps: 0 },
  { workload: 'GET only', clients: 10, throughput: '54,089 op/s', avgLatency: '182.96 μs', p95Latency: '209.90 μs', p99Latency: '1,023.20 μs', failedOps: 0 },
  { workload: 'GET only', clients: 25, throughput: '68,670 op/s', avgLatency: '172.83 μs', p95Latency: '229.70 μs', p99Latency: '647.90 μs', failedOps: 0 },
  { workload: 'GET only', clients: 50, throughput: '50,163 op/s', avgLatency: '257.10 μs', p95Latency: '221.40 μs', p99Latency: '559.00 μs', failedOps: 0 },

  { workload: 'SET only', clients: 1, throughput: '8,582 op/s', avgLatency: '115.65 μs', p95Latency: '270.50 μs', p99Latency: '743.60 μs', failedOps: 0 },
  { workload: 'SET only', clients: 10, throughput: '66,622 op/s', avgLatency: '147.92 μs', p95Latency: '171.50 μs', p99Latency: '409.30 μs', failedOps: 0 },
  { workload: 'SET only', clients: 25, throughput: '56,160 op/s', avgLatency: '216.54 μs', p95Latency: '196.20 μs', p99Latency: '643.80 μs', failedOps: 0 },
  { workload: 'SET only', clients: 50, throughput: '50,235 op/s', avgLatency: '194.87 μs', p95Latency: '214.80 μs', p99Latency: '693.00 μs', failedOps: 0 },

  { workload: 'Mixed R/W', clients: 1, throughput: '13,314 op/s', avgLatency: '74.95 μs', p95Latency: '117.70 μs', p99Latency: '235.50 μs', failedOps: 0 },
  { workload: 'Mixed R/W', clients: 10, throughput: '58,456 op/s', avgLatency: '166.31 μs', p95Latency: '172.10 μs', p99Latency: '436.80 μs', failedOps: 0 },
  { workload: 'Mixed R/W', clients: 25, throughput: '50,729 op/s', avgLatency: '176.88 μs', p95Latency: '216.00 μs', p99Latency: '561.20 μs', failedOps: 0 },
  { workload: 'Mixed R/W', clients: 50, throughput: '57,205 op/s', avgLatency: '140.03 μs', p95Latency: '187.00 μs', p99Latency: '458.50 μs', failedOps: 0 },

  { workload: 'MULTI / INCRBY / EXEC', clients: 1, throughput: '6,361 tx/s', avgLatency: '157.06 μs', p95Latency: '242.80 μs', p99Latency: '441.40 μs', failedOps: 0 },
  { workload: 'MULTI / INCRBY / EXEC', clients: 10, throughput: '35,988 tx/s', avgLatency: '269.82 μs', p95Latency: '311.50 μs', p99Latency: '2,513.30 μs', failedOps: 0 },
  { workload: 'MULTI / INCRBY / EXEC', clients: 25, throughput: '38,965 tx/s', avgLatency: '377.59 μs', p95Latency: '293.50 μs', p99Latency: '1,879.40 μs', failedOps: 0 },
  { workload: 'MULTI / INCRBY / EXEC', clients: 50, throughput: '25,812 tx/s', avgLatency: '427.71 μs', p95Latency: '330.90 μs', p99Latency: '1,273.60 μs', failedOps: 0 },
];

export const AOF_BENCHMARKS: AofBenchmarkRow[] = [
  {
    policy: 'EVERYSEC',
    runs: 3,
    throughput: '138,488 op/s',
    avgLatency: '0.007 ms',
    p95Latency: '0.009 ms',
    p99Latency: '0.038 ms',
    fsyncDuration: '0.000 ms (background daemon)',
    description: 'Writes flushed to OS buffer; background thread executes fsync() every 1000ms. Optimal balance of speed and safety.'
  },
  {
    policy: 'NO',
    runs: 3,
    throughput: '85,004 op/s',
    avgLatency: '0.015 ms',
    p95Latency: '0.020 ms',
    p99Latency: '0.075 ms',
    fsyncDuration: '0.000 ms (OS managed)',
    description: 'Leaves disk flushing entirely to the OS page cache manager. Fastest I/O path with potential for minor data loss on power cut.'
  },
  {
    policy: 'ALWAYS',
    runs: 3,
    throughput: '851 op/s',
    avgLatency: '1.205 ms',
    p95Latency: '2.069 ms',
    p99Latency: '3.376 ms',
    fsyncDuration: '0.333 ms (synchronous blocking)',
    description: 'Executes fsync() synchronously before acknowledging every write. Maximum durability; constrained by physical disk write latency.'
  },
];

export const AOF_COMPACTION_METRICS = {
  bloatedFileSize: '304 KB (5,000 updates)',
  compactedFileSize: '0 KB (clean snapshot)',
  reductionPct: '100.0%',
  medianRewriteDuration: '7 ms',
  medianRecoveryDuration: '3 ms',
  recoveryThroughput: '333 cmds/sec'
};
