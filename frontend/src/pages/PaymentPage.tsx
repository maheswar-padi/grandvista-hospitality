import { useMutation, useQuery } from '@tanstack/react-query';
import { CreditCard, FlaskConical, Timer } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router';
import { PriceBreakdown } from '../components/rooms/PriceBreakdown';
import { Alert } from '../components/ui/Alert';
import { Button } from '../components/ui/Button';
import { ErrorState, LoadingBlock } from '../components/ui/States';
import { bookingsApi, paymentsApi } from '../lib/api/endpoints';
import { ApiError, newIdempotencyKey } from '../lib/api/http';
import { formatDate, formatMoney } from '../lib/format';

function useCountdown(until: string | null) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);
  if (!until) return null;
  return Math.max(0, Math.floor((new Date(until).getTime() - now) / 1000));
}

export default function PaymentPage() {
  const { bookingId = '' } = useParams();
  const navigate = useNavigate();
  const booking = useQuery({ queryKey: ['booking', bookingId], queryFn: () => bookingsApi.get(bookingId), staleTime: 0 });
  const methods = useQuery({ queryKey: ['payment-test-methods'], queryFn: paymentsApi.testMethods, staleTime: Infinity });
  const [method, setMethod] = useState('sim_card_success');
  // one key per payment attempt on this page: repeated clicks/retries cannot charge twice
  const attemptKey = useRef(newIdempotencyKey());
  const secondsLeft = useCountdown(booking.data?.holdExpiresAt ?? null);

  const pay = useMutation({
    mutationFn: () => paymentsApi.confirm(booking.data!.paymentId!, method, attemptKey.current),
    // The confirmation page shows the authoritative outcome (success, decline or still processing).
    onSuccess: () => navigate(`/bookings/${bookingId}/confirmation`, { replace: true }),
    onError: (error) => {
      if (error instanceof ApiError && ['PAYMENT_ALREADY_PROCESSED', 'PAYMENT_IN_PROGRESS'].includes(error.code)) {
        navigate(`/bookings/${bookingId}/confirmation`, { replace: true });
      }
    },
  });

  if (booking.isPending) return <div className="container-page py-16"><LoadingBlock label="Loading your booking…" /></div>;
  if (booking.isError) return <div className="container-page py-16"><ErrorState error={booking.error} onRetry={() => booking.refetch()} /></div>;
  const b = booking.data;
  if (b.status !== 'PENDING_PAYMENT' || !b.paymentId) return <Navigate to={`/bookings/${b.id}/confirmation`} replace />;

  const expired = secondsLeft !== null && secondsLeft <= 0;
  const minutes = secondsLeft !== null ? Math.floor(secondsLeft / 60) : 0;
  const seconds = secondsLeft !== null ? secondsLeft % 60 : 0;

  return (
    <div className="container-page py-10">
      <p className="eyebrow">Step 2 of 2 · Payment</p>
      <h1 className="mt-2 text-4xl sm:text-5xl">Complete your payment</h1>
      <p className="mt-2 text-ink-600">Booking reference <strong className="font-semibold text-ink-900">{b.reference}</strong></p>

      <div className="mt-8 grid gap-10 lg:grid-cols-[1fr_400px]">
        <div className="card space-y-6 p-6 sm:p-8">
          <Alert tone="warning" title="Simulated payment — no real money is charged">
            This demonstration uses a <strong>simulated payment provider</strong>. Choose a test method below to see how the system
            handles approvals, declines, delayed bank confirmations and timeouts. Never enter real card details anywhere on this site.
          </Alert>
          <div className={`flex items-center gap-3 rounded-xl p-4 text-sm ${expired ? 'bg-red-50 text-red-900' : 'bg-gold-50 text-ink-800'}`} role="timer" aria-live="off">
            <Timer className="size-5 text-gold-600" aria-hidden />
            {expired ? (
              <span>Your hold has expired. The booking will be released shortly — please start a new booking.</span>
            ) : (
              <span>
                Your room is held for <strong className="tabular-nums">{minutes}:{seconds.toString().padStart(2, '0')}</strong> more minutes.
              </span>
            )}
          </div>

          <fieldset>
            <legend className="mb-3 flex items-center gap-2 text-lg font-semibold"><FlaskConical className="size-5 text-gold-600" aria-hidden /> Test payment method</legend>
            {methods.isPending && <LoadingBlock />}
            {methods.isError && <Alert tone="error">Payment methods could not be loaded.</Alert>}
            <div className="space-y-2">
              {methods.data?.map((m) => (
                <label key={m.token} className={`flex cursor-pointer items-start gap-3 rounded-xl border p-4 text-sm transition ${method === m.token ? 'border-ink-900 bg-ink-50' : 'border-ink-200 hover:border-ink-400'}`}>
                  <input type="radio" name="method" value={m.token} checked={method === m.token} onChange={() => setMethod(m.token)} className="mt-0.5 size-4 accent-ink-900" />
                  <span>
                    <span className="block font-medium text-ink-900">{m.label}</span>
                    <span className="block text-xs text-ink-500">Expected outcome: {m.expectedOutcome}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>

          {pay.error instanceof ApiError && pay.error.status >= 400 && pay.error.status < 500 && (
            <Alert tone="error">{pay.error.message}</Alert>
          )}
          {pay.error instanceof ApiError && (pay.error.status === 0 || pay.error.status >= 500) && (
            <Alert tone="error" title="We couldn’t reach the payment service">
              Your payment may or may not have been processed. Pressing pay again is safe — you will never be charged twice.
            </Alert>
          )}
          <Button size="lg" className="w-full" icon={<CreditCard className="size-4" aria-hidden />} loading={pay.isPending} disabled={expired || !methods.data} onClick={() => pay.mutate()}>
            Pay {formatMoney(b.price?.total, b.price?.currency)} (simulated)
          </Button>
        </div>

        <aside className="card h-fit p-6" aria-label="Booking summary">
          <h2 className="text-2xl">{b.room.name}</h2>
          <p className="text-sm text-ink-500">{formatDate(b.checkIn)} → {formatDate(b.checkOut)} · {b.guests} guest{b.guests > 1 ? 's' : ''}</p>
          {b.price && (
            <div className="mt-4">
              <PriceBreakdown currency={b.price.currency} nightlyRates={b.price.nightlyRates} subtotal={b.price.subtotal} serviceFee={b.price.serviceFee}
                taxes={b.price.taxes} total={b.price.total} pricingPolicyVersion={b.price.pricingPolicyVersion} />
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}
