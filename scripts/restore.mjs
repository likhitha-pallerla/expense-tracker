#!/usr/bin/env node
/**
 * Decrypts a backup, and optionally restores it.
 *
 * This file exists because an untested backup is a guess. The common way to
 * lose data is not "we had no backups" -- it is "we had backups and none of
 * them restored", found out on the worst possible day. So `--check` is the
 * cheap, safe, no-database operation that proves a file is intact and readable,
 * and it is the one to run on a schedule.
 *
 * Usage:
 *   node scripts/restore.mjs --file BACKUP --check
 *       Decrypt, verify the authentication tag, and report what is inside.
 *       Touches no database. Writes no plaintext to disk.
 *
 *   node scripts/restore.mjs --file BACKUP --out DUMP.sql
 *       Decrypt to a plain .sql file. That file is every transaction in the
 *       database, in the clear. Delete it when you are done.
 *
 *   node scripts/restore.mjs --file BACKUP --to postgresql://...
 *       Decrypt and pipe straight into psql. Point this at a scratch database,
 *       never at the live one: the dump begins with DROP statements.
 *
 * BACKUP_PASSPHRASE must match the one used to write the file. There is no
 * recovery path if it is lost, which is the point.
 */
import { spawn } from "node:child_process";
import { createDecipheriv, scryptSync } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import { open, readFile, stat } from "node:fs/promises";
import path from "node:path";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";

import { MAGIC, SCRYPT_PARAMS, parseEnv } from "./backup.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const HEADER_BYTES = MAGIC.length + 16 + 12;
const TAG_BYTES = 16;

/**
 * Reads the header and trailing tag without loading the file into memory.
 *
 * A backup can be far larger than it is comfortable to buffer, and this runs
 * on a laptop during an incident.
 */
export async function readEnvelope(file) {
  const { size } = await stat(file);
  if (size < HEADER_BYTES + TAG_BYTES) {
    throw new Error(
      "File is too small to be a backup -- it is truncated, or not a backup at all.",
    );
  }

  const handle = await open(file, "r");
  try {
    const header = Buffer.alloc(HEADER_BYTES);
    await handle.read(header, 0, HEADER_BYTES, 0);

    const magic = header.subarray(0, MAGIC.length).toString("ascii");
    if (magic !== MAGIC) {
      throw new Error(
        `Not an expense-tracker backup (expected ${MAGIC}, found ${JSON.stringify(magic)}).`,
      );
    }

    const tag = Buffer.alloc(TAG_BYTES);
    await handle.read(tag, 0, TAG_BYTES, size - TAG_BYTES);

    return {
      salt: header.subarray(MAGIC.length, MAGIC.length + 16),
      iv: header.subarray(MAGIC.length + 16, HEADER_BYTES),
      tag,
      cipherStart: HEADER_BYTES,
      cipherEnd: size - TAG_BYTES - 1,
      size,
    };
  } finally {
    await handle.close();
  }
}

/** Builds the decryption stream for a file whose envelope has been read. */
export function openPlaintext(file, envelope, passphrase) {
  const key = scryptSync(passphrase, envelope.salt, 32, SCRYPT_PARAMS);
  const decipher = createDecipheriv("aes-256-gcm", key, envelope.iv);
  decipher.setAuthTag(envelope.tag);

  const ciphertext = createReadStream(file, {
    start: envelope.cipherStart,
    end: envelope.cipherEnd,
  });

  return { ciphertext, decipher };
}

/**
 * Pulls a few facts out of the SQL so `--check` says something useful.
 *
 * "Decrypted successfully" only proves the passphrase was right. What someone
 * actually wants to know is whether their transactions are in there.
 */
export function summarise(sql) {
  // Three sources, because no single one sees everything. The public dump
  // emits CREATE TABLE; the auth.users pass is --data-only, so the only place
  // that table name appears is its INSERT statements. Missing it would let a
  // --check report a backup as healthy when the identities are absent -- and
  // without them every foreign key fails on restore.
  const tables = [
    ...new Set([
      ...[...sql.matchAll(/^CREATE TABLE (?:IF NOT EXISTS )?([^\s(]+)/gim)].map(
        (match) => match[1],
      ),
      ...[...sql.matchAll(/^COPY ([^\s(]+)/gim)].map((match) => match[1]),
      ...[...sql.matchAll(/^INSERT INTO ([^\s(]+)/gim)].map((match) => match[1]),
    ]),
  ].sort();
  // pg_dump writes CRLF when its stdout is a Windows pipe and LF everywhere
  // else, so both have to be accepted here. Assuming LF is what made this
  // report "0 rows" for a dump that plainly had rows in it.
  const copyRows = [
    ...sql.matchAll(/^COPY [^\r\n]+FROM stdin;\r?\n([\s\S]*?)^\\\.\r?$/gim),
  ].reduce(
    (total, match) =>
      total + match[1].split(/\r?\n/).filter((line) => line.length).length,
    0,
  );
  const inserts = (sql.match(/^INSERT INTO /gim) ?? []).length;
  return { tables, rows: copyRows + inserts };
}

function usage(message) {
  console.error(`${message}\n`);
  console.error("  node scripts/restore.mjs --file BACKUP --check");
  console.error("  node scripts/restore.mjs --file BACKUP --out DUMP.sql");
  console.error("  node scripts/restore.mjs --file BACKUP --to postgresql://...");
  process.exit(2);
}

function arg(args, name) {
  const index = args.indexOf(name);
  return index === -1 ? undefined : args[index + 1];
}

async function main() {
  const args = process.argv.slice(2);
  const file = arg(args, "--file");
  const outFile = arg(args, "--out");
  const target = arg(args, "--to");
  const check = args.includes("--check");

  if (!file) usage("Which backup? Pass --file BACKUP.");
  if (!check && !outFile && !target) {
    usage("Do what with it? Pass --check, --out DUMP.sql, or --to postgresql://...");
  }

  let fileEnv = {};
  try {
    fileEnv = parseEnv(await readFile(path.join(ROOT, ".env"), "utf8"));
  } catch {
    // Environment-only is fine.
  }
  const env = { ...fileEnv, ...process.env };

  const passphrase = env.BACKUP_PASSPHRASE;
  if (!passphrase) usage("Set BACKUP_PASSPHRASE to the passphrase used to write the file.");

  const envelope = await readEnvelope(file);

  if (check) {
    // Buffered rather than streamed: the whole point is to read the SQL, and
    // holding it in memory means it never becomes a plaintext file on disk.
    const { ciphertext, decipher } = openPlaintext(file, envelope, passphrase);
    const chunks = [];
    try {
      for await (const chunk of ciphertext.pipe(decipher)) chunks.push(chunk);
    } catch (error) {
      console.error(
        "This backup did not decrypt.\n\n" +
          "Either the passphrase is wrong, or the file has been altered or\n" +
          "truncated since it was written. GCM cannot tell you which, and that\n" +
          "is deliberate -- but either way, do not rely on this file.\n\n" +
          `  ${error.message}`,
      );
      process.exit(1);
    }

    const sql = Buffer.concat(chunks).toString("utf8");
    const { tables, rows } = summarise(sql);

    console.log(`${file}`);
    console.log(`  ${(envelope.size / 1024).toFixed(1)} KiB on disk, decrypts to ${(sql.length / 1024).toFixed(1)} KiB`);
    console.log(`  authentication tag verified -- the file is intact`);
    console.log(`  ${tables.length} tables, about ${rows} rows`);
    if (tables.length) {
      console.log(`  ${tables.slice(0, 12).join(", ")}${tables.length > 12 ? ", ..." : ""}`);
    }

    if (!tables.length || !rows) {
      console.error(
        "\nThis decrypted, but there is nothing much in it. A backup that\n" +
          "restores cleanly to an empty database is still a lost database.",
      );
      process.exit(1);
    }
    return;
  }

  if (outFile) {
    const { ciphertext, decipher } = openPlaintext(file, envelope, passphrase);
    await pipeline(ciphertext, decipher, createWriteStream(outFile));
    console.log(`Wrote ${outFile}`);
    console.warn(
      "\nThat file is unencrypted, and it is every transaction in the database.\n" +
        "Delete it when you are finished with it.",
    );
    return;
  }

  const psql = env.PSQL ?? "psql";
  const child = spawn(psql, [target, "--quiet", "--set=ON_ERROR_STOP=1"], {
    stdio: ["pipe", "inherit", "inherit"],
  });

  const { ciphertext, decipher } = openPlaintext(file, envelope, passphrase);
  try {
    await pipeline(ciphertext, decipher, child.stdin);
  } catch (error) {
    console.error(`Restore failed: ${error.message}`);
    process.exit(1);
  }

  const code = await new Promise((resolve) => child.on("close", resolve));
  process.exit(code ?? 0);
}

const invoked = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invoked === fileURLToPath(import.meta.url)) {
  await main();
}
