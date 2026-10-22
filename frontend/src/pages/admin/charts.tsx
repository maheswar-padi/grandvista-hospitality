import type { ReactNode } from 'react';
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

// Chart colours come from a palette validated for colour-vision deficiency (blue / orange, both >= 3:1 on
// white). Text always uses ink tokens, never the series colour.
export const SERIES_1 = '#2a78d6';
export const SERIES_2 = '#eb6834';
const GRID = '#e3e8f0';
const AXIS_TEXT = '#4a5d7c';

export function ChartCard({ title, subtitle, children, table }: { title: string; subtitle?: string; children: ReactNode; table: ReactNode }) {
  return (
    <section className="card p-5" aria-label={title}>
      <h2 className="font-sans text-base font-semibold text-ink-900">{title}</h2>
      {subtitle && <p className="text-xs text-ink-500">{subtitle}</p>}
      <div className="mt-4 h-64">{children}</div>
      <details className="mt-3 text-sm">
        <summary className="cursor-pointer text-xs font-medium text-ink-600">Show data as a table</summary>
        <div className="relative mt-2 max-h-64 overflow-auto">{table}</div>
      </details>
    </section>
  );
}

interface TooltipBoxProps {
  active?: boolean;
  payload?: Array<{ dataKey?: string | number; name?: string; value?: number | string; color?: string }>;
  label?: string | number;
  format: (v: number) => string;
}

/** Values lead, series names follow; each row is keyed by a short line in the series colour. */
function TooltipBox({ active, payload, label, format }: TooltipBoxProps) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-lg border border-ink-100 bg-white px-3 py-2 text-xs shadow-lg">
      <p className="mb-1 text-ink-500">{label}</p>
      {payload.map((p) => (
        <p key={String(p.dataKey)} className="flex items-center gap-2">
          <span className="inline-block h-0.5 w-3" style={{ background: p.color }} aria-hidden />
          <strong className="tabular-nums text-ink-900">{format(Number(p.value))}</strong>
          <span className="text-ink-500">{p.name}</span>
        </p>
      ))}
    </div>
  );
}

export function OccupancyChart({ data }: { data: Array<{ label: string; occupancy: number }> }) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -8 }}>
        <CartesianGrid stroke={GRID} strokeWidth={1} vertical={false} />
        <XAxis dataKey="label" tick={{ fill: AXIS_TEXT, fontSize: 11 }} tickLine={false} axisLine={{ stroke: GRID }} interval="preserveStartEnd" minTickGap={24} />
        <YAxis domain={[0, 100]} ticks={[0, 25, 50, 75, 100]} tickFormatter={(v) => `${v}%`} tick={{ fill: AXIS_TEXT, fontSize: 11 }} tickLine={false} axisLine={false} width={44} />
        <Tooltip content={<TooltipBox format={(v) => `${v}%`} />} cursor={{ stroke: AXIS_TEXT, strokeWidth: 1 }} />
        <Area type="monotone" dataKey="occupancy" name="Occupancy" stroke={SERIES_1} strokeWidth={2} fill={SERIES_1} fillOpacity={0.1}
          activeDot={{ r: 5, stroke: '#ffffff', strokeWidth: 2 }} isAnimationActive={false} />
      </AreaChart>
    </ResponsiveContainer>
  );
}

export function BookingTrendChart({ data }: { data: Array<{ label: string; created: number; confirmed: number }> }) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -16 }} barGap={2} barCategoryGap="20%">
        <CartesianGrid stroke={GRID} strokeWidth={1} vertical={false} />
        <XAxis dataKey="label" tick={{ fill: AXIS_TEXT, fontSize: 11 }} tickLine={false} axisLine={{ stroke: GRID }} interval="preserveStartEnd" minTickGap={24} />
        <YAxis allowDecimals={false} tick={{ fill: AXIS_TEXT, fontSize: 11 }} tickLine={false} axisLine={false} width={44} />
        <Tooltip content={<TooltipBox format={(v) => String(v)} />} cursor={{ fill: '#f3f5f9' }} />
        <Legend iconType="rect" iconSize={10} wrapperStyle={{ fontSize: 12, color: AXIS_TEXT }} />
        <Bar dataKey="created" name="Bookings created" fill={SERIES_1} maxBarSize={24} radius={[4, 4, 0, 0]} isAnimationActive={false} />
        <Bar dataKey="confirmed" name="Confirmed" fill={SERIES_2} maxBarSize={24} radius={[4, 4, 0, 0]} isAnimationActive={false} />
      </BarChart>
    </ResponsiveContainer>
  );
}

export function HorizontalBars({ data, valueName }: { data: Array<{ label: string; value: number }>; valueName: string }) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={data} layout="vertical" margin={{ top: 4, right: 32, bottom: 0, left: 8 }}>
        <CartesianGrid stroke={GRID} strokeWidth={1} horizontal={false} />
        <XAxis type="number" allowDecimals={false} tick={{ fill: AXIS_TEXT, fontSize: 11 }} tickLine={false} axisLine={false} />
        <YAxis type="category" dataKey="label" width={130} tick={{ fill: AXIS_TEXT, fontSize: 11 }} tickLine={false} axisLine={{ stroke: GRID }} />
        <Tooltip content={<TooltipBox format={(v) => String(v)} />} cursor={{ fill: '#f3f5f9' }} />
        <Bar dataKey="value" name={valueName} fill={SERIES_1} maxBarSize={20} radius={[0, 4, 4, 0]} isAnimationActive={false}
          label={{ position: 'right', fill: '#121d30', fontSize: 11 }} />
      </BarChart>
    </ResponsiveContainer>
  );
}
