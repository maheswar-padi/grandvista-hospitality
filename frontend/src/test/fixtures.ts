import type { Booking, Payment, QuoteResponse, Room } from '../lib/api/types';
import { hotelToday, plusDays } from '../lib/dates';

export const checkIn = plusDays(hotelToday(), 20);
export const checkOut = plusDays(checkIn, 2);

export const room: Room = {
  id: 'room-1',
  roomNumber: '201',
  name: 'Deluxe King, City View',
  description: 'Floor-to-ceiling windows over the skyline.',
  roomType: { id: 't-1', code: 'DELUXE', name: 'Deluxe Room', description: null, sortOrder: 2 },
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
    room: { id: 'room-1', roomNumber: '201', name: room.name, typeName: 'Deluxe Room' },
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
