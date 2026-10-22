import { Link } from 'react-router';

export function Logo({ light = false }: { light?: boolean }) {
  return (
    <Link to="/" className="flex items-center gap-2.5" aria-label="GrandVista Hospitality home">
      <svg viewBox="0 0 64 64" className="size-9" aria-hidden>
        <rect width="64" height="64" rx="14" fill={light ? '#faf8f4' : '#121d30'} />
        <path d="M14 44 L32 16 L50 44" fill="none" stroke="#c99d58" strokeWidth="4" strokeLinejoin="round" />
        <path d="M22 44 L32 29 L42 44" fill="none" stroke={light ? '#946b39' : '#e8d3ab'} strokeWidth="3" strokeLinejoin="round" />
        <line x1="12" y1="48" x2="52" y2="48" stroke="#c99d58" strokeWidth="3" strokeLinecap="round" />
      </svg>
      <span className="leading-none">
        <span className={`block font-display text-xl font-semibold ${light ? 'text-white' : 'text-ink-900'}`}>GrandVista</span>
        <span className={`block text-[0.65rem] font-semibold uppercase tracking-[0.25em] ${light ? 'text-gold-200' : 'text-gold-700'}`}>
          Hospitality
        </span>
      </span>
    </Link>
  );
}
