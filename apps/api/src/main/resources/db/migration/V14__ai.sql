-- The AI layer: a spend cap, and a record of what the model touched.
--
-- Two things have to be true before a language model is allowed anywhere near
-- this system, and both of them are storage problems rather than code
-- problems, which is why they are here.
--
-- First, it must be impossible for the AI to cost money quietly. This is a
-- free-tier product with a personal API key behind it; a retry loop, a stuck
-- queue or one large backfill could otherwise turn into a bill nobody agreed
-- to. `ai_usage` is a per-user, per-day counter that the service checks before
-- every call and increments after it. A cap that lives in memory would reset
-- on every deploy and on every free-instance cold start, which on Render is
-- several times a day -- so it lives in the database.
--
-- Second, it must always be possible to tell which transactions a model had a
-- hand in. Deterministic rules and an LLM are not equally trustworthy, and a
-- user reviewing their history deserves to know which is which. `parsed_by`
-- and `ai_confidence` make that a property of the row rather than something
-- inferred from a log line that has long since rotated away.

create table ai_usage (
  user_id    uuid not null references auth.users(id) on delete cascade,
  day        date not null default current_date,
  calls      integer not null default 0,
  -- Token counts are recorded but nothing is gated on them. Providers report
  -- them inconsistently and some not at all, so a limit built on them would
  -- fail open on exactly the providers we cannot verify. Calls are countable
  -- everywhere, so calls are what the cap uses.
  tokens_in  bigint not null default 0,
  tokens_out bigint not null default 0,
  updated_at timestamptz not null default now(),
  primary key (user_id, day),
  constraint ai_usage_calls_sane check (calls >= 0)
);

comment on table ai_usage is
  'Per-user daily AI call counter. Read before every model call to enforce the '
  'hard cap in app.ai.daily-call-budget; a cap held in memory would reset on '
  'every cold start.';

-- How a message came to be read ---------------------------------------------

alter table raw_messages
  add column if not exists parsed_by     text,
  add column if not exists ai_confidence numeric(3,2);

alter table raw_messages
  add constraint raw_messages_parsed_by_known
  check (parsed_by is null or parsed_by in ('rule', 'ai'));

alter table raw_messages
  add constraint raw_messages_confidence_range
  check (ai_confidence is null or (ai_confidence >= 0 and ai_confidence <= 1));

comment on column raw_messages.parsed_by is
  'Which reader produced the transaction: a deterministic parser_rule, or the '
  'LLM fallback. Never inferred -- an AI-read transaction stays labelled as one '
  'for as long as it exists.';

comment on column raw_messages.ai_confidence is
  'What the model claimed about its own answer, kept even when the answer was '
  'accepted, so a bad threshold can be found afterwards rather than guessed at.';

-- Existing rows were all read by rules; saying so is more useful than null.
update raw_messages set parsed_by = 'rule'
 where parsed_by is null and status = 'parsed';

create index raw_messages_ai_idx on raw_messages (user_id, parsed_by)
  where parsed_by = 'ai';

-- RLS ------------------------------------------------------------------------
-- The backend bypasses RLS and filters by user_id itself; these policies guard
-- the direct PostgREST path the web and mobile clients could otherwise take.

alter table ai_usage enable row level security;
alter table ai_usage force row level security;

create policy ai_usage_select on ai_usage
  for select using (user_id = auth.uid());

-- Deliberately no insert, update or delete policy for clients. A counter that
-- exists to limit spending must not be writable by the thing being limited.
