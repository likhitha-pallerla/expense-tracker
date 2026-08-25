import { apiFetch } from "@/lib/api";
import type { Narration } from "@/lib/types";

/**
 * The month, in a sentence.
 *
 * <p>Fetched separately from the figures and rendered inside a Suspense
 * boundary, because this call may involve a model and therefore a network round
 * trip to somebody else's server. The numbers must not wait on prose.
 *
 * <p>If it fails it renders nothing at all. A summary is a nicety on a page
 * that already shows every figure it would have described; an error message
 * where a sentence should be would be worse than the silence.
 */
export async function MonthSummary({ month }: { month: string | null }) {
  let narration: Narration;
  try {
    narration = await apiFetch<Narration>(
      month ? `/api/insights/summary?month=${month}` : "/api/insights/summary",
    );
  } catch {
    return null;
  }

  if (!narration.text) return null;

  return (
    <section className="rounded-lg border border-neutral-200 bg-neutral-50 p-5 dark:border-neutral-800 dark:bg-neutral-900">
      <p className="text-sm leading-relaxed">{narration.text}</p>
      {narration.source === "ai" && (
        <p className="mt-2 text-xs text-neutral-500">
          Written by AI from the figures above. The figures are ours.
        </p>
      )}
    </section>
  );
}

/** Holds the space so the page does not jump when the sentence arrives. */
export function MonthSummarySkeleton() {
  return (
    <section className="rounded-lg border border-neutral-200 bg-neutral-50 p-5 dark:border-neutral-800 dark:bg-neutral-900">
      <div className="h-4 w-3/4 animate-pulse rounded bg-neutral-200 dark:bg-neutral-800" />
      <div className="mt-2 h-4 w-1/2 animate-pulse rounded bg-neutral-200 dark:bg-neutral-800" />
    </section>
  );
}
