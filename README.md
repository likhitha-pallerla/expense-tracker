# Expense Tracker

Automatically capture expenses by reading **email** (Gmail + Outlook) and **Android SMS**
(bank/UPI alerts), parse them into structured transactions, **remove duplicates**, and
surface spending insights — on web first, then mobile.

Read-only access to your mail. No bank credentials are ever stored.

## Stack

| Layer | Choice |
|---|---|
| Web | Next.js (App Router) + TypeScript + Tailwind |
| Mobile | React Native + Expo |
| API | Java 21 + Spring Boot 3 |
| Database | PostgreSQL on Supabase |
| Auth | Supabase Auth |
| Storage | Supabase Storage |
| Web hosting | Vercel |
| API hosting | Render / Railway |
| Analytics | PostHog |
| Errors | Sentry |

## Layout

```
apps/web              Next.js web app
apps/api              Spring Boot API
  src/main/resources/db/migration   Flyway SQL migrations (ship inside the jar)
apps/mobile           Expo app (Phase 4)
packages/shared-types Shared TypeScript types
docs/PLAN.md          Development plan and phasing
```

## Getting started

Prerequisites: JDK 21, Maven 3.9+, Node 20+, a Supabase project.

```bash
cp .env.example .env                      # fill in your Supabase values
cp apps/web/.env.local.example apps/web/.env.local
```

Run the API (applies Flyway migrations on startup):

```bash
cd apps/api && mvn spring-boot:run        # http://localhost:8080
```

Run the web app in a second terminal:

```bash
cd apps/web && npm install && npm run dev # http://localhost:3000
```

Check it works:

```bash
curl http://localhost:8080/api/health     # {"status":"ok", ...}
```

Then open <http://localhost:3000>, create an account, and the API provisions your
default categories and a Cash account on first sign-in.

### Tests

```bash
cd apps/api && mvn test                   # JUnit
cd apps/web && npx tsc --noEmit && npm run lint && npm run build
```

## How authentication works

Supabase signs access tokens with **ES256** and publishes the public key at
`/auth/v1/.well-known/jwks.json`. The API verifies tokens against that JWKS endpoint
and validates the issuer and the `authenticated` audience. The legacy shared HS256
JWT secret is **not** used.

The API connects to Postgres as an owner role that **bypasses RLS**, so every query
must filter by the authenticated user id. RLS is the second line of defence, covering
direct client access with the anon key.

## Documentation

- [`docs/PLAN.md`](docs/PLAN.md) — scope, architecture, data model, dedup strategy, phasing.
- [`docs/FEATURES.md`](docs/FEATURES.md) — full feature catalogue with V1/V2/V3 phasing.
- [`docs/BLUEPRINT-REVIEW.md`](docs/BLUEPRINT-REVIEW.md) — review of the original product blueprint.

## Security

Secrets live in `.env` and `apps/web/.env.local`, both git-ignored. Never commit
credentials, OAuth client secrets, or the Supabase `service_role` key.
