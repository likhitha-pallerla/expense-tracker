-- Planning: budgets, goals, recurring transactions. Plus the audit log.

create type budget_period as enum ('monthly', 'weekly', 'yearly');
create type goal_status   as enum ('active', 'achieved', 'paused', 'cancelled');

-- budgets -------------------------------------------------------------------
-- category_id null = overall budget for the period.
create table budgets (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references auth.users(id) on delete cascade,
  category_id  uuid references categories(id) on delete cascade,
  name         text,
  amount       numeric(14,2) not null,
  currency     char(3) not null default 'INR',
  period       budget_period not null default 'monthly',
  starts_on    date not null default current_date,
  ends_on      date,
  rollover     boolean not null default false,
  alert_thresholds smallint[] not null default '{50,80,100}',
  is_active    boolean not null default true,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  constraint budgets_amount_positive check (amount > 0),
  constraint budgets_period_unique unique (user_id, category_id, period, starts_on)
);

create index budgets_user_idx on budgets (user_id) where is_active = true;

create trigger budgets_updated_at before update on budgets
  for each row execute function set_updated_at();

-- goals ---------------------------------------------------------------------
create table goals (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references auth.users(id) on delete cascade,
  name           text not null,
  target_amount  numeric(14,2) not null,
  current_amount numeric(14,2) not null default 0,
  currency       char(3) not null default 'INR',
  target_date    date,
  account_id     uuid references accounts(id) on delete set null,
  status         goal_status not null default 'active',
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  constraint goals_target_positive check (target_amount > 0)
);

create index goals_user_idx on goals (user_id, status);

create trigger goals_updated_at before update on goals
  for each row execute function set_updated_at();

-- recurring_transactions ----------------------------------------------------
-- Detected subscriptions and scheduled charges.
create table recurring_transactions (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references auth.users(id) on delete cascade,
  merchant_id    uuid references merchants(id) on delete set null,
  category_id    uuid references categories(id) on delete set null,
  account_id     uuid references accounts(id) on delete set null,
  name           text not null,
  amount         numeric(14,2) not null,
  currency       char(3) not null default 'INR',
  cadence_days   integer not null,
  last_charged_at timestamptz,
  next_expected_at timestamptz,
  is_subscription boolean not null default false,
  is_active      boolean not null default true,
  detected       boolean not null default false,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  constraint recurring_cadence_positive check (cadence_days > 0)
);

create index recurring_user_idx on recurring_transactions (user_id) where is_active = true;
create index recurring_next_idx on recurring_transactions (next_expected_at)
  where is_active = true;

create trigger recurring_updated_at before update on recurring_transactions
  for each row execute function set_updated_at();

-- notifications -------------------------------------------------------------
create table notifications (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users(id) on delete cascade,
  type       text not null,
  title      text not null,
  body       text,
  payload    jsonb not null default '{}',
  read_at    timestamptz,
  created_at timestamptz not null default now()
);

create index notifications_unread_idx on notifications (user_id, created_at desc)
  where read_at is null;

-- audit_log -----------------------------------------------------------------
-- Answers "why did this expense appear, and what changed it?".
create table audit_log (
  id          bigserial primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,
  entity_type text not null,
  entity_id   uuid,
  action      text not null,
  actor       text not null default 'system',
  details     jsonb not null default '{}',
  created_at  timestamptz not null default now()
);

create index audit_log_entity_idx on audit_log (entity_type, entity_id, created_at desc);
create index audit_log_user_idx   on audit_log (user_id, created_at desc);
