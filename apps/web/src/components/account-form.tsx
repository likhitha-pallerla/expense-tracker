"use client";

import { useActionState, useEffect, useRef, useState } from "react";
import { useFormStatus } from "react-dom";

import { removeAccount, saveAccount } from "@/lib/actions/accounts";
import { idleState } from "@/lib/actions/form-state";
import {
  ACCOUNT_TYPES,
  ACCOUNT_TYPE_LABELS,
  type Account,
} from "@/lib/types";
import {
  Button,
  Field,
  FormMessage,
  Input,
  Select,
} from "@/components/ui/form";

function SubmitButton({ label }: { label: string }) {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" disabled={pending}>
      {pending ? "Saving…" : label}
    </Button>
  );
}

export function AccountForm({
  account,
  onDone,
}: {
  account?: Account;
  onDone?: () => void;
}) {
  const [state, action] = useActionState(saveAccount, idleState);
  const formRef = useRef<HTMLFormElement>(null);
  const errors = state.fields ?? {};

  useEffect(() => {
    if (!state.ok) return;
    // Only a create should clear the form; an edit keeps showing what was saved.
    if (!account) formRef.current?.reset();
    onDone?.();
  }, [state, account, onDone]);

  return (
    <form ref={formRef} action={action} className="space-y-4">
      {account && <input type="hidden" name="id" value={account.id} />}

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Name" name="name" error={errors.name}>
          <Input
            name="name"
            required
            maxLength={120}
            defaultValue={account?.name}
            placeholder="HDFC Savings"
            error={errors.name}
          />
        </Field>

        <Field label="Type" name="type" error={errors.type}>
          <Select name="type" defaultValue={account?.type ?? "bank"} error={errors.type}>
            {ACCOUNT_TYPES.map((type) => (
              <option key={type} value={type}>
                {ACCOUNT_TYPE_LABELS[type]}
              </option>
            ))}
          </Select>
        </Field>

        <Field
          label="Opening balance"
          name="openingBalance"
          error={errors.openingBalance}
          hint="Negative is fine — a credit card starts out owing money."
        >
          <Input
            name="openingBalance"
            type="number"
            step="0.01"
            defaultValue={account?.openingBalance ?? 0}
            error={errors.openingBalance}
          />
        </Field>

        <Field
          label="Last 4 digits"
          name="last4"
          error={errors.last4}
          hint="Optional. Helps match bank alerts to this account."
        >
          <Input
            name="last4"
            inputMode="numeric"
            pattern="[0-9]{4}"
            maxLength={4}
            defaultValue={account?.last4 ?? ""}
            placeholder="4321"
            error={errors.last4}
          />
        </Field>
      </div>

      {account ? (
        <>
          <input type="hidden" name="currency" value={account.currency} />
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              name="isArchived"
              defaultChecked={account.isArchived}
              className="h-4 w-4"
            />
            Archived
          </label>
          <p className="text-xs text-neutral-500">
            Currency is fixed at {account.currency}. Existing transactions were
            recorded in it, so changing it would misstate past totals.
          </p>
        </>
      ) : (
        <Field label="Currency" name="currency" error={errors.currency}>
          <Input
            name="currency"
            maxLength={3}
            defaultValue="INR"
            className="uppercase"
            error={errors.currency}
          />
        </Field>
      )}

      <FormMessage state={state} />

      <div className="flex gap-2">
        <SubmitButton label={account ? "Save changes" : "Add account"} />
        {onDone && (
          <Button type="button" variant="secondary" onClick={onDone}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}

/** Delete, with a confirmation step so a stray click cannot remove an account. */
export function DeleteAccountButton({ account }: { account: Account }) {
  const [state, action] = useActionState(removeAccount, idleState);
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
      <input type="hidden" name="id" value={account.id} />
      <span className="text-xs text-neutral-500">Delete {account.name}?</span>
      <Button type="submit" variant="danger">
        Yes
      </Button>
      <Button type="button" variant="secondary" onClick={() => setConfirming(false)}>
        No
      </Button>
    </form>
  );
}
