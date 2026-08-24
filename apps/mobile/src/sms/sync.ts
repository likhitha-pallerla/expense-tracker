import { type FlushReport, type QueuedSms, SmsQueue, type Upload } from '../lib/queue.ts';
import type { RejectionReason } from '../lib/sms-filter.ts';

/**
 * One pass over the inbox: read, filter, queue, upload.
 *
 * Written against injected dependencies rather than importing the reader and
 * the API directly, because the native half of this feature cannot be exercised
 * anywhere except on a physical Android handset. Keeping the decisions — how
 * far back to look, when to move the watermark, what to do when the upload
 * fails halfway — in a function that takes its collaborators as arguments means
 * those decisions are covered by ordinary tests, and the untested surface
 * shrinks to the Kotlin that reads a database cursor.
 */

export type SmsSyncReport = {
  scanned: number;
  queued: number;
  alreadyQueued: number;
  filtered: Partial<Record<RejectionReason, number>>;
  upload: FlushReport;
  scannedSince: string;
};

export type SyncDeps = {
  queue: SmsQueue;
  readInbox: (since: Date) => Promise<QueuedSms[]>;
  upload: Upload;
  lastScanAt: () => Promise<Date | null>;
  rememberScan: (at: Date) => Promise<void>;
  now?: () => Date;
};

/**
 * How much history a first scan collects.
 *
 * Long enough that the app is useful on the day it is installed — a fortnight
 * of spending says almost nothing, and every budget and average would read as
 * nonsense. Short enough that the first upload is a few hundred messages
 * rather than a decade of them.
 */
export const FIRST_SCAN_DAYS = 90;

/**
 * How far back each later scan reaches beyond the last one.
 *
 * A watermark advanced to exactly "now" leaves a gap wherever the two clocks
 * disagree, where a message was written to the inbox a moment after the cursor
 * read past it, or where the app was killed mid-scan. Any of those loses a
 * payment silently. Overlapping by a day closes all of them at no cost, because
 * re-reading a message the server already holds is free: the fingerprint
 * collapses it and the response reports a duplicate.
 */
export const RESCAN_OVERLAP_MS = 24 * 60 * 60 * 1_000;

export async function syncSms(deps: SyncDeps): Promise<SmsSyncReport> {
  const now = deps.now?.() ?? new Date();
  const previous = await deps.lastScanAt();

  const since = previous
    ? new Date(Math.max(0, previous.getTime() - RESCAN_OVERLAP_MS))
    : new Date(now.getTime() - FIRST_SCAN_DAYS * 24 * 60 * 60 * 1_000);

  const found = await deps.readInbox(since);
  const enqueued = await deps.queue.enqueue(found);

  const upload = await deps.queue.flush(deps.upload);

  // The watermark moves only when the upload got through cleanly. If it did
  // not, the messages are still queued and would be sent next time anyway --
  // but a user who fixes their connection and pulls to refresh expects a
  // *rescan*, not a report that there was nothing new. Holding the watermark
  // back makes the retry look at the same window again, which is the honest
  // behaviour and costs nothing.
  if (!upload.error) {
    await deps.rememberScan(now);
  }

  return {
    scanned: found.length,
    queued: enqueued.added,
    alreadyQueued: enqueued.alreadyQueued,
    filtered: enqueued.filtered,
    upload,
    scannedSince: since.toISOString(),
  };
}

/**
 * A sentence for the screen.
 *
 * Written here rather than in the component so that the wording is covered by
 * tests. It is the only thing most users will ever read about what the app did
 * with their messages, and the number that matters to them is not how many were
 * uploaded but how many were left alone.
 */
export function describeSync(report: SmsSyncReport): string {
  const filtered = Object.values(report.filtered).reduce((sum, n) => sum + (n ?? 0), 0);

  if (report.scanned === 0) {
    return 'No new messages since the last check.';
  }

  const parts: string[] = [];
  parts.push(`Looked at ${count(report.scanned, 'message')}.`);

  if (report.upload.stored > 0) {
    parts.push(`Found ${count(report.upload.stored, 'payment')}.`);
  } else if (report.upload.duplicates > 0) {
    parts.push('Nothing new — we already had them.');
  } else if (report.queued === 0) {
    parts.push('None of them were payments.');
  }

  if (filtered > 0) {
    parts.push(`${count(filtered, 'message')} stayed on your phone.`);
  }

  if (report.upload.error) {
    parts.push(
      report.upload.remaining > 0
        ? `${count(report.upload.remaining, 'message')} still waiting to send.`
        : 'Some messages could not be sent.',
    );
  }

  return parts.join(' ');
}

function count(n: number, noun: string): string {
  return `${n.toLocaleString()} ${noun}${n === 1 ? '' : 's'}`;
}
