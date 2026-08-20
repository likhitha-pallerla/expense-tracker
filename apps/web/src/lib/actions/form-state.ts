import { ApiError } from "@/lib/api-error";

/**
 * Result of a form submission.
 *
 * Errors are returned rather than thrown so the form can re-render with the
 * user's input intact; throwing would swap the page for an error boundary and
 * lose everything they typed.
 */
export type FormState = {
  ok: boolean;
  message?: string;
  /** Per-field messages, keyed by input name. */
  fields?: Record<string, string>;
};

export const idleState: FormState = { ok: false };

/** Reads a trimmed field, treating blank as absent. */
export function text(form: FormData, key: string): string | null {
  const value = form.get(key);
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}

/**
 * A `datetime-local` value is wall-clock time with no zone, so it is parsed in
 * the server's zone and converted to the instant the API stores.
 */
export function toInstant(value: string | null): string | null {
  if (!value) return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

export function splitTags(value: string | null): string[] {
  if (!value) return [];
  return value
    .split(",")
    .map((tag) => tag.trim())
    .filter(Boolean);
}

export function toFormState(error: unknown): FormState {
  if (error instanceof ApiError) {
    return { ok: false, message: error.message, fields: error.fields };
  }
  return { ok: false, message: (error as Error).message };
}
