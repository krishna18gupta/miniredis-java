import React from 'react';
import { Database, Lock, Shield, Server, Cpu, Clock, CheckCircle2 } from 'lucide-react';
import { SeoMeta } from '../components/common/SeoMeta';
import { CodeBlock } from '../components/common/CodeBlock';

export const FeaturesPage: React.FC = () => {
  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-10 space-y-16">
      <SeoMeta
        title="Core Features"
        description="Explore MiniRedis core features: strongly typed collections, 256-stripe deadlock-free concurrency, AOF persistence with compaction, RESP2 protocol, and optimistic CAS transactions."
      />

      {/* Header */}
      <div className="space-y-4 text-center max-w-3xl mx-auto">
        <span className="text-xs font-mono uppercase text-brand-500 font-semibold tracking-wider">
          Complete Feature Overview
        </span>
        <h1 className="text-3xl sm:text-5xl font-extrabold text-zinc-900 dark:text-zinc-50 tracking-tight">
          Engineered for Correctness & High Throughput
        </h1>
        <p className="text-base text-zinc-600 dark:text-zinc-300 leading-relaxed">
          MiniRedis implements 57 Redis commands across memory layouts, concurrency primitives, write-ahead logs, and socket networking — all built in pure Java 17.
        </p>
      </div>

      {/* Feature 1: Data Structures */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-6">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-lg bg-red-500/10 text-brand-500">
            <Database className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
              1. Strongly-Typed Data Structures & Type Safety
            </h2>
            <p className="text-xs text-zinc-500">Strings, Lists, Sets, and Hashes with Strict Redis Error Semantics</p>
          </div>
        </div>

        <p className="text-sm text-zinc-600 dark:text-zinc-300 leading-relaxed">
          MiniRedis strictly enforces the Redis type system. Every stored key has an associated <code className="text-xs font-mono text-brand-600 dark:text-brand-400">RedisDataType</code>. Attempting to execute list commands on a string or hash commands on a set immediately triggers the canonical Redis <code className="text-xs font-mono text-zinc-800 dark:text-zinc-200">WRONGTYPE Operation against a key holding the wrong kind of value</code> exception.
        </p>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60 space-y-2">
            <h3 className="font-bold text-zinc-900 dark:text-zinc-100">Zero Orphaned Collections</h3>
            <p className="text-zinc-500 dark:text-zinc-400">
              When elements are popped or removed until a list, set, or hash becomes empty, MiniRedis uses atomic <code className="font-mono">ConcurrentHashMap.compute()</code> semantics returning <code className="font-mono">null</code> to immediately excise the key from memory. No orphaned or zombie collections ever linger in RAM.
            </p>
          </div>

          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60 space-y-2">
            <h3 className="font-bold text-zinc-900 dark:text-zinc-100">Linearizable Read Snapshots</h3>
            <p className="text-zinc-500 dark:text-zinc-400">
              Multi-key queries such as <code className="font-mono">MGET</code>, <code className="font-mono">LRANGE</code>, <code className="font-mono">SMEMBERS</code>, and <code className="font-mono">HGETALL</code> take deep-copied snapshots under read locks, eliminating torn reads and <code className="font-mono">ConcurrentModificationException</code> during rapid concurrent mutations.
            </p>
          </div>
        </div>
      </section>

      {/* Feature 2: Concurrency */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-6">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-lg bg-amber-500/10 text-amber-500">
            <Lock className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
              2. 256-Stripe Concurrency Engine & Canonical Lock Ordering
            </h2>
            <p className="text-xs text-zinc-500">High Multi-Threaded Scalability with Zero Circular Deadlocks</p>
          </div>
        </div>

        <p className="text-sm text-zinc-600 dark:text-zinc-300 leading-relaxed">
          Standard Redis uses an event loop on a single thread. In high-core multicore environments, MiniRedis leverages a pool of 256 independent <code className="text-xs font-mono">ReentrantReadWriteLock</code> stripes (<code className="text-xs font-mono">StripedLockManager</code>). Disjoint keys map to different stripes and execute with zero thread contention, reaching up to <strong>1,479,629 ops/sec</strong> on read workloads.
        </p>

        <div className="p-4 rounded-xl border border-amber-500/20 bg-amber-500/5 text-xs space-y-2 text-zinc-700 dark:text-zinc-300">
          <div className="font-bold text-amber-600 dark:text-amber-400 flex items-center gap-1.5">
            <CheckCircle2 className="w-4 h-4" />
            Mathematical Deadlock Prevention
          </div>
          <p>
            When an operation requires access to multiple keys (e.g. <code className="font-mono">MSET</code>, <code className="font-mono">MGET</code>, <code className="font-mono">RENAME</code>, or <code className="font-mono">EXEC</code>), MiniRedis determines their stripe indices and sorts them in strictly ascending canonical order (<code className="font-mono">0 &rarr; 1 &rarr; ... &rarr; 255</code>) before acquiring write or read locks. This guarantees that circular wait deadlocks are mathematically impossible.
          </p>
        </div>
      </section>

      {/* Feature 3: Expiration */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-6">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-lg bg-cyan-500/10 text-cyan-500">
            <Clock className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
              3. Precision Dual-Model TTL & Expiration Subsystem
            </h2>
            <p className="text-xs text-zinc-500">Passive On-Access Checks Combined with Active Daemon Sweeps</p>
          </div>
        </div>

        <p className="text-sm text-zinc-600 dark:text-zinc-300 leading-relaxed">
          MiniRedis implements sub-millisecond precision expiration across all data types using a two-tier eviction model:
        </p>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60 space-y-2">
            <h3 className="font-bold text-zinc-900 dark:text-zinc-100">Tier 1: Passive (Lazy) Eviction</h3>
            <p className="text-zinc-500 dark:text-zinc-400">
              Every read or write access transparently queries the key expiration timestamp. If the expiration timestamp has passed, the key is evicted immediately and the client receives nil/zero.
            </p>
          </div>

          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60 space-y-2">
            <h3 className="font-bold text-zinc-900 dark:text-zinc-100">Tier 2: Active Background Sweeps</h3>
            <p className="text-zinc-500 dark:text-zinc-400">
              A background daemon thread wakes every 100ms, randomly sampling 20 keys with expirations. It deletes expired entries and repeats if more than 25% of the sampled keys were expired, ensuring RAM is reclaimed even for keys never queried again.
            </p>
          </div>
        </div>
      </section>

      {/* Feature 4: Persistence */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-6">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-lg bg-emerald-500/10 text-emerald-500">
            <Shield className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
              4. Append-Only File (AOF) Persistence & Compaction
            </h2>
            <p className="text-xs text-zinc-500">Durable Write-Ahead Logging with Non-Blocking Compaction</p>
          </div>
        </div>

        <p className="text-sm text-zinc-600 dark:text-zinc-300 leading-relaxed">
          The <code className="text-xs font-mono">AofManager</code> logs mutating commands in standard RESP2 format to <code className="text-xs font-mono">appendonly.aof</code>. It supports three fsync policies:
        </p>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 text-xs font-mono">
          <div className="p-3 rounded-lg border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60">
            <span className="font-bold text-emerald-600 dark:text-emerald-400">EVERYSEC (Default)</span>
            <p className="mt-1 font-sans text-zinc-500">Flushes to OS buffer; daemon fsyncs every 1000ms. Measured at <strong>138,488 op/s</strong>.</p>
          </div>
          <div className="p-3 rounded-lg border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60">
            <span className="font-bold text-blue-600 dark:text-blue-400">NO</span>
            <p className="mt-1 font-sans text-zinc-500">Leaves disk flushing to the OS kernel. High throughput with lowest write overhead.</p>
          </div>
          <div className="p-3 rounded-lg border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60">
            <span className="font-bold text-amber-600 dark:text-amber-400">ALWAYS</span>
            <p className="mt-1 font-sans text-zinc-500">Synchronous fsync on every single write. Maximum durability guarantee.</p>
          </div>
        </div>

        <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60 text-xs space-y-2 text-zinc-600 dark:text-zinc-400">
          <h4 className="font-bold text-zinc-900 dark:text-zinc-100">Timestamp Normalization & Compaction</h4>
          <p>
            When logging expiring keys, relative commands (e.g. <code className="font-mono">EXPIRE key 60</code>) are normalized into absolute epoch timestamps (<code className="font-mono">PEXPIREAT key &lt;timestamp&gt;</code>). Server reboots never accidentally grant expired keys a new lease of life.
          </p>
          <p>
            The <code className="font-mono">BGREWRITEAOF</code> engine dumps an in-memory snapshot to a temporary file while routing concurrent mutations into an in-memory diff buffer. When compaction finishes in milliseconds, the diff buffer is appended and the temporary file replaces the bloated log.
          </p>
        </div>
      </section>

      {/* Feature 5: Transactions */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-6">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-lg bg-purple-500/10 text-purple-500">
            <Cpu className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
              5. Optimistic CAS Transactions (`MULTI` / `EXEC` / `WATCH`)
            </h2>
            <p className="text-xs text-zinc-500">Compile-Time Gate (`EXECABORT`) and CAS Key Versioning</p>
          </div>
        </div>

        <p className="text-sm text-zinc-600 dark:text-zinc-300 leading-relaxed">
          MiniRedis implements full Redis transactional mechanics:
        </p>

        <CodeBlock
          code={`# Atomic Transaction with WATCH
WATCH balance:alice balance:bob
MULTI
DECRBY balance:alice 50
INCRBY balance:bob 50
EXEC
# If another client modified balance:alice or bob, EXEC aborts and returns nil (CAS)`}
          language="bash"
          title="redis-cli - Optimistic Concurrency Control"
        />

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs">
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60 space-y-1.5">
            <h3 className="font-bold text-zinc-900 dark:text-zinc-100">EXECABORT Compiler Gate</h3>
            <p className="text-zinc-500 dark:text-zinc-400">
              If a command has syntax errors or unknown tokens while being queued after MULTI, the transaction is marked as aborted. When EXEC is called, it refuses execution with <code className="font-mono">-EXECABORT</code>.
            </p>
          </div>
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60 space-y-1.5">
            <h3 className="font-bold text-zinc-900 dark:text-zinc-100">CAS Key Version Tracking</h3>
            <p className="text-zinc-500 dark:text-zinc-400">
              Every key has an atomic version incremented on mutation, deletion, or expiration. If any watched key's version changes prior to EXEC, the transaction cleanly yields <code className="font-mono">*-1</code>.
            </p>
          </div>
        </div>
      </section>

      {/* Feature 6: RESP2 */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-6">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-lg bg-blue-500/10 text-blue-500">
            <Server className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
              6. RESP2 Protocol Engine & Native Networking
            </h2>
            <p className="text-xs text-zinc-500">Standard Wire Framing, Pipelining, and Zero Third-Party Sockets</p>
          </div>
        </div>

        <p className="text-sm text-zinc-600 dark:text-zinc-300 leading-relaxed">
          The <code className="text-xs font-mono">RespParser</code> and <code className="text-xs font-mono">RespSerializer</code> implement standard RESP2 wire framing using pure Java socket I/O without Netty:
        </p>

        <div className="grid grid-cols-2 sm:grid-cols-5 gap-2 text-xs font-mono text-center">
          <div className="p-2.5 rounded border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950">
            <div className="text-brand-500 font-bold">+OK\r\n</div>
            <div className="text-[10px] text-zinc-400 mt-1">Simple String</div>
          </div>
          <div className="p-2.5 rounded border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950">
            <div className="text-red-500 font-bold">-ERR\r\n</div>
            <div className="text-[10px] text-zinc-400 mt-1">Error</div>
          </div>
          <div className="p-2.5 rounded border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950">
            <div className="text-purple-500 font-bold">:100\r\n</div>
            <div className="text-[10px] text-zinc-400 mt-1">Integer</div>
          </div>
          <div className="p-2.5 rounded border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950">
            <div className="text-emerald-500 font-bold">$5\r\nhello</div>
            <div className="text-[10px] text-zinc-400 mt-1">Bulk String</div>
          </div>
          <div className="p-2.5 rounded border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950">
            <div className="text-amber-500 font-bold">*-1\r\n</div>
            <div className="text-[10px] text-zinc-400 mt-1">Null Array</div>
          </div>
        </div>

        <p className="text-xs text-zinc-500 dark:text-zinc-400">
          MiniRedis explicitly preserves the distinction between Null Bulk Strings (<code className="font-mono">$-1\r\n</code>) and Empty Bulk Strings (<code className="font-mono">$0\r\n\r\n</code>), preventing null-pointer ambiguities in standard clients like Python's <code className="font-mono">redis-py</code> or Java's Jedis.
        </p>
      </section>
    </div>
  );
};
