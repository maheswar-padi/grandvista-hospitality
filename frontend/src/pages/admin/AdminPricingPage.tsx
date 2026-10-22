import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { AdminHeader, Table, td, th } from '../../components/admin/AdminUi';
import { Alert } from '../../components/ui/Alert';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { TextField } from '../../components/ui/Field';
import { ErrorState, LoadingBlock } from '../../components/ui/States';
import { adminApi } from '../../lib/api/endpoints';
import { ApiError } from '../../lib/api/http';
import type { PricingPolicy } from '../../lib/api/types';
import { useAuth } from '../../lib/auth/AuthContext';
import { formatDate, formatDateTime } from '../../lib/format';

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
type Draft = Omit<PricingPolicy, 'id' | 'version' | 'status' | 'createdBy' | 'createdAt'>;

function toDraft(p: PricingPolicy): Draft {
  return {
    weekendDays: p.weekendDays, weekendMultiplier: p.weekendMultiplier, minPriceFactor: p.minPriceFactor, maxPriceFactor: p.maxPriceFactor,
    serviceFeeRate: p.serviceFeeRate, taxRate: p.taxRate, seasons: p.seasons.map((s) => ({ ...s })), demandTiers: p.demandTiers.map((t) => ({ ...t })), notes: '',
  };
}

function PolicySummary({ p }: { p: PricingPolicy }) {
  return (
    <dl className="grid gap-4 text-sm sm:grid-cols-3">
      <div><dt className="text-ink-500">Weekend nights</dt><dd className="font-medium">{p.weekendDays.map((d) => d.slice(0, 3)).join(', ') || 'none'} × {p.weekendMultiplier}</dd></div>
      <div><dt className="text-ink-500">Price limits</dt><dd className="font-medium">{p.minPriceFactor}× – {p.maxPriceFactor}× base rate</dd></div>
      <div><dt className="text-ink-500">Fees &amp; tax</dt><dd className="font-medium">{Math.round(p.serviceFeeRate * 100)}% service · {Math.round(p.taxRate * 100)}% tax</dd></div>
      <div className="sm:col-span-3"><dt className="text-ink-500">Seasons</dt><dd className="font-medium">{p.seasons.length ? p.seasons.map((s) => `${s.name} (${formatDate(s.startDate, 'd MMM yyyy')} – ${formatDate(s.endDate, 'd MMM yyyy')}, ×${s.multiplier})`).join(' · ') : 'none'}</dd></div>
      <div className="sm:col-span-3"><dt className="text-ink-500">Demand tiers (room-type occupancy → multiplier)</dt><dd className="font-medium">{p.demandTiers.map((t) => `≥${Math.round(t.minOccupancy * 100)}% → ×${t.multiplier}`).join(' · ')}</dd></div>
    </dl>
  );
}

export default function AdminPricingPage() {
  const { hasRole } = useAuth();
  const queryClient = useQueryClient();
  const active = useQuery({ queryKey: ['admin', 'policy'], queryFn: adminApi.activePolicy });
  const history = useQuery({ queryKey: ['admin', 'policies'], queryFn: adminApi.policies });
  const [draft, setDraft] = useState<Draft | null>(null);
  const publish = useMutation({
    mutationFn: (d: Draft) => adminApi.publishPolicy(d),
    onSuccess: () => {
      setDraft(null);
      void queryClient.invalidateQueries({ queryKey: ['admin', 'policy'] });
      void queryClient.invalidateQueries({ queryKey: ['admin', 'policies'] });
    },
  });

  if (active.isPending) return <LoadingBlock />;
  if (active.isError) return <ErrorState error={active.error} onRetry={() => active.refetch()} />;
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((d) => (d ? { ...d, [key]: value } : d));
  const num = (v: string) => (v === '' ? 0 : Number(v));

  return (
    <>
      <AdminHeader
        title="Dynamic pricing"
        description="nightly rate = base × weekend × season × demand, clamped to the price limits and rounded to the paisa; then service fee and tax. Publishing creates a new immutable version; existing bookings keep the price they were quoted."
        actions={hasRole('ADMIN') && !draft && <Button onClick={() => setDraft(toDraft(active.data))}>Edit as new version</Button>}
      />
      <section className="card p-6">
        <div className="mb-4 flex items-center gap-2"><h2 className="text-2xl">Version {active.data.version}</h2><Badge tone="success">Active</Badge></div>
        <PolicySummary p={active.data} />
        {active.data.notes && <p className="mt-4 text-sm text-ink-600">{active.data.notes}</p>}
      </section>

      {draft && (
        <section className="card mt-6 space-y-6 p-6" aria-label="New pricing version">
          <h2 className="text-2xl">New version</h2>
          <fieldset>
            <legend className="mb-2 text-sm font-medium">Weekend nights</legend>
            <div className="flex flex-wrap gap-3">
              {DAYS.map((d) => (
                <label key={d} className="flex items-center gap-1.5 text-sm">
                  <input type="checkbox" className="size-4 accent-ink-900" checked={draft.weekendDays.includes(d)}
                    onChange={(e) => set('weekendDays', e.target.checked ? [...draft.weekendDays, d] : draft.weekendDays.filter((x) => x !== d))} />
                  {d.slice(0, 3)}
                </label>
              ))}
            </div>
          </fieldset>
          <div className="grid gap-4 sm:grid-cols-3">
            <TextField label="Weekend multiplier" type="number" step="0.01" value={draft.weekendMultiplier} onChange={(e) => set('weekendMultiplier', num(e.target.value))} />
            <TextField label="Minimum (× base)" type="number" step="0.01" value={draft.minPriceFactor} onChange={(e) => set('minPriceFactor', num(e.target.value))} />
            <TextField label="Maximum (× base)" type="number" step="0.01" value={draft.maxPriceFactor} onChange={(e) => set('maxPriceFactor', num(e.target.value))} />
            <TextField label="Service fee rate" hint="0.05 = 5%" type="number" step="0.01" value={draft.serviceFeeRate} onChange={(e) => set('serviceFeeRate', num(e.target.value))} />
            <TextField label="Tax rate" hint="0.12 = 12%" type="number" step="0.01" value={draft.taxRate} onChange={(e) => set('taxRate', num(e.target.value))} />
          </div>
          <fieldset>
            <legend className="mb-2 text-sm font-medium">Seasons (inclusive dates, must not overlap)</legend>
            <div className="space-y-2">
              {draft.seasons.map((s, i) => (
                <div key={i} className="grid items-end gap-2 sm:grid-cols-[1fr_auto_auto_120px_auto]">
                  <TextField label="Name" value={s.name} onChange={(e) => set('seasons', draft.seasons.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
                  <TextField label="From" type="date" value={s.startDate} onChange={(e) => set('seasons', draft.seasons.map((x, j) => (j === i ? { ...x, startDate: e.target.value } : x)))} />
                  <TextField label="To" type="date" value={s.endDate} onChange={(e) => set('seasons', draft.seasons.map((x, j) => (j === i ? { ...x, endDate: e.target.value } : x)))} />
                  <TextField label="Multiplier" type="number" step="0.01" value={s.multiplier} onChange={(e) => set('seasons', draft.seasons.map((x, j) => (j === i ? { ...x, multiplier: num(e.target.value) } : x)))} />
                  <Button variant="ghost" aria-label={`Remove season ${s.name}`} onClick={() => set('seasons', draft.seasons.filter((_, j) => j !== i))}><Trash2 className="size-4" aria-hidden /></Button>
                </div>
              ))}
            </div>
            <Button variant="secondary" size="sm" className="mt-2" icon={<Plus className="size-4" aria-hidden />}
              onClick={() => set('seasons', [...draft.seasons, { name: 'New season', startDate: '', endDate: '', multiplier: 1 }])}>Add season</Button>
          </fieldset>
          <fieldset>
            <legend className="mb-2 text-sm font-medium">Demand tiers (a tier at occupancy 0 is required)</legend>
            <div className="space-y-2">
              {draft.demandTiers.map((t, i) => (
                <div key={i} className="grid items-end gap-2 sm:grid-cols-[160px_160px_auto]">
                  <TextField label="From occupancy" hint="0.8 = 80%" type="number" step="0.05" value={t.minOccupancy} onChange={(e) => set('demandTiers', draft.demandTiers.map((x, j) => (j === i ? { ...x, minOccupancy: num(e.target.value) } : x)))} />
                  <TextField label="Multiplier" type="number" step="0.01" value={t.multiplier} onChange={(e) => set('demandTiers', draft.demandTiers.map((x, j) => (j === i ? { ...x, multiplier: num(e.target.value) } : x)))} />
                  <Button variant="ghost" aria-label="Remove tier" onClick={() => set('demandTiers', draft.demandTiers.filter((_, j) => j !== i))}><Trash2 className="size-4" aria-hidden /></Button>
                </div>
              ))}
            </div>
            <Button variant="secondary" size="sm" className="mt-2" icon={<Plus className="size-4" aria-hidden />} onClick={() => set('demandTiers', [...draft.demandTiers, { minOccupancy: 0.9, multiplier: 1.3 }])}>Add tier</Button>
          </fieldset>
          <TextField label="Notes" value={draft.notes ?? ''} maxLength={500} onChange={(e) => set('notes', e.target.value)} />
          {publish.error instanceof ApiError && (
            <Alert tone="error" title="The policy was not published">
              {publish.error.fieldErrors.length ? publish.error.fieldErrors.map((f) => `${f.field}: ${f.message}`).join('; ') : publish.error.message}
            </Alert>
          )}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setDraft(null)}>Discard</Button>
            <Button loading={publish.isPending} onClick={() => publish.mutate({ ...draft, notes: draft.notes || null })}>Publish version {active.data.version + 1}</Button>
          </div>
        </section>
      )}

      <h2 className="mt-10 mb-4 text-2xl">History</h2>
      {history.data && (
        <Table caption="Pricing policy versions">
          <thead><tr><th className={th}>Version</th><th className={th}>Status</th><th className={th}>Published</th><th className={th}>By</th><th className={th}>Notes</th></tr></thead>
          <tbody>
            {history.data.map((p) => (
              <tr key={p.id}>
                <td className={td}>v{p.version}</td>
                <td className={td}><Badge tone={p.status === 'ACTIVE' ? 'success' : 'neutral'}>{p.status.toLowerCase()}</Badge></td>
                <td className={td}>{formatDateTime(p.createdAt)}</td>
                <td className={`${td} text-xs text-ink-500`}>{p.createdBy}</td>
                <td className={`${td} text-xs`}>{p.notes}</td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </>
  );
}
