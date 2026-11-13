import React, { useState, useMemo } from 'react';
import { SeoMeta } from '../components/common/SeoMeta';
import { COMMANDS, COMMAND_CATEGORIES, RedisCommand } from '../data/commands';
import { Search, X, Terminal, Copy, Check } from 'lucide-react';
import { Badge } from '../components/common/Badge';

export const CommandsPage: React.FC = () => {
  const [search, setSearch] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('All');
  const [mutatingOnly, setMutatingOnly] = useState<boolean>(false);
  const [copiedIndex, setCopiedIndex] = useState<string | null>(null);

  const filteredCommands = useMemo(() => {
    return COMMANDS.filter((cmd: RedisCommand) => {
      const matchesCategory = selectedCategory === 'All' || cmd.category === selectedCategory;
      const matchesSearch =
        cmd.name.toLowerCase().includes(search.toLowerCase().trim()) ||
        cmd.summary.toLowerCase().includes(search.toLowerCase().trim()) ||
        cmd.syntax.toLowerCase().includes(search.toLowerCase().trim());
      const matchesMutating = !mutatingOnly || cmd.mutating;
      return matchesCategory && matchesSearch && matchesMutating;
    });
  }, [search, selectedCategory, mutatingOnly]);

  const handleCopy = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedIndex(id);
    setTimeout(() => setCopiedIndex(null), 1800);
  };

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-10 space-y-8">
      <SeoMeta
        title="57 Supported Commands Reference"
        description="Comprehensive reference for all 57 supported Redis commands in MiniRedis: syntax, complexity, arguments, and live examples across Strings, Lists, Sets, Hashes, Expiration, and Transactions."
      />

      {/* Header */}
      <div className="space-y-4 text-center max-w-3xl mx-auto">
        <span className="text-xs font-mono uppercase text-brand-500 font-semibold tracking-wider">
          Complete Wire Catalog
        </span>
        <h1 className="text-3xl sm:text-5xl font-extrabold text-zinc-900 dark:text-zinc-50 tracking-tight">
          Command Reference
        </h1>
        <p className="text-base text-zinc-600 dark:text-zinc-300 leading-relaxed">
          MiniRedis implements 57 Redis commands across 8 functional categories, matching Redis arity, syntax, and response wire framing.
        </p>
      </div>

      {/* Search and Filters */}
      <div className="space-y-4 p-5 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/60 shadow-sm">
        {/* Search input */}
        <div className="relative">
          <Search className="w-5 h-5 absolute left-3.5 top-1/2 -translate-y-1/2 text-zinc-400" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search commands by name, syntax, or keywords (e.g. 'EXPIRE', 'pop', 'hash')..."
            className="w-full pl-11 pr-10 py-3 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950 text-sm text-zinc-900 dark:text-zinc-100 placeholder-zinc-400 focus:outline-none focus:ring-2 focus:ring-brand-500 transition-all"
          />
          {search && (
            <button
              onClick={() => setSearch('')}
              className="absolute right-3.5 top-1/2 -translate-y-1/2 text-zinc-400 hover:text-zinc-600 dark:hover:text-zinc-200"
            >
              <X className="w-4 h-4" />
            </button>
          )}
        </div>

        {/* Category Pills & Checkbox */}
        <div className="flex flex-wrap items-center justify-between gap-3 pt-2">
          <div className="flex flex-wrap gap-1.5">
            {COMMAND_CATEGORIES.map((category) => (
              <button
                key={category}
                onClick={() => setSelectedCategory(category)}
                className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                  selectedCategory === category
                    ? 'bg-brand-600 text-white shadow-sm'
                    : 'bg-zinc-100 dark:bg-zinc-800 text-zinc-600 dark:text-zinc-400 hover:bg-zinc-200 dark:hover:bg-zinc-700'
                }`}
              >
                {category}
              </button>
            ))}
          </div>

          <label className="flex items-center space-x-2 text-xs text-zinc-600 dark:text-zinc-400 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={mutatingOnly}
              onChange={(e) => setMutatingOnly(e.target.checked)}
              className="rounded border-zinc-300 dark:border-zinc-700 text-brand-600 focus:ring-brand-500"
            />
            <span>Mutating Only (AOF Logged)</span>
          </label>
        </div>

        {/* Count Summary */}
        <div className="text-xs text-zinc-500 pt-2 border-t border-zinc-100 dark:border-zinc-800/80 flex justify-between items-center">
          <span>Showing <strong>{filteredCommands.length}</strong> of <strong>{COMMANDS.length}</strong> commands</span>
          {(search || selectedCategory !== 'All' || mutatingOnly) && (
            <button
              onClick={() => {
                setSearch('');
                setSelectedCategory('All');
                setMutatingOnly(false);
              }}
              className="text-brand-600 dark:text-brand-400 hover:underline"
            >
              Reset filters
            </button>
          )}
        </div>
      </div>

      {/* Commands Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {filteredCommands.map((cmd) => {
          const syntaxId = `syntax-${cmd.name}`;
          const exampleId = `example-${cmd.name}`;

          return (
            <div
              key={cmd.name}
              className="p-5 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/40 hover:border-zinc-300 dark:hover:border-zinc-700 transition-all flex flex-col justify-between space-y-4 shadow-sm"
            >
              <div>
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center space-x-2">
                    <span className="font-mono font-bold text-lg text-brand-600 dark:text-brand-400">
                      {cmd.name}
                    </span>
                    <Badge variant="neutral">{cmd.category}</Badge>
                    {cmd.mutating && <Badge variant="warning">Writes to AOF</Badge>}
                  </div>
                  <span className="text-[11px] font-mono text-zinc-400 dark:text-zinc-500">
                    {cmd.complexity}
                  </span>
                </div>

                <p className="text-xs text-zinc-600 dark:text-zinc-300 mb-3 leading-relaxed">
                  {cmd.summary}
                </p>

                {/* Syntax */}
                <div className="space-y-1 mb-2">
                  <div className="flex items-center justify-between text-[11px] text-zinc-400 font-mono">
                    <span>Syntax</span>
                    <button
                      onClick={() => handleCopy(cmd.syntax, syntaxId)}
                      className="hover:text-zinc-200 flex items-center space-x-1"
                    >
                      {copiedIndex === syntaxId ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
                    </button>
                  </div>
                  <div className="p-2 rounded bg-zinc-50 dark:bg-zinc-950 font-mono text-xs text-zinc-800 dark:text-zinc-200 border border-zinc-200 dark:border-zinc-800">
                    {cmd.syntax}
                  </div>
                </div>

                {/* Example */}
                <div className="space-y-1">
                  <div className="flex items-center justify-between text-[11px] text-zinc-400 font-mono">
                    <span>Example</span>
                    <button
                      onClick={() => handleCopy(cmd.example, exampleId)}
                      className="hover:text-zinc-200 flex items-center space-x-1"
                    >
                      {copiedIndex === exampleId ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
                    </button>
                  </div>
                  <div className="p-2 rounded bg-zinc-900 font-mono text-xs text-emerald-400 border border-zinc-800">
                    {cmd.example}
                  </div>
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {filteredCommands.length === 0 && (
        <div className="text-center py-16 space-y-3">
          <Terminal className="w-8 h-8 text-zinc-400 mx-auto" />
          <h3 className="font-bold text-zinc-800 dark:text-zinc-200">No matching commands found</h3>
          <p className="text-xs text-zinc-500">Try adjusting your search query or selected category filter.</p>
        </div>
      )}
    </div>
  );
};
