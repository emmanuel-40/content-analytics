# Local development

Everything local lives in `src/main/resources/application.properties` - edit it directly.
No `.env`, no flags, no setup script: `./mvnw spring-boot:run` boots against local
Postgres + Redis with dev-safe defaults.

## Contents

- [Prerequisites](#prerequisites)
- [Run](#run)
- [Local services](#local-services)
- [Optional features](#optional-features)
- [Tests](#tests)
- [Conventions](#conventions)

## Prerequisites

- JDK 21, Maven wrapper included (`./mvnw`).
- PostgreSQL 15+ with database `content_analytics_db` (default user `postgres` /
  password `postgres`; change the three `spring.datasource.*` lines if yours differ).
- Redis on `localhost:6379` (empty password by default).
- Optional: local RabbitMQ (`guest`/`guest`) only if you flip `app.queue.type` to `rabbitmq`.

## Run

```bash
./mvnw spring-boot:run          # API on http://localhost:8080
./mvnw test                     # full suite, H2 in-memory, no credentials
```

Flyway migrates `V1`-`V11` automatically; Hibernate is `validate`-only, so a
schema mismatch fails fast instead of silently altering tables.

To see SQL while debugging, create the git-ignored
`src/main/resources/application-local.properties` with the two Hibernate
logger lines described in the `LOGGING` block of `application.properties`.

## Local services

| Service | Default | Notes |
|---|---|---|
| Postgres | `localhost:5432/content_analytics_db` | Create the DB once; Flyway owns the schema. |
| Redis | `localhost:6379` | Cache; app degrades if down but auth sessions are JWTs, not server state. |
| Storage | `./uploads` via `app.storage.type=local` | S3 keys stay placeholder locally; flipping to `s3` dials the real bucket. |
| Mail | disabled (empty `app.brevo.api-key`) | Register still works; the verification link is logged, not sent. |
| AI | `gemini` with empty key | Analysis falls back to basic; paste `app.gemini.api-key` to use real AI. |
| Google | disabled (`local-dev-not-configured`) | `providers` reports false; paste the client id/secret + register `http://localhost:8080/login/oauth2/code/google` to try the real flow. |

## Optional features

- **AI**: `app.ai.provider=gemini|anthropic|none`; keys paste straight into
  `application.properties` (never commit a real one - this repo is shared).
- **Google**: see `docs/GOOGLE_OAUTH_SETUP.md` sections 2-4 (local rows).
- **RabbitMQ**: set `app.queue.type=rabbitmq` and run a local broker to exercise
  the queue path instead of in-process async.

## Tests

- Unit + slice tests run on H2 (`application-test.properties` - self-contained,
  reads no local/prod values).
- `GoogleOAuthIntegrationTest` covers the provisioner rules, the providers flag,
  the failure-handler vocabulary, and the password-login guard - all without
  network access. Fixture emails contain `goauth-test` so cleanup cannot touch
  other suites.
- JaCoCo gate is 85 percent (`./mvnw verify`).

## Conventions

- Java 21, constructor injection (`@RequiredArgsConstructor`), `java.time` only.
- New tables need a Flyway `V<N>__...sql` migration (Postgres dialect) + entity +
  repository + service + controller + test - never rely on `ddl-auto` beyond validate.
- Keep `application.properties` local-plain and `application-prod.properties`
  Render-mapped; never hardcode a secret in either. Test values stay in
  `application-test.properties` only.
