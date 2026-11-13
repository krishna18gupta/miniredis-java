import React from 'react';
import { SeoMeta } from '../components/common/SeoMeta';
import { SITE_CONFIG } from '../config/site';
import { AlertTriangle, Compass, Heart, CheckCircle2, Code2 } from 'lucide-react';
import { Badge } from '../components/common/Badge';

export const AboutPage: React.FC = () => {
  return (
    <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 py-10 space-y-16">
      <SeoMeta
        title="About & Engineering Roadmap"
        description="Learn about the engineering philosophy behind MiniRedis, documented architectural limitations, development roadmap, and contribution guidelines."
      />

      {/* Header */}
      <div className="space-y-4 text-center max-w-3xl mx-auto">
        <span className="text-xs font-mono uppercase text-brand-500 font-semibold tracking-wider">
          Philosophy & Vision
        </span>
        <h1 className="text-3xl sm:text-5xl font-extrabold text-zinc-900 dark:text-zinc-50 tracking-tight">
          About MiniRedis
        </h1>
        <p className="text-base text-zinc-600 dark:text-zinc-300 leading-relaxed">
          An open-source Redis-compatible in-memory database built from the ground up to demonstrate production-grade system engineering in pure Java 17.
        </p>
      </div>

      {/* Story & Principles */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/60 space-y-6 shadow-sm">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-lg bg-brand-500/10 text-brand-500">
            <Heart className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
              The Engineering Philosophy
            </h2>
            <p className="text-xs text-zinc-500">First-Principles Implementation with Zero Black Boxes</p>
          </div>
        </div>

        <p className="text-sm text-zinc-600 dark:text-zinc-300 leading-relaxed">
          Modern infrastructure often relies on enormous dependency trees. MiniRedis was built under a strict design rule: <strong>zero external runtime dependencies</strong>. Every data structure, protocol serializer, concurrency manager, and persistence coordinator is written using only standard Java 17 platform primitives (<code className="text-xs font-mono">java.base</code> and <code className="text-xs font-mono">jdk.httpserver</code>).
        </p>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs">
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60 space-y-1.5">
            <span className="font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-1.5">
              <CheckCircle2 className="w-4 h-4 text-emerald-500" />
              Transparent Concurrency
            </span>
            <p className="text-zinc-500 dark:text-zinc-400">
              Replaces the single-thread Redis bottleneck with a 256-stripe read-write lock pool, avoiding global locks while mathematically preventing deadlocks via canonical sorting.
            </p>
          </div>

          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60 space-y-1.5">
            <span className="font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-1.5">
              <CheckCircle2 className="w-4 h-4 text-emerald-500" />
              Verified Durability
            </span>
            <p className="text-zinc-500 dark:text-zinc-400">
              Complete crash survivability with write-ahead logging, timestamp normalization, background compaction, and automatic truncation of trailing corrupt frames.
            </p>
          </div>
        </div>
      </section>

      {/* Transparent Limitations */}
      <section className="space-y-4">
        <div className="flex items-center space-x-2 text-amber-500">
          <AlertTriangle className="w-5 h-5" />
          <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
            Documented Project Limitations
          </h2>
        </div>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          MiniRedis is designed for high-performance single-instance key-value workloads. In the interest of full technical transparency, the following features are intentionally out of scope for v1.0.0:
        </p>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          {SITE_CONFIG.limitations.map((limit, idx) => (
            <div
              key={idx}
              className="p-5 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/40 space-y-2 shadow-sm"
            >
              <div className="flex items-center space-x-2">
                <Badge variant="warning">Limitation</Badge>
                <h3 className="font-bold text-sm text-zinc-900 dark:text-zinc-100">{limit.title}</h3>
              </div>
              <p className="text-xs text-zinc-600 dark:text-zinc-400 leading-relaxed">
                {limit.description}
              </p>
            </div>
          ))}
        </div>
      </section>

      {/* Roadmap */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/60 space-y-6 shadow-sm">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-lg bg-blue-500/10 text-blue-500">
            <Compass className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
              Development Roadmap
            </h2>
            <p className="text-xs text-zinc-500">Future Milestones Beyond v1.0.0</p>
          </div>
        </div>

        <div className="space-y-3 font-mono text-xs">
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
            <div>
              <span className="font-bold text-zinc-900 dark:text-zinc-100">v1.1.0 &bull; RESP3 Protocol Support</span>
              <p className="font-sans text-zinc-500 text-xs mt-0.5">Native parsing for RESP3 maps (%), sets (~), and metadata attributes (|).</p>
            </div>
            <Badge variant="info">Planned</Badge>
          </div>

          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
            <div>
              <span className="font-bold text-zinc-900 dark:text-zinc-100">v1.2.0 &bull; Asynchronous Master-Replica Replication</span>
              <p className="font-sans text-zinc-500 text-xs mt-0.5">Leader-follower replication stream with offset tracking and automatic reconnect.</p>
            </div>
            <Badge variant="info">Planned</Badge>
          </div>

          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
            <div>
              <span className="font-bold text-zinc-900 dark:text-zinc-100">v1.3.0 &bull; RDB Binary Snapshots</span>
              <p className="font-sans text-zinc-500 text-xs mt-0.5">Point-in-time compacted binary dumps alongside existing AOF write-ahead logs.</p>
            </div>
            <Badge variant="info">Future</Badge>
          </div>
        </div>
      </section>

      {/* Open Source & Contributing */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/60 space-y-4 shadow-sm">
        <div className="flex items-center space-x-2 text-zinc-900 dark:text-zinc-100">
          <Code2 className="w-5 h-5 text-brand-500" />
          <h2 className="text-xl font-bold">Open Source & Community</h2>
        </div>
        <p className="text-xs text-zinc-600 dark:text-zinc-300 leading-relaxed">
          MiniRedis is licensed under the permissive <strong>MIT License</strong>. Contributions adhering to the zero runtime dependency mandate and verified by the 145-test suite are welcome.
        </p>
        <div className="text-xs font-mono text-zinc-500 pt-2 border-t border-zinc-200 dark:border-zinc-800">
          Copyright &copy; {SITE_CONFIG.copyrightYear} {SITE_CONFIG.author}.
        </div>
      </section>
    </div>
  );
};
