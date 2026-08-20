import { AppShell } from "@/components/app-shell";
import { DuplicatesView } from "@/components/duplicates-view";
import { EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import type { DuplicatePair } from "@/lib/types";

export const metadata = { title: "Review duplicates" };

export default async function ReviewPage() {
  let pairs: DuplicatePair[] = [];
  let error: string | null = null;

  try {
    pairs = await apiFetch<DuplicatePair[]>("/api/duplicates");
  } catch (err) {
    error = (err as Error).message;
  }

  return (
    <AppShell
      title="Review duplicates"
      description="The same payment often gets reported twice. Anything uncertain waits here for you rather than being merged behind your back."
    >
      {error ? (
        <EmptyState>Could not load the review queue: {error}</EmptyState>
      ) : (
        <DuplicatesView pairs={pairs} />
      )}
    </AppShell>
  );
}
