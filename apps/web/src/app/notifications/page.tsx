import { AppShell } from "@/components/app-shell";
import { NotificationsView } from "@/components/notifications-view";
import { EmptyState } from "@/components/ui/form";
import { apiFetch } from "@/lib/api";
import type { Notification } from "@/lib/types";

export const metadata = { title: "Notifications" };

export default async function NotificationsPage({
  searchParams,
}: {
  searchParams: Promise<{ dismissed?: string }>;
}) {
  const params = await searchParams;
  const showDismissed = params.dismissed === "1";

  let notifications: Notification[] = [];
  let error: string | null = null;

  try {
    notifications = await apiFetch<Notification[]>(
      `/api/notifications?includeDismissed=${showDismissed}`,
    );
  } catch (err) {
    error = (err as Error).message;
  }

  return (
    <AppShell
      title="Notifications"
      description="Things that need a decision, in the order they matter."
    >
      {error ? (
        <EmptyState>Could not load notifications: {error}</EmptyState>
      ) : (
        <NotificationsView
          notifications={notifications}
          showingDismissed={showDismissed}
        />
      )}
    </AppShell>
  );
}
