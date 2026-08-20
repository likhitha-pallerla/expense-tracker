import type { ReactNode } from "react";

import { NavLinks } from "@/components/nav-links";
import { SignOutButton } from "@/components/sign-out-button";
import { apiFetch } from "@/lib/api";
import { createClient } from "@/lib/supabase/server";

/**
 * Shell for every signed-in page.
 *
 * The middleware already guarantees a session here, so this only renders
 * chrome; it does not re-check authorisation.
 */
export async function AppShell({
  title,
  description,
  action,
  children,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();

  // A badge is not worth breaking every page over, so a failure here is
  // swallowed and the nav simply renders without a count.
  let reviewCount = 0;
  try {
    const { pending } = await apiFetch<{ pending: number }>(
      "/api/duplicates/count",
    );
    reviewCount = pending;
  } catch {
    reviewCount = 0;
  }

  return (
    <div className="flex min-h-screen flex-col bg-neutral-50 dark:bg-neutral-900">
      <header className="border-b border-neutral-200 bg-white dark:border-neutral-800 dark:bg-neutral-950">
        <div className="mx-auto flex w-full max-w-5xl items-center justify-between gap-4 px-6 py-3">
          <NavLinks reviewCount={reviewCount} />
          <div className="flex items-center gap-3">
            <span className="hidden text-sm text-neutral-500 sm:inline">
              {user?.email}
            </span>
            <SignOutButton />
          </div>
        </div>
      </header>

      <main className="mx-auto w-full max-w-5xl flex-1 px-6 py-8">
        <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
            {description && (
              <p className="mt-1 text-sm text-neutral-500">{description}</p>
            )}
          </div>
          {action}
        </div>

        {children}
      </main>
    </div>
  );
}
