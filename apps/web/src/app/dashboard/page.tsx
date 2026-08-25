import Link from "next/link";
import { Suspense } from "react";

import { AppShell } from "@/components/app-shell";
import {
  Breakdown,
  Caveats,
  Headline,
  MonthNav,
  Movers,
  TopMerchants,
  Trend,
} from "@/components/insights-view";
import { MonthSummary, MonthSummarySkeleton } from "@/components/month-summary";
import { QuickAdd } from "@/components/quick-add";
import { Card, EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import type { Account, Category, Insights, Profile } from "@/lib/types";

export const metadata = { title: "Dashboard" };

type SearchParams = Record<string, string | string[] | undefined>;

/**
 * Reads the month out of the URL, if there is one.
 *
 * Left to the API when absent: only the server knows which month the user is
 * actually in, because it knows their timezone and this process does not.
 */
function monthParam(params: SearchParams): string | null {
  const value = params.month;
  return typeof value === "string" && /^\d{4}-\d{2}$/.test(value) ? value : null;
}

async function load(
  month: string | null,
): Promise<
  | {
      ok: true;
      profile: Profile;
      insights: Insights;
      accounts: Account[];
      categories: Category[];
    }
  | { ok: false; error: string }
> {
  try {
    // /api/me provisions the profile, categories and default account on first
    // call, so it must resolve before anything else is requested.
    const profile = await apiFetch<Profile>("/api/me");

    // The rest are independent of each other, so they go together. The quick-add
    // box needs the account and category lists to offer a correction when a
    // name in the sentence matched nothing.
    const [insights, accounts, categories] = await Promise.all([
      apiFetch<Insights>(month ? `/api/insights?month=${month}` : "/api/insights"),
      apiFetch<Account[]>("/api/accounts"),
      apiFetch<Category[]>("/api/categories"),
    ]);
    return { ok: true, profile, insights, accounts, categories };
  } catch (error) {
    return { ok: false, error: (error as Error).message };
  }
}

export default async function DashboardPage({
  searchParams,
}: {
  searchParams: Promise<SearchParams>;
}) {
  const params = await searchParams;
  const result = await load(monthParam(params));

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

  const { profile, insights, accounts, categories } = result;

  if (!insights.hasHistory) {
    return (
      <AppShell
        title={profile.displayName ? `Hi, ${profile.displayName}` : "Dashboard"}
        description="Nothing recorded yet."
      >
        <div className="space-y-4">
          <Card title="Getting started">
            <EmptyState>
              There is nothing to show until some money moves.
            </EmptyState>
            <ul className="mt-4 space-y-2 text-sm">
              <li>
                <Link href="/connections" className="underline underline-offset-4">
                  Connect a mailbox
                </Link>{" "}
                and we will read your bank alerts for you.
              </li>
              <li>
                <Link href="/import" className="underline underline-offset-4">
                  Import a statement
                </Link>{" "}
                if you would rather start with history.
              </li>
              <li>
                Or type one below — the fastest way to see how any of this looks.
              </li>
            </ul>
          </Card>

          <QuickAdd
            accounts={accounts}
            categories={categories}
            currency={insights.currency}
          />
        </div>
      </AppShell>
    );
  }

  return (
    <AppShell
      title={profile.displayName ? `Hi, ${profile.displayName}` : "Dashboard"}
      description="A quick read on where your money went."
    >
      <div className="space-y-4">
        <MonthNav insights={insights} />
        <Headline insights={insights} />

        <Suspense fallback={<MonthSummarySkeleton />}>
          <MonthSummary month={monthParam(params)} />
        </Suspense>

        <QuickAdd
          accounts={accounts}
          categories={categories}
          currency={insights.currency}
        />

        {insights.totals.isEmpty ? (
          <Card title="Where it went">
            <EmptyState>
              Nothing recorded in {insights.label}.
            </EmptyState>
          </Card>
        ) : (
          <>
            <div className="grid gap-4 lg:grid-cols-2">
              <Breakdown insights={insights} />
              <div className="space-y-4">
                <Movers insights={insights} />
                <TopMerchants insights={insights} />
              </div>
            </div>
          </>
        )}

        <Trend insights={insights} />
        <Caveats insights={insights} />

        <p className="text-xs text-neutral-500">
          <Link href="/transactions" className="underline underline-offset-4">
            See every transaction
          </Link>
        </p>
      </div>
    </AppShell>
  );
}
