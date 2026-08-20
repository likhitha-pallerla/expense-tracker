"use client";

import { useState } from "react";

import { CardForm, ClearCardButton } from "@/components/card-form";
import { Button, Card, EmptyState } from "@/components/ui/form";
import { formatDate, formatMoney } from "@/lib/format";
import type { Card as CreditCard, CardStatus } from "@/lib/types";

const STATUS: Record<CardStatus, { label: string; chip: string }> = {
  clear: {
    label: "Nothing owed",
    chip: "bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300",
  },
  tracking: {
    label: "No statement yet",
    chip: "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
  },
  paid: {
    label: "Statement paid",
    chip: "bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300",
  },
  minimum_met: {
    label: "Minimum paid",
    chip: "bg-sky-50 text-sky-700 dark:bg-sky-950 dark:text-sky-300",
  },
  due: {
    label: "Payment due",
    chip: "bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300",
  },
  overdue: {
    label: "Overdue",
    chip: "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300",
  },
};

/** Above 30% starts hurting a credit score, so the bar warns well before the limit. */
function utilisationColour(percent: number): string {
  if (percent >= 75) return "bg-red-500";
  if (percent >= 30) return "bg-amber-500";
  return "bg-emerald-500";
}

function Figure({
  label,
  value,
  tone = "",
}: {
  label: string;
  value: string;
  tone?: string;
}) {
  return (
    <div>
      <p className="text-xs uppercase tracking-wide text-neutral-500">{label}</p>
      <p className={`font-mono text-sm ${tone}`}>{value}</p>
    </div>
  );
}

function CardRow({ card }: { card: CreditCard }) {
  const [editing, setEditing] = useState(false);
  const look = STATUS[card.status] ?? STATUS.tracking;

  if (editing) {
    return (
      <Card title={`Edit ${card.name}`}>
        <CardForm card={card} onDone={() => setEditing(false)} />
      </Card>
    );
  }

  const due = card.daysUntilDue;

  return (
    <Card>
      <div className="space-y-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p className="font-medium">
              {card.name}
              {card.last4 && (
                <span className="text-neutral-400"> ••{card.last4}</span>
              )}
            </p>
            {card.dueDate ? (
              <p className="text-sm text-neutral-500">
                Due {formatDate(card.dueDate)}
                {due !== null && due >= 0 && (
                  <> · {due === 0 ? "today" : `in ${due} day${due === 1 ? "" : "s"}`}</>
                )}
                {due !== null && due < 0 && (
                  <> · {Math.abs(due)} day{Math.abs(due) === 1 ? "" : "s"} ago</>
                )}
              </p>
            ) : (
              <p className="text-sm text-neutral-500">
                Add a statement and due day to track payments.
              </p>
            )}
          </div>

          <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${look.chip}`}>
            {look.label}
          </span>
        </div>

        {card.creditLimit !== null && card.utilisation !== null && (
          <div>
            <div className="mb-1 flex items-baseline justify-between gap-3 text-sm">
              <span className="font-mono">
                {formatMoney(card.outstanding, card.currency)}
                <span className="text-neutral-500">
                  {" "}
                  of {formatMoney(card.creditLimit, card.currency)}
                </span>
              </span>
              <span className="text-neutral-500">{card.utilisation}% used</span>
            </div>
            <div
              role="progressbar"
              aria-valuenow={Math.round(card.utilisation)}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-label={`${card.name} credit used`}
              className="h-2 w-full overflow-hidden rounded-full bg-neutral-200 dark:bg-neutral-800"
            >
              <div
                className={`h-full rounded-full transition-all ${utilisationColour(card.utilisation)}`}
                style={{ width: `${Math.min(card.utilisation, 100)}%` }}
              />
            </div>
          </div>
        )}

        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Figure
            label="Outstanding"
            value={formatMoney(card.outstanding, card.currency)}
            tone={card.outstanding > 0 ? "text-red-600 dark:text-red-400" : ""}
          />
          {card.available !== null && (
            <Figure
              label="Available"
              value={formatMoney(card.available, card.currency)}
            />
          )}
          {card.currentSpend !== null && (
            <Figure
              label="This cycle"
              value={formatMoney(card.currentSpend, card.currency)}
            />
          )}
          {card.remainingDue !== null && (
            <Figure
              label="Still to pay"
              value={formatMoney(card.remainingDue, card.currency)}
              tone={card.remainingDue > 0 ? "text-amber-700 dark:text-amber-400" : ""}
            />
          )}
        </div>

        {card.minimumRemaining !== null && card.minimumRemaining > 0 && (
          <p className="text-xs text-amber-700 dark:text-amber-400">
            Pay at least {formatMoney(card.minimumRemaining, card.currency)}
            {card.dueDate ? ` by ${formatDate(card.dueDate)}` : ""} to avoid a
            late fee.
          </p>
        )}

        <div className="flex items-center gap-2">
          <Button variant="secondary" onClick={() => setEditing(true)}>
            Edit details
          </Button>
          {card.creditLimit !== null || card.billingDay !== null ? (
            <ClearCardButton card={card} />
          ) : null}
        </div>
      </div>
    </Card>
  );
}

export function CardsView({ cards }: { cards: CreditCard[] }) {
  if (cards.length === 0) {
    return (
      <EmptyState>
        No credit cards yet. Add an account with the type &ldquo;Credit
        card&rdquo; and it will show up here.
      </EmptyState>
    );
  }

  return (
    <ul className="space-y-3">
      {cards.map((card) => (
        <li key={card.accountId}>
          <CardRow card={card} />
        </li>
      ))}
    </ul>
  );
}
