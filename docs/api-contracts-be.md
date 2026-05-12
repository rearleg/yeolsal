# API Contracts — BE

External base URL: `https://api.rearleg.com/yeolsal/api/v1` (production).
Internal context-path: `/yeolsal`. Controller-declared paths use `/api/v1/...` only.

## Envelopes

**Success**
```json
{ "data": <T> }
```
Empty body on `204 No Content`. `Void` returns also use `ResponseEntity<Void>`.

**Error**
```json
{ "error": { "code": "<STABLE_ENUM>", "message": "<human-readable, may be Korean>" } }
```

Stable error codes: `BAD_REQUEST`, `VALIDATION`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `INTERNAL_ERROR`.
HTTP status is the authoritative dimension; clients may branch on `error.code` for finer UX.

## Authentication

- Bearer JWT for everything except the public auth endpoints below.
- `Authorization: Bearer <accessToken>`. On `401`, FE `apiRequest` retries once after `/auth/refresh`.
- Auth is stateless; no cookies.

## Public Endpoints (no auth)

| Method | Path | Body | Response | Notes |
|--------|------|------|----------|-------|
| POST | `/api/v1/auth/signup` | `SignupRequest` | `AuthTokens` | email/password create |
| POST | `/api/v1/auth/login` | `LoginRequest` | `AuthTokens` | email/password login |
| GET | `/api/v1/auth/kakao/authorize` | — | 302 → Kakao | redirects to Kakao OAuth |
| GET | `/api/v1/auth/kakao/callback?code=` | — | 302 → `yeosal://auth/kakao` deep link | server-side callback |
| POST | `/api/v1/auth/kakao/exchange` | `KakaoExchangeRequest` | `AuthTokens` | mobile exchange flow |
| POST | `/api/v1/auth/refresh` | `RefreshRequest` | `AuthTokens` | rotate access token |
| GET | `/v3/api-docs/**` | — | OpenAPI JSON | springdoc |
| GET | `/swagger-ui/**` | — | Swagger UI | springdoc |
| Upgrade | `/ws` | — | STOMP frames | JWT enforced at CONNECT frame |

## Authenticated Endpoints

### Auth (logout requires session)

| Method | Path | Body | Response |
|--------|------|------|----------|
| POST | `/api/v1/auth/logout` | `RefreshRequest` | `String` |

### Friends + Feed

| Method | Path | Query | Body | Response |
|--------|------|-------|------|----------|
| GET | `/api/v1/friends/requests` | — | — | `List<FriendRequestDto>` |
| POST | `/api/v1/friends/requests` | — | `FriendRequestCreate` | `FriendRequestDto` |
| PATCH | `/api/v1/friends/requests/{id}` | — | `FriendRequestDecision` | `FriendRequestDto` |
| GET | `/api/v1/feed/daily` | `date=YYYY-MM-DD` | — | `List<DailyFeedItem>` |

### Daily Work (entries / todos / reflections / monthly goals)

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET | `/api/v1/daily-entries/today` | — | `DailyEntryDto` |
| POST | `/api/v1/daily-entries` | `DailyEntryCreate` | `DailyEntryDto` |
| PATCH | `/api/v1/daily-entries/today` | `DailyEntryUpdate` | `DailyEntryDto` |
| POST | `/api/v1/daily-entries/today/todo-items` | `TodoCreate` | `TodoDto` |
| PATCH | `/api/v1/todo-items/{id}` | `TodoUpdate` | `TodoDto` |
| DELETE | `/api/v1/todo-items/{id}` | — | `Void` |
| POST | `/api/v1/reflections` | `ReflectionCreate` | `ReflectionDto` |
| PATCH | `/api/v1/reflections/{id}` | `ReflectionUpdate` | `ReflectionDto` |

> Day boundary: 06:00 in `Asia/Seoul`. The `today` endpoints rely on this; clients must not infer "today" from UTC midnight.

### Profile + Grass

| Method | Path | Query | Response |
|--------|------|-------|----------|
| GET | `/api/v1/profiles/me` | — | `ProfileDto` |
| GET | `/api/v1/profiles/{userId}` | — | `PublicProfileDto` |
| GET | `/api/v1/profiles/{userId}/grass` | `from=YYYY-MM-DD&to=YYYY-MM-DD` | `List<GrassDayDto>` |
| GET | `/api/v1/profiles/me/grass` | `from=YYYY-MM-DD&to=YYYY-MM-DD` | `List<GrassDayDto>` |
| GET | `/api/v1/profiles/{userId}/reflections` | (paging params) | `List<ReflectionDto>` |

`GrassDayDto`:
```json
{
  "date": "2026-04-26",
  "missionCompleted": true,
  "completedTodoCount": 3,
  "reflectionSubmitted": true,
  "intensity": 3
}
```

### Rooms (group accountability)

| Method | Path | Body | Response |
|--------|------|------|----------|
| POST | `/api/v1/rooms` | `CreateRoomRequest` | `RoomService.RoomSummary` |
| GET | `/api/v1/rooms` | — | `List<RoomService.RoomSummary>` |
| GET | `/api/v1/rooms/{id}/members` | — | `List<RoomService.MemberSummary>` |
| GET | `/api/v1/rooms/{id}/today` | (no body) | `List<RoomService.MemberTodayDto>` |
| POST | `/api/v1/rooms/{id}/invites` | — | `RoomService.InviteSummary` |
| POST | `/api/v1/rooms/join` | `JoinRequest` | `RoomService.MemberSummary` |
| DELETE | `/api/v1/rooms/{id}/members/me` | — | `Void` |
| PATCH | `/api/v1/rooms/{id}/members/me/minimum` | `UpdateMinimumRequest` | `RoomService.MemberSummary` |

### Room Chat

| Method | Path | Query | Body | Response |
|--------|------|-------|------|----------|
| GET | `/api/v1/rooms/{id}/messages` | `cursor`, `limit` | — | `ChatService.MessagePage` |
| POST | `/api/v1/rooms/{id}/messages` | — | `SendMessageRequest` | `ChatService.MessageDto` |

> Sending a message triggers a STOMP fan-out via `RealtimePublisher` so connected members in the room receive the message in near-real-time. FE must dedupe REST + WS to avoid duplicate display.

### Stats

| Method | Path | Query | Response |
|--------|------|-------|----------|
| GET | `/api/v1/stats/monthly` | `month=YYYY-MM` | `MonthlyStatsDto` |

### Notifications

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET | `/api/v1/me/notification-prefs` | — | `NotificationPrefDto` |
| PUT | `/api/v1/me/notification-prefs` | `UpdatePrefsRequest` | `NotificationPrefDto` |
| POST | `/api/v1/me/push-tokens` | `RegisterTokenRequest` | `PushTokenDto` |
| DELETE | `/api/v1/me/push-tokens/{id}` | — | `Void` |

## Realtime (STOMP over `/ws`)

- Handshake: `ws(s)://<host>/yeolsal/ws` (HTTP upgrade); SockJS fallback **off**.
- `CONNECT` frame must include `Authorization: Bearer <accessToken>` header (validated by `JwtChannelInterceptor`).
- Topic conventions:
  - `/topic/rooms/{roomId}/messages` — chat fan-out
  - `/topic/rooms/{roomId}/members` — member-added events
  - `/user/queue/notifications` — per-user direct messages
- Server emits `RealtimeEvent` payloads; clients dedupe by event ID against REST responses.

## Validation

All controller DTOs use `@Valid` with Bean Validation (`@NotBlank`, `@NotNull`, `@Size`, `@Email`, `@Pattern`). Validation failures map to `400 VALIDATION`.

## Rate Limiting

`RateLimitFilter` runs first in the chain. Specific limits live in code; exceeding them returns the framework default (typically 429).
