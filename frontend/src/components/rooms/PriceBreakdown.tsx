import type { NightlyRate } from '../../lib/api/types';
import { formatDate, formatMoney } from '../../lib/format';

interface Props {
  currency: string;
  nightlyRates: NightlyRate[];
  subtotal: number;
  serviceFee: number;
  taxes: number;
  total: number;
  serviceFeeRate?: number;
  taxRate?: number;
  pricingPolicyVersion?: number;
  compact?: boolean;
}

function explain(r: NightlyRate) {
  const parts: string[] = [];
  if (r.dayMultiplier !== 1) parts.push(`weekend ×${r.dayMultiplier}`);
  if (r.seasonName) parts.push(`${r.seasonName} ×${r.seasonMultiplier}`);
  if (r.demandMultiplier !== 1) parts.push(`demand ×${r.demandMultiplier}`);
  if (r.limitApplied) parts.push(r.limitApplied === 'MAX' ? 'capped at maximum' : 'raised to minimum');
  return parts.join(' · ');
}

/** Itemised price exactly as calculated by the backend pricing engine. */
export function PriceBreakdown({ currency, nightlyRates, subtotal, serviceFee, taxes, total, serviceFeeRate, taxRate, pricingPolicyVersion, compact }: Props) {
  return (
    <div className="text-sm">
      {!compact && (
        <table className="w-full">
          <caption className="sr-only">Nightly rates</caption>
          <thead>
            <tr className="text-left text-xs uppercase tracking-wider text-ink-500">
              <th scope="col" className="pb-2 font-semibold">Night</th>
              <th scope="col" className="pb-2 text-right font-semibold">Rate</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {nightlyRates.map((r) => (
              <tr key={r.date}>
                <td className="py-2">
                  <span className="text-ink-800">{formatDate(r.date, 'EEE d MMM')}</span>
                  {explain(r) && <span className="block text-xs text-ink-500">{explain(r)}</span>}
                </td>
                <td className="py-2 text-right tabular-nums text-ink-800">{formatMoney(r.rate, currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <dl className="mt-3 space-y-1.5 border-t border-ink-100 pt-3">
        <div className="flex justify-between">
          <dt className="text-ink-600">Room subtotal ({nightlyRates.length} night{nightlyRates.length > 1 ? 's' : ''})</dt>
          <dd className="tabular-nums">{formatMoney(subtotal, currency)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-ink-600">Service fee{serviceFeeRate !== undefined ? ` (${Math.round(serviceFeeRate * 100)}%)` : ''}</dt>
          <dd className="tabular-nums">{formatMoney(serviceFee, currency)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-ink-600">Taxes{taxRate !== undefined ? ` (${Math.round(taxRate * 100)}%)` : ''}</dt>
          <dd className="tabular-nums">{formatMoney(taxes, currency)}</dd>
        </div>
        <div className="flex justify-between border-t border-ink-200 pt-2 text-base font-semibold">
          <dt>Total</dt>
          <dd className="tabular-nums">{formatMoney(total, currency)}</dd>
        </div>
      </dl>
      {pricingPolicyVersion !== undefined && (
        <p className="mt-2 text-xs text-ink-400">Priced with pricing policy v{pricingPolicyVersion}. Amounts calculated by the server.</p>
      )}
    </div>
  );
}
