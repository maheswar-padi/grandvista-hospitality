import { useQuery } from '@tanstack/react-query';
import { BedDouble, CalendarRange, CreditCard, LayoutDashboard, ScanSearch, Tags, Users } from 'lucide-react';
import { Suspense, useMemo, useState } from 'react';
import { NavLink, Outlet } from 'react-router';
import { cn } from '../../components/ui/cn';
import { LoadingBlock } from '../../components/ui/States';
import { adminApi } from '../../lib/api/endpoints';
import { AdminPropertyContext, effectiveProperty, readStoredProperty, storeProperty } from '../../lib/adminProperty';
import { useAuth } from '../../lib/auth/AuthContext';

export default function AdminLayout() {
  const { hasRole } = useAuth();
  const properties = useQuery({ queryKey: ['admin', 'properties'], queryFn: adminApi.properties, staleTime: 60_000 });
  const [stored, setStored] = useState<string | undefined>(readStoredProperty);
  const list = useMemo(() => properties.data ?? [], [properties.data]);
  // a remembered hotel that no longer exists falls back to "All hotels"
  const propertyId = stored && (!properties.data || list.some((p) => p.id === stored)) ? stored : undefined;
  const context = useMemo(
    () => ({
      propertyId,
      setPropertyId: (id: string | undefined) => {
        storeProperty(id);
        setStored(id);
      },
      properties: list,
      effective: effectiveProperty(list, propertyId),
    }),
    [propertyId, list],
  );

  const items = [
    { to: '/admin', label: 'Overview', icon: LayoutDashboard, end: true },
    { to: '/admin/rooms', label: 'Rooms & inventory', icon: BedDouble },
    { to: '/admin/pricing', label: 'Pricing', icon: Tags },
    { to: '/admin/bookings', label: 'Bookings', icon: CalendarRange },
    { to: '/admin/payments', label: 'Payments', icon: CreditCard },
    { to: '/admin/reconciliation', label: 'Reconciliation', icon: ScanSearch },
    ...(hasRole('ADMIN') ? [{ to: '/admin/users', label: 'Users & roles', icon: Users }] : []),
  ];
  return (
    <AdminPropertyContext.Provider value={context}>
      <div className="container-page grid grid-cols-[minmax(0,1fr)] gap-8 py-10 lg:grid-cols-[220px_minmax(0,1fr)]">
        <nav aria-label="Administration" className="min-w-0 lg:sticky lg:top-24 lg:self-start">
          <p className="eyebrow mb-3">Administration</p>
          <label className="mb-4 block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wider text-ink-500">Hotel</span>
            <select
              className="w-full rounded-xl border border-ink-200 bg-white px-3 py-2 text-sm"
              value={propertyId ?? ''}
              onChange={(e) => context.setPropertyId(e.target.value || undefined)}
              disabled={properties.isPending}
            >
              <option value="">All hotels</option>
              {list.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} · {p.city}{p.active ? '' : ' (inactive)'}
                </option>
              ))}
            </select>
          </label>
          <ul className="relative -mx-1 flex gap-1 overflow-x-auto px-1 pb-1 lg:flex-col">
            {items.map(({ to, label, icon: Icon, end }) => (
              <li key={to}>
                <NavLink
                  to={to}
                  end={end}
                  className={({ isActive }) =>
                    cn('flex items-center gap-2 whitespace-nowrap rounded-xl px-3 py-2 text-sm font-medium', isActive ? 'bg-ink-900 text-white' : 'text-ink-700 hover:bg-white')
                  }
                >
                  <Icon className="size-4" aria-hidden /> {label}
                </NavLink>
              </li>
            ))}
          </ul>
          {!hasRole('ADMIN') && <p className="mt-4 text-xs text-ink-500">Signed in as staff: read access, room status changes and reconciliation runs.</p>}
        </nav>
        <div className="min-w-0">
          {/* keyed by hotel so page-local state (filters, drafts, paging) never leaks across hotels */}
          <Suspense key={propertyId ?? 'all'} fallback={<LoadingBlock />}>
            <Outlet />
          </Suspense>
        </div>
      </div>
    </AdminPropertyContext.Provider>
  );
}
