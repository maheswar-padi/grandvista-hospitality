import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { SlidersHorizontal } from 'lucide-react';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { RoomCard, RoomCardSkeleton } from '../components/rooms/RoomCard';
import { SearchBar } from '../components/rooms/SearchBar';
import { Button } from '../components/ui/Button';
import { EmptyState, ErrorState } from '../components/ui/States';
import { Pagination } from '../components/ui/Pagination';
import { propertiesApi, roomsApi, type RoomSearchParams } from '../lib/api/endpoints';
import { ApiError } from '../lib/api/http';
import { defaultStay } from '../lib/dates';
import { describeDestination, uniqueRoomTypes } from '../lib/destinations';
import { formatDate } from '../lib/format';

const sorts: Array<{ value: NonNullable<RoomSearchParams['sort']>; label: string }> = [
  { value: 'RECOMMENDED', label: 'Recommended' },
  { value: 'PRICE_ASC', label: 'Price: low to high' },
  { value: 'PRICE_DESC', label: 'Price: high to low' },
  { value: 'CAPACITY_DESC', label: 'Most spacious' },
  { value: 'NAME_ASC', label: 'Name' },
];

export default function SearchPage() {
  const [params, setParams] = useSearchParams();
  const [filtersOpen, setFiltersOpen] = useState(false);
  const fallback = defaultStay();

  const criteria: RoomSearchParams = {
    propertyId: params.get('propertyId') ?? undefined,
    city: params.get('city') ?? undefined,
    checkIn: params.get('checkIn') ?? undefined,
    checkOut: params.get('checkOut') ?? undefined,
    guests: params.get('guests') ? Number(params.get('guests')) : undefined,
    roomType: params.get('roomType') ?? undefined,
    minPrice: params.get('minPrice') ? Number(params.get('minPrice')) : undefined,
    maxPrice: params.get('maxPrice') ? Number(params.get('maxPrice')) : undefined,
    amenities: params.getAll('amenities'),
    sort: (params.get('sort') as RoomSearchParams['sort']) ?? 'RECOMMENDED',
    page: Number(params.get('page') ?? 0),
    size: 9,
  };

  const results = useQuery({
    queryKey: ['rooms', 'search', criteria],
    queryFn: ({ signal }) => roomsApi.search(criteria, signal),
    placeholderData: keepPreviousData,
  });
  const types = useQuery({
    queryKey: ['room-types', criteria.propertyId],
    queryFn: () => roomsApi.roomTypes(criteria.propertyId),
    staleTime: 300_000,
    select: uniqueRoomTypes,
  });
  const properties = useQuery({ queryKey: ['properties'], queryFn: propertiesApi.list, staleTime: 300_000 });
  const where = describeDestination({ propertyId: criteria.propertyId, city: criteria.city }, properties.data ?? []);
  const amenities = useQuery({ queryKey: ['amenities'], queryFn: roomsApi.amenities, staleTime: 300_000 });

  const update = (changes: Record<string, string | string[] | undefined>) => {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([k, v]) => {
      next.delete(k);
      if (Array.isArray(v)) v.forEach((x) => next.append(k, x));
      else if (v) next.set(k, v);
    });
    if (!('page' in changes)) next.delete('page');
    setParams(next);
  };

  const toggleAmenity = (code: string) => {
    const current = new Set(criteria.amenities);
    if (current.has(code)) current.delete(code);
    else current.add(code);
    update({ amenities: [...current] });
  };

  const stayQuery = new URLSearchParams();
  if (criteria.checkIn && criteria.checkOut) {
    stayQuery.set('checkIn', criteria.checkIn);
    stayQuery.set('checkOut', criteria.checkOut);
  }
  if (criteria.guests) stayQuery.set('guests', String(criteria.guests));
  // "clear filters" keeps the stay and the destination
  const keepQuery = new URLSearchParams(stayQuery);
  if (criteria.propertyId) keepQuery.set('propertyId', criteria.propertyId);
  if (criteria.city) keepQuery.set('city', criteria.city);
  const dated = !!(criteria.checkIn && criteria.checkOut);
  const validationError = results.error instanceof ApiError && results.error.status === 400 ? results.error : null;

  const filters = (
    <div className="space-y-6">
      <div>
        <label htmlFor="roomType" className="mb-2 block text-sm font-semibold text-ink-800">Room type</label>
        <select
          id="roomType"
          className="w-full rounded-xl border border-ink-200 bg-white px-3 py-2 text-sm"
          value={criteria.roomType ?? ''}
          onChange={(e) => update({ roomType: e.target.value || undefined })}
        >
          <option value="">All types</option>
          {types.data?.map((t) => (
            <option key={t.code} value={t.code}>{t.name}</option>
          ))}
        </select>
      </div>
      <fieldset>
        <legend className="mb-2 text-sm font-semibold text-ink-800">Base nightly price (INR)</legend>
        <div className="flex gap-2">
          <input
            type="number"
            min={0}
            step={500}
            placeholder="Min"
            aria-label="Minimum nightly price"
            defaultValue={criteria.minPrice}
            className="w-full rounded-xl border border-ink-200 bg-white px-3 py-2 text-sm"
            onBlur={(e) => update({ minPrice: e.target.value || undefined })}
          />
          <input
            type="number"
            min={0}
            step={500}
            placeholder="Max"
            aria-label="Maximum nightly price"
            defaultValue={criteria.maxPrice}
            className="w-full rounded-xl border border-ink-200 bg-white px-3 py-2 text-sm"
            onBlur={(e) => update({ maxPrice: e.target.value || undefined })}
          />
        </div>
      </fieldset>
      <fieldset>
        <legend className="mb-2 text-sm font-semibold text-ink-800">Amenities</legend>
        <div className="space-y-1.5">
          {amenities.data?.map((a) => (
            <label key={a.code} className="flex cursor-pointer items-center gap-2 text-sm text-ink-700">
              <input type="checkbox" className="size-4 accent-ink-900" checked={criteria.amenities?.includes(a.code) ?? false} onChange={() => toggleAmenity(a.code)} />
              {a.name}
            </label>
          ))}
        </div>
      </fieldset>
      <Button variant="ghost" size="sm" onClick={() => setParams(new URLSearchParams(keepQuery))}>Clear filters</Button>
    </div>
  );

  return (
    <div className="container-page py-10">
      <h1 className="text-4xl sm:text-5xl">{where ? `Rooms in ${where}` : 'Rooms & suites'}</h1>
      <p className="mt-2 text-ink-600">
        {dated
          ? `Showing rooms available from ${formatDate(criteria.checkIn)} to ${formatDate(criteria.checkOut)}${criteria.guests ? ` for ${criteria.guests} guest${criteria.guests > 1 ? 's' : ''}` : ''}.`
          : 'Choose dates to see live availability and the exact price of your stay.'}
      </p>
      <div className="mt-6">
        <SearchBar
          key={`${criteria.checkIn}-${criteria.checkOut}-${criteria.guests}-${criteria.propertyId}-${criteria.city}`}
          withDestination
          defaults={{
            checkIn: criteria.checkIn ?? fallback.checkIn,
            checkOut: criteria.checkOut ?? fallback.checkOut,
            guests: criteria.guests ?? 2,
            destination: { propertyId: criteria.propertyId, city: criteria.city },
          }}
          onSearch={(v, d) => update({ checkIn: v.checkIn, checkOut: v.checkOut, guests: String(v.guests), propertyId: d.propertyId, city: d.city, roomType: undefined })}
        />
      </div>

      <div className="mt-10 grid gap-8 lg:grid-cols-[260px_1fr]">
        <aside className="hidden lg:block" aria-label="Filters">{filters}</aside>
        <div>
          <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-ink-600" aria-live="polite">
              {results.data ? `${results.data.totalElements} room${results.data.totalElements === 1 ? '' : 's'} found` : 'Searching…'}
            </p>
            <div className="flex items-center gap-2">
              <Button variant="secondary" size="sm" className="lg:hidden" onClick={() => setFiltersOpen((v) => !v)} aria-expanded={filtersOpen} icon={<SlidersHorizontal className="size-4" aria-hidden />}>
                Filters
              </Button>
              <label htmlFor="sort" className="sr-only">Sort by</label>
              <select id="sort" className="rounded-full border border-ink-200 bg-white px-3 py-2 text-sm" value={criteria.sort} onChange={(e) => update({ sort: e.target.value })}>
                {sorts.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
              </select>
            </div>
          </div>
          {filtersOpen && <div className="card mb-6 p-5 lg:hidden">{filters}</div>}

          {validationError ? (
            <ErrorState error={validationError} title="Please check your dates" />
          ) : results.isError ? (
            <ErrorState error={results.error} onRetry={() => results.refetch()} />
          ) : results.isPending ? (
            <div className="grid gap-6 sm:grid-cols-2 xl:grid-cols-3">
              {Array.from({ length: 6 }, (_, i) => <RoomCardSkeleton key={i} />)}
            </div>
          ) : results.data.content.length === 0 ? (
            <EmptyState title="No rooms match your search" action={<Button variant="secondary" onClick={() => setParams(new URLSearchParams(keepQuery))}>Clear filters</Button>}>
              {dated ? 'Every matching room is booked for these dates. Try different dates or fewer filters.' : 'Try removing some filters.'}
            </EmptyState>
          ) : (
            <div className={`grid gap-6 sm:grid-cols-2 xl:grid-cols-3 ${results.isFetching ? 'opacity-70' : ''}`}>
              {results.data.content.map((room) => <RoomCard key={room.id} room={room} search={stayQuery.toString()} />)}
            </div>
          )}
          {results.data && (
            <div className="mt-10">
              <Pagination page={results.data.page} totalPages={results.data.totalPages} onChange={(p) => update({ page: String(p) })} />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
