---
project_name: 'yeolsal'
user_name: 'rearleg'
date: '2026-05-09'
sections_completed: ['technology_stack', 'language_rules', 'framework_rules', 'testing_rules', 'code_quality_rules', 'workflow_rules', 'critical_rules']
existing_patterns_found: 12
status: 'complete'
rule_count: 130
optimized_for_llm: true
---

# Project Context for AI Agents

_This file contains critical rules and patterns that AI agents must follow when implementing code in this project. Focus on unobvious details that agents might otherwise miss._

---

## Technology Stack & Versions

### Repository

- Monorepo at `yeosal/` (note the directory name is `yeosal` while `project_name` is `yeolsal`).
- Top-level layout:
  - `FE/` — Expo React Native app (npm workspace)
  - `BE/` — Spring Boot 3 API (Gradle)
  - `infra/` — Docker Compose, nginx reverse proxy
  - `docs/` — product, architecture, API contract, design system, test plan
  - `scripts/` — `verify.sh`, `test.sh`, `build.sh`
  - `rules/`, `_bmad/`, `.claude/skills/` — agent harness materials

### Backend (`BE/`)

- Java **21** (toolchain pinned), Spring Boot **3.3.5**, Gradle.
- Group `com.yeosal`, app entry `com.yeosal.api.YeosalApiApplication`.
- Starters: `web`, `websocket`, `security`, `validation`, `data-jpa`.
- Persistence: PostgreSQL via Spring Data JPA + Hibernate, `ddl-auto: validate`, `open-in-view: false`.
- Migrations: Flyway core + `flyway-database-postgresql`. Files: `BE/src/main/resources/db/migration/V<N>__<slug>.sql` (currently V1–V10).
- Auth: `io.jsonwebtoken:jjwt 0.12.6` (api/impl/jackson) + Kakao OAuth.
- API docs: `springdoc-openapi-starter-webmvc-ui:2.6.0`.
- Realtime: STOMP over WebSocket (`api.realtime.WebSocketConfig`, `JwtChannelInterceptor`, `RealtimePublisher`).
- Tests: JUnit 5 (`useJUnitPlatform`), `spring-boot-starter-test`, `spring-security-test`, **Testcontainers** (`junit-jupiter` + `postgresql`).
- Server: `server.port=8080`, context-path `/yeolsal` → external API base is `https://api.rearleg.com/yeolsal/api/v1`.

#### BE package layout (package-by-feature)

`com.yeosal.api.{auth, common, daily, friend, notification, profile, realtime, room, stats, user}`

- `common/` holds cross-cutting infra: `ApiResponse`, `ApiErrorResponse`, `ApiExceptionHandler`, custom exceptions (`BadRequest/NotFound/Forbidden/Unauthorized`), `CurrentUser`, `RateLimitFilter`, `SecurityConfig`, `StartupConfigValidator`.

### Frontend (`FE/`)

- Expo SDK **54.0.34**, React Native **0.81.5**, React **19.1.0**, TypeScript **~5.9.0** (strict mode on, base extends `expo/tsconfig.base`).
- Routing: **expo-router 6** (`FE/app/` is the route surface; `(tabs)` group, `rooms/`, plus auth/login/signup/join/notification-settings/friend-profile/index).
- State / data: **TanStack Query 5.100.6** with `react-query-persist-client` + `query-async-storage-persister`.
- Realtime: `@stomp/stompjs 7.3.0` (FE STOMP client in `src/lib/realtime`, wired via `src/providers/RealtimeProvider.tsx`).
- Storage: `@react-native-async-storage/async-storage 2.2.0`, `expo-secure-store ~15.0.8`.
- UI infra: `@shopify/flash-list 2.0.2`, `@expo/vector-icons`, `react-native-svg`, `react-native-safe-area-context`, `react-native-screens`, `expo-haptics`, `expo-clipboard`.
- Observability: `@sentry/react-native ~7.2.0` (auto-disabled when `EXPO_PUBLIC_SENTRY_DSN` is unset).
- Push: `expo-notifications`.
- Tooling: ESLint 9 + `typescript-eslint`, `jest 29.7` + `jest-expo`, `@testing-library/react-native`.
- Path alias: `@/*` → `src/*` (also includes `app/`).
- API base default: `EXPO_PUBLIC_API_BASE_URL` (defaults to `https://api.rearleg.com/yeolsal/api/v1`).
- App ID: `app.yeosal.mobile`.

#### FE src/ layout (feature-oriented)

`src/{api, auth, components, domain, hooks, lib, providers, theme, types}`

- `lib/` includes `query/` (TanStack hooks), `realtime/` (STOMP), `notifications.ts`, `push.ts`, `sentry.ts`, `bucket.ts`, `calendar.ts`, `chatRead.ts`, `toast.ts`, `fonts.ts`.
- `providers/` exposes `QueryProvider.tsx`, `RealtimeProvider.tsx`.

### Infra & Deploy

- Docker Compose composes `api`, `postgres`, `nginx` (port 8088 externally).
- `BE/Dockerfile` produces the API image. `infra/.env` carries secrets.
- Mobile builds via **EAS** (`eas.json` profiles: `preview` = APK, `production` = AAB/iOS).

---

## Critical Implementation Rules

### Language-Specific Rules

#### Java 21 (BE)

- DTO / envelope / error types are `record`s (`ApiResponse<T>`, `ApiErrorResponse`); never plain classes.
- Domain exceptions extend `RuntimeException` with a single-message constructor (`BadRequest/NotFound/Forbidden/Unauthorized`). Add a corresponding `@ExceptionHandler` in `ApiExceptionHandler` whenever you introduce a new one — otherwise it falls through to 5xx.
- Constructor injection only — no `@Autowired` fields, including in `@Configuration` classes.
- Never touch lazy collections outside a `@Transactional` boundary. `LazyInitializationException` is intentionally treated as a 5xx bug.
- A caller-side `IllegalArgumentException` reaching the controller is mapped to `400 VALIDATION` deliberately (keeps it out of the FE 5xx Sentry channel).
- JJWT is **0.12.x** — use `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)`. Do not port 0.11.x snippets (`parseClaimsJws`, `setSigningKey`) verbatim.
- Use `java.time` exclusively. The day-boundary for daily missions is **06:00 in `Asia/Seoul`**, not UTC midnight.

#### TypeScript 5.9 / Expo 54 (FE)

- `strict: true` is on. No `any`. Type external input as `unknown` and narrow.
- Do not read `process.env` directly — Expo runtime is not Node. Use the guarded pattern in `src/api/config.ts`. Only `EXPO_PUBLIC_*` keys are bundled to the client.
- All API calls must go through `apiRequest<T>` in `src/api/client.ts`. Direct `fetch` is forbidden — only `apiRequest` has 401-refresh and `ApiError` handling.
- Throw and catch `ApiError` (subclass of `Error`). When branching on `error.code`, the string must match a code emitted by the BE `ApiErrorResponse` enum.
- Auth tokens live in `expo-secure-store` under keys `yeosal.accessToken` / `yeosal.refreshToken`. `AsyncStorage` is reserved for the TanStack Query persister.
- Use the `@/*` path alias for `src/*`. No new deep relative imports.
- Component props are named `interface`s. Do not use `React.FC`.
- Immutable updates only — no `Array#push` or field assignment; spread/`map`/`filter` return new objects.

### Framework-Specific Rules

#### Spring Boot 3.3 (BE)

- Controller paths use `/api/v1/...` only; the `/yeolsal` context-path is applied automatically by `application.yml`.
- All controller responses must be wrapped in `ApiResponse<T>` (`ApiResponse.of(dto)`). The FE assumes the `{ data: ... }` envelope shape.
- There is exactly one `@RestControllerAdvice`: `ApiExceptionHandler`. Add new handlers there; do not introduce a second advice class.
- Security filter order is fixed (do not change): `RateLimitFilter` → `JwtAuthenticationFilter`, both anchored before `UsernamePasswordAuthenticationFilter`.
- Public endpoint whitelist (in `SecurityConfig`): `/api/v1/auth/{signup,login,kakao/{authorize,callback,exchange},refresh}`, `/v3/api-docs/**`, `/swagger-ui/**`, `/ws`, `/ws/**`. Everything else requires authentication.
- `/ws` is `permitAll` at the HTTP layer; JWT auth runs on the STOMP `CONNECT` frame in `JwtChannelInterceptor`. Removing the `/ws` permit blocks the handshake itself.
- CORS is sourced from `yeosal.cors.allowed-origins` (comma-separated) with `allowCredentials = false` (Bearer-only). Do not enable cookies/credentials.
- STOMP topic conventions: `/topic/*` and `/queue/*` for server→client, `/user/*` for per-principal, `/app` reserved for client-published frames. SockJS fallback is intentionally off.
- All realtime emissions go through `RealtimePublisher`. Services do not inject `SimpMessagingTemplate` directly.
- JPA `open-in-view: false`. Resolve lazy associations inside `@Transactional` service methods or map to DTOs there.
- Hibernate is in `validate` mode. Schema changes require a Flyway migration (`V<N>__<slug>.sql`); entity-only changes will fail boot.
- Use `@Valid` on controller DTOs. `MethodArgumentNotValidException` is mapped to `400 VALIDATION` by the global handler.

#### Expo Router 6 / React Native (FE)

- Routes are file-based under `FE/app/`. New screens must also be registered in the relevant `_layout.tsx` (`Stack.Screen`); creating the file alone leaves nav options empty.
- TanStack Query is persisted to `AsyncStorage` by `QueryProvider`. Never call `queryClient.clear()` — it nukes persisted state. Use `invalidateQueries` instead.
- All data fetching goes through domain hooks in `src/lib/query/hooks/*`. Components do not call `useQuery` directly.
- Realtime subscriptions share the single STOMP client owned by `RealtimeProvider`. Screen-level hooks (e.g., `useChatRealtime`) subscribe/unsubscribe; do not create another STOMP client.
- Dedupe REST/WS: a WS event must not overwrite cache directly — check a dedupe key, then invalidate or merge per the `useChatRealtime` pattern.
- Adaptive polling is a backoff fallback used only when WS is down. Do not run polling alongside an active WS subscription.
- Sentry auto-disables when `EXPO_PUBLIC_SENTRY_DSN` is empty. Import via `src/lib/sentry.ts` only — never `@sentry/react-native` directly in app code.
- Push tokens are registered with the BE `notification` endpoint; do not persist the token in `SecureStore`. The server is the source of truth.
- `expo-secure-store` is a native module — adding/removing it requires `adb uninstall app.yeosal.mobile` (or equivalent) and a fresh build. Metro reload is insufficient.
- `@shopify/flash-list` requires `estimatedItemSize`; omitting it causes scroll jank and warnings.

### Testing Rules

#### Backend (BE)

- Tests live in `BE/src/test/java/com/yeosal/api/<module>/...` mirroring the main package layout. New modules require their test counterpart at the same path.
- JUnit 5 only (`useJUnitPlatform`); no JUnit 4 annotations.
- Use `@SpringBootTest` for full integration, `@WebMvcTest` for web slices, `@DataJpaTest` for JPA slices. Do not collapse integration tests into pure unit tests — Flyway and the security chain are validated only at integration scope.
- DB integration tests use Testcontainers PostgreSQL (e.g., `postgres:16`). H2 is forbidden (Postgres-specific dialect, jsonb, and partial expression indexes will not behave correctly).
- Use `spring-security-test` (`MockMvc`, `@WithMockUser`, or a JWT helper). Do not bypass auth by treating endpoints as anonymous in tests.
- When adding a partial unique expression index (cf. V8/V9 MILESTONE dedup), the matching service `INSERT ... ON CONFLICT ... WHERE pred DO ...` clause must be exact. Add a conflict-path integration test alongside the migration.
- TDD order is enforced: RED → GREEN → refactor. Do not push BE changes until `./gradlew test` is green.
- Test naming: `methodName_scenario_expectedBehavior()` or `@DisplayName("...")`. Use AssertJ `assertThat(...)`.

#### Frontend (FE)

- Test files must match `FE/src/**/__tests__/**/*.test.{ts,tsx}` — Jest will not discover them elsewhere.
- Sentry is mocked globally in `jest.setup.ts`. Do not re-mock `@sentry/react-native` per test — duplicate mocks conflict with setup.
- `Animated/NativeAnimatedHelper` is also stubbed globally; do not re-mock it.
- Use `@testing-library/react-native`. Do not import `react-test-renderer` directly for snapshots.
- TanStack Query hook tests must wrap in a `QueryClientProvider` and stub `fetch` (no real network).
- Realtime hook tests mock `RealtimeProvider`; never open a real WebSocket. Simulate emits through a mock publisher.
- Use `waitFor`/`findBy*` for async assertions. Arbitrary `setTimeout` waits cause flakes.
- Jest is configured with `forceExit: true`, but open handles still indicate a bug — fix the leaking timer/cleanup rather than relying on force-exit.
- Pre-push order: `npm run lint` → `npm run typecheck` → `npm test`, all green.

#### Project-wide

- Coverage target is 80%, focused on domain and service logic. Trivial getters and config classes are excluded.
- `bash scripts/verify.sh` from the repo root runs FE + BE verification together; run it once before declaring a feature complete.

### Code Quality & Style Rules

#### Linting / Formatting

- FE uses ESLint flat config (`FE/eslint.config.js`) with `@eslint/js recommended` + `typescript-eslint recommended`. Do not add legacy `.eslintrc`.
- `@typescript-eslint/no-unused-vars` is `error`; intentionally unused params must use a `_` prefix.
- `.expo/`, `dist/`, `node_modules/` are excluded from lint — never edit build output.
- BE follows Google Java style: one public top-level type per file, readability over cleverness.

#### File / Folder Structure

- BE is package-by-feature: `auth, common, daily, friend, notification, profile, realtime, room, stats, user`. New domains live as siblings; do not introduce layered packaging that splits controller/service/repository across separate roots.
- FE is feature-oriented under `src/{api, auth, components, domain, hooks, lib, providers, theme, types}`. New domain components belong in `src/components/<feature>/`.
- File size: hard cap 800 lines; typical 200–400. Split large files into the same folder.
- Function size: 50 lines max; nesting depth 4 max — flatten with early returns.

#### Naming

- Java: `PascalCase` types/records/enums, `camelCase` members, `SCREAMING_SNAKE_CASE` static finals, all-lowercase packages.
- TypeScript: `PascalCase` types/components, `camelCase` values, `use*` for custom hooks, `is/has/should/can` for booleans, `UPPER_SNAKE_CASE` for module-level constants.
- Flyway migrations: `V<N>__<slug>.sql`, where `<N>` is the smallest free integer and `<slug>` is a snake_case noun phrase.
- Git branches: `feat/...`, `fix/...`, `chore/...`.

#### Comments & Documentation

- Default to no comments. When you do write one, explain only the **why** (hidden constraint, subtle invariant, workaround for a specific bug, surprising behavior). Identifiers already say what the code does.
- Do not reference current task / fix / callers in comments (no "Used by X", "Added for Y flow", "issue #123"). Those belong in the PR description and rot in code.
- No emojis in source files or docs unless explicitly requested.

#### Security-related Style

- Never hardcode secrets in `application.yml`. Use `${ENV_VAR:default}` with safe placeholders for dev defaults.
- Error messages must never leak stack traces, internal paths, or SQL details. The global `ApiExceptionHandler` already sanitizes — do not bypass it.
- Never log tokens, passwords, or PII.
- No `console.log` in FE production code.

### Development Workflow Rules

#### Branches & Commits

- Branch prefixes: `feat/`, `fix/`, `chore/`. Always branch from `main`.
- Commit format: `<type>: <description>` (`feat|fix|refactor|docs|test|chore|perf|ci`). Body explains the **why**. Never add automated attribution lines (e.g., `Co-Authored-By`) — disabled globally.
- TDD commits may split RED → GREEN → refactor.

#### Pre-push Verification

- BE changes: `cd BE && ./gradlew test` must be green before push.
- FE changes: `cd FE && npm run lint && npm run typecheck && npm test` must all be green.
- Feature-complete: `bash scripts/verify.sh` from repo root (FE checks + BE test/build + Docker image build when Docker is available).

#### Stack PR Merge Procedure (incident-driven, mandatory)

A prior stack-PR squash merged via the GitHub UI dropped commits onto the stack base branch instead of `main`, causing migrations V7/V8 to never reach production (cf. PR #36). Enforce:

1. Merge stack PRs **from the bottom of the base chain upward**.
2. Before each merge, verify the PR's base is `main` (`gh pr view <N> --json baseRefName,mergeStateStatus`). If the base is another stack branch, stop and either merge that base first or retarget this PR's base to `main`.
3. Merge with **Delete branch** enabled — when the base branch is deleted, GitHub auto-rebases stack PRs above onto `main`.
4. After merge, verify the squash commit reached `main`: `git merge-base --is-ancestor <merge-commit-oid> origin/main`.

#### Migrations

- Path: `BE/src/main/resources/db/migration/V<N>__<slug>.sql`; `<N>` is the smallest free integer.
- Flyway runs each migration exactly once — prefer idempotent SQL (`drop ... if exists`, `insert ... on conflict do nothing`).
- A partial unique expression index must exactly match the service's `INSERT ... ON CONFLICT ... WHERE pred DO ...` clause (cf. V8/V9 MILESTONE dedup).
- Any change with significant operational impact (migrations, security, auth wiring) must include a "Post-merge user action" section in the PR body.

#### Secrets

- `.env` files are never committed. Expected values live in `infra/.env.example` and `FE/.env.example`.
- JWT secret must be at least 32 chars; the `dev-only-change-me-...` default must never reach production. `StartupConfigValidator` enforces this.
- Kakao REST API key lives on the BE only. FE proxies through `/auth/kakao/authorize`; never expose the key as an `EXPO_PUBLIC_*` variable.

#### Outage Diagnosis Priority (per RUNBOOK)

1. `docker compose exec api cat /app/COMMIT` — which commit is running.
2. `docker compose logs api --since 5m | grep -iE "\[chat\]|\[db\]|\[validation\]|exception"` — root cause via `ApiExceptionHandler` logs.
3. `flyway_schema_history` — has the migration actually applied?
4. If ApplicationContext boot is suspect: grep for `Error creating bean|No default constructor` (cf. PR #34 missing `@Autowired` on `RateLimitFilter`).

#### Deployment

- Compose stack (`infra/docker-compose.yml`) runs `api`, `postgres`, `nginx` on external port 8088.
- Mobile builds via EAS: `preview` = APK, `production` = AAB / iOS.
- Production API base URL: `https://api.rearleg.com/yeolsal/api/v1`. The FE `EXPO_PUBLIC_API_BASE_URL` default must stay aligned.

### Critical Don't-Miss Rules

#### Anti-Patterns

- Do not prefix `/yeolsal` in controller paths — the context-path is added automatically; controllers use `/api/v1/...`.
- Never return a raw DTO from a controller — wrap in `ApiResponse.of(dto)` so the FE envelope `{ data: ... }` holds.
- Adding a new domain exception without a matching `@ExceptionHandler` in `ApiExceptionHandler` results in a generic 5xx and pollutes the Sentry server-bug channel.
- Direct `fetch` in FE bypasses 401 refresh and `ApiError` mapping. Always use `apiRequest<T>`.
- `queryClient.clear()` deletes the AsyncStorage-persisted cache. Use `invalidateQueries` instead.
- Do not import `@sentry/react-native` directly — go through `src/lib/sentry.ts`.
- Do not instantiate a second STOMP client on the FE — the single `RealtimeProvider` instance is shared.
- BE services must not inject `SimpMessagingTemplate` directly; emit through `RealtimePublisher`.
- Do not introduce a second `@RestControllerAdvice` — extend the existing `ApiExceptionHandler`.
- No `@Autowired` field injection in Java code — constructor injection only.
- Do not change `ddl-auto` away from `validate`. Schema changes require Flyway migrations.
- Do not use H2 for integration tests — Postgres-specific features (jsonb, partial expression indexes) do not behave correctly. Use Testcontainers PostgreSQL.
- Do not merge stack PRs out of order or without verifying `baseRefName == main` (incident: V7/V8 missing in prod).

#### Domain Edge Cases

- The daily-mission day boundary is **06:00 in `Asia/Seoul`**, not UTC midnight. Migration V2 encodes this. New date comparisons must use `Asia/Seoul`.
- Per-user timezone editing is not yet implemented — `Asia/Seoul` is hardcoded. When timezone-per-user lands, revisit V2's semantics.
- A reflection counts only when the daily entry exists **and** the reflection is submitted before the next day's 06:00. Either alone does not count.
- STOMP CONNECT auth runs after the HTTP handshake — the FE must branch on the `CONNECT` result, not the HTTP 200/upgrade success.
- Missing a REST/WS dedupe key surfaces duplicate messages — follow the `useChatRealtime` pattern.
- With `open-in-view: false`, exposing a lazy collection past the service boundary throws `LazyInitializationException` (mapped to 5xx). Resolve associations inside `@Transactional` or map to DTOs.
- A partial unique index whose predicate does not exactly match the service's `INSERT ... ON CONFLICT ... WHERE pred DO ...` clause causes `DataIntegrityViolationException` at runtime.

#### Security Rules

- No hardcoded secrets in code, `application.yml`, or the FE bundle. Environment variables only.
- JWT secret must be ≥ 32 chars. The `dev-only-change-me-...` default reaching production is rejected by `StartupConfigValidator` at boot.
- Kakao REST API key lives on the BE only — never expose it as `EXPO_PUBLIC_*`.
- Do not leak stack traces, internal paths, or SQL in error responses. Do not bypass `ApiExceptionHandler`.
- Never log tokens, passwords, or PII. Log prefixes are channel-scoped (`[chat]`, `[db]`, `[validation]`); follow the convention.
- All controller input DTOs must use `@Valid` together with `@NotBlank/@NotNull/@Size` etc. — missing validation lets bad payloads NPE the service layer.

#### Performance Gotchas

- `@shopify/flash-list` requires `estimatedItemSize` — omitting it causes scroll jank and warnings.
- With `open-in-view: false`, lazy access from a controller throws `LazyInitializationException` and surfaces as 5xx.
- Do not rely on TanStack Query `staleTime`/`gcTime` defaults — set them per domain. No unbounded caches.
- Adding a native module without `adb uninstall app.yeosal.mobile` and a fresh build leaves the module out of the app binary.
- Running adaptive polling alongside an active WS subscription duplicates fetches and races the cache.

---

## Usage Guidelines

**For AI Agents**

- Read this file before implementing any code in this repository.
- Follow ALL rules exactly as documented. When in doubt, prefer the more restrictive option.
- Cite the specific rule (section + bullet) in your reasoning when a decision is rule-driven.
- If a new pattern emerges that future agents will need, propose an addition rather than acting on tribal knowledge.

**For Humans**

- Keep this file lean and focused on agent needs — drop rules that have become obvious or are now enforced by tooling.
- Update it whenever the technology stack, security boundaries, or operational invariants change (especially Flyway migration conventions, security filter chain, and the day-boundary semantics).
- Review quarterly. Past-incident rules (e.g., the stack PR merge procedure) should stay until the underlying gap has a hard guard.
- Treat the BMad output `_bmad-output/project-context.md` as the canonical copy; do not duplicate it under `docs/`.

Last Updated: 2026-05-09
