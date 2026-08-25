"use client";

import { useActionState, useEffect, useRef, useState } from "react";
import { useFormStatus } from "react-dom";

import { confirmEntry, idleEntry, readEntry } from "@/lib/actions/entry";
import { formatMoney } from "@/lib/format";
import type { Account, Category, EntrySuggestion } from "@/lib/types";
import { Button, Card, Field, FormMessage, Input, Select } from "@/components/ui/form";

/**
 * Typing a payment the way you would say it.
 *
 * <p>Two steps, always. What the sentence was read as is shown before anything
 * is filed, because "500 mom" is a gift or a repayment or a transfer and no
 * reader can tell which. The confirmation is one keypress away — the button is
 * focused as soon as the reading appears — so the cost of the safety is close
 * to nothing, and the cost of getting it wrong is a transaction the user has to
 * find and fix later.
 */

function ReadButton() {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" disabled={pending}>
      {pending ? "Reading…" : "Read it"}
    </Button>
  );
}

function ConfirmButton({ innerRef }: { innerRef: React.Ref<HTMLButtonElement> }) {
  const { pending } = useFormStatus();
  return (
    <Button ref={innerRef} type="submit" disabled={pending}>
      {pending ? "Adding…" : "Add it"}
    </Button>
  );
}

export function QuickAdd({
  accounts,
  categories,
  currency,
}: {
  accounts: Account[];
  categories: Category[];
  currency: string;
}) {
  const [readState, read] = useActionState(readEntry, idleEntry);
  const [confirmState, confirm] = useActionState(confirmEntry, idleEntry);
  const [dismissed, setDismissed] = useState(false);
  const typedRef = useRef<HTMLInputElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);

  const suggestion = dismissed ? undefined : readState.suggestion;

  // A new reading arrives: put the cursor on "Add it" so confirming is a
  // keypress rather than a reach for the mouse.
  useEffect(() => {
    if (readState.suggestion) {
      setDismissed(false);
      confirmRef.current?.focus();
    }
  }, [readState.suggestion]);

  // Filed successfully: clear the box and go back to the start, ready for the
  // next one. People add several at a time.
  useEffect(() => {
    if (confirmState.ok) {
      setDismissed(true);
      if (typedRef.current) typedRef.current.value = "";
      typedRef.current?.focus();
    }
  }, [confirmState.ok]);

  return (
    <Card title="Add in words">
      <form action={read} className="flex gap-2">
        <Input
          ref={typedRef}
          name="text"
          placeholder="spent 850 on dinner at Zomato using HDFC card"
          defaultValue={readState.typed ?? ""}
          autoComplete="off"
          maxLength={500}
          aria-label="Describe the payment"
        />
        <ReadButton />
      </form>

      {!suggestion && (
        <p className="mt-2 text-xs text-neutral-500">
          Amount is the only part we need. Everything else is a bonus.
        </p>
      )}

      {!suggestion && !readState.ok && <div className="mt-3"><FormMessage state={readState} /></div>}
      {confirmState.ok && !suggestion && (
        <div className="mt-3">
          <FormMessage state={confirmState} />
        </div>
      )}

      {suggestion && (
        <Confirmation
          suggestion={suggestion}
          accounts={accounts}
          categories={categories}
          currency={currency}
          action={confirm}
          state={confirmState}
          confirmRef={confirmRef}
          onCancel={() => setDismissed(true)}
        />
      )}
    </Card>
  );
}

/**
 * What we think you said, with every part of it editable.
 *
 * <p>Editable rather than read-only because a reading that is nearly right is
 * the common case, and a user who has to retype the whole sentence to change
 * one word will stop using the box. The fields that were understood are filled
 * in; the ones that were not are empty and normal-looking rather than flagged
 * as failures.
 */
function Confirmation({
  suggestion,
  accounts,
  categories,
  currency,
  action,
  state,
  confirmRef,
  onCancel,
}: {
  suggestion: EntrySuggestion;
  accounts: Account[];
  categories: Category[];
  currency: string;
  action: (payload: FormData) => void;
  state: { ok: boolean; message?: string; fields?: Record<string, string> };
  confirmRef: React.Ref<HTMLButtonElement>;
  onCancel: () => void;
}) {
  const errors = state.fields ?? {};
  const amount = suggestion.amount ?? 0;

  return (
    <form action={action} className="mt-4 space-y-3 border-t border-neutral-200 pt-4 dark:border-neutral-800">
      <p className="text-sm">
        <span className="font-medium">
          {suggestion.direction === "credit" ? "Received" : "Spent"}{" "}
          {formatMoney(amount, currency)}
        </span>
        {suggestion.merchant ? ` at ${suggestion.merchant}` : ""}
        {suggestion.dateExplicit && suggestion.occurredOn
          ? ` on ${suggestion.occurredOn}`
          : " today"}
        .
      </p>

      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Amount" name="amount" error={errors.amount}>
          <Input
            name="amount"
            type="number"
            step="0.01"
            min="0.01"
            defaultValue={amount}
            required
          />
        </Field>

        <Field label="Date" name="occurredOn" error={errors.occurredAt}>
          <Input
            name="occurredOn"
            type="date"
            defaultValue={suggestion.occurredOn ?? ""}
            required
          />
        </Field>

        <Field label="Direction" name="direction">
          <Select name="direction" defaultValue={suggestion.direction ?? "debit"}>
            <option value="debit">Money out</option>
            <option value="credit">Money in</option>
          </Select>
        </Field>

        <Field label="Description" name="description">
          <Input
            name="description"
            defaultValue={suggestion.description ?? ""}
            maxLength={200}
          />
        </Field>

        <Field
          label="Account"
          name="accountId"
          hint={
            !suggestion.accountId && suggestion.accountHint
              ? `Couldn't match "${suggestion.accountHint}" to one account.`
              : undefined
          }
        >
          <Select name="accountId" defaultValue={suggestion.accountId ?? ""}>
            <option value="">No account</option>
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
              </option>
            ))}
          </Select>
        </Field>

        <Field
          label="Category"
          name="categoryId"
          hint={
            !suggestion.categoryId && suggestion.categoryHint
              ? `No category called "${suggestion.categoryHint}".`
              : undefined
          }
        >
          <Select name="categoryId" defaultValue={suggestion.categoryId ?? ""}>
            <option value="">Uncategorised</option>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </Select>
        </Field>
      </div>

      <input type="hidden" name="currency" value={currency} />
      <input type="hidden" name="merchant" value={suggestion.merchant ?? ""} />

      <FormMessage state={state} />

      <div className="flex items-center gap-2">
        <ConfirmButton innerRef={confirmRef} />
        <Button type="button" variant="secondary" onClick={onCancel}>
          Cancel
        </Button>
        {suggestion.source === "ai" && (
          <span className="text-xs text-neutral-500">
            Read by AI — worth a glance.
          </span>
        )}
      </div>
    </form>
  );
}
