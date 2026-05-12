# Source Tree Analysis

Annotated map of the `yeosal/` (project name `yeolsal`) repository. Two parts plus shared infra and docs.

## Top-Level Layout

```text
yeosal/
├── FE/                         # Part: fe — Expo React Native mobile app
├── BE/                         # Part: be — Spring Boot 3.3 API
├── infra/                      # Docker Compose, nginx reverse proxy, deploy script
├── docs/                       # Project-knowledge docs (this file lives here)
├── scripts/                    # verify.sh, test.sh, build.sh — repo-wide entrypoints
├── rules/                      # ECC-derived language/web rule sets (committed)
├── _bmad/                      # BMad agent harness config + module skills
├── _bmad-output/               # BMad workflow artifacts (planning/implementation)
├── .claude/                    # Claude Code skills, plugins, settings
├── package.json                # Root npm workspace (FE only); React 19 override pin
├── tsconfig.json               # Root TS base config
├── AGENTS.md                   # Repo-wide agent operating contract
├── CONTRIBUTING.md             # PR/merge procedure (incident-driven rules)
├── RUNBOOK.md                  # Run/test/build/deploy how-to
└── guide.md                    # ECC harness setup guide
```

Integration boundary: **FE talks to BE only over REST (`/yeolsal/api/v1/*`) and STOMP-over-WebSocket (`/ws`)**. There is no shared code package; types are duplicated by hand at the boundary.

---

## FE — `FE/` (part_id: fe)

```text
FE/
├── app/                        # expo-router file-based routes
│   ├── _layout.tsx             # Root Stack; mounts QueryProvider + RealtimeProvider
│   ├── (tabs)/                 # Tab group (parens = no path segment)
│   │   └── _layout.tsx         # Tab nav for Today/Feed/Monthly/Profile
│   ├── rooms/                  # Room list + detail screens
│   ├── index.tsx               # Splash/landing entry
│   ├── login.tsx, signup.tsx, join.tsx
│   ├── notification-settings.tsx
│   └── friend-profile.tsx
├── src/
│   ├── api/                    # Typed REST client (apiRequest<T>, ApiError, AuthTokens)
│   │   ├── client.ts           # ENTRY: fetch wrapper + 401 refresh
│   │   ├── config.ts           # API_BASE_URL (EXPO_PUBLIC_API_BASE_URL)
│   │   ├── chat.ts, rooms.ts, reflections.ts, notifications.ts
│   │   └── types.ts
│   ├── auth/                   # AuthContext, login/refresh hooks
│   ├── components/             # Domain UI
│   │   ├── ui/                 # Primitives: Button/Card/Surface/Text/EmptyState/Skeleton
│   │   ├── today/              # Goal/Todo/Reflection/Friends-today widgets
│   │   ├── chat/               # MessageBubble, ChatList, MessageInput, SystemMessage
│   │   ├── rooms/              # InviteCodeSheet, RoomMemberCard, RoomMinimumSettings
│   │   ├── grid/, feedback/    # Reusable layout/feedback widgets
│   │   ├── BottomNav.tsx
│   │   └── Screen.tsx          # Screen container with safe-area + theme tokens
│   ├── domain/                 # Pure domain helpers (date, mission rules)
│   ├── hooks/                  # Cross-cutting RN hooks
│   ├── lib/
│   │   ├── query/              # TanStack Query hooks (auth-gated)
│   │   │   └── hooks/          # ENTRY: useToday, useFeed, useChatRealtime, useRooms, useProfile
│   │   ├── realtime/           # STOMP client + dedupe logic
│   │   ├── notifications.ts, push.ts, sentry.ts, toast.ts
│   │   ├── chatRead.ts, fonts.ts, calendar.ts, bucket.ts
│   │   └── __tests__/
│   ├── providers/
│   │   ├── QueryProvider.tsx       # AsyncStorage-persisted QueryClient
│   │   └── RealtimeProvider.tsx    # Single STOMP client; screen hooks subscribe here
│   ├── theme/                  # Design tokens (Risograph + neobrutalist palette)
│   └── types/
├── android/, ios/              # Native projects (expo prebuild output)
├── assets/                     # brand/, fonts/, splash, app icons
├── app.config.ts, app.json     # Expo config (app id app.yeosal.mobile)
├── babel.config.js, metro.config.js
├── eslint.config.js            # Flat config: @eslint/js + typescript-eslint
├── jest.setup.ts               # Global Sentry mock + Animated stub
├── eas.json                    # preview=APK, production=AAB/iOS
├── package.json, tsconfig.json # @/* alias → src/*
└── .env.example
```

**Critical entry points**: `app/_layout.tsx` (root nav), `src/api/client.ts` (every HTTP call), `src/providers/RealtimeProvider.tsx` (single STOMP client).

---

## BE — `BE/` (part_id: be)

```text
BE/
├── src/main/java/com/yeosal/api/
│   ├── YeosalApiApplication.java     # @SpringBootApplication entry
│   ├── auth/                         # JWT, Kakao OAuth, login/refresh
│   │   ├── AuthController.java       # /api/v1/auth/{signup,login,kakao/*,refresh,logout}
│   │   ├── JwtAuthenticationFilter.java
│   │   └── ...
│   ├── common/                       # Cross-cutting infra
│   │   ├── ApiResponse.java          # record { data: T }
│   │   ├── ApiErrorResponse.java     # record { error: { code, message } }
│   │   ├── ApiExceptionHandler.java  # @RestControllerAdvice (single source of error mapping)
│   │   ├── BadRequest/NotFound/Forbidden/UnauthorizedException.java
│   │   ├── CurrentUser.java          # @AuthenticationPrincipal helper
│   │   ├── RateLimitFilter.java      # First in chain (before JwtAuthenticationFilter)
│   │   ├── SecurityConfig.java       # Filter chain + CORS bean
│   │   └── StartupConfigValidator.java
│   ├── daily/                        # Daily entries, todos, reflections
│   │   ├── DailyController.java      # /api/v1/daily-entries/*, /reflections/*
│   │   ├── DailyService.java
│   │   └── repositories: DailyEntryRepository, ReflectionRepository, TodoItemRepository, MonthlyGoalRepository
│   ├── friend/                       # Friend graph + daily feed
│   │   ├── FriendController.java     # /api/v1/friends/*, /api/v1/feed/daily
│   │   └── FriendService.java
│   ├── notification/                 # Push tokens + prefs
│   │   └── NotificationController.java # /api/v1/me/notification-prefs, /me/push-tokens
│   ├── profile/                      # Public profile + grass
│   │   └── ProfileController.java    # /api/v1/profiles/{me,{userId},{userId}/grass,{userId}/reflections}
│   ├── realtime/                     # STOMP infra
│   │   ├── WebSocketConfig.java      # /ws endpoint, /topic /queue /user prefixes
│   │   ├── JwtChannelInterceptor.java # Authn at STOMP CONNECT frame
│   │   ├── RealtimePublisher.java    # Single emit point for services
│   │   └── RealtimeEvent.java        # Sealed event taxonomy
│   ├── room/                         # Group rooms + invites + chat
│   │   ├── RoomController.java       # /api/v1/rooms/*
│   │   ├── chat/ChatController.java  # /api/v1/rooms/{id}/messages
│   │   └── repositories
│   ├── stats/                        # Monthly counts
│   │   └── StatsController.java      # /api/v1/stats/monthly
│   └── user/                         # Profile + timezone state
├── src/main/resources/
│   ├── application.yml               # JPA validate, Flyway on, /yeolsal context path
│   ├── application-prod.yml
│   └── db/migration/                 # Flyway: V1__init.sql ... V10__reflection_updated_at.sql
├── src/test/java/                    # Mirror of main packages; JUnit 5 + Testcontainers
├── build.gradle                      # Spring Boot 3.3.5 plugin, Java 21 toolchain
├── settings.gradle
├── Dockerfile
└── gradle/, gradlew, gradlew.bat
```

**Critical entry points**: `YeosalApiApplication.java` (boot), `SecurityConfig.java` (filter chain), `WebSocketConfig.java` (STOMP), `ApiExceptionHandler.java` (error mapping), `RealtimePublisher.java` (realtime fan-out).

---

## Infra & Scripts

```text
infra/
├── docker-compose.yml          # api + postgres + nginx (external port 8088)
├── nginx/                      # Reverse-proxy config
└── deploy.sh                   # Server-side deploy entrypoint

scripts/
├── verify.sh                   # FE checks + BE test/build + Docker image build
├── test.sh                     # FE lint/typecheck/jest + BE gradle test
└── build.sh                    # FE export + BE build + Docker image
```

---

## Multi-Part Integration Map

```text
FE (mobile)                                      BE (API)
─────────────────────                            ─────────────────────
src/api/client.ts  ──── HTTPS REST ────────►  /yeolsal/api/v1/* (8 controllers)
src/lib/realtime   ──── STOMP/WSS ─────────►  /ws (JWT auth at CONNECT)
expo-secure-store         (no shared types — duplicated by hand)
expo-notifications ──── token register ────►  /api/v1/me/push-tokens
@sentry/react-native ── DSN runtime only ──   (separate Sentry project)
```

Detailed contracts: see `integration-architecture.md`.
