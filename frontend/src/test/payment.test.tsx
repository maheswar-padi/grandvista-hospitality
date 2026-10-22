import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import PaymentPage from '../pages/PaymentPage';
import { booking, payment } from './fixtures';
import { customerSession, mockApi, renderRoutes } from './utils';

const routes = [
  { path: '/bookings/:bookingId/pay', element: <PaymentPage /> },
  { path: '/bookings/:bookingId/confirmation', element: <p>confirmation page</p> },
];

const session = (provider: 'SIMULATED' | 'RAZORPAY') => ({
  paymentId: 'p-1', provider, testMode: true,
  keyId: provider === 'RAZORPAY' ? 'rzp_test_GVFAKE00001' : null,
  orderId: provider === 'RAZORPAY' ? 'order_GVTEST0001' : null,
  amount: 22391.51, amountMinorUnits: provider === 'RAZORPAY' ? 2239151 : null, currency: 'INR',
  merchantName: 'GrandVista Hospitality', description: 'Booking GV-7KQ2MX', bookingReference: 'GV-7KQ2MX',
  prefillEmail: provider === 'RAZORPAY' ? 'asha@example.test' : null, expiresAt: new Date(Date.now() + 600_000).toISOString(),
});

afterEach(() => {
  delete window.Razorpay;
});

describe('payment page', () => {
  it('simulated mode offers the test methods and confirms with the chosen token', async () => {
    const { calls } = mockApi({
      ...customerSession(),
      'GET /api/v1/bookings/b-1': () => ({ body: booking('PENDING_PAYMENT') }),
      'POST /api/v1/payments/p-1/checkout': () => ({ body: session('SIMULATED') }),
      'GET /api/v1/payments/test-methods': () => ({ body: [
        { token: 'sim_card_success', label: 'Simulated Visa – approved', expectedOutcome: 'SUCCEEDED' },
        { token: 'sim_card_declined', label: 'Simulated card – declined', expectedOutcome: 'FAILED' },
      ] }),
      'POST /api/v1/payments/p-1/confirm': () => ({ body: payment('SUCCEEDED') }),
    });
    renderRoutes(routes, '/bookings/b-1/pay');
    expect(await screen.findByText('Simulated payment — no real money is charged')).toBeInTheDocument();
    await userEvent.click(await screen.findByLabelText(/Simulated card – declined/));
    await userEvent.click(screen.getByRole('button', { name: /\(simulated\)/ }));
    expect(await screen.findByText('confirmation page')).toBeInTheDocument();
    const confirm = calls.find((c) => c.path === '/api/v1/payments/p-1/confirm')!;
    expect(JSON.parse(String(confirm.init.body))).toEqual({ paymentMethodToken: 'sim_card_declined' });
    expect(new Headers(confirm.init.headers).get('Idempotency-Key')).toBeTruthy();
  });

  it('Razorpay mode opens Checkout for the server order and sends the signed result to the server', async () => {
    let options: Record<string, unknown> = {};
    window.Razorpay = vi.fn().mockImplementation((o: Record<string, unknown>) => {
      options = o;
      return {
        on: vi.fn(),
        open: () => (o.handler as (r: unknown) => void)({
          razorpay_payment_id: 'pay_GVOK0000001', razorpay_order_id: 'order_GVTEST0001', razorpay_signature: 'a'.repeat(64),
        }),
      };
    }) as unknown as typeof window.Razorpay;
    const { calls } = mockApi({
      ...customerSession(),
      'GET /api/v1/bookings/b-1': () => ({ body: booking('PENDING_PAYMENT') }),
      'POST /api/v1/payments/p-1/checkout': () => ({ body: session('RAZORPAY') }),
      'POST /api/v1/payments/p-1/confirm': () => ({ body: payment('SUCCEEDED', { provider: 'RAZORPAY', simulated: false }) }),
    });
    renderRoutes(routes, '/bookings/b-1/pay');
    expect(await screen.findByText('Razorpay test mode — no real money is charged')).toBeInTheDocument();
    expect(screen.queryByText('Test payment method')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /with Razorpay \(test mode\)/ }));
    expect(await screen.findByText('confirmation page')).toBeInTheDocument();
    expect(options).toMatchObject({ key: 'rzp_test_GVFAKE00001', order_id: 'order_GVTEST0001', amount: 2239151, currency: 'INR' });
    const confirm = calls.find((c) => c.path === '/api/v1/payments/p-1/confirm')!;
    expect(JSON.parse(String(confirm.init.body))).toEqual({
      razorpayPaymentId: 'pay_GVOK0000001', razorpayOrderId: 'order_GVTEST0001', razorpaySignature: 'a'.repeat(64),
    });
    expect(calls.some((c) => c.path === '/api/v1/payments/test-methods')).toBe(false);
  });

  it('closing the Razorpay window explains that nothing was charged and allows a retry', async () => {
    window.Razorpay = vi.fn().mockImplementation((o: { modal: { ondismiss: () => void } }) => ({
      on: vi.fn(),
      open: () => o.modal.ondismiss(),
    })) as unknown as typeof window.Razorpay;
    const { calls } = mockApi({
      ...customerSession(),
      'GET /api/v1/bookings/b-1': () => ({ body: booking('PENDING_PAYMENT') }),
      'POST /api/v1/payments/p-1/checkout': () => ({ body: session('RAZORPAY') }),
    });
    renderRoutes(routes, '/bookings/b-1/pay');
    await userEvent.click(await screen.findByRole('button', { name: /with Razorpay/ }));
    expect(await screen.findByText(/payment window was closed/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /with Razorpay/ })).toBeEnabled());
    expect(calls.some((c) => c.path === '/api/v1/payments/p-1/confirm')).toBe(false);
  });
});
