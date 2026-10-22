import { Suspense } from 'react';
import { Outlet, ScrollRestoration } from 'react-router';
import { LoadingBlock } from '../ui/States';
import { DemoBanner } from './DemoBanner';
import { Footer } from './Footer';
import { Header } from './Header';

export function Layout() {
  return (
    <div className="flex min-h-screen flex-col">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded focus:bg-white focus:px-4 focus:py-2">
        Skip to content
      </a>
      <DemoBanner />
      <Header />
      <main id="main" className="flex-1">
        <Suspense fallback={<div className="container-page py-16"><LoadingBlock /></div>}>
          <Outlet />
        </Suspense>
      </main>
      <Footer />
      <ScrollRestoration />
    </div>
  );
}
