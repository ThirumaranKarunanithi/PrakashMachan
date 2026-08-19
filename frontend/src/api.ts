/**
 * Cross-origin deployment support. When the static frontend is hosted apart from the
 * backend (e.g. Hostinger serving dist/, Railway serving the API), config.js sets
 * window.API_BASE_URL and every '/api' call is rewritten to that origin with the
 * session cookie included. Left empty, everything stays same-origin (dev proxy).
 */
declare global {
  interface Window { API_BASE_URL?: string }
}

const raw = window.API_BASE_URL || '';
export const API_BASE = String(raw).replace(/\/+$/, '');

/** Absolute URL for non-fetch uses of the API (download links). */
export const api = (path: string) => `${API_BASE}${path}`;

if (API_BASE) {
  const orig = window.fetch.bind(window);
  window.fetch = (input, init) => {
    if (typeof input === 'string' && input.startsWith('/api')) {
      return orig(API_BASE + input, { ...init, credentials: 'include' });
    }
    return orig(input, init);
  };
}
