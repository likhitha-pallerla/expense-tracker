-- Transactions: the heart of the system.
--
-- Design decisions worth remembering:
--  * amount is always POSITIVE; `direction` carries the sign. signed_amount is
--    a generated column so aggregation is a plain SUM.
--  * money is NUMERIC(14,2). Never float.
--  * a TRANSFER is two linked legs sharing transfer_id (debit from A, credit to
--    B). A single account_id column cannot express a transfer on its own.
--  * duplicates are never deleted. They join a transaction_group and carry
--    merged_into_id; aggregates count only rows where merged_into_id is null.
--  * deleted_at gives soft delete, so financial records stay recoverable.

create type duplicate_status as enum ('pending', 'merged', 'kept_both', 'dismissed');

-- transaction_groups --------------------------------------------------------
-- primary_transaction_id FK is added after `transactions` exists (circular).
create table transaction_groups (
  id                     uuid primary key default gen_random_uuid(),
  user_id                uuid not null references auth.users(id) on delete cascade,
  primary_transaction_id uuid,
  created_at             timestamptz not null default now()
);

create index transaction_groups_user_idx on transaction_groups (user_id);

-- transactions --------------------------------------------------------------
create table transactions (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  account_id    uuid references accounts(id) on delete set null,
  category_id   uuid references categories(id) on delete set null,
  merchant_id   uuid references merchants(id) on delete set null,

  kind          transaction_kind not null default 'expense',
  direction     transaction_direction not null default 'debit',

  amount        numeric(14,2) not null,
  currency      char(3) not null default 'INR',
  base_amount   numeric(14,2) not null,
  fx_rate       numeric(18,8) not null default 1,
  signed_amount numeric(14,2)
    generated always as (case when direction = 'debit' then -amount else amount end) stored,

  occurred_at   timestamptz not null,
  description   text,
  notes         text,
  tags          text[] not null default '{}',

  -- transfers: both legs share transfer_id
  transfer_id   uuid,

  -- dedup
  external_ref         text,
  transaction_group_id uuid references transaction_groups(id) on delete set null,
  merged_into_id       uuid references transactions(id) on delete set null,
  refund_of_id         uuid references transactions(id) on delete set null,

  -- provenance: "where did this expense come from?"
  source_id       uuid references source_connections(id) on delete set null,
  raw_message_id  uuid references raw_messages(id) on delete set null,
  import_batch_id uuid references import_batches(id) on delete set null,
  confidence      numeric(4,3),

  is_excluded  boolean not null default false,
  is_recurring boolean not null default false,

  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,

  constraint transactions_amount_positive check (amount >= 0),
  constraint transactions_base_amount_positive check (base_amount >= 0),
  constraint transactions_transfer_has_id check (kind <> 'transfer' or transfer_id is not null),
  constraint transactions_not_self_merged check (id <> merged_into_id)
);

-- L1 dedup: a bank reference (RRN / UTR / auth code) is unique per user.
create unique index transactions_external_ref_unique
  on transactions (user_id, external_ref)
  where external_ref is not null and deleted_at is null;

-- Primary listing: live, unmerged rows newest first.
create index transactions_user_occurred_idx
  on transactions (user_id, occurred_at desc)
  where deleted_at is null and merged_into_id is null;

-- L2 dedup candidate lookup: narrow by user + amount + time before scoring.
create index transactions_dedup_idx
  on transactions (user_id, amount, occurred_at)
  where deleted_at is null and merged_into_id is null;

create index transactions_account_idx  on transactions (account_id)  where deleted_at is null;
create index transactions_category_idx on transactions (category_id) where deleted_at is null;
create index transactions_merchant_idx on transactions (merchant_id) where deleted_at is null;
create index transactions_transfer_idx on transactions (transfer_id) where transfer_id is not null;
create index transactions_group_idx    on transactions (transaction_group_id)
  where transaction_group_id is not null;
create index transactions_raw_message_idx on transactions (raw_message_id)
  where raw_message_id is not null;

create trigger transactions_updated_at before update on transactions
  for each row execute function set_updated_at();

alter table transaction_groups
  add constraint transaction_groups_primary_fk
  foreign key (primary_transaction_id) references transactions(id) on delete set null;

-- duplicate_candidates ------------------------------------------------------
-- Pairs scoring between the auto-merge and distinct thresholds land here for
-- the user to review. `signals` records which rules matched, so the UI can
-- explain *why* two rows look like duplicates.
create table duplicate_candidates (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references auth.users(id) on delete cascade,
  transaction_a  uuid not null references transactions(id) on delete cascade,
  transaction_b  uuid not null references transactions(id) on delete cascade,
  score          numeric(4,3) not null,
  signals        jsonb not null default '{}',
  status         duplicate_status not null default 'pending',
  resolved_at    timestamptz,
  created_at     timestamptz not null default now(),
  constraint duplicate_candidates_distinct check (transaction_a <> transaction_b),
  constraint duplicate_candidates_pair_unique unique (transaction_a, transaction_b)
);

create index duplicate_candidates_pending_idx
  on duplicate_candidates (user_id, score desc)
  where status = 'pending';

-- Derived account balance ---------------------------------------------------
-- Balances are computed, not mutated per transaction, so they cannot drift
-- under concurrent writes, edits or deletes.
create or replace function account_balance(p_account_id uuid)
returns numeric(14,2)
language sql stable as $$
  select a.opening_balance + coalesce(sum(t.signed_amount), 0)
  from accounts a
  left join transactions t
    on t.account_id = a.id
   and t.deleted_at is null
   and t.merged_into_id is null
  where a.id = p_account_id
  group by a.opening_balance;
$$;
