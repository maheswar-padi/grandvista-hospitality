import type { Booking, Payment, Property, QuoteResponse, ReconciliationReport, Room } from '../lib/api/types';
import { hotelToday, plusDays } from '../lib/dates';

export const checkIn = plusDays(hotelToday(), 20);
export const checkOut = plusDays(checkIn, 2);

export const hotels: Property[] = [
  { id: 'p-hyd', code: 'HYD', name: 'GrandVista Hyderabad', city: 'Hyderabad', country: 'IN', timeZone: 'Asia/Kolkata', active: true, totalRooms: 11, activeRooms: 11, createdAt: '2026-10-01T00:00:00Z', version: 0 },
  { id: 'p-goa', code: 'GOA', name: 'GrandVista Goa', city: 'Goa', country: 'IN', timeZone: 'Asia/Kolkata', active: true, totalRooms: 4, activeRooms: 4, createdAt: '2026-10-01T00:00:00Z', version: 0 },
  { id: 'p-dxb', code: 'DXB', name: 'GrandVista Dubai', city: 'Dubai', country: 'AE', timeZone: 'Asia/Dubai', active: true, totalRooms: 4, activeRooms: 4, createdAt: '2026-10-01T00:00:00Z', version: 0 },
];

export const room: Room = {
  id: 'room-1',
  property: { id: 'p-hyd', code: 'HYD', name: 'GrandVista Hyderabad', city: 'Hyderabad', country: 'IN', timeZone: 'Asia/Kolkata' },
  roomNumber: '201',
  name: 'Deluxe King, City View',
  description: 'Floor-to-ceiling windows over the skyline.',
  roomType: { id: 't-1', propertyId: 'p-hyd', code: 'DELUXE', name: 'Deluxe Room', description: null, sortOrder: 2 },
  maxOccupancy: 3,
  bedConfiguration: '1 King bed',
  sizeSqM: 32,
  floor: 2,
  viewType: 'City',
  basePrice: 7200,
  currency: 'INR',
  status: 'ACTIVE',
  statusReason: null,
  amenities: [
    { id: 'a-1', code: 'WIFI', name: 'High-speed Wi-Fi', icon: 'wifi', category: 'CONNECTIVITY' },
    { id: 'a-2', code: 'CITY_VIEW', name: 'City view', icon: 'building-2', category: 'VIEW' },
  ],
  images: [],
  stayPrice: { nights: 2, averageNightlyRate: 7200, total: 16934.4, currency: 'INR', pricingPolicyVersion: 1 },
  version: 0,
  updatedAt: '2026-10-01T00:00:00Z',
};

export const quote: QuoteResponse = {
  roomId: 'room-1',
  guests: 2,
  available: true,
  unavailableReason: null,
  quote: {
    currency: 'INR',
    checkIn,
    checkOut,
    nights: 2,
    nightlyRates: [
      { date: checkIn, baseRate: 7200, dayMultiplier: 1, seasonName: null, seasonMultiplier: 1, occupancy: 0, demandMultiplier: 1, limitApplied: null, rate: 7200 },
      { date: plusDays(checkIn, 1), baseRate: 7200, dayMultiplier: 1.15, seasonName: 'Festive peak', seasonMultiplier: 1.3, occupancy: 0.5, demandMultiplier: 1.1, limitApplied: null, rate: 11840.4 },
    ],
    subtotal: 19040.4,
    serviceFee: 952.02,
    taxes: 2399.09,
    total: 22391.51,
    averageNightlyRate: 9520.2,
    serviceFeeRate: 0.05,
    taxRate: 0.12,
    pricingPolicyVersion: 1,
    quotedAt: '2026-10-21T10:00:00Z',
    validUntil: '2026-10-21T10:15:00Z',
  },
};

export function booking(status: Booking['status'], extra: Partial<Booking> = {}): Booking {
  return {
    id: 'b-1',
    reference: 'GV-7KQ2MX',
    status,
    statusReason: null,
    room: { id: 'room-1', roomNumber: '201', name: room.name, typeName: 'Deluxe Room', propertyId: 'p-hyd', propertyName: 'GrandVista Hyderabad' },
    checkIn,
    checkOut,
    nights: 2,
    guests: 2,
    primaryGuest: { fullName: 'Asha Rao', email: 'asha@example.test', phone: null },
    specialRequests: null,
    price: { nights: 2, subtotal: 19040.4, serviceFee: 952.02, taxes: 2399.09, total: 22391.51, currency: 'INR', pricingPolicyVersion: 1, quotedAt: '2026-10-21T10:00:00Z', nightlyRates: quote.quote.nightlyRates },
    paymentId: 'p-1',
    holdExpiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
    refundAmount: null,
    allowedActions: status === 'PENDING_PAYMENT' ? ['PAY', 'CANCEL'] : status === 'CONFIRMED' ? ['CANCEL'] : [],
    createdAt: '2026-10-21T10:00:00Z',
    confirmedAt: status === 'CONFIRMED' ? '2026-10-21T10:01:00Z' : null,
    cancelledAt: null,
    customerId: 'u-1',
    customerEmail: 'asha@example.test',
    ...extra,
  };
}

export function payment(status: Payment['status'], extra: Partial<Payment> = {}): Payment {
  return {
    id: 'p-1', bookingId: 'b-1', bookingReference: 'GV-7KQ2MX', customerId: 'u-1', amount: 22391.51, currency: 'INR', status,
    provider: 'SIMULATED', simulated: true, providerReference: null, failureCode: null, failureMessage: null, refundedAmount: 0,
    expiresAt: new Date(Date.now() + 600_000).toISOString(), createdAt: '2026-10-21T10:00:00Z', updatedAt: '2026-10-21T10:00:00Z', ...extra,
  };
}

export const reconciliationReport: ReconciliationReport = {
  id: 'run-1',
  propertyId: 'p-goa',
  propertyCode: 'GOA',
  propertyName: 'GrandVista Goa',
  trigger: 'MANUAL',
  status: 'COMPLETED',
  startedAt: '2026-10-23T10:00:00Z',
  finishedAt: '2026-10-23T10:00:01Z',
  actor: 'user:u-1',
  allocationsChecked: 4,
  bookingsChecked: 3,
  findingCount: 2,
  releasedCount: 1,
  countsByType: {
    ORPHAN_HOLD: 1,
    ALLOCATION_WITHOUT_BOOKING: 0,
    STATUS_MISMATCH: 0,
    CONFIRMED_BOOKING_WITHOUT_ALLOCATION: 1,
    PENDING_BOOKING_WITHOUT_HOLD: 0,
  },
  error: null,
  findings: [
    { type: 'CONFIRMED_BOOKING_WITHOUT_ALLOCATION', severity: 'CRITICAL', action: 'NONE', bookingId: 'b-9', bookingReference: 'GV-ABC123', allocationId: null, roomId: 'r-1', roomNumber: null, checkIn: '2026-11-10', checkOut: '2026-11-12', allocationStatus: null, bookingStatus: 'CONFIRMED', detail: 'confirmed booking has no allocation' },
    { type: 'ORPHAN_HOLD', severity: 'WARNING', action: 'RELEASE_EXPIRED_ORPHAN_HOLD', bookingId: 'b-8', bookingReference: null, allocationId: 'a-8', roomId: 'r-2', roomNumber: '202', checkIn: '2026-11-01', checkOut: '2026-11-03', allocationStatus: 'HELD', bookingStatus: null, detail: 'hold for a booking that does not exist' },
  ],
};
