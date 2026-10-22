import { useId } from 'react';

interface Palette {
  wall: [string, string];
  floor: string;
  bed: string;
  throw: string;
  accent: string;
}

const palettes: Record<string, Palette> = {
  CLASSIC: { wall: ['#efe7da', '#e2d6c3'], floor: '#b89a78', bed: '#ffffff', throw: '#5d7a8c', accent: '#c99d58' },
  DELUXE: { wall: ['#e6e9ef', '#cfd6e1'], floor: '#8c6f55', bed: '#fbfaf7', throw: '#283850', accent: '#c99d58' },
  FAMILY: { wall: ['#eef0e6', '#d9ddc9'], floor: '#a68a66', bed: '#ffffff', throw: '#7c8f5e', accent: '#d9b77a' },
  SUITE: { wall: ['#ece4ea', '#d8c9d4'], floor: '#6f5644', bed: '#fdfbf9', throw: '#6a3d55', accent: '#c99d58' },
  PRESIDENTIAL: { wall: ['#1f2b40', '#121d30'], floor: '#4a3a2e', bed: '#f4ead6', throw: '#946b39', accent: '#e8d3ab' },
};

/**
 * Illustrative artwork used when a room has no uploaded photograph. It is decorative and clearly not a photo;
 * administrators can upload real images, which then take precedence.
 */
export function RoomIllustration({ typeCode, view, className, label }: { typeCode: string; view?: string | null; className?: string; label?: string }) {
  const p = palettes[typeCode] ?? palettes.CLASSIC;
  const id = useId().replace(/:/g, '');
  const sea = view?.toLowerCase().includes('sea');
  const garden = view?.toLowerCase().includes('garden');
  return (
    <svg viewBox="0 0 480 320" className={className} role="img" aria-label={label ?? 'Illustration of the room (not a photograph)'} preserveAspectRatio="xMidYMid slice">
      <defs>
        <linearGradient id={`wall${id}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={p.wall[0]} />
          <stop offset="1" stopColor={p.wall[1]} />
        </linearGradient>
        <linearGradient id={`sky${id}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={sea ? '#9cc3dc' : garden ? '#b9d7c4' : '#f2c99a'} />
          <stop offset="1" stopColor={sea ? '#e3eef2' : garden ? '#eef4e6' : '#f8e7d2'} />
        </linearGradient>
      </defs>
      <rect width="480" height="320" fill={`url(#wall${id})`} />
      {/* window */}
      <rect x="250" y="40" width="180" height="150" rx="6" fill={`url(#sky${id})`} stroke="#ffffff" strokeWidth="8" />
      {sea ? (
        <g>
          <rect x="254" y="132" width="172" height="54" fill="#3f7ea6" />
          <path d="M254 145 q20 -6 40 0 t40 0 t40 0 t52 0" stroke="#d9ecf4" strokeWidth="3" fill="none" />
          <path d="M254 162 q20 -6 40 0 t40 0 t40 0 t52 0" stroke="#bfe0ee" strokeWidth="2" fill="none" />
          <circle cx="390" cy="78" r="16" fill="#fbe7b5" />
        </g>
      ) : garden ? (
        <g>
          <ellipse cx="300" cy="186" rx="60" ry="40" fill="#6f9a6a" />
          <ellipse cx="380" cy="190" rx="70" ry="46" fill="#5c8758" />
          <rect x="296" y="120" width="6" height="40" fill="#6b4f3a" />
        </g>
      ) : (
        <g fill="#7d8aa3" opacity="0.85">
          <rect x="262" y="110" width="26" height="76" />
          <rect x="294" y="86" width="22" height="100" />
          <rect x="322" y="122" width="30" height="64" />
          <rect x="358" y="70" width="24" height="116" />
          <rect x="388" y="104" width="32" height="82" />
        </g>
      )}
      <line x1="340" y1="40" x2="340" y2="190" stroke="#ffffff" strokeWidth="5" />
      {/* floor */}
      <rect y="236" width="480" height="84" fill={p.floor} />
      <rect y="236" width="480" height="6" fill="#000" opacity="0.08" />
      {/* art */}
      <rect x="70" y="62" width="110" height="70" rx="3" fill="#ffffff" stroke={p.accent} strokeWidth="4" />
      <path d="M82 120 l24 -30 l18 20 l14 -14 l30 24 z" fill={p.throw} opacity="0.75" />
      {/* headboard + bed */}
      <rect x="40" y="150" width="200" height="70" rx="10" fill={p.throw} opacity="0.9" />
      <rect x="30" y="196" width="230" height="58" rx="10" fill={p.bed} />
      <rect x="30" y="226" width="230" height="30" rx="8" fill={p.throw} />
      <rect x="56" y="178" width="70" height="26" rx="12" fill="#ffffff" stroke="#e8e2d8" />
      <rect x="140" y="178" width="70" height="26" rx="12" fill="#ffffff" stroke="#e8e2d8" />
      {/* side table + lamp */}
      <rect x="272" y="214" width="46" height="40" rx="4" fill="#5b4636" />
      <rect x="292" y="184" width="6" height="30" fill={p.accent} />
      <path d="M278 160 h34 l-6 26 h-22 z" fill="#fff6e2" stroke={p.accent} strokeWidth="2" />
      <circle cx="295" cy="174" r="40" fill="#fff4d6" opacity="0.18" />
      {/* plant */}
      <rect x="420" y="222" width="26" height="32" rx="4" fill="#d9cfc0" />
      <path d="M433 222 c-18 -30 -10 -48 0 -60 c10 12 18 30 0 60" fill="#5f8a5c" />
    </svg>
  );
}
