import React from 'react';
import { SeoMeta } from '../components/common/SeoMeta';
import { SITE_CONFIG } from '../config/site';
import { CodeBlock } from '../components/common/CodeBlock';
import { Download, Package, ShieldCheck, Terminal, AlertCircle } from 'lucide-react';
import { Badge } from '../components/common/Badge';

export const DownloadsPage: React.FC = () => {
  return (
    <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 py-10 space-y-12">
      <SeoMeta
        title="Downloads & Installation"
        description="Download MiniRedis v1.0.0 executable JAR or build from source using Apache Maven on OpenJDK 17+."
      />

      {/* Header */}
      <div className="space-y-4 text-center max-w-3xl mx-auto">
        <span className="text-xs font-mono uppercase text-brand-500 font-semibold tracking-wider">
          Distribution & Binaries
        </span>
        <h1 className="text-3xl sm:text-5xl font-extrabold text-zinc-900 dark:text-zinc-50 tracking-tight">
          Download MiniRedis
        </h1>
        <p className="text-base text-zinc-600 dark:text-zinc-300 leading-relaxed">
          MiniRedis is distributed as a lightweight, zero-dependency executable JAR ready to run on any platform with Java 17 or higher.
        </p>
      </div>

      {/* Release Card */}
      <div className="p-8 rounded-2xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/60 shadow-sm space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between pb-6 border-b border-zinc-200 dark:border-zinc-800 gap-4">
          <div className="flex items-center space-x-3">
            <div className="p-3 rounded-xl bg-brand-500/10 text-brand-500">
              <Package className="w-8 h-8" />
            </div>
            <div>
              <div className="flex items-center space-x-2">
                <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">
                  MiniRedis v{SITE_CONFIG.version}
                </h2>
                <Badge variant="success">Production Ready</Badge>
              </div>
              <p className="text-xs text-zinc-500 font-mono mt-0.5">
                Artifact: {SITE_CONFIG.jarName} &bull; Target: {SITE_CONFIG.javaVersion}
              </p>
            </div>
          </div>

          <div>
            {SITE_CONFIG.releaseDownloadUrl ? (
              <a
                href={SITE_CONFIG.releaseDownloadUrl}
                download
                className="inline-flex items-center space-x-2 px-5 py-2.5 rounded-lg text-sm font-medium text-white bg-brand-600 hover:bg-brand-500 shadow-sm transition-colors"
              >
                <Download className="w-4 h-4" />
                <span>Download JAR</span>
              </a>
            ) : (
              <div className="flex items-center space-x-2 px-4 py-2 rounded-lg bg-zinc-100 dark:bg-zinc-800 text-zinc-500 text-xs font-mono">
                <AlertCircle className="w-4 h-4 text-amber-500" />
                <span>Download via Build Below (Public Release Pending)</span>
              </div>
            )}
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs font-mono">
          <div className="p-3 rounded-lg bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800">
            <span className="text-zinc-400 block text-[10px]">Runtime Dependencies</span>
            <span className="font-bold text-emerald-500">0 (Zero External Libraries)</span>
          </div>
          <div className="p-3 rounded-lg bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800">
            <span className="text-zinc-400 block text-[10px]">Test Coverage</span>
            <span className="font-bold text-zinc-800 dark:text-zinc-200">145 Tests (0 Failures)</span>
          </div>
          <div className="p-3 rounded-lg bg-zinc-50 dark:bg-zinc-950 border border-zinc-200 dark:border-zinc-800">
            <span className="text-zinc-400 block text-[10px]">License</span>
            <span className="font-bold text-zinc-800 dark:text-zinc-200">MIT Open Source</span>
          </div>
        </div>
      </div>

      {/* Build from Source Section */}
      <section className="space-y-4">
        <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
          <Terminal className="w-5 h-5 text-brand-500" />
          Recommended: Build from Source with Maven
        </h2>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          Building from source takes less than 30 seconds and verifies your local environment against the complete test suite:
        </p>

        <CodeBlock
          code={`# 1. Clean checkout and verify 145 automated tests
mvn clean test

# 2. Package the self-contained executable JAR
mvn clean package

# 3. Launch the database
java -jar target/${SITE_CONFIG.jarName}`}
          language="bash"
          title="Build and Run Instructions"
        />
      </section>

      {/* System Requirements */}
      <section className="space-y-4">
        <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
          <ShieldCheck className="w-5 h-5 text-emerald-500" />
          System Requirements
        </h2>

        <div className="overflow-x-auto rounded-xl border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900/40">
          <table className="w-full text-left text-xs font-mono">
            <thead className="bg-zinc-50 dark:bg-zinc-950 border-b border-zinc-200 dark:border-zinc-800 text-zinc-500">
              <tr>
                <th className="p-3 font-semibold">Component</th>
                <th className="p-3 font-semibold">Minimum</th>
                <th className="p-3 font-semibold">Recommended</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-100 dark:divide-zinc-800/60 text-zinc-800 dark:text-zinc-200">
              <tr>
                <td className="p-3 font-medium">Operating System</td>
                <td className="p-3">Windows 10+, Linux (kernel 4.x+), macOS 11+</td>
                <td className="p-3">Modern 64-bit Linux / Windows 11</td>
              </tr>
              <tr>
                <td className="p-3 font-medium">Java Runtime</td>
                <td className="p-3">OpenJDK 17 64-Bit</td>
                <td className="p-3">OpenJDK 17 or 21 LTS (Eclipse Temurin)</td>
              </tr>
              <tr>
                <td className="p-3 font-medium">Memory (RAM)</td>
                <td className="p-3">256 MB JVM Heap</td>
                <td className="p-3">1 GB to 4 GB depending on dataset size</td>
              </tr>
              <tr>
                <td className="p-3 font-medium">Storage</td>
                <td className="p-3">50 MB disk space</td>
                <td className="p-3">SSD / NVMe storage for low-latency AOF fsync</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
};
