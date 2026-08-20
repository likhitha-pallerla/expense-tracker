"use server";

import { revalidatePath } from "next/cache";

import { apiPost } from "@/lib/api";
import { toFormState, type FormState } from "@/lib/actions/form-state";
import type { ImportMapping, ImportPreview, ImportResult } from "@/lib/types";

/**
 * Reads the file and reports what an import *would* do, writing nothing.
 *
 * The CSV is sent rather than stored between the two steps: keeping a raw bank
 * statement server-side between requests would mean holding the user's whole
 * transaction history somewhere it does not need to live.
 */
export async function previewImport(input: {
  csv: string;
  filename: string;
  accountId: string | null;
  mapping: ImportMapping | null;
}): Promise<FormState & { preview?: ImportPreview }> {
  try {
    const preview = await apiPost<ImportPreview>("/api/imports/preview", input);
    return { ok: true, preview };
  } catch (error) {
    return toFormState(error);
  }
}

export async function commitImport(input: {
  csv: string;
  filename: string;
  accountId: string;
  mapping: ImportMapping;
  skipRows: number[];
}): Promise<FormState & { result?: ImportResult }> {
  let result: ImportResult;
  try {
    result = await apiPost<ImportResult>("/api/imports", input);
  } catch (error) {
    return toFormState(error);
  }

  // An import moves balances and may queue duplicates, so every view that
  // reads transactions is now stale.
  revalidatePath("/transactions");
  revalidatePath("/dashboard");
  revalidatePath("/accounts");
  revalidatePath("/review");
  revalidatePath("/import");

  return { ok: true, result };
}
