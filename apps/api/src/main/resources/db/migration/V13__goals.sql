-- Savings goals: a ledger, not a running total.
--
-- `goals.current_amount` was a single number, which is enough to draw a
-- progress bar and useless for anything else. You cannot tell from one figure
-- whether someone is saving steadily or put everything in on day one and has
-- added nothing since — and those two people need to be told completely
-- different things.
--
-- So contributions become rows and the total is derived, matching how account
-- balances already work here. Nothing in this codebase keeps a running total
-- that a bug can quietly desynchronise from its own history.

create table goal_contributions (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references auth.users(id) on delete cascade,
  goal_id        uuid not null references goals(id) on delete cascade,
  amount         numeric(14,2) not null,
  occurred_on    date not null default current_date,
  note           text,
  -- When the money moved as a real transaction, keep the link. Set null on
  -- delete rather than cascade: deleting a transaction should not silently
  -- erase the fact that you saved that month.
  transaction_id uuid references transactions(id) on delete set null,
  created_at     timestamptz not null default now(),
  -- Withdrawals are allowed and stored negative, because raiding your own
  -- savings is a thing that happens and a goal that cannot show it would be
  -- lying. Zero is not, since it records nothing.
  constraint goal_contributions_amount_nonzero check (amount <> 0)
);

create index goal_contributions_goal_idx
  on goal_contributions (goal_id, occurred_on);
create index goal_contributions_user_idx
  on goal_contributions (user_id);

-- What has actually been put aside.
create or replace function goal_saved(p_goal_id uuid)
returns numeric(14,2)
language sql stable as $$
  select coalesce(sum(c.amount), 0)::numeric(14,2)
  from goal_contributions c
  where c.goal_id = p_goal_id;
$$;

comment on function goal_saved is
  'Derived from the contribution ledger. Never stored: a cached total is one '
  'bug away from disagreeing with the rows that produced it.';

-- Carry across whatever the old column held ----------------------------------
-- Dated to the goal's creation, which is the only honest guess available, and
-- labelled so nobody later mistakes it for a real deposit they made.
insert into goal_contributions (user_id, goal_id, amount, occurred_on, note)
select g.user_id, g.id, g.current_amount, g.created_at::date,
       'Balance recorded before contributions were tracked'
from goals g
where g.current_amount <> 0;

alter table goals drop column current_amount;

-- What the user intends to put in, as opposed to what they have --------------
-- Optional. Someone can save toward a target date without committing to a
-- monthly figure, and someone can commit to a monthly figure with no deadline
-- in mind. Both are real ways to save and neither should be forced into the
-- other.
alter table goals
  add column monthly_target numeric(14,2),
  add column notes          text,
  add column achieved_at    timestamptz,
  add constraint goals_monthly_target_positive
    check (monthly_target is null or monthly_target > 0);

comment on column goals.achieved_at is
  'Set the first time the target is reached, and never cleared by a later '
  'withdrawal. Hitting a savings goal is an event that happened; dipping into '
  'it afterwards does not un-happen it.';

comment on column goals.target_date is
  'Optional. Without one a goal has progress but no pace, and nothing here '
  'may call it "behind" — there is nothing to be behind.';

-- Same policies as every other per-user table.
alter table goal_contributions enable row level security;
alter table goal_contributions force row level security;

create policy goal_contributions_select on goal_contributions
  for select using (user_id = auth.uid());
create policy goal_contributions_insert on goal_contributions
  for insert with check (user_id = auth.uid());
create policy goal_contributions_update on goal_contributions
  for update using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy goal_contributions_delete on goal_contributions
  for delete using (user_id = auth.uid());
