/**
 * Colour and spacing, kept in step with the web app.
 *
 * The values are Tailwind's, copied out by hand because React Native has no
 * Tailwind and the two applications showing the same figures in different
 * greens would look like two different products. Where the web writes
 * `text-emerald-600`, this writes `theme.positive`.
 */

export const theme = {
  background: '#ffffff',
  surface: '#f8fafc',
  border: '#e2e8f0',

  text: '#0f172a',
  muted: '#475569',
  faint: '#94a3b8',

  /** Money coming in, and goals being met. */
  positive: '#059669',
  positiveSoft: '#ecfdf5',
  positiveText: '#047857',

  /** Money going out. Used sparingly: most spending is not a problem. */
  negative: '#e11d48',

  /** Something needing attention but not yet wrong. */
  warning: '#f59e0b',
  warningSoft: '#fffbeb',
  warningText: '#b45309',

  /** No verdict available — the same grey the web uses for "we cannot say". */
  neutral: '#94a3b8',
  neutralSoft: '#f1f5f9',

  accent: '#0f172a',
  onAccent: '#ffffff',
} as const;

export const space = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
} as const;

export const radius = {
  sm: 6,
  md: 10,
  lg: 14,
  pill: 999,
} as const;

export const type = {
  title: { fontSize: 28, fontWeight: '700' as const, color: theme.text },
  heading: { fontSize: 18, fontWeight: '600' as const, color: theme.text },
  body: { fontSize: 15, color: theme.text },
  small: { fontSize: 13, color: theme.muted },
  tiny: { fontSize: 11, color: theme.faint },
  figure: { fontSize: 32, fontWeight: '700' as const, color: theme.text },
} as const;

/**
 * Money, in the user's own currency.
 *
 * Currency comes from the API rather than the device, because someone using the
 * app abroad still thinks in the currency their bank uses. Falls back to the
 * plain number if the runtime cannot format the code — an unfamiliar currency
 * should show an unadorned figure, never `NaN`.
 */
export function money(amount: number, currency = 'INR'): string {
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency,
      maximumFractionDigits: amount % 1 === 0 ? 0 : 2,
    }).format(amount);
  } catch {
    return amount.toLocaleString();
  }
}
