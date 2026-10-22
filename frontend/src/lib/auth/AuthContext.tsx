import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { authApi } from '../api/endpoints';
import { setAccessToken, setRefreshHandler } from '../api/http';
import type { AuthResponse, Role, User } from '../api/types';

type Status = 'loading' | 'authenticated' | 'anonymous';

interface AuthContextValue {
  status: Status;
  user: User | null;
  login: (email: string, password: string) => Promise<User>;
  register: (data: { email: string; password: string; fullName: string; phone?: string }) => Promise<User>;
  logout: () => Promise<void>;
  hasRole: (...roles: Role[]) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

// A non-sensitive hint (no token, just "1") so first-time visitors don't make a pointless refresh call.
const SESSION_HINT = 'gv.hasSession';
function setSessionHint(present: boolean) {
  try {
    if (present) localStorage.setItem(SESSION_HINT, '1');
    else localStorage.removeItem(SESSION_HINT);
  } catch {
    // storage unavailable: refresh is simply attempted every time
  }
}
function hasSessionHint() {
  try {
    return localStorage.getItem(SESSION_HINT) === '1';
  } catch {
    return true;
  }
}

/**
 * Session management. The access token lives only in memory; on start-up (and shortly before expiry)
 * a new one is obtained from /auth/refresh using the HttpOnly refresh cookie.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>('loading');
  const [user, setUser] = useState<User | null>(null);
  const refreshTimer = useRef<number | undefined>(undefined);
  const inFlightRefresh = useRef<Promise<string | null> | null>(null);

  const clearSession = useCallback(() => {
    setSessionHint(false);
    window.clearTimeout(refreshTimer.current);
    setAccessToken(null);
    setUser(null);
    setStatus('anonymous');
  }, []);

  // applySession and refresh reference each other (refresh schedules the next refresh); a ref breaks the cycle.
  const applySessionRef = useRef<(session: AuthResponse) => void>(() => {});

  /** Concurrent callers share one in-flight refresh so the rotating refresh token is used only once. */
  const refresh = useCallback((): Promise<string | null> => {
    if (!inFlightRefresh.current) {
      inFlightRefresh.current = authApi
        .refresh()
        .then((session) => {
          applySessionRef.current(session);
          return session.accessToken;
        })
        .catch(() => {
          clearSession();
          return null;
        })
        .finally(() => {
          inFlightRefresh.current = null;
        });
    }
    return inFlightRefresh.current;
  }, [clearSession]);

  const applySession = useCallback(
    (session: AuthResponse) => {
      setAccessToken(session.accessToken);
      setSessionHint(true);
      setUser(session.user);
      setStatus('authenticated');
      window.clearTimeout(refreshTimer.current);
      const renewInMs = Math.max(10_000, (session.expiresIn - 60) * 1000);
      refreshTimer.current = window.setTimeout(() => void refresh(), renewInMs);
    },
    [refresh],
  );
  applySessionRef.current = applySession;

  useEffect(() => {
    setRefreshHandler(refresh);
    if (hasSessionHint()) void refresh();
    else setStatus('anonymous');
    return () => {
      setRefreshHandler(null);
      window.clearTimeout(refreshTimer.current);
    };
  }, [refresh]);

  const login = useCallback(
    async (email: string, password: string) => {
      const session = await authApi.login({ email, password });
      applySession(session);
      return session.user;
    },
    [applySession],
  );

  const register = useCallback(
    async (data: { email: string; password: string; fullName: string; phone?: string }) => {
      const session = await authApi.register(data);
      applySession(session);
      return session.user;
    },
    [applySession],
  );

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      clearSession();
    }
  }, [clearSession]);

  const hasRole = useCallback((...roles: Role[]) => !!user && roles.some((r) => user.roles.includes(r)), [user]);

  const value = useMemo(
    () => ({ status, user, login, register, logout, hasRole }),
    [status, user, login, register, logout, hasRole],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
