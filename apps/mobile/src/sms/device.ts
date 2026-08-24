import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

import { api, ApiError } from '../lib/api.ts';
import { SmsQueue, type QueuedSms, type QueueStorage, type UploadOutcome } from '../lib/queue.ts';
import type { SmsIngestResult } from '../lib/types.ts';

/**
 * The concrete wiring behind `syncSms`: where the queue lives, what identifies
 * this handset, and how a batch actually reaches the server.
 *
 * Kept apart from `sync.ts` so that the decisions in that file can be tested
 * without a device, storage, or a network.
 */

const QUEUE_KEY = 'sms.queue.v1';
const WATERMARK_KEY = 'sms.lastScan.v1';
const DEVICE_KEY = 'sms.deviceId.v1';

/**
 * The queue is held in AsyncStorage, not SecureStore.
 *
 * SecureStore caps a value at about 2 KB, and this holds up to two thousand
 * messages. More to the point, the contents are transient copies of alerts the
 * phone already stores unencrypted in its own inbox, so encrypting them here
 * would protect nothing that is not already exposed. The session token is a
 * different matter and does go in SecureStore.
 */
const queueStorage: QueueStorage = {
  read: () => AsyncStorage.getItem(QUEUE_KEY),
  write: (value) => AsyncStorage.setItem(QUEUE_KEY, value),
};

export const smsQueue = new SmsQueue(queueStorage);

export async function lastScanAt(): Promise<Date | null> {
  const raw = await AsyncStorage.getItem(WATERMARK_KEY);
  if (!raw) return null;
  const at = new Date(raw);
  // A watermark that will not parse is worse than none: `new Date(NaN)`
  // propagates into the scan window and the query returns nothing at all,
  // which looks exactly like an empty inbox.
  return Number.isNaN(at.getTime()) ? null : at;
}

export async function rememberScan(at: Date): Promise<void> {
  await AsyncStorage.setItem(WATERMARK_KEY, at.toISOString());
}

export async function forgetScans(): Promise<void> {
  await AsyncStorage.removeItem(WATERMARK_KEY);
}

/**
 * A stable name for this installation.
 *
 * Generated rather than derived from anything the hardware reports. A device
 * serial or advertising id would identify the person across applications, which
 * is not something an expense tracker has any business doing; a random value
 * kept in the keychain does the one job needed — telling this phone's uploads
 * apart from a tablet's — and means nothing anywhere else.
 *
 * Reinstalling produces a new id and therefore a new connection row. That is
 * correct: the reinstalled app has no queue and no watermark, so it is a new
 * source as far as the server is concerned.
 */
export async function deviceId(): Promise<string> {
  if (Platform.OS === 'web') {
    // No keychain in a browser. The web build cannot read SMS anyway, so this
    // only ever exists to keep the code path from throwing during development.
    const existing = await AsyncStorage.getItem(DEVICE_KEY);
    if (existing) return existing;
    const created = randomId();
    await AsyncStorage.setItem(DEVICE_KEY, created);
    return created;
  }

  const existing = await SecureStore.getItemAsync(DEVICE_KEY);
  if (existing) return existing;

  const created = randomId();
  await SecureStore.setItemAsync(DEVICE_KEY, created);
  return created;
}

function randomId(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * Sends one batch and translates the outcome into something the queue can act
 * on.
 *
 * The translation is the point. `ApiError.retryable` already knows that a
 * timeout is worth another go and a 400 never will be, so the queue does not
 * have to learn anything about HTTP.
 */
export async function uploadBatch(
  batch: QueuedSms[],
  isFinalBatch: boolean,
): Promise<UploadOutcome> {
  try {
    const result = await api.post<SmsIngestResult>(`/api/sms?parse=${isFinalBatch}`, {
      deviceId: await deviceId(),
      deviceName: deviceLabel(),
      messages: batch,
    });
    return { kind: 'ok', stored: result.stored, duplicates: result.duplicates };
  } catch (error) {
    if (error instanceof ApiError) {
      return error.retryable
        ? { kind: 'retry', error: error.message }
        : { kind: 'rejected', error: error.message };
    }
    // An unrecognised failure is assumed retryable. Discarding messages on the
    // strength of an error nobody has seen before is the more expensive guess.
    return { kind: 'retry', error: 'Something went wrong sending your messages.' };
  }
}

function deviceLabel(): string {
  return Platform.OS === 'android' ? 'Android phone' : `${Platform.OS} device`;
}
