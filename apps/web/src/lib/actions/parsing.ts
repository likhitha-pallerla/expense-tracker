"use server";

import { revalidatePath } from "next/cache";

import { apiPost } from "@/lib/api";
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
