"use client";

import { useActionState, useEffect, useState } from "react";
import { useFormStatus } from "react-dom";

import { clearCard, saveCard } from "@/lib/actions/cards";
import { idleState } from "@/lib/actions/form-state";
import type { Card as CreditCard } from "@/lib/types";
import { Button, Field, FormMessage, Input } from "@/components/ui/form";

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" disabled={pending}>
      {pending ? "Saving…" : "Save details"}
    </Button>
  );
}

export function CardForm({
  card,
  onDone,
}: {
  card: CreditCard;
  onDone?: () => void;
}) {
  const [state, action] = useActionState(saveCard, idleState);
  const errors = state.fields ?? {};

  useEffect(() => {
    if (state.ok) onDone?.();
  }, [state, onDone]);

  return (
    <form action={action} className="space-y-4">
      <input type="hidden" name="accountId" value={card.accountId} />

      <div className="grid gap-4 sm:grid-cols-2">
        <Field
          label="Credit limit"
          name="creditLimit"
          error={errors.creditLimit}
          hint="Used to work out how much of the card is in use."
        >
          <Input
            name="creditLimit"
            type="number"
            step="0.01"
            min="0.01"
            defaultValue={card.creditLimit ?? ""}
            placeholder="200000"
            error={errors.creditLimit}
          />
        </Field>

        <Field
          label="Statement day"
          name="billingDay"
          error={errors.billingDay}
          hint="Day of the month the bill is generated. Short months clamp to the last day."
        >
          <Input
            name="billingDay"
            type="number"
            min="1"
            max="31"
            defaultValue={card.billingDay ?? ""}
            placeholder="5"
            error={errors.billingDay}
          />
        </Field>

        <Field
          label="Payment due day"
          name="dueDay"
          error={errors.dueDay}
          hint="Day of the month payment is due."
        >
          <Input
            name="dueDay"
            type="number"
            min="1"
            max="31"
            defaultValue={card.dueDay ?? ""}
            placeholder="25"
            error={errors.dueDay}
          />
        </Field>

        <Field
          label="Last statement date"
          name="lastStatementAt"
          error={errors.lastStatementAt}
          hint="Optional. Lets the app work out what is still unpaid."
        >
          <Input
            name="lastStatementAt"
            type="date"
            defaultValue={card.lastStatementAt ?? ""}
            error={errors.lastStatementAt}
          />
        </Field>

        <Field
          label="Statement balance"
          name="statementBalance"
          error={errors.statementBalance}
          hint="What that statement said you owed."
        >
          <Input
            name="statementBalance"
            type="number"
            step="0.01"
            min="0"
            defaultValue={card.statementBalance ?? ""}
            error={errors.statementBalance}
          />
        </Field>

        <Field
          label="Minimum due"
          name="minimumDue"
          error={errors.minimumDue}
          hint="The smallest payment that avoids a late fee."
        >
          <Input
            name="minimumDue"
            type="number"
            step="0.01"
            min="0"
            defaultValue={card.minimumDue ?? ""}
            error={errors.minimumDue}
          />
        </Field>
      </div>

      <p className="text-xs text-neutral-500">
        These are the bank&apos;s figures. What you owe right now is worked out
        from your transactions and is not editable here.
      </p>

      <FormMessage state={state} />

      <div className="flex gap-2">
        <SubmitButton />
        {onDone && (
          <Button type="button" variant="secondary" onClick={onDone}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}

/** Clears the bank-supplied details; the account and its transactions stay. */
export function ClearCardButton({ card }: { card: CreditCard }) {
  const [state, action] = useActionState(clearCard, idleState);
  const [confirming, setConfirming] = useState(false);

  if (state.message) {
    return <p className="text-xs text-neutral-500">{state.message}</p>;
  }

  if (!confirming) {
    return (
      <Button variant="danger" onClick={() => setConfirming(true)}>
        Clear details
      </Button>
    );
  }

  return (
    <form action={action} className="flex items-center gap-2">
      <input type="hidden" name="accountId" value={card.accountId} />
      <span className="text-xs text-neutral-500">
        Clear the limit and dates? Transactions are kept.
      </span>
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
