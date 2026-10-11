# Deployment (Render + Supabase + Upstash, all free tiers)

Profile `prod` (`SPRING_PROFILES_ACTIVE=prod`). `application-prod.properties` maps
Render dashboard env vars onto Spring properties - the file itself hardcodes nothing
secret. Local values stay in `application.properties`.

## Contents

- [1. Provision backing services](#1-provision-backing-services)
- [2. Create the Render service](#2-create-the-render-service)
- [3. Set Render env vars](#3-set-render-env-vars)
- [4. Google redirect URI](#4-google-redirect-uri-optional-but-recommended)
- [5. Deploy and verify](#5-deploy-and-verify)
- [Rollback / notes](#rollback--notes)

## 1. Provision backing services

- **Supabase project**: Project Settings -> Database gives `PROJECTREF` + region.
  - Database: use the **transaction pooler** URL (port 6543) with `?prepareThreshold=0`;
    the pooler does not support prepared statements and Hibernate fails without it.
    `DB_USERNAME` is `postgres.PROJECTREF`. Keep the per-instance Hikari pool small
    (default 4) - the free tier connection budget is shared.
  - Storage: Storage -> Settings -> turn **S3 protocol ON**; Project Settings -> API ->
    create **S3 Access Keys**; Storage -> New bucket (PRIVATE, e.g. `content-analytics`).
    Endpoint: `https://<PROJECTREF>.supabase.co/storage/v1/s3`, path-style forced on.
- **Upstash Redis**: free tier, copy host/port/password.
- **Brevo**: free 300/day; sender address verified; API key.
- **Gemini** (optional): AI Studio key; without it analysis falls back to basic.

## 2. Create the Render service

- New -> Web Service -> point at this repo. Build: `./mvnw -q -DskipTests package`;
  start: `java -jar target/*.jar --spring.profiles.active=prod`
  (or set `SPRING_PROFILES_ACTIVE=prod` in Environment and start plain `java -jar`).
- Health check path: `/api/health`. Free instances sleep - first request after idle is slow.

## 3. Set Render env vars

Dashboard -> Environment -> Add (see the header table in `application-prod.properties`):

```
PORT=<auto-supplied by Render, do not hardcode>
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=jdbc:postgresql://postgres.PROJECTREF:PASSWORD@aws-0-REGION.pooler.supabase.com:6543/postgres?prepareThreshold=0&sslmode=require
DB_USERNAME=postgres.PROJECTREF
DB_PASSWORD=<supabase db password>
REDIS_HOST=<upstash host>
REDIS_PORT=6379
REDIS_PASSWORD=<upstash password>
JWT_SECRET=<openssl rand -base64 64, MUST be 64+ bytes>
FRONTEND_URL=https://<your-frontend>
CORS_ALLOWED_ORIGINS=https://<your-frontend>
WEBSOCKET_ALLOWED_ORIGINS=https://<your-frontend>
GOOGLE_CLIENT_ID=<optional, enables Google sign-in>
GOOGLE_CLIENT_SECRET=<optional>
AWS_S3_BUCKET_NAME=<bucket>
AWS_REGION=<e.g. eu-central-1>
AWS_ACCESS_KEY_ID=<supabase s3 key>
AWS_SECRET_ACCESS_KEY=<supabase s3 secret>
AWS_S3_ENDPOINT=https://<PROJECTREF>.supabase.co/storage/v1/s3
GEMINI_API_KEY=<optional>
BREVO_API_KEY=<optional>
EMAIL_FROM_ADDRESS=no-reply@yourdomain.com
```

## 4. Google redirect URI (optional but recommended)

Google Cloud Console -> Credentials -> the Web client -> Authorized redirect URIs, add:
`https://<backend-host>/login/oauth2/code/google`. Without it Google answers
`redirect_uri_mismatch`. `{baseUrl}` is derived from `X-Forwarded-Proto/Host`,
which is why the prod file pins `server.forward-headers-strategy=framework`
(Render terminates TLS at the edge and forwards plain HTTP).

## 5. Deploy and verify

1. Deploy; Flyway runs `V1`-`V11` on boot (validate mode - the schema must migrate,
   never be created by Hibernate).
2. `GET https://<backend>/actuator/health` -> UP (sub-checks: db, redis, storage).
3. `GET https://<backend>/api/auth/providers` -> `{"google":true}` once the Google
   env vars are set (false until then - frontend hides the button).
4. Register/login/refresh in the SPA; Google button -> `/auth/callback?status=success`.
5. Upload a document; analysis job completes; avatar upload serves a signed URL.

## Rollback / notes

-_roll back by redeploying the previous Render release; Flyway migrations are
  additive (`V11` only adds nullable columns + an index), so old code runs on the
  new schema.
- Never enable `server.ssl` on Render (no keystore exists; TLS ends at the proxy).
- Cookies flip Secure automatically on https origins; CSRF comes from `/api/auth/csrf`.
- Free-tier sleeps: keep UptimeRobot on `/api/health` if cold starts hurt.
