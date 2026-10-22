// Renders the GrandVista illustrative room artwork to WebP for the room-service demo seeder.
// Usage (sharp is not a project dependency): npm install sharp@0.34 in a scratch directory, then
//   node scripts/generate-demo-media.mjs services/room-service/src/main/resources/demo-media
import sharp from 'sharp';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

const out = process.argv[2];
mkdirSync(out, { recursive: true });

const palettes = {
  classic: { wall: ['#efe7da', '#e2d6c3'], floor: '#b89a78', bed: '#ffffff', throw: '#5d7a8c', accent: '#c99d58', view: 'courtyard', twin: false },
  'classic-twin': { wall: ['#eee8dd', '#ded3c1'], floor: '#b3936f', bed: '#ffffff', throw: '#6b8a7a', accent: '#c99d58', view: 'courtyard', twin: true },
  'deluxe-city': { wall: ['#e6e9ef', '#cfd6e1'], floor: '#8c6f55', bed: '#fbfaf7', throw: '#283850', accent: '#c99d58', view: 'city', twin: false },
  'deluxe-sea': { wall: ['#e3ecef', '#c9d9de'], floor: '#8c6f55', bed: '#fbfaf7', throw: '#2f5d75', accent: '#c99d58', view: 'sea', twin: false },
  family: { wall: ['#eef0e6', '#d9ddc9'], floor: '#a68a66', bed: '#ffffff', throw: '#7c8f5e', accent: '#d9b77a', view: 'garden', twin: true },
  suite: { wall: ['#ece4ea', '#d8c9d4'], floor: '#6f5644', bed: '#fdfbf9', throw: '#6a3d55', accent: '#c99d58', view: 'city', twin: false },
  'suite-ocean': { wall: ['#e5e6ee', '#cfd1e0'], floor: '#6f5644', bed: '#fdfbf9', throw: '#3d4a6a', accent: '#c99d58', view: 'sea', twin: false },
  presidential: { wall: ['#1f2b40', '#121d30'], floor: '#4a3a2e', bed: '#f4ead6', throw: '#946b39', accent: '#e8d3ab', view: 'sea', twin: false },
};

function svg(p) {
  const sky = p.view === 'sea' ? ['#9cc3dc', '#e3eef2'] : p.view === 'garden' ? ['#b9d7c4', '#eef4e6'] : p.view === 'city' ? ['#f2c99a', '#f8e7d2'] : ['#cfe0ea', '#f3f4ef'];
  const view = p.view === 'sea'
    ? `<rect x="254" y="132" width="172" height="54" fill="#3f7ea6"/><path d="M254 145 q20 -6 40 0 t40 0 t40 0 t52 0" stroke="#d9ecf4" stroke-width="3" fill="none"/><path d="M254 162 q20 -6 40 0 t40 0 t40 0 t52 0" stroke="#bfe0ee" stroke-width="2" fill="none"/><circle cx="390" cy="78" r="16" fill="#fbe7b5"/>`
    : p.view === 'garden'
      ? `<ellipse cx="300" cy="186" rx="60" ry="40" fill="#6f9a6a"/><ellipse cx="380" cy="190" rx="70" ry="46" fill="#5c8758"/><rect x="296" y="120" width="6" height="40" fill="#6b4f3a"/>`
      : p.view === 'city'
        ? `<g fill="#7d8aa3" opacity="0.85"><rect x="262" y="110" width="26" height="76"/><rect x="294" y="86" width="22" height="100"/><rect x="322" y="122" width="30" height="64"/><rect x="358" y="70" width="24" height="116"/><rect x="388" y="104" width="32" height="82"/></g>`
        : `<rect x="254" y="150" width="172" height="36" fill="#d8cdb8"/><ellipse cx="300" cy="150" rx="22" ry="28" fill="#7a9a72"/><ellipse cx="380" cy="152" rx="26" ry="30" fill="#6c8c66"/>`;
  const beds = p.twin
    ? `<rect x="30" y="150" width="104" height="70" rx="10" fill="${p.throw}" opacity="0.9"/><rect x="146" y="150" width="104" height="70" rx="10" fill="${p.throw}" opacity="0.9"/>
       <rect x="24" y="196" width="112" height="58" rx="10" fill="${p.bed}"/><rect x="144" y="196" width="112" height="58" rx="10" fill="${p.bed}"/>
       <rect x="24" y="226" width="112" height="30" rx="8" fill="${p.throw}"/><rect x="144" y="226" width="112" height="30" rx="8" fill="${p.throw}"/>
       <rect x="44" y="178" width="72" height="24" rx="12" fill="#ffffff" stroke="#e8e2d8"/><rect x="164" y="178" width="72" height="24" rx="12" fill="#ffffff" stroke="#e8e2d8"/>`
    : `<rect x="40" y="150" width="200" height="70" rx="10" fill="${p.throw}" opacity="0.9"/><rect x="30" y="196" width="230" height="58" rx="10" fill="${p.bed}"/>
       <rect x="30" y="226" width="230" height="30" rx="8" fill="${p.throw}"/><rect x="56" y="178" width="70" height="26" rx="12" fill="#ffffff" stroke="#e8e2d8"/>
       <rect x="140" y="178" width="70" height="26" rx="12" fill="#ffffff" stroke="#e8e2d8"/>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 480 320" width="1200" height="800">
  <defs><linearGradient id="w" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${p.wall[0]}"/><stop offset="1" stop-color="${p.wall[1]}"/></linearGradient>
  <linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${sky[0]}"/><stop offset="1" stop-color="${sky[1]}"/></linearGradient>
  <radialGradient id="l" cx="0.5" cy="0.5" r="0.5"><stop offset="0" stop-color="#fff4d6" stop-opacity="0.55"/><stop offset="1" stop-color="#fff4d6" stop-opacity="0"/></radialGradient></defs>
  <rect width="480" height="320" fill="url(#w)"/>
  <rect x="250" y="40" width="180" height="150" rx="6" fill="url(#s)" stroke="#ffffff" stroke-width="8"/>${view}
  <line x1="340" y1="40" x2="340" y2="190" stroke="#ffffff" stroke-width="5"/>
  <rect y="236" width="480" height="84" fill="${p.floor}"/><rect y="236" width="480" height="6" fill="#000" opacity="0.08"/>
  <rect x="70" y="62" width="110" height="70" rx="3" fill="#ffffff" stroke="${p.accent}" stroke-width="4"/><path d="M82 120 l24 -30 l18 20 l14 -14 l30 24 z" fill="${p.throw}" opacity="0.75"/>
  ${beds}
  <rect x="272" y="214" width="46" height="40" rx="4" fill="#5b4636"/><rect x="292" y="184" width="6" height="30" fill="${p.accent}"/>
  <path d="M278 160 h34 l-6 26 h-22 z" fill="#fff6e2" stroke="${p.accent}" stroke-width="2"/><circle cx="295" cy="174" r="60" fill="url(#l)"/>
  <rect x="420" y="222" width="26" height="32" rx="4" fill="#d9cfc0"/><path d="M433 222 c-18 -30 -10 -48 0 -60 c10 12 18 30 0 60" fill="#5f8a5c"/>
  <text x="470" y="312" text-anchor="end" font-family="Helvetica, Arial, sans-serif" font-size="9" fill="${p.view === 'sea' && p.wall[0] === '#1f2b40' ? '#e8d3ab' : '#ffffff'}" opacity="0.85">Illustration · demo artwork</text>
</svg>`;
}

for (const [name, p] of Object.entries(palettes)) {
  const file = join(out, `${name}.webp`);
  const info = await sharp(Buffer.from(svg(p))).webp({ quality: 82 }).toFile(file);
  console.log(`${name}.webp ${info.width}x${info.height} ${(info.size / 1024).toFixed(1)} KB`);
}
