import { AppShell } from "@/components/app-shell";
import { DuplicatesView } from "@/components/duplicates-view";
import { HeldSendersView } from "@/components/held-senders-view";
import { EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import type { DuplicatePair, HeldSender, TrustedSender } from "@/lib/types";

export const metadata = { title: "Review" };

export default async function ReviewPage() {
  let pairs: DuplicatePair[] = [];
  let held: HeldSender[] = [];
  let trusted: TrustedSender[] = [];
  let error: string | null = null;

  try {
    // Fetched together: one slow API is better than three sequential ones, and
    // a user sent here by a notification may need either section.
    [pairs, held, trusted] = await Promise.all([
      apiFetch<DuplicatePair[]>("/api/duplicates"),
      apiFetch<HeldSender[]>("/api/parse/held"),
      apiFetch<TrustedSender[]>("/api/parse/trusted"),
    ]);
  } catch (err) {
    error = (err as Error).message;
  }

  return (
    <AppShell
      title="Review"
      description="Two things end up here: the same payment reported twice, and alerts from a sender we cannot place. Neither is acted on behind your back."
    >
      {error ? (
        <EmptyState>Could not load the review queue: {error}</EmptyState>
      ) : (
        <div className="space-y-8">
          <HeldSendersView held={held} trusted={trusted} />
          <DuplicatesView pairs={pairs} />
        </div>
      )}
    </AppShell>
  );
}
