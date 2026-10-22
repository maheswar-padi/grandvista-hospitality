import { describe, expect, it } from 'vitest';
import { ApiError, buildUrl, request, setAccessToken, setRefreshHandler } from './api/http';
import { hotelToday, plusDays } from './dates';
import { formatMoney, nightsBetween } from './format';
import { safeRedirect } from './safeRedirect';
import { guestSchema, registerSchema, staySchema } from './schemas';
import { mockApi } from '../test/utils';

describe('safeRedirect', () => {
  it('accepts same-site relative paths only', () => {
    expect(safeRedirect('/checkout?roomId=1')).toBe('/checkout?roomId=1');
    expect(safeRedirect('https://evil.example')).toBe('/');
    expect(safeRedirect('//evil.example/path')).toBe('/');
    expect(safeRedirect('/\\evil.example')).toBe('/');
    expect(safeRedirect('javascript:alert(1)')).toBe('/');
    expect(safeRedirect(null, '/account')).toBe('/account');
  });
});

describe('formatting and dates', () => {
  it('formats rupees with Indian grouping', () => {
    expect(formatMoney(10584)).toBe('₹10,584.00');
    expect(formatMoney(1234567.5, 'INR', 0)).toBe('₹12,34,568');
    expect(formatMoney(null)).toBe('—');
  });
  it('computes hotel-local today and nights with the half-open convention', () => {
    // 2026-03-01T20:00Z is already 2 March in India (UTC+05:30)
    expect(hotelToday(new Date('2026-03-01T20:00:00Z'))).toBe('2026-03-02');
    expect(plusDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(nightsBetween('2026-12-03', '2026-12-05')).toBe(2);
  });
});

describe('validation schemas', () => {
  it('rejects past check-in and check-out not after check-in', () => {
    const today = hotelToday();
    expect(staySchema.safeParse({ checkIn: plusDays(today, 2), checkOut: plusDays(today, 4), guests: 2 }).success).toBe(true);
    const sameDay = staySchema.safeParse({ checkIn: plusDays(today, 2), checkOut: plusDays(today, 2), guests: 2 });
    expect(sameDay.success).toBe(false);
    const past = staySchema.safeParse({ checkIn: plusDays(today, -1), checkOut: plusDays(today, 1), guests: 2 });
    expect(past.success).toBe(false);
    expect(staySchema.safeParse({ checkIn: plusDays(today, 2), checkOut: plusDays(today, 3), guests: 13 }).success).toBe(false);
  });
  it('enforces the password policy and matching confirmation', () => {
    const base = { fullName: 'Asha Rao', email: 'asha@example.test', phone: '' };
    expect(registerSchema.safeParse({ ...base, password: 'correct-horse-42', confirmPassword: 'correct-horse-42' }).success).toBe(true);
    expect(registerSchema.safeParse({ ...base, password: 'short1', confirmPassword: 'short1' }).success).toBe(false);
    expect(registerSchema.safeParse({ ...base, password: 'nodigitsatall', confirmPassword: 'nodigitsatall' }).success).toBe(false);
    expect(registerSchema.safeParse({ ...base, password: 'correct-horse-42', confirmPassword: 'other-horse-42' }).success).toBe(false);
    expect(guestSchema.safeParse({ fullName: 'A', email: 'nope' }).success).toBe(false);
  });
});

describe('http client', () => {
  it('builds query strings without empty values and repeats arrays', () => {
    expect(buildUrl('/rooms', { guests: 2, roomType: undefined, amenities: ['WIFI', 'SEA_VIEW'], q: '' })).toBe(
      '/api/v1/rooms?guests=2&amenities=WIFI&amenities=SEA_VIEW',
    );
  });

  it('turns problem documents into ApiError with code, field errors and correlation id', async () => {
    mockApi({
      'POST /api/v1/bookings': () => ({
        status: 400,
        body: { status: 400, code: 'VALIDATION_FAILED', detail: 'One or more fields are invalid.', correlationId: 'cid-12345678', errors: [{ field: 'guests', message: 'must be at least 1' }] },
      }),
    });
    const error = await request('/bookings', { method: 'POST', body: {} }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('VALIDATION_FAILED');
    expect((error as ApiError).fieldErrors[0].field).toBe('guests');
    expect((error as ApiError).correlationId).toBe('cid-12345678');
  });

  it('sends bearer token, CSRF header and idempotency key; refreshes once on 401', async () => {
    let first = true;
    const { calls } = mockApi({
      'GET /api/v1/bookings/my': () => {
        if (first) {
          first = false;
          return { status: 401, body: { status: 401, code: 'AUTHENTICATION_REQUIRED' } };
        }
        return { body: { content: [] } };
      },
    });
    setAccessToken('stale');
    setRefreshHandler(async () => {
      setAccessToken('fresh');
      return 'fresh';
    });
    await request('/bookings/my', { idempotencyKey: 'key-123456789' });
    const headers = calls[1].init.headers as Record<string, string>;
    expect(calls).toHaveLength(2);
    expect(headers.Authorization).toBe('Bearer fresh');
    expect(headers['X-Requested-With']).toBe('XMLHttpRequest');
    expect(headers['Idempotency-Key']).toBe('key-123456789');
    setAccessToken(null);
    setRefreshHandler(null);
  });

  it('reports network failures as a friendly ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    const error = await request('/rooms').catch((e: unknown) => e);
    expect((error as ApiError).code).toBe('NETWORK_ERROR');
  });
});
