"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";

import { Button, Field, Input, Select } from "@/components/ui/form";
import type { Account, Category } from "@/lib/types";

/**
 * Filters live in the URL rather than component state, so a filtered view can
 * be bookmarked, shared and restored by the back button.
 */
export function TransactionFilters({
  accounts,
  categories,
}: {
  accounts: Account[];
  categories: Category[];
}) {
  const router = useRouter();
  const params = useSearchParams();
  const [open, setOpen] = useState(
    () => [...params.keys()].some((key) => key !== "offset"),
  );

  const current = (key: string) => params.get(key) ?? "";

  function apply(form: FormData) {
    const next = new URLSearchParams();
    for (const [key, value] of form.entries()) {
      if (typeof value === "string" && value.trim() !== "") {
        next.set(key, value.trim());
      }
    }
    // Paging restarts: the old offset almost certainly points past the end of
    // a newly narrowed result set.
    next.delete("offset");
    router.push(`/transactions?${next.toString()}`);
  }

  if (!open) {
    return (
      <Button variant="secondary" onClick={() => setOpen(true)}>
        Filters
      </Button>
    );
  }

  return (
    <form
      action={apply}
      className="rounded-lg border border-neutral-200 bg-white p-4 dark:border-neutral-800 dark:bg-neutral-950"
    >
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Field label="Search" name="search">
          <Input
            name="search"
            defaultValue={current("search")}
            placeholder="Merchant, description or note"
          />
        </Field>

        <Field label="Account" name="accountId">
          <Select name="accountId" defaultValue={current("accountId")}>
            <option value="">All accounts</option>
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
              </option>
            ))}
          </Select>
        </Field>

        <Field label="Category" name="categoryId">
          <Select name="categoryId" defaultValue={current("categoryId")}>
            <option value="">All categories</option>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </Select>
        </Field>

        <Field label="Type" name="kind">
          <Select name="kind" defaultValue={current("kind")}>
            <option value="">All types</option>
            <option value="expense">Expense</option>
            <option value="income">Income</option>
            <option value="transfer">Transfer</option>
          </Select>
        </Field>

        <Field label="From" name="from">
          <Input name="from" type="date" defaultValue={current("from")} />
        </Field>

        <Field label="To" name="to">
          <Input name="to" type="date" defaultValue={current("to")} />
        </Field>

        <Field label="Min amount" name="minAmount">
          <Input
            name="minAmount"
            type="number"
            step="0.01"
            defaultValue={current("minAmount")}
          />
        </Field>

        <Field label="Max amount" name="maxAmount">
          <Input
            name="maxAmount"
            type="number"
            step="0.01"
            defaultValue={current("maxAmount")}
          />
        </Field>
      </div>

      <label className="mt-4 flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          name="includeExcluded"
          value="true"
          defaultChecked={current("includeExcluded") === "true"}
          className="h-4 w-4"
        />
        Include transactions excluded from reports
      </label>

      <div className="mt-4 flex gap-2">
        <Button type="submit">Apply</Button>
        <Button
          type="button"
          variant="secondary"
          onClick={() => router.push("/transactions")}
        >
          Clear
        </Button>
      </div>
    </form>
  );
}
