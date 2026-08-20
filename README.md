# Expense Tracker

Automatically capture expenses by reading **email** (Gmail + Outlook) and **Android SMS**
(bank/UPI alerts), parse them into structured transactions, **remove duplicates**, and
surface spending insights — on web first, then mobile.

Read-only access to your mail. No bank credentials are ever stored.

## Stack

| Layer | Choice |
|---|---|
| Web | Next.js (App Router) + TypeScript + Tailwind |
| Mobile | React Native + Expo |
| API | Java 21 + Spring Boot 3 |
| Database | PostgreSQL on Supabase |
| Auth | Supabase Auth |
| Storage | Supabase Storage |
| Web hosting | Vercel |
| API hosting | Render / Railway |
| Analytics | PostHog |
| Errors | Sentry |

## Layout

```
apps/web              Next.js web app
apps/api              Spring Boot API
  src/main/resources/db/migration   Flyway SQL migrations (ship inside the jar)
apps/mobile           Expo app (Phase 4)
packages/shared-types Shared TypeScript types
docs/PLAN.md          Development plan and phasing
```

## Getting started

Prerequisites: JDK 21, Maven 3.9+, Node 20+, a Supabase project.

```bash
cp .env.example .env                      # fill in your Supabase values
cp apps/web/.env.local.example apps/web/.env.local
```

Run the API (applies Flyway migrations on startup):

```bash
cd apps/api && mvn spring-boot:run        # http://localhost:8080
```

Run the web app in a second terminal:

```bash
cd apps/web && npm install && npm run dev # http://localhost:3000
```

Check it works:

```bash
curl http://localhost:8080/api/health     # {"status":"ok", ...}
```

Then open <http://localhost:3000>, create an account, and the API provisions your
default categories and a Cash account on first sign-in.

### Tests

```bash
cd apps/api && mvn test                   # JUnit
cd apps/web && npx tsc --noEmit && npm run lint && npm run build
```

## How authentication works

Supabase signs access tokens with **ES256** and publishes the public key at
`/auth/v1/.well-known/jwks.json`. The API verifies tokens against that JWKS endpoint
and validates the issuer and the `authenticated` audience. The legacy shared HS256
JWT secret is **not** used.

The API connects to Postgres as an owner role that **bypasses RLS**, so every query
must filter by the authenticated user id. RLS is the second line of defence, covering
direct client access with the anon key.

## API

All routes require a bearer token and are scoped to the caller.

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/me` | Profile; provisions categories and a Cash account on first call |
| `GET` | `/api/categories` | Category tree |
| `GET` | `/api/accounts` | Accounts with derived balances (`?includeArchived=true`) |
| `POST` `PUT` | `/api/accounts[/{id}]` | Create / replace an account |
| `DELETE` | `/api/accounts/{id}` | Delete, or archive when transactions still reference it |
| `GET` | `/api/transactions` | Filtered, paged list plus the net total |
| `POST` `PUT` | `/api/transactions[/{id}]` | Create / replace an expense or income |
| `POST` | `/api/transactions/transfers` | Record a transfer as two linked legs |
| `DELETE` | `/api/transactions/{id}` | Soft delete; removes both legs of a transfer |
| `GET` | `/api/duplicates` | Suspected duplicate pairs awaiting review, with signals |
| `GET` | `/api/duplicates/count` | Pending count, for the nav badge |
| `POST` | `/api/duplicates/{id}/merge` | Confirm one payment; keeps the earlier row |
| `POST` | `/api/duplicates/{id}/keep-both` | Two genuinely separate payments |
| `POST` | `/api/duplicates/{id}/dismiss` | Stop asking about this pair |
| `POST` | `/api/imports/preview` | Read a CSV and report what it *would* import; writes nothing |
| `POST` | `/api/imports` | Import the file with a confirmed column mapping |
| `GET` | `/api/budgets` | Budgets with live spend, remaining and status (`?includeInactive=true`) |
| `GET` | `/api/budgets/{id}` | One budget with the same computed figures |
| `POST` `PUT` | `/api/budgets[/{id}]` | Create / replace a budget |
| `DELETE` | `/api/budgets/{id}` | Delete a budget |
| `GET` | `/api/cards` | Credit cards with live outstanding, utilisation and dues |
| `GET` | `/api/cards/{accountId}` | One card |
| `PUT` | `/api/cards/{accountId}` | Save the bank's figures: limit, billing/due day, statement |
| `DELETE` | `/api/cards/{accountId}` | Clear those figures; the account and its transactions stay |
| `GET` | `/api/recurring` | Subscriptions and regular payments, detected plus confirmed |
| `GET` | `/api/recurring/{id}` | One saved recurring payment |
| `POST` | `/api/recurring` | Confirm a detected series, or add one by hand |
| `POST` | `/api/recurring/dismiss` | Stop suggesting a series |
| `PUT` | `/api/recurring/{id}` | Rename, recategorise or pause one |
| `DELETE` | `/api/recurring/{id}` | Stop tracking, or undo a dismissal |
| `GET` | `/api/financial-health` | The health score with every driver behind it |
| `GET` | `/api/notifications` | Live alerts, worked out on read (`?includeDismissed=true`) |
| `GET` | `/api/notifications/count` | Unread total, for the nav badge |
| `POST` | `/api/notifications/read` | Mark one alert read, by key |
| `POST` | `/api/notifications/read-all` | Mark everything currently showing read |
| `POST` | `/api/notifications/dismiss` | Hide an alert until its situation changes |
| `POST` | `/api/notifications/restore` | Undo a dismissal |
| `GET` | `/api/connections` | Every mail provider, whether it is set up, and what you have linked |
| `POST` | `/api/connections/{provider}/start` | Begin linking a mailbox; returns the URL to send the browser to |
| `GET` | `/api/connections/callback/{provider}` | Where the provider returns to. Public by necessity — see below |
| `DELETE` | `/api/connections/{id}` | Unlink a mailbox and destroy its token |
| `POST` | `/api/sync` | Check every connected mailbox for new payment alerts |
| `POST` | `/api/sync/{connectionId}` | Check one mailbox |
| `GET` | `/api/sync/runs` | History of past checks; `limit` (default 10) |
| `POST` | `/api/parse` | Turn every stored alert into transactions |
| `GET` | `/api/parse/queue` | How many alerts are waiting, failed, already read |
| `GET` | `/api/parse/unread` | Alerts we could not read, and why; `limit` (default 50, max 200) |
| `POST` | `/api/parse/retry` | Put failures back in the queue and read them again |
| `POST` | `/api/parse/{messageId}/ignore` | Stop trying to read one message |
| `GET` | `/api/insights` | Everything the dashboard shows for one month; `month=YYYY-MM` (defaults to the month you are in) |

List filters: `from`, `to`, `accountId`, `categoryId`, `merchantId`, `kind`,
`search`, `minAmount`, `maxAmount`, `includeExcluded`, `limit` (max 200), `offset`.

### How duplicates are caught

The same payment routinely arrives twice — once as a bank email, once as an SMS
alert. Every new transaction is screened against existing ones in four layers:

| Layer | Rule |
| --- | --- |
| L0 | Identical raw message — blocked by unique constraints on `raw_messages` |
| L1 | Bank reference (RRN/UTR) — equal means the same payment, different means definitely not |
| L2 | Weighted score: time `0.40`, merchant `0.35`, account `0.15`, source channel `0.10` |
| L3 | Anything uncertain goes to `/review` for the user to decide |

Scores at or above `0.90` merge automatically; `0.55` and above are queued.

Two rules stop the engine from destroying real data:

- **Hand-typed transactions are never merged automatically.** Two identical
  purchases minutes apart score high enough to merge, but a user who types a
  transaction meant to — so those go to review instead. An identical bank
  reference is exempt, because that is proof rather than inference.
- **Transfer legs are excluded entirely.** A transfer is two rows bound by
  `transfer_id`; merging one away would leave the other dangling.

Merging never deletes. The duplicate keeps its row and gains a `merged_into_id`
pointing at the survivor, so any merge can be undone.

### Importing a bank statement

`/import` takes a CSV export from net banking. It is deliberately two steps: the
preview reads the file and shows exactly what it understood — dates, amounts,
directions, and which rows already exist — and writes nothing until confirmed. A
wrong column guess would otherwise file real money against the wrong dates.

Columns are detected from the header row and can be corrected in the UI. The
detection handles split withdrawal/deposit columns (HDFC, ICICI, SBI), a single
signed amount column, and an unsigned amount beside a `DR|CR` indicator (Axis,
Kotak). Balance columns are never mistaken for the amount.

Two rules keep an import honest:

- **Rows sharing an `import_batch_id` are never merged with each other.** A
  statement lists each payment exactly once, so two identical rows in one file
  are two real payments — merging them would erase money the user spent.
- **A repeated bank reference is folded in before insert, not after.** Bank
  references are unique in the database, so re-importing an overlapping
  statement enriches the existing rows instead of failing outright.

Statement dates carry no time, so they are anchored at midday in the user's own
timezone; no zone shift can move a purchase onto the adjacent day.

### How budgets work

A budget is a limit on a category — or on everything, if no category is chosen.
Spend is **computed from the ledger on every read**, never stored, so editing,
deleting, excluding or merging a transaction can never leave a budget quoting a
figure that disagrees with the transaction list.

Four kinds of row are deliberately left out of the total: transfers and income
(moving or receiving your own money is not spending), rows the user marked as
excluded, soft-deleted rows, and rows merged away as duplicates.

- **Periods run from the budget's own start date, not the 1st of the month.**
  Someone paid on the 25th budgets from the 25th. Each window is measured from
  the original start date rather than stepped one period at a time, so a budget
  starting on the 31st does not get stranded on the 28th after February.
- **A budget on a parent category includes its children.** Budgeting "Food"
  means groceries and dining, not an empty parent bucket.
- **Rollover is cumulative, and floored at zero.** Carried-over allowance is
  everything budgeted so far minus everything spent so far, so an overspend in
  one period reduces the next but never becomes an invisible debt.

Status comes from the user's own alert thresholds rather than a fixed rule —
the point of setting them is to decide when you want to be told.

### Credit cards

A credit card *is* an account, so cards are addressed by their account id and
share the same transactions and balance machinery. What the module adds is the
split between two kinds of number:

| Source | Fields |
| --- | --- |
| The ledger | outstanding, available credit, utilisation, spend this cycle, payments made |
| The bank | credit limit, statement and due day, statement balance, minimum due |

They are kept apart deliberately. Collapsing them would leave the user unable to
tell whether a figure came from their bank or from their own bookkeeping.

- **Billing days clamp, they do not spill.** A card billed on the 31st bills on
  the 28th in February — and, because each date is recomputed from the day
  number rather than added to the clamped one, March still bills on the 31st.
- **A bill is never due the moment it is generated.** A card billed and due on
  the 10th gives until the 10th of the following month.
- **Payments are only credited from the day *after* the statement date.** A
  payment made on the statement day may already be inside the balance the bank
  quoted; counting it twice would say "paid" when it is not, and that costs a
  late fee. Over-reporting a debt merely prompts the user to check.
- **Payments count even when marked excluded.** That flag keeps a row out of
  spending analytics; it does not mean the money never moved.

When no statement has been entered the status says `tracking` rather than
guessing — claiming a card is clear because nothing was entered would be worse
than admitting there is nothing to compare against.

### Recurring payments

Subscriptions are found in the ledger rather than declared. Charges are grouped
by merchant, direction and currency, and a group becomes a series when it looks
like one. Detection runs on every read, so deleting a charge, merging a
duplicate or re-dating a transaction changes the answer immediately; nothing is
cached to go stale.

What *is* stored is the user's decision — that a series is real, what to call
it, and which suggestions they never want to see again. Those are facts about
the user, not about the data.

**Three charges minimum.** Two charges give one gap and nothing to compare it
against, which is a coincidence rather than a pattern.

**Six cadences, with wide gaps between them:** weekly, fortnightly, monthly,
quarterly, half-yearly and yearly. Plans are sold on those rhythms. A merchant
charged every 45 days is a habit, not a plan, and admitting them would bury the
real subscriptions in noise. Daily is excluded for the same reason.

**A skipped cycle is a hole in a series, not the end of one.** A subscription
billed on the 5th that misses April is charged 61 days later and is still on the
5th, so the tolerance is not widened for a longer gap. But a run where *every*
gap is two cycles is not monthly-with-holes — it is a slower rhythm this list
does not carry, and it is rejected rather than promising a charge that will
never arrive.

**Amounts may move.** A utility bill that is different every month still
recurs, and is marked as varying rather than forecast precisely. A price
*change* is claimed only when a settled amount stepped to a new one — flagging
an inherently variable bill every month would teach you to ignore the flag.

**Every suggestion explains itself** — how many times it was charged, how many
gaps fit, how close to schedule, and whether the amount held steady. A list you
cannot argue with is a list you will not trust.

**Transfers are excluded.** Moving money between your own accounts is regular
but is not a payment; a monthly card settlement belongs on the cards page.
Charges you excluded from analytics *are* included: that flag hides a row from
spending totals, it does not stop the money leaving.

Rejecting a suggestion and pausing a subscription are stored separately. They
are different intentions, and collapsing them would lose one of them.

### Financial health

One score out of 100, built from five drivers and never from anything the app
cannot see. Nothing is stored — it is recomputed on every read, like budgets and
recurring payments, so fixing something changes the number immediately.

| Driver | Weight | Measures |
| --- | --- | --- |
| Savings rate | 30 | What share of income survives the month |
| Cash buffer | 25 | Months of spending your cash would cover |
| Credit utilisation | 15 | How much of your credit limit is in use |
| Budget discipline | 15 | Whether your own limits are holding |
| Fixed commitments | 15 | How much of each month is already promised |

**What cannot be measured is not counted — and not scored as zero.** Someone
with no credit card has no credit risk; someone who has never opened the budgets
page has not failed at budgeting. Scoring either as nil would tell a perfectly
healthy user they were doing badly, and the suggested fix — take out a card,
create a budget — would have nothing to do with the number that moved. So the
driver is dropped and the remaining weights are renormalised. The response
carries `coverage`, the share of the intended weighting that was measurable, and
the page says so rather than quietly presenting a partial score as a full one.

The rule cuts both ways: nobody is awarded fifteen points for *not* owning a
credit card either.

**The window is the last three complete calendar months.** The current one is
always excluded — on the 2nd it would contribute two days of income against two
days of spending, and a monthly average built from that is nonsense. A partial
first month is dropped for the same reason: history starting on the 20th holds a
third of a salary and would drag every average down for two months afterwards.

**Under eight transactions or one complete month, nothing is scored at all.** A
diagnosis drawn from three transactions would either alarm or reassure at
random, so the page says what it needs instead of inventing a number.

**Each driver is a curve, not a set of bands.** Thresholds would make the score
jump fifteen points because one coffee moved a ratio across a boundary, and a
number that lurches for no visible reason stops being believed. The points the
curves pass through are the ones worth defending out loud: 20% saved, three
months of cover, 30% utilisation.

**Card debt is netted off the cash buffer.** Fifty thousand saved against forty
thousand owed is not fifty thousand of cover, and a buffer that ignores the debt
is exactly the reassurance that makes an emergency expensive.

**Only confirmed subscriptions count as commitments.** An unconfirmed suggestion
would make the score drift on its own as the detector changed its mind about a
merchant — a number that moves without you doing anything is one you stop
trusting.

**Advice is ordered by points recoverable, not by which number looks worst.** A
driver at 30 out of a weight of 15 is worth less attention than one at 60 out of
a weight of 30, and telling you to fix the first would be advice that barely
moves the score you are being shown.

Budgets, cards and recurring payments are read through their own services rather
than requeried here, so the health page can never claim a budget is blown that
the budgets page shows as on track.

### Notifications

Five things are worth interrupting someone for: a budget crossing a threshold,
a card payment falling due, a subscription changing price, a subscription that
should have been charged and wasn't, and duplicates waiting to be judged.

**Alerts are never stored — only your decisions are.** A saved alert outlives
its cause: delete the transaction that blew the budget and the row still insists
there is a problem, pay the card and the reminder still nags. It would also need
a scheduler, which a free-tier instance that sleeps cannot be relied on to run.
So alerts are rebuilt from live state on every read, exactly like budget spend
and card dues. What gets written down is the one thing that cannot be derived:
that you have seen an alert, or told it to go away.

**Each alert is identified by a key describing the situation, not the subject.**

| Alert | Key |
| --- | --- |
| Budget threshold | `budget:{id}:{periodStart}:{threshold}` |
| Card due | `card:{accountId}:{dueDate}` |
| Price change | `price:{matchKey}:{newAmount}` |
| Subscription overdue | `overdue:{matchKey}:{expectedDate}` |
| Duplicates waiting | `duplicates:{newestCandidateId}` |

That is the whole design. Dismissing March's 80% warning must not hide April's,
nor the 100% breach a week later; dismissing one price rise must not swallow the
next one. Because the period, the threshold and the amount are all in the key,
each is a separate alert with its own decision, and a dismissal expires by
itself when the situation genuinely changes.

**Only the highest threshold crossed is raised.** Going from 75% to 105% crosses
80, 90 and 100 at once. Three alerts would bury everything else in the list, and
two of them are no longer true. 100 is always treated as a threshold whether or
not you set it.

**A card whose minimum is already paid is not alerted on.** You made a decision
about that bill on purpose; being reminded of it is how a notification list gets
ignored. Overdue subscriptions stay at `info` rather than a warning, because the
two explanations — a cancellation you already know about, or an import that has
not run — are both things that would be wrong to shout about.

**The duplicates alert is keyed on the newest candidate.** A bare `duplicates`
key would be silenced forever by one dismissal; a key holding the count would
come back every time the number moved, including when you cleared one. Naming
the newest one settles the queue as it stands and speaks up again only when
something new arrives.

Read and dismissed are two nullable timestamps rather than one status, because
dismissing implies having read: an enum would force a choice between them and
lose the fact that a dismissed alert was also, at some point, seen.

### Connecting a mailbox

Most payment alerts already arrive by email. Linking a mailbox lets them become
transactions without anyone typing them in.

The browser never talks to Google or Microsoft on its own. The web app asks the
API to start (it is the only side holding your session), the API hands back a
URL, the browser follows it, and the provider returns to
`/api/connections/callback/{provider}`. That callback cannot be authenticated —
the provider will not carry a session cookie — so it trusts **nothing** in the
request except the `state`, which it looks up. It always answers with a redirect
back into the web app, carrying `?connected=gmail` or `?error=…`; an error page
served from the API would strand you outside the app.

`state` is a database row, not a signed token. A signed token cannot be
revoked once issued and cannot be spent only once, and PKCE needs the code
verifier to stay secret, which a token handed to the browser would not be. The
row is spent in a single statement:

```sql
update oauth_states set consumed_at = now()
where state = ? and consumed_at is null and expires_at > now()
returning ...
```

A replay updates zero rows, so it fails without any extra check. The table has
row-level security enabled with **no policies at all**, meaning no client key of
any kind can read a code verifier.

PKCE (S256) is used even though there is a client secret: the secret proves
*which app* is asking, the verifier proves *which sign-in attempt* is being
finished. Only together do they mean anything.

Tokens are encrypted with AES-256-GCM before they are stored, with your user id
as additional authenticated data — a row copied to another user simply fails to
decrypt. The stored value is `v1.<iv>.<ciphertext>`; the version prefix exists so
the key can be rotated later without a guessing game. If
`TOKEN_ENCRYPTION_KEY` is missing the API still boots, and refuses only when a
mailbox is actually linked; a nightly job should not die because an unrelated
secret is absent.

Gmail is asked for `access_type=offline` **and** `prompt=consent`. Without the
second, reconnecting an already-approved account returns no refresh token and
the link dies silently an hour later. A missing refresh token is therefore
treated as a hard failure rather than a warning.

Disconnecting **deletes the row** instead of soft-deleting it, breaking the
convention used everywhere else in this codebase. That is deliberate:
"disconnect" has to mean the credential is gone, not hidden. Transactions
already imported stay, because they are yours rather than the mailbox's.

Access is read-only in both providers (`gmail.readonly`, `Mail.Read`), so a
compromise of this app could not send or delete mail.

To enable it, set per provider — connecting stays switched off in the UI until
they are present:

| Variable | Notes |
| --- | --- |
| `TOKEN_ENCRYPTION_KEY` | 32 bytes, base64. `openssl rand -base64 32` |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google Cloud console, OAuth client |
| `MICROSOFT_CLIENT_ID` / `MICROSOFT_CLIENT_SECRET` | Entra app registration |
| `OAUTH_API_BASE` | Public URL of the API, for building the redirect URI |
| `OAUTH_WEB_BASE` | Public URL of the web app, for the return trip |

Register the redirect URI as `{OAUTH_API_BASE}/api/connections/callback/gmail`
and `…/callback/outlook`. Microsoft has no per-token revoke endpoint, so
`MICROSOFT_REVOKE_URI` is empty and revocation there is skipped.

### Reading mail

Nothing is read on a schedule. The API runs on a free instance that sleeps when
idle, so a cron job would mostly not fire at all — a scheduler here would be a
promise the deployment cannot keep. Mail is fetched when the user presses
**Check for new alerts** on `/connections`, and only mail that arrived since the
last check.

The endpoints are `POST` rather than `GET` because they are not safe to repeat
blindly: each call spends provider quota and advances a cursor, and a `GET`
would be prefetched by browsers and retried by proxies.

**Only payment alerts are stored.** `MailQuery.looksRelevant` requires *both* a
currency amount and a whole-word payment keyword, and rejects a list of known
bank marketing phrases. A newsletter, a loan advert or a lunch invitation is
counted as fetched and then dropped. This is the single place the promise made
on the connections page is kept, and it is deliberately the narrow gate rather
than the wide one: missing a transaction is recoverable, storing a private
letter is not.

**Every run is recorded** in `sync_runs` — fetched, stored, skipped, whether
more is waiting, and any error — so "why has nothing appeared?" has an answer.
A mailbox with genuinely nothing new looks quite different from one that has
been failing quietly for a week. The table has **select-only** RLS, breaking the
four-policy pattern used elsewhere: a client that could write a run could invent
an import that never happened or hide a failure.

**A run stops after 200 messages.** Gmail needs one request per message, so an
unbounded first sync would be killed by the platform having stored nothing and
advanced nothing. When a run stops early it says so, and pressing again picks up
where it left off.

**Cursor loss is routine, not exceptional.** Gmail returns 404 for a `historyId`
older than about a week; Graph returns 410 for an expired delta link. Both are
mapped to `MailCursorLostException`, which the fetchers treat as a fork — fall
back to a dated scan — rather than a wall. The cursor is advanced even when a
run stores nothing, otherwise the next run re-reads the same mail forever.

Deduplication is left to the database: `insert … on conflict do nothing` with
**no conflict target**, because `raw_messages` carries two unique constraints
(`connection_id + provider_message_id`, and `user_id + body_hash`) and either
one firing means the same thing. Naming one would let the other throw.

`app.mail.gmail-base` and `app.mail.graph-base` exist so sync can be pointed at
a test double; they are separate from the OAuth settings because they answer a
different question — where to ask, rather than proving who is asking.

### Reading alerts

Fetching mail and reading it are **two endpoints, one button**. They are apart
in the API because they fail for unrelated reasons and are worth retrying at
different times: fetching depends on Gmail being reachable and costs quota,
reading depends only on the rules and costs nothing. A user whose bank we do not
understand yet can press *try again* after an update without spending another
provider request. On the page they are one press, because nobody wants to be
told "12 alerts fetched" and then have to hunt for a second button.

**The rules are data, not code.** `parser_rules` holds a match pattern and a map
of field name to `{pattern, group, as}`, seeded by `V12__parsing.sql`. Alert
formats change without warning and differ per issuer; when a bank reformats its
UPI mail the fix should be a row, not a redeploy. It also leaves room for a user
to add a rule for a bank we have never seen. Rules are tried in
`(built-in last), priority, name` order, so a user's own rule beats every
built-in one regardless of its priority number — they know their bank better
than we do. A rule that fails to compile is logged and skipped; one bad row must
not stop every other rule from working.

`SeededRules` in the test suite parses the rules **out of the migration file**
and runs the real parser against them, so the tests cannot drift from what
actually ships.

**A rule that matches but extracts nothing does not end the search.** It is
remembered as a near miss and the remaining rules still get a turn; the near
miss is only reported if nothing else succeeds. Without this a broad catch-all
would shadow every specific rule behind it.

**Some things are deliberately not guessed.** Two accounts ending in the same
four digits means the transaction is created with *no* account rather than the
wrong one — visible and fixable, instead of neither. A merchant capture with no
letters in it is dropped. A reference made of one repeated character is
discarded, because it would wrongly merge unrelated payments. A date more than
21 days before the mail arrived is treated as noise and the arrival date is used
instead, with the substitution recorded in `parse_notes`.

**Parsing is safely re-runnable.** A partial unique index on
`transactions(raw_message_id)` is what makes that true: a message can be read
again, but it cannot produce a second transaction. Dedup still runs on the way
in, so the same payment arriving as both mail and SMS is merged rather than
counted twice — which is why `source_id` is written *at insert time* and not
patched afterwards, since dedup weighs provenance and would otherwise judge a
mail-derived row by the wrong rule.

**Failures are shown, not hidden.** A message that could not be read is listed
on `/connections` with the reason and a snippet, because the alternative is
money quietly missing from the totals. The reason is written for a person: the
exception text is a stack-trace detail and never reaches the page. Each one can
be ignored, which keeps it out of the list without deleting it, so the decision
stays reversible.

Regex input is attacker-influenced — anyone can send you mail — so patterns are
bounded (`{2,60}` rather than `+`) and every match runs against `Bounded`, a
`CharSequence` that gives up after two million character reads. Java 21 turns
the textbook catastrophic patterns into merely *quadratic* ones rather than
exponential, but quadratic across a megabyte body is still minutes of a core.

### The month view

The dashboard is **one endpoint, not four**. Split across separate calls, the
totals and the category breakdown could be built from different snapshots of the
ledger and quietly disagree — and the first thing anyone does with a dashboard
is check whether the parts add up.

**Comparisons are like for like.** On the 12th, this month is compared against
the *first 12 days* of last month, not all 30. Comparing a part-month against a
whole one would tell every user, every month, that their spending had collapsed
— and then reverse the verdict on the last day. The number of days being
compared is stated on the page rather than left to be inferred. When this month
has more days than last month had, the count is clamped: 31 days of March is
compared against all 28 of February, because there is nothing else to compare
it to.

**A percentage needs something to be a percentage of.** Going from nothing to
₹4,000 is not "up 100%" and not "up infinitely"; the API returns `null` and the
page says *"nothing last month"*. Only words can say "this is the first time".

**Projection is withheld early.** Before the 5th, a straight-line pace off two
days of data would swing wildly and read as authority. `projectedExpense` is
`null` until there is enough month to extrapolate from, and never appears on a
month that has already ended.

**Empty months are drawn, not skipped.** A trend line that closes up a gap turns
*"I recorded nothing in June"* into *"I spent evenly through June"*. Months with
no data are filled with zeroes and rendered as empty columns.

**Months are bucketed in the user's timezone**, via
`date_trunc('month', occurred_at at time zone ?)`. Bucketing in UTC moves a
00:30 IST payment into the previous month — and the user, who was awake and
remembers spending it, would be told it never happened.

**"You have no history" is not the same as "this month is empty".** `hasHistory`
is asked of the whole ledger, so someone paging back to a quiet month sees a
quiet month rather than the first-run instructions. `earliestMonth` and
`currentMonth` come back too, so the page knows when to stop offering another
step in either direction — and `currentMonth` comes from the server because the
browser's clock may be in a different zone from the user's settings.

Transfers, excluded rows, deleted rows and rows merged into a duplicate are all
left out of every figure. Spending in another currency is still **added in** —
a total that silently omits money is worse than an approximate one — and a
`mixedCurrencies` flag lets the page admit the sum is rough. The long tail of
small categories is folded into a single *"N smaller categories"* row, and
*biggest changes* includes falls as well as rises, since spending less on
something is the more useful half of the news.

### Money rules worth knowing

- `amount` is always positive; `direction` carries the sign, and `signed_amount`
  is generated by the database.
- Balances are **derived** through `account_balance()`, never incremented per
  write, so they cannot drift when a transaction is edited, deleted or merged.
- A transfer is **two legs sharing a `transfer_id`**, written and deleted
  together; neither leg can be edited alone.
- Deletes are soft, so financial records stay recoverable.
- Merchant strings are normalised (`UPI-SWIGGY LTD` → `SWIGGY`) by matching
  logic in both Java and SQL — change one and you must change the other.

## Documentation

- [`docs/PLAN.md`](docs/PLAN.md) — scope, architecture, data model, dedup strategy, phasing.
- [`docs/FEATURES.md`](docs/FEATURES.md) — full feature catalogue with V1/V2/V3 phasing.
- [`docs/BLUEPRINT-REVIEW.md`](docs/BLUEPRINT-REVIEW.md) — review of the original product blueprint.

## Security

Secrets live in `.env` and `apps/web/.env.local`, both git-ignored. Never commit
credentials, OAuth client secrets, or the Supabase `service_role` key.
