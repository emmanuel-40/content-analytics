# Content Analytics - Backend

Spring Boot 4 / Java 21 REST API behind the Content Analytics SPA:
JWT-cookie auth (password + **Google sign-in**), document upload and AI
analysis, health metrics, subscriptions/credits, email (Brevo), WebSocket
progress - backed by PostgreSQL (Supabase-friendly), Redis and S3-compatible
storage.

> **Frontend:** the SPA lives in a separate repository.
> <!-- FRONTEND-REPO-PLACEHOLDER: replace the URL below with the real repo when created. -->
> **Frontend repo:** `https://github.com/<org-or-user>/<frontend-repo>` _(placeholder - update when the repo exists)_
>
> The only backend-to-frontend contracts are:
> `NEXT_PUBLIC_API_URL` maps to this API origin, the `/api/auth/providers`
> response, and the `/auth/callback?status=...&reason=...` redirect documented in
> [`docs/AUTHENTICATION.md`](docs/AUTHENTICATION.md).

## Table of contents

- [Stack](#stack)
- [Quickstart](#quickstart)
- [Configuration](#configuration)
- [Google sign-in (OAuth2)](#google-sign-in-oauth2)
- [API overview](#api-overview)
- [Database and migrations](#database-and-migrations)
- [Tests](#tests)
- [Deployment](#deployment)
- [Repository map](#repository-map)
- [Further docs](#further-docs)

## Stack

| Layer | Choice |
|---|---|
| Runtime | Java 21, Spring Boot 4.0.6 (Maven `mvnw`) |
| Auth | Spring Security + JWT in HttpOnly cookies; `spring-boot-starter-oauth2-client` for Google (authorization-code flow only) |
| Data | Spring Data JPA + Flyway, PostgreSQL (local dev, Supabase in prod) |
| Cache / realtime | Redis (cache), STOMP WebSocket (`/ws`) |
| Storage | S3-compatible SDK, Supabase Storage in prod, local filesystem for dev |
| AI | Google Gemini (free tier, default), Anthropic optional, `none` to disable |
| Email | Brevo (300/day free), verification + password-reset links |
| Observability | Actuator (health/info/prometheus), JaCoCo (85 percent gate) |

## Quickstart

Prerequisites: JDK 21, Maven (wrapper included), PostgreSQL 15+ and Redis running locally.

```bash
# Local dev needs no setup step: values are already in
# src/main/resources/application.properties. Edit that file directly
# if your Postgres/Redis differ, then:
./mvnw spring-boot:run

# Prod profile (Render: Supabase + Upstash, env vars from the dashboard)
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run
```

The API listens on `http://localhost:8080` by default.

## Configuration

Local values live **directly in `src/main/resources/application.properties`** - no `.env`, no flags,
just edit the file. Production values live in `src/main/resources/application-prod.properties`,
which reads every secret from an environment variable set in the **Render dashboard**
(Service -> Environment). Nothing secret is hardcoded in the prod file.

| Where | What |
|---|---|
| `application.properties` | Local Postgres (`localhost:5432/content_analytics_db`), local Redis, dev JWT secret, `http://localhost:5173` frontend/CORS, Google placeholder (disabled until you paste credentials), empty Gemini/Brevo keys (features degrade gracefully) |
| `application-prod.properties` | Same keys mapped to Render env vars: `DATABASE_URL` (Supabase pooler, keep `?prepareThreshold=0`), `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST/PORT/PASSWORD` (Upstash), `JWT_SECRET` (64+ bytes, `openssl rand -base64 64`), `FRONTEND_URL` + CORS/websocket origins (public SPA URL), `GOOGLE_CLIENT_ID/SECRET` (optional), Supabase S3 keys + endpoint, `GEMINI_API_KEY`, `BREVO_API_KEY`, `EMAIL_FROM_ADDRESS` |
| `application-test.properties` | Self-contained H2 suite, never touched by either of the above |

## Google sign-in (OAuth2)

Passwordless sign-in via the Google authorization-code flow. Fully optional and free,
setup takes about 5 minutes in Google Cloud Console.

- **Start:** `GET /oauth2/authorization/google` sends the browser to Google.
  (Spring Security builds the URL; `state` + `nonce` are validated automatically.)
- **Success:** `302 <FRONTEND_URL>/auth/callback?status=success` with the same
  access + refresh HttpOnly cookies `POST /api/auth/login` sets.
- **Failure:** `302 <FRONTEND_URL>/auth/callback?status=error&reason=<code>`
  where `<code>` is one of `access_denied | link_conflict | email_unverified |
  state_mismatch | client_not_configured | error` (fixed vocabulary, nothing
  attacker-controlled is reflected into the URL).
- **Capability flag:** `GET /api/auth/providers` returns `{"google": true|false}`.
  The SPA calls this pre-login to decide whether to render the button.

Account rules: unverified Google emails are rejected; first sign-in links a
matching local account by verified email or creates a passwordless row; a row
already linked to a *different* Google `sub` is never re-pointed (409 conflict).

To enable locally, paste your Google Cloud Console credentials into
`application.properties` (OAuth client ID, type Web application) and register:
`http://localhost:8080/login/oauth2/code/google`. On Render, set `GOOGLE_CLIENT_ID` /
`GOOGLE_CLIENT_SECRET` in the dashboard and register:
`https://<backend-host>/login/oauth2/code/google`.

Full checklist: **[`docs/GOOGLE_OAUTH_SETUP.md`](docs/GOOGLE_OAUTH_SETUP.md)**.
Auth design + frontend contract: **[`docs/AUTHENTICATION.md`](docs/AUTHENTICATION.md)**.

## API overview

Base path `/api`. Auth cookies are HttpOnly; CSRF token via `GET /api/auth/csrf`.

| Area | Endpoints |
|---|---|
| Auth | `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/verify-email`, `POST /api/auth/forgot-password`, `POST /api/auth/reset-password`, `GET /api/auth/verify-token/**`, `GET /api/auth/csrf`, **`GET /api/auth/providers`** (OAuth flag) |
| OAuth handshake | `GET /oauth2/authorization/google`, `GET /login/oauth2/code/google` (browser-driven, no JSON) |
| Profile | `GET/PUT /api/profile`, password change, avatar upload/signed URLs |
| Documents | upload/list/download/delete, analysis results |
| Health metrics | CRUD + aggregates |
| Subscriptions/credits | plans, usage, audit |
| Ops | `/actuator/health`, `/actuator/info`, `/actuator/prometheus` |

## Database and migrations

Flyway (`spring.jpa.hibernate.ddl-auto=validate`) runs `V1` through `V11` from
`src/main/resources/db/migration` on boot.

- `V1-V10`: users, documents, health metrics, email/password tokens, profiles,
  avatars, password history, subscriptions/credits/audit, performance indexes.
- **`V11__Add_Google_OAuth_Support.sql`**: adds `users.provider`
  (`LOCAL`/`GOOGLE`, backfills existing rows to `LOCAL`), nullable
  `users.provider_subject` (Google `sub`), makes `users.password` nullable
  (Google-only accounts have none), unique index on
  `(provider, provider_subject)` - NULL-safe so local rows never collide.

## Tests

```bash
./mvnw test            # full suite (H2 in-memory, no credentials needed)
```

Google coverage (`GoogleOAuthIntegrationTest`): provisioner find/link/create
rules including sub-mismatch refusal, providers-flag shape, failure-handler
reason vocabulary, password-login guard for passwordless rows. OAuth tests
never hit the network - the Google side (consent/code/userinfo) is outside
what we own.

## Deployment

Prod profile (`SPRING_PROFILES_ACTIVE=prod`): Supabase Postgres via the **transaction pooler**
(`:6543`, keep `?prepareThreshold=0`), Upstash Redis, Supabase Storage over the S3 gateway
(bucket private; downloads stream through the API), Brevo email. On Render:

1. Set env vars in the dashboard (Service -> Environment) per the list at the top
   of `application-prod.properties`, **plus** `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`,
   `FRONTEND_URL` = public SPA URL, `JWT_SECRET` (64+ bytes).
2. Register the prod redirect URI in Google Cloud Console:
   `https://<backend-host>/login/oauth2/code/google`
   (else `redirect_uri_mismatch`; `{baseUrl}` derives the URI from
   `X-Forwarded-*`, which is why `server.forward-headers-strategy=framework` matters).
3. Flyway migrates on boot; health check `/actuator/health`.

## Repository map

```
src/main/java/com/contentanalytics/
  config/SecurityConfig.java          JWT filter + oauth2Login wiring
  security/
    GoogleOAuth2UserService.java      verified-email gate, delegates to provisioner
    OAuthAccountProvisioner.java      find-or-create / link / create-passwordless
    OAuth2LoginSuccessHandler.java    mint JWT pair, cookies, redirect to /auth/callback?status=success
    OAuth2LoginFailureHandler.java    fixed reason vocabulary, redirect to /auth/callback?status=error
    JwtAuthenticationFilter.java      per-request cookie auth (unchanged by OAuth)
  controller/AuthController.java      /api/auth/* including GET /providers
  service/AuthService.java            register/login/refresh + issueTokens() shared by OAuth
  entity/User.java + AuthProvider.java  provider + providerSubject columns
  exception/AccountLinkingException.java  sub-mismatch signal (becomes 409)
src/main/resources/db/migration/V11__Add_Google_OAuth_Support.sql
src/test/java/com/contentanalytics/security/GoogleOAuthIntegrationTest.java
```

## Further docs

- [`docs/AUTHENTICATION.md`](docs/AUTHENTICATION.md) - auth design: password +
  Google flows, cookies, frontend `/auth/callback` contract, security rules.
- [`docs/GOOGLE_OAUTH_SETUP.md`](docs/GOOGLE_OAUTH_SETUP.md) - step-by-step
  Google Cloud Console checklist + local/prod verification.
- [`docs/API.md`](docs/API.md) - endpoint reference.
- [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) - production runbook (Render +
  Supabase + Upstash).
- [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) - local dev guide.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) - module overview.

