# Feature Catalogue

Merged from the original plan and the *Public Expense Tracker Blueprint*.
See `BLUEPRINT-REVIEW.md` for what changed and why.

Legend: **V1** = first web release · **V1.5** = depth · **V2** = automatic capture ·
**V3** = mobile / AI / long-term

Organised under the three product pillars: **TRACK** (§1–8), **UNDERSTAND** (§11–12),
**PLAN** (§9, §9b–9d).

---

## 1. Accounts & Onboarding

| Feature | Phase |
|---|---|
| Sign up / sign in with email + password | V1 |
| Sign in with Google | V1 |
| Sign in with Microsoft | V1 |
| Landing / marketing page | V1 |
| Password reset, email verification | V1 |
| Profile: display name, base currency, timezone, locale | V1 |
| Onboarding wizard: currency → connect sources → pick categories | V1 |
| Session management, sign out everywhere | V2 |
| Biometric / PIN app lock | V3 (mobile) |

## 2. Source Connections — where expenses come from

| Feature | Phase |
|---|---|
| Manual expense entry | V1 |
| CSV / bank statement import with column mapping | V1 |
| Connect Gmail (read-only) | V2 |
| Connect Outlook / Microsoft 365 (read-only) | V2 |
| Multiple mailboxes per user | V2 |
| Android SMS capture (bank + UPI alerts) | V3 |
| Connection health: last sync time, error state, re-auth prompt | V2 |
| Disconnect a source and purge its data | V2 |
| PDF statement import (password-protected bank PDFs) | V3 |
| Email forwarding address (`you@in.app`) as a no-OAuth fallback | V2 |

## 3. Parsing & Extraction

| Feature | Phase |
|---|---|
| Sender allowlist + keyword pre-filter (drop non-financial mail early) | V2 |
| Rule engine with per-bank/issuer templates, stored in DB (no redeploy to add a bank) | V2 |
| Extract amount, currency, direction, merchant, account/card last4, reference no., timestamp, running balance | V2 |
| Indian format handling: `₹`, `Rs.`, `INR`, lakh/crore, `DD-MM-YY` | V2 |
| Quarantine queue for unrecognised messages | V2 |
| "Teach the parser" — highlight fields in a sample to author a new template | V3 |
| Template versioning + reparse of historical messages | V3 |
| LLM fallback parser with schema-constrained output and confidence gating | V3 |
| Receipt OCR from photo / attachment | V3 |

## 4. Duplicate Handling — the core feature

| Feature | Phase |
|---|---|
| L0 exact dedup: unique provider message id + body hash | V1 |
| L1 deterministic match on bank reference (RRN / UTR / auth code / order id) | V1 |
| L2 probabilistic scoring: amount + instrument + time window + merchant similarity | V1 |
| L3 decision: auto-merge ≥ 0.90, review queue 0.60–0.90, distinct below | V1 |
| Non-destructive merge — duplicates are grouped, never deleted | V1 |
| One-click un-merge, fully reversible | V1 |
| Duplicate review queue showing *which signals matched* | V1 |
| Cross-source dedup: same spend seen in SMS *and* email *and* statement | V2 |
| Pre-authorisation → final settlement supersede (fuel, hotels) | V2 |
| Refund / reversal linking, netted in reports rather than hidden | V2 |
| Threshold tuning learned from your merge/keep decisions | V3 |

## 5. Transactions

| Feature | Phase |
|---|---|
| **Income transactions** (salary, refunds, interest) | V1 |
| List with filter (date, category, amount, merchant, account, source) | V1 |
| Full-text search, sort, pagination | V1 |
| Inline edit of any field; parser overrides are remembered | V1 |
| **Provenance** — open a transaction, see the exact message it came from | V1 |
| Notes and tags | V1 |
| Bulk categorise / bulk edit / bulk delete | V1 |
| Split one transaction across several categories | V2 |
| Attach a receipt image | V2 |
| Mark reimbursable · business vs personal | V2 |
| Exclude from reports (e.g. self-transfers) | V1 |
| **Transfers between own accounts**, stored as two linked legs and excluded from spend | V1 |
| **Transfer auto-detection** from ingested messages | V2 |
| Cash transactions and cash wallet | V1 |

## 6. Categorisation

| Feature | Phase |
|---|---|
| System category tree (Food & Dining → Restaurants / Groceries / Delivery …) | V1 |
| Custom categories with icon and colour | V1 |
| Merchant → category mapping | V1 |
| User rules: "if merchant contains X → category Y" | V2 |
| Learns from your corrections | V3 |
| Uncategorised queue with bulk assign | V1 |

**Seed categories:** Food (restaurants, groceries, delivery) · Transport (fuel, cab,
metro) · Housing (rent, maintenance) · Bills (electricity, internet, mobile) ·
Shopping (clothes, cosmetics) · Health (medicine, doctor) · Travel (flights, hotels) ·
Entertainment (movies, subscriptions) · Family (parents, gifts) · Education (courses,
books) · Investments (mutual funds, stocks) · EMI / Loans · Miscellaneous.

## 7. Merchants

| Feature | Phase |
|---|---|
| Canonical merchant with alias list (`SWIGGY*ORDER`, `Swiggy Ltd` → **Swiggy**) | V1 |
| Merge / rename merchants | V2 |
| Per-merchant spend history and frequency | V2 |
| Merchant logos | V3 |

## 8. Accounts, Cards & Instruments

| Feature | Phase |
|---|---|
| Account types: bank, cash, UPI, wallet, credit card, custom | V1 |
| Nicknames and last4 ("HDFC Credit ⋯4521") | V1 |
| **Derived balances** (opening balance + signed sum, reconciled on a schedule) | V1 |
| Per-account spend breakdown | V1 |
| **Credit card module**: limit, available limit, outstanding, billing date, due date, minimum due | V1.5 |
| Credit-card due-date reminders | V1.5 |
| Payment history per card | V1.5 |
| Rewards / cashback tracking | V3 |
| Balance cross-check against balances parsed from SMS | V2 |

## 9. Budgets

| Feature | Phase |
|---|---|
| Monthly overall budget | V1.5 |
| Per-category budgets, user-configurable (never hard-coded) | V1.5 |
| Progress bars + projected month-end overspend | V1.5 |
| Alerts at 50 / 80 / 100 % | V1.5 |
| Budget vs actual reporting | V1.5 |
| Rollover of unused budget | V3 |

## 9b. Goals

| Feature | Phase |
|---|---|
| Create a goal: name, target amount, current amount, target date | V3 |
| Suggested monthly contribution, computed deterministically | V3 |
| Progress tracking and projection ("on track / behind") | V3 |
| Link a goal to an account | V3 |

## 9c. Cash-flow forecasting

| Feature | Phase |
|---|---|
| Month-end cash estimate: balance + expected income − recurring − planned investments − expected discretionary | V3 |
| Forecast refined from historical spending patterns | V3 |
| "Safe to spend" figure for the rest of the month | V3 |

## 9d. Financial Health Score

| Feature | Phase |
|---|---|
| 0–100 score from savings rate, debt ratio, emergency-fund cover, budget adherence, investment rate, spending consistency | V1.5 |
| **Driver breakdown — never a black box**; each component shown with its rating | V1.5 |
| Score trend over time | V3 |

## 9e. Net worth & investments

*Deferred deliberately — only build if usage evidence supports it.*

| Feature | Phase |
|---|---|
| Assets: bank balances, mutual funds, stocks/ETFs, gold, fixed deposits | V3 |
| Liabilities: credit cards, personal / car / home loans | V3 |
| Net worth = assets − liabilities, tracked over time | V3 |
| Investment tracking: invested, current value, P&L, return %, date | V3 |

## 10. Recurring & Subscriptions

| Feature | Phase |
|---|---|
| Recurring transaction flag on any transaction | V1 |
| Auto-detect recurring charges (Netflix, rent, EMI, SIP) | V1.5 |
| Dedicated subscriptions view with monthly **and annualised** cost | V1.5 |
| Renewal dates and cancellation reminders | V1.5 |
| Upcoming charges calendar | V1.5 |
| Price-increase detection ("Spotify went up ₹30") | V3 |
| Unused-subscription nudges | V3 |

## 11. Dashboard & Insights

| Feature | Phase |
|---|---|
| **Monthly summary: income, expenses, investments, savings, savings rate** | V1 |
| This month vs last month | V1 |
| Category breakdown (donut) | V1 |
| Spend trend over time (daily / weekly / monthly / yearly) | V1 |
| Top spending categories and merchants | V1 |
| Merchant-level spending detail | V1 |
| Custom date ranges | V1 |
| Biggest expenses of the period | V1 |
| Budget vs actual | V1.5 |
| Daily burn rate + projected month-end total | V1.5 |
| Smart insights in words ("Food spending increased 18.6 % versus last month") | V1.5 |
| Anomaly flags ("Shopping is 47 % above your 3-month average") | V3 |
| Natural-language questions ("how much on travel last quarter?") | V3 |

## 12. Reports & Export

| Feature | Phase |
|---|---|
| Export CSV | V1 |
| Export Excel | V2 |
| Monthly / annual statement PDF | V3 |
| Reimbursable-expenses export for claims | V2 |

## 13. Notifications

| Feature | Phase |
|---|---|
| In-app: new expense detected, duplicate needs review | V2 |
| Budget threshold email | V2 |
| Weekly / monthly summary email | V2 |
| Mobile push | V3 |

## 14. Multi-currency

| Feature | Phase |
|---|---|
| Base currency per user | V1 |
| FX conversion at the transaction date, original amount preserved | V2 |
| Travel mode — group foreign spend by trip | V3 |

## 15. Privacy & Trust

| Feature | Phase |
|---|---|
| Read-only mail scopes; no bank credentials, ever | V2 |
| Retention control for raw message bodies (default 30 days) | V2 |
| Account numbers stored as last4 only | V1 |
| Export all my data | V1 |
| Delete account and all data | V1 |
| Audit log — "why did this expense appear, and what changed it?" | V1 |

## 15b. AI layer

**Standing rule: AI explains, the backend computes.** Every figure the user relies on
is produced by deterministic, tested Java. AI only classifies uncertain data,
summarises, and provides a conversational surface.

| Feature | Phase |
|---|---|
| Deterministic merchant rules first, AI only for uncertain transactions (cost control + predictability) | V2 |
| LLM fallback parser for unknown bank templates, schema-constrained + confidence-gated | V3 |
| Natural-language entry: "Spent 850 on dinner at Zomato using HDFC card" | V3 |
| Receipt OCR with a review screen before saving | V3 |
| Financial assistant over deterministic figures ("why did spending increase?") | V3 |

## 15c. Monetization

Free while validating usage. If monetised, price **automation and AI** — never basic
record-keeping behind a paywall.

| Free | Pro (V3+) |
|---|---|
| Manual transactions, CSV import | Automatic email + SMS capture |
| Budgets, goals | Receipt scanning, AI insights |
| Basic analytics and reports | Advanced analytics, cash-flow prediction |
| Core accounts | Unlimited accounts, net worth reports |

## 16. Mobile-specific (V3)

Android SMS auto-capture · fast "add expense" flow · glanceable dashboard · quick-add
widget · share-sheet capture · camera receipt scan · offline queue with idempotent
sync · push notifications · biometric lock.
iOS has no SMS access, so it uses email + manual + share sheet.

## 17. Operator tooling (for you)

| Feature | Phase |
|---|---|
| Parser template management UI | V2 |
| Quarantine review of unparsed messages | V2 |
| Metrics: parse success rate, dedup rate, auto-merge precision | V2 |

## Deliberately out of scope

Bank screen-scraping or credential storage · initiating payments · iOS SMS reading ·
credit-score features · bill splitting with friends (a different product) ·
tax filing or financial advice.

*Net worth and investment tracking are deferred to V3, not excluded — build them only
if real usage justifies the effort.*
