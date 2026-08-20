"use server";

import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";

import { apiDelete, apiPost } from "@/lib/api";
import { text, toFormState, type FormState } from "@/lib/actions/form-state";
import type { SyncRun } from "@/lib/types";

/**
 * Starts the handshake and sends the browser to the provider.
 *
 * <p>Two hops rather than one: the API has to be called with the user's token,
 * which only the server has, and the provider has to be reached by the browser,
 * which only it can do. So the server asks for the URL and then redirects.
 */
export async function connectMailbox(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const provider = text(form, "provider");
  if (!provider) {
    return { ok: false, message: "Missing provider." };
  }

  let authorizeUrl: string;
  try {
    const response = await apiPost<{ authorizeUrl: string }>(
      `/api/connections/${provider}/start`,
      { returnPath: "/connections" },
    );
    authorizeUrl = response.authorizeUrl;
  } catch (error) {
    return toFormState(error);
  }

  // Outside the try: redirect() works by throwing, and catching it here would
  // turn a successful start into an error message.
  redirect(authorizeUrl);
}

export async function disconnectMailbox(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");
  if (!id) {
    return { ok: false, message: "Missing connection." };
  }

  try {
    await apiDelete<void>(`/api/connections/${id}`);
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/connections");
  revalidatePath("/", "layout");
  return { ok: true, message: "Disconnected." };
}

/**
 * Checks a mailbox — or all of them — for new alerts.
 *
 * <p>Nothing polls in the background, so this is the only thing that ever
 * brings mail in. The API runs on a free instance that sleeps when idle, which
 * means a scheduled job would mostly not run at all; asking while the user is
 * here is the only way it happens reliably.
 */
export async function syncMailboxes(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");
  const path = id ? `/api/sync/${id}` : "/api/sync";

  let runs: SyncRun[];
  try {
    const result = await apiPost<SyncRun | SyncRun[]>(path, {});
    runs = Array.isArray(result) ? result : [result];
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/connections");
  revalidatePath("/", "layout");

  if (runs.length === 0) {
    return { ok: true, message: "No mailboxes are connected yet." };
  }

  // One line per mailbox rather than a total. A run that failed and a run that
  // found nothing add up to "0 new", which would hide the failure completely.
  const failed = runs.filter((run) => run.status !== "ok");
  return {
    ok: failed.length === 0,
    message: runs.map((run) => run.summary).join(" "),
  };
}
