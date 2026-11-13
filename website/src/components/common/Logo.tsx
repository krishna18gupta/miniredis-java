import React from 'react';
import { Link } from 'react-router-dom';

interface LogoProps {
  size?: 'sm' | 'md' | 'lg';
  showText?: boolean;
}

export const Logo: React.FC<LogoProps> = ({ size = 'md', showText = true }) => {
  const sizeMap = {
    sm: { icon: 'w-6 h-6', text: 'text-base' },
    md: { icon: 'w-8 h-8', text: 'text-lg' },
    lg: { icon: 'w-12 h-12', text: 'text-2xl' }
  };

  return (
    <Link to="/" className="flex items-center space-x-2.5 group">
      <div className={`relative ${sizeMap[size].icon} flex-shrink-0 transition-transform group-hover:scale-105`}>
        <svg viewBox="0 0 64 64" fill="none" className="w-full h-full drop-shadow">
          <defs>
            <linearGradient id="logo-redis" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" stop-color="#EF4444" />
              <stop offset="100%" stop-color="#B91C1C" />
            </linearGradient>
            <linearGradient id="logo-bolt" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" stop-color="#FDE047" />
              <stop offset="100%" stop-color="#EAB308" />
            </linearGradient>
          </defs>
          <path d="M12 40 C12 36 52 36 52 40 L52 48 C52 53 12 53 12 48 Z" fill="url(#logo-redis)" opacity="0.8" />
          <path d="M12 27 C12 23 52 23 52 27 L52 35 C52 40 12 40 12 35 Z" fill="url(#logo-redis)" opacity="0.9" />
          <path d="M12 14 C12 10 52 10 52 14 L52 22 C52 27 12 27 12 22 Z" fill="url(#logo-redis)" />
          <ellipse cx="32" cy="14" rx="20" ry="6" fill="#F87171" />
          <path d="M35 15 L23 31 L32 31 L29 49 L43 29 L33 29 Z" fill="url(#logo-bolt)" stroke="#18181B" stroke-width="1.5" stroke-linejoin="round" />
        </svg>
      </div>
      {showText && (
        <div className="flex items-baseline space-x-1">
          <span className={`font-bold tracking-tight text-zinc-900 dark:text-zinc-100 ${sizeMap[size].text}`}>
            Mini<span className="text-brand-500">Redis</span>
          </span>
          <span className="text-[10px] font-mono text-zinc-400 dark:text-zinc-500 font-semibold px-1 rounded bg-zinc-200 dark:bg-zinc-800">
            v1.0.0
          </span>
        </div>
      )}
    </Link>
  );
};
