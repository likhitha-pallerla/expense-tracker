"use client";

import { useActionState, useEffect, useRef } from "react";
import { useFormStatus } from "react-dom";

import { removeGoal, saveGoal } from "@/lib/actions/goals";
import { idleState } from "@/lib/actions/form-state";
import type { Account, Goal } from "@/lib/types";
import {
  Button,
  Field,
  FormMessage,
  Input,
  Select,
  Textarea,
} from "@/components/ui/form";

const STATUSES = [
  { value: "active", label: "Saving toward it" },
  { value: "paused", label: "Paused" },
  { value: "achieved", label: "Reached" },
  { value: "cancelled", label: "Given up on" },
];

function SubmitButton({ label }: { label: string }) {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" disabled={pending}>
      {pending ? "Saving…" : label}
    </Button>
  );
}

export function GoalForm({
  goal,
  accounts,
  currency,
  onDone,
}: {
  goal?: Goal;
  accounts: Account[];
  currency: string;
  onDone?: () => void;
}) {
  const [state, action] = useActionState(saveGoal, idleState);
  const formRef = useRef<HTMLFormElement>(null);
  const errors = state.fields ?? {};

  useEffect(() => {
    if (!state.ok) return;
    if (!goal) formRef.current?.reset();
    onDone?.();
  }, [state, goal, onDone]);

  return (
    <form ref={formRef} action={action} className="space-y-4">
      {goal && <input type="hidden" name="id" value={goal.id} />}
      <input type="hidden" name="currency" value={goal?.currency ?? currency} />

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="What for" name="name" error={errors.name}>
          <Input
            name="name"
            required
            maxLength={120}
            defaultValue={goal?.name}
            placeholder="Emergency fund"
            error={errors.name}
          />
        </Field>

        <Field label="How much" name="targetAmount" error={errors.targetAmount}>
          <Input
            name="targetAmount"
            type="number"
            step="0.01"
            min="0.01"
            required
            defaultValue={goal?.targetAmount}
            placeholder="120000"
            error={errors.targetAmount}
          />
        </Field>

        <Field
          label="By when"
          name="targetDate"
          error={errors.targetDate}
          hint="Optional. Without a date there is progress but no deadline, and nothing here will call you behind."
        >
          <Input
            name="targetDate"
            type="date"
            defaultValue={goal?.targetDate ?? ""}
            error={errors.targetDate}
          />
        </Field>

        <Field
          label="Planned each month"
          name="monthlyTarget"
          error={errors.monthlyTarget}
          hint="Optional. If it is not enough to hit the date, you will be told."
        >
          <Input
            name="monthlyTarget"
            type="number"
            step="0.01"
            min="0.01"
            defaultValue={goal?.monthlyTarget ?? ""}
            placeholder="10000"
            error={errors.monthlyTarget}
          />
        </Field>

        <Field
          label="Kept in"
          name="accountId"
          error={errors.accountId}
          hint="For your reference. Progress is tracked here, not read from the account."
        >
          <Select
            name="accountId"
            defaultValue={goal?.accountId ?? ""}
            error={errors.accountId}
          >
            <option value="">Not linked</option>
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
              </option>
            ))}
          </Select>
        </Field>

        <Field label="Status" name="status" error={errors.status}>
          <Select
            name="status"
            defaultValue={goal?.status ?? "active"}
            error={errors.status}
          >
            {STATUSES.map((status) => (
              <option key={status.value} value={status.value}>
                {status.label}
              </option>
            ))}
          </Select>
        </Field>
      </div>

      <Field label="Notes" name="notes" error={errors.notes}>
        <Textarea
          name="notes"
          rows={2}
          maxLength={2000}
          defaultValue={goal?.notes ?? ""}
          placeholder="Six months of expenses"
          error={errors.notes}
        />
      </Field>

      <FormMessage state={state} />

      <div className="flex gap-2">
        <SubmitButton label={goal ? "Save changes" : "Create goal"} />
        {onDone && (
          <Button type="button" variant="secondary" onClick={onDone}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}

export function DeleteGoalButton({ goal }: { goal: Goal }) {
  const [state, action] = useActionState(removeGoal, idleState);
  const { pending } = useFormStatus();

  return (
    <form
      action={action}
      onSubmit={(event) => {
        if (
          !confirm(
            `Delete "${goal.name}"? Its contribution history goes too. Your transactions are not touched.`,
          )
        ) {
          event.preventDefault();
        }
      }}
    >
      <input type="hidden" name="id" value={goal.id} />
      <Button type="submit" variant="danger" disabled={pending}>
        Delete
      </Button>
      {!state.ok && state.message && (
        <p className="mt-1 text-xs text-red-600">{state.message}</p>
      )}
    </form>
  );
}
