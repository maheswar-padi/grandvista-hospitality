import { differenceInCalendarDays, format, parseISO } from 'date-fns';
import type { BookingStatus, PaymentStatus } from './api/types';

const currencyFormatters = new Map<string, Intl.NumberFormat>();

/** Formats an amount the backend calculated. No arithmetic on money happens in the browser. */
export function formatMoney(amount: number | null | undefined, currency = 'INR', fractionDigits = 2) {
  if (amount === null || amount === undefined) return '—';
  const key = `${currency}:${fractionDigits}`;
  let formatter = currencyFormatters.get(key);
  if (!formatter) {
    formatter = new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency,
      minimumFractionDigits: fractionDigits,
      maximumFractionDigits: fractionDigits,
    });
    currencyFormatters.set(key, formatter);
  }
  return formatter.format(amount);
}

/** ISO calendar date (yyyy-MM-dd) → "Thu, 3 Dec 2026". Parsed as a local date, never shifted by time zone. */
export function formatDate(isoDate: string | null | undefined, pattern = 'EEE, d MMM yyyy') {
  if (!isoDate) return '—';
  return format(parseISO(isoDate), pattern);
}

export function formatDateTime(isoInstant: string | null | undefined) {
  if (!isoInstant) return '—';
  return format(new Date(isoInstant), 'd MMM yyyy, HH:mm');
}

export function nightsBetween(checkIn: string, checkOut: string) {
  return differenceInCalendarDays(parseISO(checkOut), parseISO(checkIn));
}

export function formatPercent(ratio: number) {
  return `${Math.round(ratio * 100)}%`;
}

export const bookingStatusLabel: Record<BookingStatus, string> = {
  CREATED: 'Processing',
  PENDING_PAYMENT: 'Awaiting payment',
  CONFIRMED: 'Confirmed',
  PAYMENT_FAILED: 'Payment failed',
  EXPIRED: 'Expired',
  REJECTED: 'Not completed',
  CANCELLATION_PENDING: 'Cancelling',
  CANCELLED: 'Cancelled',
  NEEDS_ATTENTION: 'Under review',
};

export const paymentStatusLabel: Record<PaymentStatus, string> = {
  CREATED: 'Not paid yet',
  PENDING: 'Processing',
  SUCCEEDED: 'Paid',
  FAILED: 'Failed',
  CANCELLED: 'Voided',
  REFUND_PENDING: 'Refund in progress',
  PARTIALLY_REFUNDED: 'Partially refunded',
  REFUNDED: 'Refunded',
};

export function humanize(code: string | null | undefined) {
  if (!code) return '';
  return code.toLowerCase().replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase());
}
