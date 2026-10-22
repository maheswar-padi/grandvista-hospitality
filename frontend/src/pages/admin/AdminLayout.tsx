import { BedDouble, CalendarRange, CreditCard, LayoutDashboard, Tags, Users } from 'lucide-react';
import { Suspense } from 'react';
import { NavLink, Outlet } from 'react-router';
import { cn } from '../../components/ui/cn';
import { LoadingBlock } from '../../components/ui/States';
import { useAuth } from '../../lib/auth/AuthContext';

export default function AdminLayout() {
  const { hasRole } = useAuth();
  const items = [
    { to: '/admin', label: 'Overview', icon: LayoutDashboard, end: true },
    { to: '/admin/rooms', label: 'Rooms & inventory', icon: BedDouble },
    { to: '/admin/pricing', label: 'Pricing', icon: Tags },
    { to: '/admin/bookings', label: 'Bookings', icon: CalendarRange },
    { to: '/admin/payments', label: 'Payments', icon: CreditCard },
    ...(hasRole('ADMIN') ? [{ to: '/admin/users', label: 'Users & roles', icon: Users }] : []),
  ];
  return (
    <div className="container-page grid grid-cols-[minmax(0,1fr)] gap-8 py-10 lg:grid-cols-[220px_minmax(0,1fr)]">
      <nav aria-label="Administration" className="min-w-0 lg:sticky lg:top-24 lg:self-start">
        <p className="eyebrow mb-3">Administration</p>
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
        {!hasRole('ADMIN') && <p className="mt-4 text-xs text-ink-500">Signed in as staff: read access plus room status changes.</p>}
      </nav>
      <div className="min-w-0">
        <Suspense fallback={<LoadingBlock />}>
          <Outlet />
        </Suspense>
      </div>
    </div>
  );
}
