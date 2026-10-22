/**
 * Minimal typed HTTP client for the GrandVista API gateway.
 *
 * Security model (see docs/security/SECURITY.md):
 *  - The short-lived access token is held in memory only (never localStorage), so a page reload or a
 *    new tab starts without it and silently obtains a new one with the refresh cookie.
 *  - The refresh token is an HttpOnly, SameSite=Strict cookie the browser sends only to /api/v1/auth.
 *  - Every request carries X-Requested-With, which the auth-service requires on cookie-based endpoints
 *    (a cross-site page cannot add it without passing the gateway's CORS allow-list).
 */

export interface FieldError {
  field: string;
  message: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldError[];
  readonly correlationId?: string;
  readonly properties: Record<string, unknown>;

  constructor(status: number, code: string, message: string, extra: Record<string, unknown> = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fieldErrors = Array.isArray(extra.errors) ? (extra.errors as FieldError[]) : [];
    this.correlationId = typeof extra.correlationId === 'string' ? extra.correlationId : undefined;
    this.properties = extra;
  }
}

let accessToken: string | null = null;
let refreshHandler: (() => Promise<string | null>) | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

/** Registered by the AuthProvider; used to transparently renew an expired access token once. */
export function setRefreshHandler(handler: (() => Promise<string | null>) | null) {
  refreshHandler = handler;
}

export type QueryValue = string | number | boolean | undefined | null | Array<string | number>;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  query?: Record<string, QueryValue>;
  body?: unknown;
  idempotencyKey?: string;
  signal?: AbortSignal;
  /** Set to false for endpoints that must not trigger token refresh (login, refresh itself). */
  retryOnUnauthorized?: boolean;
}

export const API_BASE = '/api/v1';

export function buildUrl(path: string, query?: Record<string, QueryValue>) {
  const params = new URLSearchParams();
  Object.entries(query ?? {}).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    if (Array.isArray(value)) value.forEach((v) => params.append(key, String(v)));
    else params.append(key, String(value));
  });
  const qs = params.toString();
  return `${API_BASE}${path}${qs ? `?${qs}` : ''}`;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', query, body, idempotencyKey, signal, retryOnUnauthorized = true } = options;
  const headers: Record<string, string> = { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData;
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json';
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers,
      body: body === undefined ? undefined : isForm ? (body as FormData) : JSON.stringify(body),
      credentials: 'same-origin',
      signal,
    });
  } catch (error) {
    if ((error as Error).name === 'AbortError') throw error;
    throw new ApiError(0, 'NETWORK_ERROR', 'We could not reach the server. Check your connection and try again.');
  }

  if (response.status === 401 && retryOnUnauthorized && accessToken && refreshHandler) {
    const renewed = await refreshHandler();
    if (renewed) return request<T>(path, { ...options, retryOnUnauthorized: false });
  }

  if (!response.ok) throw await toApiError(response);
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

async function toApiError(response: Response): Promise<ApiError> {
  let problem: Record<string, unknown> = {};
  try {
    problem = (await response.json()) as Record<string, unknown>;
  } catch {
    // not a JSON body (e.g. a proxy error page)
  }
  const code = typeof problem.code === 'string' ? problem.code : `HTTP_${response.status}`;
  const detail =
    typeof problem.detail === 'string' && problem.detail
      ? problem.detail
      : response.status >= 500
        ? 'The service is temporarily unavailable. Please try again shortly.'
        : 'The request could not be completed.';
  return new ApiError(response.status, code, detail, problem);
}

export function newIdempotencyKey() {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}
