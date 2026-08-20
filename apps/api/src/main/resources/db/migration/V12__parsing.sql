-- Turning stored payment alerts into transactions.
--
-- The rules live in the database rather than in Java on purpose. Bank alert
-- formats change without warning and differ per issuer; when HDFC reformats
-- its UPI mail, the fix should be a row, not a redeploy. It also means a user
-- can eventually add a rule for a bank we have never seen.
--
-- Rules are matched most-specific-first via `priority` (lower wins). A user's
-- own rule always beats a built-in one, because they know their bank better
-- than we do.

-- One transaction per message, enforced ---------------------------------------
-- Parsing is re-runnable by design (a rule improves, a user retries a failure),
-- so nothing may depend on it running exactly once. This index is what makes a
-- second pass safe: the message can be re-read, but it cannot produce a second
-- transaction.
create unique index transactions_raw_message_unique
  on transactions (raw_message_id)
  where raw_message_id is not null and deleted_at is null;

-- Why a message could not be read --------------------------------------------
-- `parse_error` already exists but says nothing about which rule was tried.
-- Without that, "it failed" is untraceable: we cannot tell a message no rule
-- matched from one where the right rule matched and the amount was missing.
alter table raw_messages
  add column if not exists parsed_at   timestamptz,
  add column if not exists parse_notes text;

comment on column raw_messages.parse_notes is
  'Human-readable trace of the parse attempt: which rule matched and what it '
  'could not find. Shown to the user, so it must never contain mail content '
  'beyond the fragment that failed.';

-- Built-in rules --------------------------------------------------------------
-- user_id is null for a built-in. The extractors are a map of field name to
-- { pattern, group, as }, where `as` says how to read the captured text:
-- amount | direction | date | text.
--
-- Patterns are deliberately bounded ({2,60} rather than +) because they are
-- applied to attacker-influenced text — anyone can send you mail. An unbounded
-- alternation here is a denial of service waiting to happen.

insert into parser_rules (user_id, name, issuer, provider, sender_pattern, match_pattern, extractors, priority) values

-- UPI is the most specific and the most common, so it is tried first. The VPA
-- is the merchant: 'swiggy@icici' is more reliable than any display name.
(null, 'UPI debit', null, null, null,
 '(?i)\b(?:debited|paid|sent|transferred)\b[\s\S]{0,200}?\b(?:vpa|upi)\b',
 '{
   "amount":     {"pattern": "(?i)(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]{0,15}(?:\\.[0-9]{1,2})?)", "group": 1, "as": "amount"},
   "direction":  {"pattern": "(?i)\\b(debited|paid|sent|transferred)\\b", "group": 1, "as": "direction"},
   "merchant":   {"pattern": "(?i)(?:to\\s+(?:vpa\\s+)?|vpa\\s+)([A-Za-z0-9][A-Za-z0-9._-]{1,40}@[A-Za-z]{2,20})", "group": 1, "as": "text"},
   "last4":      {"pattern": "(?i)(?:a/c|acct|account|card)\\s*(?:no\\.?\\s*)?[Xx*]{0,12}\\s*([0-9]{4})\\b", "group": 1, "as": "text"},
   "occurredAt": {"pattern": "(?i)\\bon\\s+([0-9]{1,2}[-/][0-9]{1,2}[-/][0-9]{2,4}|[0-9]{1,2}[-\\s][A-Za-z]{3}[-\\s][0-9]{2,4}|[0-9]{4}-[0-9]{2}-[0-9]{2})", "group": 1, "as": "date"},
   "reference":  {"pattern": "(?i)\\b(?:upi|ref|rrn|utr|txn)\\s*(?:no\\.?|id|ref(?:erence)?)?\\s*[:# ]\\s*([A-Za-z0-9]{6,25})\\b", "group": 1, "as": "text"}
 }'::jsonb, 10),

(null, 'UPI credit', null, null, null,
 '(?i)\b(?:credited|received)\b[\s\S]{0,200}?\b(?:vpa|upi)\b',
 '{
   "amount":     {"pattern": "(?i)(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]{0,15}(?:\\.[0-9]{1,2})?)", "group": 1, "as": "amount"},
   "direction":  {"pattern": "(?i)\\b(credited|received)\\b", "group": 1, "as": "direction"},
   "merchant":   {"pattern": "(?i)(?:from\\s+(?:vpa\\s+)?|vpa\\s+)([A-Za-z0-9][A-Za-z0-9._-]{1,40}@[A-Za-z]{2,20})", "group": 1, "as": "text"},
   "last4":      {"pattern": "(?i)(?:a/c|acct|account)\\s*(?:no\\.?\\s*)?[Xx*]{0,12}\\s*([0-9]{4})\\b", "group": 1, "as": "text"},
   "occurredAt": {"pattern": "(?i)\\bon\\s+([0-9]{1,2}[-/][0-9]{1,2}[-/][0-9]{2,4}|[0-9]{1,2}[-\\s][A-Za-z]{3}[-\\s][0-9]{2,4}|[0-9]{4}-[0-9]{2}-[0-9]{2})", "group": 1, "as": "date"},
   "reference":  {"pattern": "(?i)\\b(?:upi|ref|rrn|utr|txn)\\s*(?:no\\.?|id|ref(?:erence)?)?\\s*[:# ]\\s*([A-Za-z0-9]{6,25})\\b", "group": 1, "as": "text"}
 }'::jsonb, 11),

-- Cards name the merchant in words rather than a VPA, and say "spent"/"used".
-- Both word orders are accepted because both are common: "Card x1234 was used
-- at..." and "Rs 500 spent on your Card x1234 at...". Matching only one of them
-- silently drops half the card alerts in the country.
(null, 'Card spend', null, null, null,
 '(?i)(?:\b(?:spent|used|purchase)\b[\s\S]{0,200}?\bcard\b|\bcard\b[\s\S]{0,200}?\b(?:spent|used|debited)\b)',
 '{
   "amount":     {"pattern": "(?i)(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]{0,15}(?:\\.[0-9]{1,2})?)", "group": 1, "as": "amount"},
   "direction":  {"pattern": "(?i)\\b(spent|used|debited)\\b", "group": 1, "as": "direction"},
   "merchant":   {"pattern": "(?i)\\bat\\s+([A-Za-z0-9][A-Za-z0-9 &@._''*-]{1,58}?)(?=\\s+on\\b|\\s*[.,;]|\\s*$)", "group": 1, "as": "text"},
   "last4":      {"pattern": "(?i)card\\s*(?:no\\.?\\s*)?(?:ending\\s*(?:in|with)?\\s*)?[Xx*]{0,12}\\s*([0-9]{4})\\b", "group": 1, "as": "text"},
   "occurredAt": {"pattern": "(?i)\\bon\\s+([0-9]{1,2}[-/][0-9]{1,2}[-/][0-9]{2,4}|[0-9]{1,2}[-\\s][A-Za-z]{3}[-\\s][0-9]{2,4}|[0-9]{4}-[0-9]{2}-[0-9]{2})", "group": 1, "as": "date"},
   "reference":  {"pattern": "(?i)\\b(?:ref|rrn|auth|approval|txn)\\s*(?:no\\.?|code|id)?\\s*[:# ]\\s*([A-Za-z0-9]{6,25})\\b", "group": 1, "as": "text"}
 }'::jsonb, 20),

-- Cash out of an ATM is an expense with no merchant worth recording.
(null, 'ATM withdrawal', null, null, null,
 '(?i)\b(?:atm|cash\s+withdraw(?:al|n))\b[\s\S]{0,200}?\b(?:withdraw(?:n|al)?|debited)\b',
 '{
   "amount":     {"pattern": "(?i)(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]{0,15}(?:\\.[0-9]{1,2})?)", "group": 1, "as": "amount"},
   "direction":  {"pattern": "(?i)\\b(withdrawn|withdrawal|debited)\\b", "group": 1, "as": "direction"},
   "last4":      {"pattern": "(?i)(?:a/c|acct|account|card)\\s*(?:no\\.?\\s*)?[Xx*]{0,12}\\s*([0-9]{4})\\b", "group": 1, "as": "text"},
   "occurredAt": {"pattern": "(?i)\\bon\\s+([0-9]{1,2}[-/][0-9]{1,2}[-/][0-9]{2,4}|[0-9]{1,2}[-\\s][A-Za-z]{3}[-\\s][0-9]{2,4}|[0-9]{4}-[0-9]{2}-[0-9]{2})", "group": 1, "as": "date"},
   "reference":  {"pattern": "(?i)\\b(?:ref|rrn|txn)\\s*(?:no\\.?|id)?\\s*[:# ]\\s*([A-Za-z0-9]{6,25})\\b", "group": 1, "as": "text"}
 }'::jsonb, 30),

-- The catch-alls. These run last and are why an unrecognised bank still
-- produces something rather than nothing.
(null, 'Account debit', null, null, null,
 '(?i)\b(?:debited|withdrawn|deducted)\b',
 '{
   "amount":     {"pattern": "(?i)(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]{0,15}(?:\\.[0-9]{1,2})?)", "group": 1, "as": "amount"},
   "direction":  {"pattern": "(?i)\\b(debited|withdrawn|deducted)\\b", "group": 1, "as": "direction"},
   "merchant":   {"pattern": "(?i)\\b(?:to|towards|at|favou?ring)\\s+([A-Za-z0-9][A-Za-z0-9 &@._''*-]{1,58}?)(?=\\s+on\\b|\\s*[.,;]|\\s*$)", "group": 1, "as": "text"},
   "last4":      {"pattern": "(?i)(?:a/c|acct|account|card)\\s*(?:no\\.?\\s*)?[Xx*]{0,12}\\s*([0-9]{4})\\b", "group": 1, "as": "text"},
   "occurredAt": {"pattern": "(?i)\\bon\\s+([0-9]{1,2}[-/][0-9]{1,2}[-/][0-9]{2,4}|[0-9]{1,2}[-\\s][A-Za-z]{3}[-\\s][0-9]{2,4}|[0-9]{4}-[0-9]{2}-[0-9]{2})", "group": 1, "as": "date"},
   "reference":  {"pattern": "(?i)\\b(?:ref|rrn|utr|txn|transaction)\\s*(?:no\\.?|id|ref(?:erence)?)?\\s*[:# ]\\s*([A-Za-z0-9]{6,25})\\b", "group": 1, "as": "text"}
 }'::jsonb, 90),

(null, 'Account credit', null, null, null,
 '(?i)\b(?:credited|received|deposited)\b',
 '{
   "amount":     {"pattern": "(?i)(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]{0,15}(?:\\.[0-9]{1,2})?)", "group": 1, "as": "amount"},
   "direction":  {"pattern": "(?i)\\b(credited|received|deposited)\\b", "group": 1, "as": "direction"},
   "merchant":   {"pattern": "(?i)\\b(?:from|by)\\s+([A-Za-z0-9][A-Za-z0-9 &@._''*-]{1,58}?)(?=\\s+on\\b|\\s*[.,;]|\\s*$)", "group": 1, "as": "text"},
   "last4":      {"pattern": "(?i)(?:a/c|acct|account)\\s*(?:no\\.?\\s*)?[Xx*]{0,12}\\s*([0-9]{4})\\b", "group": 1, "as": "text"},
   "occurredAt": {"pattern": "(?i)\\bon\\s+([0-9]{1,2}[-/][0-9]{1,2}[-/][0-9]{2,4}|[0-9]{1,2}[-\\s][A-Za-z]{3}[-\\s][0-9]{2,4}|[0-9]{4}-[0-9]{2}-[0-9]{2})", "group": 1, "as": "date"},
   "reference":  {"pattern": "(?i)\\b(?:ref|rrn|utr|txn|transaction)\\s*(?:no\\.?|id|ref(?:erence)?)?\\s*[:# ]\\s*([A-Za-z0-9]{6,25})\\b", "group": 1, "as": "text"}
 }'::jsonb, 91);
