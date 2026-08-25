# End-to-end checks

Three scripts that exercise the API against a **real Supabase project** rather
than a mock. They are not part of `mvn test` and CI does not run them, because
they need credentials and a database that CI does not have.

They exist because the unit tests cover rules in isolation and cannot tell you
whether those rules are actually wired into the paths that use them. Every one
of these scripts found a real bug the unit tests could not have caught:

| Found by | Bug |
|---|---|
| `smoke.mjs` | `"using card 4821"` never matched, because the account pattern required the sentence to *end* with the keyword |
| `ai.mjs` | `"250g of beans"` was read as an amount of ₹250, inventing a figure from a sentence that named none |
| `ai.mjs` | Redaction assertions passed against an empty string — the model was never being called, so nothing was being checked |

## Running them

Both need the API running and `../../../.env` filled in. The `.env` path is
resolved from the script, so it does not matter which directory you are in.

### `smoke.mjs` — the default path, AI off

26 checks: sign-up, accounts, categories, transactions, duplicate detection,
budgets, goals, the health score, forecasting, natural-language entry, and that
the schema is where the code thinks it is.

```bash
# terminal 1
cd apps/api && mvn spring-boot:run
# terminal 2
node apps/api/e2e/smoke.mjs
```

This is the one to run before any release. AI must be **off** — that is the
configuration nearly everyone will use.

### `ai.mjs` — the AI layer, against a scripted stand-in

27 checks. There is no API key in this project and there may never be one, so
`stub-model.mjs` impersonates an OpenAI-compatible endpoint: it records every
request and replies with whatever the current scenario says. That makes the
interesting cases — a model that hedges, that fabricates a percentage, that
returns a direction of `"maybe"` — reproducible rather than hypothetical.

```bash
# terminal 1
node apps/api/e2e/stub-model.mjs        # listens on 9099

# terminal 2
cd apps/api
AI_ENABLED=true AI_API_KEY=stub-key \
AI_BASE_URL=http://localhost:9099/v1 AI_MODEL=stub-model \
AI_MIN_CONFIDENCE=0.75 mvn spring-boot:run

# terminal 3
node apps/api/e2e/ai.mjs
```

On Windows PowerShell, set the variables with `$env:AI_ENABLED="true"` etc.
before `mvn spring-boot:run`, in the same shell.

What it proves, in order:

- the model is asked **only** when the rules fail, and never for a sentence
  with no digit in it
- `temperature` is 0, so the same alert cannot parse two different ways
- **what actually leaves the machine** is redacted: card number, account
  number, one-time code, balance, phone and email are gone; the last four
  digits, the UPI reference and the merchant survive, because those are what
  make a transaction matchable
- a summary containing a percentage that is not in the underlying figures is
  discarded and the deterministic sentence shown instead
- an alert no rule matched becomes a transaction marked `parsed_by = 'ai'`,
  with the confidence stored beside it and a note the user can see
- four rejections: confidence below the gate, `payment: false`, an amount that
  is not a figure, and a direction that is neither debit nor credit

The redaction block asserts a call was made *before* inspecting it. Without
that, an empty string passes every "is it gone?" check while testing nothing —
which is exactly what happened the first time this ran.

## Housekeeping

Both scripts create a throwaway user and delete it in a `finally` block, so a
failure part-way through still cleans up. If one is killed with Ctrl-C the user
survives; `smoke.mjs` reports the orphan count on its next run.
