-- Notifications: the alert is derived, only the decision is stored.
--
-- The table as first drafted assumed alerts would be written by something —
-- a scheduler, a trigger — and then read back. That design cannot survive
-- contact with a ledger that changes underneath it. A row saying "Food is over
-- budget" outlives the transaction that caused it: delete the transaction and
-- the alert stays, insisting on a problem that no longer exists. The same
-- applies to a card whose bill has since been paid and a duplicate already
-- reviewed. It also needs a background job, which a free-tier instance that
-- sleeps cannot be relied upon to run.
--
-- So alerts are recomputed from live state on every read, exactly like budget
-- spend, card dues and recurring detection. What is stored here is the only
-- thing that cannot be derived: that the user has already seen an alert, or
-- has told us to stop showing it.
alter table notifications
  -- Stable identity for an alert across recomputations. It has to encode
  -- enough that dismissing one alert cannot silence a different one: the
  -- budget *and* its period *and* the threshold crossed, the card *and* the
  -- due date, the price change *and* the new amount. Dismissing March's 80%
  -- warning must not hide April's, nor the 100% breach that follows it.
  add column alert_key text,

  -- Dismissing implies having read it, so the two timestamps are ordered
  -- rather than exclusive. An enum would force a choice between them and lose
  -- the fact that a dismissed alert was also, at some point, seen.
  add column dismissed_at timestamptz;

-- Title and body are recomputed from live state on every read. They are kept
-- here only as a record of what the user was actually looking at when they
-- acted, which is the first thing worth knowing when a dismissal turns out to
-- be wrong. Nothing displays these: a snapshot shown as if it were current
-- would reintroduce the staleness this migration exists to remove.
alter table notifications alter column title drop not null;

create unique index notifications_alert_key_unique
  on notifications (user_id, alert_key)
  where alert_key is not null;

comment on column notifications.alert_key is
  'Stable identity of a derived alert. Changing how a key is built orphans '
  'every decision recorded against the old form, and dismissed alerts return.';
