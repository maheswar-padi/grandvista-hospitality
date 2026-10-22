import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import AccountPage from '../pages/AccountPage';
import { booking } from './fixtures';
import { customerSession, mockApi, renderRoutes } from './utils';

const page = (content: unknown[], extra: Partial<{ totalPages: number; page: number }> = {}) => ({
  body: { content, page: extra.page ?? 0, size: 20, totalElements: content.length, totalPages: extra.totalPages ?? 1 },
});

describe('customer booking history', () => {
  it('shows upcoming bookings with their real statuses, and switches scope', async () => {
    const { calls } = mockApi({
      ...customerSession(),
      'GET /api/v1/bookings/my': (url) =>
        url.searchParams.get('scope') === 'UPCOMING'
          ? page([booking('CONFIRMED'), booking('CANCELLED', { id: 'b-2', reference: 'GV-CANC22' })])
          : page([booking('EXPIRED', { id: 'b-3', reference: 'GV-EXPR33' })]),
    });
    renderRoutes([{ path: '/account', element: <AccountPage /> }], '/account');
    const list = await screen.findByRole('list');
    expect(within(list).getByText('Confirmed')).toBeInTheDocument();
    expect(within(list).getByText('Cancelled')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: 'Past' }));
    expect(await screen.findByText('Expired')).toBeInTheDocument();
    expect(calls.filter((c) => c.path === '/api/v1/bookings/my').length).toBeGreaterThanOrEqual(2);
  });

  it('renders a helpful empty state', async () => {
    mockApi({ ...customerSession(), 'GET /api/v1/bookings/my': () => page([]) });
    renderRoutes([{ path: '/account', element: <AccountPage /> }], '/account');
    expect(await screen.findByText('No bookings yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Find a room' })).toBeInTheDocument();
  });

  it('shows an error with its reference and recovers on retry', async () => {
    let fail = true;
    mockApi({
      ...customerSession(),
      'GET /api/v1/bookings/my': () =>
        fail
          ? { status: 500, body: { status: 500, code: 'INTERNAL_ERROR', detail: 'An unexpected error occurred.', correlationId: 'cid-err-0001' } }
          : page([booking('CONFIRMED')]),
    });
    renderRoutes([{ path: '/account', element: <AccountPage /> }], '/account');
    expect(await screen.findByText('An unexpected error occurred.')).toBeInTheDocument();
    expect(screen.getByText('cid-err-0001')).toBeInTheDocument();
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByText('Confirmed')).toBeInTheDocument());
  });

  it('paginates', async () => {
    const { calls } = mockApi({
      ...customerSession(),
      'GET /api/v1/bookings/my': (url) => page([booking('CONFIRMED', { id: `b-p${url.searchParams.get('page')}` })], { totalPages: 2, page: Number(url.searchParams.get('page')) }),
    });
    renderRoutes([{ path: '/account', element: <AccountPage /> }], '/account');
    await userEvent.click(await screen.findByRole('button', { name: /next/i }));
    await waitFor(() => expect(calls.some((c) => c.path === '/api/v1/bookings/my' && String(c.init) !== '' )).toBe(true));
    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument();
  });
});
