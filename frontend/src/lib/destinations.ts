import type { Property, RoomType } from './api/types';

/** A search destination: everywhere, every hotel in a city, or one hotel. Encoded for a single <select>. */
export interface Destination {
  propertyId?: string;
  city?: string;
}

export function encodeDestination(d: Destination) {
  if (d.propertyId) return `property:${d.propertyId}`;
  if (d.city) return `city:${d.city}`;
  return '';
}

export function decodeDestination(value: string): Destination {
  if (value.startsWith('property:')) return { propertyId: value.slice('property:'.length) };
  if (value.startsWith('city:')) return { city: value.slice('city:'.length) };
  return {};
}

/** Hotels grouped by city, cities and hotels sorted by name. */
export function groupByCity(properties: Property[]) {
  const groups = new Map<string, Property[]>();
  [...properties]
    .sort((a, b) => a.city.localeCompare(b.city) || a.name.localeCompare(b.name))
    .forEach((p) => groups.set(p.city, [...(groups.get(p.city) ?? []), p]));
  return [...groups.entries()].map(([city, hotels]) => ({ city, country: hotels[0].country, hotels }));
}

export function describeDestination(d: Destination, properties: Property[]) {
  if (d.propertyId) {
    const p = properties.find((x) => x.id === d.propertyId);
    return p ? `${p.name}, ${p.city}` : 'the selected hotel';
  }
  return d.city ?? null;
}

/** Room types are defined per hotel with shared codes; lists for the whole portfolio show each code once. */
export function uniqueRoomTypes(types: RoomType[]) {
  const seen = new Set<string>();
  return types.filter((t) => (seen.has(t.code) ? false : (seen.add(t.code), true)));
}
