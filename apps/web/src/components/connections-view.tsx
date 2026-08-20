"use client";

import { useActionState } from "react";

import { connectMailbox, disconnectMailbox } from "@/lib/actions/connections";
import { idleState } from "@/lib/actions/form-state";
import { Button, Card, EmptyState } from "@/components/ui/form";
import { formatDateTime } from "@/lib/format";
import type { MailConnection, MailProviderOption } from "@/lib/types";

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
      <DisconnectButton connection={connection} />
    </li>
  );
}

export function ConnectionsView({
  providers,
}: {
  providers: MailProviderOption[];
}) {
  return (
    <div className="space-y-6">
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
