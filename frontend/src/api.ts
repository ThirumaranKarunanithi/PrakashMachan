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

/** Session-bound CSRF token, fetched from /api/auth/csrf after sign-in (App sets it). */
export function setCsrfToken(token: string | null) {
  (window as unknown as { __CSRF?: string | null }).__CSRF = token;
}

const orig = window.fetch.bind(window);
window.fetch = (input, init) => {
  if (typeof input === 'string' && input.startsWith('/api')) {
    const method = (init?.method ?? 'GET').toUpperCase();
    const csrf = (window as unknown as { __CSRF?: string | null }).__CSRF;
    const headers = new Headers(init?.headers);
    if (csrf && method !== 'GET' && method !== 'HEAD') headers.set('X-XSRF-TOKEN', csrf);
    const next: RequestInit = { ...init, headers };
    if (API_BASE) next.credentials = 'include';
    return orig(API_BASE + input, next);
  }
  return orig(input, init);
};
