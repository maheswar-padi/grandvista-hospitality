import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { AdminHeader, Table, td, th } from '../../components/admin/AdminUi';
import { PaymentStatusBadge } from '../../components/booking/BookingStatusBadge';
import { Alert } from '../../components/ui/Alert';
import { SelectField } from '../../components/ui/Field';
import { Pagination } from '../../components/ui/Pagination';
import { EmptyState, ErrorState, LoadingBlock } from '../../components/ui/States';
import { adminApi } from '../../lib/api/endpoints';
import type { PaymentStatus } from '../../lib/api/types';
import { formatDateTime, formatMoney, paymentStatusLabel } from '../../lib/format';

const STATUSES = Object.keys(paymentStatusLabel) as PaymentStatus[];

export default function AdminPaymentsPage() {
  const [status, setStatus] = useState<PaymentStatus | ''>('');
  const [page, setPage] = useState(0);
  const payments = useQuery({
    queryKey: ['admin', 'payments', status, page],
    queryFn: () => adminApi.payments({ status: status || undefined, page, size: 25 }),
    placeholderData: keepPreviousData,
  });
  return (
    <>
      <AdminHeader title="Payments" description="Read-only view of payment records. Outcomes change only through the payment provider — never by hand." />
      <Alert tone="info" className="mb-6">All payments are processed by the <strong>simulated provider</strong>; no real money is involved.</Alert>
      <SelectField label="Status" wrapperClassName="mb-4 w-52" value={status} onChange={(e) => { setStatus(e.target.value as PaymentStatus | ''); setPage(0); }}>
        <option value="">All statuses</option>
        {STATUSES.map((s) => <option key={s} value={s}>{paymentStatusLabel[s]}</option>)}
      </SelectField>
      {payments.isPending ? <LoadingBlock /> : payments.isError ? <ErrorState error={payments.error} onRetry={() => payments.refetch()} /> : payments.data.content.length === 0 ? (
        <EmptyState title="No payments yet" />
      ) : (
        <>
          <Table caption="Payments">
            <thead><tr><th className={th}>Booking</th><th className={th}>Amount</th><th className={th}>Refunded</th><th className={th}>Status</th><th className={th}>Provider reference</th><th className={th}>Updated</th></tr></thead>
            <tbody>
              {payments.data.content.map((p) => (
                <tr key={p.id}>
                  <td className={`${td} font-mono`}>{p.bookingReference}</td>
                  <td className={`${td} tabular-nums`}>{formatMoney(p.amount, p.currency)}</td>
                  <td className={`${td} tabular-nums`}>{p.refundedAmount > 0 ? formatMoney(p.refundedAmount, p.currency) : '—'}</td>
                  <td className={td}><PaymentStatusBadge status={p.status} />{p.failureCode && <span className="mt-1 block text-xs text-ink-500">{p.failureCode}</span>}</td>
                  <td className={`${td} max-w-48 truncate font-mono text-xs text-ink-500`}>{p.providerReference ?? '—'}</td>
                  <td className={`${td} text-xs`}>{formatDateTime(p.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </Table>
          <div className="mt-4"><Pagination page={payments.data.page} totalPages={payments.data.totalPages} onChange={setPage} /></div>
        </>
      )}
    </>
  );
}
