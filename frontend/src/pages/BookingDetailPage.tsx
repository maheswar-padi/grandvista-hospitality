import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft } from 'lucide-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { BookingStatusBadge, PaymentStatusBadge } from '../components/booking/BookingStatusBadge';
import { PriceBreakdown } from '../components/rooms/PriceBreakdown';
import { Alert } from '../components/ui/Alert';
import { Button, ButtonLink } from '../components/ui/Button';
import { Dialog } from '../components/ui/Dialog';
import { TextAreaField } from '../components/ui/Field';
import { ErrorState, LoadingBlock } from '../components/ui/States';
import { bookingsApi, paymentsApi } from '../lib/api/endpoints';
import { ApiError } from '../lib/api/http';
import { useAuth } from '../lib/auth/AuthContext';
import { bookingStatusLabel, formatDate, formatDateTime, formatMoney, humanize } from '../lib/format';

export function CancelBookingDialog({ bookingId, open, onClose }: { bookingId: string; open: boolean; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const quote = useQuery({ queryKey: ['cancellation-quote', bookingId], queryFn: () => bookingsApi.cancellationQuote(bookingId), enabled: open, staleTime: 0 });
  const cancel = useMutation({
    mutationFn: () => bookingsApi.cancel(bookingId, reason || undefined),
    onSuccess: (booking) => {
      queryClient.setQueryData(['booking', bookingId], booking);
      void queryClient.invalidateQueries({ queryKey: ['my-bookings'] });
      void queryClient.invalidateQueries({ queryKey: ['admin-bookings'] });
      onClose();
    },
  });
  return (
    <Dialog open={open} onClose={onClose} title="Cancel this booking?">
      {quote.isPending && <LoadingBlock />}
      {quote.data && (
        <div className="space-y-4 text-sm">
          {quote.data.rule === 'NOT_YET_PAID' ? (
            <p>This booking has not been paid. Cancelling releases the room; nothing will be charged.</p>
          ) : quote.data.allowed ? (
            <Alert tone={quote.data.refundPercent === 100 ? 'success' : 'warning'} title={`Refund: ${formatMoney(quote.data.refundAmount, quote.data.currency ?? 'INR')} (${quote.data.refundPercent}%)`}>
              {quote.data.rule === 'HOTEL_INITIATED_FULL_REFUND'
                ? 'Cancellations made by the hotel are refunded in full.'
                : `Check-in is ${quote.data.daysBeforeCheckIn} day(s) away. Policy: full refund 7+ days before arrival, 50% from 2–6 days, no refund within 48 hours.`}
            </Alert>
          ) : (
            <Alert tone="error" title="This booking can no longer be cancelled online">Please contact the hotel.</Alert>
          )}
          {quote.data.allowed && (
            <TextAreaField label="Reason (optional)" value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />
          )}
          {cancel.error instanceof ApiError && <Alert tone="error">{cancel.error.message}</Alert>}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={onClose}>Keep booking</Button>
            {quote.data.allowed && <Button variant="danger" loading={cancel.isPending} onClick={() => cancel.mutate()}>Cancel booking</Button>}
          </div>
        </div>
      )}
    </Dialog>
  );
}

export default function BookingDetailPage() {
  const { bookingId = '' } = useParams();
  const { hasRole } = useAuth();
  const [cancelOpen, setCancelOpen] = useState(false);
  const booking = useQuery({
    queryKey: ['booking', bookingId],
    queryFn: () => bookingsApi.get(bookingId),
    refetchInterval: (q) => (q.state.data?.status === 'CANCELLATION_PENDING' ? 2000 : false),
  });
  const history = useQuery({ queryKey: ['booking-history', bookingId, booking.data?.status], queryFn: () => bookingsApi.history(bookingId), enabled: booking.isSuccess });
  const payment = useQuery({ queryKey: ['payment-by-booking', bookingId, booking.data?.status], queryFn: () => paymentsApi.byBooking(bookingId), enabled: !!booking.data?.paymentId });

  if (booking.isPending) return <div className="container-page py-16"><LoadingBlock /></div>;
  if (booking.isError) return <div className="container-page py-16"><ErrorState error={booking.error} title={booking.error instanceof ApiError && booking.error.status === 404 ? 'Booking not found' : undefined} onRetry={() => booking.refetch()} /></div>;
  const b = booking.data;

  return (
    <div className="container-page py-10">
      <Link to={hasRole('ADMIN', 'STAFF') ? '/admin/bookings' : '/account'} className="-my-2 inline-flex items-center gap-1.5 py-2.5 text-sm text-ink-600 hover:text-ink-900">
        <ArrowLeft className="size-4" aria-hidden /> Back
      </Link>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <h1 className="text-4xl">Booking {b.reference}</h1>
        <BookingStatusBadge status={b.status} />
      </div>
      {b.statusReason && b.status !== 'CONFIRMED' && <p className="mt-1 text-sm text-ink-500">{humanize(b.statusReason.split(':')[0])}</p>}

      <div className="mt-8 grid gap-8 lg:grid-cols-[1fr_380px]">
        <div className="space-y-8">
          <section className="card p-6" aria-labelledby="stay-heading">
            <h2 id="stay-heading" className="text-2xl">Your stay</h2>
            <dl className="mt-4 grid gap-5 sm:grid-cols-2">
              <div><dt className="eyebrow">Room</dt><dd className="mt-1">{b.room.name}<span className="block text-sm text-ink-500">{b.room.typeName} · Room {b.room.roomNumber}</span></dd></div>
              <div><dt className="eyebrow">Dates</dt><dd className="mt-1">{formatDate(b.checkIn)} → {formatDate(b.checkOut)}<span className="block text-sm text-ink-500">{b.nights} night{b.nights > 1 ? 's' : ''}</span></dd></div>
              <div><dt className="eyebrow">Guests</dt><dd className="mt-1">{b.guests}</dd></div>
              <div><dt className="eyebrow">Lead guest</dt><dd className="mt-1">{b.primaryGuest?.fullName}<span className="block text-sm text-ink-500">{b.primaryGuest?.email}</span></dd></div>
              {b.specialRequests && <div className="sm:col-span-2"><dt className="eyebrow">Special requests</dt><dd className="mt-1 text-sm">{b.specialRequests}</dd></div>}
            </dl>
          </section>

          {history.data && (
            <section className="card p-6" aria-labelledby="history-heading">
              <h2 id="history-heading" className="text-2xl">Timeline</h2>
              <ol className="mt-4 space-y-4 border-l-2 border-gold-200 pl-5">
                {history.data.map((h, i) => (
                  <li key={i} className="relative">
                    <span className="absolute -left-[1.6rem] top-1 size-3 rounded-full bg-gold-400" aria-hidden />
                    <p className="text-sm font-semibold">{bookingStatusLabel[h.toStatus]}</p>
                    <p className="text-xs text-ink-500">{formatDateTime(h.occurredAt)}{h.reason ? ` · ${humanize(h.reason.split(':')[0])}` : ''}</p>
                  </li>
                ))}
              </ol>
            </section>
          )}
        </div>

        <aside className="space-y-6">
          <div className="card p-6">
            <div className="flex items-center justify-between">
              <h2 className="text-2xl">Payment</h2>
              {payment.data && <PaymentStatusBadge status={payment.data.status} />}
            </div>
            {b.price && <div className="mt-4"><PriceBreakdown currency={b.price.currency} nightlyRates={b.price.nightlyRates} subtotal={b.price.subtotal} serviceFee={b.price.serviceFee} taxes={b.price.taxes} total={b.price.total} pricingPolicyVersion={b.price.pricingPolicyVersion} /></div>}
            {b.refundAmount !== null && b.status !== 'CONFIRMED' && <p className="mt-3 text-sm text-ink-700">Refund: <strong>{formatMoney(b.refundAmount, b.price?.currency)}</strong></p>}
            {payment.data?.simulated && <p className="mt-3 text-xs text-ink-500">Processed by the simulated provider; no real money moved.</p>}
          </div>
          <div className="flex flex-col gap-2">
            {b.allowedActions.includes('PAY') && hasRole('CUSTOMER') && <ButtonLink to={`/bookings/${b.id}/pay`}>Complete payment</ButtonLink>}
            {b.allowedActions.includes('CANCEL') && <Button variant="secondary" onClick={() => setCancelOpen(true)}>Cancel booking</Button>}
          </div>
        </aside>
      </div>
      <CancelBookingDialog bookingId={b.id} open={cancelOpen} onClose={() => setCancelOpen(false)} />
    </div>
  );
}
