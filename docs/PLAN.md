# Expense Tracker — Development Plan (merged)

> Supersedes the first draft. Merges the **Public Expense Tracker Blueprint**'s product
> shape with this plan's ingestion + deduplication engineering and a corrected data model.
> See `BLUEPRINT-REVIEW.md` for the reasoning behind each change.

**Product promise:** *Know where your money goes, where it is going next, and whether
you are on track.*

**Launch decision (locked):** **Personal / invite-only first.** Google OAuth stays in
testing mode (100-user cap), which avoids paid CASA verification and public-product
legal obligations. Design so it *can* go public later, but do not pay that cost yet.

---

## 1. Product Pillars

| Pillar | User question | Product response |
|---|---|---|
| **TRACK** | Where did my money go? | Auto-capture from mail & SMS, CSV import, manual entry, transactions, accounts, categories |
| **UNDERSTAND** | Why did my spending change? | Analytics, trends, month-over-month, merchant insights, anomaly detection |
| **PLAN** | What can I afford next? | Budgets, goals, cash-flow forecast, financial health score |

**Standing architectural rule:** *AI explains, the backend computes.* All financial
maths is deterministic, tested Java. AI is used only to classify uncertain data,
summarise trends, and provide a conversational interface — never to compute a number
the user relies on.

**Differentiator:** automatic capture from email + SMS with **trustworthy duplicate
handling**. Manual-entry trackers get abandoned; this one fills itself in.

---

## 2. Architecture

```
                 ┌──────────────────────────────────────────────┐
                 │            CLIENTS                           │
                 │  Next.js web (Vercel)                        │
                 │  Expo React Native app (Android SMS reader)  │
                 └───────────────┬──────────────────────────────┘
                                 │ HTTPS + Supabase JWT
                 ┌───────────────▼──────────────────────────────┐
                 │  Spring Boot modular monolith (Render)       │
                 │  auth · accounts · transactions · categories │
                 │  ingestion · parsing · dedup · budgets       │
                 │  goals · analytics · notifications · ai      │
                 └───────────────┬──────────────────────────────┘
                                 │ JDBC / service role
                 ┌───────────────▼──────────────────────────────┐
                 │  Supabase: PostgreSQL + Auth + Storage        │
                 │  Row Level Security on every table            │
                 └──────────────────────────────────────────────┘

External: Gmail API (Pub/Sub push) · Microsoft Graph (delta + subscriptions)
Observability: Sentry · PostHog
Deliberately omitted for now: Redis (adds ops burden, no V1 benefit)
```

**Modular monolith, not microservices.** Split out transaction processing,
notifications, analytics or AI only when usage justifies it.

### Repository layout

```
expense-tracker/
├── apps/web            Next.js App Router + TypeScript + Tailwind + shadcn/ui
├── apps/mobile         Expo React Native (Phase 4)
├── apps/api            Spring Boot 3 (Java 21) + Maven
├── packages/shared-types
├── db/migrations       Flyway SQL
├── db/policies         RLS policies
└── docs/
```

### Navigation

- **Web:** Dashboard · Transactions · Budgets · Analytics · Goals · Accounts · Settings
- **Mobile:** Home · Add · Transactions · Analytics · Profile

---

## 3. Data Model

Corrections applied versus the blueprint's `§10` are marked **[fix]**.

### `transactions` — the heart of the system

| Column | Type | Note |
|---|---|---|
| `id` | uuid | |
| `user_id` | uuid | RLS key |
| `account_id` | uuid | |
| `category_id` | uuid | |
| `merchant_id` | uuid | **[fix]** FK, not free text — otherwise "SWIGGY", "Swiggy Ltd" and "SWIGGY*ORDER" become three merchants |
| `amount` | `NUMERIC(14,2)` | **[fix]** never float/double |
| `currency` | `CHAR(3)` | **[fix]** blueprint had none, hard-coding INR |
| `base_amount` | `NUMERIC(14,2)` | converted to the user's base currency |
| `fx_rate` | `NUMERIC(18,8)` | rate on the transaction date |
| `direction` | enum | `debit` / `credit` |
| `kind` | enum | `expense` / `income` / `transfer` |
| `transfer_id` | uuid | **[fix]** a transfer is **two linked legs**; a single `account_id` row cannot express one. Both legs excluded from spend totals |
| `occurred_at` | timestamptz | |
| `description`, `notes` | text | |
| `external_ref` | text | RRN / UTR / auth code — the strongest dedup signal |
| `source_id` | uuid | which mailbox / device / import produced this |
| `raw_message_id` | uuid | **provenance** — open an expense, see the message it came from |
| `confidence` | numeric | parser confidence |
| `merged_into_id` | uuid | self-FK; aggregates count only `NULL` |
| `transaction_group_id` | uuid | duplicate cluster |
| `is_excluded` | boolean | e.g. self-transfers, excluded from reports |
| `deleted_at` | timestamptz | **[fix]** soft delete — financial records stay recoverable |

### Other tables

`profiles` · `accounts` (bank / cash / UPI / wallet / credit card; nickname, last4,
opening balance) · `credit_card_details` (limit, outstanding, billing date, due date,
minimum due) · `categories` (hierarchical, system + custom) · `merchants` +
`merchant_aliases` · `source_connections` (provider, encrypted refresh token, sync
cursor) · `raw_messages` (provider message id, body hash, redacted snippet) ·
`parser_rules` · `category_rules` · `transaction_groups` · `duplicate_candidates` ·
`budgets` · `goals` · `recurring_transactions` · `notifications` · `audit_log`.

### Rules

- **RLS on every table**: `user_id = auth.uid()`. The backend uses the service role and
  *additionally* filters by user — defence in depth.
- `raw_messages`: `UNIQUE (connection_id, provider_message_id)` and
  `UNIQUE (user_id, body_hash)` — this is dedup layer L0, enforced by the database.
- `transactions`: partial unique on `(user_id, external_ref)` where `external_ref IS NOT NULL`.
- **Balances are derived**, not mutated. **[fix]** The blueprint's "every transaction
  updates the balance" drifts under concurrent writes, edits and deletes. Compute
  `opening_balance + SUM(signed amounts)`, cache it, and reconcile on a schedule.

---

## 4. Ingestion → Transaction Pipeline

```
1. INGEST      Gmail push (Pub/Sub) · Graph delta/subscription · SMS batch from Android
2. PRE-FILTER  Sender allowlist + keyword regex; non-financial mail dropped before storage
3. NORMALISE   Strip HTML, canonicalise ₹ / Rs. / INR, lakh-crore, DD-MM-YY
4. EXTRACT     DB-stored rule templates per bank → amount, currency, direction,
               merchant, last4, external_ref, occurred_at, balance
               Unmatched → quarantine queue (later: LLM fallback)
5. RESOLVE     Merchant alias → canonical merchant; account last4 → account
6. CATEGORISE  merchant map → user rules → learned model → Uncategorised
7. DEDUP       L0..L3 (below)
8. PERSIST     Transaction + audit entry + realtime push to clients
```

Adding a new bank must never require a redeploy — templates live in `parser_rules`.

---

## 5. Duplicate Detection

Duplicates arise from six real sources: re-sync overlap · bank SMS *and* bank email ·
alert now, statement later · bank alert *and* merchant receipt (HDFC UPI SMS + Swiggy
email) · pre-auth then settlement · refunds and reversals.

**L0 — Exact.** DB unique constraints on provider message id and normalised body hash.

**L1 — Deterministic.** Exact match on `(user_id, amount, currency, external_ref)`.
Banks repeat the same reference across SMS, email and statement. Auto-merge.

**L2 — Probabilistic.** Weighted score over candidates inside a time window:

| Signal | Weight |
|---|---|
| Exact amount + currency | 0.35 |
| Instrument match (card last4 / UPI VPA / account) | 0.20 |
| Timestamp delta in window (±10 min default, up to 72 h for email lag) | 0.20 |
| Normalised merchant similarity (token-set + trigram ≥ 0.8) | 0.20 |
| Same direction | 0.05 |

Candidates are pre-filtered by index (`user_id`, amount bucket, time window) — never
an O(n²) scan.

**L3 — Decision.** ≥ 0.90 auto-merge · 0.60–0.90 → **review queue** showing which
signals matched · < 0.60 distinct.

**Merge is never destructive.** Duplicates join a `transaction_group`; one row is
primary, others carry `merged_into_id`. Aggregates count primaries only. Un-merge is
one click and every merge is logged. That reversibility is what makes the feature
trustworthy.

**Special cases.** Pre-auth → settlement *supersedes* (amount replaced) rather than
merges. Refunds link as `refund_of` and are netted in reports, not hidden.
EMI conversions are always flagged for review, never auto-merged.

---

## 6. Security & Privacy

- Read-only scopes only: Gmail `gmail.readonly`, Graph `Mail.Read`. **No bank
  credentials, ever. No screen-scraping. No payments.**
- OAuth stays in Google testing mode (100 users) — no CASA cost while personal.
- Refresh tokens encrypted at rest (AES-256-GCM envelope encryption); keys in the
  host secret manager, never in the repo or DB.
- Data minimisation: extracted fields + a short redacted snippet. Raw bodies optional,
  30-day default retention. Account numbers stored as **last4 only**.
- RLS everywhere; `service_role` key exists only server-side.
- HTTPS everywhere · input validation · rate limiting · parameterised SQL.
- Never log passwords, card numbers, tokens or bank credentials.
- User control: disconnect a source, export everything, delete account and all data.
- `audit_log` answers "why did this expense appear, and what changed it?"

---

## 7. Phased Delivery

### Phase 0 — Foundations
✅ JDK 21 + Maven installed. Monorepo, Git, Supabase project, schema + Flyway + RLS,
Spring Boot skeleton with Supabase JWT filter, Next.js skeleton with auth, CI.

### Phase 1 — Web V1
Accounts (bank/cash/UPI/wallet/card) · transactions incl. **income** and **transfers as
two legs** · categories · merchant canonicalisation · manual entry · CSV import with a
review screen · **the full dedup engine + review queue** · monthly dashboard (income,
expenses, savings, savings rate, category breakdown, trend, top merchants) · settings,
export, delete account.

*Dedup ships in V1 even though it is "advanced": it is the hardest logic, it is fully
testable against CSV import alone, and every later feature sits on top of a correct
transaction model.*

### Phase 1.5 — Depth
Budgets with thresholds · recurring & subscription detection · credit-card module
(limit, outstanding, billing/due date, minimum due) · financial health score with
visible drivers · notifications.

### Phase 2 — Automatic capture
Gmail + Outlook OAuth, encrypted tokens, incremental sync, webhooks, backfill ·
parser rule engine + template catalogue for major Indian banks · quarantine queue ·
automatic transfer detection.

### Phase 3 — Plan
Goals with suggested monthly contribution · cash-flow forecasting · anomaly detection ·
advanced analytics · reports.

### Phase 4 — Mobile
Expo app · Android SMS capture with on-device pre-filter and idempotent batched upload ·
offline queue · push · biometric lock. iOS uses email + manual + share sheet.

### Phase 5 — AI layer ✅
LLM fallback parser (schema-constrained, confidence-gated) · natural-language entry
("Spent 850 on dinner at Zomato using HDFC card") · plain-English month summary
over deterministic figures.

Off by default and useful with it off: the rules read alerts, a deterministic
sentence explains the month, and the typing box asks you to rephrase rather than
guess. AI is a **second attempt where the rules gave up**, never an override.
Every reading is re-parsed and range-checked before it becomes a transaction, and
every number in a summary must already exist in the figures behind it.

**Receipt photo OCR — deferred, not cancelled.** It needs image upload, storage and
a vision model, which is a different shape of problem from the text path built here
and cannot be tested without one. Building an unverifiable path into a money app is
worse than not building it. Revisit when there is a real user asking for it.

### Phase 6 — Harden
Sentry · PostHog · rate limiting · structured logging · backups · security review ·
Vercel + Render production deploy.

*Net worth, investments and liabilities remain post-V2 and are only worth building if
usage evidence supports them.*

---

## 8. Monetization (later)

Keep everything free while validating usage. If it is ever monetised, price
**automation and AI** — ingestion, receipt scanning, forecasting, advanced analytics —
never basic record-keeping. Locking manual entry behind a paywall kills adoption.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Google restricted-scope verification | Deferred entirely by staying invite-only under the 100-user cap |
| Bank formats vary and change | DB-stored templates (no redeploy) + quarantine queue + LLM fallback |
| Over-aggressive dedup hides real spend | Never delete; conservative threshold; review queue; one-click un-merge |
| Scope creep (blueprint lists ~40 features) | Hard V1 cut above; net worth and investments deferred |
| Balance drift | Derived balances + scheduled reconciliation |
| Free-tier sleep (Render, Supabase) | Keep-alive pings; acceptable while invite-only |

---

## 10. Immediate Next Steps

1. ✅ JDK 21 + Maven installed and verified.
2. `git init`; `.gitignore` and `README` are in place.
3. Create the Supabase project; fill `.env.example`.
4. Write the initial Flyway migration (§3) + RLS policies.
5. Scaffold the Spring Boot API: health endpoint + Supabase JWT filter.
6. Scaffold Next.js: Supabase auth + protected dashboard shell.
7. GitHub Actions CI.
