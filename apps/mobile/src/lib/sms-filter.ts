/**
 * Decides which text messages are allowed to leave the phone.
 *
 * This is a deliberate re-implementation of `SmsFilter.java`. The server
 * applies the same rules again on arrival and its copy is the one that
 * guarantees correctness — but by then the message has already travelled, and
 * for a private message that is the harm. This copy is the one that protects
 * the user, because a message it rejects is never transmitted at all.
 *
 * Both are held to `packages/shared/sms-filter-vectors.json`, so the pair
 * cannot quietly diverge.
 *
 * The bias here runs opposite to everywhere else in this codebase. Elsewhere a
 * missed transaction is the expensive mistake and rules lean towards keeping
 * things. Here, a bank alert wrongly dropped costs one manual entry; a personal
 * message wrongly uploaded cannot be taken back. Ambiguous messages stay put.
 */

/** Longest body worth examining. A concatenated SMS tops out near 1,600. */
export const MAX_BODY_LENGTH = 2_000;

/**
 * A digits-only sender with at least this many digits is a person.
 *
 * Service shortcodes run to five or six digits; mobile numbers are ten.
 * Nothing legitimate sits in between, so the line is unambiguous.
 */
export const PERSONAL_NUMBER_DIGITS = 7;

export const REJECTION_REASONS = [
  'PERSONAL_SENDER',
  'UNKNOWN_SENDER',
  'MALFORMED',
  'OTP_CODE',
  'NOT_SETTLED',
  'MONEY_REQUEST',
  'PROMOTIONAL',
  'NO_AMOUNT',
  'NO_TRANSACTION_VERB',
] as const;

export type RejectionReason = (typeof REJECTION_REASONS)[number];

export type Decision =
  | { accepted: true; reason: 'ACCEPTED' }
  | { accepted: false; reason: RejectionReason };

/**
 * An amount: a currency marker followed by digits. The marker is required —
 * bare numbers appear in every message ever sent.
 */
const AMOUNT = /(?:rs\.?|inr|₹|usd|\$|eur|€|gbp|£)\s*[0-9][0-9,]*(?:\.[0-9]{1,2})?/i;

/**
 * Money that has already moved. All past tense, which is what separates
 * "spent 2,000" (a receipt) from "spend 2,000 and get 200 back" (an advert)
 * without maintaining a list of every campaign a bank has run.
 */
const SETTLED_VERB =
  /\b(?:debited|credited|deducted|spent|paid|withdrawn|withdrew|received|transferred|purchased|charged|refunded|reversed|sent|autopay|auto-debited|emi\s+of|billed)\b/i;

/**
 * One-time passwords, rejected before anything else is considered.
 *
 * "724193 is the OTP for a payment of 2,500 to AMAZON" carries an amount and a
 * verb, so it would otherwise be stored — and then the real alert follows and
 * the user is charged twice over in their own records.
 */
const OTP =
  /\b(?:otp|o\.t\.p|one[\s-]?time[\s-]?(?:password|passcode|pin)|verification\s+code|security\s+code|auth(?:entication)?\s+code|login\s+code|2fa)\b/i;

/** Attempts that moved no money. Recording one invents an expense. */
const UNSUCCESSFUL =
  /\b(?:declined|failed|unsuccessful|could\s+not\s+be\s+processed|has\s+been\s+rejected|insufficient\s+(?:funds|balance))\b/i;

/** Requests for money. Nothing has left the account until one is approved. */
const REQUEST =
  /\b(?:has\s+requested|is\s+requesting|requesting\s+(?:money|payment)|collect\s+request|payment\s+request|requested\s+money)\b/i;

/**
 * Sales patter that survived the past-tense rule. Deliberately short: every
 * extra entry is another chance to throw away a genuine alert.
 */
const PROMOTIONAL =
  /\b(?:pre-?approved|apply\s+now|click\s+here|limited\s+period|hurry|book\s+now|shop\s+now|t&c\s+apply|unsubscribe|lowest\s+interest|instant\s+loan|win\s+a)\b/i;

const accept = (): Decision => ({ accepted: true, reason: 'ACCEPTED' });
const reject = (reason: RejectionReason): Decision => ({ accepted: false, reason });

/**
 * True when the sender is a phone number rather than a business.
 *
 * The question asked is deliberately the crudest available — is there a letter
 * anywhere? Anything more specific is something to be evaded. An earlier
 * version of the server rule matched the *shape* of a phone number, and
 * `(+91) 9812345678` slipped past it because of the leading bracket. Counting
 * letters cannot fail that way: no arrangement of punctuation turns a number
 * into a name.
 */
export function isPersonalNumber(sender: string): boolean {
  const trimmed = sender.trim();
  if (/\p{L}/u.test(trimmed)) return false;
  const digits = (trimmed.match(/\p{Nd}/gu) ?? []).length;
  return digits >= PERSONAL_NUMBER_DIGITS;
}

/**
 * Judges one message.
 *
 * Order matters. Structural tests come first so a conversation is dismissed on
 * its sender alone and its contents are never examined — the app should not
 * read private messages any more closely than it must. Exclusions then run
 * ahead of inclusions, so a message that looks like a payment *and* like an OTP
 * is treated as the OTP it is.
 */
export function checkSms(
  sender: string | null | undefined,
  body: string | null | undefined,
): Decision {
  if (body == null || body.trim() === '' || body.length > MAX_BODY_LENGTH) {
    return reject('MALFORMED');
  }
  if (sender == null || sender.trim() === '') {
    return reject('UNKNOWN_SENDER');
  }
  if (isPersonalNumber(sender)) {
    return reject('PERSONAL_SENDER');
  }

  const text = body.toLowerCase();

  if (OTP.test(text)) return reject('OTP_CODE');
  if (UNSUCCESSFUL.test(text)) return reject('NOT_SETTLED');
  if (REQUEST.test(text)) return reject('MONEY_REQUEST');
  if (PROMOTIONAL.test(text)) return reject('PROMOTIONAL');
  if (!AMOUNT.test(text)) return reject('NO_AMOUNT');
  if (!SETTLED_VERB.test(text)) return reject('NO_TRANSACTION_VERB');

  return accept();
}

/** Wording for the settings screen, so the user can see what each count means. */
export const REASON_LABELS: Record<RejectionReason, string> = {
  PERSONAL_SENDER: 'From a phone number, so treated as a personal message',
  UNKNOWN_SENDER: 'No sender, so we could not rule out a person',
  MALFORMED: 'Empty, or too long to be a text message',
  OTP_CODE: 'A one-time password',
  NOT_SETTLED: 'A payment that did not go through',
  MONEY_REQUEST: 'A request for money, not a payment',
  PROMOTIONAL: 'An advert',
  NO_AMOUNT: 'No amount in it',
  NO_TRANSACTION_VERB: 'An amount, but nothing saying money moved',
};
