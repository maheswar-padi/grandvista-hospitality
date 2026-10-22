import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import axe from 'axe-core';
import type { ReactNode } from 'react';
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router';
import { vi } from 'vitest';
import { AuthProvider } from '../lib/auth/AuthContext';

type Handler = (url: URL, init: RequestInit) => { status?: number; body?: unknown } | undefined;

/** Routes fetch() calls by "METHOD /path" to handlers; unmatched calls fail loudly. */
export function mockApi(handlers: Record<string, Handler>) {
  const calls: Array<{ method: string; path: string; init: RequestInit }> = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'http://localhost');
    const method = (init.method ?? 'GET').toUpperCase();
    calls.push({ method, path: url.pathname, init });
    const handler = handlers[`${method} ${url.pathname}`];
    const result = handler?.(url, init);
    if (!result) {
      return new Response(JSON.stringify({ status: 404, code: 'NOT_MOCKED', detail: `${method} ${url.pathname}` }), {
        status: 404,
        headers: { 'Content-Type': 'application/problem+json' },
      });
    }
    const status = result.status ?? 200;
    return new Response(status === 204 ? null : JSON.stringify(result.body ?? {}), {
      status,
      headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, calls };
}

export const anonymousSession: Record<string, Handler> = {
  'POST /api/v1/auth/refresh': () => ({ status: 401, body: { status: 401, code: 'INVALID_REFRESH_TOKEN', detail: 'expired' } }),
};

export function customerSession(overrides: Partial<{ roles: string[]; fullName: string }> = {}): Record<string, Handler> {
  const user = { id: 'u-1', email: 'asha@example.test', fullName: overrides.fullName ?? 'Asha Rao', phone: null, roles: overrides.roles ?? ['CUSTOMER'], enabled: true, createdAt: '2026-01-01T00:00:00Z', lastLoginAt: null };
  return { 'POST /api/v1/auth/refresh': () => ({ body: { accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 900, user } }) };
}

export function renderRoutes(routes: RouteObject[], initialPath: string) {
  localStorage.setItem('gv.hasSession', '1'); // let the provider attempt its silent refresh, as a returning visitor would
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [initialPath] });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  );
  return { ...utils, router };
}

export function renderPlain(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '*', element: <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider> }]);
  return render(<RouterProvider router={router} />);
}

/** Runs axe-core against the rendered container and returns serious/critical violations. */
export async function seriousA11yViolations(container: Element) {
  const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  return results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.help}`);
}
