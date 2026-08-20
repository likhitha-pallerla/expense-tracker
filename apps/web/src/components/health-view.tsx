import { formatDate } from "@/lib/format";
import type { HealthBand, HealthGrade, HealthReport, HealthSignal } from "@/lib/types";

import { Card, EmptyState } from "@/components/ui/form";

const GRADE: Record<HealthGrade, { label: string; text: string; ring: string }> = {
  strong: {
    label: "Strong",
    text: "text-emerald-700 dark:text-emerald-300",
    ring: "border-emerald-500",
  },
  good: {
    label: "Good",
    text: "text-sky-700 dark:text-sky-300",
    ring: "border-sky-500",
  },
  fair: {
    label: "Fair",
    text: "text-amber-700 dark:text-amber-300",
    ring: "border-amber-500",
  },
  needs_work: {
    label: "Needs work",
    text: "text-orange-700 dark:text-orange-300",
    ring: "border-orange-500",
  },
  at_risk: {
    label: "At risk",
    text: "text-red-700 dark:text-red-300",
    ring: "border-red-500",
  },
  unrated: {
    label: "Not yet rated",
    text: "text-neutral-600 dark:text-neutral-400",
    ring: "border-neutral-300 dark:border-neutral-700",
  },
};

const BAND: Record<HealthBand, { label: string; bar: string; chip: string }> = {
  strong: {
    label: "Strong",
    bar: "bg-emerald-500",
    chip: "bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300",
  },
  good: {
    label: "Good",
    bar: "bg-sky-500",
    chip: "bg-sky-50 text-sky-700 dark:bg-sky-950 dark:text-sky-300",
  },
  fair: {
    label: "Fair",
    bar: "bg-amber-500",
    chip: "bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300",
  },
  weak: {
    label: "Weak",
    bar: "bg-orange-500",
    chip: "bg-orange-50 text-orange-700 dark:bg-orange-950 dark:text-orange-300",
  },
  poor: {
    label: "Poor",
    bar: "bg-red-500",
    chip: "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300",
  },
  unknown: {
    label: "Not measured",
    bar: "bg-neutral-300 dark:bg-neutral-700",
    chip: "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
  },
};

function measurement(signal: HealthSignal): string | null {
  if (signal.value === null) return null;
  switch (signal.unit) {
    case "percent":
      return `${signal.value}%`;
    case "months":
      return signal.value === 1 ? "1 month" : `${signal.value} months`;
    default:
      return String(signal.value);
  }
}

function SignalCard({ signal }: { signal: HealthSignal }) {
  const look = BAND[signal.band] ?? BAND.unknown;
  const reading = measurement(signal);

  return (
    <Card>
      <div className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h3 className="text-sm font-semibold">{signal.label}</h3>
          <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${look.chip}`}>
            {look.label}
          </span>
        </div>

        <div className="flex items-baseline gap-3">
          <p className="font-mono text-2xl">
            {signal.score === null ? "—" : signal.score}
          </p>
          {reading && (
            <p className="font-mono text-sm text-neutral-500">{reading}</p>
          )}
          {signal.weight > 0 && (
            <p className="ml-auto text-xs text-neutral-500">
              {signal.weight}% of your score
            </p>
          )}
        </div>

        <div
          className="h-1.5 w-full overflow-hidden rounded-full bg-neutral-100 dark:bg-neutral-900"
          role="img"
          aria-label={
            signal.score === null
              ? `${signal.label} could not be measured`
              : `${signal.label} scores ${signal.score} out of 100`
          }
        >
          <div
            className={`h-full rounded-full ${look.bar}`}
            style={{ width: `${signal.score ?? 100}%` }}
          />
        </div>

        <p className="text-sm">{signal.finding}</p>
        <p className="text-sm text-neutral-500">{signal.action}</p>
      </div>
    </Card>
  );
}

/**
 * The score, and everything it was built from.
 *
 * <p>The drivers are always all shown, including the ones that could not be
 * measured. Hiding them would leave the user unable to work out why the number
 * is what it is, and the gaps are usually the most useful thing on the page.
 */
export function HealthView({ report }: { report: HealthReport }) {
  const grade = GRADE[report.grade] ?? GRADE.unrated;
  const measured = report.signals.filter((signal) => signal.score !== null);
  const unmeasured = report.signals.filter((signal) => signal.score === null);

  return (
    <div className="space-y-8">
      <Card>
        <div className="flex flex-wrap items-center gap-6">
          <div
            className={`flex h-28 w-28 shrink-0 flex-col items-center justify-center rounded-full border-4 ${grade.ring}`}
          >
            <span className="font-mono text-4xl leading-none">
              {report.score ?? "—"}
            </span>
            {report.score !== null && (
              <span className="text-xs text-neutral-500">out of 100</span>
            )}
          </div>

          <div className="min-w-56 flex-1 space-y-1">
            <p className={`text-lg font-semibold ${grade.text}`}>{grade.label}</p>
            <p className="text-sm text-neutral-600 dark:text-neutral-400">
              {report.headline}
            </p>
            {report.monthsObserved > 0 && (
              <p className="text-xs text-neutral-500">
                Based on {report.monthsObserved}{" "}
                {report.monthsObserved === 1 ? "month" : "months"} to{" "}
                {formatDate(report.windowEnd)}. This month is still running, so it
                is not counted yet.
              </p>
            )}
          </div>
        </div>
      </Card>

      {report.priorities.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-sm font-semibold">Do this next</h2>
          <p className="text-xs text-neutral-500">
            Ordered by how much each one would move your score, not by which
            number looks worst.
          </p>
          <ol className="space-y-2">
            {report.priorities.map((priority, index) => (
              <li
                key={priority}
                className="flex gap-3 rounded-lg border border-neutral-200 bg-white px-4 py-3 text-sm dark:border-neutral-800 dark:bg-neutral-950"
              >
                <span className="font-mono text-neutral-400">{index + 1}</span>
                <span>{priority}</span>
              </li>
            ))}
          </ol>
        </section>
      )}

      {measured.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-sm font-semibold">What the score is made of</h2>
          <div className="grid gap-4 sm:grid-cols-2">
            {measured.map((signal) => (
              <SignalCard key={signal.key} signal={signal} />
            ))}
          </div>
        </section>
      )}

      {report.wins.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-sm font-semibold">Going well</h2>
          <ul className="space-y-2">
            {report.wins.map((win) => (
              <li
                key={win}
                className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-300"
              >
                {win}
              </li>
            ))}
          </ul>
        </section>
      )}

      {report.missing.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-sm font-semibold">
            {report.score === null ? "What is needed to score you" : "Not measured yet"}
          </h2>
          {report.score !== null && report.coverage < 100 && (
            <p className="text-xs text-neutral-500">
              These are left out of the score rather than counted as zero, so
              nothing here is dragging your number down. Filling them in makes it
              a fuller picture.
            </p>
          )}
          <ul className="space-y-2">
            {report.missing.map((gap) => (
              <li
                key={gap}
                className="rounded-lg border border-dashed border-neutral-300 px-4 py-3 text-sm text-neutral-600 dark:border-neutral-700 dark:text-neutral-400"
              >
                {gap}
              </li>
            ))}
          </ul>
        </section>
      )}

      {unmeasured.length > 0 && report.score !== null && (
        <section className="space-y-3">
          <h2 className="text-sm font-semibold">Drivers not counted</h2>
          <div className="grid gap-4 sm:grid-cols-2">
            {unmeasured.map((signal) => (
              <SignalCard key={signal.key} signal={signal} />
            ))}
          </div>
        </section>
      )}

      {report.signals.length === 0 && report.missing.length === 0 && (
        <EmptyState>Nothing to score yet.</EmptyState>
      )}
    </div>
  );
}
