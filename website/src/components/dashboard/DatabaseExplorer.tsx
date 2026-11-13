import React, { useState } from 'react';
import { Search, Plus, RefreshCw, Eye, Trash2, Clock, Database, AlertTriangle } from 'lucide-react';
import { KeyItem, KeyDetailResponse } from './types';
import { fetchKeyDetail, deleteKey } from './api';
import { KeyInspectorModal } from './KeyInspectorModal';
import { InsertKeyModal } from './InsertKeyModal';

interface Props {
  keys: KeyItem[];
  totalKeys: number;
  loading: boolean;
  searchPattern: string;
  setSearchPattern: (p: string) => void;
  typeFilter: string;
  setTypeFilter: (t: string) => void;
  onRefresh: () => void;
}

export const DatabaseExplorer: React.FC<Props> = ({
  keys,
  totalKeys,
  loading,
  searchPattern,
  setSearchPattern,
  typeFilter,
  setTypeFilter,
  onRefresh,
}) => {
  const [selectedKeyDetail, setSelectedKeyDetail] = useState<KeyDetailResponse | null>(null);
  const [inspectLoading, setInspectLoading] = useState(false);
  const [showInsertModal, setShowInsertModal] = useState(false);
  const [keyToDelete, setKeyToDelete] = useState<string | null>(null);
  const [deleteLoading, setDeleteLoading] = useState(false);

  const handleInspect = async (key: string) => {
    setInspectLoading(true);
    try {
      const detail = await fetchKeyDetail(key);
      setSelectedKeyDetail(detail);
    } catch (err: any) {
      alert(err.message || 'Failed to inspect key');
    } finally {
      setInspectLoading(false);
    }
  };

  const confirmDelete = async () => {
    if (!keyToDelete) return;
    setDeleteLoading(true);
    try {
      await deleteKey(keyToDelete);
      setKeyToDelete(null);
      onRefresh();
    } catch (err: any) {
      alert(err.message || 'Failed to delete key');
    } finally {
      setDeleteLoading(false);
    }
  };

  const getTypeBadgeClass = (type: string) => {
    switch (type.toLowerCase()) {
      case 'string':
        return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950/60 dark:text-emerald-300 border-emerald-200 dark:border-emerald-800';
      case 'list':
        return 'bg-sky-100 text-sky-800 dark:bg-sky-950/60 dark:text-sky-300 border-sky-200 dark:border-sky-800';
      case 'set':
        return 'bg-amber-100 text-amber-800 dark:bg-amber-950/60 dark:text-amber-300 border-amber-200 dark:border-amber-800';
      case 'hash':
        return 'bg-purple-100 text-purple-800 dark:bg-purple-950/60 dark:text-purple-300 border-purple-200 dark:border-purple-800';
      default:
        return 'bg-zinc-100 text-zinc-800 dark:bg-zinc-800 dark:text-zinc-200';
    }
  };

  return (
    <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-sm mb-6 overflow-hidden transition-colors">
      {/* Explorer Header */}
      <div className="p-4 sm:p-5 border-b border-zinc-200 dark:border-zinc-800">
        <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
          <div className="flex items-center space-x-2">
            <div className="p-1.5 rounded-lg bg-brand-50 dark:bg-brand-950/60 text-brand-600 dark:text-brand-400">
              <Database className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
                Keyspace Explorer
              </h3>
              <p className="text-xs text-zinc-500 dark:text-zinc-400">
                Search, inspect data structures, and manage active keys
              </p>
            </div>
          </div>

          {/* Action buttons */}
          <div className="flex items-center space-x-2">
            <button
              onClick={() => setShowInsertModal(true)}
              className="flex items-center space-x-1.5 px-3 py-1.5 bg-brand-600 hover:bg-brand-700 text-white rounded-lg text-xs font-medium transition-colors shadow-sm"
            >
              <Plus className="w-4 h-4" />
              <span>Create Key</span>
            </button>
            <button
              onClick={onRefresh}
              disabled={loading}
              className="p-1.5 border border-zinc-200 dark:border-zinc-700 hover:bg-zinc-100 dark:hover:bg-zinc-800 rounded-lg text-zinc-600 dark:text-zinc-300 text-xs transition-colors"
              title="Refresh Keys"
            >
              <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin text-brand-500' : ''}`} />
            </button>
          </div>
        </div>

        {/* Filter and Search Bar */}
        <div className="mt-4 flex flex-col sm:flex-row sm:items-center gap-3">
          {/* Pattern Search */}
          <div className="relative flex-grow">
            <Search className="w-4 h-4 absolute left-3 top-2.5 text-zinc-400" />
            <input
              type="text"
              value={searchPattern}
              onChange={(e) => setSearchPattern(e.target.value)}
              placeholder="Search keys by glob pattern (e.g. user:*, *cache*, session:*)..."
              className="w-full pl-9 pr-3 py-1.5 text-xs rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 text-zinc-900 dark:text-zinc-100 font-mono focus:outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>

          {/* Type Filter Buttons */}
          <div className="flex items-center bg-zinc-100 dark:bg-zinc-800 p-1 rounded-lg text-xs">
            {['all', 'string', 'list', 'set', 'hash'].map((t) => (
              <button
                key={t}
                onClick={() => setTypeFilter(t)}
                className={`px-2.5 py-1 rounded text-xs font-medium capitalize transition-colors ${
                  typeFilter === t
                    ? 'bg-white dark:bg-zinc-700 text-brand-600 dark:text-brand-400 shadow-sm'
                    : 'text-zinc-600 dark:text-zinc-400 hover:text-zinc-900 dark:hover:text-zinc-200'
                }`}
              >
                {t}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Keys Table */}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs font-mono">
          <thead className="bg-zinc-50 dark:bg-zinc-800/60 border-b border-zinc-200 dark:border-zinc-800 text-zinc-500 dark:text-zinc-400 uppercase text-[10px] tracking-wider font-sans">
            <tr>
              <th className="px-4 py-3">Key Identifier</th>
              <th className="px-4 py-3">Type</th>
              <th className="px-4 py-3">TTL</th>
              <th className="px-4 py-3">Size / Len</th>
              <th className="px-4 py-3 hidden md:table-cell">Value Preview</th>
              <th className="px-4 py-3 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
            {keys.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-4 py-10 text-center font-sans text-xs text-zinc-500 dark:text-zinc-400">
                  {totalKeys === 0 ? (
                    <div className="space-y-2">
                      <p className="font-semibold text-zinc-700 dark:text-zinc-300">No keys stored in MiniRedis.</p>
                      <p className="text-zinc-400">Click &ldquo;Create Key&rdquo; above or execute commands in the terminal below.</p>
                    </div>
                  ) : (
                    <p>No keys match the pattern &ldquo;{searchPattern}&rdquo; with type &ldquo;{typeFilter}&rdquo;.</p>
                  )}
                </td>
              </tr>
            ) : (
              keys.map((k) => (
                <tr key={k.key} className="hover:bg-zinc-50 dark:hover:bg-zinc-800/40 transition-colors">
                  {/* Key */}
                  <td className="px-4 py-3 font-semibold text-zinc-900 dark:text-zinc-100 max-w-xs truncate">
                    {k.key}
                  </td>

                  {/* Type */}
                  <td className="px-4 py-3 font-sans">
                    <span className={`px-2 py-0.5 text-[10px] font-bold uppercase rounded border ${getTypeBadgeClass(k.type)}`}>
                      {k.type}
                    </span>
                  </td>

                  {/* TTL */}
                  <td className="px-4 py-3 text-zinc-600 dark:text-zinc-400 whitespace-nowrap">
                    <span className="flex items-center space-x-1">
                      <Clock className="w-3 h-3 text-zinc-400" />
                      <span>{k.ttlFormatted}</span>
                    </span>
                  </td>

                  {/* Size */}
                  <td className="px-4 py-3 text-zinc-600 dark:text-zinc-400">
                    {k.size}
                  </td>

                  {/* Preview */}
                  <td className="px-4 py-3 text-zinc-500 dark:text-zinc-400 max-w-sm truncate hidden md:table-cell">
                    {k.preview}
                  </td>

                  {/* Actions */}
                  <td className="px-4 py-3 text-right whitespace-nowrap font-sans">
                    <div className="flex items-center justify-end space-x-1">
                      <button
                        onClick={() => handleInspect(k.key)}
                        disabled={inspectLoading}
                        className="p-1 rounded text-zinc-500 hover:text-brand-600 hover:bg-zinc-100 dark:hover:bg-zinc-800 transition-colors"
                        title="Inspect Key Value"
                      >
                        <Eye className="w-4 h-4" />
                      </button>
                      <button
                        onClick={() => setKeyToDelete(k.key)}
                        className="p-1 rounded text-zinc-500 hover:text-rose-600 hover:bg-rose-50 dark:hover:bg-rose-950/40 transition-colors"
                        title="Delete Key"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Table Footer */}
      <div className="px-4 py-2.5 bg-zinc-50 dark:bg-zinc-800/40 border-t border-zinc-200 dark:border-zinc-800 flex items-center justify-between text-[11px] text-zinc-500">
        <span>Showing {keys.length} of {totalKeys} total keys in database</span>
        <span>Filter: {typeFilter.toUpperCase()}</span>
      </div>

      {/* Inspector Modal */}
      {selectedKeyDetail && (
        <KeyInspectorModal
          detail={selectedKeyDetail}
          onClose={() => setSelectedKeyDetail(null)}
        />
      )}

      {/* Insert Key Modal */}
      {showInsertModal && (
        <InsertKeyModal
          isOpen={showInsertModal}
          onClose={() => setShowInsertModal(false)}
          onKeyInserted={onRefresh}
        />
      )}

      {/* Delete Confirmation Modal */}
      {keyToDelete && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 backdrop-blur-sm animate-fade-in font-sans">
          <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-xl max-w-sm w-full p-5">
            <div className="flex items-center space-x-3 text-rose-600 dark:text-rose-400 mb-3">
              <AlertTriangle className="w-6 h-6 flex-shrink-0" />
              <h4 className="text-sm font-bold text-zinc-900 dark:text-zinc-100">
                Confirm Key Deletion
              </h4>
            </div>
            <p className="text-xs text-zinc-600 dark:text-zinc-400 mb-4">
              Are you sure you want to delete <code className="font-mono text-rose-600 dark:text-rose-400 bg-zinc-100 dark:bg-zinc-800 px-1 py-0.5 rounded">{keyToDelete}</code>? This operation is permanent and logged directly to the AOF journal.
            </p>
            <div className="flex justify-end space-x-2">
              <button
                onClick={() => setKeyToDelete(null)}
                className="px-3 py-1.5 text-xs text-zinc-600 dark:text-zinc-400 hover:bg-zinc-100 dark:hover:bg-zinc-800 rounded-lg transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={confirmDelete}
                disabled={deleteLoading}
                className="px-3 py-1.5 text-xs font-semibold bg-rose-600 hover:bg-rose-700 text-white rounded-lg transition-colors disabled:opacity-50"
              >
                {deleteLoading ? 'Deleting...' : 'Delete Key'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
