"use server";

import { revalidatePath } from "next/cache";

import { apiDelete, apiPost, apiPut } from "@/lib/api";
import { text, toFormState, type FormState } from "@/lib/actions/form-state";
import type { Budget } from "@/lib/types";

/** Checkbox groups post one value per checked box, or nothing at all. */
function thresholds(form: FormData): number[] {
  return form
    .getAll("alertThresholds")
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value));
}

export async function saveBudget(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");
  const chosen = thresholds(form);

  const payload = {
    name: text(form, "name"),
    // An empty select means "every expense", which the API stores as no category.
    categoryId: text(form, "categoryId"),
    amount: form.get("amount"),
    currency: text(form, "currency") ?? "INR",
    period: text(form, "period") ?? "monthly",
    startsOn: text(form, "startsOn"),
    endsOn: text(form, "endsOn"),
    rollover: form.get("rollover") === "on",
    alertThresholds: chosen.length > 0 ? chosen : undefined,
    isActive: form.get("isActive") === "on",
  };

  try {
    if (id) {
      await apiPut<Budget>(`/api/budgets/${id}`, payload);
    } else {
      await apiPost<Budget>("/api/budgets", payload);
    }
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/budgets");
  revalidatePath("/dashboard");
  return { ok: true, message: id ? "Budget updated." : "Budget created." };
}

export async function removeBudget(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");
  if (!id) {
    return { ok: false, message: "Missing budget id." };
  }

  try {
    await apiDelete<void>(`/api/budgets/${id}`);
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/budgets");
  revalidatePath("/dashboard");
  return { ok: true, message: "Budget deleted." };
}
