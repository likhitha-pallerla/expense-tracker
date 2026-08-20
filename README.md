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
