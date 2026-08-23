"use client";

import { useActionState, useEffect, useState } from "react";
import { useFormStatus } from "react-dom";

import { DeleteGoalButton, GoalForm } from "@/components/goal-form";
import { contribute, removeContribution } from "@/lib/actions/goals";
import { idleState } from "@/lib/actions/form-state";
import { Button, Card, EmptyState, Field, FormMessage, Input, Select } from "@/components/ui/form";
import { formatDate, formatMoney } from "@/lib/format";
import type { Account, Goal, GoalProgress } from "@/lib/types";

/**
 * The colour of the progress bar.
 *
 * Grey when there is no verdict to give. Colouring an unknowable state green or
 * red would be inventing an opinion out of a default value, and someone glancing
 * at the page reads colour long before they read words.
 */
function barColour(progress: GoalProgress, status: Goal["status"]): string {
  if (progress.achieved) return "bg-emerald-500";
  if (status === "cancelled" || status === "paused") return "bg-neutral-400";
  if (progress.overdue) return "bg-red-500";
  if (progress.onTrack === true) return "bg-emerald-500";
  if (progress.onTrack === false) return "bg-amber-500";
  return "bg-neutral-400";
}

function chipStyle(progress: GoalProgress, status: Goal["status"]): string {
  if (progress.achieved) {
    return "bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300";
  }
  if (progress.overdue) {
    return "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300";
  }
  if (progress.onTrack === false) {
    return "bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300";
  }
  if (status === "paused" || status === "cancelled") {
    return "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400";
  }
  return "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400";
}

function chipLabel(progress: GoalProgress, status: Goal["status"]): string {
  if (progress.achieved) return "Reached";
  if (status === "cancelled") return "Given up";
  if (status === "paused") return "Paused";
  if (progress.overdue) return "Date passed";
  if (progress.onTrack === true) return "On track";
  if (progress.onTrack === false) return "Behind";
  return "No verdict yet";
}

function SubmitButton({ label }: { label: string }) {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" disabled={pending}>
      {pending ? "Saving…" : label}
    </Button>
  );
}

function ContributeForm({ goal, onDone }: { goal: Goal; onDone: () => void }) {
  const [state, action] = useActionState(contribute, idleState);

  // Closing has to happen in an effect. Calling onDone() during render updates
  // the parent mid-render, which React rejects outright.
  useEffect(() => {
    if (state.ok) onDone();
  }, [state, onDone]);

  return (
    <form action={action} className="space-y-3 border-t border-neutral-200 pt-3 dark:border-neutral-800">
      <input type="hidden" name="goalId" value={goal.id} />

      <div className="grid gap-3 sm:grid-cols-4">
        <Field label="Amount" name="amount">
          <Input
            name="amount"
            type="number"
            step="0.01"
            min="0.01"
            required
            placeholder="5000"
            autoFocus
          />
        </Field>

        <Field label="Direction" name="direction">
          <Select name="direction" defaultValue="in">
            <option value="in">Putting in</option>
            <option value="out">Taking out</option>
          </Select>
        </Field>

        <Field label="When" name="occurredOn">
          <Input name="occurredOn" type="date" />
        </Field>

        <Field label="Note" name="note">
          <Input name="note" maxLength={500} placeholder="Bonus" />
        </Field>
      </div>

      <FormMessage state={state} />

      <div className="flex gap-2">
        <SubmitButton label="Record it" />
        <Button type="button" variant="secondary" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function RemoveContributionButton({
  goalId,
  contributionId,
}: {
  goalId: string;
  contributionId: string;
}) {
  const [, action] = useActionState(removeContribution, idleState);

  return (
    <form action={action}>
      <input type="hidden" name="goalId" value={goalId} />
      <input type="hidden" name="contributionId" value={contributionId} />
      <button
        type="submit"
        className="text-xs text-neutral-500 underline underline-offset-4 hover:text-red-600"
      >
        Remove
      </button>
    </form>
  );
}

/**
 * The numbers under the bar.
 *
 * Every one of these is omitted rather than zeroed when it cannot be computed.
 * A goal with no deadline showing "₹0 needed per month" would read as good news
 * about a question nobody asked.
 */
function Figures({ goal }: { goal: Goal }) {
  const p = goal.progress;
  const items: { label: string; value: string; hint?: string }[] = [];

  items.push({
    label: "Saved",
    value: `${formatMoney(p.saved, goal.currency)} of ${formatMoney(p.target, goal.currency)}`,
  });

  if (!p.achieved) {
    items.push({ label: "To go", value: formatMoney(p.remaining, goal.currency) });
  }

  if (p.requiredPerMonth !== null) {
    items.push({
      label: p.overdue ? "Still needed" : "Needed each month",
      value: formatMoney(p.requiredPerMonth, goal.currency),
      hint: p.overdue ? "The date has already passed" : undefined,
    });
  }

  if (p.actualPerMonth !== null) {
    items.push({
      label: "Going in each month",
      value: formatMoney(p.actualPerMonth, goal.currency),
      hint: "Measured from your first contribution",
    });
  }

  if (p.daysLeft !== null && !p.achieved) {
    items.push({
      label: p.daysLeft < 0 ? "Overdue by" : "Time left",
      value: `${Math.abs(p.daysLeft)} day${Math.abs(p.daysLeft) === 1 ? "" : "s"}`,
    });
  }

  if (p.projectedDate && !p.achieved) {
    items.push({
      label: "At this rate, done",
      value: formatDate(p.projectedDate),
    });
  }

  return (
    <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-3">
      {items.map((item) => (
        <div key={item.label}>
          <dt className="text-xs uppercase tracking-wide text-neutral-500">
            {item.label}
          </dt>
          <dd className="font-mono">{item.value}</dd>
          {item.hint && (
            <dd className="text-xs text-neutral-500">{item.hint}</dd>
          )}
        </div>
      ))}
    </dl>
  );
}

function GoalCard({
  goal,
  accounts,
  currency,
}: {
  goal: Goal;
  accounts: Account[];
  currency: string;
}) {
  const [editing, setEditing] = useState(false);
  const [adding, setAdding] = useState(false);
  const p = goal.progress;

  if (editing) {
    return (
      <Card title={`Edit ${goal.name}`}>
        <GoalForm
          goal={goal}
          accounts={accounts}
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
          <div className="min-w-0">
            <p className="font-medium">{goal.name}</p>
            <p className="text-sm text-neutral-500">
              {goal.targetDate ? `by ${formatDate(goal.targetDate)}` : "no deadline"}
              {goal.accountName && ` · ${goal.accountName}`}
              {goal.achievedAt && ` · reached ${formatDate(goal.achievedAt)}`}
            </p>
          </div>
          <span
            className={`rounded-full px-2 py-0.5 text-xs font-medium ${chipStyle(p, goal.status)}`}
          >
            {chipLabel(p, goal.status)}
          </span>
        </div>

        <div>
          <div className="h-2 w-full overflow-hidden rounded-full bg-neutral-200 dark:bg-neutral-800">
            <div
              className={`h-full rounded-full ${barColour(p, goal.status)}`}
              style={{ width: `${Math.max(p.percent, 0)}%` }}
              role="progressbar"
              aria-valuenow={p.percent}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-label={`${goal.name} progress`}
            />
          </div>
          <p className="mt-1 text-sm">{goal.headline}</p>
        </div>

        <Figures goal={goal} />

        {p.planFallsShort && p.planShortfall !== null && goal.monthlyTarget !== null && (
          <p className="text-xs text-amber-700 dark:text-amber-400">
            Your plan of {formatMoney(goal.monthlyTarget, goal.currency)} a month is{" "}
            {formatMoney(p.planShortfall, goal.currency)} short of what the date needs.
          </p>
        )}

        {goal.notes && (
          <p className="text-sm text-neutral-500">{goal.notes}</p>
        )}

        {goal.contributions.length > 0 && (
          <details className="text-sm">
            <summary className="cursor-pointer text-neutral-500">
              {goal.contributions.length} contribution
              {goal.contributions.length === 1 ? "" : "s"}
            </summary>
            <ul className="mt-2 divide-y divide-neutral-200 dark:divide-neutral-800">
              {goal.contributions.map((item) => (
                <li
                  key={item.id}
                  className="flex items-center justify-between gap-3 py-1.5"
                >
                  <span className="min-w-0 truncate">
                    {formatDate(item.occurredOn)}
                    {item.note && (
                      <span className="text-neutral-500"> · {item.note}</span>
                    )}
                  </span>
                  <span className="flex shrink-0 items-center gap-3">
                    <span
                      className={`font-mono ${
                        item.isWithdrawal
                          ? "text-red-600 dark:text-red-400"
                          : "text-emerald-600 dark:text-emerald-400"
                      }`}
                    >
                      {item.isWithdrawal ? "−" : "+"}
                      {formatMoney(Math.abs(item.amount), goal.currency)}
                    </span>
                    <RemoveContributionButton
                      goalId={goal.id}
                      contributionId={item.id}
                    />
                  </span>
                </li>
              ))}
            </ul>
          </details>
        )}

        {adding ? (
          <ContributeForm goal={goal} onDone={() => setAdding(false)} />
        ) : (
          <div className="flex flex-wrap gap-2">
            {!goal.isCancelled && (
              <Button onClick={() => setAdding(true)}>Add money</Button>
            )}
            <Button variant="secondary" onClick={() => setEditing(true)}>
              Edit
            </Button>
            <DeleteGoalButton goal={goal} />
          </div>
        )}
      </div>
    </Card>
  );
}

export function GoalsView({
  goals,
  accounts,
  currency,
  showClosed,
}: {
  goals: Goal[];
  accounts: Account[];
  currency: string;
  showClosed: boolean;
}) {
  const [creating, setCreating] = useState(false);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        {!creating && (
          <Button onClick={() => setCreating(true)}>New goal</Button>
        )}
        <a
          href={`/goals?includeClosed=${showClosed ? "false" : "true"}`}
          className="text-sm text-neutral-500 underline underline-offset-4"
        >
          {showClosed ? "Hide abandoned goals" : "Show abandoned goals"}
        </a>
      </div>

      {creating && (
        <Card title="New goal">
          <GoalForm
            accounts={accounts}
            currency={currency}
            onDone={() => setCreating(false)}
          />
        </Card>
      )}

      {goals.length === 0 ? (
        <Card>
          <EmptyState>
            No goals yet. A goal is a number and, if you want one, a date — the
            rest is worked out from what you actually put aside.
          </EmptyState>
        </Card>
      ) : (
        goals.map((goal) => (
          <GoalCard
            key={goal.id}
            goal={goal}
            accounts={accounts}
            currency={currency}
          />
        ))
      )}
    </div>
  );
}
