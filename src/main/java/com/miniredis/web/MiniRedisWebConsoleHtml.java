package com.miniredis.web;

public class MiniRedisWebConsoleHtml {
    public static String getHtml() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>MiniRedis Web Console</title>
  <script src="https://www.gstatic.com/antigravity/web/dev/tailwindcss.min.js"></script>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
    .terminal-scroll::-webkit-scrollbar { width: 6px; }
    .terminal-scroll::-webkit-scrollbar-track { background: #0f172a; }
    .terminal-scroll::-webkit-scrollbar-thumb { background: #334155; border-radius: 3px; }
  </style>
</head>
<body class="bg-slate-950 text-slate-100 min-h-screen flex flex-col p-4 md:p-6">
  <header class="flex items-center justify-between pb-4 border-b border-slate-800 mb-6">
    <div class="flex items-center space-x-3">
      <div class="w-10 h-10 rounded-xl bg-red-600 flex items-center justify-center font-black text-xl text-white shadow-lg shadow-red-500/20">R</div>
      <div>
        <h1 class="text-xl font-bold tracking-tight text-white flex items-center gap-2">
          MiniRedis
          <span class="text-xs font-medium px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">Phase 8 &amp; 9: Transactions &amp; Live Metrics</span>
        </h1>
        <p class="text-xs text-slate-400">In-Memory Engine • TCP RESP2 (6379) • Web Console (8080) • 57 Commands • MULTI/EXEC &amp; AOF</p>
      </div>
    </div>
    <div class="flex items-center gap-3 text-xs">
      <div class="bg-slate-900 border border-slate-800 px-3 py-1.5 rounded-lg flex items-center gap-2">
        <span class="text-slate-400">Active Keys:</span>
        <span id="stat-keys" class="font-mono font-bold text-amber-400 text-sm">0</span>
      </div>
      <div class="bg-slate-900 border border-slate-800 px-3 py-1.5 rounded-lg flex items-center gap-2">
        <span class="text-slate-400">Total Ops:</span>
        <span id="stat-ops" class="font-mono font-bold text-violet-400 text-sm">0</span>
      </div>
      <div class="bg-slate-900 border border-slate-800 px-3 py-1.5 rounded-lg flex items-center gap-2">
        <span class="text-slate-400">Stripes:</span>
        <span class="font-mono text-blue-400 font-semibold">256</span>
      </div>
      <div class="bg-slate-900 border border-slate-800 px-3 py-1.5 rounded-lg flex items-center gap-2">
        <span class="text-slate-400">RESP2:</span>
        <span class="font-mono text-emerald-400 font-semibold">6379</span>
      </div>
      <div class="bg-slate-900 border border-slate-800 px-3 py-1.5 rounded-lg flex items-center gap-2">
        <span class="text-slate-400">HTTP:</span>
        <span class="font-mono text-cyan-400 font-semibold">8080</span>
      </div>
    </div>
  </header>

  <!-- AOF Persistence Status Banner -->
  <div class="mb-5 bg-slate-900/90 border border-slate-800 rounded-xl p-3 flex flex-wrap items-center justify-between gap-4 text-xs font-mono shadow-md">
    <div class="flex flex-wrap items-center gap-4">
      <div class="flex items-center gap-1.5">
        <span class="text-slate-400">AOF:</span>
        <span id="aof-health" class="px-2 py-0.5 rounded font-bold bg-emerald-950 text-emerald-400 border border-emerald-800/40">HEALTHY</span>
      </div>
      <div class="flex items-center gap-1.5">
        <span class="text-slate-400">Policy:</span>
        <span id="aof-policy" class="text-cyan-400 font-semibold">EVERYSEC</span>
      </div>
      <div class="flex items-center gap-1.5">
        <span class="text-slate-400">File Size:</span>
        <span id="aof-size" class="text-amber-400 font-semibold">0 B</span>
      </div>
      <div class="flex items-center gap-1.5">
        <span class="text-slate-400">Logged Commands:</span>
        <span id="aof-logged" class="text-violet-400 font-semibold">0</span>
      </div>
      <div class="flex items-center gap-1.5">
        <span class="text-slate-400">Write Queue:</span>
        <span id="aof-queue" class="text-sky-400 font-semibold">0</span>
      </div>
      <div class="flex items-center gap-1.5">
        <span class="text-slate-400">Last Fsync:</span>
        <span id="aof-fsync" class="text-slate-300">0ms</span>
      </div>
      <div class="flex items-center gap-1.5">
        <span class="text-slate-400">Compaction:</span>
        <span id="aof-rewrite" class="text-emerald-300">Idle</span>
      </div>
    </div>
    <div class="flex items-center gap-2">
      <button onclick="quickRun('BGREWRITEAOF')" class="px-2.5 py-1 rounded bg-red-950/60 text-red-300 border border-red-800/60 hover:bg-red-900 transition flex items-center gap-1 text-[11px] font-sans">
        <svg class="w-3 h-3 text-red-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path></svg>
        BGREWRITEAOF
      </button>
    </div>
  </div>

  <div class="grid grid-cols-1 lg:grid-cols-12 gap-6 flex-1">
    <!-- Terminal Area (7 cols) -->
    <section class="lg:col-span-7 flex flex-col bg-slate-900/90 border border-slate-800 rounded-2xl overflow-hidden shadow-2xl backdrop-blur">
      <div class="bg-slate-950 px-4 py-3 border-b border-slate-800 flex items-center justify-between">
        <div class="flex items-center space-x-2">
          <div class="w-3 h-3 rounded-full bg-red-500/80"></div>
          <div class="w-3 h-3 rounded-full bg-yellow-500/80"></div>
          <div class="w-3 h-3 rounded-full bg-green-500/80"></div>
          <span class="text-xs font-mono text-slate-400 ml-2">miniredis-cli (AOF + RESP2 active)</span>
        </div>
        <button onclick="clearTerminal()" class="text-xs text-slate-400 hover:text-slate-200 transition px-2 py-0.5 rounded bg-slate-800/60 hover:bg-slate-800">Clear</button>
      </div>

      <div id="terminal-log" class="flex-1 p-4 font-mono text-sm overflow-y-auto space-y-2 terminal-scroll min-h-[350px] max-h-[500px]">
        <div class="text-slate-500 text-xs">
          MiniRedis v1.0.0 Web Terminal ready.<br>
          Supports: Strings, Lists, Sets, Hashes, Key TTL Expiration, Transactions (MULTI/EXEC/WATCH), AOF Persistence, RESP2 protocol (57 commands).
        </div>
      </div>

      <!-- Quick Command Buttons for All Phases -->
      <div class="p-2.5 bg-slate-950/60 border-t border-slate-800 flex flex-wrap gap-2 text-xs">
        <button onclick="quickRun('PING')" class="px-2 py-0.5 rounded bg-blue-950/40 text-blue-300 border border-blue-800/40 hover:bg-blue-900/50 transition">PING</button>
        <button onclick="quickRun('SETEX token 60 secret123')" class="px-2 py-0.5 rounded bg-amber-950/40 text-amber-300 border border-amber-800/40 hover:bg-amber-900/50 transition">SETEX token 60 secret123</button>
        <button onclick="quickRun('TYPE token')" class="px-2 py-0.5 rounded bg-sky-950/40 text-sky-300 border border-sky-800/40 hover:bg-sky-900/50 transition">TYPE token</button>
        <button onclick="quickRun('TTL token')" class="px-2 py-0.5 rounded bg-slate-800 text-slate-300 hover:bg-slate-700 hover:text-white transition">TTL token</button>
        <button onclick="quickRun('LPUSH tasks deploy test code')" class="px-2 py-0.5 rounded bg-purple-950/40 text-purple-300 border border-purple-800/40 hover:bg-purple-900/50 transition">LPUSH tasks</button>
        <button onclick="quickRun('SADD tags java redis aof resp2')" class="px-2 py-0.5 rounded bg-emerald-950/40 text-emerald-300 border border-emerald-800/40 hover:bg-emerald-900/50 transition">SADD tags</button>
        <button onclick="quickRun('HSET user:1 name Krishna role Admin')" class="px-2 py-0.5 rounded bg-amber-950/40 text-amber-300 border border-amber-800/40 hover:bg-amber-900/50 transition">HSET user:1</button>
        <button onclick="quickRun('BGREWRITEAOF')" class="px-2 py-0.5 rounded bg-rose-950/40 text-rose-300 border border-rose-800/40 hover:bg-rose-900/50 transition">BGREWRITEAOF</button>
        <button onclick="quickRun('INFO stats')" class="px-2 py-0.5 rounded bg-slate-800 text-slate-300 hover:bg-slate-700 hover:text-white transition">INFO stats</button>
        <button onclick="quickRun('FLUSHDB')" class="px-2 py-0.5 rounded bg-red-950/40 text-red-300 border border-red-800/40 hover:bg-red-900/50 transition">FLUSHDB</button>
      </div>

      <form id="cmd-form" onsubmit="handleSubmit(event)" class="bg-slate-950 p-3 border-t border-slate-800 flex items-center space-x-2">
        <span class="font-mono font-bold text-red-500">miniredis&gt;</span>
        <input id="cmd-input" type="text" autofocus autocomplete="off" spellcheck="false"
               placeholder="Type command (e.g. SETEX session 30 'temp_token')..."
               class="flex-1 bg-transparent font-mono text-sm text-white focus:outline-none placeholder-slate-600">
        <button type="submit" class="px-4 py-1.5 bg-red-600 hover:bg-red-500 text-white text-xs font-semibold rounded-lg transition shadow-md shadow-red-600/30">Execute</button>
      </form>
    </section>

    <!-- In-Memory Storage Visualizer (5 cols) -->
    <section class="lg:col-span-5 flex flex-col bg-slate-900/90 border border-slate-800 rounded-2xl overflow-hidden shadow-2xl backdrop-blur">
      <div class="bg-slate-950 px-4 py-3 border-b border-slate-800 flex items-center justify-between">
        <h2 class="text-sm font-semibold text-slate-200 flex items-center gap-2">
          <svg class="w-4 h-4 text-amber-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 7v10c0 2 1.5 3 3.5 3h9c2 0 3.5-1 3.5-3V7c0-2-1.5-3-3.5-3h-9C5.5 4 4 5 4 7zm0 5h16"></path></svg>
          In-Memory RAM Store (Live)
        </h2>
        <button onclick="refreshState()" title="Refresh keys" class="text-xs text-slate-400 hover:text-slate-200 transition flex items-center gap-1 bg-slate-800/60 px-2 py-0.5 rounded">
          <span>↻ Sync</span>
        </button>
      </div>

      <div class="flex-1 p-4 overflow-y-auto terminal-scroll max-h-[550px]">
        <div id="empty-state" class="text-center py-12 text-slate-500 text-xs hidden">
          <p class="mb-1 font-mono text-sm">Database is empty</p>
          <p>Run a command to insert data structures and TTLs into RAM.</p>
        </div>
        <div id="keys-table-container" class="overflow-x-auto">
          <table class="w-full text-left text-xs font-mono">
            <thead>
              <tr class="border-b border-slate-800 text-slate-400">
                <th class="pb-2 font-medium">Key</th>
                <th class="pb-2 font-medium">Type</th>
                <th class="pb-2 font-medium">TTL / Status</th>
                <th class="pb-2 font-medium">Value</th>
                <th class="pb-2 text-right">Action</th>
              </tr>
            </thead>
            <tbody id="keys-body" class="divide-y divide-slate-800/60">
            </tbody>
          </table>
        </div>
      </div>
    </section>
  </div>

  <script>
    const history = [];
    let historyIdx = -1;

    async function executeCommand(cmd) {
      if (!cmd || !cmd.trim()) return;
      appendLog('user', cmd);
      history.push(cmd);
      historyIdx = history.length;

      try {
        const res = await fetch('/api/exec', {
          method: 'POST',
          headers: { 'Content-Type': 'text/plain' },
          body: cmd
        });
        const data = await res.json();
        appendLog(data.status === 'error' ? 'error' : 'response', data.output);
      } catch (err) {
        appendLog('error', '(error) Network or server error: ' + err.message);
      }
      refreshState();
    }

    function appendLog(type, text) {
      const log = document.getElementById('terminal-log');
      const row = document.createElement('div');
      row.className = 'leading-relaxed break-all';

      if (type === 'user') {
        row.innerHTML = `<span class="text-red-400 font-bold mr-2">127.0.0.1:6379&gt;</span> <span class="text-white font-semibold">${escapeHtml(text)}</span>`;
      } else if (type === 'error') {
        row.innerHTML = `<span class="text-rose-400">${escapeHtml(text)}</span>`;
      } else {
        let formatted = escapeHtml(text);
        if (text === 'OK') {
          row.innerHTML = `<span class="text-emerald-400 font-bold">${formatted}</span>`;
        } else if (text.startsWith('(integer)')) {
          row.innerHTML = `<span class="text-amber-300 font-semibold">${formatted}</span>`;
        } else if (text === '(nil)') {
          row.innerHTML = `<span class="text-slate-500 italic">${formatted}</span>`;
        } else {
          row.innerHTML = `<span class="text-cyan-300">${formatted.replace(/\\n/g, '<br>')}</span>`;
        }
      }
      log.appendChild(row);
      log.scrollTop = log.scrollHeight;
    }

    function getTypeBadge(type) {
      const t = (type || '').toLowerCase();
      if (t === 'string') {
        return '<span class="px-1.5 py-0.5 rounded text-[10px] bg-sky-950 text-sky-400 border border-sky-800/40 uppercase font-semibold">STRING</span>';
      } else if (t === 'list') {
        return '<span class="px-1.5 py-0.5 rounded text-[10px] bg-purple-950 text-purple-400 border border-purple-800/40 uppercase font-semibold">LIST</span>';
      } else if (t === 'set') {
        return '<span class="px-1.5 py-0.5 rounded text-[10px] bg-emerald-950 text-emerald-400 border border-emerald-800/40 uppercase font-semibold">SET</span>';
      } else if (t === 'hash') {
        return '<span class="px-1.5 py-0.5 rounded text-[10px] bg-amber-950 text-amber-400 border border-amber-800/40 uppercase font-semibold">HASH</span>';
      }
      return `<span class="px-1.5 py-0.5 rounded text-[10px] bg-slate-800 text-slate-300 font-semibold uppercase">${escapeHtml(t)}</span>`;
    }

    function getTtlBadge(ttl, isExpiring) {
      if (!isExpiring) {
        return '<span class="px-1.5 py-0.5 rounded text-[10px] bg-slate-800 text-slate-400 border border-slate-700/60 font-mono">Persistent</span>';
      }
      return `<span class="px-1.5 py-0.5 rounded text-[10px] bg-rose-950 text-rose-300 border border-rose-800/60 font-mono font-bold animate-pulse">⏱ ${escapeHtml(ttl)}</span>`;
    }

    async function refreshState() {
      try {
        const res = await fetch('/api/state');
        const data = await res.json();
        const keys = Array.isArray(data) ? data : (data.keys || []);
        const stats = data.stats || {};
        document.getElementById('stat-keys').innerText = keys.length;
        if (document.getElementById('stat-ops') && stats.totalCompletedOperations !== undefined) {
          document.getElementById('stat-ops').innerText = stats.totalCompletedOperations;
        }

        if (stats.aof) {
          const a = stats.aof;
          const healthEl = document.getElementById('aof-health');
          if (healthEl) {
            healthEl.innerText = a.healthStatus || (a.enabled ? 'HEALTHY' : 'DISABLED');
            if (a.healthStatus === 'HEALTHY') {
              healthEl.className = 'px-2 py-0.5 rounded font-bold bg-emerald-950 text-emerald-400 border border-emerald-800/40';
            } else if (a.healthStatus === 'DEGRADED') {
              healthEl.className = 'px-2 py-0.5 rounded font-bold bg-amber-950 text-amber-400 border border-amber-800/40';
            } else if (a.healthStatus === 'FAILING') {
              healthEl.className = 'px-2 py-0.5 rounded font-bold bg-rose-950 text-rose-400 border border-rose-800/40 animate-pulse';
            } else {
              healthEl.className = 'px-2 py-0.5 rounded font-bold bg-slate-800 text-slate-400 border border-slate-700';
            }
          }
          if (document.getElementById('aof-policy')) document.getElementById('aof-policy').innerText = a.fsyncPolicy || 'N/A';
          if (document.getElementById('aof-size')) document.getElementById('aof-size').innerText = formatBytes(a.fileSize || 0);
          if (document.getElementById('aof-logged')) document.getElementById('aof-logged').innerText = a.totalLoggedCommands || 0;
          if (document.getElementById('aof-queue')) document.getElementById('aof-queue').innerText = a.pendingQueueSize || 0;
          if (document.getElementById('aof-fsync')) document.getElementById('aof-fsync').innerText = (a.lastFsyncDurationMs || 0) + 'ms';
          if (document.getElementById('aof-rewrite')) {
            document.getElementById('aof-rewrite').innerText = a.isRewriting ? 'Rewriting...' : (a.lastRewriteDurationMs ? `${a.lastRewriteDurationMs}ms` : 'Idle');
          }
        }

        const tbody = document.getElementById('keys-body');
        const empty = document.getElementById('empty-state');
        tbody.innerHTML = '';

        if (keys.length === 0) {
          empty.classList.remove('hidden');
        } else {
          empty.classList.add('hidden');
          keys.forEach(item => {
            const tr = document.createElement('tr');
            tr.className = 'hover:bg-slate-800/40 transition';
            tr.innerHTML = `
              <td class="py-2 font-bold text-white">${escapeHtml(item.key)}</td>
              <td class="py-2">${getTypeBadge(item.type)}</td>
              <td class="py-2">${getTtlBadge(item.ttl, item.isExpiring)}</td>
              <td class="py-2 text-slate-300 truncate max-w-[120px]" title="${escapeHtml(item.value)}">${escapeHtml(item.value)}</td>
              <td class="py-2 text-right">
                <button onclick="deleteKey('${escapeHtml(item.key)}')\" class="text-slate-400 hover:text-red-400 transition">✕</button>
              </td>
            `;
            tbody.appendChild(tr);
          });
        }
      } catch (err) {
        console.error('Failed to sync state:', err);
      }
    }

    function formatBytes(bytes) {
      if (!bytes || bytes === 0) return '0 B';
      if (bytes < 1024) return bytes + ' B';
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
      return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
    }

    function deleteKey(key) {
      executeCommand(`DEL ${key}`);
    }

    function handleSubmit(e) {
      e.preventDefault();
      const input = document.getElementById('cmd-input');
      const cmd = input.value;
      input.value = '';
      executeCommand(cmd);
    }

    function quickRun(cmd) {
      executeCommand(cmd);
    }

    function clearTerminal() {
      document.getElementById('terminal-log').innerHTML = '';
    }

    function escapeHtml(str) {
      if (!str) return '';
      return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    document.getElementById('cmd-input').addEventListener('keydown', (e) => {
      if (e.key === 'ArrowUp') {
        if (historyIdx > 0) {
          historyIdx--;
          e.target.value = history[historyIdx];
        }
      } else if (e.key === 'ArrowDown') {
        if (historyIdx < history.length - 1) {
          historyIdx++;
          e.target.value = history[historyIdx];
        } else {
          historyIdx = history.length;
          e.target.value = '';
        }
      }
    });

    refreshState();
  </script>
</body>
</html>
""";
    }
}