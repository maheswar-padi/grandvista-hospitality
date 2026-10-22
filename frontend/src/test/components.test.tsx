import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { RequireAuth } from '../components/RequireAuth';
import { PriceBreakdown } from '../components/rooms/PriceBreakdown';
import { RoomCard } from '../components/rooms/RoomCard';
import { SearchBar } from '../components/rooms/SearchBar';
import { hotelToday, plusDays } from '../lib/dates';
import CheckoutPage from '../pages/CheckoutPage';
import ConfirmationPage from '../pages/ConfirmationPage';
import LoginPage from '../pages/LoginPage';
import { booking, checkIn, checkOut, payment, quote, room } from './fixtures';
import { anonymousSession, customerSession, mockApi, renderPlain, renderRoutes, seriousA11yViolations } from './utils';

describe('SearchBar', () => {
  it('blocks a check-out that is not after check-in and submits valid stays', async () => {
    const onSearch = vi.fn();
    const day = plusDays(hotelToday(), 5);
    renderPlain(<SearchBar defaults={{ checkIn: day, checkOut: day, guests: 2 }} onSearch={onSearch} />);
    await userEvent.click(screen.getByRole('button', { name: /search availability/i }));
    expect(await screen.findByText('Check-out must be after check-in')).toBeInTheDocument();
    expect(onSearch).not.toHaveBeenCalled();

    const checkOutInput = screen.getByLabelText(/check-out/i);
    await userEvent.clear(checkOutInput);
    await userEvent.type(checkOutInput, plusDays(day, 2));
    await userEvent.click(screen.getByRole('button', { name: /search availability/i }));
    await waitFor(() => expect(onSearch).toHaveBeenCalledWith({ checkIn: day, checkOut: plusDays(day, 2), guests: 2 }));
  });
});

describe('PriceBreakdown and RoomCard', () => {
  it('shows each night with the reason for its rate and the server total', async () => {
    const { container } = renderPlain(<PriceBreakdown {...quote.quote} />);
    expect(screen.getByText(/weekend ×1.15 · Festive peak ×1.3 · demand ×1.1/)).toBeInTheDocument();
    expect(screen.getByText('₹22,391.51')).toBeInTheDocument();
    expect(screen.getByText(/Service fee \(5%\)/)).toBeInTheDocument();
    expect(await seriousA11yViolations(container)).toEqual([]);
  });

  it('labels illustrative artwork honestly and shows the stay price', async () => {
    const { container } = renderPlain(<RoomCard room={room} search="checkIn=x" />);
    // the card image duplicates the title link, so it is hidden from assistive tech; its label is still honest
    expect(screen.getByRole('img', { name: /artwork, not a photograph/i, hidden: true })).toBeInTheDocument();
    expect(screen.getByText('₹16,934')).toBeInTheDocument();
    expect(await seriousA11yViolations(container)).toEqual([]);
  });
});

describe('authentication flows', () => {
  it('redirects anonymous visitors from protected pages to sign-in, keeping the destination', async () => {
    mockApi(anonymousSession);
    const { router } = renderRoutes(
      [
        { path: '/account', element: <RequireAuth><p>secret</p></RequireAuth> },
        { path: '/login', element: <p>login page</p> },
      ],
      '/account',
    );
    expect(await screen.findByText('login page')).toBeInTheDocument();
    expect(router.state.location.search).toBe('?next=%2Faccount');
    expect(screen.queryByText('secret')).not.toBeInTheDocument();
  });

  it('shows the server’s generic credential error and never reveals which field was wrong', async () => {
    mockApi({
      ...anonymousSession,
      'POST /api/v1/auth/login': () => ({ status: 401, body: { status: 401, code: 'INVALID_CREDENTIALS', detail: 'Invalid e-mail address or password.' } }),
    });
    const { container } = renderRoutes([{ path: '/login', element: <LoginPage /> }], '/login');
    await userEvent.type(await screen.findByLabelText('E-mail address'), 'asha@example.test');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password-1');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Invalid e-mail address or password.')).toBeInTheDocument();
    expect(await seriousA11yViolations(container)).toEqual([]);
  });

  it('signs in and follows a safe next destination only', async () => {
    mockApi({
      ...anonymousSession,
      'POST /api/v1/auth/login': () => customerSession()['POST /api/v1/auth/refresh'](new URL('http://x'), {}),
    });
    const { router } = renderRoutes(
      [{ path: '/login', element: <LoginPage /> }, { path: '/account', element: <p>account</p> }, { path: '/', element: <p>home</p> }],
      '/login?next=https%3A%2F%2Fevil.example',
    );
    await userEvent.type(await screen.findByLabelText('E-mail address'), 'asha@example.test');
    await userEvent.type(screen.getByLabelText('Password'), 'correct-horse-42');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });
});

describe('checkout', () => {
  it('reserves with an idempotency key and the total the customer saw', async () => {
    const { calls } = mockApi({
      ...customerSession(),
      'GET /api/v1/rooms/room-1': () => ({ body: room }),
      'GET /api/v1/rooms/room-1/quote': () => ({ body: quote }),
      'POST /api/v1/bookings': () => ({ status: 201, body: booking('PENDING_PAYMENT') }),
    });
    const { router } = renderRoutes(
      [{ path: '/checkout', element: <RequireAuth><CheckoutPage /></RequireAuth> }, { path: '/bookings/:id/pay', element: <p>pay page</p> }],
      `/checkout?roomId=room-1&checkIn=${checkIn}&checkOut=${checkOut}&guests=2`,
    );
    await userEvent.click(await screen.findByRole('button', { name: /reserve/i }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/bookings/b-1/pay'));
    const post = calls.find((c) => c.method === 'POST' && c.path === '/api/v1/bookings')!;
    const body = JSON.parse(String(post.init.body));
    expect(body.expectedTotal).toBe(22391.51);
    expect(body.primaryGuest.fullName).toBe('Asha Rao');
    expect((post.init.headers as Record<string, string>)['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('explains a price change instead of charging a different amount', async () => {
    mockApi({
      ...customerSession(),
      'GET /api/v1/rooms/room-1': () => ({ body: room }),
      'GET /api/v1/rooms/room-1/quote': () => ({ body: quote }),
      'POST /api/v1/bookings': () => ({ status: 409, body: { status: 409, code: 'PRICE_CHANGED', detail: 'The price changed.', currentTotal: 23000, currency: 'INR' } }),
    });
    renderRoutes([{ path: '/checkout', element: <RequireAuth><CheckoutPage /></RequireAuth> }], `/checkout?roomId=room-1&checkIn=${checkIn}&checkOut=${checkOut}&guests=2`);
    await userEvent.click(await screen.findByRole('button', { name: /reserve/i }));
    expect(await screen.findByText('The price has changed')).toBeInTheDocument();
    expect(screen.getByText(/now ₹23,000.00/)).toBeInTheDocument();
  });
});

describe('confirmation page shows only the authoritative booking state', () => {
  const route = (path: string) => renderRoutes([{ path: '/bookings/:bookingId/confirmation', element: <ConfirmationPage /> }], path);

  it('confirmed booking', async () => {
    mockApi({ ...customerSession(), 'GET /api/v1/bookings/b-1': () => ({ body: booking('CONFIRMED') }), 'GET /api/v1/payments/by-booking/b-1': () => ({ body: payment('SUCCEEDED') }) });
    const { container } = route('/bookings/b-1/confirmation');
    expect(await screen.findByRole('heading', { name: 'Your stay is confirmed' })).toBeInTheDocument();
    expect(screen.getByText('GV-7KQ2MX')).toBeInTheDocument();
    expect(await seriousA11yViolations(container)).toEqual([]);
  });

  it('payment still processing is not shown as confirmed', async () => {
    mockApi({ ...customerSession(), 'GET /api/v1/bookings/b-1': () => ({ body: booking('PENDING_PAYMENT') }), 'GET /api/v1/payments/by-booking/b-1': () => ({ body: payment('PENDING') }) });
    route('/bookings/b-1/confirmation');
    expect(await screen.findByRole('heading', { name: 'Waiting for payment confirmation' })).toBeInTheDocument();
    expect(screen.queryByText('Your stay is confirmed')).not.toBeInTheDocument();
  });

  it('failed payment explains that nothing was charged', async () => {
    mockApi({ ...customerSession(), 'GET /api/v1/bookings/b-1': () => ({ body: booking('PAYMENT_FAILED') }), 'GET /api/v1/payments/by-booking/b-1': () => ({ body: payment('FAILED', { failureMessage: '[SIMULATED] payment declined: card_declined' }) }) });
    route('/bookings/b-1/confirmation');
    expect(await screen.findByRole('heading', { name: 'Payment was not successful' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Try again' })).toBeInTheDocument();
  });
});
