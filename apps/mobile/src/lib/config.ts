/**
 * Where the app points and what it needs to start.
 *
 * Values arrive through Expo's `EXPO_PUBLIC_` mechanism, which inlines them at
 * bundle time. That has a consequence worth stating plainly: anything here ends
 * up readable inside the shipped app, so only values that are already public
 * belong in this file. The Supabase anon key qualifies — it is designed to be
 * handed to clients and is useless without a signed-in user, because row-level
 * security decides what it can reach. The service key emphatically does not,
 * and must never appear in this directory.
 */

const read = (key: string): string | undefined => {
  const value = process.env[key];
  return value && value.length > 0 ? value : undefined;
};

export const SUPABASE_URL = read('EXPO_PUBLIC_SUPABASE_URL');
export const SUPABASE_ANON_KEY = read('EXPO_PUBLIC_SUPABASE_ANON_KEY');

/**
 * The API base.
 *
 * Defaults to the Android emulator's alias for the host machine, because that
 * is where it is run first and a wrong default there wastes an hour on a
 * confusing network error. A real device on the same network needs the host's
 * LAN address instead, and a shipped build needs the deployed URL.
 */
export const API_URL = read('EXPO_PUBLIC_API_URL') ?? 'http://10.0.2.2:8080';

/**
 * Whether the app has enough configuration to work.
 *
 * Checked at startup and reported on screen rather than left to fail as an
 * opaque network error several taps later.
 */
export function missingConfig(): string[] {
  const missing: string[] = [];
  if (!SUPABASE_URL) missing.push('EXPO_PUBLIC_SUPABASE_URL');
  if (!SUPABASE_ANON_KEY) missing.push('EXPO_PUBLIC_SUPABASE_ANON_KEY');
  return missing;
}
