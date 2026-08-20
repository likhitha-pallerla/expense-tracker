-- Keep normalize_merchant_name in lockstep with MerchantNormalizer.java.
--
-- Corporate suffixes carry no merchant identity, but they cost a full token in
-- similarity scoring: "SWIGGY" vs "SWIGGY LTD" scored as a half match, which
-- pushed genuine email+SMS duplicates below the auto-merge threshold.
create or replace function normalize_merchant_name(raw text)
returns text
language sql immutable as $$
  select nullif(
    trim(regexp_replace(
      regexp_replace(
        regexp_replace(
          regexp_replace(
            upper(coalesce(raw, '')),
            '(^|[^A-Z0-9])(UPI|POS|ATM|NEFT|IMPS|RTGS|ACH|MMT|VPA|TXN|REF|PURCHASE|PAYMENT|PMT|LTD|LIMITED|PVT|PRIVATE|INC|LLP|LLC|CORP|COMPANY)([^A-Z0-9]|$)',
            ' ', 'g'),
          -- Applied twice: adjacent noise tokens share a delimiter, so a single
          -- pass leaves every second token behind ("UPI POS SWIGGY").
          '(^|[^A-Z0-9])(UPI|POS|ATM|NEFT|IMPS|RTGS|ACH|MMT|VPA|TXN|REF|PURCHASE|PAYMENT|PMT|LTD|LIMITED|PVT|PRIVATE|INC|LLP|LLC|CORP|COMPANY)([^A-Z0-9]|$)',
          ' ', 'g'),
        '[^A-Z ]+', ' ', 'g'),
      '\s+', ' ', 'g')),
    '');
$$;

-- Merchant names normalised under the old definition keep their suffix, so
-- refresh them. Conflicts mean two rows collapse to one canonical name; keep
-- the oldest and repoint aliases and transactions at it.
with ranked as (
  select id,
         user_id,
         normalize_merchant_name(name) as new_name,
         row_number() over (
           partition by user_id, normalize_merchant_name(name)
           order by created_at, id
         ) as rn,
         first_value(id) over (
           partition by user_id, normalize_merchant_name(name)
           order by created_at, id
         ) as keep_id
  from merchants
  where normalize_merchant_name(name) is not null
)
update transactions t
   set merchant_id = r.keep_id
  from ranked r
 where t.merchant_id = r.id
   and r.rn > 1;

with ranked as (
  select id,
         user_id,
         row_number() over (
           partition by user_id, normalize_merchant_name(name)
           order by created_at, id
         ) as rn,
         first_value(id) over (
           partition by user_id, normalize_merchant_name(name)
           order by created_at, id
         ) as keep_id
  from merchants
  where normalize_merchant_name(name) is not null
)
update merchant_aliases a
   set merchant_id = r.keep_id
  from ranked r
 where a.merchant_id = r.id
   and r.rn > 1;

with ranked as (
  select id,
         row_number() over (
           partition by user_id, normalize_merchant_name(name)
           order by created_at, id
         ) as rn
  from merchants
  where normalize_merchant_name(name) is not null
)
delete from merchants m
 using ranked r
 where m.id = r.id
   and r.rn > 1;

update merchants
   set normalized_name = normalize_merchant_name(name)
 where normalize_merchant_name(name) is not null
   and normalized_name is distinct from normalize_merchant_name(name);

update merchant_aliases
   set normalized_text = normalize_merchant_name(raw_text)
 where normalize_merchant_name(raw_text) is not null
   and normalized_text is distinct from normalize_merchant_name(raw_text);
