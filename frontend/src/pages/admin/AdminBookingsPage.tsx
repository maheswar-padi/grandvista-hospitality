import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { AdminHeader, Table, td, th } from '../../components/admin/AdminUi';
import { BookingStatusBadge } from '../../components/booking/BookingStatusBadge';
import { Alert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { SelectField, TextField } from '../../components/ui/Field';
import { Pagination } from '../../components/ui/Pagination';
import { EmptyState, ErrorState, LoadingBlock } from '../../components/ui/States';
import { adminApi } from '../../lib/api/endpoints';
import { ApiError } from '../../lib/api/http';
import type { Booking, BookingStatus } from '../../lib/api/types';
import { useAdminProperty } from '../../lib/adminProperty';
import { useAuth } from '../../lib/auth/AuthContext';
import { bookingStatusLabel, formatDate, formatDateTime, formatMoney, humanize } from '../../lib/format';
import { CancelBookingDialog } from '../BookingDetailPage';

const STATUSES = Object.keys(bookingStatusLabel) as BookingStatus[];

export default function AdminBookingsPage() {
  const { hasRole } = useAuth();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<BookingStatus | ''>('');
  const [q, setQ] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [cancelling, setCancelling] = useState<Booking | null>(null);
  const { propertyId } = useAdminProperty();
  const bookings = useQuery({
    queryKey: ['admin-bookings', propertyId, status, q, from, to, page],
    queryFn: () => adminApi.bookings({ propertyId, status: status || undefined, q: q || undefined, from: from || undefined, to: to || undefined, page, size: 25 }),
    placeholderData: keepPreviousData,
  });
  const retry = useMutation({
    mutationFn: (id: string) => adminApi.retryCompensation(id),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: ['admin-bookings'] }),
  });

  return (
    <>
      <AdminHeader title="Bookings" description="Search reservations by reference, guest e-mail or room. Cancellations made here are hotel-initiated and refunded in full." />
      <div className="mb-4 flex flex-wrap gap-3">
        <SelectField label="Status" wrapperClassName="w-52" value={status} onChange={(e) => { setStatus(e.target.value as BookingStatus | ''); setPage(0); }}>
          <option value="">All statuses</option>
          {STATUSES.map((s) => <option key={s} value={s}>{bookingStatusLabel[s]}</option>)}
        </SelectField>
        <TextField label="Search" placeholder="GV-…, e-mail or room" wrapperClassName="w-60" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
        <TextField label="Stay from" type="date" wrapperClassName="w-44" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} />
        <TextField label="Stay until" type="date" wrapperClassName="w-44" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} />
      </div>
      {retry.error instanceof ApiError && <Alert tone="error" className="mb-4">{retry.error.message}</Alert>}
      {bookings.isPending ? <LoadingBlock /> : bookings.isError ? <ErrorState error={bookings.error} onRetry={() => bookings.refetch()} /> : bookings.data.content.length === 0 ? (
        <EmptyState title="No bookings match">Try a different filter.</EmptyState>
      ) : (
        <>
          <Table caption="Bookings">
            <thead><tr><th className={th}>Reference</th><th className={th}>Guest</th><th className={th}>Room</th><th className={th}>Stay</th><th className={th}>Total</th><th className={th}>Status</th><th className={th}><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {bookings.data.content.map((b) => (
                <tr key={b.id}>
                  <td className={td}><Link to={`/account/bookings/${b.id}`} className="font-mono font-semibold text-gold-700 hover:underline">{b.reference}</Link><span className="block text-xs text-ink-500">{formatDateTime(b.createdAt)}</span></td>
                  <td className={td}>{b.primaryGuest?.fullName}<span className="block text-xs text-ink-500">{b.customerEmail}</span></td>
                  <td className={td}>{b.room.roomNumber}<span className="block text-xs text-ink-500">{b.room.typeName}{b.room.propertyName ? ` · ${b.room.propertyName}` : ''}</span></td>
                  <td className={td}>{formatDate(b.checkIn, 'd MMM')} – {formatDate(b.checkOut, 'd MMM yy')}<span className="block text-xs text-ink-500">{b.guests} guest(s)</span></td>
                  <td className={`${td} tabular-nums`}>{formatMoney(b.price?.total, b.price?.currency, 0)}{b.refundAmount !== null && <span className="block text-xs text-ink-500">refund {formatMoney(b.refundAmount, b.price?.currency, 0)}</span>}</td>
                  <td className={td}><BookingStatusBadge status={b.status} />{b.statusReason && b.status !== 'CONFIRMED' && <span className="mt-1 block max-w-40 text-xs text-ink-500">{humanize(b.statusReason.split(':')[0])}</span>}</td>
                  <td className={`${td} whitespace-nowrap text-right`}>
                    {b.allowedActions.includes('CANCEL') && <Button size="sm" variant="secondary" onClick={() => setCancelling(b)}>Cancel</Button>}
                    {b.status === 'NEEDS_ATTENTION' && hasRole('ADMIN') && <Button size="sm" loading={retry.isPending && retry.variables === b.id} onClick={() => retry.mutate(b.id)}>Retry compensation</Button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
          <div className="mt-4"><Pagination page={bookings.data.page} totalPages={bookings.data.totalPages} onChange={setPage} /></div>
        </>
      )}
      {cancelling && <CancelBookingDialog bookingId={cancelling.id} open onClose={() => setCancelling(null)} />}
    </>
  );
}
