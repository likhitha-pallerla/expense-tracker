import Link from "next/link";

import { Card, EmptyState } from "@/components/ui/form";
import { formatMoney, formatSigned } from "@/lib/format";
import type {
  CategorySlice,
  Insights,
  MerchantSlice,
  TrendPoint,
} from "@/lib/types";

/** Steps a YYYY-MM string by whole months without touching the local clock. */
export function shiftMonth(month: string, by: number): string {
  const [year, index] = month.split("-").map(Number);
  const zero = year * 12 + (index - 1) + by;
  return `${String(Math.floor(zero / 12)).padStart(4, "0")}-${String(
    (zero % 12) + 1,
  ).padStart(2, "0")}`;
}

/**
 * How a change should read.
 *
 * Spending less is good news and earning less is bad, so the colour cannot be
 * decided by the sign alone.
 */
function tone(delta: number, lowerIsBetter: boolean): string {
  if (delta === 0) return "text-neutral-500";
  const good = lowerIsBetter ? delta < 0 : delta > 0;
  return good
    ? "text-emerald-600 dark:text-emerald-400"
    : "text-red-600 dark:text-red-400";
}

/**
 * Says how something changed, in words where a percentage would lie.
 *
 * A percentage needs something to be a percentage *of*. Going from nothing to
 * ₹4,000 is not "up 100%" and not "up infinitely" — it is the first time, and
 * that is what it should say.
 */
function changeText(
  percent: number | null,
  current: number,
  previous: number,
): string {
  if (previous === 0 && current === 0) return "same as last month — nothing";
  if (previous === 0) return "nothing last month";
  if (current === 0) return "nothing this month";
  if (percent === null) return "";
  const rounded = Math.round(percent);
  if (rounded === 0) return "about the same as last month";
  return `${rounded > 0 ? "up" : "down"} ${Math.abs(rounded)}% on last month`;
}

export function MonthNav({ insights }: { insights: Insights }) {
  const previous = shiftMonth(insights.month, -1);
  const next = shiftMonth(insights.month, 1);
  const canGoBack =
    insights.earliestMonth === null || previous >= insights.earliestMonth;
  const canGoForward = next <= insights.currentMonth;

  return (
    <div className="flex items-center justify-between gap-4">
      <Step href={`/dashboard?month=${previous}`} enabled={canGoBack}>
        ← Earlier
      </Step>
      <div className="text-center">
        <p className="text-sm font-medium">{insights.label}</p>
        {insights.partial && (
          <p className="text-xs text-neutral-500">
            {insights.daysElapsed} of {insights.daysInMonth} days so far
          </p>
        )}
      </div>
      <Step href={`/dashboard?month=${next}`} enabled={canGoForward}>
        Later →
      </Step>
    </div>
  );
}

function Step({
  href,
  enabled,
  children,
}: {
  href: string;
  enabled: boolean;
  children: React.ReactNode;
}) {
  const shape =
    "rounded-md border px-3 py-1.5 text-sm border-neutral-300 dark:border-neutral-700";

  if (!enabled) {
    return (
      <span className={`${shape} text-neutral-400 dark:text-neutral-600`}>
        {children}
      </span>
    );
  }

  return (
    <Link href={href} className={`${shape} hover:bg-neutral-50 dark:hover:bg-neutral-900`}>
      {children}
    </Link>
  );
}

export function Headline({ insights }: { insights: Insights }) {
  const { totals, previous, currency } = insights;

  return (
    <div className="grid gap-4 sm:grid-cols-3">
      <Card title="Spent">
        <p className="font-mono text-2xl">
          {formatMoney(totals.expense, currency)}
        </p>
        <p className={`mt-1 text-xs ${tone(totals.expense - previous.expense, true)}`}>
          {changeText(insights.expenseChange, totals.expense, previous.expense)}
        </p>
        {insights.partial && (
          <p className="mt-1 text-xs text-neutral-500">
            compared with the first {insights.previousDaysCounted} day
            {insights.previousDaysCounted === 1 ? "" : "s"} of last month
          </p>
        )}
      </Card>

      <Card title="Received">
        <p className="font-mono text-2xl">
          {formatMoney(totals.income, currency)}
        </p>
        <p className={`mt-1 text-xs ${tone(totals.income - previous.income, false)}`}>
          {changeText(insights.incomeChange, totals.income, previous.income)}
        </p>
      </Card>

      <Card title="Left over">
        <p className={`font-mono text-2xl ${tone(totals.net, false)}`}>
          {formatSigned(totals.net, currency)}
        </p>
        <p className="mt-1 text-xs text-neutral-500">
          {totals.count} transaction{totals.count === 1 ? "" : "s"}
          {insights.projectedExpense !== null &&
            ` · on track to spend ${formatMoney(insights.projectedExpense, currency)}`}
        </p>
      </Card>
    </div>
  );
}

export function Breakdown({ insights }: { insights: Insights }) {
  if (insights.categories.length === 0) {
    return (
      <Card title="Where it went">
        <EmptyState>Nothing spent this month.</EmptyState>
      </Card>
    );
  }

  const widest = Math.max(...insights.categories.map((slice) => slice.amount));

  return (
    <Card title="Where it went">
      <ul className="space-y-3">
        {insights.categories.map((slice) => (
          <li key={slice.categoryId ?? slice.name}>
            <div className="flex items-baseline justify-between gap-4 text-sm">
              <span className="truncate">
                {slice.name}
                {slice.count > 0 && (
                  <span className="ml-2 text-xs text-neutral-500">
                    {slice.count}×
                  </span>
                )}
              </span>
              <span className="shrink-0 font-mono">
                {formatMoney(slice.amount, insights.currency)}
                <span className="ml-2 text-xs text-neutral-500">
                  {Math.round(slice.share)}%
                </span>
              </span>
            </div>
            <div className="mt-1 h-1.5 rounded-full bg-neutral-100 dark:bg-neutral-800">
              <div
                className="h-1.5 rounded-full"
                style={{
                  width: `${widest === 0 ? 0 : (slice.amount / widest) * 100}%`,
                  backgroundColor: slice.colour ?? "#a3a3a3",
                }}
              />
            </div>
          </li>
        ))}
      </ul>
    </Card>
  );
}

export function Movers({ insights }: { insights: Insights }) {
  if (insights.movers.length === 0) return null;

  return (
    <Card title="Biggest changes">
      <ul className="space-y-2 text-sm">
        {insights.movers.map((slice) => (
          <MoverRow
            key={slice.categoryId ?? slice.name}
            slice={slice}
            currency={insights.currency}
          />
        ))}
      </ul>
    </Card>
  );
}

function MoverRow({
  slice,
  currency,
}: {
  slice: CategorySlice;
  currency: string;
}) {
  return (
    <li className="flex items-baseline justify-between gap-4">
      <span className="truncate">{slice.name}</span>
      <span className={`shrink-0 font-mono ${tone(slice.delta, true)}`}>
        {slice.delta > 0 ? "+" : "−"}
        {formatMoney(Math.abs(slice.delta), currency)}
        <span className="ml-2 text-xs text-neutral-500">
          {changeText(slice.percentChange, slice.amount, slice.previousAmount)}
        </span>
      </span>
    </li>
  );
}

export function TopMerchants({ insights }: { insights: Insights }) {
  if (insights.merchants.length === 0) return null;

  return (
    <Card title="Who you paid most">
      <ul className="space-y-2 text-sm">
        {insights.merchants.map((merchant: MerchantSlice) => (
          <li
            key={merchant.merchantId ?? merchant.name}
            className="flex items-baseline justify-between gap-4"
          >
            <span className="truncate">
              {merchant.name}
              <span className="ml-2 text-xs text-neutral-500">
                {merchant.count}×
              </span>
            </span>
            <span className="shrink-0 font-mono">
              {formatMoney(merchant.amount, insights.currency)}
            </span>
          </li>
        ))}
      </ul>
    </Card>
  );
}

/**
 * Six months of spending as bars.
 *
 * Months with nothing in them are drawn as empty columns rather than skipped,
 * so a gap reads as "I spent nothing" instead of quietly closing up and
 * implying the months either side were adjacent.
 */
export function Trend({ insights }: { insights: Insights }) {
  const tallest = Math.max(
    ...insights.trend.map((point: TrendPoint) => point.expense),
    0,
  );

  return (
    <Card title="Last six months">
      <div className="flex items-end justify-between gap-2" style={{ height: "8rem" }}>
        {insights.trend.map((point) => {
          const height = tallest === 0 ? 0 : (point.expense / tallest) * 100;
          const current = point.month === insights.month;
          return (
            <div key={point.month} className="flex flex-1 flex-col items-center gap-1">
              <span className="font-mono text-[10px] text-neutral-500">
                {point.expense === 0
                  ? "—"
                  : formatMoney(point.expense, insights.currency)}
              </span>
              <div className="flex w-full flex-1 items-end">
                <div
                  className={`w-full rounded-t ${
                    current
                      ? "bg-neutral-800 dark:bg-neutral-200"
                      : "bg-neutral-300 dark:bg-neutral-700"
                  }`}
                  style={{ height: `${Math.max(height, point.expense > 0 ? 2 : 0)}%` }}
                />
              </div>
              <span className="text-[10px] text-neutral-500">{point.label}</span>
            </div>
          );
        })}
      </div>
      {insights.partial && (
        <p className="mt-2 text-xs text-neutral-500">
          The last bar covers only the part of {insights.label} that has
          happened.
        </p>
      )}
    </Card>
  );
}

export function Caveats({ insights }: { insights: Insights }) {
  const notes = [];

  if (insights.mixedCurrencies) {
    notes.push(
      "This month holds more than one currency. The totals add them together as if they were the same, so treat them as a rough guide.",
    );
  }

  if (insights.uncategorisedAmount > 0) {
    notes.push(
      `${formatMoney(insights.uncategorisedAmount, insights.currency)} is not in any category yet.`,
    );
  }

  if (notes.length === 0) return null;

  return (
    <div className="space-y-2">
      {notes.map((note) => (
        <p key={note} className="text-xs text-neutral-500">
          {note}
          {note.includes("category") && (
            <>
              {" "}
              <Link href="/transactions" className="underline underline-offset-4">
                Sort them out
              </Link>
              .
            </>
          )}
        </p>
      ))}
    </div>
  );
}
