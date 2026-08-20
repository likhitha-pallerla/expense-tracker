# Review — "Public Expense Tracker" Blueprint

Reviewed against `PLAN.md` and `FEATURES.md`.

**Summary:** the blueprint is the stronger *product* document — vision, monetization,
and the TRACK / UNDERSTAND / PLAN framing are genuinely good. But it silently drops
the original core idea, and its data model has several defects that would be
expensive to fix after launch.

---

## 1. The critical omission

The blueprint never mentions:

- **Email ingestion** (Gmail / Outlook)
- **SMS ingestion** (bank / UPI alerts)
- **Duplicate detection**

Those three *are* the original brief: *"this should read my messages and mails and
also filter out duplicates and add the expense."* The blueprint replaces automatic
capture with manual entry + CSV import and calls that the differentiator.

It is right that CSV import is a safer starting point (`§13.1`), and both documents
agree on sequencing it first. The problem is that the blueprint drops auto-ingestion
**entirely** rather than deferring it — so the finished product is a well-built but
undifferentiated manual tracker. Manual entry is exactly the thing users abandon in
week three.

**Verdict:** keep the blueprint's CSV-first sequencing; restore ingestion + dedup as
the V1.5/V2 differentiator.

---

## 2. The strategic fork: personal tool or public product?

The blueprint's title and goal say **"public, multi-user"**. My plan assumed a
personal tool. This single choice changes everything downstream:

| Dimension | Personal / small beta | Public product |
|---|---|---|
| Gmail `gmail.readonly` | Stay in Google "testing" mode, 100-user cap, **free** | Full OAuth verification **+ annual CASA security assessment** — paid, and weeks-to-months of lead time |
| Legal | None | Privacy policy, terms, DPA. India DPDP Act: consent notice, grievance officer, breach reporting. GDPR if any EU user |
| Security bar | Reasonable | You are holding strangers' bank data. Pen test, incident response, cyber insurance |
| Support | None | Users email you when a bank template breaks |
| Cost | Genuinely ~free | CASA + Apple $99/yr + Play $25 + domain + a non-sleeping backend dyno |

**The "free-tier public launch" premise partly conflicts with email ingestion.**
You can have a free public launch *or* Gmail reading at public scale, not both
cheaply. Honest options:

- **A. Personal / invite-only first.** Full ingestion + dedup, 100 test users, zero
  verification cost. Prove the hard part. *(My recommendation.)*
- **B. Public, manual + CSV only.** Follow the blueprint literally. Genuinely free,
  but no differentiator.
- **C. Public with email, budgeted.** Accept CASA cost and legal work from the start.

---

## 3. Data-model defects to fix before writing migrations

The `transactions` table in `§10.1` has real problems:

| Issue | Why it matters | Fix |
|---|---|---|
| No data types given for `amount` | Money in `float`/`double` silently corrupts totals | `NUMERIC(14,2)`, never floating point |
| **No `currency` column** | Hard-codes INR; breaks on any foreign spend | `currency CHAR(3)` + original amount + FX rate |
| `merchant` is free text | "SWIGGY", "Swiggy Ltd", "SWIGGY*ORDER" become three merchants; merchant analytics becomes noise | `merchant_id` FK + `merchant_aliases` table |
| `payment_method` *and* `account_id` | Overlapping and contradictory | Payment method belongs to the account, not the transaction |
| **Transfers cannot be represented** | A transfer has two legs (debit A, credit B); one row with one `account_id` cannot express it | Two linked rows sharing a `transfer_id`, both excluded from spend totals |
| No soft delete | Financial records should be recoverable and auditable | `deleted_at` + never hard-delete |
| No source/provenance columns | Cannot answer "where did this expense come from?" | `source_id`, `raw_message_id`, `confidence` |
| No dedup columns | No `external_ref`, no merge group | `external_ref`, `merged_into_id`, `transaction_group_id` |

Also, `§2.3`: *"Every transaction should update the associated balance"* — a mutable
running balance drifts under concurrent writes, edits, and deletes. Safer: store an
opening balance and **derive** the current balance, or maintain it inside the same DB
transaction with row locking and a periodic reconciliation job.

`§11` says *"password hashing when applicable"* — with Supabase Auth you never touch
passwords, so this is a non-issue. Good.

---

## 4. What the blueprint gets right — adopt these

1. **TRACK / UNDERSTAND / PLAN pillars.** Excellent framing; better than my flat
   feature list. Adopt it as the navigation model.
2. **"AI explains, the backend computes."** (`§16`) Exactly right. Financial maths
   must be deterministic and testable; AI classifies uncertain data and narrates.
   This should be a standing architectural rule.
3. **Income tracking and savings rate.** My plan was expense-only. Without income
   there is no savings rate, no cash-flow forecast, no financial health score.
4. **Accounts as first-class** with balances, across bank / cash / UPI / wallet /
   credit card. Stronger than my "card last4 nickname" model.
5. **Credit-card module** — limit, outstanding, billing date, due date, minimum due.
   Highly relevant for Indian users; I had under-prioritised this.
6. **Goals** with suggested monthly contribution.
7. **Cash-flow forecasting** — genuinely forward-looking, and rare in free trackers.
8. **Financial Health Score with visible drivers.** Good differentiator, and
   "not a black box" is the right instinct.
9. **Monetization model** (Free vs Pro) — I had none. Pricing automation and AI
   rather than basic record-keeping is the correct call.
10. **Natural-language entry** — "Spent 850 on dinner at Zomato using HDFC card".
11. **Modular monolith, not microservices.** Correct for this stage.
12. **Landing page** in the page list — needed for a public product; I had omitted it.

## 5. What my plan has that must be merged back

Ingestion pipeline · parser rule engine (DB-stored bank templates) · the four-layer
dedup engine · non-destructive merge groups with un-merge · provenance ("open an
expense, see the message it came from") · automatic transfer detection · merchant
canonicalisation · multi-currency · RLS specifics for Supabase · retention, export
and delete-my-data.

---

## 6. Scope judgement

The blueprint's V1 (`§15`) is ten areas: signup, dashboard, add expense, categories,
accounts, transactions, budgets, analytics, CSV import, settings — plus it wants
goals, net worth, investments, credit cards, subscriptions, health score and an AI
assistant close behind. That is a lot for a small team, and it contradicts its own
closing advice to *"ship the smallest polished version"*.

**Recommended cut for a true V1:**

| Keep in V1 | Defer to V1.5 | Defer to V2+ |
|---|---|---|
| Auth + landing page | Budgets | Goals |
| Accounts (bank/cash/UPI/card) | Recurring & subscriptions | Net worth |
| Transactions: expense, income, transfer | Credit-card module | Investments |
| Categories + merchant canonicalisation | Financial health score | AI assistant |
| CSV import with review screen | Email ingestion + parsers | Receipt OCR |
| **Dedup engine + review queue** | | Cash-flow forecasting |
| Monthly dashboard | | |
| Settings, export, delete account | | |

Dedup stays in V1 despite being "advanced" because it is the hardest logic in the
product, it is testable against CSV import alone, and everything else is easy to add
on top of a correct transaction model.

---

## 7. Recommendation

Merge the two documents: **the blueprint's product shape, my ingestion and dedup
engineering, and a corrected data model.** Then pick a lane on the personal-versus-
public fork before the first migration is written, because it determines the OAuth
strategy and the legal work.
