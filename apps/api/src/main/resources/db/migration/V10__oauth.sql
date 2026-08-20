-- OAuth for mailbox connections.
--
-- V2 already anticipated the shape of a linked mailbox. What it did not have
-- is anywhere to keep the half-finished handshake: between sending someone to
-- Google and hearing back from Google, there is state that must survive, must
-- be tied to one user, and must be usable exactly once.

-- oauth_states --------------------------------------------------------------
-- One row per authorisation attempt, deleted or expired shortly after.
--
-- The `state` parameter could have been a signed token instead, needing no
-- table. It was not, for two reasons. A signed token cannot be revoked or
-- spent: replaying a captured callback URL would work as often as the attacker
-- liked, and single use is the entire defence against that. And PKCE requires
-- the verifier to be secret, which a value living in the user's own address bar
-- is not.
create table oauth_states (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  provider      connection_provider not null,

  -- The opaque value handed to the provider and echoed back to us. Random,
  -- unguessable, and matched on return: this is what stops a third party
  -- feeding us a callback for an authorisation we never started.
  state         text not null unique,

  -- PKCE. The provider only ever sees the SHA-256 of this, so a stolen
  -- authorisation code cannot be exchanged without the row.
  code_verifier text not null,

  -- Where to send the browser afterwards. Held here rather than passed through
  -- the provider, so the return address cannot be edited mid-flight into an
  -- open redirect.
  return_path   text,

  created_at    timestamptz not null default now(),
  expires_at    timestamptz not null,

  -- Set on the first successful callback. A second attempt with the same state
  -- finds it non-null and is refused.
  consumed_at   timestamptz
);

create index oauth_states_expiry_idx on oauth_states (expires_at);

-- RLS with no policies at all, which denies every direct client.
--
-- Every other table grants its owner full access, because the web app may
-- legitimately read its own rows through PostgREST. Not this one: the whole
-- point of the code verifier is that only the server ever sees it, and a user
-- session that can read its own verifier hands that secret to anything running
-- in the browser. The backend connects as the table owner and bypasses RLS, so
-- it is unaffected.
alter table oauth_states enable row level security;
alter table oauth_states force row level security;

-- source_connections --------------------------------------------------------
alter table source_connections
  -- Access tokens are short-lived, but they are still bearer credentials for a
  -- mailbox and get the same treatment as the refresh token.
  add column encrypted_access_token text,

  -- What the provider actually granted, which is not always what was asked
  -- for: consent screens let people withhold individual permissions. Stored so
  -- a connection that cannot read mail is diagnosed here rather than as a
  -- puzzling empty sync.
  add column granted_scopes text,

  add column connected_at timestamptz;

comment on column source_connections.encrypted_refresh_token is
  'AES-256-GCM envelope (v1.iv.ciphertext) bound to user_id as additional '
  'authenticated data. Never written or logged in plaintext.';
