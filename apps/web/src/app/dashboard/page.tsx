import Link from "next/link";

import { AppShell } from "@/components/app-shell";
import { Card, EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import { formatDate, formatMoney, formatSigned } from "@/lib/format";
import type {
  Account,
  Profile,
  Transaction,
  TransactionPage,
} from "@/lib/types";

export const metadata = { title: "Dashboard" };

/** Start of the current month in UTC, matching the API's instant filters. */
function monthStart(): string {
  const now = new Date();
  return new Date(
    Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1),
  ).toISOString();
}

type Data = {
  profile: Profile;
  accounts: Account[];
  recent: TransactionPage;
  month: TransactionPage;
};

async function load(): Promise<
  { ok: true; data: Data } | { ok: false; error: string }
> {
  try {
    // /api/me provisions the profile, categories and default account on first
    // call, so it must resolve before anything else is requested.
    const profile = await apiFetch<Profile>("/api/me");

    const [accounts, recent, month] = await Promise.all([
      apiFetch<Account[]>("/api/accounts"),
      apiFetch<TransactionPage>("/api/transactions?limit=5"),
      apiFetch<TransactionPage>(
        `/api/transactions?from=${monthStart()}&kind=expense&limit=1`,
      ),
    ]);

    return { ok: true, data: { profile, accounts, recent, month } };
  } catch (error) {
    return { ok: false, error: (error as Error).message };
  }
}

export default async function DashboardPage() {
  const result = await load();

  if (!result.ok) {
    return (
      <AppShell title="Dashboard">
        <Card title="Backend API">
          <p className="text-sm text-red-600">
            Could not reach the API: {result.error}
          </p>
          <p className="mt-2 text-xs text-neutral-500">
            Start it with <code>mvn spring-boot:run</code> in{" "}
            <code>apps/api</code>.
          </p>
        </Card>
      </AppShell>
    );
  }

  const { profile, accounts, recent, month } = result.data;
  const netWorth = accounts.reduce((sum, account) => sum + account.balance, 0);
  // Expenses are stored as negative signed amounts; flip for display.
  const spentThisMonth = -month.netAmount;

  return (
    <AppShell
      title={profile.displayName ? `Hi, ${profile.displayName}` : "Dashboard"}
      description="A quick read on where your money went."
    >
      <div className="grid gap-4 sm:grid-cols-3">
        <Card title="Net worth">
          <p className="font-mono text-2xl">
            {formatMoney(netWorth, profile.baseCurrency)}
          </p>
          <p className="mt-1 text-xs text-neutral-500">
            Across {accounts.length} account{accounts.length === 1 ? "" : "s"}
          </p>
        </Card>

        <Card title="Spent this month">
          <p className="font-mono text-2xl">
            {formatMoney(spentThisMonth, profile.baseCurrency)}
          </p>
          <p className="mt-1 text-xs text-neutral-500">
            {month.total} expense{month.total === 1 ? "" : "s"} so far
          </p>
        </Card>

        <Card title="Transactions">
          <p className="font-mono text-2xl">{recent.total}</p>
          <p className="mt-1 text-xs text-neutral-500">Recorded in total</p>
        </Card>
      </div>

      <div className="mt-4">
        <Card
          title="Recent activity"
          action={
            <Link
              href="/transactions"
              className="text-sm text-neutral-500 underline underline-offset-4"
            >
              View all
            </Link>
          }
        >
          {recent.items.length === 0 ? (
            <EmptyState>
              No transactions yet.{" "}
              <Link href="/transactions" className="underline underline-offset-4">
                Add your first one
              </Link>
              .
            </EmptyState>
          ) : (
            <ul className="divide-y divide-neutral-200 dark:divide-neutral-800">
              {recent.items.map((transaction) => (
                <RecentRow key={transaction.id} transaction={transaction} />
              ))}
            </ul>
          )}
        </Card>
      </div>

      {accounts.length === 0 && (
        <p className="mt-4 text-sm text-neutral-500">
          Start by{" "}
          <Link href="/accounts" className="underline underline-offset-4">
            adding an account
          </Link>{" "}
          so balances have somewhere to live.
        </p>
      )}
    </AppShell>
  );
}

function RecentRow({ transaction }: { transaction: Transaction }) {
  return (
    <li className="flex items-center justify-between gap-4 py-2 text-sm">
      <div className="min-w-0">
        <p className="truncate">
          {transaction.merchantName ??
            transaction.description ??
            (transaction.kind === "transfer" ? "Transfer" : "Untitled")}
        </p>
        <p className="text-xs text-neutral-500">
          {formatDate(transaction.occurredAt)}
          {transaction.accountName && ` · ${transaction.accountName}`}
        </p>
      </div>
      <span
        className={`font-mono ${
          transaction.kind === "transfer"
            ? "text-neutral-500"
            : transaction.signedAmount < 0
              ? "text-red-600 dark:text-red-400"
              : "text-emerald-600 dark:text-emerald-400"
        }`}
      >
        {formatSigned(transaction.signedAmount, transaction.currency)}
      </span>
    </li>
  );
}
