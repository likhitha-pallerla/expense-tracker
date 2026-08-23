"use server";

import { revalidatePath } from "next/cache";

import { apiDelete, apiPost, apiPut } from "@/lib/api";
import { text, toFormState, type FormState } from "@/lib/actions/form-state";
import type { Goal } from "@/lib/types";

export async function saveGoal(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");

  const payload = {
    name: text(form, "name"),
    targetAmount: form.get("targetAmount"),
    currency: text(form, "currency") ?? "INR",
    targetDate: text(form, "targetDate"),
    monthlyTarget: text(form, "monthlyTarget"),
    accountId: text(form, "accountId"),
    notes: text(form, "notes"),
    status: text(form, "status") ?? "active",
  };

  try {
    if (id) {
      await apiPut<Goal>(`/api/goals/${id}`, payload);
    } else {
      await apiPost<Goal>("/api/goals", payload);
    }
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/goals");
  return { ok: true, message: id ? "Goal updated." : "Goal created." };
}

export async function removeGoal(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "id");
  if (!id) return { ok: false, message: "Missing goal id." };

  try {
    await apiDelete<void>(`/api/goals/${id}`);
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/goals");
  return { ok: true, message: "Goal deleted." };
}

/**
 * Records money going in, or coming back out.
 *
 * A withdrawal is the same call with a negative amount, so the running total
 * stays a plain sum. The form sends the direction separately because typing a
 * minus sign into a number field is not something anyone should have to know
 * to do.
 */
export async function contribute(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const id = text(form, "goalId");
  if (!id) return { ok: false, message: "Missing goal id." };

  const raw = Number(form.get("amount"));
  if (!Number.isFinite(raw) || raw <= 0) {
    return { ok: false, message: "Enter an amount above zero." };
  }

  const withdrawing = text(form, "direction") === "out";

  try {
    await apiPost<Goal>(`/api/goals/${id}/contributions`, {
      amount: withdrawing ? -raw : raw,
      occurredOn: text(form, "occurredOn"),
      note: text(form, "note"),
    });
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/goals");
  return {
    ok: true,
    message: withdrawing ? "Withdrawal recorded." : "Contribution recorded.",
  };
}

export async function removeContribution(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  const goalId = text(form, "goalId");
  const contributionId = text(form, "contributionId");
  if (!goalId || !contributionId) {
    return { ok: false, message: "Missing contribution id." };
  }

  try {
    await apiDelete<void>(`/api/goals/${goalId}/contributions/${contributionId}`);
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/goals");
  return { ok: true, message: "Contribution removed." };
}
