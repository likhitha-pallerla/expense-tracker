import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, it } from 'node:test';

import {
  checkSms,
  isPersonalNumber,
  MAX_BODY_LENGTH,
  REASON_LABELS,
  REJECTION_REASONS,
  type RejectionReason,
} from './sms-filter.ts';

/**
 * The on-device filter, measured against the same corpus as the Java one.
 *
 * If these two implementations drift, the symptom is invisible in both: the
 * phone either uploads something the server discards, or withholds something
 * the server would have accepted and a transaction never appears. Neither
 * produces an error. Sharing the examples is the only thing that catches it.
 */

const here = dirname(fileURLToPath(import.meta.url));
const vectorPath = resolve(here, '../../../../packages/shared/sms-filter-vectors.json');

type Vector = { name: string; sender: string; body: string; reason?: string };
const vectors: { accept: Vector[]; reject: Vector[] } = JSON.parse(readFileSync(vectorPath, 'utf8'));

describe('shared corpus', () => {
  it('has cases to run', () => {
    assert.ok(vectors.accept.length > 0, 'no accept vectors found');
    assert.ok(vectors.reject.length > 0, 'no reject vectors found');
  });

  for (const vector of vectors.accept) {
    it(`accepts: ${vector.name}`, () => {
      const decision = checkSms(vector.sender, vector.body);
      assert.equal(decision.accepted, true, `rejected as ${decision.reason}`);
    });
  }

  for (const vector of vectors.reject) {
    it(`rejects: ${vector.name}`, () => {
      const decision = checkSms(vector.sender, vector.body);
      assert.equal(decision.accepted, false, 'was accepted');
      // The grounds are asserted too. Rejecting an OTP because it happens to
      // lack an amount would pass a looser test and then break the moment a
      // bank reworded its template.
      assert.equal(decision.reason, vector.reason, 'wrong grounds');
    });
  }

  it('exercises every rejection reason', () => {
    const covered = new Set(vectors.reject.map((v) => v.reason));
    for (const reason of REJECTION_REASONS) {
      assert.ok(covered.has(reason), `no worked example for ${reason}`);
    }
  });

  it('describes every rejection reason to the user', () => {
    // A count with no wording renders as a blank row in settings, which on a
    // screen whose whole job is to reassure is worse than showing nothing.
    for (const reason of REJECTION_REASONS) {
      assert.ok(REASON_LABELS[reason as RejectionReason], `no label for ${reason}`);
    }
  });
});

describe('sender rules', () => {
  it('treats a six digit shortcode as a business', () => {
    assert.equal(isPersonalNumber('561617'), false);
  });

  it('treats seven digits as already too long for a shortcode', () => {
    assert.equal(isPersonalNumber('5616171'), true);
  });

  it('accepts any sender carrying a letter', () => {
    for (const sender of ['HDFCBK', 'AD-HDFCBK', 'JK-SBIINB-S']) {
      assert.equal(isPersonalNumber(sender), false, sender);
    }
  });

  it('is not fooled by punctuation around a mobile number', () => {
    for (const sender of ['+91 98123-45678', '(+91) 9812345678', '098123 45678']) {
      assert.equal(isPersonalNumber(sender), true, sender);
    }
  });

  it('drops a personal sender without reading the message', () => {
    const decision = checkSms('+919812345678', 'Rs 450.00 debited from a/c **1234');
    assert.equal(decision.reason, 'PERSONAL_SENDER');
  });
});

describe('bounds', () => {
  const alert = 'Rs 450.00 debited from a/c **1234 on 04-02-26.';

  it('considers a body at the limit', () => {
    const padded = alert + 'x'.repeat(MAX_BODY_LENGTH - alert.length);
    assert.equal(checkSms('AD-HDFCBK', padded).accepted, true);
  });

  it('refuses one past the limit', () => {
    const padded = alert + 'x'.repeat(MAX_BODY_LENGTH - alert.length + 1);
    assert.equal(checkSms('AD-HDFCBK', padded).reason, 'MALFORMED');
  });

  it('treats absent fields as malformed rather than throwing', () => {
    // A batch of forty must not fail because one message had no body.
    assert.equal(checkSms('AD-HDFCBK', null).reason, 'MALFORMED');
    assert.equal(checkSms(null, alert).reason, 'UNKNOWN_SENDER');
    assert.equal(checkSms(undefined, undefined).reason, 'MALFORMED');
  });
});

describe('precedence', () => {
  it('lets an OTP outrank the alert it describes', () => {
    const decision = checkSms(
      'AD-HDFCBK',
      'OTP 724193 for Rs 2,500.00 debited from HDFC Card XX9021 at AMAZON.',
    );
    assert.equal(decision.reason, 'OTP_CODE');
  });

  it('lets a personal sender outrank every content rule', () => {
    assert.equal(checkSms('+919812345678', 'OTP 1234 for Rs 100 debited').reason, 'PERSONAL_SENDER');
  });
});
