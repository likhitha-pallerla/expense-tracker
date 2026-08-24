/**
 * Holds captured alerts until the server has confirmed it has them.
 *
 * A phone reads text messages at the worst possible moments — on the
 * underground, mid-flight, on 2% battery. The queue exists so that capturing a
 * message and successfully uploading it are separate events that can be hours
 * apart.
 *
 * ## The queue is a cache, not the record
 *
 * This is the fact that makes everything else here simple. Every message in the
 * queue is *also* still sitting in the phone's SMS inbox, and a rescan will find
 * it again. So losing the queue — a crash, a reinstall, an overflow — costs
 * nothing that cannot be recovered by scanning again. That frees the code from
 * the usual durable-queue anxieties and lets it fail in the cheap direction
 * every time.
 *
 * ## Removal always follows confirmation
 *
 * Messages are deleted only after the server has answered. If the app dies in
 * between, the batch is sent a second time and the server reports it as a
 * duplicate — the `(user_id, body_hash)` constraint makes that free. The
 * opposite ordering would be a data-loss bug, so it is worth being explicit:
 * nothing is ever removed optimistically.
 */

import { checkSms, type RejectionReason } from './sms-filter.ts';

/** One captured alert, in the shape the API expects. */
export type QueuedSms = {
  sender: string;
  body: string;
  /**
   * ISO-8601, taken from the message record on the device — never from the
   * clock. The server folds this into the fingerprint, so a message captured
   * live and the same message seen again by a later scan must report the same
   * instant or the user will see the payment twice.
   */
  receivedAt: string;
};

/** Matches `SmsBatchRequest.MAX_MESSAGES` on the server. */
export const MAX_BATCH = 200;

/**
 * Most messages held at once.
 *
 * Reached only after a long spell offline or a first scan of a very full inbox.
 * When it is hit the *oldest* are dropped, because recent spending is what the
 * app is for and, as above, anything dropped is still in the inbox and will be
 * picked up by the next full scan.
 */
export const MAX_QUEUED = 2_000;

export type UploadOutcome =
  | { kind: 'ok'; stored: number; duplicates: number }
  /** Network trouble or a server fault. The batch is kept and tried again. */
  | { kind: 'retry'; error: string }
  /** The server will never accept this batch. Keeping it would block the queue. */
  | { kind: 'rejected'; error: string };

export type Upload = (batch: QueuedSms[], isFinalBatch: boolean) => Promise<UploadOutcome>;

export type FlushReport = {
  sent: number;
  stored: number;
  duplicates: number;
  /** Left in the queue: either untried, or waiting out a failure. */
  remaining: number;
  /** Discarded because the server refused them outright. */
  discarded: number;
  /** Set when the flush stopped early. */
  error?: string;
};

export type EnqueueReport = {
  added: number;
  /** Already in the queue — the live receiver and a scan both found it. */
  alreadyQueued: number;
  /** Refused by the on-device filter, keyed by reason. */
  filtered: Partial<Record<RejectionReason, number>>;
  /** Dropped to stay under {@link MAX_QUEUED}. */
  evicted: number;
};

export interface QueueStorage {
  read(): Promise<string | null>;
  write(value: string): Promise<void>;
}

/**
 * Identifies a message for local purposes.
 *
 * Built from the same three fields the server hashes, so the app's idea of "we
 * already have this" matches the database's. It is not the server's hash and is
 * not meant to be — recomputing SHA-256 on the device to save a round trip
 * would couple the two implementations far more tightly than this does, for no
 * benefit the constraint does not already provide.
 */
function localKey(message: QueuedSms): string {
  const at = Math.floor(new Date(message.receivedAt).getTime() / 1000);
  return `${message.sender}|${at}|${message.body.replace(/\s+/g, ' ').trim().toLowerCase()}`;
}

export class SmsQueue {
  private readonly storage: QueueStorage;

  constructor(storage: QueueStorage) {
    this.storage = storage;
  }

  async pending(): Promise<QueuedSms[]> {
    const raw = await this.storage.read();
    if (!raw) return [];
    try {
      const parsed: unknown = JSON.parse(raw);
      return Array.isArray(parsed) ? (parsed as QueuedSms[]) : [];
    } catch {
      // Corrupt storage is treated as an empty queue rather than an error. The
      // messages are still in the inbox; refusing to start would be a far worse
      // outcome than rescanning.
      return [];
    }
  }

  /**
   * Adds messages that pass the filter and are not already waiting.
   *
   * Filtering happens here rather than at the point of capture so that there is
   * exactly one place where a message can enter the queue, and therefore
   * exactly one place to audit. A caller cannot bypass it by accident.
   */
  async enqueue(candidates: QueuedSms[]): Promise<EnqueueReport> {
    const queue = await this.pending();
    const seen = new Set(queue.map(localKey));
    const report: EnqueueReport = { added: 0, alreadyQueued: 0, filtered: {}, evicted: 0 };

    for (const candidate of candidates) {
      const decision = checkSms(candidate.sender, candidate.body);
      if (!decision.accepted) {
        report.filtered[decision.reason] = (report.filtered[decision.reason] ?? 0) + 1;
        continue;
      }

      const key = localKey(candidate);
      if (seen.has(key)) {
        report.alreadyQueued++;
        continue;
      }

      seen.add(key);
      queue.push(candidate);
      report.added++;
    }

    if (queue.length > MAX_QUEUED) {
      report.evicted = queue.length - MAX_QUEUED;
      queue.splice(0, report.evicted);
    }

    await this.storage.write(JSON.stringify(queue));
    return report;
  }

  /**
   * Sends everything waiting, oldest first.
   *
   * Stops at the first retryable failure and leaves the rest queued: pressing
   * on would just fail the same way and waste the battery that prompted the
   * failure. A batch the server refuses outright is dropped instead, because a
   * permanently invalid message at the head of the queue would otherwise block
   * every message behind it forever.
   */
  async flush(upload: Upload): Promise<FlushReport> {
    const queue = await this.pending();
    const report: FlushReport = {
      sent: 0,
      stored: 0,
      duplicates: 0,
      remaining: queue.length,
      discarded: 0,
    };
    if (queue.length === 0) return report;

    let index = 0;
    while (index < queue.length) {
      const batch = queue.slice(index, index + MAX_BATCH);
      const isFinal = index + batch.length >= queue.length;

      const outcome = await upload(batch, isFinal);

      if (outcome.kind === 'retry') {
        report.error = outcome.error;
        break;
      }

      if (outcome.kind === 'rejected') {
        report.discarded += batch.length;
        report.error = outcome.error;
      } else {
        report.sent += batch.length;
        report.stored += outcome.stored;
        report.duplicates += outcome.duplicates;
      }

      index += batch.length;

      // Written after every batch, not once at the end. A first scan can be
      // twenty batches long; if the app is killed on the nineteenth, the work
      // already acknowledged should not be repeated.
      await this.storage.write(JSON.stringify(queue.slice(index)));
    }

    report.remaining = queue.length - index;
    return report;
  }

  async clear(): Promise<void> {
    await this.storage.write('[]');
  }
}
