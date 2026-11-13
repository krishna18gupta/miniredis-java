import React, { useState } from 'react';
import { NavLink } from 'react-router-dom';
import { Moon, Sun, Menu, X, Github, Terminal, Activity } from 'lucide-react';
import { useTheme } from '../../context/ThemeContext';
import { Logo } from '../common/Logo';
import { SITE_CONFIG } from '../../config/site';

const NAV_LINKS = [
  { name: 'Home', path: '/' },
  { name: 'Dashboard', path: '/dashboard', badge: 'Live' },
  { name: 'Features', path: '/features' },
  { name: 'Architecture', path: '/architecture' },
  { name: 'Docs', path: '/docs' },
  { name: 'Commands', path: '/commands' },
  { name: 'Benchmarks', path: '/benchmarks' },
  { name: 'Downloads', path: '/downloads' },
  { name: 'About', path: '/about' },
];

export const Navbar: React.FC = () => {
  const { theme, toggleTheme } = useTheme();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  return (
    <header className="sticky top-0 z-50 w-full border-b border-zinc-200 dark:border-zinc-800 bg-white/80 dark:bg-zinc-950/80 backdrop-blur-md transition-colors">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between h-16">
          {/* Logo */}
          <div className="flex-shrink-0">
            <Logo />
          </div>

          {/* Desktop Navigation Links */}
          <nav className="hidden md:flex items-center space-x-1 lg:space-x-2">
            {NAV_LINKS.map(link => (
              <NavLink
                key={link.path}
                to={link.path}
                className={({ isActive }) =>
                  `flex items-center space-x-1 px-2.5 py-1.5 rounded-md text-sm font-medium transition-colors ${
                    isActive
                      ? 'text-brand-600 dark:text-brand-400 bg-brand-500/10'
                      : 'text-zinc-600 dark:text-zinc-400 hover:text-zinc-900 dark:hover:text-zinc-100 hover:bg-zinc-100 dark:hover:bg-zinc-900'
                  }`
                }
              >
                <span>{link.name}</span>
                {link.badge && (
                  <span className="px-1.5 py-0.2 text-[10px] font-bold rounded-full bg-emerald-500 text-white animate-pulse">
                    {link.badge}
                  </span>
                )}
              </NavLink>
            ))}
          </nav>

          {/* Right Action Icons (Theme toggle + GitHub + Terminal quick link) */}
          <div className="hidden md:flex items-center space-x-3">
            <button
              onClick={toggleTheme}
              aria-label="Toggle Theme"
              className="p-2 rounded-lg text-zinc-500 hover:text-zinc-800 dark:text-zinc-400 dark:hover:text-zinc-100 hover:bg-zinc-100 dark:hover:bg-zinc-800 transition-colors"
            >
              {theme === 'dark' ? <Sun className="w-4 h-4 text-amber-400" /> : <Moon className="w-4 h-4 text-zinc-700" />}
            </button>

            {SITE_CONFIG.githubRepoUrl ? (
              <a
                href={SITE_CONFIG.githubRepoUrl}
                target="_blank"
                rel="noreferrer"
                aria-label="GitHub Repository"
                className="p-2 rounded-lg text-zinc-500 hover:text-zinc-800 dark:text-zinc-400 dark:hover:text-zinc-100 hover:bg-zinc-100 dark:hover:bg-zinc-800 transition-colors"
              >
                <Github className="w-4 h-4" />
              </a>
            ) : (
              <NavLink
                to="/about"
                className="flex items-center space-x-1 px-2.5 py-1 text-xs font-mono rounded border border-zinc-200 dark:border-zinc-800 text-zinc-600 dark:text-zinc-400 hover:border-zinc-300 dark:hover:border-zinc-700"
                title="Open Source Pure Java 17 Project"
              >
                <Github className="w-3.5 h-3.5" />
                <span>v1.0.0</span>
              </NavLink>
            )}

            <NavLink
              to="/dashboard"
              className="flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg bg-emerald-600 text-white hover:bg-emerald-500 shadow-sm transition-colors"
            >
              <Activity className="w-3.5 h-3.5" />
              <span>Live Console</span>
            </NavLink>

            <NavLink
              to="/docs"
              className="flex items-center space-x-1.5 px-3 py-1.5 text-xs font-medium rounded-lg bg-zinc-900 text-white hover:bg-zinc-800 dark:bg-brand-600 dark:hover:bg-brand-500 transition-colors"
            >
              <Terminal className="w-3.5 h-3.5" />
              <span>Get Started</span>
            </NavLink>
          </div>

          {/* Mobile menu button */}
          <div className="flex md:hidden items-center space-x-2">
            <button
              onClick={toggleTheme}
              aria-label="Toggle Theme"
              className="p-2 rounded-lg text-zinc-500 dark:text-zinc-400 hover:bg-zinc-100 dark:hover:bg-zinc-800"
            >
              {theme === 'dark' ? <Sun className="w-4 h-4 text-amber-400" /> : <Moon className="w-4 h-4" />}
            </button>
            <button
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
              aria-label="Toggle Mobile Menu"
              className="p-2 rounded-lg text-zinc-600 dark:text-zinc-300 hover:bg-zinc-100 dark:hover:bg-zinc-800"
            >
              {mobileMenuOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
            </button>
          </div>
        </div>
      </div>

      {/* Mobile Drawer */}
      {mobileMenuOpen && (
        <div className="md:hidden border-b border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-950 px-4 pt-2 pb-4 space-y-1">
          {NAV_LINKS.map(link => (
            <NavLink
              key={link.path}
              to={link.path}
              onClick={() => setMobileMenuOpen(false)}
              className={({ isActive }) =>
                `block px-3 py-2 rounded-md text-base font-medium ${
                  isActive
                    ? 'text-brand-600 dark:text-brand-400 bg-brand-500/10'
                    : 'text-zinc-700 dark:text-zinc-300 hover:bg-zinc-100 dark:hover:bg-zinc-900'
                }`
              }
            >
              {link.name}
            </NavLink>
          ))}
          <div className="pt-2 border-t border-zinc-200 dark:border-zinc-800 flex justify-between items-center">
            <NavLink
              to="/docs"
              onClick={() => setMobileMenuOpen(false)}
              className="w-full text-center py-2 text-sm font-medium rounded-md bg-brand-600 text-white"
            >
              Get Started
            </NavLink>
          </div>
        </div>
      )}
    </header>
  );
};
