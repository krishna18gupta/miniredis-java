/**
 * Centralized site configuration.
 * Configured so repository and release URLs can be customized via environment variables
 * without modifying UI code.
 */
export const SITE_CONFIG = {
  name: 'MiniRedis',
  version: '1.0.0',
  license: 'MIT',
  javaVersion: '17+',
  jarName: 'miniredis-java-1.0.0.jar',
  tagline: 'High-Performance Redis-Compatible In-Memory Database in Pure Java 17',
  description: 'Engineered from first principles with zero external runtime dependencies. Features 57 Redis commands, RESP2 protocol, 256-stripe deadlock-free concurrency, AOF persistence & transactions.',
  
  // URLs configured via Vite environment variables with fallback
  githubRepoUrl: import.meta.env.VITE_GITHUB_REPO_URL || '',
  releaseDownloadUrl: import.meta.env.VITE_RELEASE_DOWNLOAD_URL || '',
  docsUrl: '/docs',
  commandsUrl: '/commands',
  benchmarksUrl: '/benchmarks',

  author: 'Krishna Gupta & MiniRedis Contributors',
  copyrightYear: 2026,

  // Architecture highlights
  stats: {
    supportedCommands: 57,
    automatedTests: 145,
    concurrencyStripes: 256,
    peakInProcessOps: '1.48M op/s',
    peakTcpOps: '68.6K op/s',
    runtimeDependencies: 0,
    compactionDuration: '7 ms',
  },

  // Documented limitations (accurate to v1.0.0)
  limitations: [
    {
      title: 'RESP2 Protocol Only',
      description: 'Full RESP2 compliance. RESP3 features like native maps (%), sets (~), and attributes (|) are not implemented.'
    },
    {
      title: 'Standalone Instance',
      description: 'Runs as a high-performance single-instance database. Redis Cluster slot hashing and master-replica replication are not implemented.'
    },
    {
      title: 'AOF Persistence Only',
      description: 'Durability is achieved using Append-Only File (AOF) with compaction. Point-in-time binary RDB snapshots are not implemented.'
    },
    {
      title: 'Logical Database 0 Only',
      description: 'Standard single keyspace (db 0). SELECT 0 is supported; non-zero indices return an index-out-of-range error.'
    }
  ]
} as const;
