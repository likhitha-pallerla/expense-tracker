"use client";

import { useActionState, useEffect } from "react";
import { useFormStatus } from "react-dom";

import {
  dismissDuplicate,
  keepBothDuplicates,
  mergeDuplicate,
} from "@/lib/actions/duplicates";
import { idleState } from "@/lib/actions/form-state";
import { track } from "@/lib/analytics";
import { formatDateTime, formatMoney } from "@/lib/format";
import type { DuplicatePair, DuplicateSide } from "@/lib/types";
import { Button, Card, EmptyState, FormMessage } from "@/components/ui/form";

/**
 * Turns the engine's raw signals into sentences.
 *
 * A bare score tells the user nothing they can act on; "same merchant, 2
 * minutes apart, same account" lets them judge it in a second.
 */
function explain(signals: string): string[] {
  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(signals) as Record<string, unknown>;
  } catch {
    return [];
  }

  const reasons: string[] = [];
  const num = (key: string) =>
    typeof parsed[key] === "number" ? (parsed[key] as number) : null;

  if (parsed.externalRef === "equal") {
    reasons.push("Identical bank reference");
  }
  if (parsed.amount === "equal") {
    reasons.push("Same amount");
  }

  const minutes = num("minutesApart");
  if (minutes !== null) {
    if (minutes < 1) reasons.push("Less than a minute apart");
    else if (minutes < 60) reasons.push(`${minutes} minutes apart`);
    else if (minutes < 1440) reasons.push(`${Math.round(minutes / 60)} hours apart`);
    else reasons.push(`${Math.round(minutes / 1440)} days apart`);
  }

  const merchant = num("merchantScore");
  if (merchant !== null) {
    if (merchant >= 0.99) reasons.push("Same merchant");
    else if (merchant >= 0.6) reasons.push("Similar merchant name");
    else if (merchant <= 0.4) reasons.push("Different merchant name");
  }

  const account = num("accountScore");
  if (account === 1) reasons.push("Same account");
  else if (account === 0) reasons.push("Different accounts");

  const source = num("sourceScore");
  if (source === 1) reasons.push("Reported by two different sources");
  else if (source === 0) reasons.push("Both from the same source");

  return reasons;
}

function Side({ side, label }: { side: DuplicateSide; label: string }) {
  return (
    <div className="flex-1 rounded-md border border-neutral-200 p-3 dark:border-neutral-800">
      <p className="text-xs uppercase tracking-wide text-neutral-400">{label}</p>
      <p className="mt-1 font-mono text-sm">
        {formatMoney(side.amount, side.currency)}
      </p>
      <p className="text-sm font-medium">
        {side.merchantName ?? side.description ?? "Untitled"}
      </p>
      <p className="text-xs text-neutral-500">{formatDateTime(side.occurredAt)}</p>
      <p className="text-xs text-neutral-500">{side.accountName ?? "No account"}</p>
    </div>
  );
}

function ActionButton({
  label,
  variant = "secondary",
}: {
  label: string;
  variant?: "primary" | "secondary" | "danger";
}) {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" variant={variant} disabled={pending}>
      {pending ? "Working…" : label}
    </Button>
  );
}

function PairCard({ pair }: { pair: DuplicatePair }) {
  const [mergeState, merge] = useActionState(mergeDuplicate, idleState);
  const [keepState, keep] = useActionState(keepBothDuplicates, idleState);
  const [dismissState, dismiss] = useActionState(dismissDuplicate, idleState);

  const state = [mergeState, keepState, dismissState].find((s) => s.message);
  const reasons = explain(pair.signals);

  // Which way people resolve a duplicate is the feedback signal for the
  // matcher: a run of "kept both" means it is pairing things that are not
  // pairs. The choice is recorded; the transactions themselves are not.
  useEffect(() => {
    if (mergeState.ok) track("duplicate_resolved", { choice: "merged" });
  }, [mergeState.ok]);
  useEffect(() => {
    if (keepState.ok) track("duplicate_resolved", { choice: "kept_both" });
  }, [keepState.ok]);
  useEffect(() => {
    if (dismissState.ok) track("duplicate_resolved", { choice: "dismissed" });
  }, [dismissState.ok]);

  // Once resolved the pair disappears on the next load; until then, say so
  // rather than leaving a stale row that invites a second click.
  if (state?.ok) {
    return (
      <Card>
        <FormMessage state={state} />
      </Card>
    );
  }

  return (
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm font-medium">
          Possible duplicate
          <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-xs font-normal text-amber-800 dark:bg-amber-950 dark:text-amber-300">
            {Math.round(pair.score * 100)}% match
          </span>
        </p>
      </div>

      {reasons.length > 0 && (
        <p className="mt-2 text-xs text-neutral-500">{reasons.join(" · ")}</p>
      )}

      <div className="mt-3 flex flex-col gap-3 sm:flex-row">
        <Side side={pair.a} label="First" />
        <Side side={pair.b} label="Second" />
      </div>

      {state && !state.ok && <div className="mt-3"><FormMessage state={state} /></div>}

      <div className="mt-4 flex flex-wrap gap-2">
        <form action={merge}>
          <input type="hidden" name="id" value={pair.id} />
          <ActionButton label="Same payment — merge" variant="primary" />
        </form>
        <form action={keep}>
          <input type="hidden" name="id" value={pair.id} />
          <ActionButton label="Keep both" />
        </form>
        <form action={dismiss}>
          <input type="hidden" name="id" value={pair.id} />
          <ActionButton label="Dismiss" />
        </form>
      </div>

      <p className="mt-3 text-xs text-neutral-500">
        Merging keeps the earlier transaction and hides the other. Nothing is
        deleted, so this can be undone from the database if you change your mind.
      </p>
    </Card>
  );
}

export function DuplicatesView({ pairs }: { pairs: DuplicatePair[] }) {
  if (pairs.length === 0) {
    return (
      <EmptyState>
        Nothing to review. Transactions that look like the same payment will
        appear here before anything is merged.
      </EmptyState>
    );
  }

  return (
    <ul className="space-y-4">
      {pairs.map((pair) => (
        <li key={pair.id}>
          <PairCard pair={pair} />
        </li>
      ))}
    </ul>
  );
}
