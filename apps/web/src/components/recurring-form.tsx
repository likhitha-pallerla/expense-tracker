"use client";

import { useActionState, useEffect } from "react";
import { useFormStatus } from "react-dom";

import {
  confirmRecurring,
  dismissRecurring,
  forgetRecurring,
  saveRecurring,
} from "@/lib/actions/recurring";
import { idleState } from "@/lib/actions/form-state";
import { CADENCES, type Category, type Recurring } from "@/lib/types";
import {
  Button,
  Field,
  FormMessage,
  Input,
  Select,
  Textarea,
} from "@/components/ui/form";

function SubmitButton({ label, busy }: { label: string; busy: string }) {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" disabled={pending}>
      {pending ? busy : label}
    </Button>
  );
}

function CategoryOptions({ categories }: { categories: Category[] }) {
  return (
    <>
      <option value="">Uncategorised</option>
      {categories.map((category) => (
        <option key={category.id} value={category.id}>
          {category.parent_id ? "— " : ""}
          {category.name}
        </option>
      ))}
    </>
  );
}

/**
 * Adding or editing a payment by hand.
 *
 * <p>The same form serves both, because a payment typed in before its first
 * charge should behave exactly like one that was detected.
 */
export function RecurringForm({
  payment,
  categories,
  onDone,
}: {
  payment?: Recurring;
  categories: Category[];
  onDone?: () => void;
}) {
  const [state, action] = useActionState(saveRecurring, idleState);
  const errors = state.fields ?? {};

  useEffect(() => {
    if (state.ok) onDone?.();
  }, [state, onDone]);

  return (
    <form action={action} className="space-y-4">
      {payment?.id && <input type="hidden" name="id" value={payment.id} />}

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Name" name="name" error={errors.name}>
          <Input
            name="name"
            required
            maxLength={120}
            defaultValue={payment?.name ?? ""}
            placeholder="Netflix"
            error={errors.name}
          />
        </Field>

        <Field
          label="Amount"
          name="amount"
          error={errors.amount}
          hint="What it costs each time."
        >
          <Input
            name="amount"
            type="number"
            step="0.01"
            min="0.01"
            defaultValue={payment?.latestAmount ?? ""}
            placeholder="499"
            error={errors.amount}
          />
        </Field>

        <Field label="How often" name="cadence" error={errors.cadence}>
          <Select
            name="cadence"
            defaultValue={payment?.cadence ?? "monthly"}
            error={errors.cadence}
          >
            {CADENCES.map((cadence) => (
              <option key={cadence.value} value={cadence.value}>
                {cadence.label}
              </option>
            ))}
          </Select>
        </Field>

        <Field
          label="Next charge"
          name="nextExpected"
          error={errors.nextExpected}
          hint="Left blank, this is worked out from the charges we can see."
        >
          <Input
            name="nextExpected"
            type="date"
            defaultValue={payment?.nextExpected ?? ""}
            error={errors.nextExpected}
          />
        </Field>

        <Field label="Category" name="categoryId" error={errors.categoryId}>
          <Select
            name="categoryId"
            defaultValue={payment?.categoryId ?? ""}
            error={errors.categoryId}
          >
            <CategoryOptions categories={categories} />
          </Select>
        </Field>
      </div>

      <Field label="Notes" name="notes" error={errors.notes}>
        <Textarea
          name="notes"
          rows={2}
          maxLength={500}
          defaultValue={payment?.notes ?? ""}
          placeholder="Shared with two other people"
          error={errors.notes}
        />
      </Field>

      <div className="space-y-2">
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            name="isSubscription"
            defaultChecked={payment?.isSubscription ?? true}
            className="h-4 w-4 rounded border-neutral-300"
          />
          Count this towards what my subscriptions cost
        </label>

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            name="isActive"
            defaultChecked={payment?.isActive ?? true}
            className="h-4 w-4 rounded border-neutral-300"
          />
          Still running
        </label>
      </div>

      <FormMessage state={state} />

      <div className="flex gap-2">
        <SubmitButton
          label={payment?.id ? "Save changes" : "Add payment"}
          busy="Saving…"
        />
        {onDone && (
          <Button type="button" variant="secondary" onClick={onDone}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}

/** Accepts a suggestion, optionally under a tidier name. */
export function ConfirmForm({
  payment,
  categories,
  onDone,
}: {
  payment: Recurring;
  categories: Category[];
  onDone?: () => void;
}) {
  const [state, action] = useActionState(confirmRecurring, idleState);
  const errors = state.fields ?? {};

  useEffect(() => {
    if (state.ok) onDone?.();
  }, [state, onDone]);

  return (
    <form action={action} className="space-y-4">
      <input type="hidden" name="matchKey" value={payment.matchKey} />

      <div className="grid gap-4 sm:grid-cols-2">
        <Field
          label="Call it"
          name="name"
          error={errors.name}
          hint="Bank descriptions are rarely readable."
        >
          <Input
            name="name"
            required
            maxLength={120}
            defaultValue={payment.name}
            error={errors.name}
          />
        </Field>

        <Field label="Category" name="categoryId" error={errors.categoryId}>
          <Select name="categoryId" defaultValue={payment.categoryId ?? ""}>
            <CategoryOptions categories={categories} />
          </Select>
        </Field>
      </div>

      <FormMessage state={state} />

      <div className="flex gap-2">
        <SubmitButton label="Track it" busy="Saving…" />
        {onDone && (
          <Button type="button" variant="secondary" onClick={onDone}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}

export function DismissButton({ payment }: { payment: Recurring }) {
  const [state, action] = useActionState(dismissRecurring, idleState);

  if (state.message && !state.ok) {
    return <p className="text-xs text-red-600">{state.message}</p>;
  }

  return (
    <form action={action}>
      <input type="hidden" name="matchKey" value={payment.matchKey} />
      <Button type="submit" variant="secondary">
        {payment.direction === "credit" ? "Not recurring" : "Not a subscription"}
      </Button>
    </form>
  );
}

export function ForgetButton({
  payment,
  label,
}: {
  payment: Recurring;
  label: string;
}) {
  const [state, action] = useActionState(forgetRecurring, idleState);

  if (state.message && !state.ok) {
    return <p className="text-xs text-red-600">{state.message}</p>;
  }

  return (
    <form action={action}>
      <input type="hidden" name="id" value={payment.id ?? ""} />
      <Button type="submit" variant="secondary">
        {label}
      </Button>
    </form>
  );
}
