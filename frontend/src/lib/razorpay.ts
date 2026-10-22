import type { CheckoutSession, RazorpayCheckoutResult } from './api/types';

/** Razorpay Checkout's documented script; loaded only when the platform runs with the Razorpay provider. */
export const RAZORPAY_CHECKOUT_SRC = 'https://checkout.razorpay.com/v1/checkout.js';

interface RazorpayInstance {
  open(): void;
  on(event: 'payment.failed', handler: (response: { error?: { description?: string; reason?: string } }) => void): void;
}

type RazorpayConstructor = new (options: Record<string, unknown>) => RazorpayInstance;

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor;
  }
}

let loading: Promise<RazorpayConstructor> | null = null;

export function loadRazorpay(): Promise<RazorpayConstructor> {
  if (window.Razorpay) return Promise.resolve(window.Razorpay);
  if (loading) return loading;
  loading = new Promise<RazorpayConstructor>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = RAZORPAY_CHECKOUT_SRC;
    script.async = true;
    script.onload = () => (window.Razorpay ? resolve(window.Razorpay) : reject(new Error('Razorpay Checkout did not load.')));
    script.onerror = () => {
      loading = null;
      reject(new Error('Razorpay Checkout could not be loaded. Check your connection and try again.'));
    };
    document.head.appendChild(script);
  });
  return loading;
}

export class CheckoutDismissedError extends Error {
  constructor() {
    super('The payment window was closed before the payment was completed.');
  }
}

/**
 * Opens Razorpay Checkout for the server-created order and resolves with Razorpay's signed result.
 * The signature is verified by the payment service, which also asks Razorpay for the real payment state.
 */
export async function payWithRazorpay(session: CheckoutSession, onAttemptFailed: (message: string) => void) {
  const Razorpay = await loadRazorpay();
  const secondsLeft = Math.floor((new Date(session.expiresAt).getTime() - Date.now()) / 1000);
  return new Promise<RazorpayCheckoutResult>((resolve, reject) => {
    const checkout = new Razorpay({
      key: session.keyId,
      order_id: session.orderId,
      amount: session.amountMinorUnits,
      currency: session.currency,
      name: session.merchantName,
      description: session.description,
      prefill: session.prefillEmail ? { email: session.prefillEmail } : undefined,
      notes: { booking: session.bookingReference },
      theme: { color: '#1f3a34' },
      // never let the checkout outlive the inventory hold
      timeout: Math.max(60, secondsLeft),
      handler: (result: RazorpayCheckoutResult) => resolve(result),
      modal: { ondismiss: () => reject(new CheckoutDismissedError()), escape: true },
    });
    checkout.on('payment.failed', (response) =>
      onAttemptFailed(response.error?.description ?? 'The payment was not completed. You can try again.'),
    );
    checkout.open();
  });
}
