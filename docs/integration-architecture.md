# Integration Architecture

How the **FE** (Expo RN mobile) and **BE** (Spring Boot API) parts of this monorepo communicate. There is no shared code package — the boundary is fully on-the-wire.

## Topology

```
┌─────────────────────────┐      HTTPS REST       ┌──────────────────────────┐
│  FE (Expo / RN)         │ ────────────────────► │  BE (Spring Boot 3.3)    │
│  src/api/client.ts      │                       │  /yeolsal/api/v1/*       │
│  apiRequest<T>()        │ ◄──────────────────── │  ApiResponse<T> envelope │
│                         │                       │                          │
│  src/lib/realtime/      │      STOMP / WSS      │  /ws (single endpoint)   │
│  RealtimeProvider       │ ◄──────────────────── │  /topic/* /queue/*       │
└─────────────────────────┘     event payloads    └──────────────────────────┘
                                                              │
                                                              ▼
                                                    ┌──────────────────┐
                                                    │  PostgreSQL      │
                                                    │  Flyway V1–V10   │
                                                    └──────────────────┘
```

## Integration Points

| From | To | Type | Details |
|------|----|------|---------|
| `fe` (`src/api/client.ts`) | `be` (`/api/v1/*`) | REST over HTTPS | Bearer JWT, `ApiResponse<T>` envelope, `ApiError` mapping |
| `fe` (`src/lib/realtime/`) | `be` (`/ws`) | STOMP over WebSocket (WSS in prod) | JWT validated at CONNECT frame |
| `fe` (`expo-notifications`) | `be` (`/api/v1/me/push-tokens`) | REST | Token registration; FE never persists its own copy |
| `fe` (`auth/kakao` deep-link) | `be` (`/api/v1/auth/kakao/*`) | REST + redirect | FE never embeds Kakao REST API key |

## REST Contract

**Base URL** (FE side): `EXPO_PUBLIC_API_BASE_URL` (default `https://api.rearleg.com/yeolsal/api/v1`).
**Context path** (BE side): `/yeolsal` is applied automatically; controllers declare `/api/v1/...` only.

**Success envelope**: `{ "data": <T> }`
**Error envelope**: `{ "error": { "code": "<STABLE_ENUM>", "message": "<human>" } }`

Stable error codes the FE branches on:

| HTTP | code | FE behavior |
|------|------|-------------|
| 400 | `BAD_REQUEST`, `VALIDATION` | Show inline form error / generic toast |
| 401 | `UNAUTHORIZED` | `apiRequest` calls `/auth/refresh` once; if that fails, clears tokens and triggers `onAuthInvalid` |
| 403 | `FORBIDDEN` | Redirect to safe screen / show "no permission" |
| 404 | `NOT_FOUND` | Empty state |
| 5xx | `INTERNAL_ERROR` | Sentry capture (server-bug channel) |

## Auth Lifecycle

1. FE → `POST /auth/login` (or Kakao exchange) → BE issues `{ accessToken, refreshToken, tokenType, user }`.
2. FE stores tokens in `expo-secure-store` (`yeosal.accessToken`, `yeosal.refreshToken`).
3. Each `apiRequest` adds `Authorization: Bearer <accessToken>`.
4. On `401`, `apiRequest` calls `POST /auth/refresh` once with `{ refreshToken }` and retries the original call.
5. If refresh fails, FE clears tokens and fires `onAuthInvalid` (consumer redirects to login).
6. BE `/auth/logout` revokes the refresh token (sets `revoked_at`); subsequent refreshes 401.

Refresh-token rotation is enforced server-side: each successful `/auth/refresh` issues a new refresh token; the old one is revoked.

## Realtime Contract (STOMP)

**Endpoint**: `wss://<host>/yeolsal/ws` (HTTP upgrade). SockJS fallback **off** — FE uses native `WebSocket`.

**Auth**: JWT is sent on the STOMP `CONNECT` frame's `Authorization` header. Validated by `JwtChannelInterceptor`. The HTTP handshake is `permitAll`; without that allowance the upgrade is rejected before STOMP can run.

**Topic conventions** (server → client):

| Topic | Payload | Producer |
|-------|---------|----------|
| `/topic/rooms/{roomId}/messages` | `RealtimeEvent.ChatMessage` | `ChatService` via `RealtimePublisher` |
| `/topic/rooms/{roomId}/members` | `RealtimeEvent.MemberAdded` | `RoomService` via `RealtimePublisher` |
| `/user/queue/notifications` | `RealtimeEvent.Notification` | `NotificationService` per principal |
| `/user/queue/friend-requests` | `RealtimeEvent.FriendRequest*` | `FriendService` per principal |

**App → server**: prefix `/app` is reserved but currently unused. Clients read-only.

## REST/WS Dedupe

Chat is the canonical case where both REST and WS deliver the same message:

1. FE sends a message via `POST /api/v1/rooms/{id}/messages`. BE persists, returns `ChatService.MessageDto`.
2. BE also fans the message out on `/topic/rooms/{id}/messages` via `RealtimePublisher`.
3. The sender's WS client may receive the same message moments later.
4. FE must dedupe by message `id` (the canonical key). The pattern lives in `useChatRealtime`; new realtime hooks must follow it.

Reading direction:
- WS event must NOT overwrite cache directly. It either calls `invalidateQueries` for the room's message list or merges into the cache only if its `id` is not already present.
- Adaptive polling is a **fallback** for when WS is down. While WS is connected, polling stays disabled.

## Push Notifications

1. Mobile app obtains an `expo-notifications` token at startup (after auth).
2. FE calls `POST /api/v1/me/push-tokens` with `{ token, platform }`.
3. BE upserts into `push_tokens` keyed `(user_id, token)`.
4. Token is the BE's source of truth — FE does not persist it in `expo-secure-store`.
5. On logout the FE deletes its registration (`DELETE /api/v1/me/push-tokens/{id}`).

`notification_log` provides idempotency: each scheduled cron / event-hook trigger inserts `(user_id, kind, key)`; duplicate triggers no-op on conflict.

## Configuration Boundary

| Concern | Owner | Notes |
|---------|-------|-------|
| API base URL | FE (`EXPO_PUBLIC_API_BASE_URL`) | Defaults to prod; override per environment |
| Allowed CORS / WS origins | BE (`yeosal.cors.allowed-origins`) | Comma-separated; same value drives REST and STOMP |
| JWT secret | BE (`yeosal.auth.jwt-secret`) | ≥32 chars; `StartupConfigValidator` rejects dev placeholder in non-dev profiles |
| Kakao client_id | BE only (`yeosal.kakao.client-id`) | Never exposed to FE |
| Sentry DSN | FE (`EXPO_PUBLIC_SENTRY_DSN`) | Empty → Sentry auto-disabled |
| App ID | FE (`app.json` → `app.yeosal.mobile`) | Native module changes need clean reinstall |

## Failure-Mode Coupling

- Migration drift (FE expects a field BE hasn't shipped) — type the FE response shape against the new BE record at PR time; `apiRequest<T>` throws if JSON shape mismatches `T` only at the access site, not at fetch time.
- `ddl-auto: validate` — schema-only PRs that miss the migration fail BE boot, blocking deploys before any FE call lands.
- Stack-PR merge ordering — see CONTRIBUTING.md. The V7/V8 incident showed how a stack-PR squash misroute can cause migration drift between deployed BE and the schema FE assumes.
- `ApiExceptionHandler` is the single advisor — adding a new domain exception without a handler degrades the FE error UX to generic 500 / Sentry server-bug noise.

## Testing the Integration

- BE side: integration tests with Testcontainers PostgreSQL exercise `@SpringBootTest` flows including auth and a stubbed `RealtimePublisher` to assert event emission.
- FE side: TanStack Query hooks are tested with `QueryClientProvider` + `fetch` stub. Realtime hooks mock `RealtimeProvider`.
- Cross-part smoke: bring up `infra/docker-compose.yml`, point `FE/.env`'s `EXPO_PUBLIC_API_BASE_URL` to `http://10.0.2.2:8088/yeolsal/api/v1` (Android emulator) or `http://localhost:8088/yeolsal/api/v1` (iOS sim).
