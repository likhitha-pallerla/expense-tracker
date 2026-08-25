// Drives the whole AI layer against a scripted stand-in for a model.
//
// Everything here is unreachable without a running API, a real database and
// something answering /chat/completions. The unit tests cover the validation
// rules in isolation; this checks that they are actually wired into the paths
// that matter -- and, crucially, that what leaves the machine has been redacted.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const env = {};
for (const line of readFileSync(join(import.meta.dirname, '..', '..', '..', '.env'), 'utf8').split(/\r?\n/)) {
  const m = line.match(/^\s*([A-Z_]+)=(.*)$/);
  if (m) env[m[1]] = m[2].trim();
}

const SUPABASE_URL = env.SUPABASE_URL;
const SERVICE_KEY = env.SUPABASE_SERVICE_ROLE_KEY;
const ANON_KEY = env.SUPABASE_ANON_KEY;
const API = 'http://localhost:8080';
const STUB = 'http://localhost:9099';

let passed = 0;
let failed = 0;

function check(name, condition, detail) {
  if (condition) {
    passed++;
    console.log(`  PASS  ${name}`);
  } else {
    failed++;
    console.log(`  FAIL  ${name}${detail ? ` -- ${detail}` : ''}`);
  }
}

const scenario = async (name) =>
  fetch(`${STUB}/control`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ scenario: name, reset: true }),
  }).then((r) => r.json());

const seen = async () => fetch(`${STUB}/seen`).then((r) => r.json());

async function main() {
  const email = `p5ai-${Date.now()}@example.com`;
  const password = 'Test-Password-9f2!';
  let userId = null;

  try {
    const created = await fetch(`${SUPABASE_URL}/auth/v1/admin/users`, {
      method: 'POST',
      headers: {
        apikey: SERVICE_KEY,
        Authorization: `Bearer ${SERVICE_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ email, password, email_confirm: true }),
    });
    const user = await created.json();
    userId = user.id;
    if (!userId) throw new Error(`could not create user: ${JSON.stringify(user)}`);
    console.log(`user ${userId}`);

    const signIn = await fetch(`${SUPABASE_URL}/auth/v1/token?grant_type=password`, {
      method: 'POST',
      headers: { apikey: ANON_KEY, 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });
    const token = (await signIn.json()).access_token;
    if (!token) throw new Error('could not sign in');

    const auth = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
    const get = async (path) => (await fetch(`${API}${path}`, { headers: auth })).json();
    const post = async (path, body) => {
      const res = await fetch(`${API}${path}`, {
        method: 'POST',
        headers: auth,
        body: JSON.stringify(body),
      });
      return { status: res.status, body: await res.json().catch(() => null) };
    };

    await get('/api/me');

    // A message no rule will match, inserted directly so parsing has something
    // to fail on. Throws loudly on rejection: a silently failed insert would
    // leave every downstream check reading an empty list and passing.
    const insert = async (subject, body) => {
      const res = await fetch(`${SUPABASE_URL}/rest/v1/raw_messages`, {
        method: 'POST',
        headers: {
          apikey: SERVICE_KEY,
          Authorization: `Bearer ${SERVICE_KEY}`,
          'Content-Type': 'application/json',
          Prefer: 'return=representation',
        },
        body: JSON.stringify({
          user_id: userId,
          provider_message_id: `stub-${Math.random()}`,
          sender: 'alerts@unknownbank.example',
          subject,
          body,
          body_hash: `h${Math.random()}`.slice(0, 40),
          received_at: new Date().toISOString(),
          status: 'pending',
        }),
      });
      const rows = await res.json();
      if (!res.ok || !Array.isArray(rows) || rows.length === 0) {
        throw new Error(`insert failed: ${res.status} ${JSON.stringify(rows)}`);
      }
      return rows[0];
    };

    console.log('\n-- the model is asked only when the rules fail --');

    await scenario('default');
    const readable = await post('/api/entry/parse', { text: 'spent 850 at zomato' });
    check('a sentence the rules understand never reaches the model',
      readable.body?.source === 'rules' && (await seen()).length === 0,
      `${readable.body?.source}, ${(await seen()).length} calls`);

    await scenario('default');
    const odd = await post('/api/entry/parse', {
      text: 'grabbed a couple of 250g bags of beans, came to six hundred and forty',
    });
    check('a sentence they cannot goes to the model',
      odd.body?.source === 'ai', JSON.stringify(odd.body));
    check('and its answer is used', Number(odd.body?.amount) === 640,
      `${odd.body?.amount}`);

    await scenario('default');
    await post('/api/entry/parse', { text: 'nothing numeric here at all' });
    check('a sentence with no digit is not worth a call',
      (await seen()).length === 0, `${(await seen()).length} calls`);

    console.log('\n-- what actually leaves the machine --');

    // Through the alert path rather than the typing box, because that is where
    // real bank text -- full of card numbers, balances and one-time codes --
    // actually arrives, and because an unmatched alert is guaranteed to reach
    // the model. Asserting the call happened first is the point: an empty
    // string passes every "is it gone?" check without testing anything.
    await insert('Transaction advice',
      'Dear customer, a sum of 1499.00 has been applied to your a/c no 123456789012 '
      + 'via card 4111 1111 1111 1111 towards chaiwala@ybl with reference 998877665544. '
      + 'Avl bal 45000.00. Do not share the OTP 445566 with anyone. '
      + 'Queries: me@example.com or 9812345678.');
    await scenario('default');
    await post('/api/parse', {});
    const calls = await seen();
    check('an alert no rule matched does reach the model', calls.length === 1,
      `${calls.length} calls`);

    const text = calls[0]?.user ?? '';
    check('temperature is zero, so a retry cannot change the answer',
      calls[0]?.temperature === 0, `${calls[0]?.temperature}`);
    check('the card number is gone', text && !text.includes('4111 1111 1111 1111'), text);
    check('the account number is gone', text && !text.includes('123456789012'), text);
    check('but its last four survive, so the account can still be matched',
      text.includes('9012'), text);
    check('the OTP is gone', text && !text.includes('445566'), text);
    check('the email address is gone', text && !text.includes('me@example.com'), text);
    check('the phone number is gone', text && !text.includes('9812345678'), text);
    check('the balance is gone', text && !text.includes('45000'), text);
    check('the UPI reference survives -- it is how duplicates are caught',
      text.includes('998877665544'), text);
    check('the merchant survives', text.includes('chaiwala@ybl'), text);

    console.log('\n-- the summary guard --');

    // The narrator does not spend a call on an empty month, so give it a month.
    const account = await post('/api/accounts', {
      name: 'HDFC Millennia', type: 'bank', currency: 'INR', last4: '4821',
      openingBalance: '0',
    });
    const now = new Date();
    for (const [n, merchant] of [[1200, 'Zomato'], [3400, 'BigBasket'], [800, 'Uber']]) {
      await post('/api/transactions', {
        kind: 'expense', amount: String(n), currency: 'INR',
        occurredAt: new Date(now.getFullYear(), now.getMonth(), 3, 12).toISOString(),
        description: merchant, merchant, accountId: account.body?.id,
      });
    }

    await scenario('default');
    const good = await get('/api/insights/summary');
    check('a clean summary is used', good?.source === 'ai', JSON.stringify(good));

    await scenario('fabricate');
    const bad = await get('/api/insights/summary');
    check('one with an invented percentage is thrown away',
      bad?.source === 'template', JSON.stringify(bad));
    check('and the deterministic sentence is shown instead',
      typeof bad?.text === 'string' && bad.text.length > 0, bad?.text);

    console.log('\n-- reading an alert no rule matched --');

    const message = 'Dear customer, an amount of 2499.00 was applied to your relationship '
      + 'number 8899 on account of NETFLIX subscription renewal. Thank you.';

    const raw = await insert('Advice 1', message);
    await scenario('default');
    const parsed = await post('/api/parse', {});
    check('the model turns an unreadable alert into a transaction',
      parsed.body?.imported === 1,
      `${JSON.stringify(parsed.body)}`);

    const listed = await get('/api/transactions?limit=10');
    const netflix = listed.items?.find((t) => t.merchantName === 'NETFLIX'
      && Number(t.amount) === 2499);
    check('with the amount the model read', Number(netflix?.amount) === 2499,
      JSON.stringify(listed.items?.map((t) => `${t.merchantName} ${t.amount}`)));

    const flagged = await fetch(
      `${SUPABASE_URL}/rest/v1/raw_messages?id=eq.${raw.id}&select=parsed_by,ai_confidence,parse_notes`,
      { headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` } },
    ).then((r) => r.json());
    check('recorded as read by AI', flagged[0]?.parsed_by === 'ai',
      JSON.stringify(flagged[0]));
    check('with the confidence stored beside it',
      Number(flagged[0]?.ai_confidence) === 0.95, `${flagged[0]?.ai_confidence}`);
    check('and a note the user can see',
      /read by AI/i.test(flagged[0]?.parse_notes ?? ''), flagged[0]?.parse_notes);

    console.log('\n-- what the gate refuses --');

    const refuses = async (name, scenarioName, subject) => {
      await insert(subject, message.replace('2499.00', `${Math.floor(Math.random() * 900) + 100}.00`));
      await scenario(scenarioName);
      const result = await post('/api/parse', {});
      check(name, result.body?.failed === 1 && result.body?.imported === 0,
        JSON.stringify(result.body));
    };

    await refuses('a reading below the confidence gate is not used',
      'lowConfidence', 'Advice 2');
    await refuses('a model saying "this is not a payment" is believed',
      'notAPayment', 'Advice 3');
    await refuses('an amount that is not a figure is refused',
      'badAmount', 'Advice 4');
    await refuses('a direction that is not debit or credit is refused',
      'noDirection', 'Advice 5');

  } finally {
    if (userId) {
      const deleted = await fetch(`${SUPABASE_URL}/auth/v1/admin/users/${userId}`, {
        method: 'DELETE',
        headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` },
      });
      console.log(`\ncleanup: deleted user -> ${deleted.status}`);
    }
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
