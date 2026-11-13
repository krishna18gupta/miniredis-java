import React from 'react';
import { Database, Activity, Cpu, Layers, Clock, Zap } from 'lucide-react';
import { ServerInfo } from './types';

interface Props {
  info: ServerInfo | null;
}

export function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

export function formatUptime(seconds: number): string {
  if (!seconds || seconds <= 0) return '0s';
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);

  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m ${s}s`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

export const KpiCards: React.FC<Props> = ({ info }) => {
  const isOnline = !!info;
  const totalKeys = info?.keyspace.totalKeys ?? 0;
  const expiringKeys = info?.keyspace.expiringKeys ?? 0;
  const jvmUsed = info?.memory.jvmUsedBytes ?? 0;
  const jvmMax = info?.memory.jvmMaxBytes ?? 1;
  const dbBytes = info?.memory.estimatedDatabaseBytes ?? 0;
  const memoryPercent = Math.min(100, Math.round((jvmUsed / jvmMax) * 100));
  const opsSec = info?.operations.opsPerSecond ?? 0;
  const totalCompleted = info?.operations.totalCompleted ?? 0;
  const connectedClients = info?.clients.connectedClients ?? 0;
  const totalConns = info?.clients.totalConnectionsReceived ?? 0;
  const uptime = info?.uptimeSeconds ?? 0;
  const hitRate = info?.keyspace.hitRatePercent ?? 100;

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4 mb-6">
      {/* 1. Database Health */}
      <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl p-4 shadow-sm flex flex-col justify-between transition-colors">
        <div className="flex items-center justify-between text-zinc-500 dark:text-zinc-400 mb-2">
          <span className="text-xs font-medium uppercase tracking-wider">Engine Status</span>
          <div className="p-1.5 rounded-lg bg-emerald-50 dark:bg-emerald-950/60 text-emerald-600 dark:text-emerald-400">
            <Zap className="w-4 h-4" />
          </div>
        </div>
        <div>
          <div className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center space-x-1.5">
            <span className={`w-2.5 h-2.5 rounded-full ${isOnline ? 'bg-emerald-500 animate-pulse' : 'bg-rose-500'}`} />
            <span>{isOnline ? 'Active' : 'Offline'}</span>
          </div>
          <p className="text-[11px] text-zinc-500 dark:text-zinc-400 mt-1 flex items-center justify-between">
            <span>Protocol: RESP2</span>
            <span>Java 17</span>
          </p>
        </div>
      </div>

      {/* 2. Total Keys */}
      <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl p-4 shadow-sm flex flex-col justify-between transition-colors">
        <div className="flex items-center justify-between text-zinc-500 dark:text-zinc-400 mb-2">
          <span className="text-xs font-medium uppercase tracking-wider">Total Keys</span>
          <div className="p-1.5 rounded-lg bg-brand-50 dark:bg-brand-950/60 text-brand-600 dark:text-brand-400">
            <Database className="w-4 h-4" />
          </div>
        </div>
        <div>
          <div className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
            {totalKeys.toLocaleString()}
          </div>
          <div className="flex items-center justify-between text-[11px] text-zinc-500 dark:text-zinc-400 mt-1">
            <span>Expiring: {expiringKeys}</span>
            <span>Persistent: {totalKeys - expiringKeys}</span>
          </div>
        </div>
      </div>

      {/* 3. Memory Usage */}
      <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl p-4 shadow-sm flex flex-col justify-between transition-colors">
        <div className="flex items-center justify-between text-zinc-500 dark:text-zinc-400 mb-2">
          <span className="text-xs font-medium uppercase tracking-wider">JVM Memory</span>
          <div className="p-1.5 rounded-lg bg-purple-50 dark:bg-purple-950/60 text-purple-600 dark:text-purple-400">
            <Cpu className="w-4 h-4" />
          </div>
        </div>
        <div>
          <div className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
            {formatBytes(jvmUsed)}
          </div>
          <div className="w-full bg-zinc-100 dark:bg-zinc-800 rounded-full h-1.5 mt-1.5 overflow-hidden">
            <div
              className="bg-purple-500 h-1.5 rounded-full transition-all duration-500"
              style={{ width: `${Math.max(2, memoryPercent)}%` }}
            />
          </div>
          <p className="text-[11px] text-zinc-500 dark:text-zinc-400 mt-1 flex items-center justify-between">
            <span>Keyspace: {formatBytes(dbBytes)}</span>
            <span>{memoryPercent}%</span>
          </p>
        </div>
      </div>

      {/* 4. Operations / Sec */}
      <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl p-4 shadow-sm flex flex-col justify-between transition-colors">
        <div className="flex items-center justify-between text-zinc-500 dark:text-zinc-400 mb-2">
          <span className="text-xs font-medium uppercase tracking-wider">Throughput</span>
          <div className="p-1.5 rounded-lg bg-amber-50 dark:bg-amber-950/60 text-amber-600 dark:text-amber-400">
            <Activity className="w-4 h-4" />
          </div>
        </div>
        <div>
          <div className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
            {opsSec.toLocaleString()} <span className="text-xs font-normal text-zinc-500">op/s</span>
          </div>
          <p className="text-[11px] text-zinc-500 dark:text-zinc-400 mt-1 truncate">
            Total: {totalCompleted.toLocaleString()} ops
          </p>
        </div>
      </div>

      {/* 5. Clients & Locks */}
      <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl p-4 shadow-sm flex flex-col justify-between transition-colors">
        <div className="flex items-center justify-between text-zinc-500 dark:text-zinc-400 mb-2">
          <span className="text-xs font-medium uppercase tracking-wider">Clients & Locks</span>
          <div className="p-1.5 rounded-lg bg-sky-50 dark:bg-sky-950/60 text-sky-600 dark:text-sky-400">
            <Layers className="w-4 h-4" />
          </div>
        </div>
        <div>
          <div className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
            {connectedClients} <span className="text-xs font-normal text-zinc-500">active</span>
          </div>
          <p className="text-[11px] text-zinc-500 dark:text-zinc-400 mt-1 flex items-center justify-between">
            <span>256 Stripes</span>
            <span>Total: {totalConns}</span>
          </p>
        </div>
      </div>

      {/* 6. Uptime & Cache Hit Rate */}
      <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl p-4 shadow-sm flex flex-col justify-between transition-colors">
        <div className="flex items-center justify-between text-zinc-500 dark:text-zinc-400 mb-2">
          <span className="text-xs font-medium uppercase tracking-wider">Uptime & Hit Rate</span>
          <div className="p-1.5 rounded-lg bg-teal-50 dark:bg-teal-950/60 text-teal-600 dark:text-teal-400">
            <Clock className="w-4 h-4" />
          </div>
        </div>
        <div>
          <div className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
            {formatUptime(uptime)}
          </div>
          <p className="text-[11px] text-zinc-500 dark:text-zinc-400 mt-1 flex items-center justify-between">
            <span>Hit Rate:</span>
            <span className="font-semibold text-emerald-600 dark:text-emerald-400">{hitRate.toFixed(1)}%</span>
          </p>
        </div>
      </div>
    </div>
  );
};
