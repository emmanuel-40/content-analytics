# Architecture

Spring Boot 4 monolith (Java 21): controllers -> services -> JPA repositories ->
PostgreSQL; Redis for idempotency, rate limits, lockout and cache; S3-compatible storage; Brevo/Gemini over HTTP; STOMP
WebSocket for job progress. Auth is JWT-in-HttpOnly-cookie for every caller; Google
sign-in is a provisioning source into the same `users` table, not a second auth
system (see `docs/AUTHENTICATION.md`).

## Contents

- [Modules](#modules)
- [Request lifecycle](#request-lifecycle)
- [Data model](#data-model)
- [Cross-cutting](#cross-cutting)

## Modules

| Package | Owns |
|---|---|
| `controller` | `AuthController` (register/login/refresh/logout/tokens + `GET /providers`), `ProfileController`, `DocumentController` (+ `DocumentWebSocketController`), `HealthController`, `SubscriptionController`. |
| `service` | `AuthService` (incl. `issueTokens`, the shared mint point), `OAuthAccountProvisioner` (find/link/create), `ProfileService`, document + analysis pipeline (`AiAnalysisService` over `GeminiService`/`AnthropicAiProvider`), `EmailService` (Brevo), `ImageService`, `StorageService` (selects `local` / `s3` / `supabase` alias via `S3StorageService`), `RefreshTokenRevocationService`, queue (`AsyncJobQueue` / `RabbitMqJobQueue`). |
| `security` | `JwtAuthenticationFilter` (per-request cookie auth), `GoogleOAuth2UserService` / `GoogleOidcUserService` (verified-email gate for OAuth2 and OIDC), `OAuth2LoginSuccessHandler` / `OAuth2LoginFailureHandler` (cookies+redirect / reason vocabulary), `CookieUtil`, `TokenGenerationUtil`. `SecurityConfig` wires JWT + `oauth2Login` and the public matchers. |
| `entity` / `repository` | `User` (+ `AuthProvider`: `LOCAL`/`GOOGLE`), `RefreshToken` (single-use rotation), documents, health metrics, verification/reset tokens, profiles, subscriptions/credits/audit. |
| `exception` | `GlobalExceptionHandler` error shape; `AccountLinkingException` (409 on sub-mismatch). |
| `config` | CORS, Jackson, async/scheduling, WebSocket, storage/queue conditionals. `PasswordConfig` holds the `PasswordEncoder` bean alone so `SecurityConfig` -> OAuth handlers -> `AuthService` -> encoder never forms a cycle. |

### Layer and dependency view

```mermaid
flowchart TB
  subgraph Web
    REST[REST controllers /api/**]
    STOMP[STOMP controllers /ws]
    WSInt[WebSocketInterceptor<br/>CONNECT auth + SUBSCRIBE ownership]
  end
  subgraph Security
    JwtFilter[JwtAuthenticationFilter]
    Session[TokenSessionValidator<br/>token_version + user status]
  end
  subgraph Domain["Services (@Transactional boundary)"]
    AuthSvc[Auth + RefreshTokenRevocation]
    CreditSvc[CreditService<br/>FOR UPDATE + ledger]
    DocSvc[DocumentService<br/>claim + guarded transitions]
    Worker[DocumentProcessingService<br/>bounded retries]
    PaySvc[PaymentService<br/>HMAC then REQUIRES_NEW settle]
  end
  subgraph Infra
    JPA[JPA repositories]
    Redis[(Redis<br/>cache, idempotency, rate limit, lockout)]
    Storage[StorageService<br/>local / S3 / Supabase]
    HTTP[Paystack, Brevo, Gemini]
    MQ[JobQueue / EmailQueue: async / RabbitMQ]
    Outbox[(email_outbox<br/>transactional mail queue)]
  end
  PG[(PostgreSQL)]

  REST --> JwtFilter --> Session --> AuthSvc & CreditSvc & DocSvc & PaySvc
  STOMP --> WSInt --> Session
  AuthSvc & CreditSvc & DocSvc & PaySvc --> JPA --> PG
  AuthSvc --> Outbox --> PG
  Outbox --> MQ --> HTTP
  AuthSvc --> Redis
  CreditSvc --> Redis
  DocSvc --> MQ --> Worker --> DocSvc
  Worker --> Storage --> HTTP
  PaySvc --> HTTP
  Worker --> STOMP
```

> **Cycle break:** `DocumentService → JobQueue → Worker → DocumentService` is a
> constructor cycle. Both queue implementations inject the worker via Spring's
> `ObjectProvider<DocumentProcessingService>` so it resolves lazily at enqueue time.

## Request lifecycle

```mermaid
sequenceDiagram
  participant B as Browser
  participant CF as CORS + CSRF filters
  participant JF as JwtAuthenticationFilter
  participant TV as TokenSessionValidator
  participant C as Controller
  participant S as Service (@Transactional)
  participant DB as PostgreSQL

  B->>CF: HTTP /api/...
  CF->>JF: filter chain (OAuth legs permitAll)
  JF->>TV: validate access cookie (if present)
  TV->>DB: load user + compare token_version
  JF->>C: SecurityContext set / anonymous
  C->>S: domain call
  S->>DB: JPA via Hikari (row locks where needed)
  S-->>B: JSON / cookies / redirect
```

Analysis uploads additionally emit STOMP progress on `/ws` while the job runs
on RabbitMQ by default (`app.queue.type=async` switches to the in-process fallback).

Email never performs I/O inside the request transaction. `AuthService` and
`ProfileService` write an `email_outbox` row with the same commit as the token/password
change; `EmailOutboxPublisher` claims it with a conditional UPDATE and hands it to
RabbitMQ. The consumer reloads the row, calls Brevo, and sets `SENT` only through another
conditional UPDATE, so redeliveries cannot send twice. Failures return the row to
`PENDING` up to `app.email.max-attempts`, then mark it `FAILED`.

## Data model

`users` carries `provider` (`LOCAL` default backfill), nullable `provider_subject`
(Google `sub`), nullable `password` (NULL = Google-only until a reset adds one),
and `token_version` for server-side session invalidation. The unique index
`(provider, provider_subject)` is NULL-safe, so unlinked `LOCAL` rows never
collide while each Google identity maps to one row.

```mermaid
erDiagram
  USERS ||--o{ REFRESH_TOKENS : rotates
  USERS ||--o| SUBSCRIPTIONS : owns
  USERS ||--o{ CREDIT_TRANSACTIONS : ledger
  USERS ||--o{ PAYMENT_TRANSACTIONS : checkouts
  USERS ||--o{ DOCUMENTS : uploads
  REFRESH_TOKENS ||--o{ REFRESH_TOKENS : "family_id successor"

  USERS {
    uuid id PK
    varchar email UK
    varchar provider
    varchar provider_subject
    int token_version
  }
  REFRESH_TOKENS {
    uuid id PK
    uuid family_id
    varchar token_hash UK
    varchar status
    boolean used
    boolean revoked
  }
  SUBSCRIPTIONS {
    uuid id PK
    uuid user_id FK,UK
    int purchased_credits
    int monthly_credit_allowance
  }
  PAYMENT_TRANSACTIONS {
    uuid id PK
    varchar reference UK
    varchar status
    int amount_cents
  }
  CREDIT_TRANSACTIONS {
    uuid id PK
    varchar reference_id
    varchar type
  }
  DOCUMENTS {
    uuid id PK
    varchar status
    bigint version
  }
```

### Document state transitions

```mermaid
stateDiagram-v2
  [*] --> UPLOADED : upload committed
  UPLOADED --> PROCESSING : atomic claim
  PROCESSING --> COMPLETED : guarded result write
  PROCESSING --> UPLOADED : retry budget left
  PROCESSING --> FAILED : retries exhausted
  UPLOADED --> FAILED : publish failed
  FAILED --> UPLOADED : user retry
```

Migrations `V1`-`V10` own the tables and indexes listed above; `V12` adds the
payment-transaction ledger (PENDING-first, idempotent via provider reference),
`V13` drops two decorative profile columns, `V14` adds CHECK constraints, `V15`
adds JPA `@Version` columns plus unique ledger indexes, `V16` adds the
`refresh_tokens` rotation table, `V17` adds `documents.version`, `V18`
renames the payment amount column to USD cents, `V19` tracks whether the
source upload was removed after analysis, and `V20` adds the email outbox.

## Cross-cutting

- **Config**: `application.properties` (local, plain values) <- overridden by
  `application-prod.properties` (Render env-var mapping) <- `application-test.properties`
  (self-contained H2, never reads the other two).
- **Migrations**: Flyway `V1`-`V20`, `validate` mode everywhere - schema changes
  ship as versioned SQL, never Hibernate DDL.
- **Observability**: Actuator health/info/prometheus; `HealthCheckService` aggregates
  db/redis/storage probes for Render.
- **Tests**: `@SpringBootTest` + MockMvc on H2 (deterministic - in-memory DB,
  injected `Clock`, no network/sleeps); the OAuth suite asserts our rules without
  network. JaCoCo produces a coverage report; the `check` gate is intentionally
  disabled until coverage rises (see `pom.xml`).
