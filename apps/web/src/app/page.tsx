import Link from "next/link";

import { createClient } from "@/lib/supabase/server";

export default async function Home() {
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();

  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col justify-center px-6 py-24">
      <h1 className="text-4xl font-semibold tracking-tight">Expense Tracker</h1>
      <p className="mt-4 max-w-xl text-neutral-500">
        Automatically turns your bank emails and SMS alerts into a clean,
        de-duplicated record of what you actually spend.
      </p>

      <div className="mt-8">
        <Link
          href={user ? "/dashboard" : "/login"}
          className="inline-block rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-neutral-700 dark:bg-white dark:text-neutral-900 dark:hover:bg-neutral-200"
        >
          {user ? "Go to dashboard" : "Get started"}
        </Link>
      </div>
    </main>
  );
}
