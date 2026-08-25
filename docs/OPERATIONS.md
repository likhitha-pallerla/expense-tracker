# Operations

Running things that are not code: backups, restores, and the checks that tell
you whether either actually works.

---

## Backups

### Why this exists

**Supabase's free tier takes no backups.** Not "shorter retention" — none.
There are no daily snapshots, no point-in-time recovery, and no support path
that recovers a dropped table or a deleted project. Paid plans get daily
backups; the free tier gets nothing.

So whatever `scripts/backup.mjs` writes is **the only copy of this database
that exists outside Supabase's control.** If the project is deleted, the free
tier pauses and is reaped for inactivity, or a migration goes wrong, that file
is the entire recovery plan.

### Taking a backup

```bash
node scripts/backup.mjs --out D:/backups/expense-tracker
```

`BACKUP_DIR` works instead of `--out`. Credentials come from `.env`
(`DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`) or the environment.

The output is a single AES-256-GCM encrypted file,
`expense-tracker-<timestamp>.sql.enc`. Plaintext is never written to disk at
any point — `pg_dump` is piped straight through the cipher.

**The script refuses to write inside this repository.** The repository is
public and the backup is every transaction in the database. That guard has a
test.

### What is in it, and why

Two `pg_dump` passes go into one encrypted stream:

1. **`auth.users`, data only, as column-named `INSERT`s.**
2. **The `public` schema**, plus the `pg_trgm` extension.

Every one of the 24 user-owned tables has
`FOREIGN KEY (user_id) REFERENCES auth.users(id)`. A `public`-only backup is
worthless: restoring it into a fresh project fails on the first
`ADD CONSTRAINT`, and signing in again mints a **new** UUID, so the old
`user_id` values could never match anything again. The identities have to come
back with their original ids, and they have to be inserted **before** the
public dump adds its foreign keys. That is why the order is fixed.

Deliberately excluded:

- **Everything outside `public` and `auth.users`** — `storage`, `realtime`,
  `vault` and friends are Supabase's, and a fresh project builds its own.
- **`pgcrypto`** — created by `V1__core.sql`, but nothing uses it
  (`gen_random_uuid()` has been built into Postgres since 13). Including it
  emits `WITH SCHEMA extensions`, which re-ties the backup to the platform it
  is meant to insure against.
- **`pg_trgm` is kept**, because dropping it produces a dump with a trigram
  index and no operator class, which fails on restore.

### Checking a backup

```bash
node scripts/restore.mjs --file BACKUP.sql.enc --check
```

This decrypts in memory, verifies the GCM tag, and prints the table and row
counts. It writes no plaintext and touches no database, so it is safe to run
on a schedule.

It **exits non-zero when a backup decrypts but is empty.** A backup that
restores cleanly into an empty database is still a lost database, and that is
the failure mode that looks most like success.

Check the output actually lists `auth.users`. Without it, the restore fails on
every foreign key.

### Restoring

```bash
# Look at it first
node scripts/restore.mjs --file BACKUP.sql.enc --out restored.sql

# Or load it straight in
node scripts/restore.mjs --file BACKUP.sql.enc --to "postgresql://..."
```

The target must be a **Supabase project** (or something that looks like one).
The dump's row-level-security policies call `auth.uid()`, and its foreign keys
need `auth.users` — both of which Supabase provides and a bare Postgres does
not. Restoring into an empty `postgres` database fails with
`function auth.uid() does not exist`; that is expected, not a corrupt backup.

`--out` writes **decrypted financial data to disk**. Delete it when done.

### Scheduling it

Windows Task Scheduler:

```powershell
schtasks /create /tn "ExpenseTracker Backup" /sc daily /st 02:00 ^
  /tr "node C:\path\to\expense-tracker\scripts\backup.mjs --out D:\backups"
```

cron:

```
0 2 * * * cd /path/to/expense-tracker && node scripts/backup.mjs --out ~/backups
```

Prefer a synced folder or an external disk. Backups on the same laptop as
nothing else do not survive the laptop.

### Never put backups in CI

Do not add a workflow that uploads backups as GitHub Actions artifacts.
**This repository is public, and artifacts on a public repository are
downloadable by anyone.** Encryption does not save you: it hands an attacker
the ciphertext to grind offline at their own pace, forever, against a
passphrase that cannot be rotated after the fact. It would also mean putting
the database password and the backup passphrase into repository secrets, so a
single workflow injection leaks both the data and the key to read it.

Backups belong on a machine you control.

### If you lose the passphrase

The backups are unreadable. This is not recoverable and is not a bug — the
whole point is that a stolen backup file is useless. Store the passphrase in a
password manager, somewhere other than the folder holding the backups.

---

## Verifying a backup properly

Decrypting a backup proves the passphrase was right. It does **not** prove the
backup restores. Taking a real backup and restoring it into an empty database
found four separate defects that `--check` alone passed:

1. Row counts read as `0` because `pg_dump` writes CRLF through a Windows pipe.
2. Excluding Supabase schemas still dragged in event triggers calling
   `extensions.pgrst_ddl_watch()` — fixed by allow-listing `--schema=public`.
3. `--schema=public` alone dropped `pg_trgm`, leaving an index whose operator
   class did not exist.
4. The auth pass was invisible to `--check`, because a `--data-only` dump has
   no `CREATE TABLE` for the summary to find.

**Restore a backup somewhere harmless at least once before relying on it.**
The scripts have tests, and the round-trip has been proved end-to-end —
identity, owned rows and foreign keys all reconnect — but that was proved on
this schema. Prove it again after a migration that changes it.
