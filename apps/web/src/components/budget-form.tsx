"use client";

import { useActionState, useEffect, useRef, useState } from "react";
import { useFormStatus } from "react-dom";

import { removeBudget, saveBudget } from "@/lib/actions/budgets";
import { idleState } from "@/lib/actions/form-state";
import { BUDGET_PERIODS, type Budget, type Category } from "@/lib/types";
import {
  Button,
  Field,
  FormMessage,
  Input,
  Select,
} from "@/components/ui/form";

/** Percentages worth being told about; anything else can be typed via the API. */
const THRESHOLD_CHOICES = [50, 75, 80, 90, 100];

function SubmitButton({ label }: { label: string }) {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" disabled={pending}>
      {pending ? "Saving…" : label}
    </Button>
  );
}

/** Renders parents first with children indented, so the tree reads as a tree. */
function categoryOptions(categories: Category[]) {
  const children = new Map<string, Category[]>();
  const roots: Category[] = [];

  for (const category of categories) {
    if (category.parent_id) {
      const siblings = children.get(category.parent_id) ?? [];
      siblings.push(category);
      children.set(category.parent_id, siblings);
    } else {
      roots.push(category);
    }
  }

  const options: { id: string; label: string }[] = [];
  for (const root of roots) {
    options.push({ id: root.id, label: root.name });
    for (const child of children.get(root.id) ?? []) {
      options.push({ id: child.id, label: `\u00A0\u00A0\u00A0\u00A0${child.name}` });
    }
  }
  return options;
}

export function BudgetForm({
  budget,
  categories,
  currency,
  onDone,
}: {
  budget?: Budget;
  categories: Category[];
  currency: string;
  onDone?: () => void;
}) {
  const [state, action] = useActionState(saveBudget, idleState);
  const formRef = useRef<HTMLFormElement>(null);
  const errors = state.fields ?? {};

  useEffect(() => {
    if (!state.ok) return;
    if (!budget) formRef.current?.reset();
    onDone?.();
  }, [state, budget, onDone]);

  const active = budget?.alertThresholds ?? [50, 80, 100];
  const options = categoryOptions(categories);

  return (
    <form ref={formRef} action={action} className="space-y-4">
      {budget && <input type="hidden" name="id" value={budget.id} />}

      <div className="grid gap-4 sm:grid-cols-2">
        <Field
          label="Category"
          name="categoryId"
          error={errors.categoryId}
          hint="Leave blank to budget every expense. A parent also covers its sub-categories."
        >
          <Select
            name="categoryId"
            defaultValue={budget?.categoryId ?? ""}
            error={errors.categoryId}
          >
            <option value="">All spending</option>
            {options.map((option) => (
              <option key={option.id} value={option.id}>
                {option.label}
              </option>
            ))}
          </Select>
        </Field>

        <Field label="Amount" name="amount" error={errors.amount}>
          <Input
            name="amount"
            type="number"
            step="0.01"
            min="0.01"
            required
            defaultValue={budget?.amount}
            placeholder="15000"
            error={errors.amount}
          />
        </Field>

        <Field label="Resets" name="period" error={errors.period}>
          <Select
            name="period"
            defaultValue={budget?.period ?? "monthly"}
            error={errors.period}
          >
            {BUDGET_PERIODS.map((period) => (
              <option key={period.value} value={period.value}>
                {period.label}
              </option>
            ))}
          </Select>
        </Field>

        <Field
          label="Starts on"
          name="startsOn"
          error={errors.startsOn}
          hint="Periods run from this date — pick your payday if that is how you think."
        >
          <Input
            name="startsOn"
            type="date"
            defaultValue={budget?.startsOn ?? new Date().toISOString().slice(0, 10)}
            error={errors.startsOn}
          />
        </Field>

        <Field
          label="Name"
          name="name"
          error={errors.name}
          hint="Optional. Defaults to the category name."
        >
          <Input
            name="name"
            maxLength={120}
            defaultValue={budget?.name ?? ""}
            placeholder="Eating out"
            error={errors.name}
          />
        </Field>

        <Field
          label="Ends on"
          name="endsOn"
          error={errors.endsOn}
          hint="Optional. For a budget that only runs for a while."
        >
          <Input
            name="endsOn"
            type="date"
            defaultValue={budget?.endsOn ?? ""}
            error={errors.endsOn}
          />
        </Field>
      </div>

      <input type="hidden" name="currency" value={budget?.currency ?? currency} />

      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">Warn me at</legend>
        <div className="flex flex-wrap gap-3">
          {THRESHOLD_CHOICES.map((threshold) => (
            <label key={threshold} className="flex items-center gap-1.5 text-sm">
              <input
                type="checkbox"
                name="alertThresholds"
                value={threshold}
                defaultChecked={active.includes(threshold)}
                className="h-4 w-4"
              />
              {threshold}%
            </label>
          ))}
        </div>
      </fieldset>

      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          name="rollover"
          defaultChecked={budget?.rollover ?? false}
          className="h-4 w-4"
        />
        Carry unspent money into the next period
      </label>

      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          name="isActive"
          defaultChecked={budget?.isActive ?? true}
          className="h-4 w-4"
        />
        Active
      </label>

      <FormMessage state={state} />

      <div className="flex gap-2">
        <SubmitButton label={budget ? "Save changes" : "Create budget"} />
        {onDone && (
          <Button type="button" variant="secondary" onClick={onDone}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}

/** Delete, behind a confirmation so a stray click cannot drop a budget. */
export function DeleteBudgetButton({ budget }: { budget: Budget }) {
  const [state, action] = useActionState(removeBudget, idleState);
  const [confirming, setConfirming] = useState(false);

  if (state.message) {
    return <p className="text-xs text-neutral-500">{state.message}</p>;
  }

  if (!confirming) {
    return (
      <Button variant="danger" onClick={() => setConfirming(true)}>
        Delete
      </Button>
    );
  }

  return (
    <form action={action} className="flex items-center gap-2">
      <input type="hidden" name="id" value={budget.id} />
      <span className="text-xs text-neutral-500">Delete this budget?</span>
      <Button type="submit" variant="danger">
        Yes
      </Button>
      <Button
        type="button"
        variant="secondary"
        onClick={() => setConfirming(false)}
      >
        No
      </Button>
    </form>
  );
}
