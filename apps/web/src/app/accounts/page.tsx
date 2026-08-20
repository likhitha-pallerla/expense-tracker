import { AppShell } from "@/components/app-shell";
import { AccountsView } from "@/components/accounts-view";
import { EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import type { Account } from "@/lib/types";

export const metadata = { title: "Accounts" };

export default async function AccountsPage({
  searchParams,
}: {
  searchParams: Promise<{ includeArchived?: string }>;
}) {
  const params = await searchParams;
  const showArchived = params.includeArchived === "true";

  let accounts: Account[] = [];
  let error: string | null = null;

  try {
    accounts = await apiFetch<Account[]>(
      `/api/accounts?includeArchived=${showArchived}`,
    );
  } catch (err) {
    error = (err as Error).message;
  }

  // Archived accounts are excluded: they are closed, and counting them would
  // overstate the money actually available.
  const total = accounts
    .filter((account) => !account.isArchived)
    .reduce((sum, account) => sum + account.balance, 0);

  return (
    <AppShell
      title="Accounts"
      description="Where your money sits. Balances are derived from transactions, so they always match the ledger."
      action={
        accounts.length > 0 ? (
          <div className="text-right">
            <p className="text-xs uppercase tracking-wide text-neutral-500">
              Total
            </p>
            <p className="font-mono text-xl">{formatMoney(total)}</p>
          </div>
        ) : undefined
      }
    >
      {error ? (
        <EmptyState>Could not load accounts: {error}</EmptyState>
      ) : (
        <AccountsView accounts={accounts} showArchived={showArchived} />
      )}
    </AppShell>
  );
}
