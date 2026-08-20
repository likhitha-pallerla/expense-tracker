import Link from "next/link";

import { AppShell } from "@/components/app-shell";
import { RecurringView } from "@/components/recurring-view";
import { EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import type { Category, Profile, Recurring } from "@/lib/types";

export const metadata = { title: "Recurring" };

export default async function RecurringPage({
  searchParams,
}: {
  searchParams: Promise<{ includeDismissed?: string }>;
}) {
  const params = await searchParams;
  const showDismissed = params.includeDismissed === "true";

  let payments: Recurring[] = [];
  let categories: Category[] = [];
  let profile: Profile | null = null;
  let error: string | null = null;

  try {
    [payments, categories, profile] = await Promise.all([
      apiFetch<Recurring[]>(`/api/recurring?includeDismissed=${showDismissed}`),
      apiFetch<Category[]>("/api/categories"),
      apiFetch<Profile>("/api/me"),
    ]);
  } catch (err) {
    error = (err as Error).message;
  }

  // Only what the user has actually confirmed as a subscription counts towards
  // the headline. Adding suggestions would quote a number they never agreed to,
  // and adding a paused one would bill them for something already cancelled.
  const committed = payments.filter(
    (payment) =>
      payment.state === "confirmed" &&
      payment.isSubscription &&
      payment.isActive &&
      payment.direction === "debit",
  );
  const monthly = committed.reduce((sum, payment) => sum + payment.monthlyCost, 0);
  const currency = profile?.baseCurrency ?? "INR";

  return (
    <AppShell
      title="Recurring"
      description="Subscriptions and other regular payments, found in your own transactions."
      action={
        committed.length > 0 ? (
          <div className="text-right">
            <p className="text-xs uppercase tracking-wide text-neutral-500">
              Subscriptions
            </p>
            <p className="font-mono text-xl">
              {formatMoney(monthly, currency)}
              <span className="text-sm text-neutral-500"> / month</span>
            </p>
          </div>
        ) : undefined
      }
    >
      {error ? (
        <EmptyState>Could not load recurring payments: {error}</EmptyState>
      ) : (
        <>
          <RecurringView
            payments={payments}
            categories={categories}
            showingDismissed={showDismissed}
          />

          <p className="mt-8 text-xs text-neutral-500">
            <Link
              href={`/recurring?includeDismissed=${showDismissed ? "false" : "true"}`}
              className="underline underline-offset-2 hover:text-neutral-800 dark:hover:text-neutral-200"
            >
              {showDismissed ? "Hide dismissed" : "Show dismissed"}
            </Link>
          </p>
        </>
      )}
    </AppShell>
  );
}
