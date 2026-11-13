import React from 'react';
import { SeoMeta } from '../components/common/SeoMeta';
import {
  BENCHMARK_ENVIRONMENT,
  IN_PROCESS_BENCHMARKS,
  TCP_BENCHMARKS,
  AOF_BENCHMARKS,
  AOF_COMPACTION_METRICS
} from '../data/benchmarks';
import { Zap, Cpu, Server, ShieldCheck, AlertCircle } from 'lucide-react';
import { Badge } from '../components/common/Badge';

export const BenchmarksPage: React.FC = () => {
  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-10 space-y-16">
      <SeoMeta
        title="Performance Benchmarks"
        description="Measured Phase 10 empirical benchmarks for MiniRedis: 1.48M in-process ops/sec, 68.6K live TCP requests/sec, and 138K AOF persistence writes/sec."
      />

      {/* Header */}
      <div className="space-y-4 text-center max-w-3xl mx-auto">
        <span className="text-xs font-mono uppercase text-brand-500 font-semibold tracking-wider">
          Empirical Measurement Suite
        </span>
        <h1 className="text-3xl sm:text-5xl font-extrabold text-zinc-900 dark:text-zinc-50 tracking-tight">
          Performance Benchmarks
        </h1>
        <p className="text-base text-zinc-600 dark:text-zinc-300 leading-relaxed">
          Real measurements captured from the MiniRedis Phase 10 multi-client benchmark suite. Zero synthetic estimations or simulated numbers.
        </p>
      </div>

      {/* Test Environment Card */}
      <section className="p-6 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/60 shadow-sm space-y-4">
        <h2 className="text-sm font-bold text-zinc-900 dark:text-zinc-100 uppercase tracking-wider font-mono flex items-center gap-2">
          <Cpu className="w-4 h-4 text-brand-500" />
          Hardware & Test Bed Specifications
        </h2>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-xs font-mono">
          <div className="p-3 rounded-lg bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800">
            <span className="text-zinc-400 block text-[10px]">Operating System</span>
            <span className="font-bold text-zinc-800 dark:text-zinc-200">{BENCHMARK_ENVIRONMENT.os}</span>
          </div>
          <div className="p-3 rounded-lg bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800">
            <span className="text-zinc-400 block text-[10px]">Processors</span>
            <span className="font-bold text-zinc-800 dark:text-zinc-200">{BENCHMARK_ENVIRONMENT.cpus}</span>
          </div>
          <div className="p-3 rounded-lg bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800">
            <span className="text-zinc-400 block text-[10px]">Java Virtual Machine</span>
            <span className="font-bold text-zinc-800 dark:text-zinc-200">{BENCHMARK_ENVIRONMENT.jvm}</span>
          </div>
          <div className="p-3 rounded-lg bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800">
            <span className="text-zinc-400 block text-[10px]">Max Heap Allocation</span>
            <span className="font-bold text-zinc-800 dark:text-zinc-200">{BENCHMARK_ENVIRONMENT.heap}</span>
          </div>
        </div>

        <div className="p-3 rounded-lg bg-amber-500/5 border border-amber-500/20 text-xs text-zinc-600 dark:text-zinc-400 flex items-start gap-2.5">
          <AlertCircle className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
          <span>{BENCHMARK_ENVIRONMENT.disclaimer}</span>
        </div>
      </section>

      {/* Benchmark 1: In-Process Dispatch */}
      <section className="space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
              <Zap className="w-5 h-5 text-amber-500" />
              1. In-Process Command Dispatch (40,000 Ops / Run, 256 Striped Locks)
            </h2>
            <p className="text-xs text-zinc-500">Evaluates raw concurrency scaling across worker threads with zero network overhead.</p>
          </div>
          <Badge variant="success">Peak 1,479,629 op/s</Badge>
        </div>

        <div className="overflow-x-auto rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/40">
          <table className="w-full text-left text-xs font-mono">
            <thead className="bg-zinc-50 dark:bg-zinc-950 border-b border-zinc-200 dark:border-zinc-800 text-zinc-500">
              <tr>
                <th className="p-3 font-semibold">Workload</th>
                <th className="p-3 font-semibold">Clients</th>
                <th className="p-3 font-semibold text-brand-600 dark:text-brand-400">Throughput</th>
                <th className="p-3 font-semibold">Avg Latency</th>
                <th className="p-3 font-semibold">P95 Latency</th>
                <th className="p-3 font-semibold">P99 Latency</th>
                <th className="p-3 font-semibold text-emerald-600">Failed Ops</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-100 dark:divide-zinc-800/60 text-zinc-800 dark:text-zinc-200">
              {IN_PROCESS_BENCHMARKS.map((row, idx) => (
                <tr key={idx} className="hover:bg-zinc-50 dark:hover:bg-zinc-800/40 transition-colors">
                  <td className="p-3 font-medium">{row.workload}</td>
                  <td className="p-3">{row.clients}</td>
                  <td className="p-3 font-bold text-brand-600 dark:text-brand-400">{row.throughput}</td>
                  <td className="p-3">{row.avgLatency}</td>
                  <td className="p-3">{row.p95Latency}</td>
                  <td className="p-3">{row.p99Latency}</td>
                  <td className="p-3 text-emerald-500 font-semibold">{row.failedOps}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {/* Benchmark 2: Live TCP Socket */}
      <section className="space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
              <Server className="w-5 h-5 text-blue-500" />
              2. Live TCP + RESP2 Socket Benchmark (12,000 Reqs / Run, Persistent Sockets)
            </h2>
            <p className="text-xs text-zinc-500">Full end-to-end socket I/O, UTF-8 framing, RESP2 serialization, and multi-client socket dispatch.</p>
          </div>
          <Badge variant="info">Peak 68,670 op/s</Badge>
        </div>

        <div className="overflow-x-auto rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/40">
          <table className="w-full text-left text-xs font-mono">
            <thead className="bg-zinc-50 dark:bg-zinc-950 border-b border-zinc-200 dark:border-zinc-800 text-zinc-500">
              <tr>
                <th className="p-3 font-semibold">Workload</th>
                <th className="p-3 font-semibold">Concurrent Sockets</th>
                <th className="p-3 font-semibold text-blue-600 dark:text-blue-400">Throughput</th>
                <th className="p-3 font-semibold">Avg Latency</th>
                <th className="p-3 font-semibold">P95 Latency</th>
                <th className="p-3 font-semibold">P99 Latency</th>
                <th className="p-3 font-semibold text-emerald-600">Failed Ops</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-100 dark:divide-zinc-800/60 text-zinc-800 dark:text-zinc-200">
              {TCP_BENCHMARKS.map((row, idx) => (
                <tr key={idx} className="hover:bg-zinc-50 dark:hover:bg-zinc-800/40 transition-colors">
                  <td className="p-3 font-medium">{row.workload}</td>
                  <td className="p-3">{row.clients}</td>
                  <td className="p-3 font-bold text-blue-600 dark:text-blue-400">{row.throughput}</td>
                  <td className="p-3">{row.avgLatency}</td>
                  <td className="p-3">{row.p95Latency}</td>
                  <td className="p-3">{row.p99Latency}</td>
                  <td className="p-3 text-emerald-500 font-semibold">{row.failedOps}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {/* Benchmark 3: AOF Persistence */}
      <section className="space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
              <ShieldCheck className="w-5 h-5 text-emerald-500" />
              3. AOF Persistence Multi-Iteration Benchmark (Median Metrics)
            </h2>
            <p className="text-xs text-zinc-500">Comparison of write throughput and fsync latency across fsync policies.</p>
          </div>
          <Badge variant="warning">Compacted in 7 ms</Badge>
        </div>

        <div className="overflow-x-auto rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/40">
          <table className="w-full text-left text-xs font-mono">
            <thead className="bg-zinc-50 dark:bg-zinc-950 border-b border-zinc-200 dark:border-zinc-800 text-zinc-500">
              <tr>
                <th className="p-3 font-semibold">Policy</th>
                <th className="p-3 font-semibold">Runs</th>
                <th className="p-3 font-semibold text-emerald-600 dark:text-emerald-400">Median Throughput</th>
                <th className="p-3 font-semibold">Avg Latency</th>
                <th className="p-3 font-semibold">P95 Latency</th>
                <th className="p-3 font-semibold">P99 Latency</th>
                <th className="p-3 font-semibold">Fsync Overhead</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-100 dark:divide-zinc-800/60 text-zinc-800 dark:text-zinc-200">
              {AOF_BENCHMARKS.map((row, idx) => (
                <tr key={idx} className="hover:bg-zinc-50 dark:hover:bg-zinc-800/40 transition-colors">
                  <td className="p-3 font-bold text-zinc-900 dark:text-zinc-100">{row.policy}</td>
                  <td className="p-3">{row.runs}</td>
                  <td className="p-3 font-bold text-emerald-600 dark:text-emerald-400">{row.throughput}</td>
                  <td className="p-3">{row.avgLatency}</td>
                  <td className="p-3">{row.p95Latency}</td>
                  <td className="p-3">{row.p99Latency}</td>
                  <td className="p-3 text-zinc-400">{row.fsyncDuration}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* Compaction & Recovery Subcard */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 p-5 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950 text-xs font-mono">
          <div>
            <span className="text-zinc-400 block text-[10px]">Bloated File Size</span>
            <span className="font-bold text-zinc-800 dark:text-zinc-200">{AOF_COMPACTION_METRICS.bloatedFileSize}</span>
          </div>
          <div>
            <span className="text-zinc-400 block text-[10px]">Compaction Ratio</span>
            <span className="font-bold text-emerald-500">{AOF_COMPACTION_METRICS.reductionPct} reduction</span>
          </div>
          <div>
            <span className="text-zinc-400 block text-[10px]">Rewrite Duration</span>
            <span className="font-bold text-zinc-800 dark:text-zinc-200">{AOF_COMPACTION_METRICS.medianRewriteDuration}</span>
          </div>
          <div>
            <span className="text-zinc-400 block text-[10px]">Recovery Replay Duration</span>
            <span className="font-bold text-zinc-800 dark:text-zinc-200">{AOF_COMPACTION_METRICS.medianRecoveryDuration}</span>
          </div>
        </div>
      </section>
    </div>
  );
};
