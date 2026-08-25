import type { NextRequest, NextResponse } from "next/server";

import {
  contentSecurityPolicy,
  makeNonce,
  STATIC_SECURITY_HEADERS,
} from "@/lib/security-headers";
import { updateSession } from "@/lib/supabase/middleware";
import { env } from "@/lib/env";

export async function middleware(request: NextRequest) {
  const nonce = makeNonce();
  const csp = contentSecurityPolicy(
    nonce,
    env.supabaseUrl,
    env.apiBaseUrl,
    process.env.NODE_ENV !== "production",
  );

  // Next reads the nonce back off the *request* headers and stamps it onto the
  // scripts it generates. Set only on the response, its own hydration scripts
  // would go out unnonced, the policy would block them, and the page would
  // render but never become interactive.
  const forwarded = new Headers(request.headers);
  forwarded.set("x-nonce", nonce);
  forwarded.set("content-security-policy", csp);

  // updateSession may return a redirect rather than the response it built. The
  // headers belong on whichever it is, and its cookies must survive, so the
  // returned object is amended rather than replaced.
  const response = await updateSession(request, forwarded);
  response.headers.set("Content-Security-Policy", csp);
  for (const { key, value } of STATIC_SECURITY_HEADERS) {
    response.headers.set(key, value);
  }
  return response satisfies NextResponse;
}

export const config = {
  matcher: [
    /*
     * Run on every path except static assets and image files, so the session
     * cookie is refreshed before any page or route handler executes.
     */
    "/((?!_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|jpeg|gif|webp|ico)$).*)",
  ],
};
