import React, { useState } from 'react';
import { X, Plus, AlertCircle } from 'lucide-react';
import { executeCommand } from './api';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onKeyInserted: () => void;
}

export const InsertKeyModal: React.FC<Props> = ({ isOpen, onClose, onKeyInserted }) => {
  const [type, setType] = useState<'string' | 'list' | 'set' | 'hash'>('string');
  const [key, setKey] = useState('');
  const [value, setValue] = useState('');
  const [field, setField] = useState('');
  const [ttl, setTtl] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!key.trim()) {
      setError('Key name cannot be empty');
      return;
    }

    const cleanKey = key.trim();
    if (cleanKey.startsWith('demo:') || cleanKey === 'demo') {
      setError("Keys with 'demo:' prefix are reserved for immutable system demo data. Please choose another prefix (e.g. user:*, app:*).");
      return;
    }
    if (cleanKey.startsWith('session:') || cleanKey === 'session') {
      setError("Keys with 'session:' prefix are reserved for internal visitor namespaces.");
      return;
    }

    setLoading(true);
    setError(null);

    try {
      let command = '';

      if (type === 'string') {
        const ttlNum = parseInt(ttl.trim(), 10);
        if (ttl.trim() && !isNaN(ttlNum) && ttlNum > 0) {
          command = `SETEX ${cleanKey} ${ttlNum} "${value.replace(/"/g, '\\"')}"`;
        } else {
          command = `SET ${cleanKey} "${value.replace(/"/g, '\\"')}"`;
        }
      } else if (type === 'list') {
        const items = value.split('\n').map(s => s.trim()).filter(Boolean);
        if (items.length === 0) {
          setError('Provide at least one list item (one per line)');
          setLoading(false);
          return;
        }
        const formattedItems = items.map(i => `"${i.replace(/"/g, '\\"')}"`).join(' ');
        command = `RPUSH ${cleanKey} ${formattedItems}`;
      } else if (type === 'set') {
        const members = value.split('\n').map(s => s.trim()).filter(Boolean);
        if (members.length === 0) {
          setError('Provide at least one set member (one per line)');
          setLoading(false);
          return;
        }
        const formattedMembers = members.map(m => `"${m.replace(/"/g, '\\"')}"`).join(' ');
        command = `SADD ${cleanKey} ${formattedMembers}`;
      } else if (type === 'hash') {
        if (!field.trim()) {
          setError('Field name cannot be empty for Hash');
          setLoading(false);
          return;
        }
        command = `HSET ${cleanKey} ${field.trim()} "${value.replace(/"/g, '\\"')}"`;
      }

      const res = await executeCommand(command);
      if (res.status === 'error') {
        setError(res.output);
        setLoading(false);
        return;
      }

      // If non-string type has TTL specified, apply EXPIRE
      if (type !== 'string' && ttl.trim()) {
        const ttlNum = parseInt(ttl.trim(), 10);
        if (!isNaN(ttlNum) && ttlNum > 0) {
          await executeCommand(`EXPIRE ${cleanKey} ${ttlNum}`);
        }
      }

      setLoading(false);
      onKeyInserted();
      onClose();
    } catch (err: any) {
      setError(err.message || 'Failed to save key');
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 backdrop-blur-sm animate-fade-in">
      <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-xl max-w-lg w-full flex flex-col overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-zinc-200 dark:border-zinc-800">
          <div className="flex items-center space-x-2">
            <div className="p-1.5 rounded-lg bg-brand-50 dark:bg-brand-950/60 text-brand-600 dark:text-brand-400">
              <Plus className="w-4 h-4" />
            </div>
            <h3 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
              Create New Key
            </h3>
          </div>
          <button
            onClick={onClose}
            aria-label="Close"
            className="p-1 rounded-lg text-zinc-400 hover:text-zinc-700 dark:hover:text-zinc-200 hover:bg-zinc-100 dark:hover:bg-zinc-800 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Form Body */}
        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          {error && (
            <div className="p-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-900 rounded-lg flex items-center space-x-2 text-xs text-rose-800 dark:text-rose-300">
              <AlertCircle className="w-4 h-4 flex-shrink-0" />
              <span>{error}</span>
            </div>
          )}

          {/* Type Selector */}
          <div>
            <label className="block text-xs font-medium text-zinc-700 dark:text-zinc-300 mb-1.5">
              Data Structure Type
            </label>
            <div className="grid grid-cols-4 gap-2">
              {(['string', 'list', 'set', 'hash'] as const).map((t) => (
                <button
                  key={t}
                  type="button"
                  onClick={() => setType(t)}
                  className={`py-2 text-xs font-semibold uppercase rounded-lg border transition-colors ${
                    type === t
                      ? 'bg-brand-600 text-white border-brand-600 shadow-sm'
                      : 'border-zinc-200 dark:border-zinc-700 text-zinc-700 dark:text-zinc-300 hover:bg-zinc-100 dark:hover:bg-zinc-800'
                  }`}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>

          {/* Key Input */}
          <div>
            <label className="block text-xs font-medium text-zinc-700 dark:text-zinc-300 mb-1">
              Key Name <span className="text-rose-500">*</span>
            </label>
            <input
              type="text"
              required
              value={key}
              onChange={(e) => setKey(e.target.value)}
              placeholder="e.g. user:1001 or session:token"
              className="w-full px-3 py-2 text-xs rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 text-zinc-900 dark:text-zinc-100 font-mono focus:outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>

          {/* Hash Field (if hash) */}
          {type === 'hash' && (
            <div>
              <label className="block text-xs font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                Hash Field <span className="text-rose-500">*</span>
              </label>
              <input
                type="text"
                required
                value={field}
                onChange={(e) => setField(e.target.value)}
                placeholder="e.g. username, email, role"
                className="w-full px-3 py-2 text-xs rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 text-zinc-900 dark:text-zinc-100 font-mono focus:outline-none focus:ring-2 focus:ring-brand-500"
              />
            </div>
          )}

          {/* Value Input */}
          <div>
            <label className="block text-xs font-medium text-zinc-700 dark:text-zinc-300 mb-1">
              {type === 'string' && 'Value'}
              {type === 'list' && 'List Items (one item per line)'}
              {type === 'set' && 'Set Members (one member per line)'}
              {type === 'hash' && 'Field Value'}
            </label>
            <textarea
              rows={type === 'string' ? 3 : 4}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              placeholder={
                type === 'string'
                  ? 'Enter string value or JSON payload'
                  : type === 'list'
                  ? 'item1\nitem2\nitem3'
                  : type === 'set'
                  ? 'memberA\nmemberB\nmemberC'
                  : 'Enter field value'
              }
              className="w-full px-3 py-2 text-xs rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 text-zinc-900 dark:text-zinc-100 font-mono focus:outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>

          {/* Optional TTL */}
          <div>
            <label className="block text-xs font-medium text-zinc-700 dark:text-zinc-300 mb-1">
              Expiration TTL in Seconds (Optional)
            </label>
            <input
              type="number"
              min="1"
              value={ttl}
              onChange={(e) => setTtl(e.target.value)}
              placeholder="e.g. 60 (leave empty for persistent key)"
              className="w-full px-3 py-2 text-xs rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 text-zinc-900 dark:text-zinc-100 font-mono focus:outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>

          {/* Footer Actions */}
          <div className="pt-2 flex items-center justify-end space-x-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-xs font-medium text-zinc-700 dark:text-zinc-300 hover:bg-zinc-100 dark:hover:bg-zinc-800 rounded-lg transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={loading}
              className="px-4 py-2 text-xs font-medium bg-brand-600 hover:bg-brand-700 text-white rounded-lg transition-colors shadow-sm disabled:opacity-50"
            >
              {loading ? 'Writing to Engine...' : 'Write Key'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
