"use client";

import { useState } from "react";

import {
  ConfirmForm,
  DismissButton,
  ForgetButton,
  RecurringForm,
} from "@/components/recurring-form";
import { Button, Card, EmptyState } from "@/components/ui/form";
import { formatDate, formatMoney } from "@/lib/format";
import type { Category, Recurring, RecurringStatus } from "@/lib/types";

const STATUS: Record<RecurringStatus, { label: string; chip: string }> = {
  active: {
    label: "Running",
    chip: "bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300",
  },
  due_today: {
    label: "Charging today",
    chip: "bg-sky-50 text-sky-700 dark:bg-sky-950 dark:text-sky-300",
  },
  due_soon: {
    label: "Due soon",
    chip: "bg-sky-50 text-sky-700 dark:bg-sky-950 dark:text-sky-300",
  },
  overdue: {
    label: "Hasn't arrived",
    chip: "bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300",
  },
  ended: {
    label: "Looks cancelled",
    chip: "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
  },
  paused: {
    label: "Paused",
    chip: "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
  },
  dismissed: {
    label: "Dismissed",
    chip: "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
  },
};

const CADENCE_LABEL: Record<string, string> = {
  weekly: "a week",
  fortnightly: "a fortnight",
  monthly: "a month",
  quarterly: "a quarter",
  half_yearly: "six months",
  yearly: "a year",
};

function whenNext(payment: Recurring): string {
  if (payment.nextExpected === null) return "No date expected";
  const days = payment.daysUntilNext;
  const on = formatDate(payment.nextExpected);
  if (days === null) return `Next ${on}`;
  if (days === 0) return `Next today, ${on}`;
  if (days > 0) return `Next in ${days} day${days === 1 ? "" : "s"}, ${on}`;
  const late = Math.abs(days);
  return `Expected ${on}, ${late} day${late === 1 ? "" : "s"} ago`;
}

function PaymentRow({
  payment,
  categories,
}: {
  payment: Recurring;
  categories: Category[];
}) {
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const look = STATUS[payment.status] ?? STATUS.active;
  const suggested = payment.state === "suggested";
  const income = payment.direction === "credit";

  if (editing) {
    return (
      <Card title={`Edit ${payment.name}`}>
        <RecurringForm
          payment={payment}
          categories={categories}
          onDone={() => setEditing(false)}
        />
      </Card>
    );
  }

  if (confirming) {
    return (
      <Card title="Track this payment">
        <ConfirmForm
          payment={payment}
          categories={categories}
          onDone={() => setConfirming(false)}
        />
      </Card>
    );
  }

  return (
    <Card>
      <div className="space-y-3">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="flex flex-wrap items-center gap-2 font-medium">
              {payment.name}
              {suggested && (
                <span className="rounded-full bg-violet-50 px-2 py-0.5 text-xs font-medium text-violet-700 dark:bg-violet-950 dark:text-violet-300">
                  Suggested
                </span>
              )}
              {payment.priceChanged && (
                <span className="rounded-full bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-950 dark:text-amber-300">
                  Price changed
                </span>
              )}
            </p>
            <p className="text-sm text-neutral-500">{whenNext(payment)}</p>
          </div>

          <div className="text-right">
            <p className="font-mono text-lg">
              {formatMoney(payment.latestAmount, payment.currency)}
              <span className="text-sm text-neutral-500">
                {" "}
                / {CADENCE_LABEL[payment.cadence] ?? payment.cadence}
              </span>
            </p>
            <span
              className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${look.chip}`}
            >
              {look.label}
            </span>
          </div>
        </div>

        {payment.priceChanged && (
          <p className="text-xs text-amber-700 dark:text-amber-400">
            Was {formatMoney(payment.typicalAmount, payment.currency)}, now{" "}
            {formatMoney(payment.latestAmount, payment.currency)}.
          </p>
        )}

        <div className="flex flex-wrap gap-x-6 gap-y-1 text-xs text-neutral-500">
          <span>
            {income ? "Received" : "Costs"}{" "}
            <span className="font-mono text-neutral-700 dark:text-neutral-300">
              {formatMoney(payment.monthlyCost, payment.currency)}
            </span>{" "}
            a month
          </span>
          <span>
            <span className="font-mono text-neutral-700 dark:text-neutral-300">
              {formatMoney(payment.yearlyCost, payment.currency)}
            </span>{" "}
            a year
          </span>
          {payment.categoryName && <span>{payment.categoryName}</span>}
          {payment.accountName && <span>{payment.accountName}</span>}
          {payment.lastCharge && (
            <span>Last charged {formatDate(payment.lastCharge)}</span>
          )}
        </div>

        {payment.reasons.length > 0 && (
          <details className="text-xs">
            <summary className="cursor-pointer text-neutral-500 hover:text-neutral-800 dark:hover:text-neutral-200">
              Why this is here
            </summary>
            <ul className="mt-2 space-y-1 text-neutral-600 dark:text-neutral-400">
              {payment.reasons.map((reason) => (
                <li key={reason}>· {reason}</li>
              ))}
            </ul>
          </details>
        )}

        {payment.notes && (
          <p className="text-xs text-neutral-500">{payment.notes}</p>
        )}

        <div className="flex flex-wrap items-center gap-2">
          {suggested ? (
            <>
              <Button onClick={() => setConfirming(true)}>Track it</Button>
              <DismissButton payment={payment} />
            </>
          ) : payment.state === "dismissed" ? (
            <ForgetButton payment={payment} label="Undo dismissal" />
          ) : (
            <>
              <Button variant="secondary" onClick={() => setEditing(true)}>
                Edit
              </Button>
              <ForgetButton payment={payment} label="Stop tracking" />
            </>
          )}
        </div>
      </div>
    </Card>
  );
}

function Section({
  title,
  description,
  payments,
  categories,
}: {
  title: string;
  description: string;
  payments: Recurring[];
  categories: Category[];
}) {
  if (payments.length === 0) return null;

  return (
    <section className="space-y-3">
      <div>
        <h2 className="text-sm font-semibold uppercase tracking-wide text-neutral-500">
          {title}
        </h2>
        <p className="text-xs text-neutral-500">{description}</p>
      </div>
      <ul className="space-y-3">
        {payments.map((payment) => (
          <li key={payment.id ?? payment.matchKey}>
            <PaymentRow payment={payment} categories={categories} />
          </li>
        ))}
      </ul>
    </section>
  );
}

export function RecurringView({
  payments,
  categories,
  showingDismissed,
}: {
  payments: Recurring[];
  categories: Category[];
  showingDismissed: boolean;
}) {
  const [adding, setAdding] = useState(false);

  const tracked = payments.filter(
    (p) => p.state === "confirmed" && p.direction === "debit",
  );
  const suggested = payments.filter(
    (p) => p.state === "suggested" && p.direction === "debit",
  );
  // Money coming in is grouped by what it is rather than by whether it has
  // been confirmed: a salary sitting in a list headed "Suggested", under a
  // button reading "not a subscription", asks the wrong question entirely.
  const income = payments.filter(
    (p) => p.direction === "credit" && p.state !== "dismissed",
  );
  const dismissed = payments.filter((p) => p.state === "dismissed");

  return (
    <div className="space-y-8">
      {adding ? (
        <Card title="Add a recurring payment">
          <RecurringForm
            categories={categories}
            onDone={() => setAdding(false)}
          />
        </Card>
      ) : (
        <Button onClick={() => setAdding(true)}>Add a payment</Button>
      )}

      {payments.length === 0 && (
        <EmptyState>
          Nothing recurring yet. Once the same merchant has charged you three
          times on a regular rhythm it will be suggested here — or add one
          yourself.
        </EmptyState>
      )}

      <Section
        title="Tracked"
        description="Payments you have confirmed."
        payments={tracked}
        categories={categories}
      />

      <Section
        title="Suggested"
        description="Found in your transactions. Nothing is saved until you say so."
        payments={suggested}
        categories={categories}
      />

      <Section
        title="Regular income"
        description="Money that arrives on a rhythm."
        payments={income}
        categories={categories}
      />
      {showingDismissed && (
        <Section
          title="Dismissed"
          description="Hidden from suggestions. Undo to bring one back."
          payments={dismissed}
          categories={categories}
        />
      )}
    </div>
  );
}
