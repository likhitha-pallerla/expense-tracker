"use client";

import { useActionState, useState, useTransition } from "react";
import Link from "next/link";

import {
  dismissNotification,
  markAllNotificationsRead,
  markNotificationRead,
  restoreNotification,
} from "@/lib/actions/notifications";
import { idleState, type FormState } from "@/lib/actions/form-state";
import { Button, Card, EmptyState } from "@/components/ui/form";
import { formatDate } from "@/lib/format";
import type { Notification, NotificationSeverity } from "@/lib/types";

const SEVERITY: Record<
  NotificationSeverity,
  { label: string; chip: string; edge: string }
> = {
  urgent: {
    label: "Needs attention",
    chip: "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300",
    edge: "border-l-red-500",
  },
  warning: {
    label: "Worth a look",
    chip: "bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300",
    edge: "border-l-amber-500",
  },
  info: {
    label: "For information",
    chip: "bg-sky-50 text-sky-700 dark:bg-sky-950 dark:text-sky-300",
    edge: "border-l-sky-400",
  },
};

function KeyedButton({
  notification,
  action,
  label,
  variant = "secondary",
}: {
  notification: Notification;
  action: (prev: FormState, form: FormData) => Promise<FormState>;
  label: string;
  variant?: "primary" | "secondary";
}) {
  const [state, submit] = useActionState(action, idleState);

  if (state.message && !state.ok) {
    return <p className="text-xs text-red-600">{state.message}</p>;
  }

  return (
    <form action={submit}>
      <input type="hidden" name="key" value={notification.key} />
      <Button type="submit" variant={variant}>
        {label}
      </Button>
    </form>
  );
}

function MarkAllButton({ unread }: { unread: number }) {
  const [state, setState] = useState<FormState>(idleState);
  const [pending, startTransition] = useTransition();

  return (
    <div className="flex items-center gap-3">
      {state.message && (
        <span
          className={`text-xs ${state.ok ? "text-neutral-500" : "text-red-600"}`}
        >
          {state.message}
        </span>
      )}
      <Button
        type="button"
        variant="secondary"
        disabled={pending || unread === 0}
        onClick={() =>
          startTransition(async () => setState(await markAllNotificationsRead()))
        }
      >
        {pending ? "Marking…" : "Mark all read"}
      </Button>
    </div>
  );
}

function NotificationRow({
  notification,
}: {
  notification: Notification;
}) {
  const look = SEVERITY[notification.severity] ?? SEVERITY.info;

  return (
    <li
      className={`border-l-4 ${look.edge} ${
        notification.read ? "bg-white dark:bg-neutral-950" : "bg-neutral-50 dark:bg-neutral-900"
      } rounded-r-lg border-y border-r border-neutral-200 p-4 dark:border-neutral-800`}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            {/* Unread is carried by weight rather than a dot, so it survives
                being read out by a screen reader. */}
            <h3
              className={`text-sm ${
                notification.read ? "font-medium" : "font-semibold"
              }`}
            >
              {notification.title}
            </h3>
            <span
              className={`rounded-full px-2 py-0.5 text-xs font-medium ${look.chip}`}
            >
              {look.label}
            </span>
            {!notification.read && (
              <span className="text-xs font-medium text-neutral-500">
                Unread
              </span>
            )}
          </div>
          <p className="mt-1 text-sm text-neutral-600 dark:text-neutral-400">
            {notification.body}
          </p>
          {notification.occurredOn && (
            <p className="mt-1 text-xs text-neutral-500">
              {formatDate(notification.occurredOn)}
            </p>
          )}
        </div>

        <div className="flex shrink-0 flex-wrap items-center gap-2">
          <Link
            href={notification.href}
            className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm font-medium text-neutral-700 transition hover:bg-neutral-100 dark:border-neutral-700 dark:text-neutral-300 dark:hover:bg-neutral-900"
          >
            Open
          </Link>
          {notification.dismissed ? (
            <KeyedButton
              notification={notification}
              action={restoreNotification}
              label="Undo dismiss"
            />
          ) : (
            <>
              {!notification.read && (
                <KeyedButton
                  notification={notification}
                  action={markNotificationRead}
                  label="Mark read"
                />
              )}
              <KeyedButton
                notification={notification}
                action={dismissNotification}
                label="Dismiss"
              />
            </>
          )}
        </div>
      </div>
    </li>
  );
}

export function NotificationsView({
  notifications,
  showingDismissed,
}: {
  notifications: Notification[];
  showingDismissed: boolean;
}) {
  const live = notifications.filter((item) => !item.dismissed);
  const dismissed = notifications.filter((item) => item.dismissed);
  const unread = live.filter((item) => !item.read).length;

  return (
    <div className="space-y-6">
      <Card
        title={
          unread === 0
            ? "Nothing new"
            : `${unread} unread notification${unread === 1 ? "" : "s"}`
        }
      >
        <p className="mb-4 text-sm text-neutral-500">
          Alerts are worked out fresh every time you open this page. Fix the
          underlying thing and the alert disappears on its own.
        </p>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <MarkAllButton unread={unread} />
          <Link
            href={
              showingDismissed ? "/notifications" : "/notifications?dismissed=1"
            }
            className="text-sm font-medium text-neutral-600 underline underline-offset-4 dark:text-neutral-400"
          >
            {showingDismissed ? "Hide dismissed" : "Show dismissed"}
          </Link>
        </div>
      </Card>

      {live.length === 0 ? (
        <EmptyState>
          You&rsquo;re all caught up. No budgets breached, no bills due in the
          next week, and nothing waiting to be reviewed.
        </EmptyState>
      ) : (
        <ul className="space-y-3">
          {live.map((item) => (
            <NotificationRow key={item.key} notification={item} />
          ))}
        </ul>
      )}

      {showingDismissed && (
        <section className="space-y-3">
          <h2 className="text-sm font-semibold text-neutral-500">
            Dismissed
          </h2>
          {dismissed.length === 0 ? (
            <p className="text-sm text-neutral-500">
              Nothing dismissed. Dismissing hides an alert until its situation
              changes — a new budget period, a later due date.
            </p>
          ) : (
            <ul className="space-y-3 opacity-70">
              {dismissed.map((item) => (
                <NotificationRow key={item.key} notification={item} />
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  );
}
