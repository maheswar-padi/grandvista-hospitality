import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import { AdminHeader, Table, td, th } from '../../components/admin/AdminUi';
import { Alert } from '../../components/ui/Alert';
import { Badge, type BadgeTone } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { ErrorState, LoadingBlock } from '../../components/ui/States';
import { adminApi } from '../../lib/api/endpoints';
import { ApiError } from '../../lib/api/http';
import type { FindingType, Property, ReconciliationReport } from '../../lib/api/types';
import { useAdminProperty } from '../../lib/adminProperty';
import { formatDate, formatDateTime } from '../../lib/format';

export const findingLabel: Record<FindingType, string> = {
  ORPHAN_HOLD: 'Orphan holds',
  ALLOCATION_WITHOUT_BOOKING: 'Allocations without booking',
  STATUS_MISMATCH: 'Status mismatches',
  CONFIRMED_BOOKING_WITHOUT_ALLOCATION: 'Confirmed bookings without room',
  PENDING_BOOKING_WITHOUT_HOLD: 'Pending bookings without hold',
};

const severityTone: Record<'WARNING' | 'CRITICAL', BadgeTone> = { WARNING: 'warning', CRITICAL: 'danger' };

function RunSummary({ report }: { report: ReconciliationReport }) {
  return (
    <p className="text-sm text-ink-600">
      {report.trigger === 'SCHEDULED' ? 'Scheduled run' : 'Manual run'} · {formatDateTime(report.finishedAt)} ·{' '}
      {report.allocationsChecked} allocations and {report.bookingsChecked} bookings checked ·{' '}
      {report.releasedCount} expired orphan hold{report.releasedCount === 1 ? '' : 's'} released
    </p>
  );
}

function HotelReconciliation({ property, detailed }: { property: Property; detailed: boolean }) {
  const queryClient = useQueryClient();
  const latest = useQuery({
    queryKey: ['admin', 'reconciliation', property.id],
    queryFn: () => adminApi.reconciliation(property.id),
    retry: false,
  });
  const runs = useQuery({
    queryKey: ['admin', 'reconciliation-runs', property.id],
    queryFn: () => adminApi.reconciliationRuns(property.id, 10),
    enabled: detailed,
  });
  const run = useMutation({
    mutationFn: () => adminApi.runReconciliation(property.id),
    onSuccess: (report) => {
      queryClient.setQueryData(['admin', 'reconciliation', property.id], report);
      void queryClient.invalidateQueries({ queryKey: ['admin', 'reconciliation-runs', property.id] });
    },
  });
  const notRun = latest.error instanceof ApiError && latest.error.status === 404;
  const report = latest.data;

  return (
    <section className="card p-6" aria-label={`Reconciliation for ${property.name}`}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-2xl">{property.name}</h2>
          <p className="text-sm text-ink-500">{property.city}, {property.country} · {property.timeZone}</p>
        </div>
        <Button size="sm" variant="secondary" loading={run.isPending} onClick={() => run.mutate()} icon={<RefreshCw className="size-4" aria-hidden />}>
          Run now
        </Button>
      </div>
      {run.error instanceof ApiError && <Alert tone="error" className="mt-4">{run.error.message}</Alert>}

      <div className="mt-4">
        {latest.isPending ? (
          <LoadingBlock />
        ) : notRun ? (
          <p className="text-sm text-ink-600">No reconciliation has run for this hotel yet. The scheduled job runs every few minutes, or use “Run now”.</p>
        ) : latest.isError ? (
          <ErrorState error={latest.error} onRetry={() => latest.refetch()} />
        ) : report ? (
          <>
            <div className="flex flex-wrap items-center gap-2">
              {report.status === 'FAILED' ? (
                <Badge tone="danger">Run failed</Badge>
              ) : report.findingCount === 0 ? (
                <Badge tone="success">In balance</Badge>
              ) : (
                <Badge tone="warning">{report.findingCount} finding{report.findingCount === 1 ? '' : 's'}</Badge>
              )}
              <RunSummary report={report} />
            </div>
            {report.error && <Alert tone="error" className="mt-3">{report.error}</Alert>}
            <ul className="mt-4 grid gap-2 sm:grid-cols-2 xl:grid-cols-5" aria-label="Findings by type">
              {(Object.keys(findingLabel) as FindingType[]).map((t) => (
                <li key={t} className="rounded-xl bg-ink-50 px-3 py-2">
                  <span className="block text-xs text-ink-600">{findingLabel[t]}</span>
                  <span className="text-xl font-semibold tabular-nums">{report.countsByType[t] ?? 0}</span>
                </li>
              ))}
            </ul>
            {detailed && report.findings.length > 0 && (
              <div className="mt-5">
                <Table caption={`Findings for ${property.name}`}>
                  <thead><tr><th className={th}>Severity</th><th className={th}>Finding</th><th className={th}>Booking</th><th className={th}>Room · stay</th><th className={th}>Status (room / booking)</th><th className={th}>Action</th></tr></thead>
                  <tbody>
                    {report.findings.map((f) => (
                      <tr key={`${f.type}-${f.allocationId ?? f.bookingId}`}>
                        <td className={td}><Badge tone={severityTone[f.severity]}>{f.severity === 'CRITICAL' ? 'Critical' : 'Warning'}</Badge></td>
                        <td className={td}>{findingLabel[f.type]}<span className="block max-w-64 text-xs text-ink-500">{f.detail}</span></td>
                        <td className={`${td} font-mono text-xs`}>{f.bookingReference ?? f.bookingId?.slice(0, 8) ?? '—'}</td>
                        <td className={td}>{f.roomNumber ?? '—'}<span className="block text-xs text-ink-500">{f.checkIn ? `${formatDate(f.checkIn, 'd MMM')} – ${formatDate(f.checkOut, 'd MMM yy')}` : ''}</span></td>
                        <td className={`${td} text-xs`}>{f.allocationStatus ?? 'none'} / {f.bookingStatus ?? 'unknown'}</td>
                        <td className={`${td} text-xs`}>{f.action === 'RELEASE_EXPIRED_ORPHAN_HOLD' ? 'Hold released automatically' : 'Review manually'}</td>
                      </tr>
                    ))}
                  </tbody>
                </Table>
              </div>
            )}
          </>
        ) : null}
      </div>

      {detailed && runs.data && runs.data.length > 0 && (
        <div className="mt-6">
          <h3 className="font-sans text-sm font-semibold text-ink-800">Recent runs</h3>
          <ul className="mt-2 divide-y divide-ink-100 text-sm">
            {runs.data.map((r) => (
              <li key={r.id} className="flex flex-wrap justify-between gap-2 py-2">
                <span>{formatDateTime(r.finishedAt)} · {r.trigger === 'SCHEDULED' ? 'scheduled' : `manual (${r.actor})`}</span>
                <span className="tabular-nums text-ink-600">{r.status === 'FAILED' ? 'failed' : `${r.findingCount} findings · ${r.releasedCount} released`}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

export default function AdminReconciliationPage() {
  const { propertyId, properties } = useAdminProperty();
  const hotels = propertyId ? properties.filter((p) => p.id === propertyId) : properties;
  return (
    <>
      <AdminHeader
        title="Stock reconciliation"
        description="Compares each hotel's room allocations with the booking service's confirmed and pending bookings. Expired orphan holds are released automatically; everything else is listed for review. Every run, finding and release is written to the inventory audit trail."
      />
      {properties.length === 0 ? (
        <LoadingBlock />
      ) : (
        <div className="space-y-6">
          {!propertyId && <p className="text-sm text-ink-600">Choose a hotel in the menu to see individual findings and run history.</p>}
          {hotels.map((p) => <HotelReconciliation key={p.id} property={p} detailed={!!propertyId} />)}
        </div>
      )}
    </>
  );
}
