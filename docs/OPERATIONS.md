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

---

## Deploying

Web on **Vercel**, API on **Render**, database already on **Supabase**. All
free tier.

### What the free tiers actually cost you

**Render free instances spin down after 15 minutes without traffic.** The next
request pays a cold start of roughly 30–60 seconds while the JVM boots, and on
0.1 CPU it is at the slow end of that. There is no way to disable this on the
free plan.

This is survivable here because **nothing in the API is scheduled** — there are
no `@Scheduled` jobs, and mail and SMS sync are triggered by the user. A
spun-down service therefore loses no work; it is only slow to wake. If a
background sync is ever added, it will not run on the free tier, and that will
need a paid instance or an external trigger.

Other limits worth knowing: 512 MB RAM, 0.1 CPU, 750 instance-hours a month
across the whole workspace (exhausting them suspends every free service until
the next month), and an ephemeral filesystem that is wiped on each deploy — so
nothing may be stored on disk.

**Vercel Hobby prohibits commercial use.** Ads, payments, affiliate links or
client work all require Pro. A personal expense tracker is fine; the moment
this is sold or monetised, the plan has to change.

### Why the API ships as a container

Render has native runtimes for Node, Python, Ruby, Go, Rust and Elixir — **not
for Java**. Docker is the only supported way to run a JVM service there, which
is why `apps/api/Dockerfile` exists and why it is the only place the Java
version is pinned. There is no `.java-version` or `JAVA_VERSION` mechanism to
fall back on.

Two details in that Dockerfile are not decoration:

- **`-XX:MaxRAMPercentage=70.0`.** The JVM's default is to claim a quarter of
  the container limit, which wastes most of a 512 MB box. Raising it too far
  instead leaves no room for metaspace and thread stacks and gets the process
  OOM-killed partway through Flyway.
- **The dependency layer.** `pom.xml` is copied and resolved before the source,
  so a normal push does not re-download the dependency tree on a 0.1 CPU
  builder.

### Order of operations

The two services each need the other's URL, so a single pass cannot work.

1. **Render first.** New → Blueprint, point at this repository; it reads
   `render.yaml`. Fill in the prompted secrets. Leave `CORS_ALLOWED_ORIGINS`
   and `OAUTH_WEB_BASE` as placeholders for now. Note the assigned
   `https://<name>.onrender.com` URL and set `OAUTH_API_BASE` to it.
2. **Vercel second.** Import the repository and set **Root Directory to
   `apps/web`** — this is the setting that makes the monorepo work; without it
   the build runs at the repository root and finds no Next.js app. Set
   `NEXT_PUBLIC_API_BASE_URL` to the Render URL, plus the two Supabase
   variables. Note the assigned `https://<name>.vercel.app` URL.
3. **Back to Render.** Set `CORS_ALLOWED_ORIGINS` and `OAUTH_WEB_BASE` to the
   Vercel origin — no trailing slash — and redeploy. Until this is done the
   browser blocks every API call from production, and OAuth consent redirects
   the user to `localhost`.
4. **Supabase.** Add the Vercel origin to the allowed redirect URLs, and add
   `https://<name>.onrender.com/api/connections/callback/gmail` to the Google
   OAuth client (and `.../callback/outlook` to the Microsoft one). The last
   path segment is the provider key from `MailProvider`, not the vendor name —
   `gmail`, not `google`.

**`NEXT_PUBLIC_*` values are baked into the JavaScript at build time.**
Changing one in the Vercel dashboard does nothing until you redeploy. This
catches people out because every other environment variable updates on
restart.

Set them for **Preview as well as Production**. A variable set only on
Production leaves preview builds with `undefined`, and the failure looks like a
code bug.

### Things to turn off, given this repository is public

- **Vercel preview deployments are publicly reachable by default.** Every push
  gets a guessable `branch-project.vercel.app` URL, and on a public repository
  the branch names are visible too. That is a live instance of a personal
  finance application on a URL anyone can find. Turn on **Deployment
  Protection** (Settings → Deployment Protection), or do not push branches.
- **Mark secrets as Sensitive** in the Vercel dashboard so they are redacted
  from build logs.
- **Render service URLs are guessable** (`name.onrender.com`) and are not a
  secret. Do not rely on the URL being unknown; the API authenticates every
  request, which is what actually protects it.
- **Neither `render.yaml` nor `vercel.json` contains a secret, and neither ever
  should.** Every sensitive value in `render.yaml` is `sync: false`, which
  means Render prompts for it once and stores it on their side. Anyone can read
  these files.

### Health checks

`healthCheckPath: /actuator/health` is what Render polls after a deploy to
decide whether to keep it. That endpoint is `permitAll` in `SecurityConfig`,
which it has to be — Render calls it with no token, and if it required
authentication every deploy would fail its check and roll back.

It is a deploy-time gate, not a keep-alive: it does not prevent spin-down.

Verified locally by running the built jar with `PORT` set the way Render sets
it, and calling the endpoint unauthenticated:

```
PORT=9099 java -jar target/api-0.1.0.jar
curl http://localhost:9099/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}
```

Worth repeating after any change to `SecurityConfig` or the port configuration.
`server.port` reads `${PORT:${SERVER_PORT:8080}}` — Render's `PORT` wins, the
local `SERVER_PORT` override still works, and 8080 remains the fallback. A
service that ignores `PORT` binds to a port nothing is routed to and fails its
health check with no obvious cause.
