import { useQuery } from '@tanstack/react-query';
import { CalendarDays, ChevronRight, Mail, Phone, ShieldCheck, UserRound } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { BookingStatusBadge } from '../components/booking/BookingStatusBadge';
import { ButtonLink } from '../components/ui/Button';
import { EmptyState, ErrorState, LoadingBlock } from '../components/ui/States';
import { Pagination } from '../components/ui/Pagination';
import { bookingsApi } from '../lib/api/endpoints';
import { useAuth } from '../lib/auth/AuthContext';
import { formatDate, formatMoney } from '../lib/format';

const tabs = [
  { id: 'UPCOMING', label: 'Upcoming' },
  { id: 'PAST', label: 'Past' },
  { id: 'ALL', label: 'All bookings' },
] as const;

export default function AccountPage() {
  const { user } = useAuth();
  const [tab, setTab] = useState<(typeof tabs)[number]['id']>('UPCOMING');
  const [page, setPage] = useState(0);
  const bookings = useQuery({ queryKey: ['my-bookings', tab, page], queryFn: () => bookingsApi.mine(tab, page) });

  return (
    <div className="container-page py-10">
      <p className="eyebrow">My account</p>
      <h1 className="mt-2 text-4xl sm:text-5xl">Hello, {user?.fullName.split(' ')[0]}</h1>

      <div className="mt-8 grid gap-8 lg:grid-cols-[1fr_320px]">
        <section aria-labelledby="bookings-heading">
          <h2 id="bookings-heading" className="sr-only">Bookings</h2>
          <div role="tablist" aria-label="Booking filter" className="mb-6 inline-flex rounded-full bg-white p-1 ring-1 ring-ink-100">
            {tabs.map((t) => (
              <button
                key={t.id}
                role="tab"
                type="button"
                aria-selected={tab === t.id}
                className={`rounded-full px-4 py-2 text-sm font-medium ${tab === t.id ? 'bg-ink-900 text-white' : 'text-ink-600 hover:text-ink-900'}`}
                onClick={() => {
                  setTab(t.id);
                  setPage(0);
                }}
              >
                {t.label}
              </button>
            ))}
          </div>
          <div role="tabpanel">
            {bookings.isPending ? (
              <LoadingBlock />
            ) : bookings.isError ? (
              <ErrorState error={bookings.error} onRetry={() => bookings.refetch()} />
            ) : bookings.data.content.length === 0 ? (
              <EmptyState title={tab === 'PAST' ? 'No past stays yet' : 'No bookings yet'} icon={<CalendarDays className="size-6" aria-hidden />} action={<ButtonLink to="/rooms">Find a room</ButtonLink>}>
                Your reservations will appear here.
              </EmptyState>
            ) : (
              <ul className="space-y-3">
                {bookings.data.content.map((b) => (
                  <li key={b.id}>
                    <Link to={`/account/bookings/${b.id}`} className="card flex items-center gap-4 p-5 transition hover:shadow-lg">
                      <div className="hidden size-14 shrink-0 flex-col items-center justify-center rounded-xl bg-gold-50 text-gold-700 sm:flex">
                        <span className="text-xs font-semibold uppercase">{formatDate(b.checkIn, 'MMM')}</span>
                        <span className="font-display text-2xl leading-none">{formatDate(b.checkIn, 'd')}</span>
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-semibold text-ink-900">{b.room.name ?? 'Room'}</span>
                          <BookingStatusBadge status={b.status} />
                        </div>
                        <p className="mt-1 text-sm text-ink-600">
                          {formatDate(b.checkIn, 'd MMM')} – {formatDate(b.checkOut, 'd MMM yyyy')} · {b.nights} night{b.nights > 1 ? 's' : ''} · {b.reference}
                        </p>
                      </div>
                      <span className="hidden text-right font-semibold sm:block">{formatMoney(b.price?.total, b.price?.currency, 0)}</span>
                      <ChevronRight className="size-5 text-ink-400" aria-hidden />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
            {bookings.data && <div className="mt-6"><Pagination page={bookings.data.page} totalPages={bookings.data.totalPages} onChange={setPage} /></div>}
          </div>
        </section>

        <aside className="card h-fit p-6" aria-labelledby="account-heading">
          <h2 id="account-heading" className="text-2xl">Account</h2>
          <dl className="mt-4 space-y-3 text-sm">
            <div className="flex items-center gap-2"><UserRound className="size-4 text-gold-600" aria-hidden /><dt className="sr-only">Name</dt><dd>{user?.fullName}</dd></div>
            <div className="flex items-center gap-2"><Mail className="size-4 text-gold-600" aria-hidden /><dt className="sr-only">E-mail</dt><dd className="break-all">{user?.email}</dd></div>
            {user?.phone && <div className="flex items-center gap-2"><Phone className="size-4 text-gold-600" aria-hidden /><dt className="sr-only">Phone</dt><dd>{user.phone}</dd></div>}
            <div className="flex items-center gap-2"><ShieldCheck className="size-4 text-gold-600" aria-hidden /><dt className="sr-only">Roles</dt><dd>{user?.roles.join(', ').toLowerCase()}</dd></div>
          </dl>
        </aside>
      </div>
    </div>
  );
}
