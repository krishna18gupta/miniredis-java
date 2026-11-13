import React from 'react';
import { X, Clock, Database, Copy, Check } from 'lucide-react';
import { KeyDetailResponse } from './types';

interface Props {
  detail: KeyDetailResponse | null;
  onClose: () => void;
}

export const KeyInspectorModal: React.FC<Props> = ({ detail, onClose }) => {
  const [copied, setCopied] = React.useState(false);

  if (!detail) return null;

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const renderValue = () => {
    if (detail.type === 'string') {
      return (
        <pre className="p-4 bg-zinc-950 text-emerald-400 font-mono text-xs rounded-lg overflow-x-auto whitespace-pre-wrap break-all border border-zinc-800">
          {detail.value}
        </pre>
      );
    }

    if (detail.type === 'list' && Array.isArray(detail.value)) {
      return (
        <div className="border border-zinc-200 dark:border-zinc-800 rounded-lg overflow-hidden">
          <table className="w-full text-left text-xs font-mono">
            <thead className="bg-zinc-100 dark:bg-zinc-800/80 text-zinc-600 dark:text-zinc-400">
              <tr>
                <th className="px-3 py-2 w-16">Index</th>
                <th className="px-3 py-2">Element Value</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
              {detail.value.map((item, idx) => (
                <tr key={idx} className="hover:bg-zinc-50 dark:hover:bg-zinc-800/50">
                  <td className="px-3 py-2 text-zinc-400">{idx}</td>
                  <td className="px-3 py-2 text-zinc-900 dark:text-zinc-100 break-all">{item}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    }

    if (detail.type === 'set' && Array.isArray(detail.value)) {
      return (
        <div className="flex flex-wrap gap-2 p-3 bg-zinc-50 dark:bg-zinc-800/50 rounded-lg border border-zinc-200 dark:border-zinc-800">
          {detail.value.map((member, idx) => (
            <span
              key={idx}
              className="px-2.5 py-1 text-xs font-mono rounded bg-white dark:bg-zinc-900 border border-zinc-300 dark:border-zinc-700 text-zinc-800 dark:text-zinc-200 shadow-sm"
            >
              {member}
            </span>
          ))}
        </div>
      );
    }

    if (detail.type === 'hash' && typeof detail.value === 'object') {
      return (
        <div className="border border-zinc-200 dark:border-zinc-800 rounded-lg overflow-hidden">
          <table className="w-full text-left text-xs font-mono">
            <thead className="bg-zinc-100 dark:bg-zinc-800/80 text-zinc-600 dark:text-zinc-400">
              <tr>
                <th className="px-3 py-2 w-1/3">Field</th>
                <th className="px-3 py-2">Value</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
              {Object.entries(detail.value).map(([k, v], idx) => (
                <tr key={idx} className="hover:bg-zinc-50 dark:hover:bg-zinc-800/50">
                  <td className="px-3 py-2 font-semibold text-brand-600 dark:text-brand-400">{k}</td>
                  <td className="px-3 py-2 text-zinc-900 dark:text-zinc-100 break-all">{String(v)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    }

    return (
      <pre className="p-4 bg-zinc-950 text-zinc-200 font-mono text-xs rounded-lg overflow-x-auto">
        {JSON.stringify(detail.value, null, 2)}
      </pre>
    );
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
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 backdrop-blur-sm animate-fade-in">
      <div className="bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-xl shadow-xl max-w-2xl w-full max-h-[85vh] flex flex-col overflow-hidden">
        {/* Modal Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-zinc-200 dark:border-zinc-800">
          <div className="flex items-center space-x-2.5">
            <Database className="w-5 h-5 text-brand-600 dark:text-brand-400" />
            <div>
              <h3 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
                Key Inspector
              </h3>
              <p className="text-xs text-zinc-500 font-mono truncate max-w-md">{detail.key}</p>
            </div>
          </div>
          <button
            onClick={onClose}
            aria-label="Close modal"
            className="p-1.5 rounded-lg text-zinc-400 hover:text-zinc-700 dark:hover:text-zinc-200 hover:bg-zinc-100 dark:hover:bg-zinc-800 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Modal Body */}
        <div className="p-6 overflow-y-auto space-y-4 flex-grow">
          {/* Metadata badges */}
          <div className="flex flex-wrap items-center gap-2">
            <span className={`px-2.5 py-1 text-xs font-bold uppercase rounded-md border ${getTypeBadgeClass(detail.type)}`}>
              {detail.type}
            </span>
            <span className="flex items-center space-x-1 px-2.5 py-1 text-xs font-mono rounded-md bg-zinc-100 dark:bg-zinc-800 text-zinc-700 dark:text-zinc-300 border border-zinc-200 dark:border-zinc-700">
              <Clock className="w-3.5 h-3.5 text-zinc-400" />
              <span>TTL: {detail.ttlFormatted}</span>
            </span>
            <span className="px-2.5 py-1 text-xs font-mono rounded-md bg-zinc-100 dark:bg-zinc-800 text-zinc-700 dark:text-zinc-300 border border-zinc-200 dark:border-zinc-700">
              Length: {detail.length}
            </span>
            <button
              onClick={() => handleCopy(typeof detail.value === 'string' ? detail.value : JSON.stringify(detail.value))}
              className="ml-auto flex items-center space-x-1 px-2.5 py-1 text-xs rounded-md border border-zinc-200 dark:border-zinc-700 text-zinc-600 dark:text-zinc-400 hover:bg-zinc-100 dark:hover:bg-zinc-800 transition-colors"
            >
              {copied ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
              <span>{copied ? 'Copied' : 'Copy Value'}</span>
            </button>
          </div>

          {/* Rendered Value */}
          <div>
            <label className="block text-xs font-medium text-zinc-500 uppercase tracking-wider mb-2">
              Stored Value Payload
            </label>
            {renderValue()}
          </div>
        </div>

        {/* Modal Footer */}
        <div className="px-6 py-3 border-t border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-950/50 flex justify-end">
          <button
            onClick={onClose}
            className="px-4 py-2 text-xs font-medium text-zinc-700 dark:text-zinc-300 hover:bg-zinc-200 dark:hover:bg-zinc-800 rounded-lg transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
