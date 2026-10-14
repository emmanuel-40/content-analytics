# API reference

Base URL locally `http://localhost:8080`. All `/api` routes return JSON; auth state
travels in HttpOnly access + refresh cookies (SameSite=Lax, Secure in prod).
State-changing calls need the CSRF token from `GET /api/auth/csrf`. Error shape:
`{ "timestamp", "status", "error", "message", "path" }`. Link-conflict on OAuth
linking is HTTP 409 (`AccountLinkingException`).

## Contents

- [Auth](#auth)
- [OAuth handshake (browser)](#oauth-handshake-browser)
- [Profile](#profile)
- [Documents](#documents)
- [Health metrics](#health-metrics)
- [Subscriptions and credits](#subscriptions-and-credits)
- [Payments (Paystack, test mode)](#payments-paystack-test-mode)
- [Ops](#ops)

## Auth

| Method + path | Auth | Notes |
|---|---|---|
| `POST /api/auth/register` | no | Creates `LOCAL` user, sends Brevo verification link. |
| `GET /api/auth/verify-email?token=...` | no | Activates the account. |
| `POST /api/auth/login` | no | Requires verified email; passwordless (Google-only) rows get a use-Google message. Sets both cookies. |
| `POST /api/auth/refresh` | cookie | Rotates the pair. |
| `POST /api/auth/logout` | cookie | Clears both cookies. |
| `POST /api/auth/forgot-password` | no | Sends reset link (also how Google users add a password). |
| `POST /api/auth/reset-password` | no | Consumes the reset token. |
| `GET /api/auth/verify-token/**` | no | Token validity probes. |
| `GET /api/auth/csrf` | no | CSRF token for the SPA. |
| `GET /api/auth/providers` | no | `{"google": true/false}` - SPA calls pre-login to show/hide the Google button. |

## OAuth handshake (browser)

| Leg | Notes |
|---|---|
| `GET /oauth2/authorization/google` | Entry point - 302 to Google consent. Top-level navigation, not fetch. |
| `GET /login/oauth2/code/google?code=...&state=...` | Google redirects here; backend finishes provisioning, sets the same cookies as login, then `302 <FRONTEND_URL>/auth/callback?status=success` (or `?status=error&reason=<code>`). No JSON on either leg. |

## Profile

| Method + path | Notes |
|---|---|
| `GET /api/profile` | Current user. |
| `PUT /api/profile` | Update names etc. |
| `POST /api/profile/change-password` | Rejected on passwordless rows (nothing to verify against). |
| `POST /api/profile/picture` / `GET /api/profile/pictures/**` | Avatar upload + signed-URL serving. |

## Documents

Upload (multipart), list (paged), download (streams through the API - the bucket stays
private), delete, analysis results. Async analysis jobs run in-process locally;
set `app.queue.type=rabbitmq` + local RabbitMQ to exercise the queue path.

## Health metrics

CRUD over dated metric entries plus aggregate endpoints for the dashboard charts.

## Subscriptions and credits

Plan catalog, current subscription, credit balance/usage, and the audit trail
written on every analysis job.

| Method + path | Auth | Notes |
|---|---|---|
| `GET /api/subscription/tiers` | no | Public tier catalogue (FREE/PREMIUM/ENTERPRISE + monthly credits). |
| `GET /api/subscription` | cookie | Current tier, balance, period window. |
| `GET /api/subscription/transactions?page=&size=` | cookie | Credit ledger, newest first. |
| `POST /api/subscription/purchase-credits` | **ADMIN** | Grants credits with no money - test/demo seeding only. Real buys go through payments below. |

## Payments (Paystack, test mode)

The money flow never trusts the browser. `/initialize` creates a `PENDING`
ledger row and returns a hosted checkout URL; the **webhook** (HMAC over the raw
body, then server-side re-query of Paystack) is the only path that marks a row
`SUCCESS` and grants credits, and it is idempotent (a duplicate delivery credits
once). `/verify` reconciles a returning browser through the same verified path.

| Method + path | Auth | Notes |
|---|---|---|
| `POST /api/payments/initialize` | cookie | Body `{credits}` -> `{reference, checkoutUrl, amountKobo, credits, publicKey, status}`. Open `checkoutUrl` in Paystack. |
| `GET /api/payments/verify?reference=` | cookie | Re-queries Paystack and applies credit on success (same logic as webhook). |
| `POST /api/payments/webhook` | no (HMAC) | Paystack -> us. Verify `x-paystack-signature`; always `200 {status:received}` unless the body is unreadable. |
| `GET /api/payments/history` | cookie | Own checkout attempts, newest first. |
| `GET /api/payments/status` | no | `{configured, packs, currency}` - SPA hides the Buy button while unconfigured (mirrors `/api/auth/providers`). |

> Test mode by default: swap in live `PAYSTACK_SECRET_KEY` / `PAYSTACK_PUBLIC_KEY`
> on Render and flip `app.payments.mode=live` - **no code change**. Locally, test
> with card `4084 0840 8408 4081`; the webhook needs a public URL (ngrok) or just
> call `/verify` after paying.

## Ops

| Path | Notes |
|---|---|
| `/api/health`, `/actuator/health` | Render health check + UptimeRobot. |
| `/actuator/info`, `/actuator/prometheus` | Build info, metrics. `env`/`configprops` are NOT exposed (secret leak). |
| `/ws` | STOMP WebSocket for analysis progress; allowed origins mirror CORS. |
