import { NativeModule, requireNativeModule } from 'expo';

/**
 * The JavaScript face of the Android SMS reader.
 *
 * `requireNativeModule` throws when the native side is not in the build, which
 * is the case in Expo Go and on iOS. That is why `src/sms/reader.ts` loads this
 * file inside a try — the throw is expected and is how the app discovers it has
 * no SMS capability, rather than a fault to be fixed.
 */

export type RawSms = {
  address: string;
  body: string;
  /** Milliseconds since the epoch, read from the message row. */
  date: number;
};

declare class ExpoSmsInboxModule extends NativeModule {
  hasPermission(): Promise<boolean>;
  requestPermission(): Promise<{ status: string; granted?: boolean }>;
  readInbox(since: number, limit: number): Promise<RawSms[]>;
}

const native = requireNativeModule<ExpoSmsInboxModule>('ExpoSmsInbox');

export function hasPermission(): Promise<boolean> {
  return native.hasPermission();
}

/**
 * Asks for `READ_SMS`.
 *
 * Expo's permission helper resolves with a status object rather than a boolean,
 * and it reports `granted` on a fresh grant but only `status` on some paths.
 * Normalising here keeps that shape out of the rest of the app, which has no
 * reason to know how Android words a refusal.
 */
export async function requestPermission(): Promise<boolean> {
  const result = await native.requestPermission();
  return result.granted === true || result.status === 'granted';
}

export function readInbox(since: number, limit: number): Promise<RawSms[]> {
  return native.readInbox(since, limit);
}
