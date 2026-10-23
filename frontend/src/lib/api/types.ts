// Types mirror the backend DTOs (see docs/api/API_REFERENCE.md). Money values arrive as JSON numbers
// with two decimals; the browser only ever displays them — the backend is authoritative for prices.

export type Role = 'CUSTOMER' | 'STAFF' | 'ADMIN';

export interface User {
  id: string;
  email: string;
  fullName: string;
  phone: string | null;
  roles: Role[];
  enabled: boolean;
  createdAt: string;
  lastLoginAt: string | null;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  user: User;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PropertySummary {
  id: string;
  code: string;
  name: string;
  city: string;
  country: string;
  timeZone: string;
}

export interface Property extends PropertySummary {
  active: boolean;
  totalRooms: number;
  activeRooms: number;
  createdAt: string;
  version: number;
}

export interface RoomType {
  id: string;
  propertyId: string;
  code: string;
  name: string;
  description: string | null;
  sortOrder: number;
}

export interface Amenity {
  id: string;
  code: string;
  name: string;
  icon: string | null;
  category: string;
}

export interface RoomImage {
  id: string;
  url: string;
  altText: string | null;
  contentType: string;
  sizeBytes: number;
}

export type RoomStatus = 'ACTIVE' | 'MAINTENANCE' | 'OUT_OF_SERVICE' | 'INACTIVE';

export interface StayPrice {
  nights: number;
  averageNightlyRate: number;
  total: number;
  currency: string;
  pricingPolicyVersion: number;
}

export interface Room {
  id: string;
  property: PropertySummary;
  roomNumber: string;
  name: string;
  description: string | null;
  roomType: RoomType;
  maxOccupancy: number;
  bedConfiguration: string;
  sizeSqM: number | null;
  floor: number | null;
  viewType: string | null;
  basePrice: number;
  currency: string;
  status: RoomStatus;
  statusReason: string | null;
  amenities: Amenity[];
  images: RoomImage[];
  stayPrice: StayPrice | null;
  version: number;
  updatedAt: string;
}

export interface NightlyRate {
  date: string;
  baseRate: number;
  dayMultiplier: number;
  seasonName: string | null;
  seasonMultiplier: number;
  occupancy: number;
  demandMultiplier: number;
  limitApplied: 'MIN' | 'MAX' | null;
  rate: number;
}

export interface PriceQuote {
  currency: string;
  checkIn: string;
  checkOut: string;
  nights: number;
  nightlyRates: NightlyRate[];
  subtotal: number;
  serviceFee: number;
  taxes: number;
  total: number;
  averageNightlyRate: number;
  serviceFeeRate: number;
  taxRate: number;
  pricingPolicyVersion: number;
  quotedAt: string;
  validUntil: string;
}

export interface QuoteResponse {
  roomId: string;
  guests: number;
  available: boolean;
  unavailableReason: string | null;
  quote: PriceQuote;
}

export interface CalendarResponse {
  roomId: string;
  from: string;
  to: string;
  unavailableNights: string[];
}

export type BookingStatus =
  | 'CREATED'
  | 'PENDING_PAYMENT'
  | 'CONFIRMED'
  | 'PAYMENT_FAILED'
  | 'EXPIRED'
  | 'REJECTED'
  | 'CANCELLATION_PENDING'
  | 'CANCELLED'
  | 'NEEDS_ATTENTION';

export interface Booking {
  id: string;
  reference: string;
  status: BookingStatus;
  statusReason: string | null;
  room: {
    id: string;
    roomNumber: string | null;
    name: string | null;
    typeName: string | null;
    propertyId: string | null;
    propertyName: string | null;
  };
  checkIn: string;
  checkOut: string;
  nights: number;
  guests: number;
  primaryGuest: { fullName: string; email: string; phone: string | null } | null;
  specialRequests: string | null;
  price: {
    nights: number;
    subtotal: number;
    serviceFee: number;
    taxes: number;
    total: number;
    currency: string;
    pricingPolicyVersion: number;
    quotedAt: string;
    nightlyRates: NightlyRate[];
  } | null;
  paymentId: string | null;
  holdExpiresAt: string | null;
  refundAmount: number | null;
  allowedActions: Array<'PAY' | 'CANCEL'>;
  createdAt: string;
  confirmedAt: string | null;
  cancelledAt: string | null;
  customerId: string;
  customerEmail: string;
}

export interface BookingStatusResponse {
  id: string;
  reference: string;
  status: BookingStatus;
  statusReason: string | null;
  paymentId: string | null;
  holdExpiresAt: string | null;
  updatedAt: string;
}

export interface BookingHistoryEntry {
  fromStatus: BookingStatus | null;
  toStatus: BookingStatus;
  reason: string | null;
  actor: string;
  occurredAt: string;
}

export interface CancellationQuote {
  bookingId: string;
  allowed: boolean;
  refundPercent: number;
  refundAmount: number;
  currency: string | null;
  daysBeforeCheckIn: number;
  rule: string;
}

export type PaymentStatus =
  | 'CREATED'
  | 'PENDING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'REFUND_PENDING'
  | 'PARTIALLY_REFUNDED'
  | 'REFUNDED';

export interface Payment {
  id: string;
  bookingId: string;
  bookingReference: string;
  customerId: string;
  amount: number;
  currency: string;
  status: PaymentStatus;
  provider: string;
  simulated: boolean;
  providerReference: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  refundedAmount: number;
  expiresAt: string;
  createdAt: string;
  updatedAt: string;
}

/** What the browser needs to pay (POST /payments/{id}/checkout). */
export interface CheckoutSession {
  paymentId: string;
  provider: 'SIMULATED' | 'RAZORPAY' | string;
  testMode: boolean;
  keyId: string | null;
  orderId: string | null;
  amount: number;
  amountMinorUnits: number | null;
  currency: string;
  merchantName: string;
  description: string;
  bookingReference: string;
  prefillEmail: string | null;
  expiresAt: string;
}

/** Result returned by Razorpay Checkout's success handler. */
export interface RazorpayCheckoutResult {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
}

export interface TestPaymentMethod {
  token: string;
  label: string;
  expectedOutcome: string;
}

// ---- admin ----

export interface InventorySummary {
  totalRooms: number;
  activeRooms: number;
  maintenanceRooms: number;
  outOfServiceRooms: number;
  inactiveRooms: number;
  occupancyToday: number;
  arrivalsToday: number;
  departuresToday: number;
}

export interface OccupancyPoint {
  date: string;
  allocatedRooms: number;
  sellableRooms: number;
  occupancy: number;
}

export interface BookingSummary {
  byStatus: Array<{ status: BookingStatus; count: number; amount: number }>;
  confirmedRevenue: number;
  arrivalsToday: number;
  needsAttention: number;
  last30Days: Array<{ day: string; created: number; confirmed: number }>;
}

export interface PaymentSummary {
  byStatus: Array<{ status: PaymentStatus; count: number; amount: number; refunded: number }>;
  capturedAmount: number;
  refundedAmount: number;
  outboxPending: number;
  outboxDead: number;
}

export interface PricingPolicy {
  id: string;
  propertyId: string;
  version: number;
  status: 'ACTIVE' | 'ARCHIVED';
  weekendDays: string[];
  weekendMultiplier: number;
  minPriceFactor: number;
  maxPriceFactor: number;
  serviceFeeRate: number;
  taxRate: number;
  seasons: Array<{ name: string; startDate: string; endDate: string; multiplier: number }>;
  demandTiers: Array<{ minOccupancy: number; multiplier: number }>;
  notes: string | null;
  createdBy: string;
  createdAt: string;
}

export interface StatusChangeResponse {
  room: Room;
  futureConfirmedStays: number;
  warning: string | null;
}

export type FindingType =
  | 'ORPHAN_HOLD'
  | 'ALLOCATION_WITHOUT_BOOKING'
  | 'STATUS_MISMATCH'
  | 'CONFIRMED_BOOKING_WITHOUT_ALLOCATION'
  | 'PENDING_BOOKING_WITHOUT_HOLD';

export interface ReconciliationFinding {
  type: FindingType;
  severity: 'WARNING' | 'CRITICAL';
  action: 'NONE' | 'RELEASE_EXPIRED_ORPHAN_HOLD';
  bookingId: string | null;
  bookingReference: string | null;
  allocationId: string | null;
  roomId: string | null;
  roomNumber: string | null;
  checkIn: string | null;
  checkOut: string | null;
  allocationStatus: string | null;
  bookingStatus: string | null;
  detail: string;
}

export interface ReconciliationReport {
  id: string;
  propertyId: string;
  propertyCode: string;
  propertyName: string;
  trigger: 'SCHEDULED' | 'MANUAL';
  status: 'COMPLETED' | 'FAILED';
  startedAt: string;
  finishedAt: string;
  actor: string;
  allocationsChecked: number;
  bookingsChecked: number;
  findingCount: number;
  releasedCount: number;
  countsByType: Record<FindingType, number>;
  error: string | null;
  findings: ReconciliationFinding[];
}
