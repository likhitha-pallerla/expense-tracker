/**
 * Reads the public Supabase config and fails loudly at startup rather than
 * surfacing a confusing "Invalid API key" later at request time.
 */
function required(name: string, value: string | undefined): string {
  if (!value) {
    throw new Error(
      `Missing environment variable ${name}. Copy .env.local.example to .env.local and fill it in.`,
    );
  }
  return value;
}

export const env = {
  supabaseUrl: required(
    "NEXT_PUBLIC_SUPABASE_URL",
    process.env.NEXT_PUBLIC_SUPABASE_URL,
  ),
  supabaseAnonKey: required(
    "NEXT_PUBLIC_SUPABASE_ANON_KEY",
    process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY,
  ),
  apiBaseUrl: process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080",

  /**
   * Where analytics events are sent, or "" when analytics are switched off.
   *
   * Empty unless a PostHog key is configured, so that an installation without
   * analytics never names a third-party origin in its Content-Security-Policy.
   */
  analyticsHost: process.env.NEXT_PUBLIC_POSTHOG_KEY
    ? (process.env.NEXT_PUBLIC_POSTHOG_HOST ?? "https://us.i.posthog.com")
    : "",
};
