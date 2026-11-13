import { ServerHealth, ServerInfo, KeysListResponse, KeyDetailResponse, CommandExecResponse } from './types';

const STORAGE_KEY_BACKEND_URL = 'miniredis_backend_url';
const STORAGE_KEY_ADMIN_TOKEN = 'miniredis_admin_token';

export const DEFAULT_BACKEND_URL =
  import.meta.env.VITE_API_BASE_URL || (typeof window !== 'undefined' && window.location.hostname === 'localhost' ? 'http://localhost:8080' : 'http://localhost:8080');

export function getBackendUrl(): string {
  if (typeof window === 'undefined') return DEFAULT_BACKEND_URL;
  return localStorage.getItem(STORAGE_KEY_BACKEND_URL) || DEFAULT_BACKEND_URL;
}

export function setBackendUrl(url: string): void {
  if (typeof window === 'undefined') return;
  const cleanUrl = url.trim().replace(/\/+$/, '');
  localStorage.setItem(STORAGE_KEY_BACKEND_URL, cleanUrl);
}

export function getAdminToken(): string {
  if (typeof window === 'undefined') return '';
  return localStorage.getItem(STORAGE_KEY_ADMIN_TOKEN) || '';
}

export function setAdminToken(token: string): void {
  if (typeof window === 'undefined') return;
  localStorage.setItem(STORAGE_KEY_ADMIN_TOKEN, token.trim());
}

function getHeaders(): HeadersInit {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  const token = getAdminToken();
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
    headers['X-Admin-Token'] = token;
  }
  return headers;
}

export async function fetchHealth(): Promise<ServerHealth> {
  const url = `${getBackendUrl()}/api/health`;
  const res = await fetch(url, { headers: getHeaders(), cache: 'no-store' });
  if (!res.ok) {
    throw new Error(`Health check failed (HTTP ${res.status})`);
  }
  return res.json();
}

export async function fetchInfo(): Promise<ServerInfo> {
  const url = `${getBackendUrl()}/api/info`;
  const res = await fetch(url, { headers: getHeaders(), cache: 'no-store' });
  if (!res.ok) {
    throw new Error(`Failed to fetch database telemetry (HTTP ${res.status})`);
  }
  return res.json();
}

export async function fetchKeys(
  pattern = '*',
  type = 'all',
  limit = 50,
  offset = 0
): Promise<KeysListResponse> {
  const params = new URLSearchParams({
    pattern,
    type,
    limit: limit.toString(),
    offset: offset.toString(),
  });
  const url = `${getBackendUrl()}/api/keys?${params.toString()}`;
  const res = await fetch(url, { headers: getHeaders(), cache: 'no-store' });
  if (!res.ok) {
    throw new Error(`Failed to fetch keyspace (HTTP ${res.status})`);
  }
  return res.json();
}

export async function fetchKeyDetail(key: string): Promise<KeyDetailResponse> {
  const url = `${getBackendUrl()}/api/keys?key=${encodeURIComponent(key)}`;
  const res = await fetch(url, { headers: getHeaders(), cache: 'no-store' });
  if (!res.ok) {
    throw new Error(`Failed to inspect key "${key}" (HTTP ${res.status})`);
  }
  return res.json();
}

export async function deleteKey(key: string): Promise<void> {
  const url = `${getBackendUrl()}/api/keys?key=${encodeURIComponent(key)}`;
  const res = await fetch(url, {
    method: 'DELETE',
    headers: getHeaders(),
  });
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.message || `Failed to delete key "${key}" (HTTP ${res.status})`);
  }
}

export async function executeCommand(cmdLine: string): Promise<CommandExecResponse> {
  const url = `${getBackendUrl()}/api/exec`;
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      ...getHeaders(),
      'Content-Type': 'text/plain; charset=UTF-8',
    },
    body: cmdLine,
  });

  const data = await res.json().catch(() => ({
    status: 'error',
    type: 'ERROR',
    output: `(error) HTTP ${res.status} ${res.statusText}`,
  }));

  if (!res.ok && data.status !== 'error') {
    return {
      status: 'error',
      type: 'ERROR',
      output: data.message || `(error) HTTP ${res.status}`,
    };
  }
  return data;
}
