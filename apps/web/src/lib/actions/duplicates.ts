"use server";

import { revalidatePath } from "next/cache";

import { apiPost } from "@/lib/api";
import { text, toFormState, type FormState } from "@/lib/actions/form-state";

async function resolve(
  form: FormData,
  action: "merge" | "keep-both" | "dismiss",
  message: string,
): Promise<FormState> {
  const id = text(form, "id");
  if (!id) {
    return { ok: false, message: "Missing duplicate id." };
  }

  try {
    await apiPost(`/api/duplicates/${id}/${action}`, {});
  } catch (error) {
    return toFormState(error);
  }

  revalidatePath("/review");
  // Merging changes what the ledger and totals show, so both must refresh.
  revalidatePath("/transactions");
  revalidatePath("/dashboard");
  return { ok: true, message };
}

export async function mergeDuplicate(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  return resolve(form, "merge", "Merged into a single transaction.");
}

export async function keepBothDuplicates(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  return resolve(form, "keep-both", "Kept both transactions.");
}

export async function dismissDuplicate(
  _prev: FormState,
  form: FormData,
): Promise<FormState> {
  return resolve(form, "dismiss", "Dismissed.");
}
