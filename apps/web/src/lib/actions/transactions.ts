"use server";

import { revalidatePath } from "next/cache";

import { apiDelete, apiPost, apiPut } from "@/lib/api";
import {
  splitTags,
  text,
  toFormState,
  toInstant,
  type FormState,
} from "@/lib/actions/form-state";
import type { Transaction } from "@/lib/types";

export async function saveTransaction(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");
  const occurredAt = toInstant(text(form, "occurredAt"));

  if (!occurredAt) {
    return { ok: false, fields: { occurredAt: "Enter a valid date and time" } };
  }

  const payload = {
    kind: text(form, "kind") ?? "expense",
    amount: text(form, "amount"),
    currency: text(form, "currency") ?? "INR",
    occurredAt,
    description: text(form, "description"),
    notes: text(form, "notes"),
    tags: splitTags(text(form, "tags")),
    accountId: text(form, "accountId"),
    categoryId: text(form, "categoryId"),
    merchant: text(form, "merchant"),
    externalRef: text(form, "externalRef"),
    isExcluded: form.get("isExcluded") === "on",
  };

  try {
    if (id) {
      await apiPut<Transaction>(`/api/transactions/${id}`, payload);
    } else {
      await apiPost<Transaction>("/api/transactions", payload);
    }
  } catch (error) {
    return toFormState(error);
  }

  revalidateViews();
  return { ok: true, message: id ? "Transaction updated." : "Transaction added." };
}

export async function saveTransfer(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const occurredAt = toInstant(text(form, "occurredAt"));

  if (!occurredAt) {
    return { ok: false, fields: { occurredAt: "Enter a valid date and time" } };
  }

  try {
    await apiPost<Transaction[]>("/api/transactions/transfers", {
      fromAccountId: text(form, "fromAccountId"),
      toAccountId: text(form, "toAccountId"),
      amount: text(form, "amount"),
      currency: text(form, "currency") ?? "INR",
      occurredAt,
      description: text(form, "description"),
      notes: text(form, "notes"),
    });
  } catch (error) {
    return toFormState(error);
  }

  revalidateViews();
  return { ok: true, message: "Transfer recorded." };
}

export async function removeTransaction(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");
  if (!id) {
    return { ok: false, message: "Missing transaction id." };
  }

  try {
    await apiDelete<void>(`/api/transactions/${id}`);
  } catch (error) {
    return toFormState(error);
  }

  revalidateViews();
  return { ok: true, message: "Transaction deleted." };
}

/** Balances and totals are derived, so every view that shows them must refresh. */
function revalidateViews() {
  revalidatePath("/transactions");
  revalidatePath("/accounts");
  revalidatePath("/dashboard");
}
