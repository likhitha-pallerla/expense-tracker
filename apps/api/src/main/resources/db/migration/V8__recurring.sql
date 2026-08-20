-- Recurring transactions: telling a suggestion apart from a decision.
--
-- The table already existed, but it could not express the one thing the
-- feature depends on: that the user has *rejected* a series. Without it, a
-- dismissed suggestion is indistinguishable from a subscription that has
-- simply not been saved yet, and so comes back every single time the list is
-- opened.
--
-- `is_active` is left alone and keeps its own meaning — the user pausing a
-- series they still want to keep. Rejecting something and pausing it are
-- different intentions and collapsing them would lose one of them.
create type recurring_state as enum ('confirmed', 'dismissed');

alter table recurring_transactions
  -- Stable identity for a series across detections. A suggestion has no row
  -- to point at, so confirming or dismissing one has to be addressed by the
  -- same key the detector groups charges under.
  --
  -- Derived from normalize_merchant_name, so any migration that changes that
  -- function must rewrite this column too — otherwise every dismissal silently
  -- comes back.
  add column match_key text,
  add column state recurring_state not null default 'confirmed',
  add column direction transaction_direction not null default 'debit',
  add column first_charged_at timestamptz,
  add column occurrences integer not null default 0,
  add column confidence numeric(4,3),
  add column notes text;

create unique index recurring_match_key_unique
  on recurring_transactions (user_id, match_key)
  where match_key is not null;

-- Dismissals are looked up on every list, including the ones is_active
-- excludes, so the existing partial index does not cover them.
create index recurring_state_idx on recurring_transactions (user_id, state);
