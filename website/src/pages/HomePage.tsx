import React from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Terminal, Zap, Shield, Database, Lock, Cpu, Server, CheckCircle2, Activity } from 'lucide-react';
import { SITE_CONFIG } from '../config/site';
import { ArchitectureDiagram } from '../components/architecture/ArchitectureDiagram';
import { CodeBlock } from '../components/common/CodeBlock';
import { SeoMeta } from '../components/common/SeoMeta';

export const HomePage: React.FC = () => {
  const terminalSnippet = `# 1. Start MiniRedis (TCP:6379, Web:8080)
java -jar target/miniredis-java-1.0.0.jar

# 2. Connect with redis-cli or built-in client
redis-cli -p 6379

127.0.0.1:6379> SET user:1 "Krishna"
+OK
127.0.0.1:6379> EXPIRE user:1 60
:1
127.0.0.1:6379> HSET stats:daily views 1042 errors 0
:2
127.0.0.1:6379> MULTI
+OK
127.0.0.1:6379(TX)> INCRBY balance 50
+QUEUED
127.0.0.1:6379(TX)> EXEC
1) (integer) 150`;

  return (
    <div className="space-y-20 py-8">
      <SeoMeta
        title="High-Performance Redis-Compatible In-Memory Database"
        description="MiniRedis is an in-memory database built from scratch in pure Java 17 with zero external runtime dependencies. 57 commands, RESP2 protocol, 256-stripe concurrency & AOF persistence."
      />

      {/* Hero Section */}
      <section className="relative px-4 sm:px-6 lg:px-8 max-w-7xl mx-auto text-center space-y-8 pt-8">
        <div className="inline-flex items-center space-x-2 px-3.5 py-1.5 rounded-full border border-brand-500/20 bg-brand-500/5 text-brand-600 dark:text-brand-400 text-xs font-medium">
          <span className="w-2 h-2 rounded-full bg-brand-500 animate-pulse"></span>
          <span>MiniRedis v1.0.0 Released &bull; Pure Java 17 Platform</span>
        </div>

        <h1 className="text-4xl sm:text-6xl font-extrabold tracking-tight text-zinc-900 dark:text-zinc-50 max-w-4xl mx-auto leading-tight sm:leading-tight">
          High-Performance <span className="text-transparent bg-clip-text bg-gradient-to-r from-red-600 via-brand-500 to-amber-500">Redis-Compatible</span> Database in Java
        </h1>

        <p className="max-w-2xl mx-auto text-base sm:text-lg text-zinc-600 dark:text-zinc-300 leading-relaxed">
          Built completely from first principles with <strong className="text-zinc-900 dark:text-zinc-100 font-semibold">zero external runtime dependencies</strong>.
          Features 57 commands, full RESP2 protocol compliance, 256-stripe deadlock-free concurrency, AOF persistence, and optimistic transactions.
        </p>

        {/* Hero CTAs */}
        <div className="flex flex-col sm:flex-row items-center justify-center gap-3 pt-2">
          <Link
            to="/dashboard"
            className="w-full sm:w-auto inline-flex items-center justify-center space-x-2 px-6 py-3 rounded-lg font-medium text-sm text-white bg-emerald-600 hover:bg-emerald-500 shadow-sm transition-all"
          >
            <Activity className="w-4 h-4 text-emerald-200" />
            <span>Launch Live Console</span>
            <span className="px-1.5 py-0.5 text-[10px] font-bold rounded-full bg-white/20 text-white ml-1">Live</span>
          </Link>

          <Link
            to="/docs"
            className="w-full sm:w-auto inline-flex items-center justify-center space-x-2 px-6 py-3 rounded-lg font-medium text-sm text-white bg-brand-600 hover:bg-brand-500 shadow-sm transition-all"
          >
            <Terminal className="w-4 h-4" />
            <span>Quick Start Guide</span>
            <ArrowRight className="w-4 h-4 ml-1" />
          </Link>

          <Link
            to="/commands"
            className="w-full sm:w-auto inline-flex items-center justify-center space-x-2 px-6 py-3 rounded-lg font-medium text-sm text-zinc-700 dark:text-zinc-300 bg-white dark:bg-zinc-900 border border-zinc-300 dark:border-zinc-800 hover:bg-zinc-50 dark:hover:bg-zinc-800 shadow-sm transition-all"
          >
            <span>Explore 57 Commands</span>
          </Link>
        </div>

        {/* Stats Grid */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 max-w-4xl mx-auto pt-10">
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/60 shadow-sm">
            <div className="text-2xl sm:text-3xl font-extrabold text-zinc-900 dark:text-zinc-100 font-mono">
              {SITE_CONFIG.stats.supportedCommands}
            </div>
            <div className="text-xs text-zinc-500 font-medium mt-1">Supported Commands</div>
          </div>

          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/60 shadow-sm">
            <div className="text-2xl sm:text-3xl font-extrabold text-brand-600 dark:text-brand-400 font-mono">
              {SITE_CONFIG.stats.concurrencyStripes}
            </div>
            <div className="text-xs text-zinc-500 font-medium mt-1">Concurrency Stripes</div>
          </div>

          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/60 shadow-sm">
            <div className="text-2xl sm:text-3xl font-extrabold text-emerald-600 dark:text-emerald-400 font-mono">
              {SITE_CONFIG.stats.peakInProcessOps}
            </div>
            <div className="text-xs text-zinc-500 font-medium mt-1">Peak Engine Ops/Sec</div>
          </div>

          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/60 shadow-sm">
            <div className="text-2xl sm:text-3xl font-extrabold text-zinc-900 dark:text-zinc-100 font-mono">
              {SITE_CONFIG.stats.runtimeDependencies}
            </div>
            <div className="text-xs text-zinc-500 font-medium mt-1">Runtime Dependencies</div>
          </div>
        </div>
      </section>

      {/* Terminal Showcase */}
      <section className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-6 sm:p-8 shadow-sm">
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-6 pb-4 border-b border-zinc-200 dark:border-zinc-800">
            <div>
              <span className="text-xs font-mono uppercase text-brand-500 font-semibold tracking-wider">
                Native Terminal Experience
              </span>
              <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 mt-1">
                Zero Configuration. Launch in Seconds.
              </h2>
            </div>
            <div className="flex items-center space-x-2 text-xs font-mono text-zinc-500">
              <span className="w-2 h-2 rounded-full bg-emerald-500"></span>
              <span>100% Wire Compatible with redis-cli</span>
            </div>
          </div>

          <CodeBlock
            code={terminalSnippet}
            language="bash"
            title="bash - Launch Server & Run Redis Client"
          />

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mt-6 text-xs text-zinc-600 dark:text-zinc-400">
            <div className="flex items-start space-x-2">
              <CheckCircle2 className="w-4 h-4 text-emerald-500 flex-shrink-0 mt-0.5" />
              <span>Standard Redis TCP port 6379 with RESP2 serialization</span>
            </div>
            <div className="flex items-start space-x-2">
              <CheckCircle2 className="w-4 h-4 text-emerald-500 flex-shrink-0 mt-0.5" />
              <span>Built-in Web Console dashboard on port 8080</span>
            </div>
            <div className="flex items-start space-x-2">
              <CheckCircle2 className="w-4 h-4 text-emerald-500 flex-shrink-0 mt-0.5" />
              <span>Tested & verified with Python redis-py (protocol=2)</span>
            </div>
          </div>
        </div>
      </section>

      {/* Highlights Grid */}
      <section className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center space-y-3 mb-12">
          <span className="text-xs font-mono uppercase text-brand-500 font-semibold tracking-wider">
            Engineered from Scratch
          </span>
          <h2 className="text-3xl font-extrabold text-zinc-900 dark:text-zinc-100">
            Production-Grade Computer Science Architecture
          </h2>
          <p className="text-sm text-zinc-500 dark:text-zinc-400 max-w-2xl mx-auto">
            MiniRedis avoids black-box libraries, demonstrating core database engineering across concurrency, persistence, memory layouts, and protocols.
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          <div className="p-6 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-3">
            <div className="w-10 h-10 rounded-lg bg-red-500/10 text-brand-500 flex items-center justify-center">
              <Database className="w-5 h-5" />
            </div>
            <h3 className="font-bold text-base text-zinc-900 dark:text-zinc-100">
              Rich Strongly-Typed Collections
            </h3>
            <p className="text-xs text-zinc-500 dark:text-zinc-400 leading-relaxed">
              Strings, Lists (Deques), Sets (HashSets), and Hashes (HashMaps) with strict Redis <code className="text-[11px] font-mono">WRONGTYPE</code> exception semantics and zero orphaned keys.
            </p>
          </div>

          <div className="p-6 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-3">
            <div className="w-10 h-10 rounded-lg bg-amber-500/10 text-amber-500 flex items-center justify-center">
              <Lock className="w-5 h-5" />
            </div>
            <h3 className="font-bold text-base text-zinc-900 dark:text-zinc-100">
              256-Stripe Concurrency Engine
            </h3>
            <p className="text-xs text-zinc-500 dark:text-zinc-400 leading-relaxed">
              Eliminates global lock contention with 256 fine-grained ReadWriteLocks. Enforces canonical ascending lock ordering to mathematically guarantee zero multi-key deadlocks.
            </p>
          </div>

          <div className="p-6 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-3">
            <div className="w-10 h-10 rounded-lg bg-emerald-500/10 text-emerald-500 flex items-center justify-center">
              <Shield className="w-5 h-5" />
            </div>
            <h3 className="font-bold text-base text-zinc-900 dark:text-zinc-100">
              AOF Durability & Auto-Recovery
            </h3>
            <p className="text-xs text-zinc-500 dark:text-zinc-400 leading-relaxed">
              Configurable fsync policies (ALWAYS, EVERYSEC, NO). Includes non-blocking <code className="text-[11px] font-mono">BGREWRITEAOF</code> with diff buffer and startup auto-truncation of corrupted frames.
            </p>
          </div>

          <div className="p-6 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-3">
            <div className="w-10 h-10 rounded-lg bg-blue-500/10 text-blue-500 flex items-center justify-center">
              <Server className="w-5 h-5" />
            </div>
            <h3 className="font-bold text-base text-zinc-900 dark:text-zinc-100">
              RESP2 Protocol & Pipelining
            </h3>
            <p className="text-xs text-zinc-500 dark:text-zinc-400 leading-relaxed">
              Full wire protocol parser supporting simple strings, errors, integers, bulk strings, and arrays. Accurately distinguishes null bulk strings (<code className="text-[11px] font-mono">$-1</code>) from empty strings.
            </p>
          </div>

          <div className="p-6 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-3">
            <div className="w-10 h-10 rounded-lg bg-purple-500/10 text-purple-500 flex items-center justify-center">
              <Cpu className="w-5 h-5" />
            </div>
            <h3 className="font-bold text-base text-zinc-900 dark:text-zinc-100">
              Optimistic CAS Transactions
            </h3>
            <p className="text-xs text-zinc-500 dark:text-zinc-400 leading-relaxed">
              Full <code className="text-[11px] font-mono">MULTI/EXEC/DISCARD/WATCH</code> support with compile-time error marking (<code className="text-[11px] font-mono">EXECABORT</code>) and atomic multi-key execution.
            </p>
          </div>

          <div className="p-6 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-3">
            <div className="w-10 h-10 rounded-lg bg-cyan-500/10 text-cyan-500 flex items-center justify-center">
              <Zap className="w-5 h-5" />
            </div>
            <h3 className="font-bold text-base text-zinc-900 dark:text-zinc-100">
              Dual Expiration Engine
            </h3>
            <p className="text-xs text-zinc-500 dark:text-zinc-400 leading-relaxed">
              Sub-millisecond TTL precision via passive lazy checking on access plus a 100ms adaptive background daemon sweep with probabilistic termination.
            </p>
          </div>
        </div>
      </section>

      {/* Architecture Interactive Preview */}
      <section className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="space-y-4 mb-6">
          <span className="text-xs font-mono uppercase text-brand-500 font-semibold tracking-wider">
            Interactive Architecture Diagram
          </span>
          <h2 className="text-2xl sm:text-3xl font-bold text-zinc-900 dark:text-zinc-100">
            Engineered in Layers
          </h2>
          <p className="text-xs sm:text-sm text-zinc-500 dark:text-zinc-400 max-w-2xl">
            Click through each layer to inspect the internal components, class relationships, and thread interactions that power MiniRedis.
          </p>
        </div>

        <ArchitectureDiagram />

        <div className="text-center pt-6">
          <Link
            to="/architecture"
            className="inline-flex items-center space-x-1.5 text-xs font-semibold text-brand-600 dark:text-brand-400 hover:text-brand-500"
          >
            <span>Read Complete Architectural Deep Dive</span>
            <ArrowRight className="w-3.5 h-3.5" />
          </Link>
        </div>
      </section>

      {/* CTA Footer Banner */}
      <section className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="rounded-2xl bg-gradient-to-br from-zinc-900 to-zinc-950 text-white p-8 sm:p-12 border border-zinc-800 text-center space-y-6 shadow-xl">
          <h2 className="text-2xl sm:text-4xl font-extrabold tracking-tight">
            Ready to explore MiniRedis?
          </h2>
          <p className="text-sm text-zinc-400 max-w-xl mx-auto">
            Package the executable JAR in under 30 seconds with Maven. Run the complete 145 automated test suite or connect your favorite Redis client.
          </p>
          <div className="flex flex-col sm:flex-row items-center justify-center gap-3 pt-2">
            <Link
              to="/docs"
              className="w-full sm:w-auto px-6 py-3 rounded-lg text-sm font-medium bg-brand-600 hover:bg-brand-500 text-white transition-colors"
            >
              Get Started with Docs
            </Link>
            <Link
              to="/downloads"
              className="w-full sm:w-auto px-6 py-3 rounded-lg text-sm font-medium bg-zinc-800 hover:bg-zinc-700 text-zinc-200 border border-zinc-700 transition-colors"
            >
              Download Instructions
            </Link>
          </div>
        </div>
      </section>
    </div>
  );
};
