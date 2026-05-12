# Architecture — BE (Yeosal API)

## Executive Summary

Spring Boot 3.3 monolith on Java 21 with PostgreSQL. The API exposes a small REST surface under `/yeolsal/api/v1/*` and a single STOMP WebSocket endpoint at `/ws`. Persistence is JPA in `validate` mode with Flyway as the only schema-change channel. Auth is stateless: bearer JWT for REST, JWT validated at the STOMP CONNECT frame for realtime. Errors are funneled through one `@RestControllerAdvice`.

## Technology Stack

| Layer | Tech | Version |
|-------|------|---------|
| Runtime | Java | 21 (toolchain pinned) |
| Framework | Spring Boot | 3.3.5 |
| Web / REST | spring-boot-starter-web | 3.3.5 |
| Realtime | spring-boot-starter-websocket | 3.3.5 |
| Auth / Security | spring-boot-starter-security + JJWT | 3.3.5 / 0.12.6 |
| Validation | spring-boot-starter-validation | 3.3.5 |
| Persistence | spring-boot-starter-data-jpa (Hibernate, `validate`) | 3.3.5 |
| Migrations | Flyway core + flyway-database-postgresql | latest BOM |
| Driver | postgresql JDBC | runtime |
| API docs | springdoc-openapi-starter-webmvc-ui | 2.6.0 |
| Tests | JUnit 5, spring-security-test, Testcontainers (junit-jupiter, postgresql) | — |
| Build | Gradle (Spring Boot plugin) | — |

## Architecture Pattern

**Package-by-feature monolith.** Cross-cutting concerns live in `common/`; everything else is a sibling feature module.

```
com.yeosal.api/
├── YeosalApiApplication           # @SpringBootApplication
├── auth/        — AuthController, JwtAuthenticationFilter, KakaoClient
├── common/      — ApiResponse, ApiErrorResponse, ApiExceptionHandler,
│                  CurrentUser, RateLimitFilter, SecurityConfig,
│                  StartupConfigValidator, BadRequest/NotFound/Forbidden/UnauthorizedException
├── daily/       — DailyController, DailyService, repositories
├── friend/      — FriendController, FriendService, FriendshipRepository
├── notification/— NotificationController, NotificationService
├── profile/     — ProfileController
├── realtime/    — WebSocketConfig, JwtChannelInterceptor, RealtimePublisher, RealtimeEvent
├── room/        — RoomController, RoomService, room/chat/ChatController, ChatService
├── stats/       — StatsController
└── user/        — User entity + repository
```

**Layer convention inside each feature**:
- `*Controller` (HTTP) — thin; validates input, calls service, wraps result in `ApiResponse.of(dto)`.
- `*Service` (business) — `@Transactional` boundary; resolves lazy associations here.
- `*Repository` (persistence) — Spring Data interface.
- DTOs are nested records or `*Dto` classes; entities are never returned directly.

## Configuration

`src/main/resources/application.yml`:
- `server.port=8080`, context-path `/yeolsal` → external API root is `/yeolsal/api/v1`.
- `spring.jpa.hibernate.ddl-auto=validate`, `open-in-view=false`.
- `spring.flyway.enabled=true` (runs on boot before context refresh).
- `yeosal.auth.jwt-secret`, `access-token-minutes`, `refresh-token-days` — secrets via env.
- `yeosal.kakao.client-id`, `redirect-uri`, `mobile-redirect-uri` — Kakao OAuth.
- `yeosal.cors.allowed-origins` — comma-separated; bound by both `SecurityConfig` and `WebSocketConfig`.

`StartupConfigValidator` rejects boot if the JWT secret looks like the dev placeholder in any non-`dev` profile.

## Security Model

`SecurityConfig` defines a stateless filter chain:

1. **`RateLimitFilter`** (registered first → executed first).
2. **`JwtAuthenticationFilter`** — extracts `Authorization: Bearer ...`, populates `SecurityContext`.

Both anchor before `UsernamePasswordAuthenticationFilter` (Spring Security order requirement).

Public allowlist (`permitAll`):
- `/api/v1/auth/{signup,login,refresh}`
- `/api/v1/auth/kakao/{authorize,callback,exchange}`
- `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`
- `/ws`, `/ws/**` (handshake — JWT enforced inside the channel interceptor)

Everything else: `authenticated()`. CORS reads `yeosal.cors.allowed-origins`, `setAllowCredentials(false)` (Bearer-only). Method allowlist: `GET, POST, PATCH, PUT, DELETE, OPTIONS`. Header allowlist: `Authorization, Content-Type, Accept`.

Passwords are stored with `BCryptPasswordEncoder`.

## Realtime Model (STOMP)

`WebSocketConfig`:
- Endpoint: `/ws` (HTTP upgrade; SockJS fallback is **intentionally disabled** — the FE uses native `WebSocket`).
- Server→client topic prefixes: `/topic/*`, `/queue/*`.
- App→server prefix: `/app` (reserved; not used today).
- Per-principal user destinations: `/user/*`.
- Allowed origins reuse `yeosal.cors.allowed-origins`.

`JwtChannelInterceptor` validates the JWT on the STOMP `CONNECT` frame and binds the principal for the rest of the session. The HTTP handshake is `permitAll` — without that allow-entry the upgrade is rejected before any STOMP frame can be inspected.

`RealtimePublisher` is the single emit point; services (e.g. `ChatService`, `RoomService`, `FriendService`) are constructor-injected with the publisher and never call `SimpMessagingTemplate` directly.

`RealtimeEvent` is a sealed taxonomy of emitted events.

## Data Architecture

Postgres is the source of truth. Schema evolves only through Flyway migrations under `src/main/resources/db/migration/V<N>__<slug>.sql`.

Currently V1–V10:
- V1 — initial schema (`users`, `refresh_tokens`, `friendships`, `daily_entries`, `todo_items`, `reflections`, `monthly_goals`).
- V2 — entry-date 06:00 KST boundary helper.
- V3 — rooms, room_members, room_invites (active-code partial unique).
- V4 — notifications (push tokens, prefs, log).
- V5 — login codes (Kakao exchange / email magic link infra).
- V6 — room minimums + warnings.
- V7 — chat_messages.
- V8/V9 — chat milestone dedup, partial unique per day.
- V10 — `reflections.updated_at`.

Schema details: [`data-models-be.md`](./data-models-be.md).

## API Design

REST surface across 8 controllers (auth, friend, daily, profile, stats, room, room/chat, notification). All endpoints under `/api/v1/...`. Responses wrapped as `ApiResponse<T>` (`{ data: ... }`); errors as `ApiErrorResponse` (`{ error: { code, message } }`).

Endpoint inventory: [`api-contracts-be.md`](./api-contracts-be.md).

## Error Handling

`ApiExceptionHandler` (`@RestControllerAdvice`) is the single source of error mapping:

| Exception | HTTP | code |
|-----------|------|------|
| `BadRequestException` | 400 | `BAD_REQUEST` |
| `MethodArgumentNotValidException`, `MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException`, `IllegalArgumentException` | 400 | `VALIDATION` |
| `UnauthorizedException` | 401 | `UNAUTHORIZED` |
| `ForbiddenException` | 403 | `FORBIDDEN` |
| `NotFoundException` | 404 | `NOT_FOUND` |
| `LazyInitializationException` | 500 | `INTERNAL_ERROR` (logged ERROR — code path is a bug) |
| `DataIntegrityViolationException` | 500 | `INTERNAL_ERROR` (root cause logged WARN with class+message) |
| any other `Exception` | 500 | `INTERNAL_ERROR` |

`IllegalArgumentException` is intentionally mapped to 400 so caller-supplied bad inputs do not pollute the FE 5xx Sentry channel.

## Source Tree (BE)

See [`source-tree-analysis.md`](./source-tree-analysis.md) (BE section).

## Development Workflow

- Build/test: `cd BE && ./gradlew test` and `./gradlew build` (or `gradle ... --no-daemon` with `JAVA_HOME` per RUNBOOK).
- Run with Postgres: `gradle bootRun --no-daemon` plus the `SPRING_DATASOURCE_*` and `YEOSAL_JWT_SECRET` env vars.
- Verify: TDD RED → GREEN before push; `./gradlew test` must be green.

Detailed: [`development-guide-be.md`](./development-guide-be.md), [`../RUNBOOK.md`](../RUNBOOK.md).

## Deployment Architecture

- Image: `BE/Dockerfile` produces the API image; image embeds `/app/COMMIT` for outage diagnosis.
- Compose stack at `infra/docker-compose.yml` runs `api`, `postgres`, `nginx`, exposing port 8088 externally.
- Outage diagnosis priority is documented in `CONTRIBUTING.md` and `RUNBOOK.md`.

## Testing Strategy

- Test layout mirrors `src/main` under `src/test/java/com/yeosal/api/<module>/...`.
- JUnit 5 only; AssertJ for assertions.
- Slice tests: `@WebMvcTest`, `@DataJpaTest`. Full integration: `@SpringBootTest`.
- DB integration uses **Testcontainers PostgreSQL** — H2 is forbidden.
- Auth helpers from `spring-security-test` (`MockMvc` + `@WithMockUser` or a JWT helper).
- Coverage target: 80% on domain/service logic.

## Cross-Cutting Concerns

| Concern | Location |
|---------|----------|
| Error envelope | `common/ApiExceptionHandler` |
| Response envelope | `common/ApiResponse` |
| AuthN | `auth/JwtAuthenticationFilter` (REST) + `realtime/JwtChannelInterceptor` (WS) |
| AuthZ | per-controller `Authentication` arg + per-service ownership checks |
| Rate limiting | `common/RateLimitFilter` (executes before JWT) |
| CORS | `common/SecurityConfig#corsConfigurationSource` |
| Realtime fan-out | `realtime/RealtimePublisher` |
| Bootstrap validation | `common/StartupConfigValidator` |
