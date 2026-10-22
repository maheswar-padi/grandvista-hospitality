import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, Clock, Loader2, XCircle } from 'lucide-react';
import { useState } from 'react';
import { useParams } from 'react-router';
import { BookingStatusBadge, PaymentStatusBadge } from '../components/booking/BookingStatusBadge';
import { PriceBreakdown } from '../components/rooms/PriceBreakdown';
import { Alert } from '../components/ui/Alert';
import { ButtonLink } from '../components/ui/Button';
import { ErrorState, LoadingBlock } from '../components/ui/States';
import type { BookingStatus } from '../lib/api/types';
import { bookingsApi, paymentsApi } from '../lib/api/endpoints';
import { formatDate, formatMoney, humanize } from '../lib/format';

const IN_PROGRESS: BookingStatus[] = ['CREATED', 'PENDING_PAYMENT', 'CANCELLATION_PENDING'];
const POLL_LIMIT_MS = 120_000;

/**
 * Shows the AUTHORITATIVE booking state from the booking-service. A successful payment request alone is
 * never treated as a confirmation: the page polls until the saga has confirmed (or failed) the booking.
 */
export default function ConfirmationPage() {
  const { bookingId = '' } = useParams();
  const [startedAt] = useState(() => Date.now());
  const booking = useQuery({
    queryKey: ['booking', bookingId],
    queryFn: () => bookingsApi.get(bookingId),
    staleTime: 0,
    refetchInterval: (q) =>
      q.state.data && IN_PROGRESS.includes(q.state.data.status) && Date.now() - startedAt < POLL_LIMIT_MS ? 2000 : false,
  });
  const payment = useQuery({
    queryKey: ['payment-by-booking', bookingId, booking.data?.status],
    queryFn: () => paymentsApi.byBooking(bookingId),
    enabled: !!booking.data?.paymentId,
  });

  if (booking.isPending) return <div className="container-page py-16"><LoadingBlock label="Checking your booking…" /></div>;
  if (booking.isError) return <div className="container-page py-16"><ErrorState error={booking.error} onRetry={() => booking.refetch()} /></div>;
  const b = booking.data;

  const header = (() => {
    switch (b.status) {
      case 'CONFIRMED':
        return { icon: <CheckCircle2 className="size-10 text-emerald-700" aria-hidden />, title: 'Your stay is confirmed', text: 'We look forward to welcoming you. A summary is below.' };
      case 'PENDING_PAYMENT':
        return payment.data?.status === 'PENDING'
          ? { icon: <Loader2 className="size-10 animate-spin text-gold-600" aria-hidden />, title: 'Waiting for payment confirmation', text: 'The payment provider is still processing your payment. This page updates automatically.' }
          : payment.data?.status === 'SUCCEEDED'
            ? { icon: <Loader2 className="size-10 animate-spin text-gold-600" aria-hidden />, title: 'Payment received — confirming your room', text: 'We are securing your room. This usually takes a few seconds.' }
            : { icon: <Clock className="size-10 text-gold-600" aria-hidden />, title: 'Awaiting payment', text: 'Your room is held, but we have not received a payment yet.' };
      case 'PAYMENT_FAILED':
        return { icon: <XCircle className="size-10 text-red-700" aria-hidden />, title: 'Payment was not successful', text: 'Your card was not charged and the room has been released. You can try booking again.' };
      case 'CANCELLATION_PENDING':
        return { icon: <Loader2 className="size-10 animate-spin text-gold-600" aria-hidden />, title: 'Cancelling your booking', text: 'We are releasing the room and processing any refund.' };
      case 'CANCELLED':
        return { icon: <XCircle className="size-10 text-ink-500" aria-hidden />, title: 'Booking cancelled', text: b.refundAmount ? `A refund of ${formatMoney(b.refundAmount, b.price?.currency)} has been issued.` : 'No refund was due under the cancellation policy.' };
      case 'EXPIRED':
        return { icon: <Clock className="size-10 text-ink-500" aria-hidden />, title: 'This booking expired', text: 'Payment was not completed in time, so the room was released. Nothing was charged.' };
      default:
        return { icon: <Clock className="size-10 text-ink-500" aria-hidden />, title: humanize(b.status), text: 'Contact the hotel if you need help with this booking.' };
    }
  })();

  return (
    <div className="container-page py-12">
      <div className="card mx-auto max-w-3xl p-8 sm:p-10">
        <div className="flex flex-col items-center gap-3 text-center" aria-live="polite">
          {header.icon}
          <h1 className="text-4xl">{header.title}</h1>
          <p className="max-w-lg text-ink-600">{header.text}</p>
          <div className="flex flex-wrap items-center justify-center gap-2">
            <BookingStatusBadge status={b.status} />
            {payment.data && <PaymentStatusBadge status={payment.data.status} />}
            {payment.data?.simulated && <span className="text-xs text-ink-500">simulated payment</span>}
          </div>
        </div>

        <dl className="mt-10 grid gap-6 border-t border-ink-100 pt-8 sm:grid-cols-2">
          <div><dt className="eyebrow">Booking reference</dt><dd className="mt-1 font-mono text-2xl font-semibold tracking-wider">{b.reference}</dd></div>
          <div><dt className="eyebrow">Room</dt><dd className="mt-1">{b.room.name}<span className="block text-sm text-ink-500">{b.room.typeName} · Room {b.room.roomNumber}</span></dd></div>
          <div><dt className="eyebrow">Check-in</dt><dd className="mt-1">{formatDate(b.checkIn)}<span className="block text-sm text-ink-500">from 14:00</span></dd></div>
          <div><dt className="eyebrow">Check-out</dt><dd className="mt-1">{formatDate(b.checkOut)}<span className="block text-sm text-ink-500">until 11:00</span></dd></div>
          <div><dt className="eyebrow">Guests</dt><dd className="mt-1">{b.guests} · lead guest {b.primaryGuest?.fullName}</dd></div>
          {payment.data?.failureMessage && <div><dt className="eyebrow">Payment</dt><dd className="mt-1 text-sm text-red-800">{payment.data.failureMessage}</dd></div>}
        </dl>

        {b.price && (
          <div className="mt-8 border-t border-ink-100 pt-8">
            <PriceBreakdown compact currency={b.price.currency} nightlyRates={b.price.nightlyRates} subtotal={b.price.subtotal} serviceFee={b.price.serviceFee} taxes={b.price.taxes} total={b.price.total} />
          </div>
        )}

        {b.status === 'CONFIRMED' && (
          <Alert tone="info" title="Next steps" className="mt-8">
            Bring a photo ID to check-in. You can review or cancel this booking from <strong>My bookings</strong>; free cancellation applies up to 7 days before arrival.
          </Alert>
        )}

        <div className="mt-8 flex flex-wrap justify-center gap-3">
          {b.status === 'PENDING_PAYMENT' && payment.data?.status === 'CREATED' && <ButtonLink to={`/bookings/${b.id}/pay`}>Continue to payment</ButtonLink>}
          {['PAYMENT_FAILED', 'EXPIRED'].includes(b.status) && <ButtonLink to={`/rooms/${b.room.id}?checkIn=${b.checkIn}&checkOut=${b.checkOut}&guests=${b.guests}`}>Try again</ButtonLink>}
          <ButtonLink to={`/account/bookings/${b.id}`} variant="secondary">View booking</ButtonLink>
          <ButtonLink to="/account" variant="ghost">All my bookings</ButtonLink>
        </div>
      </div>
    </div>
  );
}
