/**
 * Tests for the pure helpers in the backup and restore scripts.
 *
 * These deliberately do not touch a database. The parts that need one were
 * proved by taking a real backup and restoring it into an empty database --
 * which is the only thing that actually proves a backup, and which found four
 * separate defects that decrypting alone would have passed. What is left here
 * is the logic that can go wrong quietly: the repository guard, the connection
 * string rewrite, and the summary that tells you whether a backup has anything
 * in it.
 */

import assert from "node:assert/strict";
import path from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

import { backupName, isInsideRepo, jdbcToLibpq } from "./backup.mjs";
import { summarise } from "./restore.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

describe("isInsideRepo", () => {
  it("rejects the repository itself", () => {
    assert.equal(isInsideRepo(ROOT), true);
  });

  it("rejects a directory inside the repository", () => {
    assert.equal(isInsideRepo(path.join(ROOT, "backups")), true);
    assert.equal(isInsideRepo(path.join(ROOT, "apps", "web", "tmp")), true);
  });

  it("allows a directory outside the repository", () => {
    assert.equal(isInsideRepo(path.join(ROOT, "..", "backups")), false);
  });

  it("is not fooled by a sibling whose name starts with the repo name", () => {
    // `startsWith(root)` without the separator would call this one inside.
    // The repository is public; a false negative here publishes a database.
    assert.equal(isInsideRepo(ROOT + "-backups"), false);
  });

  it("resolves relative paths before deciding", () => {
    const inside = path.relative(process.cwd(), path.join(ROOT, "docs"));
    assert.equal(isInsideRepo(inside), true);
  });
});

describe("jdbcToLibpq", () => {
  it("rewrites a JDBC URL into a libpq one", () => {
    assert.equal(
      jdbcToLibpq("jdbc:postgresql://db.example.com:5432/postgres", "pg", "s3cret"),
      "postgresql://pg:s3cret@db.example.com:5432/postgres",
    );
  });

  it("drops JDBC query parameters, which libpq spells differently", () => {
    assert.equal(
      jdbcToLibpq("jdbc:postgresql://h:5432/postgres?sslmode=require", "u", "p"),
      "postgresql://u:p@h:5432/postgres",
    );
  });

  it("percent-encodes credentials", () => {
    // Supabase passwords routinely contain @ and /. Unencoded, the @ ends the
    // userinfo early and libpq connects to the wrong host -- or, worse, to a
    // host named after part of the password.
    const url = jdbcToLibpq("jdbc:postgresql://h:5432/postgres", "u@x", "p@ss/w:rd");
    assert.equal(url, "postgresql://u%40x:p%40ss%2Fw%3Ard@h:5432/postgres");
    assert.equal(new URL(url).hostname, "h");
    assert.equal(new URL(url).password, encodeURIComponent("p@ss/w:rd"));
  });

  it("refuses anything that is not a JDBC Postgres URL", () => {
    for (const bad of [undefined, "", "postgresql://h/db", "jdbc:mysql://h/db"]) {
      assert.throws(() => jdbcToLibpq(bad, "u", "p"), /not a JDBC Postgres URL/);
    }
  });
});

describe("backupName", () => {
  it("is sortable and has no characters Windows rejects", () => {
    const name = backupName(new Date("2026-08-25T06:21:27.512Z"));
    assert.equal(name, "expense-tracker-20260825T062127Z.sql.enc");
    assert.doesNotMatch(name, /[:*?"<>|]/);
  });

  it("sorts chronologically as plain text", () => {
    const earlier = backupName(new Date("2026-08-25T06:00:00Z"));
    const later = backupName(new Date("2026-09-01T06:00:00Z"));
    assert.ok(earlier < later);
  });
});

describe("summarise", () => {
  const dump = [
    "COPY public.accounts (id, name) FROM stdin;",
    "1\tChecking",
    "2\tSavings",
    "\\.",
    "",
    "COPY public.transactions (id, amount) FROM stdin;",
    "1\t12.50",
    "\\.",
    "",
  ];

  it("counts tables and rows", () => {
    const summary = summarise(dump.join("\n"));
    assert.equal(summary.tables.length, 2);
    assert.deepEqual(summary.tables, ["public.accounts", "public.transactions"]);
    assert.equal(summary.rows, 3);
  });

  it("counts rows in a CRLF dump", () => {
    // pg_dump writes CRLF when its stdout is a Windows pipe. Splitting on "\n"
    // alone left a stray \r on every terminator, the \\. line never matched,
    // and a backup with 21 rows in it reported 0 -- which reads exactly like a
    // backup of an empty database.
    const summary = summarise(dump.join("\r\n"));
    assert.equal(summary.rows, 3);
    assert.equal(summary.tables.length, 2);
  });

  it("does not count the terminator or blank lines as rows", () => {
    const summary = summarise(
      "COPY public.accounts (id) FROM stdin;\n1\n\\.\n\n",
    );
    assert.equal(summary.rows, 1);
  });

  it("counts column-insert rows, which is how auth.users is dumped", () => {
    const summary = summarise(
      "INSERT INTO auth.users (id, email) VALUES ('a', 'x@y.z');\n" +
        "INSERT INTO auth.users (id, email) VALUES ('b', 'p@q.r');\n",
    );
    assert.ok(summary.tables.includes("auth.users"));
    assert.equal(summary.rows, 2);
  });

  it("reports an empty dump as empty", () => {
    // The caller exits non-zero on this. A backup that restores cleanly into
    // an empty database is still a lost database, and it is the failure that
    // looks most like success.
    const summary = summarise("-- PostgreSQL database dump\n\n");
    assert.equal(summary.tables.length, 0);
    assert.equal(summary.rows, 0);
  });
});
