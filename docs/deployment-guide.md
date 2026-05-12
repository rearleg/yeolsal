# Deployment Guide

This is a focused deployment summary. The full operational runbook (with every command and troubleshooting flow) is in [`../RUNBOOK.md`](../RUNBOOK.md).

## Targets

| Target | Tooling | Profile |
|--------|---------|---------|
| Server (api + db + edge) | Docker Compose | `infra/docker-compose.yml` |
| Mobile preview (sideload) | EAS | `eas build --profile preview` (APK) |
| Mobile production (Android) | EAS | `eas build --profile production` (AAB) |
| Mobile production (iOS) | EAS | `eas build --profile production` (TestFlight / App Store) |

## Server Stack (Docker Compose)

`infra/docker-compose.yml` runs three services:

- `api` — Spring Boot image built from `BE/Dockerfile`. The image embeds `/app/COMMIT` for outage diagnosis (`docker compose exec api cat /app/COMMIT`).
- `postgres` — PostgreSQL data store. Volume-mounted; `docker compose down -v` deletes data.
- `nginx` — Reverse proxy. Externally listens on **port 8088**.

External base URLs:

```text
http://localhost:8088/health             # nginx health
http://localhost:8088/yeolsal/health     # api health (via nginx)
http://localhost:8088/yeolsal/api/v1/... # api routes
```

### Required `.env` (under `infra/`)

`infra/.env.example` is the source of truth. Copy and fill:

```text
POSTGRES_PASSWORD=change-me
YEOSAL_JWT_SECRET=replace-with-at-least-32-random-characters
KAKAO_CLIENT_ID=<Kakao REST API key>
KAKAO_REDIRECT_URI=https://api.rearleg.com/yeolsal/api/v1/auth/kakao/callback
KAKAO_MOBILE_REDIRECT_URI=yeosal://auth/kakao
```

`StartupConfigValidator` rejects boot if `YEOSAL_JWT_SECRET` is the dev placeholder (`dev-only-change-me-...`) outside the `dev` profile.

### Lifecycle

| Action | Command |
|--------|---------|
| Up (foreground) | `cd infra && docker compose up --build` |
| Up (detached) | `docker compose up --build -d` |
| Status | `docker compose ps` |
| Logs | `docker compose logs -f {api\|postgres\|nginx}` |
| Down | `docker compose down` |
| Down + wipe DB | `docker compose down -v` |

### Outage Diagnosis Priority

1. `docker compose exec api cat /app/COMMIT` — verify the deployed commit.
2. `docker compose logs api --since 5m | grep -iE "\[chat\]|\[db\]|\[validation\]|exception"` — `ApiExceptionHandler` root-cause logs first.
3. `docker compose exec postgres psql -U yeosal -d yeosal -c "select version, success from flyway_schema_history order by installed_rank desc limit 5;"` — confirm migrations applied.
4. ApplicationContext boot failure: `docker compose logs api --since 5m | grep -iE "Error creating bean|No default constructor"` (cf. PR #34: missing `@Autowired` on `RateLimitFilter`).

## BE Image Build (standalone)

```bash
cd BE
docker build -t yeosal-api:local .
```

Direct run (less common — Compose preferred):

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/yeosal \
  -e SPRING_DATASOURCE_USERNAME=yeosal \
  -e SPRING_DATASOURCE_PASSWORD=yeosal \
  -e YEOSAL_JWT_SECRET=replace-with-at-least-32-random-characters \
  yeosal-api:local
```

## Mobile (EAS)

EAS profiles in `FE/eas.json`:

| Profile | Output | Purpose |
|---------|--------|---------|
| `preview` | APK | Sideload to emulator / device for QA |
| `production` | AAB / iOS | Google Play / TestFlight / App Store |

```bash
cd FE
eas login
eas build --platform android --profile preview        # APK
eas build --platform android --profile production     # AAB
eas build --platform ios     --profile production     # iOS bundle
```

EAS Secrets carry the production-only credentials:

```bash
eas secret:create --scope project --name SENTRY_AUTH_TOKEN --value <TOKEN>
eas secret:create --scope project --name EXPO_PUBLIC_SENTRY_DSN --value <DSN>
```

`SENTRY_AUTH_TOKEN` is build-time only (sourcemap upload), not embedded in the bundle.

`EXPO_PUBLIC_SENTRY_ENVIRONMENT` is set per profile in `eas.json`; not a secret.

The Kakao REST API key lives **only on the BE**. The FE does not embed it; auth starts at `BE/auth/kakao/authorize`.

## Pre-Release Verification

From repo root:

```bash
bash scripts/verify.sh    # FE checks + BE test/build + Docker image build (when Docker is up)
```

Stack-PR merge procedure (incident-driven, mandatory) lives in [`../CONTRIBUTING.md`](../CONTRIBUTING.md). Always verify a merged PR's squash commit reached `main` with `git merge-base --is-ancestor`.

## Production API URL

```text
https://api.rearleg.com/yeolsal/api/v1
```

The FE `EXPO_PUBLIC_API_BASE_URL` default must stay aligned. Override per-environment via `FE/.env`:

```text
# Android emulator → Mac host
EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:8088/yeolsal/api/v1
# iOS simulator  → Mac host
EXPO_PUBLIC_API_BASE_URL=http://localhost:8088/yeolsal/api/v1
```

After changing `FE/.env`, restart Metro with cache flush: `npx expo start -c`.
