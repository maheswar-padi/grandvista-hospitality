import type { ReactNode } from 'react';
import { cn } from '../ui/cn';

export function AdminHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="mb-8 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-4xl">{title}</h1>
        {description && <p className="mt-1 max-w-2xl text-sm text-ink-600">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function StatTile({ label, value, sub, emphasis }: { label: string; value: ReactNode; sub?: ReactNode; emphasis?: boolean }) {
  return (
    <div className="card p-5">
      <p className="text-sm text-ink-600">{label}</p>
      <p className={cn('mt-1 font-sans font-semibold tabular-nums text-ink-900', emphasis ? 'text-5xl' : 'text-3xl')}>{value}</p>
      {sub && <p className="mt-1 text-xs text-ink-500">{sub}</p>}
    </div>
  );
}

export function Table({ children, caption }: { children: ReactNode; caption: string }) {
  return (
    <div className="card relative overflow-x-auto">
      <table className="w-full min-w-[640px] text-left text-sm">
        <caption className="sr-only">{caption}</caption>
        {children}
      </table>
    </div>
  );
}

export const th = 'border-b border-ink-100 bg-ink-50/60 px-4 py-3 text-xs font-semibold uppercase tracking-wider text-ink-600';
export const td = 'border-b border-ink-100 px-4 py-3 align-top';
