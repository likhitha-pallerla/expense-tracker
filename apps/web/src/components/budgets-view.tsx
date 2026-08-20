"use client";

import { useState } from "react";

import { BudgetForm, DeleteBudgetButton } from "@/components/budget-form";
import { Button, Card, EmptyState } from "@/components/ui/form";
import { formatDate, formatMoney } from "@/lib/format";
import type { Budget, BudgetStatus, Category } from "@/lib/types";

const STATUS: Record<BudgetStatus, { label: string; bar: string; chip: string }> = {
  on_track: {
    label: "On track",
    bar: "bg-emerald-500",
    chip: "bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300",
  },
  warning: {
    label: "Running low",
    bar: "bg-amber-500",
    chip: "bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300",
  },
  over: {
    label: "Over budget",
    bar: "bg-red-500",
    chip: "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300",
  },
  upcoming: {
    label: "Not started",
    bar: "bg-neutral-400",
    chip: "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
  },
  ended: {
    label: "Ended",
    bar: "bg-neutral-400",
    chip: "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
  },
};

function BudgetRow({
  budget,
  categories,
  currency,
}: {
  budget: Budget;
  categories: Category[];
  currency: string;
}) {
  const [editing, setEditing] = useState(false);
  const look = STATUS[budget.status] ?? STATUS.on_track;
  const title = budget.name ?? budget.categoryName ?? "All spending";

  // The bar stops at 100% so an overspend does not overflow its track; the
  // number beside it still says how far past the limit things have gone.
  const filled = Math.min(budget.percentUsed, 100);

  // Only worth flagging while there is still time to act on it.
  const willOverspend =
    budget.status !== "over" &&
    budget.daysRemaining > 0 &&
    budget.projected > budget.limit;

  if (editing) {
    return (
      <Card title={`Edit ${title}`}>
        <BudgetForm
          budget={budget}
          categories={categories}
          currency={currency}
          onDone={() => setEditing(false)}
        />
      </Card>
    );
  }

  return (
    <Card>
      <div className="space-y-3">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p className="font-medium">
              {title}
              {!budget.isActive && (
                <span className="ml-2 rounded bg-neutral-200 px-1.5 py-0.5 text-xs text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400">
                  Paused
                </span>
              )}
            </p>
            <p className="text-sm text-neutral-500">
              {formatDate(budget.periodStart)} – {formatDate(budget.periodEnd)}
              {budget.daysRemaining > 0 && budget.status !== "upcoming" && (
                <> · {budget.daysRemaining} day{budget.daysRemaining === 1 ? "" : "s"} left</>
              )}
            </p>
          </div>

          <span
            className={`rounded-full px-2 py-0.5 text-xs font-medium ${look.chip}`}
          >
            {look.label}
          </span>
        </div>

        <div>
          <div className="mb-1 flex items-baseline justify-between gap-3 text-sm">
            <span className="font-mono">
              {formatMoney(budget.spent, budget.currency)}
              <span className="text-neutral-500">
                {" "}
                of {formatMoney(budget.limit, budget.currency)}
              </span>
            </span>
            <span className="text-neutral-500">{budget.percentUsed}%</span>
          </div>

          <div
            role="progressbar"
            aria-valuenow={Math.round(budget.percentUsed)}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label={`${title} budget used`}
            className="h-2 w-full overflow-hidden rounded-full bg-neutral-200 dark:bg-neutral-800"
          >
            <div
              className={`h-full rounded-full transition-all ${look.bar}`}
              style={{ width: `${filled}%` }}
            />
          </div>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="text-sm">
            {budget.remaining >= 0 ? (
              <span className="text-neutral-600 dark:text-neutral-400">
                {formatMoney(budget.remaining, budget.currency)} left
              </span>
            ) : (
              <span className="text-red-600 dark:text-red-400">
                {formatMoney(Math.abs(budget.remaining), budget.currency)} over
              </span>
            )}
            {budget.carriedOver > 0 && (
              <span className="text-neutral-500">
                {" "}
                · {formatMoney(budget.carriedOver, budget.currency)} carried over
              </span>
            )}
          </div>

          <div className="flex items-center gap-2">
            <Button variant="secondary" onClick={() => setEditing(true)}>
              Edit
            </Button>
            <DeleteBudgetButton budget={budget} />
          </div>
        </div>

        {willOverspend && (
          <p className="text-xs text-amber-700 dark:text-amber-400">
            At this pace you will spend about{" "}
            {formatMoney(budget.projected, budget.currency)} by{" "}
            {formatDate(budget.periodEnd)}.
          </p>
        )}
      </div>
    </Card>
  );
}

export function BudgetsView({
  budgets,
  categories,
  currency,
  showInactive,
}: {
  budgets: Budget[];
  categories: Category[];
  currency: string;
  showInactive: boolean;
}) {
  const [adding, setAdding] = useState(false);

  return (
    <div className="space-y-4">
      {adding ? (
        <Card title="New budget">
          <BudgetForm
            categories={categories}
            currency={currency}
            onDone={() => setAdding(false)}
          />
        </Card>
      ) : (
        <Button onClick={() => setAdding(true)}>Add budget</Button>
      )}

      {budgets.length === 0 ? (
        <EmptyState>
          No budgets yet. Set one on a category you want to keep an eye on.
        </EmptyState>
      ) : (
        <ul className="space-y-3">
          {budgets.map((budget) => (
            <li key={budget.id}>
              <BudgetRow
                budget={budget}
                categories={categories}
                currency={currency}
              />
            </li>
          ))}
        </ul>
      )}

      <p className="text-sm text-neutral-500">
        {showInactive ? (
          <a href="/budgets" className="underline underline-offset-4">
            Hide paused budgets
          </a>
        ) : (
          <a
            href="/budgets?includeInactive=true"
            className="underline underline-offset-4"
          >
            Show paused budgets
          </a>
        )}
      </p>
    </div>
  );
}
