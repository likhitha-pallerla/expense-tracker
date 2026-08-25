"use client";

import { useRouter } from "next/navigation";

import { reset } from "@/lib/analytics";
import { createClient } from "@/lib/supabase/client";

export function SignOutButton() {
  const router = useRouter();

  return (
    <button
      type="button"
      onClick={async () => {
        await createClient().auth.signOut();
        // Without this the next person to sign in on this browser inherits the
        // previous one's analytics identity, and their events merge.
        reset();
        router.push("/login");
        router.refresh();
      }}
      className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm transition hover:bg-neutral-50 dark:border-neutral-700 dark:hover:bg-neutral-900"
    >
      Sign out
    </button>
  );
}
