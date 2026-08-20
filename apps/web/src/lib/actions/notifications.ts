"use server";

import { revalidatePath } from "next/cache";

import { apiPost } from "@/lib/api";
import { text, toFormState, type FormState } from "@/lib/actions/form-state";
import type { Notification } from "@/lib/types";

/**
 * The badge lives in the shell, so every page's copy of it is stale after any
 * of these. Revalidating the layout is cheaper than being subtly wrong.
 */
function refresh() {
  revalidatePath("/notifications");
  revalidatePath("/", "layout");
}

async function act(
  form: FormData,
  path: string,
  message: string,
): Promise<FormState> {
  const key = text(form, "key");
  if (!key) {
    return { ok: false, message: "Missing notification key." };
  }

  try {
    await apiPost<Notification>(path, { key });
  } catch (error) {
    return toFormState(error);
  }

  refresh();
  return { ok: true, message };
}

export async function markNotificationRead(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  return act(form, "/api/notifications/read", "Marked as read.");
}

export async function dismissNotification(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  return act(form, "/api/notifications/dismiss", "Dismissed.");
}

export async function restoreNotification(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  return act(form, "/api/notifications/restore", "Back in the list.");
}

/**
 * Takes no arguments: the button posts nothing, there is nothing to read from
 * a form. Called directly from a transition rather than through
 * {@code useActionState}, which would demand a state parameter this does not
 * use.
 */
export async function markAllNotificationsRead(): Promise<FormState> {
  try {
    const { marked } = await apiPost<{ marked: number }>(
      "/api/notifications/read-all",
      {},
    );
    refresh();
    return {
      ok: true,
      message: marked === 0 ? "Nothing new to mark." : `Marked ${marked} read.`,
    };
  } catch (error) {
    return toFormState(error);
  }
}
