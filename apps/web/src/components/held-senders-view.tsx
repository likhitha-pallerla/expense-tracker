"use client";

import { useActionState, useEffect } from "react";

import { Button, Card, EmptyState } from "@/components/ui/form";
import {
  discardHeld,
  trustSender,
  untrustSender,
} from "@/lib/actions/parsing";
import { idleState } from "@/lib/actions/form-state";
import { track } from "@/lib/analytics";
import { formatDateTime } from "@/lib/format";
import type { HeldSender, TrustedSender } from "@/lib/types";

/**
 * The screen where a user decides whether a sender is really their bank.
 *
 * Mail is not authenticated. Anyone who knows this address can send a message
 * reading "Rs 48,500 debited", and a parser that trusts any message mentioning
 * an amount would write that number into the user's history. So an alert from
 * a sender we cannot place is held here rather than recorded.
 *
 * The question is deliberately asked about the *sender*, once, with the
 * messages counted underneath -- not once per message. Asking ten times is how
 * you train someone to stop reading the question.
 */
export function HeldSendersView({
  held,
  trusted,
}: {
  held: HeldSender[];
  trusted: TrustedSender[];
}) {
  return (
    <div className="space-y-6">
      <Card title="Waiting on you">
        <p className="mb-4 text-sm text-neutral-600 dark:text-neutral-400">
          These looked like payment alerts, but arrived from an address we
          cannot place as a bank. Nothing from them has been recorded.
        </p>
        {held.length === 0 ? (
          <EmptyState>
            Nothing is being held. Alerts from senders you have accepted are
            read as they arrive.
          </EmptyState>
        ) : (
          <ul className="divide-y divide-neutral-200 dark:divide-neutral-800">
            {held.map((sender) => (
              <HeldRow key={sender.sender ?? "unknown"} held={sender} />
            ))}
          </ul>
        )}
      </Card>

      <Card title="Senders you accept">
        <p className="mb-4 text-sm text-neutral-600 dark:text-neutral-400">
          Alerts from these domains are read automatically. Remove one and its
          alerts go back to waiting here.
        </p>
        {trusted.length === 0 ? (
          <EmptyState>
            You have not accepted any senders yet. The built-in list of banks is
            always recognised and does not appear here.
          </EmptyState>
        ) : (
          <ul className="divide-y divide-neutral-200 dark:divide-neutral-800">
            {trusted.map((sender) => (
              <TrustedRow key={sender.domain} sender={sender} />
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}

function HeldRow({ held }: { held: HeldSender }) {
  const [trustState, submitTrust] = useActionState(trustSender, idleState);
  const [discardState, submitDiscard] = useActionState(discardHeld, idleState);
  const state = trustState.message ? trustState : discardState;

  // This gate is a security control, and a security control nobody can live
  // with gets worked around. A high discard rate means it is holding junk; a
  // high trust rate means the built-in list is missing real banks. Only the
  // decision is recorded -- never the sender, which is an address.
  useEffect(() => {
    if (trustState.ok) track("held_sender_resolved", { choice: "trusted" });
  }, [trustState.ok]);
  useEffect(() => {
    if (discardState.ok) track("held_sender_resolved", { choice: "discarded" });
  }, [discardState.ok]);

  return (
    <li className="space-y-3 py-4 first:pt-0 last:pb-0">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div className="min-w-0">
          <p className="truncate font-medium text-neutral-900 dark:text-neutral-100">
            {held.sender ?? "Unknown sender"}
          </p>
          <p className="text-sm text-neutral-500 dark:text-neutral-400">
            {held.messages === 1
              ? "1 alert held"
              : `${held.messages} alerts held`}
            {held.latest ? ` · latest ${formatDateTime(held.latest)}` : ""}
          </p>
        </div>
      </div>

      {held.latestSubject && (
        // Shown so the user has something to recognise, and deliberately as
        // plain text: the whole premise is that this content is untrusted.
        <p className="truncate rounded bg-neutral-50 px-3 py-2 text-sm text-neutral-600 dark:bg-neutral-900 dark:text-neutral-400">
          {held.latestSubject}
        </p>
      )}

      {held.reason && (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          {held.reason}
        </p>
      )}

      {state.message && (
        <p
          className={`text-sm ${state.ok ? "text-emerald-600" : "text-red-600"}`}
        >
          {state.message}
        </p>
      )}

      <div className="flex flex-wrap items-center gap-2">
        {held.canBeTrusted && held.domain ? (
          <form action={submitTrust}>
            <input type="hidden" name="domain" value={held.domain} />
            <input type="hidden" name="note" value={held.sender ?? ""} />
            <Button type="submit">Yes, this is my bank</Button>
          </form>
        ) : (
          // No button at all rather than one that will be refused. Trusting a
          // consumer mail domain would trust everyone who has an address there.
          <p className="text-sm text-amber-700 dark:text-amber-400">
            This cannot be accepted as an automatic sender. Add anything real
            from here by hand.
          </p>
        )}

        <form action={submitDiscard}>
          <input type="hidden" name="sender" value={held.sender ?? ""} />
          <Button type="submit" variant="secondary">
            Discard these
          </Button>
        </form>
      </div>
    </li>
  );
}

function TrustedRow({ sender }: { sender: TrustedSender }) {
  const [state, submit] = useActionState(untrustSender, idleState);

  return (
    <li className="flex flex-wrap items-center justify-between gap-3 py-3 first:pt-0 last:pb-0">
      <div className="min-w-0">
        <p className="truncate font-medium text-neutral-900 dark:text-neutral-100">
          {sender.domain}
        </p>
        <p className="text-sm text-neutral-500 dark:text-neutral-400">
          {sender.note ? `${sender.note} · ` : ""}
          accepted {formatDateTime(sender.since)}
        </p>
        {state.message && (
          <p
            className={`text-sm ${state.ok ? "text-emerald-600" : "text-red-600"}`}
          >
            {state.message}
          </p>
        )}
      </div>
      <form action={submit}>
        <input type="hidden" name="domain" value={sender.domain} />
        <Button type="submit" variant="secondary">
          Remove
        </Button>
      </form>
    </li>
  );
}
