import React from 'react';
import { Link } from 'react-router-dom';
import { SeoMeta } from '../components/common/SeoMeta';
import { Terminal, Home, ArrowLeft } from 'lucide-react';

export const NotFoundPage: React.FC = () => {
  return (
    <div className="max-w-md mx-auto px-4 py-24 text-center space-y-6">
      <SeoMeta
        title="404 - Page Not Found"
        description="The requested page or route does not exist."
      />

      <div className="w-16 h-16 rounded-2xl bg-brand-500/10 text-brand-500 flex items-center justify-center mx-auto">
        <Terminal className="w-8 h-8" />
      </div>

      <div className="space-y-2">
        <span className="text-xs font-mono uppercase text-brand-500 font-semibold tracking-wider">
          (error) ERR 404
        </span>
        <h1 className="text-3xl font-extrabold text-zinc-900 dark:text-zinc-50 tracking-tight">
          Route Not Found
        </h1>
        <p className="text-xs text-zinc-500 font-mono">
          The requested URL path does not map to any active MiniRedis documentation page.
        </p>
      </div>

      <div className="flex flex-col sm:flex-row items-center justify-center gap-3 pt-4">
        <Link
          to="/"
          className="w-full sm:w-auto inline-flex items-center justify-center space-x-2 px-5 py-2.5 rounded-lg text-xs font-medium text-white bg-brand-600 hover:bg-brand-500 transition-colors"
        >
          <Home className="w-4 h-4" />
          <span>Return to Home</span>
        </Link>
        <Link
          to="/commands"
          className="w-full sm:w-auto inline-flex items-center justify-center space-x-2 px-5 py-2.5 rounded-lg text-xs font-medium text-zinc-700 dark:text-zinc-300 bg-zinc-100 dark:bg-zinc-800 hover:bg-zinc-200 dark:hover:bg-zinc-700 transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          <span>Command Catalog</span>
        </Link>
      </div>
    </div>
  );
};
