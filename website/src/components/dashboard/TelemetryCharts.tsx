import React from 'react';
import { HardDrive, Activity, PieChart, ShieldCheck } from 'lucide-react';
import { ServerInfo } from './types';
import { formatBytes } from './KpiCards';

interface Props {
  info: ServerInfo | null;
}

export const TelemetryCharts: React.FC<Props> = ({ info }) => {
  const telemetry = info?.telemetry || [];
  const keyspace = info?.keyspace;
  const aof = info?.aof;

  // Key counts
  const stringCount = keyspace?.stringKeys ?? 0;
  const listCount = keyspace?.listKeys ?? 0;
  const setCount = keyspace?.setKeys ?? 0;
  const hashCount = keyspace?.hashKeys ?? 0;
  const totalKeys = keyspace?.totalKeys ?? 0;

  // Proportions
  const stringPct = totalKeys > 0 ? (stringCount / totalKeys) * 100 : 0;
  const listPct = totalKeys > 0 ? (listCount / totalKeys) * 100 : 0;
  const setPct = totalKeys > 0 ? (setCount / totalKeys) * 100 : 0;
  const hashPct = totalKeys > 0 ? (hashCount / totalKeys) * 100 : 0;

  // Chart rendering for Ops/sec
  const maxOps = Math.max(10, ...telemetry.map((t) => t.ops));
  const maxJvmMb = Math.max(
    10,
    ...telemetry.map((t) => Math.round(t.jvm / (1024 * 1024)))
  );

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
      {/* 1. Real-Time Throughput Activity Chart */}
      <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl p-5 shadow-sm transition-colors">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center space-x-2">
            <div className="p-1.5 rounded-lg bg-amber-50 dark:bg-amber-950/60 text-amber-600 dark:text-amber-400">
              <Activity className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                Throughput Telemetry (Ops/sec)
              </h3>
              <p className="text-xs text-zinc-500 dark:text-zinc-400">
                Live engine command dispatch rate (2-second sampling)
              </p>
            </div>
          </div>
          <span className="text-xs font-mono font-medium px-2 py-0.5 rounded bg-zinc-100 dark:bg-zinc-800 text-zinc-700 dark:text-zinc-300">
            Peak: {maxOps.toLocaleString()} op/s
          </span>
        </div>

        {telemetry.length < 2 ? (
          <div className="h-44 flex flex-col items-center justify-center border border-dashed border-zinc-200 dark:border-zinc-800 rounded-lg text-zinc-400 text-xs">
            <Activity className="w-6 h-6 mb-1 text-zinc-300 dark:text-zinc-600 animate-pulse" />
            <span>Gathering live telemetry points...</span>
          </div>
        ) : (
          <div className="h-44 flex flex-col justify-between">
            <div className="relative h-36 w-full">
              <svg className="w-full h-full overflow-visible" preserveAspectRatio="none" viewBox="0 0 100 100">
                {/* Horizontal reference gridlines */}
                <line x1="0" y1="0" x2="100" y2="0" stroke="currentColor" strokeDasharray="3 3" className="text-zinc-200 dark:text-zinc-800" strokeWidth="0.5" />
                <line x1="0" y1="50" x2="100" y2="50" stroke="currentColor" strokeDasharray="3 3" className="text-zinc-200 dark:text-zinc-800" strokeWidth="0.5" />
                <line x1="0" y1="100" x2="100" y2="100" stroke="currentColor" className="text-zinc-200 dark:text-zinc-800" strokeWidth="0.5" />

                {/* Area Gradient Fill */}
                <defs>
                  <linearGradient id="opsGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#f59e0b" stopOpacity="0.3" />
                    <stop offset="100%" stopColor="#f59e0b" stopOpacity="0.0" />
                  </linearGradient>
                </defs>

                {/* Area under curve */}
                {(() => {
                  const pts = telemetry.map((t, idx) => {
                    const x = (idx / (telemetry.length - 1)) * 100;
                    const y = 100 - (t.ops / maxOps) * 90;
                    return `${x},${y}`;
                  });
                  return (
                    <polygon
                      points={`0,100 ${pts.join(' ')} 100,100`}
                      fill="url(#opsGradient)"
                    />
                  );
                })()}

                {/* Polyline Path */}
                {(() => {
                  const pts = telemetry.map((t, idx) => {
                    const x = (idx / (telemetry.length - 1)) * 100;
                    const y = 100 - (t.ops / maxOps) * 90;
                    return `${x},${y}`;
                  });
                  return (
                    <polyline
                      fill="none"
                      stroke="#f59e0b"
                      strokeWidth="2"
                      points={pts.join(' ')}
                    />
                  );
                })()}
              </svg>
            </div>
            <div className="flex justify-between text-[10px] text-zinc-400 font-mono mt-1">
              <span>{Math.round((telemetry.length * 2))}s ago</span>
              <span>100% live</span>
              <span>Now: {info?.operations.opsPerSecond ?? 0} op/s</span>
            </div>
          </div>
        )}
      </div>

      {/* 2. JVM Memory Usage Over Time */}
      <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl p-5 shadow-sm transition-colors">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center space-x-2">
            <div className="p-1.5 rounded-lg bg-purple-50 dark:bg-purple-950/60 text-purple-600 dark:text-purple-400">
              <HardDrive className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                JVM Heap Footprint (MB)
              </h3>
              <p className="text-xs text-zinc-500 dark:text-zinc-400">
                Allocated heap memory vs keyspace consumption
              </p>
            </div>
          </div>
          <span className="text-xs font-mono font-medium px-2 py-0.5 rounded bg-zinc-100 dark:bg-zinc-800 text-zinc-700 dark:text-zinc-300">
            Peak: {maxJvmMb} MB
          </span>
        </div>

        {telemetry.length < 2 ? (
          <div className="h-44 flex flex-col items-center justify-center border border-dashed border-zinc-200 dark:border-zinc-800 rounded-lg text-zinc-400 text-xs">
            <HardDrive className="w-6 h-6 mb-1 text-zinc-300 dark:text-zinc-600 animate-pulse" />
            <span>Tracking heap allocation...</span>
          </div>
        ) : (
          <div className="h-44 flex flex-col justify-between">
            <div className="relative h-36 w-full">
              <svg className="w-full h-full overflow-visible" preserveAspectRatio="none" viewBox="0 0 100 100">
                <line x1="0" y1="0" x2="100" y2="0" stroke="currentColor" strokeDasharray="3 3" className="text-zinc-200 dark:text-zinc-800" strokeWidth="0.5" />
                <line x1="0" y1="50" x2="100" y2="50" stroke="currentColor" strokeDasharray="3 3" className="text-zinc-200 dark:text-zinc-800" strokeWidth="0.5" />
                <line x1="0" y1="100" x2="100" y2="100" stroke="currentColor" className="text-zinc-200 dark:text-zinc-800" strokeWidth="0.5" />

                <defs>
                  <linearGradient id="memGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#a855f7" stopOpacity="0.3" />
                    <stop offset="100%" stopColor="#a855f7" stopOpacity="0.0" />
                  </linearGradient>
                </defs>

                {(() => {
                  const pts = telemetry.map((t, idx) => {
                    const mb = t.jvm / (1024 * 1024);
                    const x = (idx / (telemetry.length - 1)) * 100;
                    const y = 100 - (mb / maxJvmMb) * 90;
                    return `${x},${y}`;
                  });
                  return (
                    <polygon
                      points={`0,100 ${pts.join(' ')} 100,100`}
                      fill="url(#memGradient)"
                    />
                  );
                })()}

                {(() => {
                  const pts = telemetry.map((t, idx) => {
                    const mb = t.jvm / (1024 * 1024);
                    const x = (idx / (telemetry.length - 1)) * 100;
                    const y = 100 - (mb / maxJvmMb) * 90;
                    return `${x},${y}`;
                  });
                  return (
                    <polyline
                      fill="none"
                      stroke="#a855f7"
                      strokeWidth="2"
                      points={pts.join(' ')}
                    />
                  );
                })()}
              </svg>
            </div>
            <div className="flex justify-between text-[10px] text-zinc-400 font-mono mt-1">
              <span>{Math.round((telemetry.length * 2))}s ago</span>
              <span>Current: {formatBytes(info?.memory.jvmUsedBytes ?? 0)}</span>
              <span>Max: {formatBytes(info?.memory.jvmMaxBytes ?? 0)}</span>
            </div>
          </div>
        )}
      </div>

      {/* 3. Keyspace Data Type Distribution */}
      <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl p-5 shadow-sm transition-colors">
        <div className="flex items-center space-x-2 mb-4">
          <div className="p-1.5 rounded-lg bg-brand-50 dark:bg-brand-950/60 text-brand-600 dark:text-brand-400">
            <PieChart className="w-4 h-4" />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              Keyspace Type Distribution
            </h3>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Breakdown across strings, lists, sets, and hashes
            </p>
          </div>
        </div>

        {totalKeys === 0 ? (
          <div className="h-32 flex flex-col items-center justify-center border border-dashed border-zinc-200 dark:border-zinc-800 rounded-lg text-zinc-400 text-xs">
            <span>Database currently empty. Create a key in the Explorer or Terminal!</span>
          </div>
        ) : (
          <div>
            {/* Segmented Progress Bar */}
            <div className="w-full h-3 bg-zinc-100 dark:bg-zinc-800 rounded-full flex overflow-hidden mb-4">
              {stringPct > 0 && (
                <div style={{ width: `${stringPct}%` }} className="bg-emerald-500 transition-all" title={`Strings: ${stringCount} (${stringPct.toFixed(1)}%)`} />
              )}
              {listPct > 0 && (
                <div style={{ width: `${listPct}%` }} className="bg-sky-500 transition-all" title={`Lists: ${listCount} (${listPct.toFixed(1)}%)`} />
              )}
              {setPct > 0 && (
                <div style={{ width: `${setPct}%` }} className="bg-amber-500 transition-all" title={`Sets: ${setCount} (${setPct.toFixed(1)}%)`} />
              )}
              {hashPct > 0 && (
                <div style={{ width: `${hashPct}%` }} className="bg-purple-500 transition-all" title={`Hashes: ${hashCount} (${hashPct.toFixed(1)}%)`} />
              )}
            </div>

            {/* Badges Grid */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
              <div className="p-2.5 rounded-lg border border-emerald-200 dark:border-emerald-900/60 bg-emerald-50/50 dark:bg-emerald-950/20">
                <div className="flex items-center space-x-1.5 text-emerald-700 dark:text-emerald-400 font-medium">
                  <span className="w-2 h-2 rounded-full bg-emerald-500" />
                  <span>STRING</span>
                </div>
                <div className="text-lg font-bold text-zinc-900 dark:text-zinc-100 mt-1">{stringCount}</div>
                <div className="text-[10px] text-zinc-500 dark:text-zinc-400">{stringPct.toFixed(1)}%</div>
              </div>

              <div className="p-2.5 rounded-lg border border-sky-200 dark:border-sky-900/60 bg-sky-50/50 dark:bg-sky-950/20">
                <div className="flex items-center space-x-1.5 text-sky-700 dark:text-sky-400 font-medium">
                  <span className="w-2 h-2 rounded-full bg-sky-500" />
                  <span>LIST</span>
                </div>
                <div className="text-lg font-bold text-zinc-900 dark:text-zinc-100 mt-1">{listCount}</div>
                <div className="text-[10px] text-zinc-500 dark:text-zinc-400">{listPct.toFixed(1)}%</div>
              </div>

              <div className="p-2.5 rounded-lg border border-amber-200 dark:border-amber-900/60 bg-amber-50/50 dark:bg-amber-950/20">
                <div className="flex items-center space-x-1.5 text-amber-700 dark:text-amber-400 font-medium">
                  <span className="w-2 h-2 rounded-full bg-amber-500" />
                  <span>SET</span>
                </div>
                <div className="text-lg font-bold text-zinc-900 dark:text-zinc-100 mt-1">{setCount}</div>
                <div className="text-[10px] text-zinc-500 dark:text-zinc-400">{setPct.toFixed(1)}%</div>
              </div>

              <div className="p-2.5 rounded-lg border border-purple-200 dark:border-purple-900/60 bg-purple-50/50 dark:bg-purple-950/20">
                <div className="flex items-center space-x-1.5 text-purple-700 dark:text-purple-400 font-medium">
                  <span className="w-2 h-2 rounded-full bg-purple-500" />
                  <span>HASH</span>
                </div>
                <div className="text-lg font-bold text-zinc-900 dark:text-zinc-100 mt-1">{hashCount}</div>
                <div className="text-[10px] text-zinc-500 dark:text-zinc-400">{hashPct.toFixed(1)}%</div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* 4. AOF Persistence & Durability Telemetry */}
      <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl p-5 shadow-sm transition-colors">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center space-x-2">
            <div className="p-1.5 rounded-lg bg-teal-50 dark:bg-teal-950/60 text-teal-600 dark:text-teal-400">
              <ShieldCheck className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                AOF Persistence Durability
              </h3>
              <p className="text-xs text-zinc-500 dark:text-zinc-400">
                Write-ahead durability, fsync policies, and compaction
              </p>
            </div>
          </div>
          <span className="px-2 py-0.5 text-xs font-medium rounded-full bg-emerald-100 text-emerald-800 dark:bg-emerald-950/60 dark:text-emerald-300">
            {aof?.healthStatus || 'HEALTHY'}
          </span>
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3 text-xs">
          <div className="p-2.5 rounded-lg bg-zinc-50 dark:bg-zinc-800/60">
            <span className="text-[10px] text-zinc-500 uppercase">AOF File Size</span>
            <div className="text-base font-bold text-zinc-900 dark:text-zinc-100 mt-0.5">
              {formatBytes(aof?.fileSize ?? 0)}
            </div>
            <span className="text-[10px] text-zinc-400">appendonly.aof</span>
          </div>

          <div className="p-2.5 rounded-lg bg-zinc-50 dark:bg-zinc-800/60">
            <span className="text-[10px] text-zinc-500 uppercase">Fsync Policy</span>
            <div className="text-base font-bold text-zinc-900 dark:text-zinc-100 mt-0.5 font-mono">
              {aof?.fsyncPolicy || 'EVERYSEC'}
            </div>
            <span className="text-[10px] text-zinc-400">1s Background Worker</span>
          </div>

          <div className="p-2.5 rounded-lg bg-zinc-50 dark:bg-zinc-800/60">
            <span className="text-[10px] text-zinc-500 uppercase">Logged Commands</span>
            <div className="text-base font-bold text-zinc-900 dark:text-zinc-100 mt-0.5">
              {(aof?.totalLoggedCommands ?? 0).toLocaleString()}
            </div>
            <span className="text-[10px] text-zinc-400">Total durable ops</span>
          </div>

          <div className="p-2.5 rounded-lg bg-zinc-50 dark:bg-zinc-800/60">
            <span className="text-[10px] text-zinc-500 uppercase">Total Fsyncs</span>
            <div className="text-base font-bold text-zinc-900 dark:text-zinc-100 mt-0.5">
              {(aof?.totalFsyncs ?? 0).toLocaleString()}
            </div>
            <span className="text-[10px] text-zinc-400">Kernel fsync flushes</span>
          </div>

          <div className="p-2.5 rounded-lg bg-zinc-50 dark:bg-zinc-800/60">
            <span className="text-[10px] text-zinc-500 uppercase">Compaction (BGREWRITE)</span>
            <div className="text-base font-bold text-zinc-900 dark:text-zinc-100 mt-0.5">
              {aof?.isRewriting ? 'Active' : 'Idle'}
            </div>
            <span className="text-[10px] text-zinc-400">Diff buffer enabled</span>
          </div>

          <div className="p-2.5 rounded-lg bg-zinc-50 dark:bg-zinc-800/60">
            <span className="text-[10px] text-zinc-500 uppercase">Recovery Duration</span>
            <div className="text-base font-bold text-zinc-900 dark:text-zinc-100 mt-0.5">
              {aof?.recoveryDurationMs ?? 0} ms
            </div>
            <span className="text-[10px] text-zinc-400">Zero corrupt frames</span>
          </div>
        </div>
      </div>
    </div>
  );
};
