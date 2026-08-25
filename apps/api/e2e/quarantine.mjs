// Proves the mail sender gate actually blocks the attack it was written for.
//
// Mail is not authenticated. Before this gate existed, anyone who knew a user's
// email address could send them a message reading "Rs 48,500 debited from a/c
// XX4412" and the parser -- which reads any message containing an amount and
// the word "debited" -- would write that number into their financial history.
// Every budget, forecast and health score built on top of it would be wrong,
// and an attacker's text would be sitting in a screen the user trusts.
//
// Unit tests cover SenderTrust's judgement in isolation. This covers the part
// they cannot: that the gate is actually wired into the path a real message
// travels, that a real bank still gets through, that SMS is not caught by it,
// and that the release valve works -- because a gate with no release is just
// data loss with extra steps.

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

async function main() {
  const email = `p6-quarantine-${Date.now()}@example.com`;
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
        method: 'POST', headers: auth, body: JSON.stringify(body),
      });
      return { status: res.status, body: await res.json().catch(() => null) };
    };

    // Direct writes, because these stand in for what a mail sync would have
    // stored. Throwing on failure matters: PostgREST rejects a bad column
    // quietly with a 400, and a silent no-op here would leave every downstream
    // assertion reading an empty list and passing while testing nothing.
    const write = async (table, row) => {
      const res = await fetch(`${SUPABASE_URL}/rest/v1/${table}`, {
        method: 'POST',
        headers: {
          apikey: SERVICE_KEY,
          Authorization: `Bearer ${SERVICE_KEY}`,
          'Content-Type': 'application/json',
          Prefer: 'return=representation',
        },
        body: JSON.stringify(row),
      });
      const out = await res.json();
      if (!res.ok) throw new Error(`${table} insert failed: ${JSON.stringify(out)}`);
      return out[0];
    };

    const connection = (provider) =>
      write('source_connections', {
        user_id: userId,
        provider,
        external_account: `${provider}@example.com`,
        display_name: provider,
        status: 'active',
      });

    const message = async (connectionId, sender, subject, body) =>
      write('raw_messages', {
        user_id: userId,
        connection_id: connectionId,
        provider_message_id: `q-${Math.random()}`,
        sender,
        subject,
        body,
        body_hash: `h${Math.random()}`.slice(0, 40),
        received_at: new Date().toISOString(),
        status: 'pending',
      });

    await get('/api/me');
    const gmail = await connection('gmail');
    const sms = await connection('android_sms');

    console.log('\n-- a forged alert does not become a transaction --');

    // The attack. Wording lifted from a genuine HDFC alert so the parser has
    // every reason to read it; the only thing wrong with it is who sent it.
    await message(
      gmail.id,
      'attacker@totally-not-a-bank.example',
      'Transaction alert',
      'Rs 48500.00 has been debited from a/c XX4412 on 24-08-26 to VPA scammer@upi. Not you? Call us.',
    );

    const first = await post('/api/parse', {});
    check('the pass reports it as held, not imported',
      first.body?.quarantined === 1 && first.body?.imported === 0,
      JSON.stringify(first.body));

    const afterForged = await get('/api/transactions?limit=50');
    const forgedLanded = (afterForged.items ?? afterForged ?? [])
      .some((t) => String(t.amount).includes('48500'));
    check('no transaction was created from it', !forgedLanded);

    check('the summary says so out loud rather than reporting nothing',
      /confirm the sender/.test(first.body?.summary ?? ''),
      first.body?.summary);

    const held = await get('/api/parse/held');
    check('it is offered to the user rather than discarded',
      held.length === 1 && held[0].sender === 'attacker@totally-not-a-bank.example',
      JSON.stringify(held));
    check('grouped with a count, so one sender is one decision',
      held[0]?.messages === 1);
    check('and the reason names the domain the user has to judge',
      (held[0]?.reason ?? '').includes('totally-not-a-bank.example'),
      held[0]?.reason);

    console.log('\n-- a real bank is not made harder to use --');

    await message(
      gmail.id,
      'alerts@hdfcbank.net',
      'Transaction alert',
      'Rs 1250.00 has been debited from a/c XX4412 on 24-08-26 to Blue Tokai Coffee.',
    );
    const second = await post('/api/parse', {});
    check('a recognised bank is read without being asked about',
      second.body?.imported === 1 && second.body?.quarantined === 0,
      JSON.stringify(second.body));

    console.log('\n-- text messages are not caught by a rule about domains --');

    // SMS has no sender domain to judge -- a text arrives from a shortcode.
    // It has always had its own gate; being caught by this one as well would
    // quarantine every text the app receives.
    await message(sms.id, 'VM-HDFCBK', null,
      'Rs 320.00 debited from a/c XX4412 on 24-08-26 to Auto Rickshaw.');
    const third = await post('/api/parse', {});
    check('an SMS is read normally', third.body?.quarantined === 0,
      JSON.stringify(third.body));

    console.log('\n-- the user can let one through --');

    const trust = await post('/api/parse/trusted', {
      domain: 'totally-not-a-bank.example', note: 'testing',
    });
    check('accepting a sender releases what was held from it',
      trust.status === 200 && trust.body?.released === 1,
      JSON.stringify(trust.body));

    const released = await post('/api/parse', {});
    check('and the released message is then read',
      released.body?.imported === 1, JSON.stringify(released.body));

    check('the held list is now empty', (await get('/api/parse/held')).length === 0);
    check('and the sender is listed as one the user accepts',
      (await get('/api/parse/trusted')).some((s) => s.domain === 'totally-not-a-bank.example'));

    console.log('\n-- what the user is not allowed to do --');

    // Trusting a consumer mail provider would trust every one of its users,
    // which is the entire hole this closes. Refused, with the reason.
    const gmailTrust = await post('/api/parse/trusted', { domain: 'gmail.com' });
    check('a consumer mail domain cannot be accepted', gmailTrust.status === 400,
      `${gmailTrust.status}`);
    check('and the refusal explains why rather than just failing',
      JSON.stringify(gmailTrust.body ?? '').includes('anyone can send'),
      JSON.stringify(gmailTrust.body));

    console.log('\n-- a lookalike domain is still a different domain --');

    await message(
      gmail.id,
      'alerts@my-hdfcbank.net.evil.example',
      'Transaction alert',
      'Rs 9900.00 has been debited from a/c XX4412 on 24-08-26 to Someone Else.',
    );
    const lookalike = await post('/api/parse', {});
    check('a domain that merely ends with a bank name is held',
      lookalike.body?.quarantined === 1, JSON.stringify(lookalike.body));

    console.log('\n-- withdrawing trust --');

    const remove = await fetch(`${API}/api/parse/trusted/totally-not-a-bank.example`, {
      method: 'DELETE', headers: auth,
    });
    check('a sender can be removed again', remove.status === 200, `${remove.status}`);
    check('and is gone from the list',
      (await get('/api/parse/trusted')).every((s) => s.domain !== 'totally-not-a-bank.example'));

    // Removing trust must not retroactively delete what it already recorded.
    // The user accepted those at the time; unpicking them silently would be a
    // worse surprise than the one being prevented.
    const surviving = await get('/api/transactions?limit=50');
    check('transactions it already produced are left alone',
      (surviving.items ?? surviving ?? []).some((t) => String(t.amount).includes('48500')));
  } finally {
    if (userId) {
      const res = await fetch(`${SUPABASE_URL}/auth/v1/admin/users/${userId}`, {
        method: 'DELETE',
        headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` },
      });
      console.log(`\ncleanup: deleted user -> ${res.status}`);
    }
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
