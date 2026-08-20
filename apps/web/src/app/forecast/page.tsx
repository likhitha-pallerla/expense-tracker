import Link from "next/link";

import { AppShell } from "@/components/app-shell";
import { Card, EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import { formatMoney, formatSigned } from "@/lib/format";
import type { ExpectedCharge, Forecast } from "@/lib/types";

export const metadata = { title: "Coming up" };

type SearchParams = Record<string, string | string[] | undefined>;

const HORIZONS = [
  { days: 7, label: "7 days" },
  { days: 30, label: "30 days" },
  { days: 60, label: "60 days" },
  { days: 92, label: "3 months" },
];

function daysParam(params: SearchParams): number {
  const value = params.days;
  const parsed = typeof value === "string" ? Number(value) : NaN;
  return Number.isFinite(parsed) ? parsed : 30;
}

/** "in 3 days", said the way a person would say it. */
function when(daysAway: number): string {
  if (daysAway <= 0) return "today";
  if (daysAway === 1) return "tomorrow";
  if (daysAway < 7) return `in ${daysAway} days`;
  if (daysAway < 14) return "next week";
  return `in ${Math.round(daysAway / 7)} weeks`;
}

function shortDate(iso: string): string {
  return new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "short",
  }).format(new Date(`${iso}T00:00:00`));
}

export default async function ForecastPage({
  searchParams,
}: {
  searchParams: Promise<SearchParams>;
}) {
  const params = await searchParams;

  let forecast: Forecast | null = null;
  let error: string | null = null;

  try {
    await apiFetch("/api/me");
    forecast = await apiFetch<Forecast>(`/api/forecast?days=${daysParam(params)}`);
  } catch (caught) {
    error = (caught as Error).message;
  }

  if (error || !forecast) {
    return (
      <AppShell title="Coming up">
        <Card title="Backend API">
          <p className="text-sm text-red-600">Could not reach the API: {error}</p>
        </Card>
      </AppShell>
    );
  }

  const data = forecast;

  return (
    <AppShell
      title="Coming up"
      description={`What your balance does between now and ${shortDate(data.end)}.`}
    >
      <div className="space-y-4">
        <Horizons active={data.days} />

        {data.basedOn === 0 ? (
          <Card title="Nothing to go on yet">
            <EmptyState>
              A forecast needs to know what repeats. Nothing is confirmed as
              recurring yet, so the line below is flat because there is no
              information — not because the month is calm.
            </EmptyState>
            <p className="mt-4 text-sm">
              <Link href="/recurring" className="underline underline-offset-4">
                Confirm your subscriptions and bills
              </Link>{" "}
              and this becomes useful.
            </p>
          </Card>
        ) : (
          <Verdict data={data} />
        )}

        <div className="grid gap-4 sm:grid-cols-3">
          <Card title="Balance now">
            <p className="font-mono text-2xl">
              {formatMoney(data.balanceToday, data.currency)}
            </p>
            <p className="mt-1 text-xs text-neutral-500">
              Across every open account
            </p>
          </Card>

          <Card title="Safe to spend">
            <p className="font-mono text-2xl">
              {formatMoney(data.safeToSpend, data.currency)}
            </p>
            <p className="mt-1 text-xs text-neutral-500">
              Without dipping below zero before {shortDate(data.end)}
            </p>
          </Card>

          <Card title={`In ${data.days} days`}>
            <p className="font-mono text-2xl">
              {formatMoney(data.projectedBalance, data.currency)}
            </p>
            <p className="mt-1 text-xs text-neutral-500">
              {formatMoney(data.expectedIn, data.currency)} in,{" "}
              {formatMoney(data.expectedOut, data.currency)} out
            </p>
          </Card>
        </div>

        <BalanceLine data={data} />
        <Upcoming data={data} />
        <Suspected data={data} />
        <Caveats data={data} />
      </div>
    </AppShell>
  );
}

function Horizons({ active }: { active: number }) {
  return (
    <div className="flex flex-wrap gap-2">
      {HORIZONS.map((horizon) => (
        <Link
          key={horizon.days}
          href={`/forecast?days=${horizon.days}`}
          className={`rounded-md border px-3 py-1.5 text-sm ${
            horizon.days === active
              ? "border-neutral-900 bg-neutral-900 text-white dark:border-neutral-100 dark:bg-neutral-100 dark:text-neutral-900"
              : "border-neutral-300 hover:bg-neutral-50 dark:border-neutral-700 dark:hover:bg-neutral-900"
          }`}
        >
          {horizon.label}
        </Link>
      ))}
    </div>
  );
}

/**
 * The one sentence worth reading.
 *
 * A month-end balance can look healthy while hiding a week where everything
 * lands at once, so this leads with the low point rather than the total.
 */
function Verdict({ data }: { data: Forecast }) {
  if (data.low.goesNegative) {
    return (
      <Card title="You will run short">
        <p className="text-sm">
          On <strong>{shortDate(data.low.date)}</strong> ({when(data.low.daysAway)})
          you are {formatMoney(data.low.shortfall, data.currency)} short, going by
          what is already committed.
        </p>
        <p className="mt-2 text-xs text-neutral-500">
          Moving or cancelling something before then is what fixes it — money
          arriving afterwards will not.
        </p>
      </Card>
    );
  }

  if (!data.low.isAhead) {
    return (
      <Card title="Nothing dips">
        <p className="text-sm">
          Nothing between now and {shortDate(data.end)} takes your balance below
          where it is today.
        </p>
      </Card>
    );
  }

  return (
    <Card title="Your tightest day">
      <p className="text-sm">
        <strong>{shortDate(data.low.date)}</strong> ({when(data.low.daysAway)}) is
        when you are lowest, at{" "}
        <strong>{formatMoney(data.low.balance, data.currency)}</strong>.
      </p>
      {data.unpredicted > 0 && (
        <p className="mt-2 text-xs text-neutral-500">
          You normally spend about{" "}
          {formatMoney(data.unpredicted, data.currency)} a day beyond your
          regular bills, which is roughly{" "}
          {formatMoney(data.unpredicted * data.low.daysAway, data.currency)}{" "}
          before then. That is not in the figure above.
        </p>
      )}
    </Card>
  );
}

function BalanceLine({ data }: { data: Forecast }) {
  const balances = data.line.map((day) => day.balance);
  const top = Math.max(...balances, 0);
  const bottom = Math.min(...balances, 0);
  const span = top - bottom || 1;

  return (
    <Card title="Your balance, day by day">
      <div className="flex items-end gap-px" style={{ height: "7rem" }}>
        {data.line.map((day) => {
          const height = ((day.balance - bottom) / span) * 100;
          const lowest = day.date === data.low.date;
          return (
            <div
              key={day.date}
              className="flex-1"
              title={`${shortDate(day.date)}: ${formatSigned(day.balance, data.currency)}`}
            >
              <div
                className={`w-full rounded-t ${
                  day.balance < 0
                    ? "bg-red-500"
                    : lowest
                      ? "bg-amber-500"
                      : "bg-neutral-300 dark:bg-neutral-700"
                }`}
                style={{ height: `${Math.max(height, 2)}%` }}
              />
            </div>
          );
        })}
      </div>
      <div className="mt-2 flex justify-between text-xs text-neutral-500">
        <span>{shortDate(data.today)}</span>
        <span>{shortDate(data.end)}</span>
      </div>
      {bottom < 0 && (
        <p className="mt-2 text-xs text-red-600 dark:text-red-400">
          The red days are below zero.
        </p>
      )}
    </Card>
  );
}

function Upcoming({ data }: { data: Forecast }) {
  if (data.upcoming.length === 0) {
    return (
      <Card title="What is committed">
        <EmptyState>Nothing regular is due in this window.</EmptyState>
      </Card>
    );
  }

  return (
    <Card
      title="What is committed"
      action={
        <Link
          href="/recurring"
          className="text-sm text-neutral-500 underline underline-offset-4"
        >
          Manage
        </Link>
      }
    >
      <ul className="divide-y divide-neutral-200 dark:divide-neutral-800">
        {data.upcoming.map((charge, index) => (
          <ChargeRow
            key={`${charge.seriesId}-${charge.expectedOn}-${index}`}
            charge={charge}
            currency={data.currency}
          />
        ))}
      </ul>
    </Card>
  );
}

function Suspected({ data }: { data: Forecast }) {
  if (data.suspected.length === 0) return null;

  return (
    <Card
      title="These might also happen"
      action={
        <Link
          href="/recurring"
          className="text-sm text-neutral-500 underline underline-offset-4"
        >
          Confirm or dismiss
        </Link>
      }
    >
      <p className="mb-3 text-xs text-neutral-500">
        Patterns we spotted but you have not confirmed. They are deliberately
        left out of every figure above — being told you owe money you do not is
        worse than being told nothing.
      </p>
      <ul className="divide-y divide-neutral-200 dark:divide-neutral-800">
        {data.suspected.map((charge, index) => (
          <ChargeRow
            key={`${charge.seriesId}-${charge.expectedOn}-${index}`}
            charge={charge}
            currency={data.currency}
          />
        ))}
      </ul>
    </Card>
  );
}

function ChargeRow({
  charge,
  currency,
}: {
  charge: ExpectedCharge;
  currency: string;
}) {
  return (
    <li className="flex items-center justify-between gap-4 py-2 text-sm">
      <div className="min-w-0">
        <p className="truncate">
          {charge.name}
          {charge.overdue && (
            <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-xs text-amber-800 dark:bg-amber-900 dark:text-amber-200">
              late
            </span>
          )}
        </p>
        <p className="text-xs text-neutral-500">
          {shortDate(charge.expectedOn)} · {when(charge.daysAway)} ·{" "}
          {charge.cadence}
          {charge.categoryName && ` · ${charge.categoryName}`}
        </p>
      </div>
      <span
        className={`shrink-0 font-mono ${
          charge.isIncome
            ? "text-emerald-600 dark:text-emerald-400"
            : "text-neutral-900 dark:text-neutral-100"
        }`}
      >
        {charge.isIncome ? "+" : "−"}
        {formatMoney(charge.amount, charge.currency || currency)}
        {charge.amountVaries && (
          <span className="ml-1 text-xs text-neutral-500">approx</span>
        )}
      </span>
    </li>
  );
}

function Caveats({ data }: { data: Forecast }) {
  const notes: string[] = [];

  if (!data.hasAccounts) {
    notes.push(
      "There are no accounts yet, so the starting balance is zero and the line above is only the shape of your commitments.",
    );
  }

  if (data.mixedCurrencies) {
    notes.push(
      "Some of these are in another currency. They are added together as if they were the same, so treat the totals as a rough guide.",
    );
  }

  if (data.basedOn > 0) {
    notes.push(
      `This rests on ${data.basedOn} confirmed recurring ${
        data.basedOn === 1 ? "payment" : "payments"
      }. Day-to-day spending is deliberately not guessed at.`,
    );
  }

  if (notes.length === 0) return null;

  return (
    <div className="space-y-1">
      {notes.map((note) => (
        <p key={note} className="text-xs text-neutral-500">
          {note}
        </p>
      ))}
    </div>
  );
}
