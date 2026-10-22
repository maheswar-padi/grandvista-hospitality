import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarDays, Lock, Users } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { PriceBreakdown } from '../components/rooms/PriceBreakdown';
import { RoomVisual } from '../components/rooms/RoomVisual';
import { Alert } from '../components/ui/Alert';
import { Button, ButtonLink } from '../components/ui/Button';
import { TextAreaField, TextField } from '../components/ui/Field';
import { ErrorState, LoadingBlock } from '../components/ui/States';
import { bookingsApi, roomsApi } from '../lib/api/endpoints';
import { ApiError, newIdempotencyKey } from '../lib/api/http';
import { useAuth } from '../lib/auth/AuthContext';
import { formatDate, formatMoney } from '../lib/format';
import { guestSchema, type GuestForm } from '../lib/schemas';

/**
 * One Idempotency-Key per checkout attempt, kept in sessionStorage so that a double click, a network
 * retry or a page reload re-sends the same key and the server returns the same booking (never a duplicate).
 */
function useCheckoutKey(scope: string) {
  const storageKey = `gv.checkout.${scope}`;
  const [key, setKey] = useState(() => {
    try {
      return sessionStorage.getItem(storageKey) ?? newIdempotencyKey();
    } catch {
      return newIdempotencyKey();
    }
  });
  useEffect(() => {
    try {
      sessionStorage.setItem(storageKey, key);
    } catch {
      // storage unavailable (private mode): the in-memory key still protects against double submission
    }
  }, [storageKey, key]);
  const reset = () => {
    const fresh = newIdempotencyKey();
    setKey(fresh);
  };
  const clear = () => {
    try {
      sessionStorage.removeItem(storageKey);
    } catch {
      /* ignore */
    }
  };
  return { key, reset, clear };
}

export default function CheckoutPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const roomId = params.get('roomId') ?? '';
  const checkIn = params.get('checkIn') ?? '';
  const checkOut = params.get('checkOut') ?? '';
  const guests = Number(params.get('guests') ?? 1);
  const idempotency = useCheckoutKey(`${roomId}.${checkIn}.${checkOut}.${guests}`);

  const room = useQuery({ queryKey: ['room', roomId], queryFn: () => roomsApi.get(roomId), enabled: !!roomId });
  const quote = useQuery({
    queryKey: ['quote', roomId, checkIn, checkOut, guests],
    queryFn: () => roomsApi.quote(roomId, checkIn, checkOut, guests),
    enabled: !!roomId && !!checkIn && !!checkOut,
    staleTime: 0,
  });

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<GuestForm>({
    resolver: zodResolver(guestSchema),
    defaultValues: { fullName: user?.fullName ?? '', email: user?.email ?? '', phone: user?.phone ?? '' },
  });

  const create = useMutation({
    mutationFn: (values: GuestForm) =>
      bookingsApi.create(
        {
          roomId,
          checkIn,
          checkOut,
          guests,
          primaryGuest: { fullName: values.fullName, email: values.email, phone: values.phone || undefined },
          specialRequests: values.specialRequests || undefined,
          expectedTotal: quote.data?.quote.total,
        },
        idempotency.key,
      ),
    onSuccess: (booking) => {
      idempotency.clear();
      queryClient.setQueryData(['booking', booking.id], booking);
      navigate(`/bookings/${booking.id}/pay`, { replace: true });
    },
    onError: (error) => {
      // A definitive rejection consumed this key; a fresh attempt needs a new one.
      if (error instanceof ApiError && error.status !== 503 && error.status !== 0) {
        idempotency.clear();
        idempotency.reset();
      }
      if (error instanceof ApiError && error.code === 'PRICE_CHANGED') {
        void queryClient.invalidateQueries({ queryKey: ['quote', roomId, checkIn, checkOut, guests] });
      }
    },
  });

  if (!roomId || !checkIn || !checkOut) {
    return (
      <div className="container-page py-16">
        <ErrorState error={null} title="Your booking details are incomplete" />
        <div className="mt-6 text-center"><ButtonLink to="/rooms">Choose a room</ButtonLink></div>
      </div>
    );
  }
  if (room.isPending || quote.isPending) return <div className="container-page py-16"><LoadingBlock label="Preparing your booking…" /></div>;
  if (room.isError) return <div className="container-page py-16"><ErrorState error={room.error} onRetry={() => room.refetch()} /></div>;
  if (quote.isError) return <div className="container-page py-16"><ErrorState error={quote.error} title="We could not price this stay" onRetry={() => quote.refetch()} /></div>;

  const q = quote.data.quote;
  const err = create.error instanceof ApiError ? create.error : null;

  return (
    <div className="container-page py-10">
      <p className="eyebrow">Step 1 of 2 · Guest details</p>
      <h1 className="mt-2 text-4xl sm:text-5xl">Review and reserve</h1>

      <div className="mt-8 grid gap-10 lg:grid-cols-[1fr_420px]">
        <form onSubmit={handleSubmit((v) => create.mutate(v))} className="card space-y-5 p-6 sm:p-8" noValidate aria-describedby="checkout-note">
          {!quote.data.available && (
            <Alert tone="warning" title="This room is no longer available for these dates">
              <Link to={`/rooms?checkIn=${checkIn}&checkOut=${checkOut}&guests=${guests}`} className="underline">Find another room</Link>
            </Alert>
          )}
          {err?.code === 'PRICE_CHANGED' && (
            <Alert tone="warning" title="The price has changed">
              The price for these dates is now {formatMoney(Number(err.properties.currentTotal), String(err.properties.currency ?? q.currency))}.
              The updated breakdown is shown alongside. Review it and reserve again — you have not been charged.
            </Alert>
          )}
          {err && err.code === 'ROOM_UNAVAILABLE' && (
            <Alert tone="error" title="Sorry, this room was just booked">
              {err.message} <Link to={`/rooms?checkIn=${checkIn}&checkOut=${checkOut}&guests=${guests}`} className="underline">See other rooms</Link>
            </Alert>
          )}
          {err && err.status >= 500 && <Alert tone="error" title="We couldn’t complete your booking">{err.message} Press “Reserve” again to retry safely.</Alert>}
          {err && !['PRICE_CHANGED', 'ROOM_UNAVAILABLE'].includes(err.code) && err.status < 500 && <Alert tone="error">{err.message}</Alert>}

          <h2 className="text-2xl">Lead guest</h2>
          <div className="grid gap-5 sm:grid-cols-2">
            <TextField label="Full name" autoComplete="name" error={errors.fullName?.message} {...register('fullName')} />
            <TextField label="E-mail address" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
            <TextField label="Phone (optional)" type="tel" autoComplete="tel" error={errors.phone?.message} {...register('phone')} />
          </div>
          <TextAreaField label="Special requests (optional)" hint="Requests are passed to the hotel and are not guaranteed." error={errors.specialRequests?.message} {...register('specialRequests')} />
          <div className="flex flex-col gap-3 border-t border-ink-100 pt-5 sm:flex-row sm:items-center sm:justify-between">
            <p id="checkout-note" className="flex items-center gap-2 text-sm text-ink-600">
              <Lock className="size-4 text-gold-600" aria-hidden /> Reserving holds the room for 15 minutes. Payment is the next step.
            </p>
            <Button type="submit" size="lg" loading={create.isPending} disabled={!quote.data.available}>
              Reserve · {formatMoney(q.total, q.currency)}
            </Button>
          </div>
        </form>

        <aside className="card h-fit overflow-hidden" aria-label="Booking summary">
          <RoomVisual room={room.data} className="aspect-[16/9] w-full" />
          <div className="p-6">
            <h2 className="text-2xl">{room.data.name}</h2>
            <p className="text-sm text-ink-500">{room.data.roomType.name} · Room {room.data.roomNumber}</p>
            <ul className="mt-4 space-y-2 text-sm text-ink-700">
              <li className="flex items-center gap-2"><CalendarDays className="size-4 text-gold-600" aria-hidden /> {formatDate(checkIn)} → {formatDate(checkOut)}</li>
              <li className="flex items-center gap-2"><Users className="size-4 text-gold-600" aria-hidden /> {guests} guest{guests > 1 ? 's' : ''}</li>
            </ul>
            <div className="mt-5"><PriceBreakdown {...q} /></div>
          </div>
        </aside>
      </div>
    </div>
  );
}
