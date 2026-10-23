import { BedDouble, Maximize2, Users } from 'lucide-react';
import { Link } from 'react-router';
import type { Room } from '../../lib/api/types';
import { formatMoney } from '../../lib/format';
import { Badge } from '../ui/Badge';
import { AmenityIcon } from './AmenityIcon';
import { RoomVisual } from './RoomVisual';

export function RoomCard({ room, search }: { room: Room; search?: string }) {
  const href = `/rooms/${room.id}${search ? `?${search}` : ''}`;
  const price = room.stayPrice;
  return (
    <article className="card group flex flex-col overflow-hidden transition hover:-translate-y-0.5 hover:shadow-lg">
      <Link to={href} className="relative block aspect-[3/2] overflow-hidden" tabIndex={-1} aria-hidden>
        <RoomVisual room={room} className="size-full transition duration-500 group-hover:scale-[1.03]" />
        <Badge tone="gold" className="absolute left-3 top-3 bg-white/90 backdrop-blur">
          {room.roomType.name}
        </Badge>
      </Link>
      <div className="flex flex-1 flex-col gap-4 p-5">
        <div>
          <h3 className="text-2xl leading-tight">
            <Link to={href} className="hover:text-gold-700 focus-visible:text-gold-700">
              {room.name}
            </Link>
          </h3>
          <p className="mt-1 text-xs text-ink-500">
            {room.property ? `${room.property.name} · ${room.property.city} · ` : ''}Room {room.roomNumber}{room.viewType ? ` · ${room.viewType} view` : ''}
          </p>
        </div>
        <ul className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-ink-600">
          <li className="flex items-center gap-1.5"><Users className="size-4" aria-hidden /> Up to {room.maxOccupancy}</li>
          <li className="flex items-center gap-1.5"><BedDouble className="size-4" aria-hidden /> {room.bedConfiguration}</li>
          {room.sizeSqM && <li className="flex items-center gap-1.5"><Maximize2 className="size-4" aria-hidden /> {room.sizeSqM} m²</li>}
        </ul>
        <ul className="flex flex-wrap gap-2" aria-label="Amenities">
          {room.amenities.slice(0, 5).map((a) => (
            <li key={a.code} className="flex items-center gap-1 rounded-full bg-ink-50 px-2.5 py-1 text-xs text-ink-700">
              <AmenityIcon name={a.icon} className="size-3.5" /> {a.name}
            </li>
          ))}
          {room.amenities.length > 5 && <li className="px-1 py-1 text-xs text-ink-500">+{room.amenities.length - 5} more</li>}
        </ul>
        <div className="mt-auto flex items-end justify-between gap-3 border-t border-ink-100 pt-4">
          {price ? (
            <div>
              <p className="text-xs text-ink-500">{price.nights} night{price.nights > 1 ? 's' : ''} · taxes &amp; fees incl.</p>
              <p className="text-xl font-semibold text-ink-900">{formatMoney(price.total, price.currency, 0)}</p>
              <p className="text-xs text-ink-500">avg {formatMoney(price.averageNightlyRate, price.currency, 0)} / night before tax</p>
            </div>
          ) : (
            <div>
              <p className="text-xs text-ink-500">From</p>
              <p className="text-xl font-semibold text-ink-900">{formatMoney(room.basePrice, room.currency, 0)}</p>
              <p className="text-xs text-ink-500">per night, before dynamic pricing &amp; tax</p>
            </div>
          )}
          <Link
            to={href}
            className="rounded-full bg-ink-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-ink-700"
            aria-label={`View ${room.name}`}
          >
            View
          </Link>
        </div>
      </div>
    </article>
  );
}

export function RoomCardSkeleton() {
  return (
    <div className="card overflow-hidden" aria-hidden>
      <div className="skeleton aspect-[3/2] rounded-none" />
      <div className="space-y-3 p-5">
        <div className="skeleton h-6 w-2/3" />
        <div className="skeleton h-4 w-1/2" />
        <div className="skeleton h-4 w-3/4" />
        <div className="skeleton mt-6 h-8 w-1/3" />
      </div>
    </div>
  );
}
