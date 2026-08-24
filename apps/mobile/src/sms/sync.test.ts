import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { SmsQueue, type QueuedSms, type QueueStorage, type UploadOutcome } from '../lib/queue.ts';
import {
  describeSync,
  FIRST_SCAN_DAYS,
  RESCAN_OVERLAP_MS,
  syncSms,
  type SmsSyncReport,
} from './sync.ts';

/**
 * The scan window and the watermark. Between them they decide whether a
 * payment is seen at all, and both failure modes are silent: a gap loses a
 * transaction, and a watermark that moves too eagerly makes the gap permanent.
 */

class MemoryStorage implements QueueStorage {
  value: string | null = null;
  async read() {
    return this.value;
  }
  async write(value: string) {
    this.value = value;
  }
}

const NOW = new Date('2026-02-10T12:00:00.000Z');

const alert = (n: number, at = '2026-02-09T09:00:00.000Z'): QueuedSms => ({
  sender: 'AD-HDFCBK',
  body: `Rs ${n}.00 debited from a/c **1234 ref ${n}`,
  receivedAt: at,
});

type Harness = {
  scans: Date[];
  remembered: Date[];
  uploads: QueuedSms[][];
};

function harness(
  inbox: QueuedSms[],
  options: { lastScan?: Date; outcome?: (batch: QueuedSms[]) => UploadOutcome } = {},
) {
  const record: Harness = { scans: [], remembered: [], uploads: [] };
  const queue = new SmsQueue(new MemoryStorage());

  const deps = {
    queue,
    now: () => NOW,
    readInbox: async (since: Date) => {
      record.scans.push(since);
      return inbox;
    },
    upload: async (batch: QueuedSms[]) => {
      record.uploads.push(batch);
      return (
        options.outcome?.(batch) ?? { kind: 'ok' as const, stored: batch.length, duplicates: 0 }
      );
    },
    lastScanAt: async () => options.lastScan ?? null,
    rememberScan: async (at: Date) => {
      record.remembered.push(at);
    },
  };

  return { queue, deps, record };
}

describe('the scan window', () => {
  it('reaches back three months on a first run', async () => {
    // A fortnight of history would make every average and budget read as
    // nonsense on the day the app is installed.
    const { deps, record } = harness([]);
    await syncSms(deps);

    const expected = NOW.getTime() - FIRST_SCAN_DAYS * 24 * 60 * 60 * 1_000;
    assert.equal(record.scans[0].getTime(), expected);
  });

  it('overlaps the previous scan rather than resuming exactly where it stopped', async () => {
    // The gap this closes is invisible: a message written to the inbox just
    // after the cursor read past it is never seen again.
    const lastScan = new Date('2026-02-09T12:00:00.000Z');
    const { deps, record } = harness([], { lastScan });

    await syncSms(deps);

    assert.equal(record.scans[0].getTime(), lastScan.getTime() - RESCAN_OVERLAP_MS);
  });

  it('never asks for a negative time', async () => {
    const { deps, record } = harness([], { lastScan: new Date(0) });
    await syncSms(deps);

    assert.ok(record.scans[0].getTime() >= 0);
  });
});

describe('the watermark', () => {
  it('moves when the upload succeeded', async () => {
    const { deps, record } = harness([alert(1)]);
    await syncSms(deps);

    assert.deepEqual(record.remembered, [NOW]);
  });

  it('stays put when the upload failed', async () => {
    // Otherwise a user who reconnects and pulls to refresh is told there is
    // nothing new, because the window has already moved past the messages that
    // never got through.
    const { deps, record } = harness([alert(1)], {
      outcome: () => ({ kind: 'retry', error: 'offline' }),
    });

    const report = await syncSms(deps);

    assert.deepEqual(record.remembered, [], 'watermark must not advance past unsent messages');
    assert.equal(report.upload.remaining, 1);
  });

  it('leaves failed messages queued for the next attempt', async () => {
    const { queue, deps } = harness([alert(1), alert(2)], {
      outcome: () => ({ kind: 'retry', error: 'offline' }),
    });

    await syncSms(deps);

    assert.equal((await queue.pending()).length, 2);
  });
});

describe('what gets uploaded', () => {
  it('sends only what the filter allowed', async () => {
    const personal: QueuedSms = {
      sender: '+919812345678',
      body: 'I paid you Rs 500 yesterday',
      receivedAt: '2026-02-09T09:00:00.000Z',
    };
    const { deps, record } = harness([alert(1), personal]);

    const report = await syncSms(deps);

    assert.equal(report.scanned, 2);
    assert.equal(report.queued, 1);
    assert.equal(report.filtered.PERSONAL_SENDER, 1);
    assert.deepEqual(record.uploads.flat().map((m) => m.sender), ['AD-HDFCBK']);
  });

  it('does not re-upload what the overlap saw twice', async () => {
    // The overlap deliberately re-reads a day of messages. They must not cost
    // a second upload.
    const { queue, deps, record } = harness([alert(1)]);
    await syncSms(deps);
    await queue.enqueue([alert(1)]);

    assert.equal(record.uploads.flat().length, 1);
  });

  it('makes no network call when the inbox has nothing new', async () => {
    const { deps, record } = harness([]);
    await syncSms(deps);

    assert.equal(record.uploads.length, 0);
  });
});

describe('what the user is told', () => {
  const base: SmsSyncReport = {
    scanned: 0,
    queued: 0,
    alreadyQueued: 0,
    filtered: {},
    upload: { sent: 0, stored: 0, duplicates: 0, remaining: 0, discarded: 0 },
    scannedSince: NOW.toISOString(),
  };

  it('says nothing happened when nothing did', () => {
    assert.equal(describeSync(base), 'No new messages since the last check.');
  });

  it('leads with what was found', () => {
    const sentence = describeSync({
      ...base,
      scanned: 40,
      queued: 4,
      upload: { sent: 4, stored: 4, duplicates: 0, remaining: 0, discarded: 0 },
    });

    assert.match(sentence, /Looked at 40 messages/);
    assert.match(sentence, /Found 4 payments/);
  });

  it('says plainly how many never left the phone', () => {
    // This is the number that matters to somebody deciding whether to trust an
    // app that reads their messages.
    const sentence = describeSync({
      ...base,
      scanned: 40,
      queued: 4,
      filtered: { PERSONAL_SENDER: 27, OTP_CODE: 9 },
      upload: { sent: 4, stored: 4, duplicates: 0, remaining: 0, discarded: 0 },
    });

    assert.match(sentence, /36 messages stayed on your phone/);
  });

  it('gets the singular right', () => {
    const sentence = describeSync({
      ...base,
      scanned: 1,
      queued: 1,
      filtered: { OTP_CODE: 1 },
      upload: { sent: 1, stored: 1, duplicates: 0, remaining: 0, discarded: 0 },
    });

    assert.match(sentence, /Looked at 1 message\./);
    assert.match(sentence, /Found 1 payment\./);
    assert.match(sentence, /1 message stayed/);
  });

  it('distinguishes nothing new from nothing relevant', () => {
    const alreadyHad = describeSync({
      ...base,
      scanned: 3,
      upload: { sent: 3, stored: 0, duplicates: 3, remaining: 0, discarded: 0 },
    });
    assert.match(alreadyHad, /already had them/);

    const notPayments = describeSync({ ...base, scanned: 3, filtered: { NO_AMOUNT: 3 } });
    assert.match(notPayments, /None of them were payments/);
  });

  it('admits when messages are still waiting', () => {
    const sentence = describeSync({
      ...base,
      scanned: 5,
      queued: 5,
      upload: { sent: 0, stored: 0, duplicates: 0, remaining: 5, discarded: 0, error: 'offline' },
    });

    assert.match(sentence, /5 messages still waiting to send/);
  });
});
