import { AppShell } from "@/components/app-shell";
import { ImportView } from "@/components/import-view";
import { EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import type { Account } from "@/lib/types";

export const metadata = { title: "Import a statement" };

export default async function ImportPage() {
  let accounts: Account[] = [];
  let error: string | null = null;

  try {
    accounts = await apiFetch<Account[]>("/api/accounts");
  } catch (err) {
    error = (err as Error).message;
  }

  return (
    <AppShell
      title="Import a statement"
      description="Upload a CSV from your bank. You will see exactly what it read before anything is saved, and rows already in your ledger are caught rather than added twice."
    >
      {error ? (
        <EmptyState>Could not load your accounts: {error}</EmptyState>
      ) : (
        <ImportView accounts={accounts.filter((a) => !a.isArchived)} />
      )}
    </AppShell>
  );
}
