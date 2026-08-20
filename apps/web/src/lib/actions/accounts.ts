"use server";

import { revalidatePath } from "next/cache";

import { apiDelete, apiPost, apiPut } from "@/lib/api";
import { text, toFormState, type FormState } from "@/lib/actions/form-state";
import type { Account } from "@/lib/types";

export async function saveAccount(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");
  const payload = {
    name: form.get("name"),
    type: form.get("type"),
    currency: text(form, "currency") ?? "INR",
    last4: text(form, "last4"),
    openingBalance: text(form, "openingBalance") ?? "0",
    isArchived: form.get("isArchived") === "on",
  };

  try {
    if (id) {
      await apiPut<Account>(`/api/accounts/${id}`, payload);
    } else {
      await apiPost<Account>("/api/accounts", payload);
    }
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/accounts");
  revalidatePath("/dashboard");
  return { ok: true, message: id ? "Account updated." : "Account added." };
}

export async function removeAccount(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");
  if (!id) {
    return { ok: false, message: "Missing account id." };
  }

  try {
    // The API archives instead of deleting when transactions still reference
    // the account, so the response says which actually happened.
    const result = await apiDelete<{
      archived: boolean;
      transactionCount: number;
    }>(`/api/accounts/${id}`);

    revalidatePath("/accounts");
    revalidatePath("/dashboard");

    return {
      ok: true,
      message: result.archived
        ? `Archived rather than deleted: ${result.transactionCount} transaction(s) still reference it.`
        : "Account deleted.",
    };
  } catch (error) {
    return toFormState(error);
  }
}
