import React from 'react';
import { Link } from 'react-router-dom';
import { Logo } from '../common/Logo';
import { SITE_CONFIG } from '../../config/site';
import { Cpu, ShieldCheck, Zap } from 'lucide-react';

export const Footer: React.FC = () => {
  return (
    <footer className="border-t border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-950 text-zinc-600 dark:text-zinc-400 text-sm mt-20 transition-colors">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-12">
        <div className="grid grid-cols-1 md:grid-cols-4 gap-8 lg:gap-12">
          {/* Brand Col */}
          <div className="md:col-span-1 space-y-3">
            <Logo size="md" />
            <p className="text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
              High-performance, Redis-compatible in-memory database built from scratch in pure Java 17 with zero external runtime dependencies.
            </p>
            <div className="flex flex-col space-y-1.5 pt-2 text-xs">
              <span className="inline-flex items-center text-zinc-500">
                <Cpu className="w-3.5 h-3.5 mr-1.5 text-brand-500" />
                Pure Java 17 Platform Standard
              </span>
              <span className="inline-flex items-center text-zinc-500">
                <Zap className="w-3.5 h-3.5 mr-1.5 text-amber-500" />
                256-Stripe Concurrency Engine
              </span>
              <span className="inline-flex items-center text-zinc-500">
                <ShieldCheck className="w-3.5 h-3.5 mr-1.5 text-emerald-500" />
                145/145 Automated Tests Passing
              </span>
            </div>
          </div>

          {/* Navigation Links */}
          <div>
            <h4 className="font-semibold text-zinc-900 dark:text-zinc-100 text-xs uppercase tracking-wider mb-3">
              Explore
            </h4>
            <ul className="space-y-2 text-xs">
              <li>
                <Link to="/features" className="hover:text-brand-500 transition-colors">
                  Core Features
                </Link>
              </li>
              <li>
                <Link to="/architecture" className="hover:text-brand-500 transition-colors">
                  Architecture & Design
                </Link>
              </li>
              <li>
                <Link to="/commands" className="hover:text-brand-500 transition-colors">
                  57 Supported Commands
                </Link>
              </li>
              <li>
                <Link to="/benchmarks" className="hover:text-brand-500 transition-colors">
                  Performance Benchmarks
                </Link>
              </li>
            </ul>
          </div>

          {/* Developer Docs */}
          <div>
            <h4 className="font-semibold text-zinc-900 dark:text-zinc-100 text-xs uppercase tracking-wider mb-3">
              Developer Docs
            </h4>
            <ul className="space-y-2 text-xs">
              <li>
                <Link to="/docs" className="hover:text-brand-500 transition-colors">
                  Quick Start Guide
                </Link>
              </li>
              <li>
                <Link to="/docs" className="hover:text-brand-500 transition-colors">
                  Python redis-py Integration
                </Link>
              </li>
              <li>
                <Link to="/downloads" className="hover:text-brand-500 transition-colors">
                  Download JAR & Build
                </Link>
              </li>
              <li>
                <Link to="/about" className="hover:text-brand-500 transition-colors">
                  Project Limitations & Roadmap
                </Link>
              </li>
            </ul>
          </div>

          {/* Project & Community */}
          <div>
            <h4 className="font-semibold text-zinc-900 dark:text-zinc-100 text-xs uppercase tracking-wider mb-3">
              Open Source
            </h4>
            <ul className="space-y-2 text-xs">
              <li>
                <Link to="/about" className="hover:text-brand-500 transition-colors">
                  About MiniRedis
                </Link>
              </li>
              <li>
                <span className="text-zinc-400 dark:text-zinc-500">License: MIT</span>
              </li>
              <li>
                <span className="text-zinc-400 dark:text-zinc-500">Target: {SITE_CONFIG.javaVersion}</span>
              </li>
              <li>
                <span className="text-zinc-400 dark:text-zinc-500">Protocol: RESP2</span>
              </li>
            </ul>
          </div>
        </div>

        <div className="border-t border-zinc-200 dark:border-zinc-800 mt-10 pt-6 flex flex-col sm:flex-row items-center justify-between text-xs text-zinc-500">
          <p>© {SITE_CONFIG.copyrightYear} {SITE_CONFIG.author}. Licensed under the MIT License.</p>
          <p className="mt-2 sm:mt-0 text-[11px] text-zinc-400">
            MiniRedis is an independent project. Redis is a registered trademark of Redis Ltd.
          </p>
        </div>
      </div>
    </footer>
  );
};
