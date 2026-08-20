"use server";

import { revalidatePath } from "next/cache";

import { apiDelete, apiPut } from "@/lib/api";
import { text, toFormState, type FormState } from "@/lib/actions/form-state";
import type { Card } from "@/lib/types";

/** Blank clears the field rather than defaulting it — the bank may not have said. */
function optionalNumber(form: FormData, key: string): number | null {
  const value = text(form, key);
  if (value === null) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export async function saveCard(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const accountId = text(form, "accountId");
  if (!accountId) {
    return { ok: false, message: "Missing account id." };
  }

  const payload = {
    creditLimit: optionalNumber(form, "creditLimit"),
    billingDay: optionalNumber(form, "billingDay"),
    dueDay: optionalNumber(form, "dueDay"),
    statementBalance: optionalNumber(form, "statementBalance"),
    minimumDue: optionalNumber(form, "minimumDue"),
    lastStatementAt: text(form, "lastStatementAt"),
  };

  try {
    await apiPut<Card>(`/api/cards/${accountId}`, payload);
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/cards");
  revalidatePath("/dashboard");
  return { ok: true, message: "Card details saved." };
}

export async function clearCard(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const accountId = text(form, "accountId");
  if (!accountId) {
    return { ok: false, message: "Missing account id." };
  }

  try {
    await apiDelete<void>(`/api/cards/${accountId}`);
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/cards");
  revalidatePath("/dashboard");
  return { ok: true, message: "Card details cleared. The account is untouched." };
}
