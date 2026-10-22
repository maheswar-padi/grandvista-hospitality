import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, BedDouble, Building, CheckCircle2, Layers, Maximize2, Users, XCircle } from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { AmenityIcon } from '../components/rooms/AmenityIcon';
import { PriceBreakdown } from '../components/rooms/PriceBreakdown';
import { RoomIllustration } from '../components/rooms/RoomIllustration';
import { SearchBar } from '../components/rooms/SearchBar';
import { Alert } from '../components/ui/Alert';
import { Button } from '../components/ui/Button';
import { ErrorState, LoadingBlock } from '../components/ui/States';
import { roomsApi } from '../lib/api/endpoints';
import { ApiError } from '../lib/api/http';
import { useAuth } from '../lib/auth/AuthContext';
import { defaultStay, hotelToday } from '../lib/dates';
import { formatDate, formatMoney, humanize } from '../lib/format';

export default function RoomDetailsPage() {
  const { roomId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const { status, hasRole } = useAuth();
  const [activeImage, setActiveImage] = useState(0);
  const fallback = defaultStay();
  const checkIn = params.get('checkIn') ?? fallback.checkIn;
  const checkOut = params.get('checkOut') ?? fallback.checkOut;
  const guests = Number(params.get('guests') ?? 2);

  const room = useQuery({ queryKey: ['room', roomId], queryFn: () => roomsApi.get(roomId) });
  const quote = useQuery({
    queryKey: ['quote', roomId, checkIn, checkOut, guests],
    queryFn: () => roomsApi.quote(roomId, checkIn, checkOut, guests),
    enabled: room.isSuccess,
    staleTime: 0,
  });
  const calendar = useQuery({ queryKey: ['calendar', roomId], queryFn: () => roomsApi.calendar(roomId, hotelToday(), 60), enabled: room.isSuccess });

  if (room.isPending) return <div className="container-page py-16"><LoadingBlock /></div>;
  if (room.isError) {
    return (
      <div className="container-page py-16">
        <ErrorState error={room.error} title={room.error instanceof ApiError && room.error.status === 404 ? 'Room not found' : 'Could not load this room'} onRetry={() => room.refetch()} />
      </div>
    );
  }
  const r = room.data;
  const images = r.images;
  const stayQuery = new URLSearchParams({ roomId, checkIn, checkOut, guests: String(guests) });
  const reserve = () => {
    const target = `/checkout?${stayQuery.toString()}`;
    navigate(status === 'authenticated' ? target : `/login?next=${encodeURIComponent(target)}`);
  };
  const isPersonnelOnly = status === 'authenticated' && !hasRole('CUSTOMER');

  return (
    <div className="container-page py-8">
      <Link to={`/rooms?${new URLSearchParams({ checkIn, checkOut, guests: String(guests) })}`} className="-my-2 inline-flex items-center gap-1.5 py-2.5 text-sm text-ink-600 hover:text-ink-900">
        <ArrowLeft className="size-4" aria-hidden /> Back to results
      </Link>

      <div className="mt-6 grid gap-10 lg:grid-cols-[1fr_400px]">
        <div>
          <div className="overflow-hidden rounded-3xl">
            {images.length > 0 ? (
              <img src={images[activeImage]?.url} alt={images[activeImage]?.altText ?? `Photograph of ${r.name}`} className="aspect-[16/10] w-full object-cover" />
            ) : (
              <RoomIllustration typeCode={r.roomType.code} view={r.viewType} className="aspect-[16/10] w-full" label={`Illustration of the ${r.name} (artwork, not a photograph)`} />
            )}
          </div>
          {images.length > 1 && (
            <div className="relative mt-3 flex gap-3 overflow-x-auto" role="list" aria-label="Room photographs">
              {images.map((img, i) => (
                <button key={img.id} type="button" role="listitem" onClick={() => setActiveImage(i)} aria-label={`Show photograph ${i + 1}`} aria-pressed={i === activeImage}
                  className={`h-20 w-28 shrink-0 overflow-hidden rounded-xl ring-2 ${i === activeImage ? 'ring-gold-400' : 'ring-transparent'}`}>
                  <img src={img.url} alt="" className="size-full object-cover" loading="lazy" />
                </button>
              ))}
            </div>
          )}
          {images.length === 0 && <p className="mt-2 text-xs text-ink-500">Illustrative artwork — photographs have not been uploaded for this room.</p>}

          <p className="eyebrow mt-8">{r.roomType.name} · Room {r.roomNumber}</p>
          <h1 className="mt-2 text-4xl sm:text-5xl">{r.name}</h1>
          <ul className="mt-5 flex flex-wrap gap-x-6 gap-y-2 text-sm text-ink-700">
            <li className="flex items-center gap-2"><Users className="size-4 text-gold-600" aria-hidden /> Up to {r.maxOccupancy} guests</li>
            <li className="flex items-center gap-2"><BedDouble className="size-4 text-gold-600" aria-hidden /> {r.bedConfiguration}</li>
            {r.sizeSqM && <li className="flex items-center gap-2"><Maximize2 className="size-4 text-gold-600" aria-hidden /> {r.sizeSqM} m²</li>}
            {r.floor !== null && <li className="flex items-center gap-2"><Layers className="size-4 text-gold-600" aria-hidden /> Floor {r.floor}</li>}
            {r.viewType && <li className="flex items-center gap-2"><Building className="size-4 text-gold-600" aria-hidden /> {r.viewType} view</li>}
          </ul>
          {r.description && <p className="mt-6 max-w-2xl text-lg leading-relaxed text-ink-700">{r.description}</p>}

          <h2 className="mt-10 text-3xl">Amenities</h2>
          <ul className="mt-4 grid gap-3 sm:grid-cols-2">
            {r.amenities.map((a) => (
              <li key={a.code} className="flex items-center gap-3 text-sm text-ink-700">
                <span className="rounded-lg bg-gold-50 p-2 text-gold-700"><AmenityIcon name={a.icon} /></span>
                {a.name}
              </li>
            ))}
          </ul>

          {calendar.data && (
            <>
              <h2 className="mt-10 text-3xl">Availability (next 60 days)</h2>
              {calendar.data.unavailableNights.length === 0 ? (
                <p className="mt-3 text-sm text-ink-600">No nights are booked in the next 60 days.</p>
              ) : (
                <>
                  <p className="mt-3 text-sm text-ink-600">These nights are already reserved:</p>
                  <ul className="mt-3 flex flex-wrap gap-2">
                    {calendar.data.unavailableNights.map((d) => (
                      <li key={d} className="rounded-full bg-ink-100 px-3 py-1 text-xs text-ink-700">{formatDate(d, 'd MMM')}</li>
                    ))}
                  </ul>
                </>
              )}
            </>
          )}
        </div>

        <aside className="lg:sticky lg:top-24 lg:self-start" aria-label="Book this room">
          <div className="card p-6">
            <p className="text-sm text-ink-500">Base rate from</p>
            <p className="text-3xl font-semibold">{formatMoney(r.basePrice, r.currency, 0)}<span className="text-base font-normal text-ink-500"> / night</span></p>
            <div className="mt-5 [&_form]:grid-cols-1 [&_form]:lg:grid-cols-1">
              <SearchBar
                key={`${checkIn}-${checkOut}-${guests}`}
                defaults={{ checkIn, checkOut, guests }}
                submitLabel="Update price"
                onSearch={(v) => setParams({ checkIn: v.checkIn, checkOut: v.checkOut, guests: String(v.guests) })}
              />
            </div>
            <div className="mt-6" aria-live="polite">
              {quote.isPending && <LoadingBlock label="Calculating your price…" />}
              {quote.isError && <Alert tone="error" title="Price unavailable">{quote.error.message}</Alert>}
              {quote.data && (
                <>
                  {quote.data.available ? (
                    <p className="mb-4 flex items-center gap-2 text-sm font-medium text-emerald-800">
                      <CheckCircle2 className="size-4" aria-hidden /> Available {formatDate(checkIn, 'd MMM')} – {formatDate(checkOut, 'd MMM')}
                    </p>
                  ) : (
                    <Alert tone="warning" title="Not available for these dates" className="mb-4">
                      <span className="inline-flex items-center gap-1"><XCircle className="size-4" aria-hidden /> {humanize(quote.data.unavailableReason)}. Try other dates.</span>
                    </Alert>
                  )}
                  <PriceBreakdown {...quote.data.quote} />
                  <Button size="lg" variant="primary" className="mt-6 w-full" disabled={!quote.data.available || isPersonnelOnly} onClick={reserve}>
                    {status === 'authenticated' ? 'Reserve this room' : 'Sign in to reserve'}
                  </Button>
                  {isPersonnelOnly && <p className="mt-2 text-xs text-ink-500">Staff accounts cannot make guest bookings. Sign in with a customer account.</p>}
                  <p className="mt-3 text-center text-xs text-ink-500">You won’t be charged yet. The room is held for 15 minutes while you pay.</p>
                </>
              )}
            </div>
          </div>
        </aside>
      </div>
    </div>
  );
}
