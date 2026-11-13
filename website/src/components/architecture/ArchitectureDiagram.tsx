import React, { useState } from 'react';
import { Network, Database, Lock, Clock, FileText, Cpu, CheckCircle } from 'lucide-react';

interface LayerInfo {
  id: string;
  name: string;
  icon: React.ReactNode;
  components: string[];
  description: string;
  color: string;
}

const LAYERS: LayerInfo[] = [
  {
    id: 'network',
    name: '1. Networking & RESP2 Protocol Ingestion',
    icon: <Network className="w-5 h-5 text-blue-400" />,
    components: ['RedisServer (TCP:6379)', 'RespParser / RespSerializer', 'MiniRedisWebServer (HTTP:8080)', 'RedisClientHandler'],
    description: 'Accepts persistent socket connections, parses UTF-8 CRLF delimited RESP2 framing, handles pipelining, and multiplexes client I/O using a bounded cached thread pool.',
    color: 'border-blue-500/30 bg-blue-500/5'
  },
  {
    id: 'dispatch',
    name: '2. Command Dispatch & Transactions',
    icon: <Cpu className="w-5 h-5 text-purple-400" />,
    components: ['CommandRegistry (57 Commands)', 'TransactionContext (MULTI/EXEC)', 'Optimistic CAS Versioning (WATCH)', 'EXECABORT Compiler Gate'],
    description: 'Routes incoming tokens to command handlers in O(1). Handles transactional queueing under MULTI, marks compile-time errors for EXECABORT, and tracks key modification versions for WATCH.',
    color: 'border-purple-500/30 bg-purple-500/5'
  },
  {
    id: 'concurrency',
    name: '3. 256-Stripe Concurrency Control',
    icon: <Lock className="w-5 h-5 text-amber-400" />,
    components: ['StripedLockManager (256 RW Locks)', 'Canonical Ascending Lock Ordering', 'Deep-Copy Linearizable Snapshots', 'Deadlock Elimination Engine'],
    description: 'Distributes key hashes across 256 independent ReentrantReadWriteLock stripes. Multi-key operations (MSET, MGET, RENAME, EXEC) sort lock stripe indices in ascending order (0 -> 255), making deadlocks mathematically impossible.',
    color: 'border-amber-500/30 bg-amber-500/5'
  },
  {
    id: 'storage',
    name: '4. In-Memory Data Storage Engine',
    icon: <Database className="w-5 h-5 text-emerald-400" />,
    components: ['ConcurrentHashMap Storage', 'Strings & 64-bit Numeric INCR/DECR', 'Lists (Deque / LinkedList)', 'Sets (HashSet)', 'Hashes (HashMap)', 'Strict WRONGTYPE Enforcement'],
    description: 'Provides strongly-typed in-memory collections. Uses atomic ConcurrentHashMap.compute() semantics to delete keys immediately when collections become empty, eliminating orphaned zombie keys.',
    color: 'border-emerald-500/30 bg-emerald-500/5'
  },
  {
    id: 'eviction',
    name: '5. Millisecond Expiration Subsystem',
    icon: <Clock className="w-5 h-5 text-cyan-400" />,
    components: ['ExpirationManager', 'Passive Lazy Eviction (On-Access)', 'Active 100ms Daemon Sweep', 'Probabilistic Stop Loop (<25% threshold)'],
    description: 'Manages sub-millisecond TTLs. Intercepts expired keys on read/write (lazy) and runs an adaptive 100ms daemon thread that randomly samples 20 keys with expirations, looping until fewer than 25% are expired.',
    color: 'border-cyan-500/30 bg-cyan-500/5'
  },
  {
    id: 'persistence',
    name: '6. Durability & AOF Persistence',
    icon: <FileText className="w-5 h-5 text-brand-400" />,
    components: ['AofManager (Write-Ahead Log)', 'Fsync Policies (ALWAYS / EVERYSEC / NO)', 'Absolute Timestamp Normalization (PEXPIREAT)', 'BGREWRITEAOF with Live Diff Buffer', 'Crash Recovery & Corrupt Frame Truncation'],
    description: 'Logs mutating commands to appendonly.aof in RESP2 format. Converts relative TTLs to absolute epoch timestamps. Supports non-blocking background compaction with diff buffering and auto-truncates trailing corrupted frames on boot.',
    color: 'border-brand-500/30 bg-brand-500/5'
  }
];

export const ArchitectureDiagram: React.FC = () => {
  const [selectedLayer, setSelectedLayer] = useState<string>('concurrency');

  const active = LAYERS.find(l => l.id === selectedLayer) || LAYERS[0];

  return (
    <div className="rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/50 p-6 shadow-sm">
      <div className="flex flex-col md:flex-row items-start md:items-center justify-between pb-6 border-b border-zinc-200 dark:border-zinc-800 gap-4">
        <div>
          <h3 className="text-lg font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
            <Database className="w-5 h-5 text-brand-500" />
            MiniRedis Layered Architecture
          </h3>
          <p className="text-xs text-zinc-500 dark:text-zinc-400 mt-1">
            Click any layer to inspect internal components and execution mechanics.
          </p>
        </div>
        <div className="flex items-center gap-2 text-xs font-mono text-zinc-500 bg-zinc-100 dark:bg-zinc-800 px-3 py-1.5 rounded-lg">
          <CheckCircle className="w-3.5 h-3.5 text-emerald-500" />
          <span>Zero External Runtime Dependencies</span>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 mt-6">
        {/* Layer Selectors (Left side) */}
        <div className="lg:col-span-6 space-y-2.5">
          {LAYERS.map((layer) => {
            const isSelected = layer.id === selectedLayer;
            return (
              <div
                key={layer.id}
                onClick={() => setSelectedLayer(layer.id)}
                className={`p-3.5 rounded-lg border cursor-pointer transition-all duration-150 ${
                  isSelected
                    ? `${layer.color} shadow-sm ring-1 ring-brand-500/50`
                    : 'border-zinc-200 dark:border-zinc-800 hover:border-zinc-300 dark:hover:border-zinc-700 bg-zinc-50/50 dark:bg-zinc-900/20'
                }`}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center space-x-2.5">
                    {layer.icon}
                    <span className="text-sm font-semibold text-zinc-800 dark:text-zinc-200">
                      {layer.name}
                    </span>
                  </div>
                  <span className={`text-[10px] font-mono uppercase px-2 py-0.5 rounded ${
                    isSelected ? 'bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900' : 'text-zinc-400'
                  }`}>
                    {isSelected ? 'Viewing' : 'Inspect'}
                  </span>
                </div>
                <div className="flex flex-wrap gap-1.5 mt-2.5">
                  {layer.components.slice(0, 3).map((comp, idx) => (
                    <span
                      key={idx}
                      className="text-[11px] font-mono px-2 py-0.5 rounded bg-zinc-200/60 dark:bg-zinc-800/80 text-zinc-700 dark:text-zinc-300"
                    >
                      {comp.split(' ')[0]}
                    </span>
                  ))}
                  {layer.components.length > 3 && (
                    <span className="text-[11px] font-mono px-1.5 py-0.5 text-zinc-400">
                      +{layer.components.length - 3} more
                    </span>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        {/* Detailed Inspector View (Right side) */}
        <div className="lg:col-span-6 flex flex-col justify-between rounded-lg border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950 p-5">
          <div>
            <div className="flex items-center space-x-2 mb-3">
              {active.icon}
              <h4 className="font-bold text-base text-zinc-900 dark:text-zinc-100">
                {active.name}
              </h4>
            </div>

            <p className="text-sm text-zinc-600 dark:text-zinc-300 leading-relaxed mb-5">
              {active.description}
            </p>

            <h5 className="text-xs uppercase font-semibold tracking-wider text-zinc-400 mb-2.5">
              Key Components & Classes
            </h5>
            <div className="space-y-2">
              {active.components.map((comp, idx) => (
                <div
                  key={idx}
                  className="flex items-center space-x-2 p-2 rounded bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 text-xs font-mono text-zinc-800 dark:text-zinc-200"
                >
                  <span className="w-1.5 h-1.5 rounded-full bg-brand-500"></span>
                  <span>{comp}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="mt-6 pt-4 border-t border-zinc-200 dark:border-zinc-800/80 flex items-center justify-between text-xs text-zinc-500 font-mono">
            <span>Verified in Phase 10 Audit</span>
            <span>Target: Java 17+</span>
          </div>
        </div>
      </div>
    </div>
  );
};
