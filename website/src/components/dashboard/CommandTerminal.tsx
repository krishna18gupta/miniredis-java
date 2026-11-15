import React, { useState, useRef, useEffect } from 'react';
import { Terminal, Trash2, ShieldAlert, Sparkles, CornerDownLeft } from 'lucide-react';
import { executeCommand } from './api';

interface TerminalEntry {
  id: string;
  command: string;
  output: string;
  isError: boolean;
  timestamp: string;
}

interface Props {
  demoMode: boolean;
  onCommandExecuted: () => void;
}

const PRESET_COMMANDS = [
  'PING',
  'GET demo:welcome',
  'LRANGE demo:pipeline 0 -1',
  'HGETALL demo:server',
  'SET user:name "Alice"',
  'GET user:name',
  'INCR user:visits',
  'LPUSH user:queue "task-1" "task-2"',
  'LRANGE user:queue 0 -1',
  'HSET user:config theme "dark" lang "en"',
  'HGETALL user:config',
  'DBSIZE',
  'INFO',
];

export const CommandTerminal: React.FC<Props> = ({ demoMode, onCommandExecuted }) => {
  const [input, setInput] = useState('');
  const [history, setHistory] = useState<TerminalEntry[]>([
    {
      id: 'init-1',
      command: 'INFO SERVER',
      output: 'MiniRedis In-Memory Engine v1.0.0 (Pure Java 17, 0 Dependencies, RESP2 Protocol Online)',
      isError: false,
      timestamp: new Date().toLocaleTimeString(),
    },
  ]);
  const [commandHistory, setCommandHistory] = useState<string[]>([]);
  const [historyIndex, setHistoryIndex] = useState<number>(-1);
  const [loading, setLoading] = useState(false);

  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [history]);

  const runCommand = async (cmdToRun: string) => {
    const trimmed = cmdToRun.trim();
    if (!trimmed) return;

    setLoading(true);
    setCommandHistory((prev) => [...prev, trimmed]);
    setHistoryIndex(-1);

    try {
      const res = await executeCommand(trimmed);
      const isErr = res.status === 'error';
      const newEntry: TerminalEntry = {
        id: Math.random().toString(36).substring(7),
        command: trimmed,
        output: res.output,
        isError: isErr,
        timestamp: new Date().toLocaleTimeString(),
      };
      setHistory((prev) => [...prev, newEntry]);
      onCommandExecuted();
    } catch (err: any) {
      setHistory((prev) => [
        ...prev,
        {
          id: Math.random().toString(36).substring(7),
          command: trimmed,
          output: `(error) ${err.message || 'Execution error'}`,
          isError: true,
          timestamp: new Date().toLocaleTimeString(),
        },
      ]);
    } finally {
      setLoading(false);
      setInput('');
      inputRef.current?.focus();
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (commandHistory.length === 0) return;
      const nextIdx = historyIndex === -1 ? commandHistory.length - 1 : Math.max(0, historyIndex - 1);
      setHistoryIndex(nextIdx);
      setInput(commandHistory[nextIdx]);
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (historyIndex === -1) return;
      const nextIdx = historyIndex + 1;
      if (nextIdx >= commandHistory.length) {
        setHistoryIndex(-1);
        setInput('');
      } else {
        setHistoryIndex(nextIdx);
        setInput(commandHistory[nextIdx]);
      }
    }
  };

  const clearTerminal = () => {
    setHistory([]);
  };

  return (
    <div className="bg-zinc-950 border border-zinc-800 rounded-xl shadow-xl overflow-hidden mb-6 flex flex-col transition-colors">
      {/* Terminal Title Bar */}
      <div className="bg-zinc-900 px-4 py-3 border-b border-zinc-800 flex items-center justify-between">
        <div className="flex items-center space-x-3">
          <div className="flex items-center space-x-1.5">
            <span className="w-3 h-3 rounded-full bg-rose-500/80 inline-block" />
            <span className="w-3 h-3 rounded-full bg-amber-500/80 inline-block" />
            <span className="w-3 h-3 rounded-full bg-emerald-500/80 inline-block" />
          </div>
          <div className="flex items-center space-x-2">
            <Terminal className="w-4 h-4 text-brand-400" />
            <span className="text-xs font-mono font-semibold text-zinc-300">
              miniredis-cli (REST / RESP2 Engine)
            </span>
          </div>
        </div>

        <div className="flex items-center space-x-2">
          {demoMode && (
            <span className="hidden sm:flex items-center space-x-1 px-2 py-0.5 text-[10px] rounded bg-amber-950/60 text-amber-300 border border-amber-800 font-mono">
              <ShieldAlert className="w-3 h-3" />
              <span>Safe Demo Mode</span>
            </span>
          )}
          <button
            onClick={clearTerminal}
            className="p-1 rounded text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800 text-xs transition-colors"
            title="Clear Console"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* Preset Command Pills */}
      <div className="bg-zinc-900/60 px-4 py-2 border-b border-zinc-800/80 flex items-center space-x-2 overflow-x-auto text-[11px] font-mono scrollbar-thin">
        <span className="flex items-center space-x-1 text-zinc-500 flex-shrink-0">
          <Sparkles className="w-3 h-3 text-brand-400" />
          <span>Quick:</span>
        </span>
        {PRESET_COMMANDS.map((cmd) => (
          <button
            key={cmd}
            onClick={() => runCommand(cmd)}
            disabled={loading}
            className="px-2 py-0.5 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-300 hover:text-white transition-colors flex-shrink-0 border border-zinc-700/60"
          >
            {cmd}
          </button>
        ))}
      </div>

      {/* Terminal Screen Output */}
      <div className="p-4 font-mono text-xs text-zinc-300 min-h-[260px] max-h-[380px] overflow-y-auto space-y-3">
        {history.length === 0 && (
          <p className="text-zinc-600 italic">Terminal ready. Type any Redis command or click a quick pill above.</p>
        )}

        {history.map((entry) => (
          <div key={entry.id} className="space-y-1">
            <div className="flex items-center space-x-2 text-zinc-400">
              <span className="text-brand-400 font-bold">127.0.0.1:6379&gt;</span>
              <span className="text-zinc-100 font-semibold">{entry.command}</span>
              <span className="text-[10px] text-zinc-600 ml-auto">{entry.timestamp}</span>
            </div>
            <pre
              className={`p-2 rounded text-xs whitespace-pre-wrap break-all ${
                entry.isError
                  ? 'bg-rose-950/40 text-rose-400 border border-rose-900/60'
                  : 'bg-zinc-900/80 text-emerald-400 border border-zinc-800/80'
              }`}
            >
              {entry.output}
            </pre>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      {/* Terminal Input Line */}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          runCommand(input);
        }}
        className="px-4 py-3 bg-zinc-900/90 border-t border-zinc-800 flex items-center space-x-2"
      >
        <span className="text-brand-400 font-mono font-bold text-xs select-none">
          miniredis&gt;
        </span>
        <input
          ref={inputRef}
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Type command (e.g. SET key val, GET key, KEYS *, INFO)..."
          disabled={loading}
          className="flex-grow bg-transparent text-xs text-zinc-100 font-mono focus:outline-none placeholder-zinc-600"
        />
        <button
          type="submit"
          disabled={loading || !input.trim()}
          className="px-2.5 py-1 bg-brand-600 hover:bg-brand-500 disabled:opacity-40 text-white rounded text-xs font-mono transition-colors flex items-center space-x-1"
        >
          <span>Run</span>
          <CornerDownLeft className="w-3 h-3" />
        </button>
      </form>
    </div>
  );
};
