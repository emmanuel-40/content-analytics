# Architecture

Spring Boot 4 monolith (Java 21): controllers -> services -> JPA repositories ->
PostgreSQL; Redis for cache; S3-compatible storage; Brevo/Gemini over HTTP; STOMP
WebSocket for job progress. Auth is JWT-in-HttpOnly-cookie for every caller; Google
sign-in is a provisioning source into the same `users` table, not a second auth
system (see `docs/AUTHENTICATION.md`).

## Contents

- [Modules](#modules)
- [Request lifecycle](#request-lifecycle)
- [Data model (V11)](#data-model-v11)
- [Cross-cutting](#cross-cutting)

## Modules

| Package | Owns |
|---|---|
| `controller` | `AuthController` (register/login/refresh/logout/tokens + `GET /providers`), `ProfileController`, `DocumentController` (+ `DocumentWebSocketController`), `HealthController`, `SubscriptionController`. |
| `service` | `AuthService` (incl. `issueTokens`, the shared mint point), `OAuthAccountProvisioner` (find/link/create), `ProfileService`, document + analysis pipeline (`AiAnalysisService` over `GeminiService`/`AnthropicAiProvider`), `EmailService` (Brevo), `ImageService`, storage (`LocalStorageService` / `S3StorageService` behind `StorageService`), queue (`AsyncJobQueue` / `RabbitMqJobQueue`). |
| `security` | `JwtAuthenticationFilter` (per-request cookie auth), `GoogleOAuth2UserService` (verified-email gate), `OAuth2LoginSuccessHandler` / `OAuth2LoginFailureHandler` (cookies+redirect / reason vocabulary), `CookieUtil`, `TokenGenerationUtil`. `SecurityConfig` wires JWT + `oauth2Login` and the public matchers. |
| `entity` / `repository` | `User` (+ `AuthProvider`: `LOCAL`/`GOOGLE`), documents, health metrics, tokens, profiles, subscriptions/credits/audit. |
| `exception` | `GlobalExceptionHandler` error shape; `AccountLinkingException` (409 on sub-mismatch). |
| `config` | CORS, Jackson, async/scheduling, WebSocket, storage/queue conditionals. `PasswordConfig` holds the `PasswordEncoder` bean alone so `SecurityConfig` -> OAuth handlers -> `AuthService` -> encoder never forms a cycle. |

## Request lifecycle

```
Browser -> CORS filter -> CsrfCookieFilter -> JwtAuthenticationFilter
  (validates access cookie, sets SecurityContext; OAuth handshake legs
   /oauth2/** and /login/oauth2/** bypass it as permitAll)
  -> controller -> service (@Transactional at the service boundary)
  -> JPA/Hibernate -> Postgres (Hikari) ; Redis/S3/Brevo/Gemini as needed
  -> GlobalExceptionHandler error shape on failure
```

Analysis uploads additionally emit STOMP progress on `/ws` while the job runs
async (or via RabbitMQ when `app.queue.type=rabbitmq`).

## Data model (V11)

`users` gained `provider` (`LOCAL` default backfill) + nullable `provider_subject`
(Google `sub`) + nullable `password` (NULL = Google-only, added via forgot-password
later). Unique index `(provider, provider_subject)` is NULL-safe, so the many
unlinked `LOCAL` rows never collide while each Google identity maps to one row.
Migrations `V1`-`V10` own the remaining tables (documents, health metrics,
verification/reset tokens, profiles, avatars, password history,
subscriptions/credits/audit) plus performance indexes.

## Cross-cutting

- **Config**: `application.properties` (local, plain values) <- overridden by
  `application-prod.properties` (Render env-var mapping) <- `application-test.properties`
  (self-contained H2, never reads the other two).
- **Migrations**: Flyway `V1`-`V11`, `validate` mode everywhere - schema changes
  ship as versioned SQL, never Hibernate DDL.
- **Observability**: Actuator health/info/prometheus; `HealthCheckService` aggregates
  db/redis/storage probes for Render.
- **Tests**: `@SpringBootTest` + MockMvc on H2; OAuth suite asserts our rules
  without network; JaCoCo 85 percent gate.
