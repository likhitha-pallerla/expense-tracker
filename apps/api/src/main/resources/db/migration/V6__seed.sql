-- Helper functions and per-user default data.

-- normalize_merchant_name ---------------------------------------------------
-- Collapses the noise banks put around merchant names so that
-- "SWIGGY*ORDER 1234", "UPI-SWIGGY LTD" and "POS SWIGGY" all reduce to SWIGGY.
create or replace function normalize_merchant_name(raw text)
returns text
language sql immutable as $$
  select nullif(
    trim(regexp_replace(
      regexp_replace(
        regexp_replace(
          upper(coalesce(raw, '')),
          '(^|[^A-Z0-9])(UPI|POS|ATM|NEFT|IMPS|RTGS|ACH|MMT|VPA|TXN|REF|PURCHASE|PAYMENT|PMT)([^A-Z0-9]|$)',
          ' ', 'g'),
        '[^A-Z ]+', ' ', 'g'),
      '\s+', ' ', 'g')),
    '');
$$;

-- seed_user_defaults --------------------------------------------------------
-- Called by the backend the first time a user signs in. Categories are
-- per-user so people can rename and reorganise them freely.
create or replace function seed_user_defaults(p_user_id uuid)
returns void
language plpgsql as $$
declare
  parent_id uuid;
  spec jsonb := '[
    {"name":"Food",          "icon":"utensils",   "children":["Restaurants","Groceries","Delivery"]},
    {"name":"Transport",     "icon":"car",        "children":["Fuel","Cab","Metro","Parking"]},
    {"name":"Housing",       "icon":"home",       "children":["Rent","Maintenance","Repairs"]},
    {"name":"Bills",         "icon":"receipt",    "children":["Electricity","Internet","Mobile","Water","Gas"]},
    {"name":"Shopping",      "icon":"shopping-bag","children":["Clothes","Electronics","Cosmetics","Home"]},
    {"name":"Health",        "icon":"heart-pulse","children":["Medicine","Doctor","Insurance","Fitness"]},
    {"name":"Travel",        "icon":"plane",      "children":["Flights","Hotels","Trains"]},
    {"name":"Entertainment", "icon":"clapperboard","children":["Movies","Subscriptions","Events"]},
    {"name":"Family",        "icon":"users",      "children":["Parents","Gifts","Childcare"]},
    {"name":"Education",     "icon":"graduation-cap","children":["Courses","Books","Fees"]},
    {"name":"Investments",   "icon":"trending-up","children":["Mutual Funds","Stocks","Gold","Deposits"]},
    {"name":"EMI / Loans",   "icon":"landmark",   "children":["Personal Loan","Home Loan","Credit Card"]},
    {"name":"Income",        "icon":"wallet",     "children":["Salary","Interest","Refunds","Other Income"]},
    {"name":"Miscellaneous", "icon":"circle-dot", "children":[]}
  ]'::jsonb;
  item jsonb;
  child text;
  idx integer := 0;
begin
  if exists (select 1 from categories where user_id = p_user_id) then
    return;
  end if;

  for item in select * from jsonb_array_elements(spec) loop
    idx := idx + 1;

    insert into categories (user_id, name, icon, is_system, sort_order)
    values (p_user_id, item->>'name', item->>'icon', true, idx)
    returning id into parent_id;

    for child in select * from jsonb_array_elements_text(item->'children') loop
      insert into categories (user_id, parent_id, name, is_system, sort_order)
      values (p_user_id, parent_id, child, true, 0);
    end loop;
  end loop;

  insert into accounts (user_id, name, type, sort_order)
  values (p_user_id, 'Cash', 'cash', 1)
  on conflict (user_id, name) do nothing;
end $$;
