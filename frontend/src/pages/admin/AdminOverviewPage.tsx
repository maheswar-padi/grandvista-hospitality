import { useQuery } from '@tanstack/react-query';
import { AlertTriangle } from 'lucide-react';
import { AdminHeader, StatTile, td, th } from '../../components/admin/AdminUi';
import { Alert } from '../../components/ui/Alert';
import { ErrorState, LoadingBlock } from '../../components/ui/States';
import { adminApi } from '../../lib/api/endpoints';
import { hotelToday, plusDays } from '../../lib/dates';
import { bookingStatusLabel, formatDate, formatMoney, formatPercent, paymentStatusLabel } from '../../lib/format';
import { BookingTrendChart, ChartCard, HorizontalBars, OccupancyChart } from './charts';

export default function AdminOverviewPage() {
  const today = hotelToday();
  const inventory = useQuery({ queryKey: ['admin', 'inventory-summary'], queryFn: adminApi.inventorySummary });
  const occupancy = useQuery({ queryKey: ['admin', 'occupancy', today], queryFn: () => adminApi.occupancy(today, plusDays(today, 30)) });
  const bookings = useQuery({ queryKey: ['admin', 'booking-summary'], queryFn: adminApi.bookingSummary });
  const payments = useQuery({ queryKey: ['admin', 'payment-summary'], queryFn: adminApi.paymentSummary });

  if (inventory.isPending || bookings.isPending) return <LoadingBlock label="Loading dashboard…" />;
  if (inventory.isError) return <ErrorState error={inventory.error} onRetry={() => inventory.refetch()} />;
  if (bookings.isError) return <ErrorState error={bookings.error} onRetry={() => bookings.refetch()} />;
  const inv = inventory.data;
  const bk = bookings.data;

  const occupancyData = (occupancy.data ?? []).map((p) => ({ label: formatDate(p.date, 'd MMM'), occupancy: Math.round(p.occupancy * 100), raw: p }));
  const trendData = bk.last30Days.map((d) => ({ label: formatDate(d.day, 'd MMM'), created: d.created, confirmed: d.confirmed }));
  const statusData = bk.byStatus.filter((s) => s.status !== 'CREATED').map((s) => ({ label: bookingStatusLabel[s.status], value: s.count }));

  return (
    <>
      <AdminHeader title="Operational overview" description={`Live figures from the room, booking and payment services · ${formatDate(today)} (hotel time)`} />
      {bk.needsAttention > 0 && (
        <Alert tone="error" title={`${bk.needsAttention} booking(s) need attention`} className="mb-6">
          A compensation step (refund or release) could not complete automatically. Review them under Bookings → “Under review”.
        </Alert>
      )}
      {payments.data && payments.data.outboxDead > 0 && (
        <Alert tone="warning" title="Undelivered payment events" className="mb-6">
          <span className="inline-flex items-center gap-1"><AlertTriangle className="size-4" aria-hidden /> {payments.data.outboxDead} event(s) could not be delivered to the booking service.</span>
        </Alert>
      )}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile label="Occupancy tonight" value={formatPercent(inv.occupancyToday)} emphasis sub={`${inv.activeRooms} sellable rooms`} />
        <StatTile label="Arrivals today" value={inv.arrivalsToday} sub={`${inv.departuresToday} departures`} />
        <StatTile label="Confirmed booking value" value={formatMoney(bk.confirmedRevenue, 'INR', 0)} sub="Currently confirmed stays" />
        <StatTile label="Rooms out of order" value={inv.maintenanceRooms + inv.outOfServiceRooms} sub={`${inv.maintenanceRooms} maintenance · ${inv.outOfServiceRooms} out of service`} />
      </div>

      <div className="mt-6 grid gap-6 xl:grid-cols-2">
        <ChartCard
          title="Occupancy, next 30 nights"
          subtitle="Share of sellable rooms held or confirmed per night"
          table={
            <table className="w-full text-xs"><thead><tr><th className={th}>Night</th><th className={th}>Rooms</th><th className={th}>Occupancy</th></tr></thead>
              <tbody>{occupancyData.map((d) => <tr key={d.label}><td className={td}>{d.label}</td><td className={td}>{d.raw.allocatedRooms}/{d.raw.sellableRooms}</td><td className={td}>{d.occupancy}%</td></tr>)}</tbody></table>
          }
        >
          {occupancy.isError ? <p className="text-sm text-red-700">Occupancy data unavailable.</p> : <OccupancyChart data={occupancyData} />}
        </ChartCard>
        <ChartCard
          title="Bookings, last 30 days"
          subtitle="By creation date (hotel time)"
          table={
            <table className="w-full text-xs"><thead><tr><th className={th}>Day</th><th className={th}>Created</th><th className={th}>Confirmed</th></tr></thead>
              <tbody>{trendData.map((d) => <tr key={d.label}><td className={td}>{d.label}</td><td className={td}>{d.created}</td><td className={td}>{d.confirmed}</td></tr>)}</tbody></table>
          }
        >
          <BookingTrendChart data={trendData} />
        </ChartCard>
        <ChartCard
          title="Bookings by status"
          table={
            <table className="w-full text-xs"><thead><tr><th className={th}>Status</th><th className={th}>Bookings</th><th className={th}>Value</th></tr></thead>
              <tbody>{bk.byStatus.map((s) => <tr key={s.status}><td className={td}>{bookingStatusLabel[s.status]}</td><td className={td}>{s.count}</td><td className={td}>{formatMoney(s.amount, 'INR', 0)}</td></tr>)}</tbody></table>
          }
        >
          {statusData.length ? <HorizontalBars data={statusData} valueName="Bookings" /> : <p className="text-sm text-ink-500">No bookings yet.</p>}
        </ChartCard>
        <section className="card p-5" aria-label="Payments">
          <h2 className="font-sans text-base font-semibold text-ink-900">Payments (simulated provider)</h2>
          {payments.isPending && <LoadingBlock />}
          {payments.isError && <p className="text-sm text-red-700">Payment data unavailable.</p>}
          {payments.data && (
            <>
              <div className="mt-4 grid grid-cols-2 gap-4">
                <div><p className="text-xs text-ink-500">Captured</p><p className="text-2xl font-semibold tabular-nums">{formatMoney(payments.data.capturedAmount, 'INR', 0)}</p></div>
                <div><p className="text-xs text-ink-500">Refunded</p><p className="text-2xl font-semibold tabular-nums">{formatMoney(payments.data.refundedAmount, 'INR', 0)}</p></div>
              </div>
              <table className="mt-4 w-full text-sm">
                <caption className="sr-only">Payments by status</caption>
                <thead><tr><th className={th}>Status</th><th className={th}>Count</th><th className={th}>Amount</th></tr></thead>
                <tbody>{payments.data.byStatus.map((p) => <tr key={p.status}><td className={td}>{paymentStatusLabel[p.status]}</td><td className={`${td} tabular-nums`}>{p.count}</td><td className={`${td} tabular-nums`}>{formatMoney(p.amount, 'INR', 0)}</td></tr>)}</tbody>
              </table>
              <p className="mt-3 text-xs text-ink-500">Outbox: {payments.data.outboxPending} pending · {payments.data.outboxDead} undeliverable</p>
            </>
          )}
        </section>
      </div>
    </>
  );
}
