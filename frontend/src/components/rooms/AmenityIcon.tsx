import {
  Accessibility,
  Bath,
  Building2,
  ConciergeBell,
  Coffee,
  CookingPot,
  HandPlatter,
  Laptop,
  ShowerHead,
  Snowflake,
  Sparkles,
  Sun,
  Tv,
  Waves,
  Wifi,
  Wine,
  type LucideIcon,
} from 'lucide-react';

const icons: Record<string, LucideIcon> = {
  wifi: Wifi,
  snowflake: Snowflake,
  tv: Tv,
  wine: Wine,
  coffee: Coffee,
  bath: Bath,
  'shower-head': ShowerHead,
  sun: Sun,
  waves: Waves,
  'building-2': Building2,
  laptop: Laptop,
  'concierge-bell': ConciergeBell,
  'cooking-pot': CookingPot,
  accessibility: Accessibility,
  'hand-platter': HandPlatter,
};

export function AmenityIcon({ name, className = 'size-4' }: { name: string | null; className?: string }) {
  const Icon = (name && icons[name]) || Sparkles;
  return <Icon className={className} aria-hidden />;
}
