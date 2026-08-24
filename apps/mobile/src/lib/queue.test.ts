import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  MAX_BATCH,
  MAX_QUEUED,
  SmsQueue,
  type QueuedSms,
  type QueueStorage,
  type UploadOutcome,
} from './queue.ts';

/**
 * The queue decides what survives a bad connection, so its failures are the
 * ones that lose money quietly: a message removed before it was acknowledged
 * never appears anywhere, and nothing reports it missing.
 */

class MemoryStorage implements QueueStorage {
  value: string | null = null;
  writes = 0;

  async read(): Promise<string | null> {
    return this.value;
  }

  async write(value: string): Promise<void> {
    this.writes++;
    this.value = value;
  }
}

const alert = (n: number, at = '2026-02-04T09:00:00.000Z'): QueuedSms => ({
  sender: 'AD-HDFCBK',
  body: `Rs ${n}.00 debited from a/c **1234 ref ${n}`,
  receivedAt: at,
});

const ok = (batch: QueuedSms[]): UploadOutcome => ({
  kind: 'ok',
  stored: batch.length,
  duplicates: 0,
});

describe('enqueue', () => {
  it('keeps bank alerts', async () => {
    const queue = new SmsQueue(new MemoryStorage());
    const report = await queue.enqueue([alert(1), alert(2)]);

    assert.equal(report.added, 2);
    assert.equal((await queue.pending()).length, 2);
  });

  it('refuses anything the filter rejects', async () => {
    const store = new MemoryStorage();
    const queue = new SmsQueue(store);

    const report = await queue.enqueue([
      alert(1),
      { sender: '+919812345678', body: 'paid you Rs 500 yesterday', receivedAt: alert(1).receivedAt },
      { sender: 'AD-HDFCBK', body: 'OTP 1234 for Rs 99 debited', receivedAt: alert(1).receivedAt },
    ]);

    assert.equal(report.added, 1);
    assert.equal(report.filtered.PERSONAL_SENDER, 1);
    assert.equal(report.filtered.OTP_CODE, 1);
    // The point of filtering inside enqueue: a personal message cannot reach
    // storage even if a caller forgets to check it first.
    assert.ok(!store.value?.includes('9812345678'));
  });

  it('does not queue the same message twice', async () => {
    // The live receiver catches an alert, then a scan finds it again. Without
    // this the user pays for the same upload twice and the server does the
    // deduplicating work for nothing.
    const queue = new SmsQueue(new MemoryStorage());
    await queue.enqueue([alert(1)]);
    const second = await queue.enqueue([alert(1), alert(2)]);

    assert.equal(second.alreadyQueued, 1);
    assert.equal(second.added, 1);
    assert.equal((await queue.pending()).length, 2);
  });

  it('ignores sub-second differences when matching', async () => {
    // The receiver and the inbox can report the same message with slightly
    // different precision. The server truncates to the second, so the queue
    // must agree or it will send a message the server then rejects as a dupe.
    const queue = new SmsQueue(new MemoryStorage());
    await queue.enqueue([alert(1, '2026-02-04T09:00:00.000Z')]);
    const second = await queue.enqueue([alert(1, '2026-02-04T09:00:00.812Z')]);

    assert.equal(second.alreadyQueued, 1);
  });

  it('treats different times as different messages', async () => {
    // Two cups of tea an hour apart produce identical text. Collapsing them
    // would silently lose the second payment.
    const queue = new SmsQueue(new MemoryStorage());
    await queue.enqueue([alert(20, '2026-02-04T09:00:00.000Z')]);
    const second = await queue.enqueue([alert(20, '2026-02-04T10:00:00.000Z')]);

    assert.equal(second.added, 1);
  });

  it('drops the oldest when it overflows', async () => {
    const queue = new SmsQueue(new MemoryStorage());
    const many = Array.from({ length: MAX_QUEUED + 10 }, (_, i) => alert(i));

    const report = await queue.enqueue(many);
    const pending = await queue.pending();

    assert.equal(report.evicted, 10);
    assert.equal(pending.length, MAX_QUEUED);
    // Newest kept: recent spending is what the app is for, and the evicted
    // messages are still in the inbox for the next full scan to find.
    assert.ok(pending[pending.length - 1].body.includes(`ref ${MAX_QUEUED + 9}`));
  });

  it('survives corrupt storage', async () => {
    const store = new MemoryStorage();
    store.value = '{not json';
    const queue = new SmsQueue(store);

    assert.deepEqual(await queue.pending(), []);
    assert.equal((await queue.enqueue([alert(1)])).added, 1);
  });
});

describe('flush', () => {
  it('does nothing when there is nothing waiting', async () => {
    const queue = new SmsQueue(new MemoryStorage());
    let called = false;

    const report = await queue.flush(async () => {
      called = true;
      return { kind: 'ok', stored: 0, duplicates: 0 };
    });

    assert.equal(called, false, 'should not call the network with an empty queue');
    assert.equal(report.sent, 0);
  });

  it('empties the queue when every batch is accepted', async () => {
    const queue = new SmsQueue(new MemoryStorage());
    await queue.enqueue([alert(1), alert(2), alert(3)]);

    const report = await queue.flush(async (batch) => ok(batch));

    assert.equal(report.sent, 3);
    assert.equal(report.stored, 3);
    assert.equal(report.remaining, 0);
    assert.deepEqual(await queue.pending(), []);
  });

  it('splits a large queue into batches the server will accept', async () => {
    const queue = new SmsQueue(new MemoryStorage());
    await queue.enqueue(Array.from({ length: MAX_BATCH * 2 + 5 }, (_, i) => alert(i)));

    const sizes: number[] = [];
    await queue.flush(async (batch) => {
      sizes.push(batch.length);
      return ok(batch);
    });

    assert.deepEqual(sizes, [MAX_BATCH, MAX_BATCH, 5]);
  });

  it('asks for parsing only on the last batch', async () => {
    // Parsing after each of twenty batches repeats the same scan twenty times
    // and makes the phone wait for all of it.
    const queue = new SmsQueue(new MemoryStorage());
    await queue.enqueue(Array.from({ length: MAX_BATCH + 1 }, (_, i) => alert(i)));

    const finals: boolean[] = [];
    await queue.flush(async (batch, isFinal) => {
      finals.push(isFinal);
      return ok(batch);
    });

    assert.deepEqual(finals, [false, true]);
  });

  it('keeps everything when the network fails', async () => {
    const queue = new SmsQueue(new MemoryStorage());
    await queue.enqueue([alert(1), alert(2)]);

    const report = await queue.flush(async () => ({ kind: 'retry', error: 'offline' }));

    assert.equal(report.sent, 0);
    assert.equal(report.remaining, 2);
    assert.equal(report.error, 'offline');
    assert.equal((await queue.pending()).length, 2, 'nothing may be lost to a failed send');
  });

  it('keeps the unsent remainder when a later batch fails', async () => {
    const queue = new SmsQueue(new MemoryStorage());
    await queue.enqueue(Array.from({ length: MAX_BATCH + 3 }, (_, i) => alert(i)));

    let call = 0;
    const report = await queue.flush(async (batch) => {
      call++;
      return call === 1 ? ok(batch) : { kind: 'retry', error: 'timeout' };
    });

    assert.equal(report.sent, MAX_BATCH);
    assert.equal(report.remaining, 3);

    const left = await queue.pending();
    assert.equal(left.length, 3, 'the acknowledged batch should not be resent');
    assert.ok(left[0].body.includes(`ref ${MAX_BATCH}`));
  });

  it('discards a batch the server will never accept', async () => {
    // Otherwise one malformed message at the head blocks every message behind
    // it, and the queue never drains again.
    const queue = new SmsQueue(new MemoryStorage());
    await queue.enqueue(Array.from({ length: MAX_BATCH + 2 }, (_, i) => alert(i)));

    let call = 0;
    const report = await queue.flush(async (batch) => {
      call++;
      return call === 1 ? { kind: 'rejected', error: 'bad request' } : ok(batch);
    });

    assert.equal(report.discarded, MAX_BATCH);
    assert.equal(report.sent, 2, 'the rest still goes');
    assert.equal(report.remaining, 0);
  });

  it('saves progress after each batch, not once at the end', async () => {
    // A first scan can be twenty batches long. If the app is killed on the
    // nineteenth, the acknowledged work must not be repeated.
    const store = new MemoryStorage();
    const queue = new SmsQueue(store);
    await queue.enqueue(Array.from({ length: MAX_BATCH * 2 }, (_, i) => alert(i)));

    const writesBefore = store.writes;
    await queue.flush(async (batch) => ok(batch));

    assert.equal(store.writes - writesBefore, 2, 'one write per acknowledged batch');
  });

  it('removes nothing before the server has answered', async () => {
    const store = new MemoryStorage();
    const queue = new SmsQueue(store);
    await queue.enqueue([alert(1)]);

    await queue.flush(async (batch) => {
      // Mid-upload: the message must still be on disk. If the app were killed
      // here, a retry has to find it.
      const onDisk: QueuedSms[] = JSON.parse(store.value ?? '[]');
      assert.equal(onDisk.length, 1, 'queue was emptied before confirmation');
      return ok(batch);
    });

    assert.deepEqual(await queue.pending(), []);
  });
});
