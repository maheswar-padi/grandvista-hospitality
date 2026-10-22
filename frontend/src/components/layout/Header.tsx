import { CalendarCheck, LayoutDashboard, LogOut, Menu, UserRound, X } from 'lucide-react';
import { useState } from 'react';
import { NavLink, useNavigate } from 'react-router';
import { useAuth } from '../../lib/auth/AuthContext';
import { Button, ButtonLink } from '../ui/Button';
import { cn } from '../ui/cn';
import { Logo } from './Logo';

const navClass = ({ isActive }: { isActive: boolean }) =>
  cn('whitespace-nowrap rounded-full px-3.5 py-2 text-sm font-medium transition-colors', isActive ? 'bg-ink-900 text-white' : 'text-ink-700 hover:bg-ink-50');

export function Header() {
  const { status, user, logout, hasRole } = useAuth();
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const personnel = hasRole('ADMIN', 'STAFF');

  const links = (
    <>
      <NavLink to="/rooms" className={navClass} onClick={() => setOpen(false)}>
        Rooms &amp; suites
      </NavLink>
      {status === 'authenticated' && (
        <NavLink to="/account" className={navClass} onClick={() => setOpen(false)}>
          <span className="inline-flex items-center gap-1.5">
            <CalendarCheck className="size-4" aria-hidden /> My bookings
          </span>
        </NavLink>
      )}
      {personnel && (
        <NavLink to="/admin" className={navClass} onClick={() => setOpen(false)}>
          <span className="inline-flex items-center gap-1.5">
            <LayoutDashboard className="size-4" aria-hidden /> Administration
          </span>
        </NavLink>
      )}
    </>
  );

  const onLogout = async () => {
    setOpen(false);
    await logout();
    navigate('/');
  };

  return (
    <header className="sticky top-0 z-40 border-b border-ink-100 bg-ivory/90 backdrop-blur">
      <div className="container-page flex h-18 items-center justify-between gap-4 py-3">
        <Logo />
        <nav className="hidden items-center gap-1 lg:flex" aria-label="Main">
          {links}
        </nav>
        <div className="hidden items-center gap-2 lg:flex">
          {status === 'authenticated' ? (
            <>
              <span className="flex items-center gap-2 text-sm text-ink-600">
                <UserRound className="size-4" aria-hidden />
                <span className="max-w-40 truncate">{user?.fullName}</span>
              </span>
              <Button variant="ghost" size="sm" onClick={onLogout} icon={<LogOut className="size-4" aria-hidden />}>
                Sign out
              </Button>
            </>
          ) : status === 'anonymous' ? (
            <>
              <ButtonLink to="/login" variant="ghost" size="sm">
                Sign in
              </ButtonLink>
              <ButtonLink to="/register" size="sm">
                Create account
              </ButtonLink>
            </>
          ) : null}
        </div>
        <button
          type="button"
          className="rounded-full p-2 text-ink-800 hover:bg-ink-50 lg:hidden"
          aria-expanded={open}
          aria-controls="mobile-menu"
          aria-label={open ? 'Close menu' : 'Open menu'}
          onClick={() => setOpen((v) => !v)}
        >
          {open ? <X className="size-6" aria-hidden /> : <Menu className="size-6" aria-hidden />}
        </button>
      </div>
      {open && (
        <div id="mobile-menu" className="border-t border-ink-100 bg-ivory lg:hidden">
          <nav className="container-page flex flex-col gap-1 py-4" aria-label="Mobile">
            {links}
            <div className="mt-3 flex gap-2 border-t border-ink-100 pt-4">
              {status === 'authenticated' ? (
                <Button variant="secondary" size="sm" onClick={onLogout} icon={<LogOut className="size-4" aria-hidden />}>
                  Sign out
                </Button>
              ) : (
                <>
                  <ButtonLink to="/login" variant="secondary" size="sm" onClick={() => setOpen(false)}>
                    Sign in
                  </ButtonLink>
                  <ButtonLink to="/register" size="sm" onClick={() => setOpen(false)}>
                    Create account
                  </ButtonLink>
                </>
              )}
            </div>
          </nav>
        </div>
      )}
    </header>
  );
}
