import React, { useState, useEffect, useCallback } from 'react';
import { Database, HardDrive } from 'lucide-react';
import { SeoMeta } from '../components/common/SeoMeta';
import { ConnectionStatusBanner } from '../components/dashboard/ConnectionStatusBanner';
import { KpiCards } from '../components/dashboard/KpiCards';
import { TelemetryCharts } from '../components/dashboard/TelemetryCharts';
import { DatabaseExplorer } from '../components/dashboard/DatabaseExplorer';
import { CommandTerminal } from '../components/dashboard/CommandTerminal';
import { ServerInfo, KeyItem } from '../components/dashboard/types';
import { fetchInfo, fetchKeys } from '../components/dashboard/api';

export const DashboardPage: React.FC = () => {
  const [info, setInfo] = useState<ServerInfo | null>(null);
  const [keys, setKeys] = useState<KeyItem[]>([]);
  const [totalKeys, setTotalKeys] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [refreshInterval, setRefreshInterval] = useState(2000); // 2s default polling

  const [searchPattern, setSearchPattern] = useState('*');
  const [typeFilter, setTypeFilter] = useState('all');

  const [activeTab, setActiveTab] = useState<'all' | 'explorer' | 'terminal' | 'persistence'>('all');

  const loadData = useCallback(async (isSilent = false) => {
    if (!isSilent) setLoading(true);
    try {
      const [infoData, keysData] = await Promise.all([
        fetchInfo(),
        fetchKeys(searchPattern, typeFilter, 50, 0),
      ]);
      setInfo(infoData);
      setKeys(keysData.keys);
      setTotalKeys(keysData.totalKeys);
      setError(null);
      setLastUpdated(new Date());
    } catch (err: any) {
      setError(err.message || 'Failed to connect to MiniRedis backend');
      // Do not clear existing info entirely on a single transient glitch, but show offline banner
    } finally {
      if (!isSilent) setLoading(false);
    }
  }, [searchPattern, typeFilter]);

  // Initial load
  useEffect(() => {
    loadData(false);
  }, [loadData]);

  // Polling timer
  useEffect(() => {
    if (refreshInterval <= 0) return;
    const interval = setInterval(() => {
      loadData(true);
    }, refreshInterval);
    return () => clearInterval(interval);
  }, [refreshInterval, loadData]);

  return (
    <div className="min-h-screen bg-zinc-50 dark:bg-zinc-950 py-8 px-4 sm:px-6 lg:px-8 transition-colors">
      <SeoMeta
        title="Live Database Dashboard & Web Console | MiniRedis"
        description="Real-time telemetry, keyspace explorer, live throughput charts, and interactive RESP2 command terminal for the MiniRedis in-memory database engine."
      />

      <div className="max-w-7xl mx-auto">
        {/* Page Header */}
        <div className="flex flex-col md:flex-row md:items-center md:justify-between mb-6 pb-6 border-b border-zinc-200 dark:border-zinc-800 gap-4">
          <div>
            <div className="flex items-center space-x-2">
              <span className="p-2 rounded-xl bg-brand-500/10 text-brand-600 dark:text-brand-400">
                <Database className="w-6 h-6" />
              </span>
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-zinc-900 dark:text-zinc-100">
                  MiniRedis Live Management Console
                </h1>
                <p className="text-xs text-zinc-500 dark:text-zinc-400">
                  Real-time monitoring, live throughput analytics, keyspace inspection, and command terminal
                </p>
              </div>
            </div>
          </div>

          {/* Tab Navigation */}
          <div className="flex items-center bg-zinc-200/80 dark:bg-zinc-900 p-1 rounded-xl text-xs font-medium border border-zinc-300 dark:border-zinc-800">
            <button
              onClick={() => setActiveTab('all')}
              className={`px-3 py-1.5 rounded-lg transition-colors ${
                activeTab === 'all'
                  ? 'bg-white dark:bg-zinc-800 text-brand-600 dark:text-brand-400 shadow-sm'
                  : 'text-zinc-600 dark:text-zinc-400 hover:text-zinc-900 dark:hover:text-zinc-200'
              }`}
            >
              Full Overview
            </button>
            <button
              onClick={() => setActiveTab('explorer')}
              className={`px-3 py-1.5 rounded-lg transition-colors ${
                activeTab === 'explorer'
                  ? 'bg-white dark:bg-zinc-800 text-brand-600 dark:text-brand-400 shadow-sm'
                  : 'text-zinc-600 dark:text-zinc-400 hover:text-zinc-900 dark:hover:text-zinc-200'
              }`}
            >
              Keyspace Explorer
            </button>
            <button
              onClick={() => setActiveTab('terminal')}
              className={`px-3 py-1.5 rounded-lg transition-colors ${
                activeTab === 'terminal'
                  ? 'bg-white dark:bg-zinc-800 text-brand-600 dark:text-brand-400 shadow-sm'
                  : 'text-zinc-600 dark:text-zinc-400 hover:text-zinc-900 dark:hover:text-zinc-200'
              }`}
            >
              Command Terminal
            </button>
            <button
              onClick={() => setActiveTab('persistence')}
              className={`px-3 py-1.5 rounded-lg transition-colors ${
                activeTab === 'persistence'
                  ? 'bg-white dark:bg-zinc-800 text-brand-600 dark:text-brand-400 shadow-sm'
                  : 'text-zinc-600 dark:text-zinc-400 hover:text-zinc-900 dark:hover:text-zinc-200'
              }`}
            >
              Persistence & Concurrency
            </button>
          </div>
        </div>

        {/* Real-time Connection Status & Settings Banner */}
        <ConnectionStatusBanner
          info={info}
          loading={loading}
          error={error}
          lastUpdated={lastUpdated}
          refreshInterval={refreshInterval}
          setRefreshInterval={setRefreshInterval}
          onRefresh={() => loadData(false)}
        />

        {/* 6 Core KPI Metrics */}
        <KpiCards info={info} />

        {/* Dynamic Tab Views */}
        {activeTab === 'all' && (
          <>
            {/* Telemetry Charts */}
            <TelemetryCharts info={info} />

            {/* Keyspace Explorer */}
            <DatabaseExplorer
              keys={keys}
              totalKeys={totalKeys}
              loading={loading}
              searchPattern={searchPattern}
              setSearchPattern={setSearchPattern}
              typeFilter={typeFilter}
              setTypeFilter={setTypeFilter}
              onRefresh={() => loadData(false)}
            />

            {/* Interactive Terminal REPL */}
            <CommandTerminal
              demoMode={info?.demoMode ?? false}
              onCommandExecuted={() => loadData(true)}
            />
          </>
        )}

        {activeTab === 'explorer' && (
          <DatabaseExplorer
            keys={keys}
            totalKeys={totalKeys}
            loading={loading}
            searchPattern={searchPattern}
            setSearchPattern={setSearchPattern}
            typeFilter={typeFilter}
            setTypeFilter={setTypeFilter}
            onRefresh={() => loadData(false)}
          />
        )}

        {activeTab === 'terminal' && (
          <CommandTerminal
            demoMode={info?.demoMode ?? false}
            onCommandExecuted={() => loadData(true)}
          />
        )}

        {activeTab === 'persistence' && (
          <div className="space-y-6">
            <TelemetryCharts info={info} />
            <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl p-6 shadow-sm">
              <h3 className="text-base font-bold text-zinc-900 dark:text-zinc-100 mb-2 flex items-center space-x-2">
                <HardDrive className="w-5 h-5 text-brand-500" />
                <span>Append-Only File (AOF) Architecture & Crash Resilience</span>
              </h3>
              <p className="text-xs text-zinc-600 dark:text-zinc-400 mb-4 leading-relaxed">
                MiniRedis writes all mutating operations into <code className="font-mono bg-zinc-100 dark:bg-zinc-800 px-1 py-0.5 rounded text-brand-600 dark:text-brand-400">appendonly.aof</code> in Redis Serialization Protocol (RESP2) wire format. The durability engine guarantees linear recovery even after sudden process kills or power interruptions.
              </p>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs font-mono">
                <div className="p-4 rounded-lg bg-zinc-50 dark:bg-zinc-800/60 border border-zinc-200 dark:border-zinc-700/60">
                  <div className="font-bold text-zinc-900 dark:text-zinc-100 mb-1 font-sans">Fsync Policy: EVERYSEC</div>
                  <p className="text-[11px] text-zinc-500 font-sans">
                    A dedicated background worker thread executes an fsync system call once per second, achieving up to 138,000 writes/sec while limiting potential data loss window to under 1 second.
                  </p>
                </div>

                <div className="p-4 rounded-lg bg-zinc-50 dark:bg-zinc-800/60 border border-zinc-200 dark:border-zinc-700/60">
                  <div className="font-bold text-zinc-900 dark:text-zinc-100 mb-1 font-sans">BGREWRITEAOF Compaction</div>
                  <p className="text-[11px] text-zinc-500 font-sans">
                    Background compaction forks an in-memory snapshot and captures concurrent modifications in a Diff Buffer, shrinking multi-megabyte log files to minimum state in ~7 milliseconds.
                  </p>
                </div>

                <div className="p-4 rounded-lg bg-zinc-50 dark:bg-zinc-800/60 border border-zinc-200 dark:border-zinc-700/60">
                  <div className="font-bold text-zinc-900 dark:text-zinc-100 mb-1 font-sans">Corrupt Tail Truncation</div>
                  <p className="text-[11px] text-zinc-500 font-sans">
                    During crash recovery, incomplete trailing byte frames (caused by mid-write power failure) are automatically detected and cleanly truncated to preserve uncorrupted state.
                  </p>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
