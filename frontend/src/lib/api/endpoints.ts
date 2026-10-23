import { request, type QueryValue } from './http';
import type {
  Amenity,
  AuthResponse,
  Booking,
  BookingHistoryEntry,
  BookingStatus,
  BookingStatusResponse,
  BookingSummary,
  CalendarResponse,
  CancellationQuote,
  InventorySummary,
  OccupancyPoint,
  Page,
  CheckoutSession,
  Payment,
  PaymentStatus,
  RazorpayCheckoutResult,
  PaymentSummary,
  PricingPolicy,
  Property,
  ReconciliationReport,
  QuoteResponse,
  Role,
  Room,
  RoomImage,
  RoomStatus,
  RoomType,
  StatusChangeResponse,
  TestPaymentMethod,
  User,
} from './types';

// ---------------- auth ----------------
export const authApi = {
  register: (body: { email: string; password: string; fullName: string; phone?: string }) =>
    request<AuthResponse>('/auth/register', { method: 'POST', body, retryOnUnauthorized: false }),
  login: (body: { email: string; password: string }) =>
    request<AuthResponse>('/auth/login', { method: 'POST', body, retryOnUnauthorized: false }),
  refresh: () => request<AuthResponse>('/auth/refresh', { method: 'POST', retryOnUnauthorized: false }),
  logout: () => request<void>('/auth/logout', { method: 'POST', retryOnUnauthorized: false }),
  me: () => request<User>('/auth/me'),
};

// ---------------- rooms ----------------
export interface RoomSearchParams {
  propertyId?: string;
  city?: string;
  checkIn?: string;
  checkOut?: string;
  guests?: number;
  roomType?: string;
  minPrice?: number;
  maxPrice?: number;
  amenities?: string[];
  sort?: 'RECOMMENDED' | 'PRICE_ASC' | 'PRICE_DESC' | 'CAPACITY_DESC' | 'NAME_ASC';
  page?: number;
  size?: number;
}

export const roomsApi = {
  search: (params: RoomSearchParams, signal?: AbortSignal) =>
    request<Page<Room>>('/rooms', { query: params as Record<string, QueryValue>, signal }),
  get: (id: string) => request<Room>(`/rooms/${id}`),
  quote: (id: string, checkIn: string, checkOut: string, guests: number) =>
    request<QuoteResponse>(`/rooms/${id}/quote`, { query: { checkIn, checkOut, guests } }),
  calendar: (id: string, from: string, days = 90) =>
    request<CalendarResponse>(`/rooms/${id}/calendar`, { query: { from, days } }),
  roomTypes: (propertyId?: string) => request<RoomType[]>('/room-types', { query: { propertyId } }),
  amenities: () => request<Amenity[]>('/amenities'),
};

// ---------------- properties (hotels) ----------------
export const propertiesApi = {
  list: () => request<Property[]>('/properties'),
  get: (id: string) => request<Property>(`/properties/${id}`),
};

// ---------------- bookings ----------------
export interface CreateBookingBody {
  roomId: string;
  checkIn: string;
  checkOut: string;
  guests: number;
  primaryGuest: { fullName: string; email: string; phone?: string };
  specialRequests?: string;
  expectedTotal?: number;
}

export const bookingsApi = {
  create: (body: CreateBookingBody, idempotencyKey: string) =>
    request<Booking>('/bookings', { method: 'POST', body, idempotencyKey }),
  get: (id: string) => request<Booking>(`/bookings/${id}`),
  status: (id: string) => request<BookingStatusResponse>(`/bookings/${id}/status`),
  history: (id: string) => request<BookingHistoryEntry[]>(`/bookings/${id}/history`),
  mine: (scope: 'UPCOMING' | 'PAST' | 'ALL', page = 0, size = 20) =>
    request<Page<Booking>>('/bookings/my', { query: { scope, page, size } }),
  cancellationQuote: (id: string) => request<CancellationQuote>(`/bookings/${id}/cancellation-quote`),
  cancel: (id: string, reason?: string) =>
    request<Booking>(`/bookings/${id}/cancel`, { method: 'POST', body: { reason } }),
};

// ---------------- payments ----------------
export const paymentsApi = {
  testMethods: () => request<TestPaymentMethod[]>('/payments/test-methods'),
  get: (id: string) =>
    request<{ payment: Payment; refunds: unknown[]; events: unknown[] }>(`/payments/${id}`),
  byBooking: (bookingId: string) => request<Payment>(`/payments/by-booking/${bookingId}`),
  confirm: (paymentId: string, paymentMethodToken: string, idempotencyKey: string) =>
    request<Payment>(`/payments/${paymentId}/confirm`, { method: 'POST', body: { paymentMethodToken }, idempotencyKey }),
  mine: (page = 0) => request<Page<Payment>>('/payments/my', { query: { page, size: 50 } }),
  checkout: (paymentId: string) => request<CheckoutSession>(`/payments/${paymentId}/checkout`, { method: 'POST' }),
  confirmRazorpay: (paymentId: string, result: RazorpayCheckoutResult, idempotencyKey: string) =>
    request<Payment>(`/payments/${paymentId}/confirm`, {
      method: 'POST',
      body: {
        razorpayPaymentId: result.razorpay_payment_id,
        razorpayOrderId: result.razorpay_order_id,
        razorpaySignature: result.razorpay_signature,
      },
      idempotencyKey,
    }),
};

// ---------------- administration ----------------
export interface RoomBody {
  roomNumber: string;
  roomTypeId: string;
  name: string;
  description?: string;
  maxOccupancy: number;
  bedConfiguration: string;
  sizeSqM?: number;
  floor?: number;
  viewType?: string;
  basePrice: number;
  currency: string;
  amenityCodes: string[];
  version?: number;
}

export const adminApi = {
  // `propertyId` undefined = the whole portfolio where the backend supports it, otherwise the default hotel
  properties: () => request<Property[]>('/admin/properties'),
  inventorySummary: (propertyId?: string) => request<InventorySummary>('/admin/inventory/summary', { query: { propertyId } }),
  occupancy: (from: string, to: string, propertyId?: string) =>
    request<OccupancyPoint[]>('/admin/inventory/occupancy', { query: { from, to, propertyId } }),
  reconciliation: (propertyId?: string) =>
    request<ReconciliationReport>('/admin/inventory/reconciliation', { query: { propertyId } }),
  reconciliationRuns: (propertyId?: string, limit = 10) =>
    request<ReconciliationReport[]>('/admin/inventory/reconciliation/runs', { query: { propertyId, limit } }),
  runReconciliation: (propertyId?: string) =>
    request<ReconciliationReport>('/admin/inventory/reconciliation/run', { method: 'POST', query: { propertyId } }),
  bookingSummary: () => request<BookingSummary>('/admin/bookings/summary'),
  paymentSummary: () => request<PaymentSummary>('/admin/payments/summary'),

  rooms: (params: { propertyId?: string; status?: RoomStatus; q?: string; page?: number; size?: number }) =>
    request<Page<Room>>('/admin/rooms', { query: params }),
  room: (id: string) => request<Room>(`/admin/rooms/${id}`),
  createRoom: (body: RoomBody) => request<Room>('/admin/rooms', { method: 'POST', body }),
  updateRoom: (id: string, body: RoomBody) => request<Room>(`/admin/rooms/${id}`, { method: 'PUT', body }),
  changeStatus: (id: string, status: RoomStatus, reason?: string) =>
    request<StatusChangeResponse>(`/admin/rooms/${id}/status`, { method: 'PUT', body: { status, reason } }),
  uploadImage: (id: string, file: File, altText?: string) => {
    const form = new FormData();
    form.append('file', file);
    return request<RoomImage>(`/admin/rooms/${id}/images`, { method: 'POST', body: form, query: { altText } });
  },
  deleteImage: (roomId: string, imageId: string) =>
    request<void>(`/admin/rooms/${roomId}/images/${imageId}`, { method: 'DELETE' }),

  activePolicy: (propertyId?: string) => request<PricingPolicy>('/admin/pricing/policy', { query: { propertyId } }),
  policies: (propertyId?: string) => request<PricingPolicy[]>('/admin/pricing/policies', { query: { propertyId } }),
  publishPolicy: (body: Omit<PricingPolicy, 'id' | 'propertyId' | 'version' | 'status' | 'createdBy' | 'createdAt'>, propertyId?: string) =>
    request<PricingPolicy>('/admin/pricing/policies', { method: 'POST', body, query: { propertyId } }),

  bookings: (params: { propertyId?: string; status?: BookingStatus; from?: string; to?: string; q?: string; page?: number; size?: number }) =>
    request<Page<Booking>>('/admin/bookings', { query: params }),
  retryCompensation: (id: string) => request<Booking>(`/admin/bookings/${id}/retry-compensation`, { method: 'POST' }),

  payments: (params: { status?: PaymentStatus; bookingId?: string; page?: number; size?: number }) =>
    request<Page<Payment>>('/admin/payments', { query: params }),

  users: (params: { q?: string; page?: number; size?: number }) => request<Page<User>>('/admin/users', { query: params }),
  updateRoles: (id: string, roles: Role[]) => request<User>(`/admin/users/${id}/roles`, { method: 'PUT', body: { roles } }),
  updateUserStatus: (id: string, enabled: boolean) =>
    request<User>(`/admin/users/${id}/status`, { method: 'PUT', body: { enabled } }),
};
