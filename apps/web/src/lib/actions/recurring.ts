"use server";

import { revalidatePath } from "next/cache";

import { apiDelete, apiPost, apiPut } from "@/lib/api";
import { text, toFormState, type FormState } from "@/lib/actions/form-state";
import type { Recurring } from "@/lib/types";

function refresh() {
  revalidatePath("/recurring");
  revalidatePath("/dashboard");
}

function optionalNumber(form: FormData, key: string): number | null {
  const value = text(form, key);
  if (value === null) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/** Confirms a detected series, keeping whatever the user renamed it to. */
export async function confirmRecurring(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const matchKey = text(form, "matchKey");
  if (!matchKey) {
    return { ok: false, message: "Missing payment key." };
  }

  try {
    await apiPost<Recurring>("/api/recurring", {
      matchKey,
      name: text(form, "name"),
      categoryId: text(form, "categoryId"),
    });
  } catch (error) {
    return toFormState(error);
  }

  refresh();
  return { ok: true, message: "Tracking this payment." };
}

export async function dismissRecurring(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const matchKey = text(form, "matchKey");
  if (!matchKey) {
    return { ok: false, message: "Missing payment key." };
  }

  try {
    await apiPost<Recurring>("/api/recurring/dismiss", { matchKey });
  } catch (error) {
    return toFormState(error);
  }

  refresh();
  return { ok: true, message: "Dismissed. It will not be suggested again." };
}

export async function saveRecurring(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");

  const payload = {
    name: text(form, "name"),
    amount: optionalNumber(form, "amount"),
    cadence: text(form, "cadence"),
    categoryId: text(form, "categoryId"),
    accountId: text(form, "accountId"),
    nextExpected: text(form, "nextExpected"),
    notes: text(form, "notes"),
    // An unchecked box posts nothing at all, so absence is the "off" signal.
    isActive: form.get("isActive") === "on",
    isSubscription: form.get("isSubscription") === "on",
  };

  try {
    if (id) {
      await apiPut<Recurring>(`/api/recurring/${id}`, payload);
    } else {
      await apiPost<Recurring>("/api/recurring", payload);
    }
  } catch (error) {
    return toFormState(error);
  }

  refresh();
  return { ok: true, message: id ? "Payment updated." : "Payment added." };
}

/**
 * Deleting the saved row. For a dismissal that means the series goes back to
 * being suggested; for a confirmed one it means we stop tracking it by hand.
 */
export async function forgetRecurring(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");
  if (!id) {
    return { ok: false, message: "Missing payment id." };
  }

  try {
    await apiDelete<void>(`/api/recurring/${id}`);
  } catch (error) {
    return toFormState(error);
  }

  refresh();
  return { ok: true, message: "Removed." };
}
