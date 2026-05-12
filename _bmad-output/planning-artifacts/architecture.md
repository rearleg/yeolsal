---
stepsCompleted:
  - step-01-init
  - step-02-context
  - step-03-starter
  - step-04-decisions
  - step-05-patterns
  - step-06-structure
  - step-07-validation
inputDocuments:
  - '_bmad-output/planning-artifacts/prd.md'
  - '_bmad-output/planning-artifacts/prfaq-yeolsal.md'
  - '_bmad-output/planning-artifacts/prfaq-yeolsal-distillate.md'
  - '_bmad-output/project-context.md'
  - 'docs/index.md'
  - 'docs/architecture-be.md'
  - 'docs/architecture-fe.md'
  - 'docs/api-contracts-be.md'
  - 'docs/data-models-be.md'
  - 'docs/integration-architecture.md'
  - 'docs/source-tree-analysis.md'
workflowType: 'architecture'
project_name: 'yeolsal (열살방)'
user_name: 'rearleg'
date: '2026-05-10'
status: 'draft'
---

# Architecture Decision Document — yeolsal v1 (열살방)

> This document records implementation-level architectural decisions for the v1 build of 열살방. It builds on the [PRD](./prd.md) and resolves the open questions surfaced in PRD §6 (innovation/constraints) and §13.4 (deferred questions). Brownfield reference for the running system lives in [`docs/architecture-be.md`](../../docs/architecture-be.md), [`docs/architecture-fe.md`](../../docs/architecture-fe.md), [`docs/integration-architecture.md`](../../docs/integration-architecture.md).
>
> Audience: BE + FE engineers building v1, future contributors, AI agents implementing stories. Every decision in §3 / §4 / §5 / §6 must be honored unless explicitly reopened in a future revision.

---

## 1. Project Context

### 1.1 What's running today (brownfield baseline)

- **BE**: Spring Boot 3.3.5 on Java 21, Postgres + Flyway V1–V10, Spring Web/WebSocket/Security/Validation/Data JPA, JJWT 0.12.6, springdoc 2.6.0, Testcontainers. Package-by-feature monolith under `com.yeosal.api.{auth,common,daily,friend,notification,profile,realtime,room,stats,user}`. Single `@RestControllerAdvice` (`ApiExceptionHandler`); single STOMP fan-out (`RealtimePublisher`); JPA `validate` + `open-in-view: false`; CORS bound to `yeosal.cors.allowed-origins`.
- **FE**: Expo SDK 54.0.34, RN 0.81.5, React 19.1.0, TS 5.9 strict, expo-router 6, TanStack Query 5.100.6 with AsyncStorage persist, @stomp/stompjs 7.3.0, expo-secure-store, @sentry/react-native, jest-expo. Single STOMP client owned by `RealtimeProvider`; all HTTP through `apiRequest<T>` (`src/api/client.ts`).
- **Infra**: Docker Compose runs `api`, `postgres`, `nginx`. External port 8088. KR storefront only. Production base URL `https://api.rearleg.com/yeolsal/api/v1`.
- **Existing data model**: V1–V10 cover `users`, `refresh_tokens`, `friendships`, `daily_entries`, `todo_items`, `reflections`, `monthly_goals`, `rooms` (default `max_members=8`, V3), `room_members`, `room_invites` (active-code partial unique), `notification_prefs`, `push_tokens`, `notification_log`, `login_codes`, `chat_messages` (V7–V9 milestone-dedup partial unique on `(room_id, payload->>'userId', payload->>'month')` for `kind='MILESTONE'`), `group_member_minimums`, `group_warnings`, plus V10 `reflections.updated_at`.

### 1.2 What v1 is asking the architecture to deliver

- A **survival-state state machine** layered on top of existing daily-entry compliance, with 06:00 KST day boundary semantics and per-month rule versioning.
- **Three revival sources** (free ticket, personal points, friend gift) with strict idempotency and concurrency-safety against double-revival.
- **Read-only spectator mode** as a routing/authorization branch enforced at both FE and BE.
- **Group point pool** as a per-room append-only counter that must support phase-2 redemption without migration.
- **KakaoTalk Share SDK** integration extending the existing Kakao OAuth path.
- **Server-rendered Risograph SVG poster** for the monthly Final-3 ceremony.
- **Three new STOMP topics** layered on the existing `RealtimePublisher` fan-out.
- A V11+ Flyway migration that adds 7 new tables, modifies one existing constraint (`rooms.max_members` range), and is **safe against existing production rooms**.

### 1.3 What v1 is **not** doing (architecture must not over-build)

- No payment surface (no IAP, no PG, no buyable revival ticket).
- No gifticon redemption catalog.
- No multi-room support; single mandatory primary room only.
- No cosmetic IAP, no rule template marketplace, no sponsor pairing.
- No international localization (KR-only).
- The architecture should make phase-2 expansion mechanically simple (no painful migrations) but **must not** ship phase-2 hooks that pollute the v1 surface.

---

## 2. Reference Architecture (one-page diagram)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  FE (Expo SDK 54 / RN 0.81 / React 19.1, TS 5.9 strict)                      │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ app/_layout.tsx (root Stack)                                          │   │
│  │  └── QueryProvider (AsyncStorage-persisted)                           │   │
│  │       └── AuthProvider (JWT + refresh)                                │   │
│  │            └── RealtimeProvider (single STOMP client)                 │   │
│  │                 └── SurvivalStateRouter (NEW)                         │   │
│  │                      ├── Active mode  →  app/(tabs)/* + rooms/*       │   │
│  │                      └── Spectator mode → spectator screens (read-only)│   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│  src/lib/query/hooks/  ← useSurvivalState, useRevival, useFriendGift,       │
│                          useRoomPoints, useFinalThree (NEW)                  │
│  src/api/client.ts      ← apiRequest<T> (existing)                          │
│  src/lib/realtime/      ← single STOMP client; new topic subscribers (NEW)  │
│  src/lib/kakaoShare.ts  ← Kakao Share SDK wrapper (NEW; native module)      │
└──────────────────────────────────────────────────────────────────────────────┘
                            │   HTTPS REST            │  STOMP/WSS
                            ▼                          ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  BE (Spring Boot 3.3.5, Java 21)                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ /api/v1/...  (single context-path /yeolsal auto-prefixed)             │   │
│  │   common/      ApiResponse<T>, ApiExceptionHandler (single advisor)   │   │
│  │   auth/        existing                                               │   │
│  │   daily/       existing — extends with survival-state evaluator       │   │
│  │   friend/      existing                                               │   │
│  │   notification/existing — quiet-hours respected for new pushes        │   │
│  │   profile/     existing                                               │   │
│  │   room/        existing — extends with rule-versioning, leadership    │   │
│  │   chat/        existing — write-API enforces SPECTATOR rejection      │   │
│  │   stats/       existing                                               │   │
│  │   survival/    NEW — survival-state, daily evaluator, state machine   │   │
│  │   revival/     NEW — RevivalService (3 sources), ledger, room pool   │   │
│  │   ceremony/    NEW — FinalThreeService + SVG renderer                 │   │
│  │   kakaoshare/  NEW — server-side preview-card renderer                │   │
│  │   realtime/    existing — RealtimePublisher (single emit point)       │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│  Scheduled jobs:                                                              │
│   - SurvivalStateEvaluatorJob (06:00 KST daily)                              │
│   - FinalThreeJob (06:30 KST 1st day of each calendar month)                 │
│   - PreviewCardCacheCleanupJob (hourly)                                      │
│  STOMP topics added:                                                          │
│   - /topic/rooms/{id}/survival                                               │
│   - /topic/rooms/{id}/points                                                 │
│   - /user/queue/friend-gifts                                                 │
│  Postgres + Flyway:                                                          │
│   - V11 migration: rooms.max_members range, 7 new tables                     │
│   - Backfill jobs (one-time): survival_state, free-revival-ticket grant      │
└──────────────────────────────────────────────────────────────────────────────┘
                            │
                            ▼
                   ┌──────────────────┐
                   │ PostgreSQL       │
                   │ V1–V10 + V11+    │
                   └──────────────────┘
```

---

## 3. Starter / Framework Decisions

### 3.1 BE — no new framework

- **Reuse the existing Spring Boot 3.3.5 monolith.** No microservice carve-out. New survival/revival/ceremony domain modules live as siblings under `com.yeosal.api.*`.
- **Reuse the existing `ApiResponse<T>` envelope and `ApiExceptionHandler @RestControllerAdvice`.** Add new `404 NOT_FOUND`/`403 FORBIDDEN`/`409 CONFLICT` paths through the existing handler — no second advisor.
- **Reuse the existing `RealtimePublisher`.** All three new STOMP topics emit through it; no service injects `SimpMessagingTemplate` directly.
- **Reuse JJWT 0.12.6 + JwtAuthenticationFilter for REST and JwtChannelInterceptor for STOMP CONNECT.** No new auth surface.
- **Reuse Flyway V<N>__<slug>.sql convention** for all V11+ migrations. Idempotent SQL, partial unique indexes following V8/V9 reference pattern.
- **Reuse existing Sentry wiring on BE side** (Sentry SaaS via Spring Boot integration, if not already, then add via `sentry-spring-boot-starter`; otherwise instrumentation lives in service-layer logs).

### 3.2 FE — no new framework

- **Reuse Expo Router 6 file-based routing.** Add `app/spectator/_layout.tsx` (or equivalent flag-based branching inside `app/(tabs)/_layout.tsx`) for Spectator mode. Decision (§4.7): branch inside the existing `(tabs)` layout based on `useSurvivalState().status` rather than a parallel route group.
- **Reuse TanStack Query + AsyncStorage persister.** Add new domain hooks under `src/lib/query/hooks/{useSurvivalState, useRevival, useFriendGift, useRoomPoints, useFinalThree, useKakaoShare}.ts`.
- **Reuse `apiRequest<T>` for every HTTP call.** Direct `fetch` is forbidden per project-context.
- **Reuse single `RealtimeProvider`.** Add new topic subscriptions through this provider; never instantiate a second STOMP client.
- **Reuse design system tokens via the FE→BE codegen pipeline (§4.16).** FE owns the canonical `FE/src/theme/tokens.json` (yeolsal v2 — Oxblood Editorial). Per Sprint Change Proposal 2026-05-10: v1 Risograph + Neobrutalist palette/shadows are deprecated — hard-offset shadow guard is **released** in favor of `elevation.*` subtle-blur tokens; `blur.subtle` 4-8px whitelist replaces any glassmorphism heavy blur. Brand-voice copy lexicon is enforced as a release gate (PRD FR-8.8.6); **NFR-9.6.1 packed-type `semantic.survival` reference is enforced as a hard CI gate via brand-voice lint** (§4.15).

### 3.3 New libraries / SDKs

| Need | Choice | Rationale |
|------|--------|-----------|
| KakaoTalk share | **Kakao Share SDK**, extending existing Kakao OAuth integration in the same dependency package | One dependency, no new auth review; matches Kakao SDK convention |
| Server-side SVG render | **Plain Java string templating + JSR-223 nashorn-equivalent NOT used; pure Java text builder + `org.apache.batik` only if PNG rasterization is needed** | Risograph layout is dead simple — text + rectangles + small accent shapes. Avoid heavy SVG libs; keep startup time lean |
| Server-side PNG fallback for Kakao card | **Apache Batik `org.apache.xmlgraphics:batik-transcoder`** | Stable, JVM-native, only loaded when card is generated |
| BE scheduled jobs | **Spring `@Scheduled` (already in spring-context)** with `@SchedulerLock` (ShedLock) only if multi-instance deployment lands; v1 single-instance Compose deploy doesn't need it yet | Match KISS principle; revisit when scaling out |
| Idempotent migrations | **Flyway** as established | No change |

**Explicitly rejected:**

- ❌ Redis or any new infra service in v1. Single Postgres + JVM is sufficient for the survival evaluator and the per-room pool counter at expected scale.
- ❌ Domain events bus (Kafka, RabbitMQ, etc.). Postgres `LISTEN/NOTIFY` and Spring `ApplicationEventPublisher` already cover what we need.
- ❌ NoSQL store. Postgres jsonb (already used in `chat_messages.payload`) covers any flexible-schema need.
- ❌ Switching JJWT to a newer crypto library. The existing 0.12.6 setup works.

---

## 4. Key Architectural Decisions

Each decision below is numbered and includes the question, the decision, the rationale, and explicitly named alternatives that were considered and rejected.

### 4.1 Survival state — derived vs materialized?

**Question:** Is `survival_state.status` the source of truth, or should it be computed on the fly from `daily_entries` + `streak_freezes` + `revival_events`?

**Decision:** **Materialized table**, source of truth. Computed by `SurvivalStateEvaluatorJob` and updated transactionally by `RevivalService`.

**Rationale:** Computing status on every read would (a) require recomputing the rolling 7-day window many times per request (Today screen, Feed, Chat all need it), (b) make spectator-mode authorization slow (every chat-write call would recompute), and (c) make realtime fan-out hard (we need a single canonical "state changed at T" timestamp). The materialized table also makes the state machine auditable — we know exactly when each transition happened.

**Rejected alternative:** view-based or service-method-derived status. Rejected for performance + auditability + concurrency reasons above.

### 4.2 Daily evaluator — push or pull?

**Question:** Does the evaluator scan all members each day at 06:00 KST (push), or run lazily when a user opens the app (pull)?

**Decision:** **Push, scheduled job at 06:00 KST.** Idempotent and replay-safe.

**Rationale:** Pull-evaluation creates an "I haven't opened the app, so I'm still ACTIVE" loophole that breaks the core mechanic. Push also gives us deterministic timestamp boundaries for the rolling 7-day window. The PRD's NFR-9.1.1 (5 minutes for 50k members) is comfortably achievable with a single Postgres query batch.

**Idempotency contract** (PRD NFR-9.2.1):
- Job runs daily at 06:00 KST. Each run computes the prior day's compliance for every active member.
- Dedup key: `notification_log (user_id, kind='SURVIVAL_STATE', key='{prior_date}:{user_id}')` — partial unique already exists in V4 schema.
- If the row exists, evaluator skips that user. The evaluator can run twice on the same date with no double-progress.

**Rejected alternatives:**

- ❌ **Pull on app-open**: defeats the mechanic; users who never open the app would never lose state.
- ❌ **Trigger-based** (Postgres trigger on `daily_entries`): too tight a coupling between data ingest and state machine; harder to back-test.
- ❌ **Streaming on chat-write**: only fires when users are active, has the same problem as pull.

### 4.3 Rolling 7-day window — implementation?

**Question:** How do we implement "missed twice in a rolling 7-day window"?

**Decision:** **Compute window membership in the evaluator using a single SQL query per member**, not via a dedicated table.

**Rationale:** The window is `survival_state.last_state_change_at >= now() - interval '7 days' AND fromStatus = 'ACTIVE' AND toStatus = 'YELLOW'`. Cheap to compute against an indexed transition log (we use `survival_state` rows themselves as the log — every YELLOW→RED transition examines the most recent ACTIVE→YELLOW transition for the same `(room_id, user_id)`). No extra table needed.

**Rejected alternative:** materialize a `miss_events` table. Adds complexity for no read benefit; `survival_state` plus its append-only history is enough.

### 4.4 Revival concurrency — exactly-once semantics?

**Question:** Two friends try to revive the same eliminated user at the same time. How do we guarantee exactly-one-revival?

**Decision:** **Postgres advisory lock per `(room_id, user_id, eliminated_at::date)` plus a partial unique index on `revival_events`** following the V8/V9 milestone-dedup pattern.

**Schema:**

```sql
-- partial unique index: at most one successful revival per (room, user, day-of-elimination)
create unique index ux_revival_events_one_per_elimination
  on revival_events (room_id, user_id, ((eliminated_at)::date))
  where succeeded = true;
```

**Service flow:**

1. Receive revival call (self or gift).
2. Acquire advisory lock `pg_advisory_xact_lock(hash(room_id, user_id, eliminated_at))`.
3. Re-read `survival_state` row inside the lock.
4. If status is no longer `RED` or `SPECTATOR`, return `409 CONFLICT` with code `ALREADY_REVIVED`.
5. Insert `revival_events` row with `succeeded=true`. The partial unique index is the second line of defense — if two requests beat the lock check, exactly one INSERT succeeds and the loser sees `DataIntegrityViolationException`, mapped by `ApiExceptionHandler` to a `CONFLICT`.
6. Update `survival_state.status = 'ACTIVE'` and `personal_points_ledger` / `room_point_pool` in the same transaction.
7. Emit `RealtimeEvent.SurvivalStateChange` and `.PointPoolChange`.

**Rejected alternatives:**

- ❌ **Optimistic locking with version column**: adequate, but the lock approach is more idiomatic for "race window" semantics and aligns with how V8/V9 already handle the milestone-dedup case.
- ❌ **Application-layer mutex**: doesn't survive multi-instance deploy; advisory lock + partial unique index does.

### 4.5 Personal-points ledger — append-only?

**Question:** Should `personal_points_ledger` be append-only, or hold a running balance per user?

**Decision:** **Append-only.** Balance is computed as `SUM(delta) FILTER (WHERE user_id=? AND room_id=?)`.

**Rationale:** Audit trail for free; ledger reconciliation is mechanical; "forfeit on leaving room" is implemented as an additive `delta = -current_balance, reason='ROOM_LEAVE'` row rather than a destructive DELETE. Postgres handles the SUM at scale fine; if it ever becomes hot, we can add a covering index or a sum cache.

**Rejected alternative:** running-balance column on `room_members`. Loses audit; harder to reason about consistency under revival concurrency.

### 4.6 Room point pool — counter cache or computed?

**Question:** Same question for `room_point_pool.total`.

**Decision:** **Materialized counter cache** (`room_point_pool` row per `room_id`), updated atomically with revival events.

**Rationale:** Pool growth is the most-displayed number in the entire UI (visible on every room screen). Computing `SUM(points_spent)` on every read is wasteful. Single integer column in a small table is simpler and faster than a materialized view. Negative deltas are forbidden by the write path (PRD FR-8.4.5), making reconciliation trivial.

**Concurrency:** `SELECT … FOR UPDATE` on the `room_point_pool` row inside the revival transaction. Combined with the advisory lock from §4.4, lost updates are impossible.

### 4.7 Spectator mode — separate route group or branched layout?

**Question:** Does Spectator mode live in its own expo-router group (`app/spectator/`), or does it branch inside the existing `(tabs)` layout?

**Decision:** **Branch inside the existing layout**, keyed off `useSurvivalState().status`.

**Rationale:** A separate route group would require re-implementing every screen the spectator can see (Today, Feed, room chat, profile, grass). Branching in-layout reuses every screen as read-only by passing a `mode='spectator'` prop down to leaf components. This matches RN best practice for permission-conditional UIs. The state-machine source of truth lives in BE; FE simply mirrors via TanStack Query + STOMP.

**Implementation:**

- `app/(tabs)/_layout.tsx` reads `useCurrentRoomSurvivalState()` (new hook).
- If `status === 'SPECTATOR'`: TabBar shows the same tabs but each screen renders the spectator variant. The Wallet tab is more prominent; the Compose / Post buttons are hidden. Chat input is disabled.
- The 24-hour soft-public visibility cooldown is enforced **server-side** in `GET /api/v1/rooms/{id}/survival` (PRD FR-8.1.6) — FE simply renders what BE returns; FE does not implement privacy logic.

**Rejected alternative:** parallel route group. Doubles the screen count and risks the spectator UI drifting from the active UI.

### 4.8 Friend-gift discoverability in v1 — push-only or wallet surface?

**Question:** PRD §13.4 lists "if discoverability is low, add Wallet '친구 살리기' surface in v1.5". Should v1 ship the Wallet surface upfront or push-only?

**Decision:** **v1 ships push-only. v1 also includes a passive "내 친구 중 회생 대기" badge on the Wallet tab** that lights up when at least one eligible friend-gift target is available. Clicking the badge opens the same Friend Gift Modal the push notification would deep-link to.

**Rationale:** The push is the load-bearing emotional moment; over-instrumenting it risks turning friend-gift into a chore. But silent push-only loses people who have notifications disabled. The Wallet badge is the minimum viable discoverability surface that keeps the push as the primary trigger.

**Falsification:** if Wallet-badge-driven friend-gifts > push-driven friend-gifts at Day 30, we under-trusted the push. Telemetry distinguishes the two via `revival_events` with a `source_subtype` column (`PUSH_INITIATED` vs `WALLET_INITIATED`).

### 4.9 Final-3 poster — server-side rendering tech?

**Question:** PRD §6.3 locked "server-side SVG render". What's the implementation?

**Decision (REVISED 2026-05-10):** **Plain Java string templating into an SVG `<svg>` document with token values sourced from the FE→BE codegen pipeline (§4.16), with on-demand PNG rasterization via Apache Batik for Kakao card thumbnails.**

**Rationale:** The poster is a fixed Editorial layout (room name, member nicknames, top-3 highlight) using yeolsal v2 design tokens consumed via generated Java constants (no string-literal token values in renderer code). Keeping it text-templated makes it diffable and testable. Sub-mode `D1 Editorial` token override set is applied at render time. PNG fallback only loads Batik when Kakao Card requires a raster image.

**Token sourcing (NEW 2026-05-10):** Tokens are NEVER hard-coded in `SvgRenderer.java` — they come from the `GeneratedTokens` class produced by the `generateTokens` Gradle task (§4.16). FE/BE drift is structurally impossible. Any new BE renderer (e.g., `InvitePreviewRenderer` per FR-8.6.2) must reference `GeneratedTokens` only — direct hex literals are blocked by Checkstyle / ArchUnit rule (Story 7.1 AC, §4.15).

**Caching:**

- `posters` table stores `(room_id, year_month, svg_text, png_url, generated_at)` with PK `(room_id, year_month)`.
- Posters are immutable once generated (PRD FR-8.7.6); no TTL.
- Preview cards (room invite snippets) are different — see §4.10.

**Rejected alternative:** client-side canvas rendering. Loses ASO indexability and Kakao card consistency across devices.

**Replaces (per Sprint Change Proposal 2026-05-10):** prior decision text that referenced "fixed Risograph layout" with hardcoded `ink/paper/pink/green/acid/muted` token names; closes Implementation Readiness A5 sub-mode token export gap.

### 4.10 KakaoTalk preview card — caching?

**Question:** Preview card image regenerates on rule/member-count change. How is it cached?

**Decision:** **Edge-cached at nginx + server-side `room_invite_preview_cache` table with TTL 1h or invalidation on `room_rule_versions` insert / `room_members` change.**

**Schema:**

```sql
create table room_invite_preview_cache (
  room_id     bigint primary key references rooms(id) on delete cascade,
  png_url     varchar(512) not null,
  rendered_at timestamptz not null default now(),
  rule_version_id bigint references room_rule_versions(id),
  member_count_at_render smallint not null
);
```

**Invalidation:** any service that writes a new `room_rule_versions` row or modifies `room_members` count enqueues a cache eviction. nginx serves the existing PNG while regeneration runs (avoids cache stampede).

**Rejected alternative:** regenerate on every read. Wastes cycles when the card is unchanged.

### 4.11 Backfill strategy on deploy?

**Question:** Existing rooms have members but no `survival_state` rows. How do we backfill safely?

**Decision:** **One-time Flyway migration job (V11 callback), not a runtime lazy-create.**

**Steps:**

1. V11 migration creates the new tables (NOT the data).
2. V11 migration also runs an idempotent backfill SQL inside the same migration:
   - For every `room_members` row: insert `survival_state (room_id, user_id, status='ACTIVE')` with `ON CONFLICT DO NOTHING`.
   - For every `users` row: grant a free revival ticket — implementation detail in §4.12 below.
3. Lazy-creation in the daily evaluator is forbidden — we want the state to be "complete" at all times so the privacy filter in §4.7 has nothing to fail open on.

**Rejected alternative:** lazy create on first read. Race conditions; complicated privacy filter.

### 4.12 Free revival ticket — table or flag?

**Question:** Where does the lifetime-1 free revival ticket live?

**Decision:** **A simple flag on `users` — `free_revival_ticket_used boolean default false`.** Granted at signup (default false = available). On use, atomic `UPDATE users SET free_revival_ticket_used = true WHERE id = ? AND free_revival_ticket_used = false RETURNING …` — succeeds exactly once.

**Rationale:** A `revival_grants` table would be over-engineered for "lifetime 1". A simple flag is auditable via the existing `revival_events` row that records the use.

**Backfill:** V11 migration sets `free_revival_ticket_used = false` for all existing users (default of the new column).

**Rejected alternative:** dedicated `revival_grants` table. Overkill for v1. Phase-2 may add it when paid revival tickets land.

### 4.13 Survival evaluator — single-row vs batch SQL?

**Question:** The evaluator must process up to 50k members in 5 minutes (NFR-9.1.1). Single-row Java loop or batch SQL?

**Decision:** **Batch SQL with a single transactional procedure per room.** Per-room transaction; not single-row Java loop.

**Why:** Java per-row loops would generate ~50k JPA round-trips. A single procedure per room (a few hundred members max) iterates inside the DB plan and commits atomically.

**Procedure outline:**

```sql
-- pseudocode for SurvivalStateEvaluatorJob's per-room SQL
WITH compliant AS (
  SELECT user_id FROM daily_entries
  WHERE entry_date = (current_date - interval '1 day')
    AND user_id IN (SELECT user_id FROM room_members WHERE room_id = :rid)
)
INSERT INTO personal_points_ledger (user_id, room_id, delta, reason)
SELECT c.user_id, :rid, 2, 'SURVIVAL'
FROM compliant c
ON CONFLICT DO NOTHING;  -- idempotency

-- non-compliant members → consider streak_freeze, then yellow → red
WITH ... as above;
```

The JVM service layer wraps this with state-transition logic and emits realtime events from a `Spring TransactionalEventListener` after commit.

**Rejected alternative:** per-member JPA writes. NFR-9.1.1 fails at scale; transaction count is needlessly high.

### 4.14 Realtime topic privacy — server-side or client-side?

**Question:** When a member transitions to RED with a 24h cooldown, do we broadcast immediately and let FE filter, or hold the broadcast?

**Decision:** **Two-channel model.** Emit the **detailed** event to `/user/queue/{user_id}/private-survival` immediately (so the affected user + leader see it). Emit the **broad** event to `/topic/rooms/{room_id}/survival` only after `broad_visibility_at` (24h later) — handled by a delayed `RealtimeEvent` queue or by a second scheduled job that fires hourly.

**Rationale:** Never trust the FE to filter privacy. If we broadcast and let FE drop, a malicious client (or a bug) leaks the data.

**Implementation:** a `pending_realtime_broadcasts` table holds rows with `(event_id, scheduled_at, payload)`; a 1-minute scheduled job reads and emits matured rows.

**Rejected alternative:** FE filtering. Hard NO for security.

### 4.15 Brand-voice + a11y gate — how is it enforced?

**Question:** PRD FR-8.8.6 says brand-voice review is a release gate. NFR-9.6.1 (revised 2026-05-10) requires `semantic.survival` packed-type enforcement at lint level. How is this combined?

**Decision (REVISED 2026-05-10):** **Manual sign-off (PM + designer) for AVOID-lexicon review + automated lint with three rule sets (one is a hard CI gate).**

**Implementation:**

- `tools/brand-voice-lint.ts` (or `.py`) script scans `FE/src/**/*.{ts,tsx}`, `FE/app/**/*.tsx`, and `BE/src/main/resources/messages*.properties` (if present) for three rule sets:
  1. **AVOID lexicon** (PRD FR-8.8.2 — `벌금` / `잃었다` / `떨어졌다` / `실패` / `자책` / `부담` / `패배` / `죄책감`). Severity: **WARN** (CI prints occurrences; human review authoritative). Avoids false positives on legitimate copy.
  2. **NFR-9.6.1 packed-type enforcement (NEW 2026-05-10)** — any reference to `survival.*.color` or `semantic.survival.*.color` token field in JSX/TSX must have a sibling text label or `accessibilityLabel` within the same JSX subtree. Severity: **HARD CI GATE** (workflow fails). Replaces the prior PRD NFR-9.6.1 narrative-only requirement.
  3. **Design-token literal guard (NEW 2026-05-10)** — direct hex values (`#XXXXXX`) or rgb literals in component code that match the v2 palette → use tokens instead. Severity: **WARN**.
- Pre-release human review checklist lives in `docs/brand-voice-review.md` (deliverable in W7, Story 8.4).
- BE-side complement: Checkstyle / ArchUnit rule blocks hex-literal color values in `BE/src/main/java/com/yeosal/api/**/*.java` outside of the `GeneratedTokens` class (§4.9 / §4.16) — also a hard gate.

**Rejected alternative (for AVOID lexicon only):** auto-fail CI for lexicon. Risks blocking releases over false positives in legitimate copy (e.g., a help article explaining "you won't lose your data"). NFR-9.6.1 enforcement is *narrowly scoped* (specific token field reference + sibling label rule) so false-positive risk is minimal — hence hard gate.

**Replaces (per Sprint Change Proposal 2026-05-10):** prior decision text that named only the AVOID lexicon as the lint target; closes Implementation Readiness M5/H4 NFR-9.6.1 enforcement gap.

### 4.16 FE↔BE Design Token Codegen Pipeline (NEW 2026-05-10)

**Question:** UX spec line 887-888 historically required Final-3 SVG renderer (BE) to map D1 Editorial sub-mode tokens server-side, but the sync mechanism was unspecified. Implementation Readiness Step-4 A5 gap. Manual sync at W6 is brittle (drift risk).

**Decision:** **FE owns the canonical token source `FE/src/theme/tokens.json`. BE consumes via Gradle codegen task that generates `com.yeosal.api.theme.GeneratedTokens` Java class at build time. No manual sync, no string-literal tokens in BE code.**

**Pipeline:**

```
FE/src/theme/tokens.json   ←  canonical source (FE owns)
   │
   │ (BE build)
   ▼
BE/build.gradle  →  task generateTokens (Groovy/Kotlin DSL)
   │
   │ reads tokens.json (relative path ../FE/src/theme/tokens.json)
   │ emits BE/build/generated/sources/tokens/com/yeosal/api/theme/GeneratedTokens.java
   ▼
GeneratedTokens.java  →  used by SvgRenderer.java + InvitePreviewRenderer.java
```

**Schema of `tokens.json`:**

```json
{
  "version": "2.0.0",
  "system": "yeolsal v2 — Oxblood Editorial",
  "color": {
    "key": "oklch(...)",
    "ink": "oklch(...)",
    "paper": "oklch(...)",
    "...": "..."
  },
  "semantic": {
    "survival": {
      "ACTIVE":    { "color": "...", "label": "활동 중",   "icon": "✓" },
      "YELLOW":    { "color": "...", "label": "노란 카드", "icon": "⚠" },
      "RED":       { "color": "...", "label": "빨간 카드", "icon": "❤" },
      "SPECTATOR": { "color": "...", "label": "관전 중",   "icon": "◐" }
    }
  },
  "subMode": {
    "editorial": { "...": "overrides" },
    "bento":     { "...": "overrides" },
    "quiet":     { "...": "overrides" },
    "postcard":  { "...": "overrides" },
    "plate":     { "...": "overrides" }
  }
}
```

**Rationale:**

- **Drift impossible by construction** — BE never has its own copy of token values; BE compile fails if `tokens.json` is missing or schema-invalid.
- **Single source of truth** — designer/PM edits one file; both FE and BE rebuild pick it up.
- **Type safety** — `GeneratedTokens.SURVIVAL_ACTIVE_COLOR`, `GeneratedTokens.SUBMODE_EDITORIAL_KEY_COLOR`, etc. are public-static Java constants with IDE autocomplete and compile-error-on-rename.
- **No new infra** — reuses existing Gradle build flow; no separate publish step or registry.
- **Cost** — ~1–2 dev-days at W1 (write Gradle task + JSON schema validator + first poster integration test that proves end-to-end token drift detection).

**Constraints:**

- `tokens.json` is the canonical source. `docs/design-system.md` is the *human-readable* documentation generated from it (or hand-synced; pick at W1 design lock — Story 1.5 AC).
- Sub-mode override sets are flat dicts at the same key level (no deep merge magic — keep generator simple).
- PNG rasterization (Batik) reads colors from the same `GeneratedTokens` class — Kakao share preview cards (FR-8.6.2) and Final-3 posters (FR-8.7.2) share the codegen path.
- Schema validator runs in CI (`./gradlew validateTokens`) and fails the build on missing required fields (e.g., any `semantic.survival.*` state missing `color`/`label`/`icon`/`grass-treatment`).

**Replaces:** Implementation Readiness Step-4 A5 ("D1 Editorial sub-mode token export from FE → BE renderer — Architecture §4.9 cites only basic Risograph color tokens. No sub-mode token sync mechanism."). Added per Sprint Change Proposal 2026-05-10.

---

## 5. Patterns & Conventions (must-follow for implementers)

Aligned with `_bmad-output/project-context.md`. Italics highlight the v1-pivot extensions.

### 5.1 Backend patterns

- **Controller paths use `/api/v1/...` only.** Context-path `/yeolsal` is auto-prefixed.
- **All controller responses wrapped in `ApiResponse.of(dto)`.** No raw DTOs.
- **One advisor: `ApiExceptionHandler`.** *New v1 exceptions (`AlreadyRevivedException`, `InsufficientPointsException`, `RuleLockedException`) extend `RuntimeException` with single-arg constructors and get explicit `@ExceptionHandler` mappings.*
- **Constructor injection only.** `@Autowired` fields banned everywhere.
- **`@Transactional` boundary owns lazy associations.** With `open-in-view: false`, any view-layer lazy access throws `LazyInitializationException`.
- **JPA `validate` mode.** Schema changes via Flyway only.
- **Bean Validation `@Valid` on every controller DTO.** *New v1 DTOs include `@NotNull`, `@Size`, `@Pattern` per security guideline.*
- **`RealtimePublisher` is the sole emit point.** *All three new STOMP topics fan out through it.*
- **Idempotency via partial unique indexes per V8/V9 reference.** *V11+ uses the same pattern for `revival_events` (§4.4) and `streak_freezes`.*
- **`/ws` HTTP layer is `permitAll`; STOMP CONNECT validates JWT.** *No change for v1.*

### 5.2 Frontend patterns

- **Strict TS, no `any`.** `unknown` for external input + narrowing.
- **`process.env` only via guarded `runtime.process?.env?.EXPO_PUBLIC_*` pattern** (`src/api/config.ts`). Only `EXPO_PUBLIC_*` keys make the client bundle.
- **All HTTP via `apiRequest<T>`.** Never direct `fetch`.
- **`ApiError` extends `Error`; branch on `error.code`.** *New error codes (`ALREADY_REVIVED`, `INSUFFICIENT_POINTS`, `RULE_LOCKED`) must match BE `ApiErrorResponse.code` exactly.*
- **Auth tokens in `expo-secure-store` only.** `AsyncStorage` is for TanStack Query persist.
- **Path alias `@/*` → `src/*`.**
- **Component props are named `interface`s. No `React.FC`.**
- **Immutable updates only.** No `Array#push` / field assignment.
- **Single STOMP client owned by `RealtimeProvider`.** Subscribe through provider, not via `new Client()`.
- ***New realtime hooks (`useSurvivalState`, `useRoomPoints`, `useFriendGift`) follow the existing `useChatRealtime` REST/WS dedupe pattern.***
- **`@shopify/flash-list` requires `estimatedItemSize`.** *Spectator chat list reuses existing chat list config.*
- **Sentry only via `src/lib/sentry.ts` helper.** Never import `@sentry/react-native` directly.
- **Native module changes require `adb uninstall app.yeosal.mobile` + clean rebuild.** *KakaoTalk Share SDK addition is the v1 trigger of this rule; document in RUNBOOK.md.*
- **Brand voice lexicon (PRD FR-8.8.2) applies to all user-facing copy.**

### 5.3 Concurrency patterns

- **Postgres advisory locks** scoped per `(room_id, user_id)` for revival operations (§4.4).
- **`SELECT … FOR UPDATE`** on `room_point_pool` row inside revival transactions (§4.6).
- **Idempotent inserts via partial unique indexes** for daily evaluator (§4.2) and revival events (§4.4).
- **Spring `TransactionalEventListener`** to emit realtime events only after commit — avoids broadcasting state changes that get rolled back.

### 5.4 Privacy patterns

- **Default-private archives** (`record_visibility_prefs.share_on_elimination = false`).
- **Soft-public elimination** enforced server-side (§4.14); FE never receives forbidden state.
- **No location tracking, no surveillance APIs.**
- **Hard-deletion preserves room context** (chat messages anonymized via existing `sender_user_id` SET NULL, V7).
- **Quiet hours respected** for all push notifications (existing `notification_prefs.quiet_*_hour`).

### 5.5 Brand voice patterns

- **Use** lexicon: 함께, 선물, 응원, 컴백, 회생, 그룹, 동료, 우리, 살리다.
- **Avoid** lexicon: 벌금, 잃었다, 떨어졌다, 실패, 자책, 부담, 패배, 죄책감.
- **Push notification tone is invitation, not demand.** "수진이 회생을 기다리고 있어요" ✓; "수진이가 죽었다 살려라!" ✗.
- **English store metadata** uses "comeback pass", not "revival ticket".

---

## 6. Source Tree Structure (v1 deltas)

### 6.1 BE deltas under `BE/src/main/java/com/yeosal/api/`

```
com/yeosal/api/
├── (existing modules unchanged) auth/, common/, daily/, friend/, notification/, profile/, room/, chat/, stats/, user/, realtime/
├── survival/                                  ← NEW MODULE
│   ├── SurvivalStateController.java          // GET /api/v1/rooms/{id}/survival
│   ├── SurvivalStateService.java
│   ├── SurvivalStateRepository.java
│   ├── SurvivalState.java                    // entity
│   ├── SurvivalStatus.java                   // enum (ACTIVE|YELLOW|RED|SPECTATOR)
│   ├── SurvivalStateEvaluatorJob.java        // @Scheduled at 06:00 KST
│   ├── StreakFreeze.java
│   ├── StreakFreezeRepository.java
│   ├── RoomRuleVersion.java
│   ├── RoomRuleVersionRepository.java
│   ├── RuleLockedException.java
│   └── dto/                                   // request / response records
├── revival/                                   ← NEW MODULE
│   ├── RevivalController.java                // POST .../revival, POST .../revivals/gifts
│   ├── RevivalService.java                   // 3 sources: free / personal / friend-gift
│   ├── RevivalEventRepository.java
│   ├── PersonalPointsLedgerRepository.java
│   ├── RoomPointPoolRepository.java
│   ├── RevivalEvent.java
│   ├── PersonalPointsLedger.java
│   ├── RoomPointPool.java
│   ├── AlreadyRevivedException.java
│   ├── InsufficientPointsException.java
│   └── dto/
├── ceremony/                                  ← NEW MODULE
│   ├── FinalThreeJob.java                    // @Scheduled monthly
│   ├── FinalThreeService.java
│   ├── FinalThreePoster.java                 // entity (room_id, year_month, svg, png_url)
│   ├── FinalThreePosterRepository.java
│   ├── PosterController.java                 // GET .../posters/{yearMonth}
│   ├── SvgRenderer.java                       // Risograph templating
│   ├── PngRasterizer.java                     // Apache Batik wrapper
│   └── dto/
├── kakaoshare/                                ← NEW MODULE
│   ├── PreviewCardController.java            // public served via nginx
│   ├── PreviewCardCacheService.java
│   ├── PreviewCardCacheRepository.java
│   └── PreviewCardCache.java
└── (existing) realtime/RealtimePublisher.java + new sealed RealtimeEvent variants:
    ├── RealtimeEvent.SurvivalStateChange (existing pattern)
    ├── RealtimeEvent.PointPoolChange
    ├── RealtimeEvent.FriendGiftPrompt
    ├── RealtimeEvent.LeadershipChange
    └── RealtimeEvent.MonthlyPosterReady
```

`common/` adds nothing — new exceptions live in their respective modules and are mapped by the existing `ApiExceptionHandler` via additional `@ExceptionHandler` methods.

### 6.2 FE deltas under `FE/src/`

```
src/
├── api/                                       ← NEW endpoints under existing client
│   ├── (existing)
│   ├── survival.ts                           // typed wrappers for /rooms/{id}/survival, /survival/me
│   ├── revival.ts                             // POST .../revival, POST .../revivals/gifts
│   ├── points.ts                              // GET .../points
│   └── posters.ts                             // GET .../posters/{yearMonth}
├── lib/
│   ├── query/hooks/
│   │   ├── (existing) useToday, useFeed, useChatRealtime, useRooms, useProfile
│   │   ├── useSurvivalState.ts               // current user + per-room state
│   │   ├── useRevival.ts                      // mutations for self-revival
│   │   ├── useFriendGift.ts                  // mutations for friend-gift revival
│   │   ├── useRoomPoints.ts                  // pool + WS subscription
│   │   └── useFinalThreePoster.ts             // monthly poster fetch + share
│   ├── kakaoShare.ts                         ← NEW (Kakao Share SDK wrapper)
│   ├── realtime/
│   │   ├── (existing) STOMP client owned by RealtimeProvider
│   │   ├── topics.ts                          // central topic registry — UPDATED with 3 new topics
│   │   └── handlers/
│   │       ├── survivalHandler.ts            // /topic/rooms/{id}/survival
│   │       ├── pointsHandler.ts               // /topic/rooms/{id}/points
│   │       └── friendGiftsHandler.ts         // /user/queue/friend-gifts
│   └── (existing) chatRead.ts, sentry.ts, toast.ts, fonts.ts, calendar.ts, bucket.ts, push.ts, notifications.ts
├── components/
│   ├── (existing) ui/, today/, chat/, rooms/, grid/, feedback/, BottomNav.tsx, Screen.tsx
│   ├── survival/                             ← NEW domain folder
│   │   ├── SurvivalBanner.tsx                // yellow/red card display
│   │   ├── SpectatorOverlay.tsx              // applied at layout level
│   │   ├── Wallet.tsx                         // free ticket + personal points + room pool
│   │   └── PoolBar.tsx                       // visual pool growth indicator
│   ├── revival/                              ← NEW domain folder
│   │   ├── RevivalSheet.tsx                  // self-revival modal
│   │   ├── FriendGiftModal.tsx               // friend-gift modal
│   │   └── ReceivedGiftToast.tsx             // post-revival "고맙다" prompt UX
│   └── ceremony/
│       ├── FinalThreeCard.tsx                // home tab card
│       └── PosterShareSheet.tsx              // Kakao share entry
├── providers/
│   └── (existing) QueryProvider.tsx, RealtimeProvider.tsx
└── (existing) auth/, domain/, hooks/, theme/, types/

app/
├── (existing) (tabs)/_layout.tsx             ← BRANCHED with useSurvivalState
├── (existing) rooms/, login.tsx, signup.tsx, join.tsx, notification-settings.tsx, friend-profile.tsx, index.tsx
└── room-creation.tsx                         ← UPDATED with max_members picker (12 default, 30 max)
```

### 6.3 Database schema deltas (V11 migration outline)

```sql
-- BE/src/main/resources/db/migration/V11__survival_revival_economy.sql

-- (1) widen rooms.max_members range
alter table rooms
    drop constraint if exists chk_rooms_max_members,
    alter column max_members set default 12,
    add constraint chk_rooms_max_members check (max_members between 2 and 30);

-- (2) free revival ticket flag on users
alter table users
    add column free_revival_ticket_used boolean not null default false;

-- (3) survival_state
create table survival_state (
    id                       bigserial primary key,
    room_id                  bigint not null references rooms(id) on delete cascade,
    user_id                  bigint not null references users(id) on delete cascade,
    status                   varchar(16) not null check (status in ('ACTIVE','YELLOW','RED','SPECTATOR')),
    last_state_change_at     timestamptz not null default now(),
    eliminated_at            timestamptz,
    broad_visibility_at      timestamptz,
    grace_ends_at            timestamptz,
    unique (room_id, user_id)
);
create index idx_survival_state_room on survival_state (room_id);
create index idx_survival_state_status on survival_state (status, last_state_change_at);

-- (4) streak_freezes
create table streak_freezes (
    id            bigserial primary key,
    user_id       bigint not null references users(id) on delete cascade,
    room_id       bigint not null references rooms(id) on delete cascade,
    applied_date  date not null,
    month         varchar(7) not null,  -- 'YYYY-MM'
    created_at    timestamptz not null default now()
);
create unique index ux_streak_freezes_user_month on streak_freezes (user_id, month);

-- (5) revival_events (append-only, with V8/V9-style partial unique idempotency)
create table revival_events (
    id              bigserial primary key,
    room_id         bigint not null references rooms(id) on delete cascade,
    user_id         bigint not null references users(id) on delete cascade,
    giver_user_id   bigint references users(id),  -- NULL for self-revivals
    source          varchar(20) not null check (source in ('FREE_TICKET','PERSONAL_POINTS','FRIEND_GIFT')),
    source_subtype  varchar(20),  -- e.g. 'PUSH_INITIATED', 'WALLET_INITIATED' for FRIEND_GIFT
    points_spent    smallint not null,
    pool_after      integer not null,
    eliminated_at   timestamptz not null,
    succeeded       boolean not null default true,
    occurred_at     timestamptz not null default now()
);
create unique index ux_revival_events_one_per_elimination
    on revival_events (room_id, user_id, ((eliminated_at)::date))
    where succeeded = true;
create index idx_revival_events_giver on revival_events (giver_user_id) where giver_user_id is not null;

-- (6) personal_points_ledger (append-only)
create table personal_points_ledger (
    id          bigserial primary key,
    user_id     bigint not null references users(id) on delete cascade,
    room_id     bigint not null references rooms(id) on delete cascade,
    delta       smallint not null,
    reason      varchar(24) not null check (reason in ('SURVIVAL','REVIVAL_SPEND','FRIEND_GIFT_SPEND','ROOM_LEAVE','ADJUSTMENT')),
    occurred_at timestamptz not null default now(),
    revival_event_id bigint references revival_events(id)
);
create index idx_ppl_user_room on personal_points_ledger (user_id, room_id, occurred_at);

-- (7) room_point_pool (counter cache)
create table room_point_pool (
    room_id        bigint primary key references rooms(id) on delete cascade,
    total          integer not null default 0 check (total >= 0),
    last_event_at  timestamptz
);

-- (8) room_rule_versions
create table room_rule_versions (
    id                    bigserial primary key,
    room_id               bigint not null references rooms(id) on delete cascade,
    effective_from_month  varchar(7) not null,  -- 'YYYY-MM'
    rule_payload          jsonb not null,
    created_by_user_id    bigint not null references users(id),
    created_at            timestamptz not null default now(),
    unique (room_id, effective_from_month)
);

-- (9) record_visibility_prefs
create table record_visibility_prefs (
    user_id                bigint not null references users(id) on delete cascade,
    room_id                bigint not null references rooms(id) on delete cascade,
    share_on_elimination   boolean not null default false,
    updated_at             timestamptz not null default now(),
    primary key (user_id, room_id)
);

-- (10) ceremony posters
create table final_three_posters (
    room_id      bigint not null references rooms(id) on delete cascade,
    year_month   varchar(7) not null,
    svg_text     text not null,
    png_url      varchar(512),
    generated_at timestamptz not null default now(),
    primary key (room_id, year_month)
);

-- (11) kakao share preview cache
create table room_invite_preview_cache (
    room_id                  bigint primary key references rooms(id) on delete cascade,
    png_url                  varchar(512) not null,
    rendered_at              timestamptz not null default now(),
    rule_version_id          bigint references room_rule_versions(id),
    member_count_at_render   smallint not null
);

-- (12) pending realtime broadcasts (delayed-emit support, §4.14)
create table pending_realtime_broadcasts (
    id            bigserial primary key,
    scheduled_at  timestamptz not null,
    payload       jsonb not null,
    emitted_at    timestamptz
);
create index idx_pending_realtime_due on pending_realtime_broadcasts (scheduled_at) where emitted_at is null;

-- (13) backfill: every existing room_member gets ACTIVE survival_state
insert into survival_state (room_id, user_id, status)
select rm.room_id, rm.user_id, 'ACTIVE'
from room_members rm
on conflict (room_id, user_id) do nothing;

-- (14) backfill: every existing room gets a default rule_payload effective this month
insert into room_rule_versions (room_id, effective_from_month, rule_payload, created_by_user_id)
select r.id,
       to_char(now() at time zone 'Asia/Seoul', 'YYYY-MM'),
       jsonb_build_object('preset', 'DAILY_UPDATE', 'weekendInclude', true),
       r.owner_id
from rooms r
on conflict (room_id, effective_from_month) do nothing;

-- (15) backfill: room_point_pool row per room
insert into room_point_pool (room_id, total)
select id, 0 from rooms
on conflict (room_id) do nothing;
```

### 6.4 New REST endpoints (added to existing `/api/v1` surface)

| Method | Path | Body | Response | Auth |
|--------|------|------|----------|------|
| GET | `/rooms/{id}/survival` | — | `List<SurvivalStateDto>` (privacy filtered) | room member |
| GET | `/me/survival` | — | `List<SurvivalStateDto>` (across user's rooms) | authenticated |
| POST | `/rooms/{id}/revival` | `{ source: FREE_TICKET \| PERSONAL_POINTS }` | `RevivalEventDto` | self-targeting room member |
| POST | `/rooms/{id}/revivals/gifts` | `{ targetUserId }` | `RevivalEventDto` | room member with sufficient points + friend of target |
| GET | `/rooms/{id}/points` | — | `RoomPointPoolDto` | room member |
| GET | `/rooms/{id}/points/ledger` | — | `List<LedgerEntryDto>` (giver-side only, private) | self only |
| PATCH | `/rooms/{id}/rule` | `{ preset, weekendInclude }` | `RoomRuleVersionDto` | room leader |
| PATCH | `/rooms/{id}/members/cap` | `{ maxMembers }` | `RoomDto` | room leader |
| POST | `/rooms/{id}/transfer-leadership` | `{ targetUserId }` | `RoomDto` | room leader |
| GET | `/rooms/{id}/posters/{yearMonth}` | — | poster SVG + PNG URL | room member |
| GET | `/rooms/{id}/invites/preview-card` | — | preview card PNG URL | public (cacheable) |
| POST | `/me/visibility-prefs` | `{ roomId, shareOnElimination }` | `VisibilityPrefDto` | self only |

All endpoints follow the existing `ApiResponse<T>` envelope.

---

## 7. Validation & Open Items

### 7.1 PRD ↔ Architecture trace

Every PRD FR has a corresponding architecture decision:

| PRD reference | Architecture coverage |
|---------------|------------------------|
| FR-8.1.* (Survival State & Daily Loop) | §4.1, §4.2, §4.3, §4.13, §4.14, V11 (3, 4, 8) |
| FR-8.2.* (Spectator Mode) | §4.7, §4.14 |
| FR-8.3.* (Revival Economy) | §4.4, §4.5, §4.6, §4.12, V11 (5, 6, 7) |
| FR-8.4.* (Group Pool) | §4.6, V11 (7) |
| FR-8.5.* (Leader & Rule) | §4.* (lockstep with controller in §6.4), V11 (8) |
| FR-8.6.* (Kakao SDK) | §3.3, §4.10, V11 (11) |
| FR-8.7.* (Final-3) | §4.9, V11 (10) |
| FR-8.8.* (Brand Voice) | §4.15, §5.5 |
| NFR-9.1.* (Performance) | §4.13 + scheduled-job design |
| NFR-9.2.* (Reliability) | §4.4, §5.3 |
| NFR-9.3.* (Security & Privacy) | §4.14, §5.4 |
| NFR-9.4.* (Observability) | §3 Sentry, plus structured logs convention |
| NFR-9.5.* (Compatibility & Migration) | §4.11, §6.3 V11 backfill steps |

### 7.2 Open items deferred (none gate v1)

- **Multi-instance ShedLock** — only needed if scaling out beyond single-instance Compose deploy. Add when phase-2 traffic justifies.
- **`tools/brand-voice-lint.ts` script** — included in W7 deliverables; helper, not a hard gate.
- **Sentry alert rules** — incident-response playbook ships with v1 deploy (PRD §13.3); concrete Sentry rules tuned in production.
- **Spring Boot Sentry SDK choice** — verify whether existing BE Sentry wiring is in place. If not, `sentry-spring-boot-starter` is a 1-line addition. (Logged as a checklist item; not a design decision.)
- **PNG rasterization on first request** — Apache Batik adds ~3MB to the runtime image. Only loaded when poster PNG is first requested; fine for v1 cold-start.
- **Offline / shadow-area daily-checkin mutation queue** (decision locked 2026-05-11 per readiness review M5) — **Accepted v1 behavior:** rely on TanStack Query's existing AsyncStorage persist + `ApiError` network-class handling. When the user is offline at submit time, the FE shows a "연결을 잠시 기다리고 있어요" banner (brand-voice connection-tone copy) and retries on foreground/network-recovery. No silent data loss; dignity tone preserved. **Why deferred:** a full idempotency-keyed mutation queue with replay-on-foreground is polish-tier work; v1's 8-week budget is tighter than the marginal-improvement value. **Promotion criteria:** if Day-7 diary study (UX Validation Plan) surfaces dignity-tone violations from dropped check-ins, story a v1.5 mutation-queue improvement under Epic 1 follow-up. UX-flagged as tracked technical risk; this decision converts it to a known-accepted gap.

### 7.3 Validation checklist (must pass before architecture is ratified)

- [x] Every PRD FR has a corresponding architecture decision.
- [x] Every PRD NFR has an architecture-level path to satisfaction.
- [x] No new advisor / publisher / Spring filter introduced — reuses existing single-source patterns.
- [x] V11 migration is idempotent and safely backfills production rooms.
- [x] No payment surface anywhere in the architecture.
- [x] Spectator mode privacy enforced server-side, never FE-only.
- [x] Friend-gift concurrency exactly-once via advisory lock + partial unique index.
- [x] Brand voice gate articulated as PM/designer sign-off + lint helper.
- [x] All new STOMP topics fan out through the existing `RealtimePublisher`.
- [x] Native module addition (Kakao Share SDK) flagged in RUNBOOK.md per project-context rule.

### 7.4 Risk register (architecture-level, separate from PRD §13)

| Risk | Mitigation |
|------|------------|
| `survival_state` table grows unboundedly via membership churn | Indexed on `(room_id)` and `(status, last_state_change_at)`; can partition later if needed |
| `personal_points_ledger` table grows unboundedly | Append-only, indexed; archival at year-end via plain Postgres partitioning if needed |
| Survival evaluator job goes over 5min with 50k members | NFR-9.1.1; mitigated by per-room batch SQL (§4.13); fan out per-region jobs if multi-instance lands |
| Apache Batik PNG rasterization slow at ceremony-job time | NFR-9.1.4; batch render with 5min budget; cache PNGs in same `final_three_posters` table |
| Pending realtime broadcasts table grows | Cleanup job purges `emitted_at IS NOT NULL AND scheduled_at < now() - 30 days` weekly |
| Backfill in V11 takes minutes on large existing rooms | Run during deploy maintenance window; backfill SQL is idempotent and resumable |
| Kakao Share SDK breaking change between minor versions | Pin to specific version; native module changes ride RUNBOOK.md cycle |

---

## 8. Recommended Next BMad Steps

1. **`/bmad-create-epics-and-stories`** — feed this Architecture doc + PRD + project-context. Stories should map 1:1 to the FR-IDs in PRD §8 and reference the architectural decisions in §4.
2. **`/bmad-check-implementation-readiness`** — gate before sprint planning. Confirm PRD + Architecture + Stories are aligned.
3. **`/bmad-sprint-planning`** — generate sprint status and kick off the dev-story cycle.
4. **`/bmad-validate-prd`** *(optional but recommended)* — independent validation pass against PRD standards before architecture is committed to.

Each subsequent skill should be run in a **new context window**.
