import { AppShell } from "@/components/app-shell";
import { BudgetsView } from "@/components/budgets-view";
import { EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import type { Budget, Category, Profile } from "@/lib/types";

export const metadata = { title: "Budgets" };

export default async function BudgetsPage({
  searchParams,
}: {
  searchParams: Promise<{ includeInactive?: string }>;
}) {
  const params = await searchParams;
  const showInactive = params.includeInactive === "true";

  let budgets: Budget[] = [];
  let categories: Category[] = [];
  let profile: Profile | null = null;
  let error: string | null = null;

  try {
    [budgets, categories, profile] = await Promise.all([
      apiFetch<Budget[]>(`/api/budgets?includeInactive=${showInactive}`),
      apiFetch<Category[]>("/api/categories"),
      apiFetch<Profile>("/api/me"),
    ]);
  } catch (err) {
    error = (err as Error).message;
  }

  // Paused budgets are left out of the header: they are not tracking anything,
  // so counting them would overstate what is actually being watched.
  const live = budgets.filter((budget) => budget.isActive);
  const spent = live.reduce((sum, budget) => sum + budget.spent, 0);
  const limit = live.reduce((sum, budget) => sum + budget.limit, 0);
  const currency = profile?.baseCurrency ?? "INR";

  return (
    <AppShell
      title="Budgets"
      description="What you meant to spend, against what you actually did. Figures are read straight from your transactions."
      action={
        live.length > 0 ? (
          <div className="text-right">
            <p className="text-xs uppercase tracking-wide text-neutral-500">
              This period
            </p>
            <p className="font-mono text-xl">
              {formatMoney(spent, currency)}
              <span className="text-sm text-neutral-500">
                {" "}
                / {formatMoney(limit, currency)}
              </span>
            </p>
          </div>
        ) : undefined
      }
    >
      {error ? (
        <EmptyState>Could not load budgets: {error}</EmptyState>
      ) : (
        <BudgetsView
          budgets={budgets}
          categories={categories}
          currency={currency}
          showInactive={showInactive}
        />
      )}
    </AppShell>
  );
}
