// End-to-end check of the Phase 5 endpoints against a real API and a real
// Supabase project. Creates its own user, and deletes it in a finally block so
// a failure part-way through does not leave an orphan behind.
//
// Verifies what unit tests cannot: that V14 applied, that hint resolution finds
// the user's own accounts and categories, and that both new endpoints behave
// correctly with AI switched off -- which is the default and therefore the
// configuration almost every install will run in.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const env = {};
// Split on both line endings and strip any stray carriage return. In
// JavaScript "\r" is a line terminator, so "." does not match it and a naive
// /^([A-Z_]+)=(.+)$/ silently fails on every CRLF line in the file -- which is
// most of them, and not all of them, because this .env has both.
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
  const email = `p5-${Date.now()}@example.com`;
  const password = 'Test-Password-9f2!';
  let userId = null;
  let token = null;

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

    const signIn = await fetch(
      `${SUPABASE_URL}/auth/v1/token?grant_type=password`,
      {
        method: 'POST',
        headers: { apikey: ANON_KEY, 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      },
    );
    token = (await signIn.json()).access_token;
    if (!token) throw new Error('could not sign in');

    const auth = {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    };
    const get = async (path) =>
      (await fetch(`${API}${path}`, { headers: auth })).json();
    const post = async (path, body) => {
      const res = await fetch(`${API}${path}`, {
        method: 'POST',
        headers: auth,
        body: JSON.stringify(body),
      });
      return { status: res.status, body: await res.json().catch(() => null) };
    };

    // Provisions the profile, the category tree and a default account.
    const profile = await get('/api/me');
    check('profile provisions', !!profile.userId, JSON.stringify(profile));

    const categories = await get('/api/categories');
    check('categories exist', categories.length > 0, `${categories.length}`);

    // An account with a name and last4 the parser will have to match against.
    const account = await post('/api/accounts', {
      name: 'HDFC Millennia Credit Card',
      type: 'credit_card',
      currency: 'INR',
      last4: '4821',
      openingBalance: '0',
    });
    check('account created', account.status === 200 || account.status === 201,
      `${account.status} ${JSON.stringify(account.body)}`);
    const accountId = account.body?.id;

    console.log('\n-- reading sentences --');

    const headline = await post('/api/entry/parse', {
      text: 'Spent 850 on dinner at Zomato using HDFC card',
    });
    const h = headline.body;
    check('headline: amount', Number(h?.amount) === 850, JSON.stringify(h));
    check('headline: direction', h?.direction === 'debit', h?.direction);
    check('headline: merchant', /zomato/i.test(h?.merchant ?? ''), h?.merchant);
    check('headline: resolved the account by name', h?.accountId === accountId,
      `${h?.accountId} vs ${accountId} (hint "${h?.accountHint}")`);
    check('headline: read by rules, not a model', h?.source === 'rules', h?.source);
    check('headline: creates nothing on its own',
      (await get('/api/transactions?limit=5')).items?.length === 0);

    const digits = await post('/api/entry/parse', {
      text: '1200 groceries using card 4821',
    });
    check('resolves an account by its last four digits',
      digits.body?.accountId === accountId,
      `${digits.body?.accountId} (hint "${digits.body?.accountHint}")`);

    const dated = await post('/api/entry/parse', { text: 'paid 1200 on 12 Jan' });
    check('a date is not mistaken for the amount',
      Number(dated.body?.amount) === 1200, JSON.stringify(dated.body));
    check('the date was understood', dated.body?.dateExplicit === true,
      dated.body?.occurredOn);

    const credit = await post('/api/entry/parse', { text: 'got 5000 salary' });
    check('money coming in reads as credit', credit.body?.direction === 'credit',
      credit.body?.direction);

    const category = await post('/api/entry/parse', {
      text: '450 on groceries',
    });
    check('resolves a category the user actually has',
      !!category.body?.categoryId,
      `hint "${category.body?.categoryHint}" -> ${category.body?.categoryName}`);

    const nothing = await post('/api/entry/parse', { text: 'lunch at zomato' });
    check('refuses a sentence with no amount',
      !!nothing.body?.problem && nothing.body?.amount == null,
      JSON.stringify(nothing.body));

    const blank = await post('/api/entry/parse', { text: '   ' });
    check('rejects an empty box with 400', blank.status === 400, `${blank.status}`);

    console.log('\n-- confirming one --');

    const confirmed = await post('/api/transactions', {
      kind: 'expense',
      amount: String(h.amount),
      currency: 'INR',
      occurredAt: new Date(`${h.occurredOn}T12:00:00Z`).toISOString(),
      description: h.description,
      accountId: h.accountId,
      categoryId: h.categoryId,
      merchant: h.merchant,
    });
    check('the confirmed draft becomes a transaction',
      confirmed.status === 200 || confirmed.status === 201,
      `${confirmed.status} ${JSON.stringify(confirmed.body)}`);

    const listed = await get('/api/transactions?limit=5');
    check('and shows up in the list', listed.items?.length === 1,
      `${listed.items?.length}`);

    console.log('\n-- the month in a sentence --');

    const summary = await get('/api/insights/summary');
    check('summary comes back', typeof summary?.text === 'string',
      JSON.stringify(summary));
    check('with AI off it is the deterministic one',
      summary?.source === 'template', summary?.source);
    check('it quotes the real total', summary?.text?.includes('850'),
      summary?.text);
    check('it counts the one payment correctly',
      summary?.text?.includes('1 payment') && !summary?.text?.includes('1 payments'),
      summary?.text);

    const older = await get('/api/insights/summary?month=2020-01');
    check('a month with nothing in it says so',
      typeof older?.text === 'string' && older.text.length > 0, older?.text);

    console.log('\n-- V14 applied --');

    const columns = await fetch(
      `${SUPABASE_URL}/rest/v1/raw_messages?select=parsed_by,ai_confidence&limit=1`,
      { headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` } },
    );
    check('raw_messages has the new columns', columns.status === 200,
      `${columns.status} ${await columns.text()}`);

    const usage = await fetch(
      `${SUPABASE_URL}/rest/v1/ai_usage?select=user_id&limit=1`,
      { headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` } },
    );
    check('ai_usage table exists', usage.status === 200,
      `${usage.status} ${await usage.text()}`);

    console.log('\n-- parsing still works with AI off --');

    const parsed = await post('/api/parse', {});
    check('parse runs and reports nothing waiting',
      parsed.status === 200 && parsed.body?.read === 0,
      `${parsed.status} ${JSON.stringify(parsed.body)}`);

  } finally {
    if (userId) {
      const deleted = await fetch(
        `${SUPABASE_URL}/auth/v1/admin/users/${userId}`,
        {
          method: 'DELETE',
          headers: {
            apikey: SERVICE_KEY,
            Authorization: `Bearer ${SERVICE_KEY}`,
          },
        },
      );
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
