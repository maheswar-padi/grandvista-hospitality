import type { BookingStatus, PaymentStatus } from '../../lib/api/types';
import { bookingStatusLabel, paymentStatusLabel } from '../../lib/format';
import { Badge, type BadgeTone } from '../ui/Badge';

const bookingTones: Record<BookingStatus, BadgeTone> = {
  CREATED: 'info',
  PENDING_PAYMENT: 'warning',
  CONFIRMED: 'success',
  PAYMENT_FAILED: 'danger',
  EXPIRED: 'neutral',
  REJECTED: 'neutral',
  CANCELLATION_PENDING: 'warning',
  CANCELLED: 'neutral',
  NEEDS_ATTENTION: 'danger',
};

const paymentTones: Record<PaymentStatus, BadgeTone> = {
  CREATED: 'neutral',
  PENDING: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  CANCELLED: 'neutral',
  REFUND_PENDING: 'warning',
  PARTIALLY_REFUNDED: 'info',
  REFUNDED: 'info',
};

export function BookingStatusBadge({ status }: { status: BookingStatus }) {
  return <Badge tone={bookingTones[status]}>{bookingStatusLabel[status]}</Badge>;
}

export function PaymentStatusBadge({ status }: { status: PaymentStatus }) {
  return <Badge tone={paymentTones[status]}>{paymentStatusLabel[status]}</Badge>;
}
