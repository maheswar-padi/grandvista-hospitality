import { useQuery } from '@tanstack/react-query';
import { BadgePercent, CalendarClock, ShieldCheck, Sparkles } from 'lucide-react';
import { createSearchParams, useNavigate } from 'react-router';
import { AmenityIcon } from '../components/rooms/AmenityIcon';
import { RoomCard, RoomCardSkeleton } from '../components/rooms/RoomCard';
import { RoomIllustration } from '../components/rooms/RoomIllustration';
import { SearchBar } from '../components/rooms/SearchBar';
import { ButtonLink } from '../components/ui/Button';
import { ErrorState } from '../components/ui/States';
import { propertiesApi, roomsApi } from '../lib/api/endpoints';
import { defaultStay } from '../lib/dates';
import { uniqueRoomTypes } from '../lib/destinations';

const promises = [
  {
    icon: BadgePercent,
    title: 'Transparent dynamic pricing',
    text: 'Every quote shows each night’s rate and why it changed — weekends, seasons or demand — plus fees and taxes. No surprises at checkout.',
  },
  {
    icon: CalendarClock,
    title: 'Your room is held while you pay',
    text: 'When you reserve, the room is held for you for 15 minutes. Nobody else can book it in the meantime.',
  },
  {
    icon: ShieldCheck,
    title: 'Secure by design',
    text: 'Sessions use short-lived tokens, passwords are hashed, and payments never touch card numbers (they are simulated in this demo).',
  },
];

export function HomePage() {
  const navigate = useNavigate();
  const stay = defaultStay();
  const featured = useQuery({ queryKey: ['rooms', 'featured'], queryFn: () => roomsApi.search({ size: 3, sort: 'RECOMMENDED' }) });
  const types = useQuery({ queryKey: ['room-types', undefined], queryFn: () => roomsApi.roomTypes(), staleTime: 300_000, select: uniqueRoomTypes });
  const hotels = useQuery({ queryKey: ['properties'], queryFn: propertiesApi.list, staleTime: 300_000 });
  const amenities = useQuery({ queryKey: ['amenities'], queryFn: roomsApi.amenities, staleTime: 300_000 });

  return (
    <>
      <section className="relative overflow-hidden bg-ink-900 text-white">
        <div className="absolute inset-0 opacity-40" aria-hidden>
          <RoomIllustration typeCode="PRESIDENTIAL" view="Sea" className="size-full" label="" />
        </div>
        <div className="absolute inset-0 bg-gradient-to-b from-ink-950/80 via-ink-900/70 to-ink-900" aria-hidden />
        <div className="container-page relative py-20 sm:py-28">
          <p className="eyebrow text-gold-200">GrandVista Hospitality · Demo resort</p>
          <h1 className="mt-4 max-w-3xl text-5xl leading-[1.05] text-white sm:text-6xl lg:text-7xl">
            Stay where the city <em className="text-gold-300 not-italic">meets the sea</em>.
          </h1>
          <p className="mt-6 max-w-xl text-lg text-ink-100">
            Live availability, honest prices and a room that is held for you the moment you reserve it.
          </p>
          <div className="mt-10 max-w-5xl">
            <SearchBar
              variant="hero"
              withDestination
              defaults={{ ...stay, guests: 2 }}
              onSearch={(v, d) =>
                navigate({
                  pathname: '/rooms',
                  search: createSearchParams({
                    checkIn: v.checkIn,
                    checkOut: v.checkOut,
                    guests: String(v.guests),
                    ...(d.propertyId ? { propertyId: d.propertyId } : {}),
                    ...(d.city ? { city: d.city } : {}),
                  }).toString(),
                })
              }
            />
          </div>
        </div>
      </section>

      {hotels.data && hotels.data.length > 0 && (
        <section className="container-page pt-20" aria-labelledby="hotels-heading">
          <p className="eyebrow">Our hotels</p>
          <h2 id="hotels-heading" className="mt-2 text-4xl">{hotels.data.length} hotels, one booking experience</h2>
          <ul className="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {hotels.data.map((h) => (
              <li key={h.id} className="card flex flex-col gap-1 p-5">
                <span className="text-2xl">{h.name}</span>
                <span className="text-sm text-ink-600">{h.city}, {h.country} · {h.activeRooms} rooms · local time zone {h.timeZone}</span>
                <ButtonLink to={`/rooms?propertyId=${h.id}`} variant="secondary" size="sm" className="mt-3 self-start">View rooms</ButtonLink>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="container-page py-20" aria-labelledby="featured-heading">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="eyebrow">Rooms &amp; suites</p>
            <h2 id="featured-heading" className="mt-2 text-4xl">A room for every kind of stay</h2>
          </div>
          <ButtonLink to="/rooms" variant="secondary">See all rooms</ButtonLink>
        </div>
        <div className="mt-10 grid gap-6 md:grid-cols-2 lg:grid-cols-3">
          {featured.isPending && Array.from({ length: 3 }, (_, i) => <RoomCardSkeleton key={i} />)}
          {featured.data?.content.map((room) => <RoomCard key={room.id} room={room} />)}
        </div>
        {featured.isError && <ErrorState error={featured.error} onRetry={() => featured.refetch()} title="Rooms are unavailable right now" />}
        {types.data && (
          <ul className="mt-10 flex flex-wrap gap-3" aria-label="Room categories">
            {types.data.map((t) => (
              <li key={t.code}>
                <ButtonLink to={`/rooms?roomType=${t.code}`} variant="secondary" size="sm">{t.name}</ButtonLink>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="bg-white py-20" aria-labelledby="promise-heading">
        <div className="container-page">
          <p className="eyebrow">Booking with GrandVista</p>
          <h2 id="promise-heading" className="mt-2 text-4xl">Clear, fair and dependable</h2>
          <div className="mt-10 grid gap-6 md:grid-cols-3">
            {promises.map(({ icon: Icon, title, text }) => (
              <div key={title} className="rounded-2xl border border-ink-100 bg-ivory p-6">
                <Icon className="size-7 text-gold-600" aria-hidden />
                <h3 className="mt-4 text-2xl">{title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-ink-600">{text}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {amenities.data && (
        <section className="container-page py-20" aria-labelledby="amenities-heading">
          <p className="eyebrow">Amenities</p>
          <h2 id="amenities-heading" className="mt-2 text-4xl">Thoughtful details throughout</h2>
          <ul className="mt-8 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
            {amenities.data.map((a) => (
              <li key={a.code} className="flex items-center gap-3 rounded-xl bg-white px-4 py-3 text-sm ring-1 ring-ink-100">
                <span className="rounded-lg bg-gold-50 p-2 text-gold-700"><AmenityIcon name={a.icon} /></span>
                {a.name}
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="container-page" aria-labelledby="cta-heading">
        <div className="relative overflow-hidden rounded-3xl bg-ink-900 px-8 py-14 text-white sm:px-14">
          <Sparkles className="absolute right-8 top-8 size-16 text-gold-400/30" aria-hidden />
          <h2 id="cta-heading" className="max-w-xl text-4xl text-white">Ready when you are.</h2>
          <p className="mt-3 max-w-xl text-ink-100">Pick your dates to see live availability and the exact price for your stay.</p>
          <ButtonLink to="/rooms" variant="gold" size="lg" className="mt-8">Find your room</ButtonLink>
        </div>
      </section>
    </>
  );
}
