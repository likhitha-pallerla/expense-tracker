import { AppShell } from "@/components/app-shell";
import { ConnectionsView } from "@/components/connections-view";
import { EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import type { MailProviderOption, SyncRun, UnreadMessage } from "@/lib/types";

export const metadata = { title: "Connections" };

/** What came back from the provider, said plainly. */
const OUTCOMES: Record<string, { tone: "good" | "bad"; message: string }> = {
  cancelled: {
    tone: "bad",
    message: "You cancelled before granting access. Nothing was connected.",
  },
  refused: {
    tone: "bad",
    message: "Your mail provider refused the request. Nothing was connected.",
  },
  expired: {
    tone: "bad",
    message:
      "That sign-in attempt had already been used or had expired. Start again.",
  },
  no_code: {
    tone: "bad",
    message: "Your mail provider did not send anything back. Try again.",
  },
  exchange_failed: {
    tone: "bad",
    message:
      "We could not finish signing in with your mail provider. Try again in a minute.",
  },
};

export default async function ConnectionsPage({
  searchParams,
}: {
  searchParams: Promise<{ connected?: string; error?: string }>;
}) {
  const params = await searchParams;
  const outcome = params.error
    ? (OUTCOMES[params.error] ?? OUTCOMES.exchange_failed)
    : params.connected
      ? { tone: "good" as const, message: "Mailbox connected." }
      : null;

  let providers: MailProviderOption[] = [];
  let runs: SyncRun[] = [];
  let unread: UnreadMessage[] = [];
  let error: string | null = null;

  try {
    // The run history and the unread list are both secondary; a failure to load
    // either must not take the connections themselves down with it.
    const [loaded, history, failures] = await Promise.all([
      apiFetch<MailProviderOption[]>("/api/connections"),
      apiFetch<SyncRun[]>("/api/sync/runs").catch(() => [] as SyncRun[]),
      apiFetch<UnreadMessage[]>("/api/parse/unread").catch(
        () => [] as UnreadMessage[],
      ),
    ]);
    providers = loaded;
    runs = history;
    unread = failures;
  } catch (err) {
    error = (err as Error).message;
  }

  return (
    <AppShell
      title="Connections"
      description="Link a mailbox and payment alerts become transactions on their own."
    >
      {outcome && (
        <p
          role="status"
          className={`mb-6 rounded-md px-4 py-3 text-sm ${
            outcome.tone === "good"
              ? "bg-emerald-50 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300"
              : "bg-amber-50 text-amber-800 dark:bg-amber-950 dark:text-amber-300"
          }`}
        >
          {outcome.message}
        </p>
      )}

      {error ? (
        <EmptyState>Could not load connections: {error}</EmptyState>
      ) : (
        <ConnectionsView providers={providers} runs={runs} unread={unread} />
      )}
    </AppShell>
  );
}
