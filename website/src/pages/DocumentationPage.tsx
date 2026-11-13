import React from 'react';
import { SeoMeta } from '../components/common/SeoMeta';
import { CodeBlock } from '../components/common/CodeBlock';
import { Terminal, AlertTriangle, ShieldCheck, Globe, Code } from 'lucide-react';
import { SITE_CONFIG } from '../config/site';

export const DocumentationPage: React.FC = () => {
  return (
    <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 py-10 space-y-12">
      <SeoMeta
        title="Documentation & Developer Guide"
        description="Complete documentation for MiniRedis: build instructions, running the server, connecting redis-cli, Python redis-py integration with protocol=2, and Web Console usage."
      />

      {/* Header */}
      <div className="space-y-4 border-b border-zinc-200 dark:border-zinc-800 pb-8">
        <span className="text-xs font-mono uppercase text-brand-500 font-semibold tracking-wider">
          Developer Documentation
        </span>
        <h1 className="text-3xl sm:text-5xl font-extrabold text-zinc-900 dark:text-zinc-50 tracking-tight">
          Getting Started with MiniRedis
        </h1>
        <p className="text-base text-zinc-600 dark:text-zinc-300 leading-relaxed max-w-3xl">
          Everything you need to build, launch, and integrate MiniRedis v1.0.0 into your applications.
        </p>
      </div>

      {/* Section 1: Prerequisites */}
      <section className="space-y-4">
        <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
          <Terminal className="w-5 h-5 text-brand-500" />
          1. Prerequisites & Environment
        </h2>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          MiniRedis is compiled strictly against Java 17 platform modules without any external runtime dependencies.
        </p>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs">
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-900/50">
            <span className="font-bold text-zinc-900 dark:text-zinc-100 block mb-1">Java Development Kit</span>
            <span className="text-zinc-500">OpenJDK 17 or higher (Eclipse Temurin, Oracle JDK, Corretto).</span>
          </div>
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-900/50">
            <span className="font-bold text-zinc-900 dark:text-zinc-100 block mb-1">Build Tool</span>
            <span className="text-zinc-500">Apache Maven 3.8.0 or higher.</span>
          </div>
        </div>

        <CodeBlock
          code={`# Verify your environment
java -version
mvn -version`}
          language="bash"
          title="Verify Java & Maven Versions"
        />
      </section>

      {/* Section 2: Building */}
      <section className="space-y-4">
        <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
          <ShieldCheck className="w-5 h-5 text-emerald-500" />
          2. Building from Source & Running Tests
        </h2>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          Clone the project and execute the comprehensive 145-test suite or build the executable fat JAR:
        </p>

        <CodeBlock
          code={`# Run all 145 unit, concurrency, AOF crash recovery & network tests
mvn clean test

# Package the runnable executable JAR
mvn clean package

# Output artifact created at: target/${SITE_CONFIG.jarName}`}
          language="bash"
          title="Maven Build & Package"
        />
      </section>

      {/* Section 3: Launching */}
      <section className="space-y-4">
        <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
          <Globe className="w-5 h-5 text-blue-500" />
          3. Launching MiniRedis
        </h2>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          Starting MiniRedis boots the TCP server, the embedded HTTP Web Console, and the interactive terminal REPL:
        </p>

        <CodeBlock
          code={`java -jar target/${SITE_CONFIG.jarName}`}
          language="bash"
          title="Launch MiniRedis Server"
        />

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs">
          <div className="p-3 rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900">
            <span className="font-bold text-zinc-900 dark:text-zinc-100">TCP Server</span>
            <p className="text-zinc-500 mt-1">Port 6379 (RESP2)</p>
          </div>
          <div className="p-3 rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900">
            <span className="font-bold text-zinc-900 dark:text-zinc-100">Web Console</span>
            <p className="text-zinc-500 mt-1">http://localhost:8080</p>
          </div>
          <div className="p-3 rounded-lg border border-zinc-200 dark:border-zinc-800 bg-white dark:bg-zinc-900">
            <span className="font-bold text-zinc-900 dark:text-zinc-100">CLI REPL</span>
            <p className="text-zinc-500 mt-1">Interactive on stdin</p>
          </div>
        </div>
      </section>

      {/* Section 4: Redis Clients */}
      <section className="space-y-4">
        <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
          <Code className="w-5 h-5 text-purple-500" />
          4. Connecting with Redis Clients
        </h2>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          Connect to MiniRedis using standard command-line tools:
        </p>

        <CodeBlock
          code={`# Connect using official redis-cli
redis-cli -h 127.0.0.1 -p 6379

# Or connect using MiniRedis built-in Java client
java -cp target/${SITE_CONFIG.jarName} com.miniredis.client.RedisClient 127.0.0.1 6379

# Or connect via telnet / netcat
nc 127.0.0.1 6379
PING
+PONG`}
          language="bash"
          title="Connecting via CLI & Sockets"
        />
      </section>

      {/* Section 5: Python redis-py */}
      <section className="space-y-4">
        <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
          <Code className="w-5 h-5 text-amber-500" />
          5. Python `redis-py` Integration
        </h2>

        <div className="p-4 rounded-xl border border-amber-500/20 bg-amber-500/5 text-xs text-zinc-700 dark:text-zinc-300 space-y-2">
          <div className="font-bold text-amber-600 dark:text-amber-400 flex items-center gap-1.5">
            <AlertTriangle className="w-4 h-4" />
            Important: Configure protocol=2
          </div>
          <p>
            The Python <code className="font-mono">redis</code> package version 8.x defaults to RESP3 protocol (<code className="font-mono">protocol=3</code>). Because MiniRedis implements the robust RESP2 wire specification, you must pass <code className="font-mono font-semibold">protocol=2</code> when initializing the Redis client.
          </p>
        </div>

        <CodeBlock
          code={`import redis

# Initialize client using RESP2 protocol
r = redis.Redis(host='127.0.0.1', port=6379, protocol=2, decode_responses=True)

# 1. Basic Key-Value
r.set('user:name', 'Krishna')
print(r.get('user:name'))  # Output: Krishna

# 2. Lists & Hashes
r.rpush('tasks', 'task1', 'task2')
r.hset('profile', mapping={'role': 'engineer', 'lang': 'java'})
print(r.hgetall('profile'))  # Output: {'role': 'engineer', 'lang': 'java'}

# 3. Optimistic CAS Transactions with WATCH
with r.pipeline() as pipe:
    pipe.watch('account:balance')
    balance = int(pipe.get('account:balance') or 100)
    pipe.multi()
    pipe.set('account:balance', str(balance + 50))
    pipe.execute()

# 4. Telemetry
stats = r.info('stats')
print(f"Total commands processed: {stats['total_commands_processed']}")`}
          language="python"
          title="python - Complete Integration Example"
        />
      </section>

      {/* Section 6: Web Console */}
      <section className="space-y-4">
        <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
          <Globe className="w-5 h-5 text-emerald-500" />
          6. Embedded Web Console GUI
        </h2>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          MiniRedis includes an embedded web server running on port 8080 powered directly by <code className="text-xs font-mono">jdk.httpserver</code>.
        </p>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 text-xs">
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-900/50 space-y-1">
            <span className="font-bold text-zinc-900 dark:text-zinc-100">Live Telemetry Cards</span>
            <p className="text-zinc-500">Monitor uptime, memory usage, connected TCP clients, and keyspace hits in real time.</p>
          </div>
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-900/50 space-y-1">
            <span className="font-bold text-zinc-900 dark:text-zinc-100">Keyspace Browser</span>
            <p className="text-zinc-500">Explore stored keys with type badges, TTL countdowns, and pattern-based search.</p>
          </div>
          <div className="p-4 rounded-xl border border-zinc-200 dark:border-zinc-800 bg-zinc-50 dark:bg-zinc-900/50 space-y-1">
            <span className="font-bold text-zinc-900 dark:text-zinc-100">Interactive Terminal</span>
            <p className="text-zinc-500">Dispatch commands directly from your browser with full command history navigation.</p>
          </div>
        </div>
      </section>

      {/* Section 7: Stopping Server */}
      <section className="space-y-4">
        <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-100 flex items-center gap-2">
          <Terminal className="w-5 h-5 text-red-500" />
          7. Stopping & Graceful Termination
        </h2>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          You can safely stop MiniRedis using either standard Redis commands or OS signals:
        </p>

        <CodeBlock
          code={`# From any connected Redis client:
SHUTDOWN SAVE   # Flushes AOF to disk and terminates cleanly
SHUTDOWN NOSAVE # Terminates without flushing pending writes

# Or press Ctrl+C in the console where the server is running.`}
          language="bash"
          title="Graceful Server Shutdown"
        />
      </section>
    </div>
  );
};
