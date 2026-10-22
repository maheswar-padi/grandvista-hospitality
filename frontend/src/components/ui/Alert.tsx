import { AlertTriangle, CheckCircle2, Info, XCircle } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from './cn';

type Tone = 'info' | 'success' | 'warning' | 'error';

const tones: Record<Tone, { box: string; icon: ReactNode }> = {
  info: { box: 'border-ink-200 bg-ink-50 text-ink-800', icon: <Info className="size-5 shrink-0 text-ink-600" aria-hidden /> },
  success: { box: 'border-emerald-200 bg-emerald-50 text-emerald-900', icon: <CheckCircle2 className="size-5 shrink-0 text-emerald-700" aria-hidden /> },
  warning: { box: 'border-amber-200 bg-amber-50 text-amber-900', icon: <AlertTriangle className="size-5 shrink-0 text-amber-700" aria-hidden /> },
  error: { box: 'border-red-200 bg-red-50 text-red-900', icon: <XCircle className="size-5 shrink-0 text-red-700" aria-hidden /> },
};

export function Alert({ tone = 'info', title, children, className }: { tone?: Tone; title?: string; children?: ReactNode; className?: string }) {
  const t = tones[tone];
  return (
    <div role={tone === 'error' ? 'alert' : 'status'} className={cn('flex gap-3 rounded-xl border p-4 text-sm', t.box, className)}>
      {t.icon}
      <div className="space-y-1">
        {title && <p className="font-semibold">{title}</p>}
        {children && <div className="leading-relaxed">{children}</div>}
      </div>
    </div>
  );
}
