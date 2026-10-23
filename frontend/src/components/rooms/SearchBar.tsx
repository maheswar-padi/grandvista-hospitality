import { zodResolver } from '@hookform/resolvers/zod';
import { useQuery } from '@tanstack/react-query';
import { CalendarDays, MapPin, Search, Users } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { propertiesApi } from '../../lib/api/endpoints';
import { hotelToday, plusDays } from '../../lib/dates';
import { decodeDestination, encodeDestination, groupByCity, type Destination } from '../../lib/destinations';
import { staySchema, type StayForm } from '../../lib/schemas';
import { Button } from '../ui/Button';
import { cn } from '../ui/cn';

interface Props {
  defaults: { checkIn: string; checkOut: string; guests: number; destination?: Destination };
  onSearch: (values: StayForm, destination: Destination) => void;
  variant?: 'hero' | 'inline';
  submitLabel?: string;
  /** show the hotel/city selector (home and search pages; not on a room's own page) */
  withDestination?: boolean;
}

/** Date-and-guests search (plus an optional destination) used on the home page, the search page and room details. */
export function SearchBar({ defaults, onSearch, variant = 'inline', submitLabel = 'Search availability', withDestination = false }: Props) {
  const [destination, setDestination] = useState(encodeDestination(defaults.destination ?? {}));
  const properties = useQuery({ queryKey: ['properties'], queryFn: propertiesApi.list, staleTime: 300_000, enabled: withDestination });
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm({ resolver: zodResolver(staySchema), defaultValues: defaults });
  const checkIn = watch('checkIn');
  const today = hotelToday();

  const fieldBox = 'flex flex-col gap-1 rounded-xl bg-white px-4 py-2.5 ring-1 ring-ink-100 focus-within:ring-2 focus-within:ring-gold-300';
  const labelCls = 'flex items-center gap-1.5 text-[0.7rem] font-semibold uppercase tracking-wider text-ink-500';
  const inputCls = 'w-full bg-transparent text-sm font-medium text-ink-900 outline-none';

  return (
    <form
      onSubmit={handleSubmit((v) => onSearch(v as StayForm, decodeDestination(destination)))}
      className={cn(
        'grid gap-3 sm:grid-cols-2',
        withDestination ? 'lg:grid-cols-[1.3fr_1fr_1fr_0.7fr_auto]' : 'lg:grid-cols-[1fr_1fr_0.7fr_auto]',
        variant === 'hero' && 'rounded-2xl bg-white/95 p-3 shadow-2xl ring-1 ring-black/5 backdrop-blur',
      )}
      aria-label="Search rooms"
      noValidate
    >
      {withDestination && (
        <div className="sm:col-span-2 lg:col-span-1">
          <label className={fieldBox}>
            <span className={labelCls}><MapPin className="size-3.5" aria-hidden /> Destination</span>
            <select className={inputCls} value={destination} onChange={(e) => setDestination(e.target.value)} aria-label="Destination: city or hotel">
              <option value="">All destinations</option>
              {groupByCity(properties.data ?? []).map((g) => (
                <optgroup key={g.city} label={`${g.city}, ${g.country}`}>
                  <option value={encodeDestination({ city: g.city })}>Anywhere in {g.city}</option>
                  {g.hotels.map((p) => (
                    <option key={p.id} value={encodeDestination({ propertyId: p.id })}>{p.name}</option>
                  ))}
                </optgroup>
              ))}
            </select>
          </label>
        </div>
      )}
      <div>
        <label className={fieldBox}>
          <span className={labelCls}><CalendarDays className="size-3.5" aria-hidden /> Check-in</span>
          <input type="date" min={today} className={inputCls} aria-invalid={!!errors.checkIn} {...register('checkIn')} />
        </label>
        {errors.checkIn && <p className="mt-1 text-xs text-red-700" role="alert">{errors.checkIn.message}</p>}
      </div>
      <div>
        <label className={fieldBox}>
          <span className={labelCls}><CalendarDays className="size-3.5" aria-hidden /> Check-out</span>
          <input type="date" min={checkIn ? plusDays(checkIn, 1) : today} className={inputCls} aria-invalid={!!errors.checkOut} {...register('checkOut')} />
        </label>
        {errors.checkOut && <p className="mt-1 text-xs text-red-700" role="alert">{errors.checkOut.message}</p>}
      </div>
      <div>
        <label className={fieldBox}>
          <span className={labelCls}><Users className="size-3.5" aria-hidden /> Guests</span>
          <select className={inputCls} {...register('guests')}>
            {Array.from({ length: 8 }, (_, i) => i + 1).map((n) => (
              <option key={n} value={n}>
                {n} {n === 1 ? 'guest' : 'guests'}
              </option>
            ))}
          </select>
        </label>
        {errors.guests && <p className="mt-1 text-xs text-red-700" role="alert">{errors.guests.message}</p>}
      </div>
      <Button type="submit" size="lg" variant={variant === 'hero' ? 'gold' : 'primary'} className="h-full min-h-14 sm:col-span-2 lg:col-span-1" icon={<Search className="size-4" aria-hidden />}>
        {submitLabel}
      </Button>
    </form>
  );
}
