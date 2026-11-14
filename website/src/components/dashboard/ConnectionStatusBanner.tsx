import React, { useState } from 'react';
import { RefreshCw, CheckCircle2, ShieldCheck, ShieldAlert, Globe, Settings } from 'lucide-react';
import { ServerInfo } from './types';
import { getBackendUrl, setBackendUrl, getAdminToken, setAdminToken } from './api';

interface Props {
  info: ServerInfo | null;
  loading: boolean;
  error: string | null;
  lastUpdated: Date | null;
  refreshInterval: number;
  setRefreshInterval: (interval: number) => void;
  onRefresh: () => void;
}

export const ConnectionStatusBanner: React.FC<Props> = ({
  info,
  loading,
  error,
  lastUpdated,
  refreshInterval,
  setRefreshInterval,
  onRefresh,
}) => {
  const [showConfig, setShowConfig] = useState(false);
  const [urlInput, setUrlInput] = useState(getBackendUrl());
  const [tokenInput, setTokenInput] = useState(getAdminToken());
  const [saveSuccess, setSaveSuccess] = useState(false);

  const handleSaveConfig = (e: React.FormEvent) => {
    e.preventDefault();
    setBackendUrl(urlInput);
    setAdminToken(tokenInput);
    setSaveSuccess(true);
    setTimeout(() => setSaveSuccess(false), 2000);
    setShowConfig(false);
    onRefresh();
  };

  const isConnected = !!info && !error;

  return (
    <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl p-4 shadow-sm mb-6 transition-colors">
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
        {/* Status Indicator */}
        <div className="flex items-center space-x-3">
          <div className="relative">
            {isConnected ? (
              <span className="flex h-3.5 w-3.5 relative">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-3.5 w-3.5 bg-emerald-500"></span>
              </span>
            ) : (
              <span className="flex h-3.5 w-3.5 relative">
                <span className="relative inline-flex rounded-full h-3.5 w-3.5 bg-rose-500"></span>
              </span>
            )}
          </div>

          <div>
            <div className="flex items-center space-x-2">
              <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
                {isConnected ? 'MiniRedis Engine Online' : 'Database Offline / Connecting'}
              </h2>
              {isConnected && (
                <span className="px-2 py-0.5 text-xs font-medium rounded-full bg-emerald-100 text-emerald-800 dark:bg-emerald-950/60 dark:text-emerald-300 border border-emerald-200 dark:border-emerald-800">
                  v{info.version}
                </span>
              )}
              {isConnected && info.demoMode && (
                <span className="flex items-center space-x-1 px-2 py-0.5 text-xs font-medium rounded-full bg-amber-100 text-amber-800 dark:bg-amber-950/60 dark:text-amber-300 border border-amber-200 dark:border-amber-800" title="Visitor isolation active. System demo keys are immutable.">
                  <ShieldAlert className="w-3 h-3" />
                  <span>Public Demo</span>
                </span>
              )}
              {isConnected && !info.demoMode && (
                <span className="flex items-center space-x-1 px-2 py-0.5 text-xs font-medium rounded-full bg-blue-100 text-blue-800 dark:bg-blue-950/60 dark:text-blue-300 border border-blue-200 dark:border-blue-800">
                  <ShieldCheck className="w-3 h-3" />
                  <span>Admin Instance</span>
                </span>
              )}
            </div>
            <p className="text-xs text-zinc-500 dark:text-zinc-400 mt-0.5 flex flex-wrap items-center gap-1.5">
              <span className="font-medium text-amber-600 dark:text-amber-400">Public Cloud Demo (Ephemeral Durability • In-Memory Core)</span>
              <span>•</span>
              <span>Backend:</span>
              <code className="text-brand-600 dark:text-brand-400 font-mono">{getBackendUrl()}</code>
              {lastUpdated && isConnected && (
                <>
                  <span>•</span>
                  <span>Last sync: {lastUpdated.toLocaleTimeString()}</span>
                </>
              )}
            </p>
          </div>
        </div>

        {/* Right Action Controls */}
        <div className="flex flex-wrap items-center gap-2">
          {/* Refresh interval selector */}
          <div className="flex items-center bg-zinc-100 dark:bg-zinc-800 rounded-lg p-1 text-xs">
            <span className="px-2 text-zinc-500 dark:text-zinc-400">Polling:</span>
            {[
              { label: '2s', val: 2000 },
              { label: '5s', val: 5000 },
              { label: '10s', val: 10000 },
              { label: 'Off', val: 0 },
            ].map(({ label, val }) => (
              <button
                key={label}
                onClick={() => setRefreshInterval(val)}
                className={`px-2 py-1 rounded text-xs font-medium transition-colors ${
                  refreshInterval === val
                    ? 'bg-white dark:bg-zinc-700 text-brand-600 dark:text-brand-400 shadow-sm'
                    : 'text-zinc-600 dark:text-zinc-400 hover:text-zinc-900 dark:hover:text-zinc-200'
                }`}
              >
                {label}
              </button>
            ))}
          </div>

          {/* Manual Refresh Button */}
          <button
            onClick={onRefresh}
            disabled={loading}
            aria-label="Refresh telemetry"
            className="p-2 rounded-lg border border-zinc-200 dark:border-zinc-700 hover:bg-zinc-100 dark:hover:bg-zinc-800 text-zinc-700 dark:text-zinc-300 disabled:opacity-50 transition-colors"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin text-brand-500' : ''}`} />
          </button>

          {/* Configure Endpoint Button */}
          <button
            onClick={() => setShowConfig(!showConfig)}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg border border-zinc-200 dark:border-zinc-700 hover:bg-zinc-100 dark:hover:bg-zinc-800 text-zinc-700 dark:text-zinc-300 text-xs font-medium transition-colors"
          >
            <Settings className="w-3.5 h-3.5" />
            <span>Connection</span>
          </button>
        </div>
      </div>

      {/* Disconnected Error Alert with Cold-Start Detection */}
      {!isConnected && (
        <div className="mt-4 p-3.5 bg-amber-50 dark:bg-amber-950/40 border border-amber-200 dark:border-amber-900 rounded-lg flex items-start space-x-3 text-xs text-amber-900 dark:text-amber-200 shadow-sm">
          <RefreshCw className="w-4 h-4 flex-shrink-0 text-amber-600 dark:text-amber-400 mt-0.5 animate-spin" />
          <div className="flex-grow space-y-1">
            <div className="flex items-center space-x-2">
              <span className="font-semibold text-amber-950 dark:text-amber-100">
                Render Free backend is waking up (cold start ~50s)...
              </span>
              <span className="px-1.5 py-0.2 rounded text-[10px] bg-amber-200/70 dark:bg-amber-800/60 font-medium">
                Auto-connecting
              </span>
            </div>
            <p className="text-amber-800 dark:text-amber-300">
              Free cloud containers spin down after 15 minutes of inactivity. MiniRedis is automatically booting in the background and will connect shortly. Telemetry and terminal will activate as soon as the health check passes.
            </p>
            <p className="text-[11px] text-zinc-500 dark:text-zinc-400">
              Target REST API: <code className="font-mono bg-amber-100/80 dark:bg-amber-900/60 px-1 py-0.5 rounded text-amber-950 dark:text-amber-200">{getBackendUrl()}</code>
            </p>
          </div>
          <button
            onClick={onRefresh}
            disabled={loading}
            className="px-3 py-1.5 bg-amber-600 hover:bg-amber-700 text-white font-medium rounded-lg transition-colors flex-shrink-0 shadow-sm disabled:opacity-50"
          >
            {loading ? 'Checking...' : 'Check Now'}
          </button>
        </div>
      )}

      {/* Configuration Drawer */}
      {showConfig && (
        <form onSubmit={handleSaveConfig} className="mt-4 pt-4 border-t border-zinc-200 dark:border-zinc-800 grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label className="block text-xs font-medium text-zinc-700 dark:text-zinc-300 mb-1">
              Backend REST API URL
            </label>
            <div className="relative">
              <Globe className="w-4 h-4 absolute left-3 top-2.5 text-zinc-400" />
              <input
                type="text"
                value={urlInput}
                onChange={(e) => setUrlInput(e.target.value)}
                placeholder="http://localhost:8080 or https://api.yourdomain.com"
                className="w-full pl-9 pr-3 py-1.5 text-xs rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 text-zinc-900 dark:text-zinc-100 focus:outline-none focus:ring-2 focus:ring-brand-500 font-mono"
              />
            </div>
            <p className="text-[10px] text-zinc-500 mt-1">Default local port is 8080. Enter your Cloudflare Tunnel / Oracle VM URL for cloud deployments.</p>
          </div>

          <div>
            <label className="block text-xs font-medium text-zinc-700 dark:text-zinc-300 mb-1">
              Admin Bearer Token (Optional)
            </label>
            <input
              type="password"
              value={tokenInput}
              onChange={(e) => setTokenInput(e.target.value)}
              placeholder="Leave empty for public demo access"
              className="w-full px-3 py-1.5 text-xs rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 text-zinc-900 dark:text-zinc-100 focus:outline-none focus:ring-2 focus:ring-brand-500 font-mono"
            />
            <p className="text-[10px] text-zinc-500 mt-1">Allows bypassing demo mode restrictions when configured on the backend server.</p>
          </div>

          <div className="md:col-span-2 flex items-center justify-end space-x-2">
            {saveSuccess && (
              <span className="text-xs text-emerald-600 dark:text-emerald-400 flex items-center space-x-1">
                <CheckCircle2 className="w-3.5 h-3.5" />
                <span>Saved successfully</span>
              </span>
            )}
            <button
              type="button"
              onClick={() => setShowConfig(false)}
              className="px-3 py-1.5 text-xs text-zinc-600 dark:text-zinc-400 hover:text-zinc-900 dark:hover:text-zinc-200"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="px-4 py-1.5 text-xs font-medium bg-brand-600 hover:bg-brand-700 text-white rounded-lg transition-colors shadow-sm"
            >
              Apply & Reconnect
            </button>
          </div>
        </form>
      )}
    </div>
  );
};
