# Backend code map

This is a plain-English inventory of the current backend working tree, including
the uncommitted V16-V18 migrations and refresh-token/document-concurrency code.
It is meant to answer "what does each file actually do?" without opening every
file.

Counts: **149 main Java files**, **22 test Java files**, **18 Flyway migrations**,
3 Spring property files, 7 docs, 1 CI workflow, 2 Compose files, 1 Dockerfile.

---

## 1. How the whole thing fits together

```text
Browser
  -> AuthController / DocumentController / PaymentController / ProfileController
  -> Security filter chain (JWT or Google OAuth cookie -> principal = userId)
  -> Services (business rules, DB transactions, provider calls)
  -> Repositories -> Postgres (Flyway V1-V18 owns the schema)
  -> Redis (optional: rate limits, idempotency, login lockout)
  -> RabbitMQ or in-process @Async (document jobs)
  -> WebSocket STOMP /ws (job progress pushed to the browser)
  -> Paystack / Brevo / Gemini / Supabase Storage (external HTTP/S3)
```

Important rules baked into the code:

- **Postgres constraints, row locks and status guards are authoritative.** Redis
  only speeds things up; if Redis is down, requests fail open and the DB still
  decides.
- **Webhook HMAC is verified before any dedupe claim.** No attacker-controlled
  JSON can occupy an idempotency key before the signature is checked.
- **Provider HTTP, S3 I/O and queue publishes happen outside DB transactions.**
- **Payments are USD cents end-to-end:** `200`, `800`, `1600` = `$2`, `$8`, `$16`.
- **Refresh tokens are single-use:** SHA-256 hash only, atomic ACTIVE -> USED
  claim, replay revokes the whole family, `token_version` invalidates old JWTs.
- **Document jobs are claimed with a guarded transition + `@Version`.** Duplicate
  delivery is a no-op, not a double charge.

---

## 2. Application entry and configuration

| File | What it does |
|---|---|
| `ContentAnalyticsApplication.java` | Spring Boot entry point; enables scheduling, async execution and OpenAPI. |
| `config/CacheConfig.java` | Spring cache wiring for lightweight in-process lookups. |
| `config/ClockConfig.java` | Injects a `Clock` so token/expiry tests are deterministic. |
| `config/CorsConfig.java` | Allows the SPA origins from `app.cors.allowed-origins` for REST cookies. |
| `config/CsrfCookieFilter.java` | Puts the CSRF token in a cookie-readable form for the SPA. |
| `config/SpaCsrfTokenRequestHandler.java` | SPA-compatible CSRF token request handler. |
| `config/OpenApiConfig.java` | Swagger UI `/swagger-ui.html`, JSON `/v3/api-docs`, Bearer JWT authorize scheme, proprietary license metadata. |
| `config/PasswordConfig.java` | BCrypt password encoder bean. |
| `config/RabbitMqConfig.java` | Declares exchange/queue/binding; falls back to async when broker is absent. |
| `config/SecurityConfig.java` | The main security chain: public auth/payment/webhook/health routes, JWT filter, OAuth2 login, CSRF, CORS, authorized API routes. |
| `config/WebMvcConfig.java` | MVC helpers such as view/controller extras used by the SPA fallback. |
| `config/WebSocketConfig.java` | STOMP endpoint `/ws`, broker, origin checks and principal handling. |

## 3. Cross-cutting annotations, aspects and interceptors

| File | What it does |
|---|---|
| `annotation/Idempotent.java` | Marks a mutation endpoint as safe to retry with an `Idempotency-Key`. |
| `annotation/RateLimited.java` | Declarative per-user/per-IP rate limit for an endpoint. |
| `aspect/IdempotencyAspect.java` | Wraps `@Idempotent` methods; caches the response and blocks concurrent same-key requests. |
| `aspect/RateLimitingAspect.java` | Wraps controller methods; applies the windowed Redis limit and fails open on Redis errors. |
| `interceptor/RateLimitingInterceptor.java` | Additional request-level rate-limit hook used by the legacy filter/interceptor path. |
| `interceptor/WebSocketInterceptor.java` |Validates the STOMP handshake token/session before allowing a connection. |

## 4. Controllers (HTTP surface)

| File | What it does |
|---|---|
| `controller/AuthController.java` | Register, login, refresh, logout, verify email, reset password, providers, CSRF/current user. |
| `controller/DocumentController.java` | Upload/list/get/search/download/delete documents and expose stats. |
| `controller/DocumentWebSocketController.java` | STOMP destination handling for document progress/complete/failure messages. |
| `controller/HealthController.java` | Rich health payload (DB, Redis, disk, app metadata). |
| `controller/PaymentController.java` | `/api/payments/{initialize,webhook,verify,history,status}`; USD packs, HMAC webhook, browser reconcile. |
| `controller/ProfileController.java` | Profile read/update, avatar upload/delete/validation, password change. |
| `controller/SubscriptionController.java` | Subscription tier/current-plan endpoints. |

## 5. DTOs (request/response contracts)

### Auth and tokens

| File | What it does |
|---|---|
| `dto/RegisterRequestDto.java` | First/last name, username, email, password for sign-up. |
| `dto/LoginRequestDto.java` | Username/email + password for local login. |
| `dto/TokenResponseDto.java` | Access token, refresh token, expiry and user summary returned on login/refresh. |
| `dto/TokenRefreshRequestDto.java` | Refresh-token request body. |
| `dto/TokenValidationDto.java` | Result of a token/session validity check. |
| `dto/VerifyEmailRequestDto.java` | Email verification token body. |
| `dto/ResendVerificationEmailRequestDto.java` | Resend verification request body. |
| `dto/ForgotPasswordRequestDto.java` | Email for password-reset link. |
| `dto/ResetPasswordRequestDto.java` | Reset token + new password. |
| `dto/ChangePasswordRequestDto.java` | Current + new password. |
| `dto/PasswordChangeResponseDto.java` | Password-change acknowledgement. |
| `dto/CurrentUserDto.java` | Safe view of the authenticated user. |

### Documents

| File | What it does |
|---|---|
| `dto/DocumentUploadRequestDto.java` | Upload metadata (description/tags as applicable). |
| `dto/DocumentResponseDto.java` | Full document detail including extracted text, summary and status. |
| `dto/DocumentListItemDto.java` | Compact row for list views. |
| `dto/DocumentSearchFilterDto.java` | Search/filter/pagination parameters. |
| `dto/DocumentStatsDto.java` | Counts and aggregate document statistics. |
| `dto/DocumentStatusDto.java` | Status/progress snapshot used by polling/websocket clients. |
| `dto/DownloadFileDto.java` | Signed download URL + file metadata. |
| `dto/PaginationResponseDto.java` | Generic page envelope used by document listing. |

### Payments and credits

| File | What it does |
|---|---|
| `dto/InitializePaymentRequestDto.java` | Requested credit pack. |
| `dto/InitializePaymentResponseDto.java` | Reference, checkout URL, USD cents, credits, public key, status. |
| `dto/PaymentTransactionDto.java` | Payment history row with USD `amountCents`. |
| `dto/PurchaseCreditsRequestDto.java` | Purchase/credit top-up request. |
| `dto/CreditTransactionDto.java` | Credit ledger movement (type, amount, balance after, reference). |
| `dto/SubscriptionDto.java` | Subscription tier, allowance and period. |

### Profile, health, messaging and common

| File | What it does |
|---|---|
| `dto/UserProfileDto.java` | Public/private profile payload. |
| `dto/UpdateProfileRequestDto.java` | Editable profile fields. |
| `dto/UploadProfilePictureResponseDto.java` | Thumbnail/medium/full URLs after avatar upload. |
| `dto/ProfilePictureDeleteResponseDto.java` | Avatar deletion acknowledgement. |
| `dto/ProfileValidationResponseDto.java` | Profile completeness/validation result. |
| `dto/ImageProcessingResult.java` | Dimensions/format/output of avatar processing. |
| `dto/HealthCheckResponseDto.java` | Health endpoint payload. |
| `dto/ApiResponse.java` | Generic success/error envelope. |
| `dto/AnalyticsUpdateMessage.java` | WebSocket payload for analysis progress. |
| `dto/NotificationMessage.java` | Generic WebSocket notification payload. |
| `dto/WebSocketMessage.java` | Common STOMP message envelope with timestamp and type. |

## 6. Entities and enums (database model in Java)

| File | What it does |
|---|---|
| `entity/User.java` | Account: email/username, password or OAuth subject, role, status, token version, `@Version`. |
| `entity/RefreshToken.java` | Hashed single-use refresh token with family, status, used/revoked timestamps. |
| `entity/RefreshTokenStatus.java` | `ACTIVE`, `USED`, `REVOKED`. |
| `entity/Document.java` | Uploaded file, extracted text, summary, status/progress, `@Version` for job claims. |
| `entity/DocumentStatus.java` | `UPLOADED`, `PROCESSING`, `COMPLETED`, `FAILED`. |
| `entity/EmailVerificationToken.java` | One-time email verification token and usage metadata. |
| `entity/PasswordResetToken.java` | One-time reset token, expiry and `usedAt`. |
| `entity/PasswordHistory.java` | Old password hashes to prevent reuse. |
| `entity/UserProfile.java` | Bio, location, notification flags and counters. |
| `entity/ProfilePicture.java` | Active avatar key and generated URL variants. |
| `entity/Subscription.java` | FREE/PREMIUM/ENTERPRISE tier, credits and billing period. |
| `entity/SubscriptionStatus.java` | `ACTIVE`, `CANCELLED`, `EXPIRED`, `PAST_DUE`. |
| `entity/SubscriptionTier.java` | `FREE`, `PREMIUM`, `ENTERPRISE`. |
| `entity/CreditTransaction.java` | Immutable-style credit ledger row with type and balance after. |
| `entity/CreditTransactionType.java` | Allocation, purchase, AI debit, expiry, refund, admin adjustment. |
| `entity/PaymentTransaction.java` | Checkout reference, USD `amountCents`, credits, status, `@Version`. |
| `entity/PaymentStatus.java` | `PENDING`, `SUCCESS`, `FAILED`, `ABANDONED`. |
| `entity/AuditLog.java` | Security/audit events with actor, action, IP and details. |
| `entity/HealthMetric.java` | Historical health snapshot. |
| `entity/HealthStatus.java` | `HEALTHY`, `DEGRADED`, `UNHEALTHY`. |
| `entity/AuthProvider.java` | `LOCAL` or `GOOGLE`. |
| `entity/UserRole.java` | `USER` or `ADMIN`. |
| `entity/UserStatus.java` | `ACTIVE`, `INACTIVE`, `BANNED`. |

## 7. Exceptions

| File | What it does |
|---|---|
| `exception/GlobalExceptionHandler.java` | Maps domain exceptions to HTTP status + `ErrorResponse`; hides internals. |
| `exception/ErrorResponse.java` | Standard error body (message, code, timestamp, path). |
| `exception/AccountLinkingException.java` | Google account already bound to another user. |
| `exception/DocumentNotFoundException.java` | Missing/forbidden document. |
| `exception/InsufficientCreditsException.java` | User has no credits for an AI operation. |
| `exception/InvalidTokenException.java` | Bad/expired/replayed token. |
| `exception/UnauthorizedException.java` | Generic auth failure. |
| `exception/UserAlreadyExistsException.java` | Duplicate username/email. |
| `exception/UserNotFoundException.java` | Unknown user. |

## 8. Repositories

| File | What it does |
|---|---|
| `repository/UserRepository.java` | User lookup by id/username/email/OAuth subject. |
| `repository/RefreshTokenRepository.java` | Conditional ACTIVE claim, family revoke, hash lookup, cleanup. |
| `repository/DocumentRepository.java` | CRUD/search + guarded status transition queries for job claims. |
| `repository/EmailVerificationTokenRepository.java` | Verification token lookup and consume. |
| `repository/PasswordResetTokenRepository.java` | Reset token lookup and atomic consume. |
| `repository/PasswordHistoryRepository.java` | Old-password checks and history. |
| `repository/UserProfileRepository.java` | Profile by user id. |
| `repository/ProfilePictureRepository.java` | Active avatar by user id. |
| `repository/SubscriptionRepository.java` | Subscription by user and tier. |
| `repository/CreditTransactionRepository.java` | Ledger queries and duplicate-reference checks. |
| `repository/PaymentTransactionRepository.java` | Checkout lookup by reference, status and user. |
| `repository/AuditLogRepository.java` | Audit event persistence/query. |
| `repository/HealthMetricRepository.java` | Health history persistence. |

## 9. Security and token lifecycle

| File | What it does |
|---|---|
| `security/JwtTokenProvider.java` | Creates/parses access + refresh JWTs, checks `token_version` and session validity. |
| `security/JwtAuthenticationFilter.java` | Reads the access-token cookie/header, validates it and sets the principal to the userId. |
| `security/TokenSessionValidator.java` | Single source of truth for "is this token still allowed?" used by REST and WebSocket. |
| `security/GoogleOAuth2UserService.java` | Loads/maps Google profile info and links or provisions the local user. |
| `security/OAuth2LoginSuccessHandler.java` | Issues app tokens after Google login and redirects to the SPA callback. |
| `security/OAuth2LoginFailureHandler.java` | Redirects failed OAuth logins with a safe error. |
| `scheduler/TokenCleanupScheduler.java` | Periodically deletes expired verification/reset/refresh tokens. |

## 10. Services

### Auth, tokens and users

| File | What it does |
|---|---|
| `service/AuthService.java` | Register/login/refresh/logout, email verification, password reset, Google linking, replay detection and token-family revocation. |
| `service/RefreshTokenRevocationService.java` | Atomic rotate/revoke helpers for refresh-token families. |
| `service/LoginAttemptService.java` | Redis-backed failed-attempt counter and hard lockout (DB/fallback when Redis is down). |
| `service/OAuthAccountProvisioner.java` | Creates/links Google-backed users with deduplicated usernames. |
| `service/EmailService.java` | Brevo-backed verification/reset/welcome mail. |
| `service/GravatarService.java` | Fallback avatar derivation from email where used. |
| `service/AuditService.java` | Writes structured audit log rows. |

### Credits, subscription and payments

| File | What it does |
|---|---|
| `service/CreditService.java` | Monthly allocation, purchases, AI debits, expiry, duplicate-reference guards. |
| `service/payment/PaymentProvider.java` | Provider interface: initialize, verify, HMAC check, config flags. |
| `service/payment/PaystackProvider.java` | Paystack HTTP client; sends `currency=USD`, verifies HMAC and re-queries transactions. |
| `service/payment/PaymentService.java` | USD packs, PENDING row before provider call, webhook/verify application, amount guard, idempotent crediting. |
| `service/WebhookIdempotencyService.java` | Redis helper for webhook dedupe claims (optimization only, never authoritative). |

### Documents, jobs, AI and storage

| File | What it does |
|---|---|
| `service/DocumentService.java` | Upload metadata, ownership checks, guarded processing claim, delete/download. |
| `service/DocumentProcessingService.java` | Runs extraction + AI analysis and emits progress updates. |
| `service/DocumentJobListener.java` | RabbitMQ consumer: acknowledges, retries with bound, dead-letters exhausted jobs. |
| `service/JobQueue.java` | Queue abstraction used by document upload. |
| `service/AsyncJobQueue.java` | In-process `@Async` fallback queue (test/local and broker-down path). |
| `service/RabbitMqJobQueue.java` | Durable RabbitMQ publisher. |
| `service/FileExtractionService.java` | Extracts text/metadata from PDFs/images/documents. |
| `service/AiAnalysisService.java` | Provider dispatch, graceful degradation to basic analysis. |
| `service/AiProvider.java` | AI provider interface. |
| `service/GeminiService.java` | Free Gemini implementation. |
| `service/AnthropicAiProvider.java` | Optional Anthropic implementation. |
| `service/DocumentAnalysisResult.java` | Value object returned by AI providers. |
| `service/StorageService.java` | Storage abstraction and local fallback; rejects path traversal. |
| `service/S3StorageService.java` | Supabase S3-compatible storage, signed URLs and avatar handling. |
| `service/ImageService.java` | Avatar resize/format conversion. |
| `service/ProfileService.java` | Profile updates, avatar lifecycle and password-change orchestration. |
| `service/WebSocketService.java` | Sends user-scoped progress/complete/failure STOMP messages. |

### Infrastructure services

| File | What it does |
|---|---|
| `service/IdempotencyService.java` | Redis claim/cache for idempotent mutation endpoints; fails open when Redis is unavailable. |
| `service/RateLimitingService.java` | Atomic Redis fixed-window limiter; allows requests when the store is down. |
| `service/HealthCheckService.java` | Aggregates DB/Redis/disk status for health endpoints. |

## 11. Utilities

| File | What it does |
|---|---|
| `util/SecurityUtil.java` | Gets the current userId from the Spring Security principal. |
| `util/CookieUtil.java` | Builds/clears HttpOnly Secure SameSite auth and CSRF cookies. |
| `util/TokenGenerationUtil.java` | Generates secure opaque tokens for email/reset flows. |

---

## 12. Flyway migrations V1-V18

| File | What it does |
|---|---|
| `V1__Create_Users_Table.sql` | Base `users` table. |
| `V2__Create_Documents_Table.sql` | Base `documents` table. |
| `V3__Create_Health_Metrics_Table.sql` | Health snapshot table. |
| `V4__Create_Email_Verification_Tokens_Table.sql` | Verification tokens. |
| `V5__Create_Password_Reset_Tokens_Table.sql` | Reset tokens. |
| `V6__Create_User_Profiles_Table.sql` | Profile table. |
| `V7__Create_Profile_Pictures_Table.sql` | Avatar table. |
| `V8__Create_Password_History_Table.sql` | Password reuse history. |
| `V9__Create_Subscriptions_Credits_Audit_Tables.sql` | Subscription, credit ledger and audit tables. |
| `V10__Add_Performance_Indexes.sql` | Indexes on hot lookup columns. |
| `V11__Add_Google_OAuth_Support.sql` | `provider`, `provider_subject` and OAuth uniqueness. |
| `V12__Create_Payment_Transactions_Table.sql` | Original payment ledger (kobo at the time). |
| `V13__Drop_Decorative_Profile_Fields.sql` | Removes unused `company`/`job_title`. |
| `V14__Add_Check_Constraints.sql` | DB-level validation for statuses and amounts. |
| `V15__Add_Optimistic_Lock_And_Purchase_Idempotency.sql` | `@Version` columns and unique purchase/AI-debit references. |
| `V16__Refresh_Token_Rotation.sql` | Hashed, single-use refresh-token family table. |
| `V17__Document_Job_Concurrency.sql` | `documents.version` for guarded duplicate job claims. |
| `V18__Payments_Use_Usd_Cents.sql` | Renames `amount_kobo` to `amount_cents`, converts packs to USD cents. |

## 13. Configuration files

| File | What it does |
|---|---|
| `application.properties` | Local/default config: H2 or local DB, local Redis/queue flags, local secrets kept out of git history. |
| `application-local.properties` | Developer-specific overrides (ignored from version control in deployment docs). |
| `application-test.properties` | H2 in-memory test profile; disables external providers, uses async queue. |
| `application-prod.properties` | Render profile: Postgres pooler, Upstash Redis, JWT, OAuth, Gemini, Brevo, Paystack, CORS/WebSocket, Supabase S3. |

## 14. Tests

| File | What it verifies |
|---|---|
| `FlywayMigrationTest.java` | Opt-in Testcontainers test applying V1-V18 to real Postgres (`RUN_POSTGRES_TESTS=true`). |
| `ProfilePictureIntegrationTest.java` | Avatar upload/delete/signed URL flow. |
| `entity/SubscriptionTest.java` | Subscription allowance/tier behaviour. |
| `security/GoogleOAuthIntegrationTest.java` | Google sign-up/link/reject-steal behaviour. |
| `security/JwtTokenProviderTest.java` | Basic JWT creation/parsing/expiry. |
| `security/JwtTokenProviderSessionTest.java` | Token-version/session invalidation. |
| `security/JwtAuthenticationFilterTest.java` | Filter rejects stale/invalid tokens and sets the right principal. |
| `interceptor/WebSocketInterceptorTest.java` | STOMP handshake token validation. |
| `controller/DocumentWebSocketControllerTest.java` | Correct user-scoped STOMP messages. |
| `service/AuthServiceTokenTest.java` | Refresh rotation, replay and family revocation. |
| `service/RefreshTokenClaimTest.java` | Atomic DB claim under concurrency. |
| `service/CreditServiceTest.java` | Credit allocation/purchase/debit and duplicate guards. |
| `service/IdempotencyServiceTest.java` | Cached response, in-flight detection and Redis-down fallback. |
| `service/RateLimitingServiceTest.java` | Windowed limits and Redis-down fallback. |
| `service/LoginAttemptServiceTest.java` | Lockout and reset behaviour. |
| `service/DocumentJobConcurrencyTest.java` | Duplicate job delivery is a no-op. |
| `service/DocumentJobListenerTest.java` | Retry and dead-letter transitions. |
| `service/StorageServiceBackendSelectionTest.java` | Local vs S3 backend selection. |
| `service/StorageServiceTest.java` | Save/delete and path-traversal rejection. |
| `service/WebSocketServiceTest.java` | Progress/completion/failure message contents. |
| `service/AiAnalysisServiceTest.java` | Provider dispatch and graceful degradation. |
| `service/payment/PaymentServiceTest.java` | USD packs, PENDING row, HMAC, amount guard, idempotent crediting. |

## 15. Docs and automation

| File | What it does |
|---|---|
| `docs/API.md` | Endpoint reference, including USD Paystack flow. |
| `docs/ARCHITECTURE.md` | System topology and design decisions. |
| `docs/AUTHENTICATION.md` | Cookies, JWT, refresh rotation, Google OAuth. |
| `docs/DEPLOYMENT.md` | Render/Supabase/Upstash/Paystack setup and env vars. |
| `docs/DEVELOPMENT.md` | Local build/test/migration instructions. |
| `docs/GOOGLE_OAUTH_SETUP.md` | Google Cloud client and redirect URI setup. |
| `docs/CODE_MAP.md` | This file. |
| `.github/workflows/ci.yml` | `clean verify` on push/PR to main/development; H2 tests, no service containers. |
| `docker-compose.yml` | Full local stack: app + Postgres + Redis + RabbitMQ. |
| `docker-compose.app.yml` | App-only compose for use with external services. |
| `Dockerfile` | Multi-stage JDK 21 build and slim runtime image. |
| `LICENSE` | Proprietary, all rights reserved. |
