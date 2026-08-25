/**
 * The response headers that decide what a browser will let this page do.
 *
 * Every page here is behind a login and several of them act on one click:
 * delete a transaction, release a held message, trust a sender. A page like
 * that framed inside another site is a working attack -- the victim clicks
 * what looks like a game and is really clicking our buttons. `frame-ancestors`
 * is the fix, and it only exists in a header; there is no meta-tag form of it.
 *
 * The Content-Security-Policy is nonce-based rather than `unsafe-inline`.
 * Next injects its own inline hydration scripts, so the usual shortcut is to
 * allow all inline script, which is the same as having no script policy at
 * all. Instead a fresh nonce is minted per request, handed to Next through the
 * request headers, and Next stamps it onto the scripts it generates.
 *
 * This module deliberately imports nothing. next.config.ts is compiled on its
 * own, without the "@/..." path aliases, so anything reached from here has to
 * resolve with no help -- which is why the origins are passed in rather than
 * read from the env module.
 */

/**
 * A single-use value tying our scripts to this one response.
 *
 * Uses the Web Crypto API rather than node:crypto because middleware runs on
 * the Edge runtime, where node:crypto is not available.
 */
export function makeNonce(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes));
}

/**
 * Builds the policy for one request.
 *
 * @param nonce       the per-response value Next will stamp onto its own scripts
 * @param supabaseUrl contacted directly from the browser for auth and realtime
 * @param apiBaseUrl  where the app's own API lives
 * @param isDev       development needs `unsafe-eval` for React Fast Refresh and
 *                    websockets for the dev server; neither is allowed in a
 *                    deployed build
 * @param analyticsHost PostHog's ingestion origin, or empty when analytics are
 *                    not configured. Passed in rather than always allowed so an
 *                    installation without analytics has no third-party origin
 *                    in its policy at all.
 */
export function contentSecurityPolicy(
  nonce: string,
  supabaseUrl: string,
  apiBaseUrl: string,
  isDev: boolean,
  analyticsHost = "",
): string {
  // Listed explicitly so that a script which did somehow run could not post
  // what it read to an address of its choosing.
  const supabase = new URL(supabaseUrl).origin;
  const api = new URL(apiBaseUrl).origin;
  const websocket = supabase.replace(/^http/, "ws");
  const analytics = analyticsHost ? new URL(analyticsHost).origin : "";

  const directives: Record<string, string[]> = {
    "default-src": ["'self'"],

    // `strict-dynamic` lets a script we vouched for load the chunks it needs,
    // which is how Next loads code. Without it every chunk URL would have to
    // be listed, and the list changes on every build.
    "script-src": [
      "'self'",
      `'nonce-${nonce}'`,
      "'strict-dynamic'",
      ...(isDev ? ["'unsafe-eval'"] : []),
    ],

    // Styles keep `unsafe-inline`. Next inlines critical CSS without a nonce,
    // and there is no way to opt out of that. A style injection is a defacement
    // rather than a takeover, so this is the one place the trade is worth it.
    "style-src": ["'self'", "'unsafe-inline'"],

    "img-src": ["'self'", "data:", "blob:", supabase],
    "font-src": ["'self'", "data:"],
    "connect-src": [
      "'self'",
      supabase,
      websocket,
      api,
      ...(analytics ? [analytics] : []),
      ...(isDev ? ["ws://localhost:*", "http://localhost:*"] : []),
    ],

    // Nothing here embeds anything, and nothing should embed us.
    "frame-src": ["'none'"],
    "frame-ancestors": ["'none'"],
    "object-src": ["'none'"],

    // Stops a stray <base> tag from silently re-pointing every relative URL on
    // the page, including the ones our own scripts are loaded from.
    "base-uri": ["'self'"],

    // Forms may only submit back to us. An injected form posting a session
    // elsewhere is otherwise not covered by connect-src.
    "form-action": ["'self'"],
  };

  if (!isDev) {
    directives["upgrade-insecure-requests"] = [];
  }

  return Object.entries(directives)
    .map(([name, values]) => (values.length ? `${name} ${values.join(" ")}` : name))
    .join("; ");
}

/**
 * Headers that do not vary per request.
 *
 * Kept apart from the policy above so they can also be set in next.config.ts,
 * which covers the handful of paths middleware does not run on.
 */
export const STATIC_SECURITY_HEADERS = [
  // Belt and braces with frame-ancestors, for anything that predates CSP 2.
  { key: "X-Frame-Options", value: "DENY" },

  // Stops a stored file being re-read as script because its bytes look like
  // script, regardless of the type we served it as.
  { key: "X-Content-Type-Options", value: "nosniff" },

  // Full URLs here name what someone is looking at -- /transactions/<id>. Send
  // the origin only once the request leaves our site.
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },

  // None of these are used, and saying so stops an embedded frame from asking.
  {
    key: "Permissions-Policy",
    value: "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()",
  },

  // Two years, subdomains included. Set unconditionally: browsers ignore it on
  // plain HTTP, so it costs nothing locally and is present from the first
  // deployed response, which is the one that matters.
  {
    key: "Strict-Transport-Security",
    value: "max-age=63072000; includeSubDomains; preload",
  },
] as const;
