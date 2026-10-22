import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import type { Role } from '../lib/api/types';
import { useAuth } from '../lib/auth/AuthContext';
import { LoadingBlock } from './ui/States';
import { EmptyState } from './ui/States';
import { ButtonLink } from './ui/Button';

/**
 * Client-side route guard for a good user experience only. Every protected API call is still authorised
 * by the gateway and by the owning service; hiding a page is never the security boundary.
 */
export function RequireAuth({ children, roles }: { children: ReactNode; roles?: Role[] }) {
  const { status, hasRole } = useAuth();
  const location = useLocation();
  if (status === 'loading') {
    return (
      <div className="container-page py-16">
        <LoadingBlock label="Checking your session…" />
      </div>
    );
  }
  if (status === 'anonymous') {
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  }
  if (roles && !hasRole(...roles)) {
    return (
      <div className="container-page py-16">
        <EmptyState title="You don’t have access to this page" action={<ButtonLink to="/">Go to the home page</ButtonLink>}>
          This area is reserved for hotel personnel.
        </EmptyState>
      </div>
    );
  }
  return <>{children}</>;
}
