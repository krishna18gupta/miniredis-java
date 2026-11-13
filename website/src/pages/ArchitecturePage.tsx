import React from 'react';
import { SeoMeta } from '../components/common/SeoMeta';
import { ArchitectureDiagram } from '../components/architecture/ArchitectureDiagram';
import { CodeBlock } from '../components/common/CodeBlock';
import { Database, Lock, ShieldCheck, Cpu, GitBranch, Layers } from 'lucide-react';

export const ArchitecturePage: React.FC = () => {
  const concurrencySnippet = `// In StripedLockManager.java:
// Canonical sort prevents circular wait deadlocks across multi-key operations
public List<ReentrantReadWriteLock.WriteLock> writeLocks(Collection<String> keys) {
    return keys.stream()
        .map(this::stripeIndex)
        .distinct()
        .sorted() // ASCENDING CANONICAL STRIPE ORDER (0 -> 255)
        .map(idx -> locks[idx].writeLock())
        .collect(Collectors.toList());
}`;

  const executionFlowSnippet = `TCP Client Socket (Port 6379)
  │ (Raw UTF-8 Bytes)
  ▼
[ RedisClientHandler ] 
  │ (Socket Stream Reader)
  ▼
[ RespParser ] ──> Parses RESP2 Arrays, Bulk Strings, Framing
  │ (Parsed Command Tokens: List<String>)
  ▼
[ CommandRegistry ] 
  ├──> If in MULTI block: Validates syntax, queues in TransactionContext, returns +QUEUED
  └──> If standard command:
         │
         ▼
       [ StripedLockManager ]
         │ Maps keys to stripes; acquires readLocks or writeLocks in canonical order
         ▼
       [ StorageEngine ]
         │ Executes operation on ConcurrentHashMap data structures
         │ Updates key mutation version & telemetry metrics
         ▼
       [ RespSerializer ] ──> Serializes RedisResponse into RESP2 byte format
         │
         ▼
       Socket Output Stream (Flushes +OK, $Bulk, :Integer, or *Array)`;

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-10 space-y-16">
      <SeoMeta
        title="Architecture & Engineering"
        description="Comprehensive technical deep dive into MiniRedis database architecture: 256-stripe concurrency control, canonical deadlock prevention, AOF WAL compaction, and RESP2 protocol parsing."
      />

      {/* Header */}
      <div className="space-y-4 text-center max-w-3xl mx-auto">
        <span className="text-xs font-mono uppercase text-brand-500 font-semibold tracking-wider">
          System Architecture
        </span>
        <h1 className="text-3xl sm:text-5xl font-extrabold text-zinc-900 dark:text-zinc-50 tracking-tight">
          How MiniRedis Works Under the Hood
        </h1>
        <p className="text-base text-zinc-600 dark:text-zinc-300 leading-relaxed">
          Designed without black-box frameworks. Every layer — from socket I/O to lock striping and durability — is engineered in standard Java 17 for deterministic performance.
        </p>
      </div>

      {/* Interactive Layer Diagram */}
      <section className="space-y-4">
        <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
          <Layers className="w-5 h-5 text-brand-500" />
          Interactive System Overview
        </h2>
        <ArchitectureDiagram />
      </section>

      {/* Execution Flow */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-6">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-lg bg-blue-500/10 text-blue-500">
            <GitBranch className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
              Command Execution Pipeline
            </h2>
            <p className="text-xs text-zinc-500">From Raw Socket Bytes to In-Memory Mutation & Wire Response</p>
          </div>
        </div>

        <p className="text-sm text-zinc-600 dark:text-zinc-300 leading-relaxed">
          MiniRedis connects clients via multithreaded non-blocking TCP socket handlers. The diagram below illustrates how an incoming wire command traverses each subsystem:
        </p>

        <CodeBlock
          code={executionFlowSnippet}
          language="text"
          title="MiniRedis Pipeline Architecture"
        />
      </section>

      {/* Concurrency Deep Dive */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-6">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-lg bg-amber-500/10 text-amber-500">
            <Lock className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
              Deadlock-Free 256-Stripe Concurrency Control
            </h2>
            <p className="text-xs text-zinc-500">Eliminating the Global Synchronization Bottleneck</p>
          </div>
        </div>

        <p className="text-sm text-zinc-600 dark:text-zinc-300 leading-relaxed">
          In typical single-lock in-memory databases, concurrent writes suffer severe lock convoying. MiniRedis implements a 256-stripe <code className="text-xs font-mono">StripedLockManager</code> where key hashes map onto an array of 256 independent <code className="text-xs font-mono">ReentrantReadWriteLock</code> instances.
        </p>

        <div className="p-4 rounded-xl border border-amber-500/20 bg-amber-500/5 text-xs text-zinc-700 dark:text-zinc-300 space-y-2">
          <div className="font-bold text-amber-600 dark:text-amber-400 flex items-center gap-1.5">
            <ShieldCheck className="w-4 h-4" />
            Canonical Ascending Lock Ordering
          </div>
          <p>
            When a multi-key command (such as <code className="font-mono">MSET</code>, <code className="font-mono">MGET</code>, <code className="font-mono">RENAME</code>, or <code className="font-mono">EXEC</code>) must lock multiple keys across different stripes, acquiring them in arbitrary order can produce classic circular wait deadlocks (e.g. Thread 1 locks Stripe 4 &rarr; 12, while Thread 2 locks Stripe 12 &rarr; 4).
          </p>
          <p>
            MiniRedis prevents this by collecting all distinct stripe indices for the requested keys and <strong>sorting them strictly in ascending numerical order</strong> before acquisition. Because all threads acquire locks in the exact same order, circular wait cycles are mathematically eliminated.
          </p>
        </div>

        <CodeBlock
          code={concurrencySnippet}
          language="java"
          title="StripedLockManager.java - Canonical Sorting Implementation"
        />
      </section>

      {/* Storage Engine Deep Dive */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-6">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-lg bg-emerald-500/10 text-emerald-500">
            <Database className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
              In-Memory Memory Layout & Clean Collection Eviction
            </h2>
            <p className="text-xs text-zinc-500">Atomic compute() Semantics and Deep-Copy Isolation</p>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6 text-xs text-zinc-600 dark:text-zinc-400 leading-relaxed">
          <div className="p-5 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60 space-y-2">
            <h3 className="font-bold text-sm text-zinc-900 dark:text-zinc-100">Zero Orphaned Collections</h3>
            <p>
              In Redis, an empty list, set, or hash is deleted from the keyspace. In naive concurrent Java implementations, removing the last element leaves an empty wrapper object in the parent map, creating memory leaks.
            </p>
            <p>
              MiniRedis performs all collection updates inside <code className="font-mono">ConcurrentHashMap.compute()</code>. When a collection’s size drops to zero, the lambda returns <code className="font-mono">null</code>, which atomically removes the key from the hash table without race conditions.
            </p>
          </div>

          <div className="p-5 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/60 space-y-2">
            <h3 className="font-bold text-sm text-zinc-900 dark:text-zinc-100">Snapshot Isolation for Multi-Key Reads</h3>
            <p>
              When a client executes <code className="font-mono">MGET</code>, reading values sequentially without locking allows concurrent <code className="font-mono">INCRBY</code> or <code className="font-mono">MSET</code> writes to produce torn or non-linearizable states.
            </p>
            <p>
              MiniRedis acquires read locks across all target stripes simultaneously, takes defensive deep copies via <code className="font-mono">RedisValue.deepCopy()</code>, and releases the locks before writing the RESP2 payload to the socket, guaranteeing linearizable reads.
            </p>
          </div>
        </div>
      </section>

      {/* Persistence Architecture */}
      <section className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 space-y-6">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-lg bg-red-500/10 text-brand-500">
            <Cpu className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
              AOF Durability, Background Rewrite & Crash Recovery
            </h2>
            <p className="text-xs text-zinc-500">Write-Ahead Logging with Dual-Buffer Compaction</p>
          </div>
        </div>

        <div className="space-y-4 text-xs text-zinc-600 dark:text-zinc-300 leading-relaxed">
          <p>
            The MiniRedis persistence engine (<code className="font-mono">AofManager</code>) provides true crash survivability:
          </p>
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <strong>Write-Ahead Logging</strong>: All mutating operations are written to disk as RESP2 commands. On startup, replaying this stream fully reconstructs Strings, Lists, Sets, Hashes, and TTL states.
            </li>
            <li>
              <strong>Timestamp Normalization</strong>: When logging an expiring key, MiniRedis converts relative offsets (<code className="font-mono">EXPIRE key 60</code>) to absolute unix milliseconds (<code className="font-mono">PEXPIREAT key 1700000060000</code>), ensuring replay does not grant unearned extra lifetime to keys after a restart.
            </li>
            <li>
              <strong>Non-Blocking BGREWRITEAOF</strong>: During compaction, state is serialized to a temporary file while incoming live writes are buffered into an in-memory diff buffer. When the base dump completes, the diff buffer is appended and atomically renamed over the bloated file. In Phase 10 benchmarks, this achieved a <strong>100% reduction in file size in 7 ms</strong>.
            </li>
            <li>
              <strong>Crash Auto-Truncation</strong>: If power is cut mid-write, the final AOF frame may be truncated. Upon reboot, the parser detects the incomplete framing, safely truncates the corrupted bytes, and boots cleanly without data corruption.
            </li>
          </ul>
        </div>
      </section>
    </div>
  );
};
