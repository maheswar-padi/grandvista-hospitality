import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SearchBar } from '../components/rooms/SearchBar';
import { PROPERTY_STORAGE_KEY } from '../lib/adminProperty';
import { hotelToday, plusDays } from '../lib/dates';
import AdminLayout from '../pages/admin/AdminLayout';
import AdminReconciliationPage from '../pages/admin/AdminReconciliationPage';
import AdminRoomsPage from '../pages/admin/AdminRoomsPage';
import SearchPage from '../pages/SearchPage';
import { hotels, reconciliationReport, room } from './fixtures';
import { customerSession, mockApi, renderPlain, renderRoutes, seriousA11yViolations } from './utils';

const page = (content: unknown[]) => ({ body: { content, page: 0, size: 25, totalElements: content.length, totalPages: 1 } });
const adminRoutes = (child: { path: string; element: React.ReactNode }) => [
  { path: '/admin', element: <AdminLayout />, children: [child] },
];

afterEach(() => localStorage.clear());

describe('multi-property search', () => {
  it('offers every city and hotel and submits the chosen destination', async () => {
    mockApi({ 'GET /api/v1/properties': () => ({ body: hotels }) });
    const onSearch = vi.fn();
    const day = plusDays(hotelToday(), 5);
    const { container } = renderPlain(<SearchBar withDestination defaults={{ checkIn: day, checkOut: plusDays(day, 2), guests: 2 }} onSearch={onSearch} />);

    const select = screen.getByRole('combobox', { name: /destination/i });
    await waitFor(() => expect(within(select).getByRole('option', { name: 'GrandVista Goa' })).toBeInTheDocument());
    expect(within(select).getByRole('option', { name: 'Anywhere in Dubai' })).toBeInTheDocument();
    expect(within(select).getAllByRole('group').map((g) => g.getAttribute('label'))).toEqual(['Dubai, AE', 'Goa, IN', 'Hyderabad, IN']);

    await userEvent.selectOptions(select, 'GrandVista Goa');
    await userEvent.click(screen.getByRole('button', { name: /search availability/i }));
    await waitFor(() => expect(onSearch).toHaveBeenCalledWith({ checkIn: day, checkOut: plusDays(day, 2), guests: 2 }, { propertyId: 'p-goa' }));

    await userEvent.selectOptions(select, 'Anywhere in Dubai');
    await userEvent.click(screen.getByRole('button', { name: /search availability/i }));
    await waitFor(() => expect(onSearch).toHaveBeenLastCalledWith(expect.anything(), { city: 'Dubai' }));
    expect(await seriousA11yViolations(container)).toEqual([]);
  });

  it('filters the search by city and shows the hotel on each room', async () => {
    const dubaiRoom = { ...room, id: 'room-dxb', property: { ...hotels[2] } };
    const { calls } = mockApi({
      'GET /api/v1/properties': () => ({ body: hotels }),
      'GET /api/v1/rooms': () => page([dubaiRoom]),
      'GET /api/v1/room-types': () => ({ body: [room.roomType, { ...room.roomType, id: 't-2', propertyId: 'p-dxb' }] }),
      'GET /api/v1/amenities': () => ({ body: [] }),
    });
    renderRoutes([{ path: '/rooms', element: <SearchPage /> }], '/rooms?city=Dubai');

    expect(await screen.findByRole('heading', { level: 1, name: 'Rooms in Dubai' })).toBeInTheDocument();
    expect(await screen.findByText(/GrandVista Dubai · Dubai · Room 201/)).toBeInTheDocument();
    const search = calls.find((c) => c.path === '/api/v1/rooms');
    expect(search).toBeDefined();
    const fetched = vi.mocked(fetch).mock.calls.map(([u]) => new URL(String(u), 'http://localhost')).find((u) => u.pathname === '/api/v1/rooms');
    expect(fetched?.searchParams.get('city')).toBe('Dubai');
    // room types share codes across hotels; the filter lists each once
    await waitFor(() => expect(screen.getAllByRole('option', { name: 'Deluxe Room' })).toHaveLength(1));
  });
});

describe('admin property filter', () => {
  it('scopes the rooms list to the chosen hotel and remembers the choice', async () => {
    mockApi({
      ...customerSession({ roles: ['ADMIN'] }),
      'GET /api/v1/admin/properties': () => ({ body: hotels }),
      'GET /api/v1/admin/rooms': (url) => page(url.searchParams.get('propertyId') === 'p-goa' ? [{ ...room, id: 'r-goa', roomNumber: '101', property: hotels[1] }] : [room]),
    });
    renderRoutes(adminRoutes({ path: 'rooms', element: <AdminRoomsPage /> }), '/admin/rooms');

    expect(await screen.findByText('201')).toBeInTheDocument();
    const hotel = screen.getByRole('combobox', { name: 'Hotel' });
    await waitFor(() => expect(within(hotel).getByRole('option', { name: /GrandVista Goa · Goa/ })).toBeInTheDocument());
    await userEvent.selectOptions(hotel, 'p-goa');

    expect(await screen.findByText('101')).toBeInTheDocument();
    expect(screen.queryByText('201')).not.toBeInTheDocument();
    expect(localStorage.getItem(PROPERTY_STORAGE_KEY)).toBe('p-goa');
    const scoped = vi.mocked(fetch).mock.calls.map(([u]) => new URL(String(u), 'http://localhost'))
      .filter((u) => u.pathname === '/api/v1/admin/rooms').map((u) => u.searchParams.get('propertyId'));
    expect(scoped).toEqual([null, 'p-goa']);
  });
});

describe('stock reconciliation report', () => {
  it('shows the latest findings of the selected hotel and runs a reconciliation on demand', async () => {
    localStorage.setItem(PROPERTY_STORAGE_KEY, 'p-goa');
    let runs = 0;
    const { calls } = mockApi({
      ...customerSession({ roles: ['STAFF'] }),
      'GET /api/v1/admin/properties': () => ({ body: hotels }),
      'GET /api/v1/admin/inventory/reconciliation': () => ({ body: reconciliationReport }),
      'GET /api/v1/admin/inventory/reconciliation/runs': () => ({ body: [reconciliationReport] }),
      'POST /api/v1/admin/inventory/reconciliation/run': () => {
        runs++;
        return { body: { ...reconciliationReport, id: 'run-2', findingCount: 0, releasedCount: 0, findings: [], countsByType: { ...reconciliationReport.countsByType, ORPHAN_HOLD: 0, CONFIRMED_BOOKING_WITHOUT_ALLOCATION: 0 } } };
      },
    });
    const { container } = renderRoutes(adminRoutes({ path: 'reconciliation', element: <AdminReconciliationPage /> }), '/admin/reconciliation');

    const panel = await screen.findByRole('region', { name: 'Reconciliation for GrandVista Goa' });
    expect(await within(panel).findByText('2 findings')).toBeInTheDocument();
    expect(within(panel).getByText('GV-ABC123')).toBeInTheDocument();
    expect(within(panel).getByText('Hold released automatically')).toBeInTheDocument();
    expect(within(panel).getByText('Critical')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: /GrandVista Dubai/ })).not.toBeInTheDocument();

    await userEvent.click(within(panel).getByRole('button', { name: /run now/i }));
    expect(await within(panel).findByText('In balance')).toBeInTheDocument();
    expect(runs).toBe(1);
    const post = vi.mocked(fetch).mock.calls.map(([u]) => new URL(String(u), 'http://localhost'))
      .find((u) => u.pathname === '/api/v1/admin/inventory/reconciliation/run');
    expect(post?.searchParams.get('propertyId')).toBe('p-goa');
    expect(calls.some((c) => c.method === 'POST')).toBe(true);
    expect(await seriousA11yViolations(container)).toEqual([]);
  });

  it('lists every hotel when "All hotels" is selected, including hotels never reconciled', async () => {
    mockApi({
      ...customerSession({ roles: ['ADMIN'] }),
      'GET /api/v1/admin/properties': () => ({ body: hotels }),
      'GET /api/v1/admin/inventory/reconciliation': (url) =>
        url.searchParams.get('propertyId') === 'p-dxb'
          ? { status: 404, body: { status: 404, code: 'RECONCILIATION_NOT_RUN', detail: 'No reconciliation has run for this property yet.' } }
          : { body: { ...reconciliationReport, propertyId: url.searchParams.get('propertyId') } },
    });
    renderRoutes(adminRoutes({ path: 'reconciliation', element: <AdminReconciliationPage /> }), '/admin/reconciliation');

    expect(await screen.findAllByRole('region', { name: /^Reconciliation for/ })).toHaveLength(3);
    const dubai = screen.getByRole('region', { name: 'Reconciliation for GrandVista Dubai' });
    expect(await within(dubai).findByText(/No reconciliation has run for this hotel yet/)).toBeInTheDocument();
    // findings tables are only shown for a single selected hotel
    expect(screen.queryByText('GV-ABC123')).not.toBeInTheDocument();
  });
});
