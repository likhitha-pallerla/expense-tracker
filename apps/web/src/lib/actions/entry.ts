"use server";

import { revalidatePath } from "next/cache";

import { apiPost } from "@/lib/api";
import { text, toFormState, type FormState } from "@/lib/actions/form-state";
import type { EntrySuggestion, Transaction } from "@/lib/types";

/**
 * The state the quick-add box lives in.
 *
 * Carries the sentence back with it so the box can be re-rendered with what the
 * user typed still in it. Losing their words on a failed read would be the
 * single most annoying thing this feature could do.
 */
export type EntryState = FormState & {
  suggestion?: EntrySuggestion;
  typed?: string;
};

export const idleEntry: EntryState = { ok: false };

/**
 * Reads a sentence. Creates nothing.
 *
 * The two steps are separate because free text is genuinely ambiguous — "500
 * mom" could be a gift, a loan or a transfer — and a confirmation costs one
 * tap where a wrongly filed transaction costs a hunt through the list.
 */
export async function readEntry(
  _prev: EntryState,
  form: FormData,
): Promise<EntryState> {
  const typed = text(form, "text");
  if (!typed) {
    return { ok: false, message: "Type what you spent, like \"250 lunch\"." };
  }

  let suggestion: EntrySuggestion;
  try {
    suggestion = await apiPost<EntrySuggestion>("/api/entry/parse", { text: typed });
  } catch (error) {
    return { ...toFormState(error), typed };
  }

  if (suggestion.problem) {
    return { ok: false, message: suggestion.problem, typed };
  }
  return { ok: true, suggestion, typed };
}

/**
 * Files the payment the user has just confirmed.
 *
 * Goes through the ordinary transactions endpoint rather than one of its own,
 * so a transaction typed in words is subject to exactly the same duplicate
 * detection, balance arithmetic and defaults as one entered on the full form.
 */
export async function confirmEntry(
  _prev: EntryState,
  form: FormData,
): Promise<EntryState> {
  const amount = text(form, "amount");
  const occurredOn = text(form, "occurredOn");

  if (!amount || !occurredOn) {
    return { ok: false, message: "Something went missing. Try typing it again." };
  }

  try {
    await apiPost<Transaction>("/api/transactions", {
      kind: text(form, "direction") === "credit" ? "income" : "expense",
      amount,
      currency: text(form, "currency") ?? "INR",
      // The date is a day, not a moment: nobody types the minute they paid at.
      // Midday keeps it on the right side of a timezone shift in either
      // direction, which midnight does not.
      occurredAt: new Date(`${occurredOn}T12:00:00`).toISOString(),
      description: text(form, "description"),
      accountId: text(form, "accountId"),
      categoryId: text(form, "categoryId"),
      merchant: text(form, "merchant"),
    });
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/dashboard");
  revalidatePath("/transactions");
  revalidatePath("/", "layout");
  return { ok: true, message: "Added." };
}
