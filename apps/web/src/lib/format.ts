/**
 * Display formatting. Amounts come from the API as numbers parsed from
 * NUMERIC(14,2); they are formatted for display only and never used for
 * arithmetic that is stored.
 */

export function formatMoney(amount: number, currency = "INR"): string {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency,
    maximumFractionDigits: 2,
  }).format(amount);
}

/** Signed display, so a debit reads as an outflow at a glance. */
export function formatSigned(amount: number, currency = "INR"): string {
  const formatted = formatMoney(Math.abs(amount), currency);
  if (amount < 0) return `-${formatted}`;
  if (amount > 0) return `+${formatted}`;
  return formatted;
}

export function formatDate(iso: string): string {
  return new Intl.DateTimeFormat("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  }).format(new Date(iso));
}

export function formatDateTime(iso: string): string {
  return new Intl.DateTimeFormat("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(iso));
}

/** The value shape a `datetime-local` input expects, in the viewer's zone. */
export function toDateTimeLocal(iso: string): string {
  const date = new Date(iso);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

export function todayDateTimeLocal(): string {
  return toDateTimeLocal(new Date().toISOString());
}
