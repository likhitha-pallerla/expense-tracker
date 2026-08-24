/**
 * The API shapes the phone actually uses.
 *
 * A deliberate subset of `apps/web/src/lib/types.ts` rather than a copy of it.
 * The web application shows budgets, duplicate review, imports and goals; the
 * phone shows what you spent and lets you add something you paid in cash.
 * Declaring only what is rendered keeps the compiler useful — an unused field
 * that silently changes shape on the server is not caught by anything.
 */

export type Totals = {
  income: number;
  expense: number;
  net: number;
  count: number;
  isEmpty: boolean;
};

export type CategorySlice = {
  categoryId: string | null;
  name: string;
  amount: number;
  share: number;
  count: number;
  isUncategorised: boolean;
};

export type Insights = {
  month: string;
  label: string;
  currency: string;
  /** True while the month is still running, so the figures are partial. */
  partial: boolean;
  daysElapsed: number;
  daysInMonth: number;
  totals: Totals;
  previous: Totals;
  expenseChange: number | null;
  /** Where the month looks likely to end, or null when it is too early to say. */
  projectedExpense: number | null;
  categories: CategorySlice[];
  /** The month holds more than one currency, so the totals are approximate. */
  mixedCurrencies: boolean;
};

export type TransactionDirection = 'debit' | 'credit';

export type Transaction = {
  id: string;
  direction: TransactionDirection;
  amount: number;
  signedAmount: number;
  currency: string;
  occurredAt: string;
  description: string | null;
  accountId: string | null;
  accountName: string | null;
  categoryId: string | null;
  categoryName: string | null;
  merchantName: string | null;
};

export type TransactionPage = {
  items: Transaction[];
  total: number;
  netAmount: number;
  limit: number;
  offset: number;
};

export type Category = {
  id: string;
  name: string;
  parent_id: string | null;
};

export type Account = {
  id: string;
  name: string;
  currency: string;
  balance: number;
  isArchived: boolean;
};

/** Mirrors `SmsIngestResult` on the server. */
export type SmsIngestResult = {
  connectionId: string;
  received: number;
  stored: number;
  duplicates: number;
  skipped: Record<string, number>;
  parsed: {
    read: number;
    imported: number;
    merged: number;
    ignored: number;
    failed: number;
    summary: string;
  } | null;
};
