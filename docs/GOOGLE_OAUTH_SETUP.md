# Google OAuth setup (5 minutes, free)

Google sign-in is optional. With no credentials the app boots normally and
`GET /api/auth/providers` returns `{"google": false}`, so the frontend hides
the button. Follow this once per environment (local, then prod).

## Contents

- [1. OAuth consent screen](#1-oauth-consent-screen)
- [2. OAuth client ID](#2-oauth-client-id)
- [3. Wire the credentials in](#3-wire-the-credentials-in)
- [4. Verify](#4-verify)
- [Troubleshooting](#troubleshooting)

## 1. OAuth consent screen

Console: https://console.cloud.google.com/apis/credentials (new or existing project).

1. Left nav: APIs and Services -> OAuth consent screen.
2. User type: **External**.
3. Fill app name + user support email + developer contact email (the only required fields).
4. Scopes -> Add: `openid`, `email`, `profile`. All three are non-sensitive,
   so no Google verification is needed.
5. Leave the app in **Testing** (add your Google account under Test users)
   or click Publish app to remove the 100-user test cap - still free.

## 2. OAuth client ID

1. Credentials -> Create credentials -> OAuth client ID -> type **Web application**.
2. Authorized JavaScript origins:
   - local: `http://localhost:8080`
   - prod: `https://<your-backend-host>`
3. Authorized redirect URIs (one per environment - this is the value Google
   exact-matches on callback; a trailing slash or http/https mismatch fails):
   - local: `http://localhost:8080/login/oauth2/code/google`
   - prod: `https://<your-backend-host>/login/oauth2/code/google`
4. Copy the **Client ID** and **Client secret**.

## 3. Wire the credentials in

| Environment | Where | How |
|---|---|---|
| Local | `src/main/resources/application.properties` | Paste over the two `local-dev-not-configured` values (`spring.security.oauth2.client.registration.google.client-id/secret`), restart. `GET /api/auth/providers` flips to `{"google": true}`. |
| Prod (Render) | Dashboard -> Environment | Add `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`, redeploy. The prod file maps them; `server.forward-headers-strategy=framework` lets `{baseUrl}` resolve to the public host behind the Render proxy. |

No code or scope changes are needed: Spring fills Googles authorization, token,
userinfo and JWKS endpoints from `CommonOAuth2Provider(google)`, and the
`openid,profile,email` scope in `application.properties` already matches the
consent screen.

## 4. Verify

```bash
# backend reports the flag
curl http://localhost:8080/api/auth/providers        # {"google":true}

# handshake entry point redirects to Google (302, Location: accounts.google.com/...)
curl -i http://localhost:8080/oauth2/authorization/google | head -5
```

Then in a browser: click Continue with Google -> consent -> land on
`<FRONTEND_URL>/auth/callback?status=success` with the app session active.
First sign-in with an unknown email creates a passwordless row; with a known
local email it links and keeps the password (see `docs/AUTHENTICATION.md`).

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `providers` still `{"google": false}` | Placeholder still in place - paste the real id (local file) or set the Render env var and redeploy. |
| `redirect_uri_mismatch` (Google error page) | The callback URL is not registered EXACTLY: re-check scheme/host/path in Credentials -> Authorized redirect URIs. In prod also confirm `server.forward-headers-strategy=framework` so `{baseUrl}` is https, not `http://localhost`. |
| `/auth/callback?status=error&reason=client_not_configured` | Request reached the backend with the placeholder id - configure credentials (above). |
| `reason=state_mismatch` | Stale handshake: tab left open too long, or callback opened in a different browser than the one that started it. Start again from the button. |
| `reason=email_unverified` | The Google account email is not verified - verify it in the Google account first. |
| `reason=link_conflict` | That Google identity is tied to a different local user (HTTP 409 on API linking too). Sign in with the original method. |
| `reason=access_denied` | User cancelled on the Google screen - not an error. |
