"use client";

import { useActionState } from "react";

import {
  connectMailbox,
  disconnectMailbox,
  syncMailboxes,
} from "@/lib/actions/connections";
import { ignoreMessage, retryUnread } from "@/lib/actions/parsing";
import { idleState } from "@/lib/actions/form-state";
import { Button, Card, EmptyState } from "@/components/ui/form";
import { formatDateTime } from "@/lib/format";
import type {
  MailConnection,
  MailProviderOption,
  SyncRun,
  UnreadMessage,
} from "@/lib/types";

const STATUS_CHIP: Record<MailConnection["status"], string> = {
  active: "bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300",
  needs_reauth: "bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300",
  paused: "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
  error: "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300",
  revoked: "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
};

/** What each provider will be allowed to do, in plain words. */
const PERMISSIONS: Record<MailProviderOption["provider"], string> = {
  gmail: "Read-only access to your Gmail. It cannot send, delete or change anything.",
  outlook: "Read-only access to your Outlook mail. It cannot send, delete or change anything.",
};

function ConnectButton({
  option,
  label,
}: {
  option: MailProviderOption;
  label: string;
}) {
  const [state, submit] = useActionState(connectMailbox, idleState);

  return (
    <form action={submit} className="flex items-center gap-3">
      <input type="hidden" name="provider" value={option.provider} />
      {state.message && !state.ok && (
        <span className="text-xs text-red-600">{state.message}</span>
      )}
      <Button type="submit" disabled={!option.configured}>
        {label}
      </Button>
    </form>
  );
}

function DisconnectButton({ connection }: { connection: MailConnection }) {
  const [state, submit] = useActionState(disconnectMailbox, idleState);

  return (
    <form action={submit} className="flex items-center gap-3">
      <input type="hidden" name="id" value={connection.id} />
      {state.message && !state.ok && (
        <span className="text-xs text-red-600">{state.message}</span>
      )}
      <Button type="submit" variant="danger">
        Disconnect
      </Button>
    </form>
  );
}

/**
 * Asks for new mail.
 *
 * <p>The result is shown here rather than as a page-level banner because it
 * belongs to one mailbox: "nothing new" against a mailbox that is failing would
 * be actively misleading if it appeared at the top of the page.
 */
function CheckButton({
  id,
  label,
  variant = "secondary",
}: {
  id?: string;
  label: string;
  variant?: "primary" | "secondary";
}) {
  const [state, submit, pending] = useActionState(syncMailboxes, idleState);

  return (
    <form action={submit} className="flex flex-wrap items-center justify-end gap-3">
      {id && <input type="hidden" name="id" value={id} />}
      {state.message && (
        <span
          className={`text-xs ${state.ok ? "text-neutral-500" : "text-red-600"}`}
        >
          {state.message}
        </span>
      )}
      <Button type="submit" variant={variant} disabled={pending}>
        {pending ? "Checking…" : label}
      </Button>
    </form>
  );
}

function ConnectionRow({ connection }: { connection: MailConnection }) {
  return (
    <li className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-neutral-200 p-3 dark:border-neutral-800">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium">
            {connection.address ?? connection.label ?? "Connected mailbox"}
          </span>
          <span
            className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_CHIP[connection.status]}`}
          >
            {connection.statusDetail}
          </span>
        </div>
        <p className="mt-1 text-xs text-neutral-500">
          {connection.lastSyncedAt
            ? `Last checked ${formatDateTime(connection.lastSyncedAt)}`
            : "Not checked yet"}
          {connection.connectedAt &&
            ` · Connected ${formatDateTime(connection.connectedAt)}`}
        </p>
        {connection.lastError && (
          <p className="mt-1 text-xs text-red-600">{connection.lastError}</p>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <CheckButton id={connection.id} label="Check now" />
        <DisconnectButton connection={connection} />
      </div>
    </li>
  );
}

const RUN_CHIP: Record<SyncRun["status"], string> = {
  ok: "bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300",
  running: "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
  failed: "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300",
};

/**
 * The history of every check, successful or not.
 *
 * <p>This exists so that "why has nothing appeared?" has an answer that is not
 * a shrug. A mailbox that was checked and genuinely had nothing in it looks
 * quite different from one that has been failing quietly for a week.
 */
function RunHistory({ runs }: { runs: SyncRun[] }) {
  return (
    <Card
      title="Recent checks"
      action={<CheckButton label="Check all mailboxes" variant="primary" />}
    >
      {runs.length === 0 ? (
        <EmptyState>
          No checks yet. Connect a mailbox, then check it for new alerts —
          nothing is read until you ask.
        </EmptyState>
      ) : (
        <ul className="space-y-2">
          {runs.map((run) => (
            <li
              key={run.id}
              className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-neutral-200 p-3 dark:border-neutral-800"
            >
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium">{run.summary}</span>
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs font-medium ${RUN_CHIP[run.status]}`}
                  >
                    {run.status === "ok"
                      ? "Done"
                      : run.status === "running"
                        ? "Running"
                        : "Failed"}
                  </span>
                </div>
                <p className="mt-1 text-xs text-neutral-500">
                  {formatDateTime(run.startedAt)}
                  {run.hasMore && " · more mail is waiting, check again"}
                </p>
                {run.error && (
                  <p className="mt-1 text-xs text-red-600">{run.error}</p>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

/**
 * Alerts that were stored but could not be turned into a transaction.
 *
 * <p>Shown rather than hidden because the alternative is money quietly missing
 * from the totals. A user who can see what was skipped can add it by hand; a
 * user who cannot has no way of knowing their spending is understated.
 */
function UnreadCard({ unread }: { unread: UnreadMessage[] }) {
  // Wrapped rather than passed directly: retrying takes no input, and giving
  // the action two parameters it never reads only to satisfy the hook would be
  // a lie about what it needs.
  const [state, submit, pending] = useActionState(() => retryUnread(), idleState);

  return (
    <Card
      title="Alerts we couldn't read"
      action={
        <form action={submit} className="flex items-center gap-3">
          {state.message && (
            <span
              className={`text-xs ${state.ok ? "text-neutral-500" : "text-red-600"}`}
            >
              {state.message}
            </span>
          )}
          <Button type="submit" disabled={pending}>
            {pending ? "Trying…" : "Try again"}
          </Button>
        </form>
      }
    >
      <p className="text-sm text-neutral-600 dark:text-neutral-400">
        These arrived from a bank we don&rsquo;t understand yet, so nothing was
        added for them. Add them by hand if they matter — and try again after an
        update, because the rules improve.
      </p>
      <ul className="mt-4 space-y-2">
        {unread.map((message) => (
          <li
            key={message.id}
            className="flex flex-wrap items-start justify-between gap-3 rounded-md border border-neutral-200 p-3 dark:border-neutral-800"
          >
            <div className="min-w-0">
              <p className="text-sm font-medium">
                {message.subject ?? "(no subject)"}
              </p>
              <p className="mt-1 text-xs text-neutral-500">
                {message.sender ?? "Unknown sender"}
                {message.receivedAt &&
                  ` · ${formatDateTime(message.receivedAt)}`}
              </p>
              {message.reason && (
                <p className="mt-1 text-xs text-amber-700 dark:text-amber-400">
                  {message.reason}
                </p>
              )}
              {message.snippet && (
                <p className="mt-1 truncate text-xs text-neutral-500">
                  {message.snippet}
                </p>
              )}
            </div>
            <IgnoreButton id={message.id} />
          </li>
        ))}
      </ul>
    </Card>
  );
}

function IgnoreButton({ id }: { id: string }) {
  const [state, submit, pending] = useActionState(ignoreMessage, idleState);

  return (
    <form action={submit} className="flex items-center gap-3">
      <input type="hidden" name="id" value={id} />
      {state.message && !state.ok && (
        <span className="text-xs text-red-600">{state.message}</span>
      )}
      <Button type="submit" variant="secondary" disabled={pending}>
        {pending ? "Ignoring…" : "Ignore"}
      </Button>
    </form>
  );
}

export function ConnectionsView({
  providers,
  runs,
  unread,
}: {
  providers: MailProviderOption[];
  runs: SyncRun[];
  unread: UnreadMessage[];
}) {
  const connected = providers.some((option) => option.connections.length > 0);

  return (
    <div className="space-y-6">
      {connected && <RunHistory runs={runs} />}

      {unread.length > 0 && <UnreadCard unread={unread} />}

      {providers.map((option) => (
        <Card
          key={option.provider}
          title={option.label}
          action={
            <ConnectButton
              option={option}
              label={
                option.connections.length > 0
                  ? "Reconnect"
                  : `Connect ${option.label}`
              }
            />
          }
        >
          <p className="text-sm text-neutral-600 dark:text-neutral-400">
            {PERMISSIONS[option.provider]}
          </p>

          {!option.configured && (
            <p className="mt-3 rounded-md bg-neutral-100 px-3 py-2 text-xs text-neutral-600 dark:bg-neutral-900 dark:text-neutral-400">
              {option.label} is not set up on this server yet, so connecting is
              switched off. Nothing is wrong with your account.
            </p>
          )}

          {option.connections.length > 0 && (
            <ul className="mt-4 space-y-2">
              {option.connections.map((connection) => (
                <ConnectionRow key={connection.id} connection={connection} />
              ))}
            </ul>
          )}
        </Card>
      ))}

      {providers.length === 0 && (
        <EmptyState>No mail providers are available.</EmptyState>
      )}

      <Card title="What happens to your mail">
        <ul className="space-y-2 text-sm text-neutral-600 dark:text-neutral-400">
          <li>
            Only messages that look like bank or payment alerts are read. Nothing
            else is opened, stored or sent anywhere.
          </li>
          <li>
            Nothing is read on a schedule. Mail is only ever checked when you
            press the button, and only mail that arrived since the last check.
          </li>
          <li>
            Access is read-only. Even if this app were compromised, it could not
            send mail from your account or delete anything.
          </li>
          <li>
            Sign-in tokens are encrypted before they are stored, and are never
            sent back to the browser.
          </li>
          <li>
            Disconnecting deletes the token immediately. Transactions already
            imported stay — they are yours, not the mailbox&rsquo;s.
          </li>
        </ul>
      </Card>
    </div>
  );
}
