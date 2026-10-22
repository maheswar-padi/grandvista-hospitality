import type { ReactNode } from 'react';
import { RoomIllustration } from '../components/rooms/RoomIllustration';

export function AuthCard({ title, subtitle, children }: { title: string; subtitle: string; children: ReactNode }) {
  return (
    <div className="container-page grid min-h-[70vh] items-center gap-10 py-12 lg:grid-cols-2">
      <div className="hidden overflow-hidden rounded-3xl lg:block" aria-hidden>
        <RoomIllustration typeCode="SUITE" view="Sea" className="aspect-[4/5] w-full" label="" />
      </div>
      <div className="mx-auto w-full max-w-md">
        <h1 className="text-4xl sm:text-5xl">{title}</h1>
        <p className="mt-2 text-ink-600">{subtitle}</p>
        <div className="mt-8">{children}</div>
      </div>
    </div>
  );
}
