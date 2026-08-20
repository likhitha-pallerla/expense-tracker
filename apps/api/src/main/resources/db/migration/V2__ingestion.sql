-- Ingestion: linked sources, raw messages, CSV imports, parser rules.
-- These are created before transactions so provenance FKs resolve cleanly.

create type connection_provider as enum ('gmail', 'outlook', 'android_sms', 'csv_import', 'manual');
create type connection_status   as enum ('active', 'needs_reauth', 'paused', 'error', 'revoked');
create type parse_status        as enum ('pending', 'parsed', 'ignored', 'quarantined', 'failed');

-- source_connections --------------------------------------------------------
-- A linked mailbox or device. OAuth refresh tokens are stored encrypted
-- (AES-256-GCM) by the application; the database never sees plaintext.
create table source_connections (
  id                    uuid primary key default gen_random_uuid(),
  user_id               uuid not null references auth.users(id) on delete cascade,
  provider              connection_provider not null,
  external_account      text,
  display_name          text,
  status                connection_status not null default 'active',
  encrypted_refresh_token text,
  token_expires_at      timestamptz,
  sync_cursor           text,
  last_synced_at        timestamptz,
  last_error            text,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now(),
  constraint source_connections_unique unique (user_id, provider, external_account)
);

create index source_connections_user_idx on source_connections (user_id, status);

create trigger source_connections_updated_at before update on source_connections
  for each row execute function set_updated_at();

-- raw_messages --------------------------------------------------------------
-- Dedup layer L0 lives here as database constraints: the same provider message
-- or the same normalised body can never be stored twice for a user.
create table raw_messages (
  id                  uuid primary key default gen_random_uuid(),
  user_id             uuid not null references auth.users(id) on delete cascade,
  connection_id       uuid references source_connections(id) on delete cascade,
  provider_message_id text,
  body_hash           text not null,
  sender              text,
  subject             text,
  snippet             text,
  body                text,
  received_at         timestamptz,
  status              parse_status not null default 'pending',
  parser_rule_id      uuid,
  parse_error         text,
  purge_after         timestamptz,
  created_at          timestamptz not null default now(),
  constraint raw_messages_provider_unique unique (connection_id, provider_message_id),
  constraint raw_messages_body_unique     unique (user_id, body_hash)
);

create index raw_messages_user_status_idx on raw_messages (user_id, status);
create index raw_messages_purge_idx on raw_messages (purge_after) where purge_after is not null;

-- import_batches ------------------------------------------------------------
create table import_batches (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references auth.users(id) on delete cascade,
  filename       text,
  account_id     uuid references accounts(id) on delete set null,
  column_mapping jsonb,
  row_count      integer not null default 0,
  imported_count integer not null default 0,
  duplicate_count integer not null default 0,
  status         text not null default 'pending',
  error          text,
  created_at     timestamptz not null default now(),
  completed_at   timestamptz
);

create index import_batches_user_idx on import_batches (user_id, created_at desc);

-- parser_rules --------------------------------------------------------------
-- Templates live in the database so a new bank never requires a redeploy.
-- user_id null = system-provided rule visible to everyone.
create table parser_rules (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid references auth.users(id) on delete cascade,
  name           text not null,
  issuer         text,
  provider       connection_provider,
  sender_pattern text,
  match_pattern  text not null,
  extractors     jsonb not null,
  priority       integer not null default 100,
  version        integer not null default 1,
  is_enabled     boolean not null default true,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);

create index parser_rules_lookup_idx on parser_rules (is_enabled, priority);

create trigger parser_rules_updated_at before update on parser_rules
  for each row execute function set_updated_at();

-- category_rules ------------------------------------------------------------
create table category_rules (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  match_type  text not null default 'merchant_contains',
  match_value text not null,
  category_id uuid not null references categories(id) on delete cascade,
  priority    integer not null default 100,
  is_enabled  boolean not null default true,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create index category_rules_user_idx on category_rules (user_id, is_enabled, priority);

create trigger category_rules_updated_at before update on category_rules
  for each row execute function set_updated_at();
