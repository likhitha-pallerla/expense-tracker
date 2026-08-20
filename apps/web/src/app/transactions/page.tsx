import Link from "next/link";
import { Suspense } from "react";

import { AppShell } from "@/components/app-shell";
import { TransactionFilters } from "@/components/transaction-filters";
import { TransactionsView } from "@/components/transactions-view";
import { EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import { formatSigned } from "@/lib/format";
import type {
  Account,
  Category,
  TransactionPage,
} from "@/lib/types";

export const metadata = { title: "Transactions" };

type SearchParams = Record<string, string | string[] | undefined>;

const PAGE_SIZE = 50;

/**
 * Only known keys are forwarded, and dates are widened to cover the whole day
 * so "to = 5 March" includes everything that happened on the 5th.
 */
function buildQuery(params: SearchParams): string {
  const query = new URLSearchParams();
  const read = (key: string) => {
    const value = params[key];
    return typeof value === "string" && value.trim() !== "" ? value.trim() : null;
  };

  for (const key of [
    "accountId",
    "categoryId",
    "merchantId",
    "kind",
    "search",
    "minAmount",
    "maxAmount",
  ]) {
    const value = read(key);
    if (value) query.set(key, value);
  }

  const from = read("from");
  if (from) query.set("from", `${from}T00:00:00Z`);

  const to = read("to");
  if (to) query.set("to", `${to}T23:59:59Z`);

  if (read("includeExcluded") === "true") query.set("includeExcluded", "true");

  query.set("limit", String(PAGE_SIZE));
  query.set("offset", read("offset") ?? "0");

  return query.toString();
}

/** Preserves the active filters while moving to another page. */
function pageHref(params: SearchParams, offset: number): string {
  const next = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (typeof value === "string" && value !== "" && key !== "offset") {
      next.set(key, value);
    }
  }
  if (offset > 0) next.set("offset", String(offset));
  return `/transactions${next.toString() ? `?${next}` : ""}`;
}

export default async function TransactionsPage({
  searchParams,
}: {
  searchParams: Promise<SearchParams>;
}) {
  const params = await searchParams;

  let page: TransactionPage | null = null;
  let accounts: Account[] = [];
  let categories: Category[] = [];
  let error: string | null = null;

  try {
    [page, accounts, categories] = await Promise.all([
      apiFetch<TransactionPage>(`/api/transactions?${buildQuery(params)}`),
      apiFetch<Account[]>("/api/accounts"),
      apiFetch<Category[]>("/api/categories"),
    ]);
  } catch (err) {
    error = (err as Error).message;
  }

  const offset = page?.offset ?? 0;
  const shownTo = offset + (page?.items.length ?? 0);

  return (
    <AppShell
      title="Transactions"
      description="Every expense, payment and transfer, newest first."
      action={
        page ? (
          <div className="text-right">
            <p className="text-xs uppercase tracking-wide text-neutral-500">
              Net over {page.total} transaction{page.total === 1 ? "" : "s"}
            </p>
            <p className="font-mono text-xl">{formatSigned(page.netAmount)}</p>
          </div>
        ) : undefined
      }
    >
      {error || !page ? (
        <EmptyState>Could not load transactions: {error}</EmptyState>
      ) : (
        <div className="space-y-4">
          <Suspense fallback={null}>
            <TransactionFilters accounts={accounts} categories={categories} />
          </Suspense>

          <TransactionsView
            page={page}
            accounts={accounts}
            categories={categories}
          />

          {page.total > PAGE_SIZE && (
            <nav
              className="flex items-center justify-between text-sm"
              aria-label="Pagination"
            >
              <span className="text-neutral-500">
                Showing {offset + 1}–{shownTo} of {page.total}
              </span>
              <span className="flex gap-2">
                {offset > 0 && (
                  <Link
                    href={pageHref(params, Math.max(offset - PAGE_SIZE, 0))}
                    className="rounded-md border border-neutral-300 px-3 py-1.5 hover:bg-neutral-100 dark:border-neutral-700 dark:hover:bg-neutral-900"
                  >
                    Previous
                  </Link>
                )}
                {shownTo < page.total && (
                  <Link
                    href={pageHref(params, offset + PAGE_SIZE)}
                    className="rounded-md border border-neutral-300 px-3 py-1.5 hover:bg-neutral-100 dark:border-neutral-700 dark:hover:bg-neutral-900"
                  >
                    Next
                  </Link>
                )}
              </span>
            </nav>
          )}
        </div>
      )}
    </AppShell>
  );
}
