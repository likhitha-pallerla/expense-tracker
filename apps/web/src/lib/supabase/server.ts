import { createServerClient } from "@supabase/ssr";
import { cookies } from "next/headers";

import { env } from "@/lib/env";

/**
 * Server-side Supabase client bound to the request cookie jar.
 *
 * `setAll` throws when called from a Server Component (cookies are read-only
 * there). That is safe to swallow because `middleware.ts` refreshes the session
 * on every request, so the cookie is already up to date by the time a Server
 * Component runs.
 */
export async function createClient() {
  const cookieStore = await cookies();

  return createServerClient(env.supabaseUrl, env.supabaseAnonKey, {
    cookies: {
      getAll() {
        return cookieStore.getAll();
      },
      setAll(cookiesToSet) {
        try {
          cookiesToSet.forEach(({ name, value, options }) => {
            cookieStore.set(name, value, options);
          });
        } catch {
          // Called from a Server Component — middleware handles the refresh.
        }
      },
    },
  });
}
