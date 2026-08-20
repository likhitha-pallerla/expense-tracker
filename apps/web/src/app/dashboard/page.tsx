import Link from "next/link";

import { SignOutButton } from "@/components/sign-out-button";
import { apiFetch } from "@/lib/api";
import { createClient } from "@/lib/supabase/server";

type MeResponse = {
  userId: string;
  email: string | null;
  displayName: string | null;
  baseCurrency: string;
  timezone: string;
  locale: string;
  onboardedAt: string | null;
  newlyProvisioned: boolean;
};

type Account = {
  id: string;
  name: string;
  type: string;
  currency: string;
  balance: string;
};

async function loadApiIdentity(): Promise<
  | { ok: true; profile: MeResponse; accounts: Account[] }
  | { ok: false; error: string }
> {
  try {
    // /api/me provisions defaults on first call, so it must resolve before
    // accounts are requested.
    const profile = await apiFetch<MeResponse>("/api/me");
    const accounts = await apiFetch<Account[]>("/api/accounts");
    return { ok: true, profile, accounts };
  } catch (error) {
    return { ok: false, error: (error as Error).message };
  }
}

export default async function DashboardPage() {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();

  const api = await loadApiIdentity();

  return (
    <main className="mx-auto w-full max-w-3xl flex-1 px-6 py-12">
      <header className="mb-10 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">
            {api.ok && api.profile.displayName
              ? `Hi, ${api.profile.displayName}`
              : "Dashboard"}
          </h1>
          <p className="text-sm text-neutral-500">{user?.email}</p>
        </div>
        <SignOutButton />
      </header>

      <section className="space-y-4">
        {api.ok ? (
          <>
            <div className="rounded-lg border border-neutral-200 p-4 dark:border-neutral-800">
              <h2 className="text-sm font-medium text-neutral-500">Profile</h2>
              <dl className="mt-2 grid grid-cols-2 gap-y-1 text-sm">
                <dt className="text-neutral-500">Base currency</dt>
                <dd>{api.profile.baseCurrency}</dd>
                <dt className="text-neutral-500">Timezone</dt>
                <dd>{api.profile.timezone}</dd>
              </dl>
              {api.profile.userId !== user?.id && (
                <p className="mt-2 text-sm text-red-600">
                  Warning: the API resolved a different user id.
                </p>
              )}
            </div>

            <div className="rounded-lg border border-neutral-200 p-4 dark:border-neutral-800">
              <h2 className="text-sm font-medium text-neutral-500">Accounts</h2>
              <ul className="mt-2 space-y-1 text-sm">
                {api.accounts.map((account) => (
                  <li key={account.id} className="flex justify-between">
                    <span>
                      {account.name}{" "}
                      <span className="text-neutral-400">({account.type})</span>
                    </span>
                    <span className="font-mono">
                      {account.currency} {account.balance}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          </>
        ) : (
          <div className="rounded-lg border border-red-200 p-4 dark:border-red-900">
            <h2 className="text-sm font-medium text-neutral-500">Backend API</h2>
            <p className="mt-1 text-sm text-red-600">
              Could not reach the API: {api.error}
            </p>
            <p className="mt-2 text-xs text-neutral-500">
              Start it with <code>mvn spring-boot:run</code> in{" "}
              <code>apps/api</code>.
            </p>
          </div>
        )}
      </section>

      <p className="mt-10 text-sm text-neutral-500">
        Next up: connect an account and start importing transactions.{" "}
        <Link href="/" className="underline underline-offset-4">
          Back home
        </Link>
      </p>
    </main>
  );
}
