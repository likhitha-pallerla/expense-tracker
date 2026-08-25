-- Trusted senders -------------------------------------------------------------
--
-- Mail is not authenticated. Anyone who knows a user's address can send them a
-- message reading "Rs 48,500 debited from a/c XX4412", and a parser that reads
-- any message containing an amount and the word "debited" will read that one
-- too -- writing an attacker's number into someone's financial history, with an
-- attacker's text sitting in a screen they trust, distorting every budget,
-- forecast and health score built on top of it.
--
-- The SMS path has always refused messages from an unrecognised sender. This is
-- the same gate for mail. An unrecognised sender does not have its message
-- thrown away: the message is quarantined, shown to the user, and released with
-- one click that also records the decision here so it need not be made twice.
--
-- Refusing a real bank costs a click. Accepting a forged alert costs a wrong
-- number that may never be noticed. Those are not comparable, so the default is
-- to refuse.

create table trusted_senders (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users(id) on delete cascade,

  -- Stored bare and lowercased ("hdfcbank.net"), never as a full address. A
  -- bank sends from many addresses at one domain, and asking a user to trust
  -- each of them separately would train them to click through the question.
  domain     text not null,

  -- What the user was looking at when they decided, so the list is reviewable
  -- later. A list of bare domains with no context cannot be audited by the
  -- person who made it.
  note       text,
  created_at timestamptz not null default now(),

  constraint trusted_senders_domain_unique unique (user_id, domain),
  constraint trusted_senders_domain_shape check (domain ~ '^[a-z0-9.-]+\.[a-z]{2,}$')
);

create index trusted_senders_user_idx on trusted_senders (user_id);

comment on table trusted_senders is
  'Domains a user has accepted as a source of payment alerts. Consumer mail '
  'providers are refused by the application before a row can be written here: '
  'trusting gmail.com would mean trusting every gmail user.';

-- Why a message was held back, so the user is asked a specific question rather
-- than shown a pile of unexplained mail.
alter table raw_messages
  add column if not exists quarantine_reason text;

comment on column raw_messages.quarantine_reason is
  'Set when status = quarantined. Explains what about the sender could not be '
  'established, in words shown directly to the user.';

create index raw_messages_quarantined_idx on raw_messages (user_id, received_at desc)
  where status = 'quarantined';

-- RLS -------------------------------------------------------------------------
-- The backend bypasses RLS and filters by user_id itself; these policies guard
-- the direct PostgREST path the web and mobile clients could otherwise take.

alter table trusted_senders enable row level security;
alter table trusted_senders force row level security;

create policy trusted_senders_select on trusted_senders
  for select using (user_id = auth.uid());

create policy trusted_senders_insert on trusted_senders
  for insert with check (user_id = auth.uid());

create policy trusted_senders_delete on trusted_senders
  for delete using (user_id = auth.uid());

-- Deliberately no update policy. Changing which domain a row refers to is
-- indistinguishable from trusting a new one, and should leave the same audit
-- trail: delete the old row, add a new one.
