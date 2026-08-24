import AsyncStorage from '@react-native-async-storage/async-storage';
import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import 'react-native-url-polyfill/auto';

import { SUPABASE_ANON_KEY, SUPABASE_URL } from './config.ts';

/**
 * The signed-in session, stored where the platform keeps secrets.
 *
 * The web app can lean on an httpOnly cookie; a native app cannot, so the
 * refresh token has to live somewhere on the device. `SecureStore` puts it in
 * the Android Keystore or the iOS keychain, which means it is encrypted at rest
 * and not readable by other applications. `AsyncStorage` — the obvious default,
 * and what most tutorials use — is a plain unencrypted file, which for a token
 * granting access to somebody's entire financial history is not good enough.
 *
 * There is one catch: `SecureStore` refuses values over 2 KB. Supabase sessions
 * are usually well under that, but a JWT with unusually large claims can exceed
 * it, and the failure is silent — the write throws, the session vanishes, and
 * the user is logged out for no visible reason. So oversized values are chunked
 * rather than dropped.
 */
const CHUNK_SIZE = 1_800;

const secureAdapter = {
  async getItem(key: string): Promise<string | null> {
    const head = await SecureStore.getItemAsync(key);
    if (head === null) return null;
    if (!head.startsWith('chunked:')) return head;

    const count = Number(head.slice('chunked:'.length));
    const parts: string[] = [];
    for (let i = 0; i < count; i++) {
      const part = await SecureStore.getItemAsync(`${key}.${i}`);
      // A missing piece means the session is unusable. Returning the fragments
      // we do have would hand Supabase malformed JSON and fail less clearly
      // than simply having no session at all.
      if (part === null) return null;
      parts.push(part);
    }
    return parts.join('');
  },

  async setItem(key: string, value: string): Promise<void> {
    if (value.length <= CHUNK_SIZE) {
      await SecureStore.setItemAsync(key, value);
      return;
    }
    const count = Math.ceil(value.length / CHUNK_SIZE);
    for (let i = 0; i < count; i++) {
      await SecureStore.setItemAsync(`${key}.${i}`, value.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE));
    }
    await SecureStore.setItemAsync(key, `chunked:${count}`);
  },

  async removeItem(key: string): Promise<void> {
    const head = await SecureStore.getItemAsync(key);
    if (head?.startsWith('chunked:')) {
      const count = Number(head.slice('chunked:'.length));
      for (let i = 0; i < count; i++) {
        await SecureStore.deleteItemAsync(`${key}.${i}`);
      }
    }
    await SecureStore.deleteItemAsync(key);
  },
};

/**
 * On web there is no keychain, so `SecureStore` is unavailable entirely.
 * Expo Router renders this app in a browser during development, and a hard
 * crash there would make the web target useless for anything.
 */
const storage = Platform.OS === 'web' ? AsyncStorage : secureAdapter;

export const supabase: SupabaseClient = createClient(SUPABASE_URL ?? '', SUPABASE_ANON_KEY ?? '', {
  auth: {
    storage,
    autoRefreshToken: true,
    persistSession: true,
    // A native app has no URL bar to read a token out of, and leaving this on
    // makes the client try to parse one from the initial location.
    detectSessionInUrl: false,
  },
});

/** The bearer token for API calls, or null when signed out. */
export async function accessToken(): Promise<string | null> {
  const { data } = await supabase.auth.getSession();
  return data.session?.access_token ?? null;
}
