-- Row Level Security.
--
-- Note on the threat model: the Spring Boot backend connects as `postgres`,
-- which BYPASSES RLS. These policies therefore protect the *direct* client
-- path — the web and mobile apps hold the anon key and could otherwise query
-- PostgREST directly. The backend additionally filters by user_id in every
-- query, so isolation is enforced twice. Defence in depth.

-- Standard per-user tables: owner may do everything, nobody else sees a row.
do $$
declare
  t text;
  tables text[] := array[
    'categories', 'merchants', 'merchant_aliases', 'accounts',
    'credit_card_details', 'source_connections', 'raw_messages',
    'import_batches', 'category_rules', 'transaction_groups',
    'transactions', 'duplicate_candidates', 'budgets', 'goals',
    'recurring_transactions', 'notifications', 'audit_log'
  ];
begin
  foreach t in array tables loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);

    execute format(
      'create policy %I on %I for select using (user_id = auth.uid())',
      t || '_select', t);
    execute format(
      'create policy %I on %I for insert with check (user_id = auth.uid())',
      t || '_insert', t);
    execute format(
      'create policy %I on %I for update using (user_id = auth.uid()) with check (user_id = auth.uid())',
      t || '_update', t);
    execute format(
      'create policy %I on %I for delete using (user_id = auth.uid())',
      t || '_delete', t);
  end loop;
end $$;

-- profiles: keyed on id, not user_id. No delete policy — removing a profile
-- happens by deleting the auth user, which cascades.
alter table profiles enable row level security;
alter table profiles force row level security;

create policy profiles_select on profiles for select using (id = auth.uid());
create policy profiles_insert on profiles for insert with check (id = auth.uid());
create policy profiles_update on profiles for update
  using (id = auth.uid()) with check (id = auth.uid());

-- parser_rules: system rules (user_id null) are readable by everyone, but only
-- the owner may write their own overrides. System rules are managed by the
-- backend, which bypasses RLS.
alter table parser_rules enable row level security;
alter table parser_rules force row level security;

create policy parser_rules_select on parser_rules for select
  using (user_id is null or user_id = auth.uid());
create policy parser_rules_insert on parser_rules for insert
  with check (user_id = auth.uid());
create policy parser_rules_update on parser_rules for update
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy parser_rules_delete on parser_rules for delete
  using (user_id = auth.uid());

-- The audit log must not be rewritable by clients.
drop policy audit_log_update on audit_log;
drop policy audit_log_delete on audit_log;
