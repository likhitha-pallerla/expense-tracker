import { Platform } from 'react-native';

import type { QueuedSms } from '../lib/queue.ts';

/**
 * Reading the phone's message inbox.
 *
 * Everything platform-specific about SMS is behind this one file, for two
 * reasons. The obvious one is iOS, where no application may read messages at
 * all — Apple does not expose it, and no entitlement changes that. The less
 * obvious one is that the native module is absent in Expo Go and in the web
 * build, so the common way to run this app during development has no SMS
 * capability either.
 *
 * That makes "no SMS available" the normal case rather than an error case, and
 * the app is built accordingly: everything else works without it, and the SMS
 * screen explains which of the several reasons applies rather than showing a
 * dead button.
 */

export type SmsAvailability =
  | { available: true }
  | {
      available: false;
      /**
       * - `unsupported-platform` — iOS or web, where this can never work.
       * - `needs-development-build` — Android, but running under Expo Go,
       *   which cannot load custom native code.
       * - `permission-denied` — the user said no, or revoked it later in
       *   Android settings.
       */
      reason: 'unsupported-platform' | 'needs-development-build' | 'permission-denied';
      explanation: string;
    };

/** A message exactly as the device stores it. */
export type RawSms = {
  address: string;
  body: string;
  /** Milliseconds since the epoch, from the message row — not the clock. */
  date: number;
};

type NativeModule = {
  hasPermission(): Promise<boolean>;
  requestPermission(): Promise<boolean>;
  readInbox(since: number, limit: number): Promise<RawSms[]>;
};

/**
 * Resolves the native module, or null when it is not in this build.
 *
 * Deliberately a `require` inside a `try`. A static import would make the whole
 * module graph fail to load in Expo Go, taking the entire app down rather than
 * just the one feature that is unavailable.
 */
function nativeModule(): NativeModule | null {
  if (Platform.OS !== 'android') return null;
  try {
     
    const module = require('../../modules/expo-sms-inbox/src/index.ts') as NativeModule;
    return module ?? null;
  } catch {
    return null;
  }
}

export async function availability(): Promise<SmsAvailability> {
  if (Platform.OS !== 'android') {
    return {
      available: false,
      reason: 'unsupported-platform',
      explanation:
        Platform.OS === 'ios'
          ? 'iOS does not let any app read text messages. Connect your email instead, or add expenses by hand.'
          : 'Reading text messages only works in the Android app.',
    };
  }

  const native = nativeModule();
  if (!native) {
    return {
      available: false,
      reason: 'needs-development-build',
      explanation:
        'Reading messages needs a development build of the app. Expo Go cannot load the code that does it.',
    };
  }

  if (!(await native.hasPermission())) {
    return {
      available: false,
      reason: 'permission-denied',
      explanation:
        'We need permission to read text messages. Only bank alerts are ever uploaded — messages from people never leave your phone.',
    };
  }

  return { available: true };
}

/** Asks for the permission. Returns false if refused, including permanently. */
export async function requestPermission(): Promise<boolean> {
  const native = nativeModule();
  if (!native) return false;
  return native.requestPermission();
}

/**
 * Reads messages that arrived after a point in time.
 *
 * `limit` is a guard rather than a preference: an inbox with 40,000 messages in
 * it would otherwise be pulled across the bridge in one array and take the app
 * down with it. Callers page by moving `since` forward.
 */
export async function readInbox(since: Date, limit = 500): Promise<QueuedSms[]> {
  const native = nativeModule();
  if (!native) return [];

  const rows = await native.readInbox(since.getTime(), limit);
  return rows.map((row) => ({
    sender: row.address,
    body: row.body,
    // The stored timestamp, converted but never replaced. Substituting the
    // current time here would give the same message a different fingerprint on
    // every scan, and the user would watch one payment multiply.
    receivedAt: new Date(row.date).toISOString(),
  }));
}
