"use client";

import { useActionState, useEffect, useRef, useState } from "react";
import { useFormStatus } from "react-dom";

import {
  removeTransaction,
  saveTransaction,
  saveTransfer,
} from "@/lib/actions/transactions";
import { idleState } from "@/lib/actions/form-state";
import { todayDateTimeLocal, toDateTimeLocal } from "@/lib/format";
import type { Account, Category, Transaction } from "@/lib/types";
import {
  Button,
  Field,
  FormMessage,
  Input,
  Select,
  Textarea,
} from "@/components/ui/form";

function SubmitButton({ label }: { label: string }) {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" disabled={pending}>
      {pending ? "Saving…" : label}
    </Button>
  );
}

function CategoryOptions({ categories }: { categories: Category[] }) {
  const roots = categories.filter((category) => !category.parent_id);
  const childrenOf = (id: string) =>
    categories.filter((category) => category.parent_id === id);

  return (
    <>
      <option value="">Uncategorised</option>
      {roots.map((root) => {
        const children = childrenOf(root.id);
        return children.length === 0 ? (
          <option key={root.id} value={root.id}>
            {root.name}
          </option>
        ) : (
          <optgroup key={root.id} label={root.name}>
            <option value={root.id}>{root.name} (general)</option>
            {children.map((child) => (
              <option key={child.id} value={child.id}>
                {child.name}
              </option>
            ))}
          </optgroup>
        );
      })}
    </>
  );
}

export function TransactionForm({
  accounts,
  categories,
  transaction,
  onDone,
}: {
  accounts: Account[];
  categories: Category[];
  transaction?: Transaction;
  onDone?: () => void;
}) {
  const [state, action] = useActionState(saveTransaction, idleState);
  const formRef = useRef<HTMLFormElement>(null);
  const errors = state.fields ?? {};

  useEffect(() => {
    if (!state.ok) return;
    if (!transaction) formRef.current?.reset();
    onDone?.();
  }, [state, transaction, onDone]);

  return (
    <form ref={formRef} action={action} className="space-y-4">
      {transaction && <input type="hidden" name="id" value={transaction.id} />}

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Type" name="kind" error={errors.kind}>
          <Select
            name="kind"
            defaultValue={transaction?.kind ?? "expense"}
            error={errors.kind}
          >
            <option value="expense">Expense</option>
            <option value="income">Income</option>
          </Select>
        </Field>

        <Field label="Amount" name="amount" error={errors.amount}>
          <Input
            name="amount"
            type="number"
            step="0.01"
            min="0"
            required
            defaultValue={transaction?.amount}
            placeholder="0.00"
            error={errors.amount}
          />
        </Field>

        <Field label="Date & time" name="occurredAt" error={errors.occurredAt}>
          <Input
            name="occurredAt"
            type="datetime-local"
            required
            defaultValue={
              transaction
                ? toDateTimeLocal(transaction.occurredAt)
                : todayDateTimeLocal()
            }
            error={errors.occurredAt}
          />
        </Field>

        <Field label="Account" name="accountId" error={errors.accountId}>
          <Select
            name="accountId"
            defaultValue={transaction?.accountId ?? ""}
            error={errors.accountId}
          >
            <option value="">No account</option>
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
              </option>
            ))}
          </Select>
        </Field>

        <Field
          label="Merchant"
          name="merchant"
          error={errors.merchant}
          hint="Normalised automatically, so spellings from different banks still group."
        >
          <Input
            name="merchant"
            maxLength={200}
            defaultValue={transaction?.merchantName ?? ""}
            placeholder="Swiggy"
            error={errors.merchant}
          />
        </Field>

        <Field label="Category" name="categoryId" error={errors.categoryId}>
          <Select
            name="categoryId"
            defaultValue={transaction?.categoryId ?? ""}
            error={errors.categoryId}
          >
            <CategoryOptions categories={categories} />
          </Select>
        </Field>

        <Field label="Description" name="description" error={errors.description}>
          <Input
            name="description"
            maxLength={500}
            defaultValue={transaction?.description ?? ""}
            placeholder="Dinner with friends"
            error={errors.description}
          />
        </Field>

        <Field
          label="Tags"
          name="tags"
          error={errors.tags}
          hint="Comma separated."
        >
          <Input
            name="tags"
            defaultValue={transaction?.tags.join(", ") ?? ""}
            placeholder="food, weekend"
            error={errors.tags}
          />
        </Field>
      </div>

      <Field
        label="Bank reference"
        name="externalRef"
        error={errors.externalRef}
        hint="RRN or UTR. When present it is the decisive duplicate check."
      >
        <Input
          name="externalRef"
          maxLength={200}
          defaultValue={transaction?.externalRef ?? ""}
          error={errors.externalRef}
        />
      </Field>

      <Field label="Notes" name="notes" error={errors.notes}>
        <Textarea
          name="notes"
          rows={2}
          maxLength={2000}
          defaultValue={transaction?.notes ?? ""}
          error={errors.notes}
        />
      </Field>

      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          name="isExcluded"
          defaultChecked={transaction?.isExcluded}
          className="h-4 w-4"
        />
        Exclude from reports
      </label>

      <FormMessage state={state} />

      <div className="flex gap-2">
        <SubmitButton label={transaction ? "Save changes" : "Add transaction"} />
        {onDone && (
          <Button type="button" variant="secondary" onClick={onDone}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}

export function TransferForm({
  accounts,
  onDone,
}: {
  accounts: Account[];
  onDone?: () => void;
}) {
  const [state, action] = useActionState(saveTransfer, idleState);
  const formRef = useRef<HTMLFormElement>(null);
  const errors = state.fields ?? {};

  useEffect(() => {
    if (!state.ok) return;
    formRef.current?.reset();
    onDone?.();
  }, [state, onDone]);

  if (accounts.length < 2) {
    return (
      <p className="text-sm text-neutral-500">
        A transfer moves money between two of your accounts, so you need at
        least two before you can record one.
      </p>
    );
  }

  return (
    <form ref={formRef} action={action} className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="From" name="fromAccountId" error={errors.fromAccountId}>
          <Select name="fromAccountId" required error={errors.fromAccountId}>
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
              </option>
            ))}
          </Select>
        </Field>

        <Field label="To" name="toAccountId" error={errors.toAccountId}>
          <Select
            name="toAccountId"
            required
            defaultValue={accounts[1]?.id}
            error={errors.toAccountId}
          >
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
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
            placeholder="0.00"
            error={errors.amount}
          />
        </Field>

        <Field label="Date & time" name="occurredAt" error={errors.occurredAt}>
          <Input
            name="occurredAt"
            type="datetime-local"
            required
            defaultValue={todayDateTimeLocal()}
            error={errors.occurredAt}
          />
        </Field>
      </div>

      <Field label="Description" name="description" error={errors.description}>
        <Input name="description" placeholder="Card payment" error={errors.description} />
      </Field>

      <p className="text-xs text-neutral-500">
        Recorded as two linked legs, so both accounts show the movement and your
        spending totals stay unaffected.
      </p>

      <FormMessage state={state} />

      <div className="flex gap-2">
        <SubmitButton label="Record transfer" />
        {onDone && (
          <Button type="button" variant="secondary" onClick={onDone}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}

export function DeleteTransactionButton({
  transaction,
}: {
  transaction: Transaction;
}) {
  const [state, action] = useActionState(removeTransaction, idleState);
  const [confirming, setConfirming] = useState(false);

  if (state.message && !state.ok) {
    return <span className="text-xs text-red-600">{state.message}</span>;
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
      <input type="hidden" name="id" value={transaction.id} />
      <span className="text-xs text-neutral-500">
        {transaction.transferId ? "Delete both legs?" : "Delete?"}
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
