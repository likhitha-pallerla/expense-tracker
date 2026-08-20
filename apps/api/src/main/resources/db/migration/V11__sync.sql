-- Mail sync: bringing provider messages into raw_messages, incrementally.
--
-- There is no scheduler here on purpose. The API runs on a free instance that
-- sleeps when idle, so a cron would simply not fire; anything that must happen
-- reliably has to happen while a user is present. Sync is therefore something
-- the user (or the app on their behalf, on page load) asks for, and every run
-- is recorded so the answer to "did it work?" is not a guess.

-- sync_runs -----------------------------------------------------------------
-- One row per attempt, successful or not. Failures are the interesting ones:
-- a token that expired, a cursor the provider forgot, a provider having a bad
-- day. Without a record, a mailbox that quietly stopped importing looks
-- identical to a mailbox with nothing new in it.
create table sync_runs (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references auth.users(id) on delete cascade,
  connection_id  uuid not null references source_connections(id) on delete cascade,
  started_at     timestamptz not null default now(),
  finished_at    timestamptz,
  -- running | ok | failed. Plain text rather than an enum: this is diagnostic
  -- data whose vocabulary will change more often than the schema should.
  status         text not null default 'running',
  -- fetched = seen at the provider; stored = new to us; skipped = already had.
  -- The gap between fetched and stored is the dedup layer earning its keep.
  fetched_count  integer not null default 0,
  stored_count   integer not null default 0,
  skipped_count  integer not null default 0,
  -- True when the provider still had more waiting than one run would take.
  has_more       boolean not null default false,
  error          text,
  created_at     timestamptz not null default now()
);

create index sync_runs_connection_idx on sync_runs (connection_id, started_at desc);
create index sync_runs_user_idx on sync_runs (user_id, started_at desc);

alter table sync_runs enable row level security;
alter table sync_runs force row level security;

-- Select only, deliberately breaking the four-policy pattern used elsewhere.
-- A sync run is a record of something the server did; a client that could
-- write one could invent a successful import that never happened, or hide a
-- failure. Reading its own history is all any client legitimately needs.
create policy sync_runs_select on sync_runs
  for select using (user_id = auth.uid());

-- source_connections --------------------------------------------------------
-- How far back the first pass should reach, and whether it got there.
--
-- Backfill is separate from the incremental cursor because they answer
-- different questions: the cursor says "what is new since last time", the
-- backfill marker says "have we ever finished looking at the old mail". A
-- connection can be up to date going forward while still working backwards.
alter table source_connections
  add column if not exists backfill_from  timestamptz,
  add column if not exists backfilled_at  timestamptz,
  add column if not exists last_sync_run_id uuid references sync_runs(id) on delete set null;

comment on column source_connections.sync_cursor is
  'Provider-specific resume point. Gmail: a historyId. Outlook: a full delta '
  'link URL. Opaque to us by design -- providers change their pagination and '
  'we should not need a migration when they do.';

-- raw_messages --------------------------------------------------------------
-- Sync reads "what have I already got for this connection, most recent first"
-- constantly; without this it is a sequential scan that grows with the mailbox.
create index if not exists raw_messages_connection_received_idx
  on raw_messages (connection_id, received_at desc);
