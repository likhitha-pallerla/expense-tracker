#!/usr/bin/env node
/**
 * Takes an encrypted backup of the database.
 *
 * Supabase's free tier has **no automated backups at all** -- that is a paid
 * feature, and there is no retention window, no point-in-time recovery and no
 * "contact support" path on the free plan. Whatever this script writes is the
 * only copy of the data that is not in Supabase's hands.
 *
 * Two things follow from that, and they are why this file is not three lines
 * of pg_dump:
 *
 * 1. **The dump is encrypted before it reaches the disk.** It is a list of
 *    every transaction, merchant and account number in the system. It is piped
 *    from pg_dump into an AES-256-GCM stream and only then written, so there
 *    is never a moment where the plaintext exists as a file for a backup
 *    utility, a sync client or a search indexer to pick up.
 *
 * 2. **This must never be pointed at the repository.** The repository is
 *    public. Nor at GitHub Actions artifacts, which on a public repository can
 *    be downloaded by anyone -- shipping ciphertext there hands an attacker an
 *    offline target they can work on indefinitely. Keep backups somewhere you
 *    control.
 *
 * Usage:
 *   node scripts/backup.mjs --out DIR
 *
 * Reads DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD and BACKUP_PASSPHRASE
 * from the environment or from the .env file at the repository root.
 *
 * Restore with scripts/restore.mjs. Do that at least once, on purpose,
 * somewhere harmless -- an untested backup is a guess, not a backup.
 */
import { spawn } from "node:child_process";
import { createCipheriv, randomBytes, scryptSync } from "node:crypto";
import { createWriteStream } from "node:fs";
import { mkdir, readFile, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

/**
 * The format marker written at the head of every file.
 *
 * Versioned now rather than later: a backup written today may be restored by a
 * version of this script that does not exist yet, and "which format is this?"
 * is not a question to answer by guessing during an incident.
 */
export const MAGIC = "ETBK1";

/** Deliberately slow, so a weak passphrase is still expensive to attack. */
export const SCRYPT_PARAMS = { N: 1 << 15, r: 8, p: 1, maxmem: 128 * 1024 * 1024 };

/** Loads .env without a dependency, for the few keys this script needs. */
export function parseEnv(text) {
  const out = {};
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eq = trimmed.indexOf("=");
    if (eq === -1) continue;
    const key = trimmed.slice(0, eq).trim();
    let value = trimmed.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    out[key] = value;
  }
  return out;
}

/**
 * Turns the API's JDBC URL into something libpq understands.
 *
 * The rest of the project stores the connection as a JDBC string because the
 * API is Java. Rather than ask for the same database twice in two dialects --
 * which drifts, and then the backup silently runs against the wrong host --
 * the one that already exists is translated.
 */
export function jdbcToLibpq(jdbcUrl, user, password) {
  const match = /^jdbc:postgresql:\/\/([^/?]+)\/([^?]+)/.exec(jdbcUrl ?? "");
  if (!match) {
    throw new Error(
      `DATABASE_URL is not a JDBC Postgres URL: ${jdbcUrl ?? "(unset)"}`,
    );
  }
  const [, hostPort, database] = match;
  const encodedUser = encodeURIComponent(user ?? "");
  const encodedPassword = encodeURIComponent(password ?? "");
  return `postgresql://${encodedUser}:${encodedPassword}@${hostPort}/${database}`;
}

/** `expense-tracker-20260825T054500Z.sql.enc` -- sorts chronologically. */
export function backupName(now = new Date()) {
  const stamp = now.toISOString().replace(/[-:]/g, "").replace(/\..*$/, "Z");
  return `expense-tracker-${stamp}.sql.enc`;
}

/**
 * True when `target` is the repository or sits inside it.
 *
 * Its own function because it is the check that stops a public repository from
 * acquiring a copy of someone's bank history, and that deserves a test.
 */
export function isInsideRepo(target, root = ROOT) {
  const resolved = path.resolve(target);
  return resolved === root || resolved.startsWith(root + path.sep);
}

/**
 * Header layout, ahead of the ciphertext:
 *
 *   MAGIC (5 bytes) | salt (16) | iv (12) | ciphertext... | tag (16)
 *
 * The tag goes at the end because it is not known until the stream closes.
 * GCM is used rather than CBC so that a truncated or altered file fails loudly
 * on restore instead of decrypting to plausible rubbish.
 */
export function buildHeader(salt, iv) {
  return Buffer.concat([Buffer.from(MAGIC, "ascii"), salt, iv]);
}

/**
 * The application's own schema, and the identities its rows point at.
 *
 * `--schema=public` is an allow-list rather than a list of Supabase schemas to
 * exclude, and that distinction was not obvious until a restore was actually
 * attempted. Excluding the known-managed schemas still left behind
 * `CREATE EXTENSION ... WITH SCHEMA extensions` and a set of event triggers
 * calling `extensions.pgrst_ddl_watch()` -- Supabase platform plumbing that is
 * global rather than schema-scoped, and that fails on any database Supabase did
 * not build.
 */
export function pgDumpArgs(connection) {
  return [
    connection,
    "--schema=public",
    // The schema alone is not enough. pg_dump leaves extensions out unless
    // asked for them by name, which produced a dump carrying a trigram index
    // but not the operator class it needs -- the restore failed on
    // `operator class "public.gin_trgm_ops" does not exist`.
    //
    // Only pg_trgm. V1__core.sql also creates pgcrypto, but nothing uses a
    // pgcrypto function -- ids default to the built-in gen_random_uuid(),
    // which has needed no extension since Postgres 13. Including it would
    // emit `CREATE EXTENSION pgcrypto WITH SCHEMA extensions`, naming a schema
    // that exists on Supabase and nowhere else, which would tie these backups
    // to the one platform they are insurance against.
    "--extension=pg_trgm",
    // The restore target is a fresh project with different role names, so
    // ownership and grants from this one would only fail.
    "--no-owner",
    "--no-privileges",
    // Make the dump idempotent: restoring twice should not error.
    "--clean",
    "--if-exists",
  ];
}

/**
 * The identities every other table points at.
 *
 * All twenty-four user-owned tables carry
 * `FOREIGN KEY (user_id) REFERENCES auth.users(id)`. A backup of `public`
 * alone therefore restores into a fresh project and fails on the first
 * constraint, because the accounts those rows belong to do not exist there --
 * and signing in again would mint a *new* uuid, so the old `user_id` values
 * would never match. The rows have to come back with their original ids.
 *
 * Data only: `auth` is Supabase's schema and a fresh project already has it,
 * complete with its own functions and triggers. Only the rows are ours.
 */
export function authDumpArgs(connection) {
  return [
    connection,
    "--data-only",
    "--table=auth.users",
    "--no-owner",
    "--no-privileges",
    // Column names are written out, so a restore still works if Supabase has
    // added a column to auth.users since the backup was taken.
    "--column-inserts",
  ];
}

async function main() {
  const args = process.argv.slice(2);
  const outIndex = args.indexOf("--out");
  const outDir = outIndex !== -1 ? args[outIndex + 1] : process.env.BACKUP_DIR;

  if (!outDir) {
    console.error(
      "Where should the backup go? Pass --out DIR or set BACKUP_DIR.\n" +
        "Do not choose a directory inside this repository: it is public.",
    );
    process.exit(2);
  }

  const resolvedOut = path.resolve(outDir);
  if (isInsideRepo(resolvedOut)) {
    console.error(
      `Refusing to write a backup into the repository (${resolvedOut}).\n` +
        "This repository is public, and the backup is every transaction in the\n" +
        "database. Choose a directory outside it.",
    );
    process.exit(2);
  }

  let fileEnv = {};
  try {
    fileEnv = parseEnv(await readFile(path.join(ROOT, ".env"), "utf8"));
  } catch {
    // Environment-only is a perfectly good way to run this.
  }
  const env = { ...fileEnv, ...process.env };

  const passphrase = env.BACKUP_PASSPHRASE;
  if (!passphrase || passphrase.length < 12) {
    console.error(
      "Set BACKUP_PASSPHRASE to at least 12 characters.\n" +
        "Store it somewhere separate from the backups themselves -- a password\n" +
        "manager, not the same folder. Lose it and the backup is unreadable;\n" +
        "there is deliberately no recovery path.",
    );
    process.exit(2);
  }

  const connection = jdbcToLibpq(
    env.DATABASE_URL,
    env.DATABASE_USER,
    env.DATABASE_PASSWORD,
  );

  await mkdir(resolvedOut, { recursive: true });
  const target = path.join(resolvedOut, backupName());

  const salt = randomBytes(16);
  const iv = randomBytes(12);
  const key = scryptSync(passphrase, salt, 32, SCRYPT_PARAMS);
  const cipher = createCipheriv("aes-256-gcm", key, iv);

  const pgDump = env.PG_DUMP ?? "pg_dump";
  const out = createWriteStream(target);
  out.write(buildHeader(salt, iv));

  // Nothing is awaited on `out` directly: the cipher is the only writer, and
  // both dumps are pushed through it in turn so they end up in one file.
  cipher.pipe(out);

  /** Runs one pg_dump pass into the cipher, without closing it. */
  async function dumpInto(args, label) {
    const child = spawn(pgDump, args, { stdio: ["ignore", "pipe", "pipe"] });

    let stderr = "";
    let spawnError = null;
    child.on("error", (error) => {
      spawnError = error;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });

    await new Promise((resolve, reject) => {
      // pipe() rather than a data handler: pg_dump can outrun the cipher on a
      // large database, and only pipe applies backpressure. `end: false`
      // keeps the cipher open for the next pass.
      child.stdout.pipe(cipher, { end: false });
      child.stdout.on("error", reject);
      child.on("close", resolve);
    });

    const code = child.exitCode;
    if (spawnError || code !== 0) {
      console.error(
        spawnError
          ? `Could not run ${pgDump}: ${spawnError.message}`
          : `pg_dump (${label}) exited ${code}`,
      );
      if (stderr.trim()) console.error(stderr.trim());
      if (spawnError) {
        console.error(
          "\nInstall the PostgreSQL client tools, or set PG_DUMP to the full\n" +
            "path of pg_dump.",
        );
      }
      process.exit(1);
    }
  }

  // Order matters. The identities have to be inserted before the public dump
  // adds its foreign keys, or every ADD CONSTRAINT fails against rows whose
  // owner does not exist yet.
  await dumpInto(authDumpArgs(connection), "auth.users");
  await dumpInto(pgDumpArgs(connection), "public");

  await new Promise((resolve, reject) => {
    out.on("error", reject);
    out.on("finish", resolve);
    cipher.end();
  });

  // The tag authenticates everything above it. Without it a restore cannot
  // tell a good file from a corrupted one.
  await new Promise((resolve, reject) => {
    const tagStream = createWriteStream(target, { flags: "a" });
    tagStream.on("error", reject);
    tagStream.end(cipher.getAuthTag(), resolve);
  });

  const { size } = await stat(target);
  console.log(`Wrote ${target}`);
  console.log(`${(size / 1024).toFixed(1)} KiB, encrypted with AES-256-GCM.`);
  console.log(
    "\nRestore it once, somewhere harmless, before you rely on it:\n" +
      `  node scripts/restore.mjs --file "${target}" --check`,
  );
}

// Only run when invoked directly, so the helpers above can be imported by
// tests without taking a backup as a side effect.
const invoked = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invoked === fileURLToPath(import.meta.url)) {
  await main();
}
