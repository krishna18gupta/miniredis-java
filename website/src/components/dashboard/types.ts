export interface ServerHealth {
  status: string;
  service: string;
  version: string;
  uptimeSeconds: number;
  timestamp: number;
  demoMode: boolean;
}

export interface TelemetryPoint {
  t: number;
  ops: number;
  mem: number;
  jvm: number;
  keys: number;
}

export interface ServerInfo {
  status: string;
  version: string;
  uptimeSeconds: number;
  demoMode: boolean;
  requiresAuth: boolean;
  keyspace: {
    totalKeys: number;
    stringKeys: number;
    listKeys: number;
    setKeys: number;
    hashKeys: number;
    expiringKeys: number;
    avgTtlMs: number;
    hits: number;
    misses: number;
    hitRatePercent: number;
  };
  memory: {
    estimatedDatabaseBytes: number;
    jvmUsedBytes: number;
    jvmFreeBytes: number;
    jvmTotalBytes: number;
    jvmMaxBytes: number;
  };
  operations: {
    totalCompleted: number;
    failed: number;
    opsPerSecond: number;
    stripedLockCount: number;
    contentionCount: number;
  };
  clients: {
    connectedClients: number;
    totalConnectionsReceived: number;
  };
  aof: {
    enabled: boolean;
    healthStatus: string;
    fileSize: number;
    fsyncPolicy: string;
    lastSuccessfulFsyncTime: number;
    lastFsyncDurationMs: number;
    pendingQueueSize: number;
    totalLoggedCommands: number;
    totalFsyncs: number;
    isRewriting: boolean;
    lastRewriteDurationMs: number;
    recoveryDurationMs: number;
    recoveryErrors: number;
  };
  telemetry: TelemetryPoint[];
}

export interface KeyItem {
  key: string;
  type: 'string' | 'list' | 'set' | 'hash';
  ttl: number;
  ttlFormatted: string;
  isExpiring: boolean;
  size: number;
  preview: string;
}

export interface KeysListResponse {
  status: string;
  totalKeys: number;
  matchingKeys: number;
  offset: number;
  limit: number;
  keys: KeyItem[];
}

export interface KeyDetailResponse {
  status: string;
  key: string;
  type: string;
  ttl: number;
  ttlFormatted: string;
  isExpiring: boolean;
  length: number;
  value: any;
}

export interface CommandExecResponse {
  status: 'ok' | 'error';
  type: string;
  output: string;
}
