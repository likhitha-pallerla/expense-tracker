import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { scrubPath } from "./analytics.ts";

/**
 * These tests exist because {@link scrubPath} is a privacy control, not a
 * formatting helper. Every case below is something that would otherwise be
 * sent to a third party.
 *
 * Run with `npm test`. Node's own runner is used rather than a framework:
 * the module under test is a pure function, and this keeps the web app free
 * of a test-only dependency tree.
 */
describe("scrubPath", () => {
  it("replaces a transaction id", () => {
    // Sending these would build a record of which rows a person opens.
    assert.equal(
      scrubPath("/transactions/9f8e7d6c-5b4a-4321-8765-1a2b3c4d5e6f"),
      "/transactions/:id",
    );
  });

  it("replaces an id in the middle of a path", () => {
    assert.equal(
      scrubPath("/accounts/9f8e7d6c-5b4a-4321-8765-1a2b3c4d5e6f/transactions"),
      "/accounts/:id/transactions",
    );
  });

  it("replaces every id when there is more than one", () => {
    assert.equal(
      scrubPath(
        "/accounts/9f8e7d6c-5b4a-4321-8765-1a2b3c4d5e6f/budgets/1a2b3c4d-5e6f-4a1b-9c8d-7e6f5a4b3c2d",
      ),
      "/accounts/:id/budgets/:id",
    );
  });

  it("matches an uppercase id", () => {
    // Postgres returns lowercase, but a hand-typed or copied URL may not, and
    // a case-sensitive match would leak the ones that differ.
    assert.equal(
      scrubPath("/transactions/9F8E7D6C-5B4A-4321-8765-1A2B3C4D5E6F"),
      "/transactions/:id",
    );
  });

  it("replaces a numeric segment", () => {
    // These are dates and amounts more often than they are page numbers.
    assert.equal(scrubPath("/forecast/2026/03"), "/forecast/:n/:n");
  });

  it("leaves an ordinary route alone", () => {
    // Scrubbing that flattens every page into one is useless as analytics.
    assert.equal(scrubPath("/dashboard"), "/dashboard");
    assert.equal(scrubPath("/transactions"), "/transactions");
  });

  it("keeps a word that merely looks like an id", () => {
    assert.equal(scrubPath("/review/duplicates"), "/review/duplicates");
  });

  it("handles the root path", () => {
    assert.equal(scrubPath("/"), "/");
  });

  it("does not mistake a short hex word for an id", () => {
    // "cafe" is hex, but it is not a uuid, and a route named for a feature
    // should stay readable.
    assert.equal(scrubPath("/budgets/cafe"), "/budgets/cafe");
  });
});
