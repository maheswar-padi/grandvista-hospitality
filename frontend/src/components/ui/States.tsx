import { AlertOctagon, SearchX } from 'lucide-react';
import type { ReactNode } from 'react';
import { ApiError } from '../../lib/api/http';
import { Button } from './Button';
import { cn } from './cn';

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('skeleton', className)} aria-hidden />;
}

export function LoadingBlock({ label = 'Loading…', className }: { label?: string; className?: string }) {
  return (
    <div className={cn('flex flex-col gap-3', className)} role="status" aria-live="polite">
      <span className="sr-only">{label}</span>
      <Skeleton className="h-6 w-1/3" />
      <Skeleton className="h-4 w-2/3" />
      <Skeleton className="h-4 w-1/2" />
    </div>
  );
}

export function EmptyState({ title, children, action, icon }: { title: string; children?: ReactNode; action?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="card flex flex-col items-center gap-3 px-6 py-14 text-center">
      <div className="rounded-full bg-gold-50 p-3 text-gold-600">{icon ?? <SearchX className="size-6" aria-hidden />}</div>
      <h3 className="text-2xl">{title}</h3>
      {children && <div className="max-w-md text-sm text-ink-600">{children}</div>}
      {action}
    </div>
  );
}

export function ErrorState({ error, onRetry, title = 'Something went wrong' }: { error: unknown; onRetry?: () => void; title?: string }) {
  const apiError = error instanceof ApiError ? error : null;
  return (
    <div className="card flex flex-col items-center gap-3 px-6 py-12 text-center" role="alert">
      <div className="rounded-full bg-red-50 p-3 text-red-700">
        <AlertOctagon className="size-6" aria-hidden />
      </div>
      <h3 className="text-2xl">{title}</h3>
      <p className="max-w-md text-sm text-ink-600">
        {apiError?.message ?? 'An unexpected error occurred. Please try again.'}
      </p>
      {apiError?.correlationId && (
        <p className="text-xs text-ink-400">
          Reference: <code>{apiError.correlationId}</code>
        </p>
      )}
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}
