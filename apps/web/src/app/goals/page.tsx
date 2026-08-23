import { AppShell } from "@/components/app-shell";
import { GoalsView } from "@/components/goals-view";
import { EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import type { Account, Goal, Profile } from "@/lib/types";

export const metadata = { title: "Goals" };

export default async function GoalsPage({
  searchParams,
}: {
  searchParams: Promise<{ includeClosed?: string }>;
}) {
  const params = await searchParams;
  const showClosed = params.includeClosed === "true";

  let goals: Goal[] = [];
  let accounts: Account[] = [];
  let profile: Profile | null = null;
  let error: string | null = null;

  try {
    profile = await apiFetch<Profile>("/api/me");
    [goals, accounts] = await Promise.all([
      apiFetch<Goal[]>(
        `/api/goals?includeClosed=${showClosed}&withContributions=true`,
      ),
      apiFetch<Account[]>("/api/accounts"),
    ]);
  } catch (err) {
    error = (err as Error).message;
  }

  const currency = profile?.baseCurrency ?? "INR";

  // Only goals still being pursued are summarised. Counting finished or
  // abandoned ones would inflate the header into a number that describes
  // nothing anyone is currently doing.
  const live = goals.filter((goal) => goal.status === "active");
  const saved = live.reduce((sum, goal) => sum + goal.progress.saved, 0);
  const target = live.reduce((sum, goal) => sum + goal.targetAmount, 0);

  return (
    <AppShell
      title="Goals"
      description="What you are saving for, and whether the pace gets you there. Progress is added up from what you record putting aside, not read from your accounts."
      action={
        live.length > 0 ? (
          <div className="text-right">
            <p className="text-xs uppercase tracking-wide text-neutral-500">
              Across {live.length} goal{live.length === 1 ? "" : "s"}
            </p>
            <p className="font-mono text-xl">
              {formatMoney(saved, currency)}
              <span className="text-sm text-neutral-500">
                {" "}
                / {formatMoney(target, currency)}
              </span>
            </p>
          </div>
        ) : undefined
      }
    >
      {error ? (
        <EmptyState>Could not load goals: {error}</EmptyState>
      ) : (
        <GoalsView
          goals={goals}
          accounts={accounts}
          currency={currency}
          showClosed={showClosed}
        />
      )}
    </AppShell>
  );
}
