"use server";

import { revalidatePath } from "next/cache";

import { apiDelete, apiPost } from "@/lib/api";
import { text, toFormState, type FormState } from "@/lib/actions/form-state";
import type { ParseResult } from "@/lib/types";

/**
 * Reads alerts already in hand, without going back to the mail provider.
 *
 * <p>Worth having on its own because a rule improving is the usual reason a
 * message could not be read. Re-fetching mail to benefit from a better rule
 * would be absurd, and on Gmail it costs quota.
 */
export async function retryUnread(): Promise<FormState> {
  let result: ParseResult;
  try {
    result = await apiPost<ParseResult>("/api/parse/retry", {});
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/connections");
  revalidatePath("/transactions");
  revalidatePath("/", "layout");

  if (result.read === 0) {
    return { ok: true, message: "Nothing left to read." };
  }
  if (result.failed === result.read) {
    return {
      ok: false,
      message:
        "Still no luck with these. They are kept, so a future improvement can " +
        "pick them up.",
    };
  }
  return { ok: true, message: result.summary };
}

/**
 * Stops trying to read one message.
 *
 * <p>Kept rather than deleted, so the decision can be undone and so the same
 * message arriving again is not re-read from scratch.
 */
export async function ignoreMessage(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");
  if (!id) {
    return { ok: false, message: "Missing message." };
  }

  try {
    await apiPost<{ ignored: boolean }>(`/api/parse/${id}/ignore`, {});
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/connections");
  revalidatePath("/", "layout");
  return { ok: true, message: "We won't try to read that one again." };
}

/**
 * Accepts a sender, and releases everything already held from it.
 *
 * <p>The released messages go back into the normal queue rather than being
 * turned straight into transactions, so they still pass through duplicate
 * detection -- which matters, because a held mail alert has very often already
 * arrived as a text.
 */
export async function trustSender(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const domain = text(form, "domain");
  if (!domain) {
    return { ok: false, message: "Missing sender." };
  }

  let result: { domain: string; released: number };
  try {
    result = await apiPost<{ domain: string; released: number }>(
      "/api/parse/trusted",
      { domain, note: text(form, "note") ?? null },
    );
  } catch (error) {
    // The API refuses consumer mail providers and says why. That reason is
    // the useful part, so it is passed through rather than replaced.
    return toFormState(error);
  }

  revalidatePath("/connections");
  revalidatePath("/transactions");
  revalidatePath("/", "layout");

  if (result.released === 0) {
    return { ok: true, message: `${result.domain} is now a sender we accept.` };
  }
  return {
    ok: true,
    message:
      result.released === 1
        ? `${result.domain} accepted. 1 held alert is being read now.`
        : `${result.domain} accepted. ${result.released} held alerts are being read now.`,
  };
}

/** Withdraws trust. Transactions already recorded are left alone. */
export async function untrustSender(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const domain = text(form, "domain");
  if (!domain) {
    return { ok: false, message: "Missing sender." };
  }

  try {
    await apiDelete<{ removed: boolean }>(
      `/api/parse/trusted/${encodeURIComponent(domain)}`,
    );
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/connections");
  revalidatePath("/", "layout");
  return {
    ok: true,
    message: `Alerts from ${domain} will need confirming again. Anything already recorded stays.`,
  };
}

/** Throws away everything held from a sender, without accepting it. */
export async function discardHeld(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const sender = text(form, "sender");
  if (!sender) {
    return { ok: false, message: "Missing sender." };
  }

  let result: { discarded: number };
  try {
    result = await apiPost<{ discarded: number }>("/api/parse/held/discard", {
      sender,
    });
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/connections");
  revalidatePath("/", "layout");
  return {
    ok: true,
    message:
      result.discarded === 1
        ? "Discarded. That message won't be read."
        : `Discarded ${result.discarded} messages. They won't be read.`,
  };
}
