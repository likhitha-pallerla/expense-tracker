-- Core reference data: profiles, categories, merchants, accounts.
-- Money is NUMERIC(14,2) everywhere. Never floating point.

create extension if not exists pg_trgm;
create extension if not exists pgcrypto;

-- Shared updated_at trigger ------------------------------------------------
create or replace function set_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

-- Enums --------------------------------------------------------------------
create type account_type as enum
  ('bank', 'cash', 'upi', 'wallet', 'credit_card', 'other');

create type transaction_kind as enum
  ('expense', 'income', 'transfer');

create type transaction_direction as enum
  ('debit', 'credit');

-- profiles -----------------------------------------------------------------
create table profiles (
  id            uuid primary key references auth.users(id) on delete cascade,
  display_name  text,
  base_currency char(3)     not null default 'INR',
  timezone      text        not null default 'Asia/Kolkata',
  locale        text        not null default 'en-IN',
  onboarded_at  timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

create trigger profiles_updated_at before update on profiles
  for each row execute function set_updated_at();

-- categories ---------------------------------------------------------------
-- Per-user. Defaults are seeded on signup so users may rename freely.
create table categories (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users(id) on delete cascade,
  parent_id  uuid references categories(id) on delete cascade,
  name       text not null,
  icon       text,
  color      text,
  is_system  boolean not null default false,
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint categories_name_unique unique (user_id, parent_id, name),
  constraint categories_not_self_parent check (id <> parent_id)
);

create index categories_user_idx   on categories (user_id);
create index categories_parent_idx on categories (parent_id);

create trigger categories_updated_at before update on categories
  for each row execute function set_updated_at();

-- merchants ----------------------------------------------------------------
-- Canonical merchant. Raw strings from banks are matched via merchant_aliases,
-- so "SWIGGY", "Swiggy Ltd" and "SWIGGY*ORDER" collapse to one merchant.
create table merchants (
  id                  uuid primary key default gen_random_uuid(),
  user_id             uuid not null references auth.users(id) on delete cascade,
  name                text not null,
  normalized_name     text not null,
  default_category_id uuid references categories(id) on delete set null,
  logo_url            text,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  constraint merchants_name_unique unique (user_id, normalized_name)
);

create index merchants_user_idx on merchants (user_id);
create index merchants_trgm_idx on merchants using gin (normalized_name gin_trgm_ops);

create trigger merchants_updated_at before update on merchants
  for each row execute function set_updated_at();

create table merchant_aliases (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  merchant_id     uuid not null references merchants(id) on delete cascade,
  raw_text        text not null,
  normalized_text text not null,
  created_at      timestamptz not null default now(),
  constraint merchant_aliases_unique unique (user_id, normalized_text)
);

create index merchant_aliases_merchant_idx on merchant_aliases (merchant_id);
create index merchant_aliases_trgm_idx
  on merchant_aliases using gin (normalized_text gin_trgm_ops);

-- accounts -----------------------------------------------------------------
-- Balance is DERIVED (opening_balance + sum of signed amounts), never mutated
-- per transaction. cached_balance is a denormalised convenience, refreshed by
-- a reconciliation job; it is not the source of truth.
create table accounts (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references auth.users(id) on delete cascade,
  name             text not null,
  type             account_type not null,
  currency         char(3) not null default 'INR',
  last4            char(4),
  opening_balance  numeric(14,2) not null default 0,
  cached_balance   numeric(14,2) not null default 0,
  balance_synced_at timestamptz,
  is_archived      boolean not null default false,
  sort_order       integer not null default 0,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  constraint accounts_name_unique unique (user_id, name),
  constraint accounts_last4_digits check (last4 is null or last4 ~ '^[0-9]{4}$')
);

create index accounts_user_idx on accounts (user_id) where is_archived = false;
create index accounts_last4_idx on accounts (user_id, last4) where last4 is not null;

create trigger accounts_updated_at before update on accounts
  for each row execute function set_updated_at();

-- credit card detail (1:1 with an account of type credit_card) --------------
create table credit_card_details (
  account_id      uuid primary key references accounts(id) on delete cascade,
  user_id         uuid not null references auth.users(id) on delete cascade,
  credit_limit    numeric(14,2),
  outstanding     numeric(14,2),
  billing_day     smallint,
  due_day         smallint,
  minimum_due     numeric(14,2),
  last_statement_at date,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  constraint cc_billing_day_range check (billing_day is null or billing_day between 1 and 31),
  constraint cc_due_day_range     check (due_day     is null or due_day     between 1 and 31)
);

create trigger credit_card_details_updated_at before update on credit_card_details
  for each row execute function set_updated_at();
