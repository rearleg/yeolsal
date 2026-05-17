# Story 3.1: Free revival ticket + self-revival via personal points

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **an eliminated room member (`survival_state.status ∈ {RED, SPECTATOR}`)**,
I want **to redeem my one-time free revival ticket at first elimination, or — after that ticket is used — to spend 3 of my own personal points to revive myself in the same room**,
so that **I can return to ACTIVE state and resume the daily loop without depending on a friend's gift**.

PRD authority: **FR-8.3.1** (lifetime-1 free ticket), **FR-8.3.2** (`POST /api/v1/rooms/{id}/revival` with `{ source: 'FREE_TICKET' | 'PERSONAL_POINTS' }`), **FR-8.3.8** (`personal_points_ledger` append-only, balance = `SUM(delta)`), **NFR-9.1.2** (revival p95 < 300ms / p99 < 800ms), **NFR-9.2.2** (advisory lock + partial unique index exactly-once), **NFR-9.2.4** (`SELECT … FOR UPDATE` on `room_point_pool`).
Architecture authority: **§4.4** (Postgres advisory lock + partial unique index pattern), **§4.5** (ledger append-only), **§4.6** (room point pool counter cache + FOR UPDATE concurrency), **§4.12** (free ticket = flag on `users`), **§6.1** (new `revival/` module shape — `RevivalController`, `RevivalService`, `RevivalEventRepository`, `RoomPointPoolRepository`, `RevivalEvent`, `RoomPointPool`, `AlreadyRevivedException`, `InsufficientPointsException`), **§6.3 V11 (5)(6)(7)** (already-shipped schema for `revival_events`, `personal_points_ledger`, `room_point_pool`), **§6.4** (endpoint contract — `POST /rooms/{id}/revival { source }` → `RevivalEventDto`).
Epics ref: `_bmad-output/planning-artifacts/epics.md` lines 419–453 (Story 3.1 ACs verbatim).
UX ref: J2 Spectator → Revival journey (`_bmad-output/planning-artifacts/ux-design-specification.md` lines 1280–1308). UX Effortless Interactions #2 (free ticket = "1탭 + 확인 모달 1회"); 5 critical success moments M2 ("첫 spectator → revival" → day-7 retention ≥ 45%).
Execution-order lock (epics line 1192): **3.1 → 3.5 → 3.2 → 3.3 → 3.4**. Story 3.1 lands FIRST in Epic 3; downstream stories (3.2 friend-gift, 3.5 Kudos, 3.3 Wallet badge, 3.4 Wallet UI) all depend on the `revival/` module + `revival_events`/`room_point_pool` write path this story establishes.

> **Foundation note.** Every backing table for this story already exists in production from the V11 migration (`BE/src/main/resources/db/migration/V11__survival_revival_economy.sql`, shipped via PR #55 + #57 + #62 in Story 1.4). The `revival/` Java package exists with three files (`PersonalPointsLedger.java`, `PersonalPointsLedgerRepository.java`, `LedgerReason.java`) — Story 1.2 placed them there as the "first file" of the module. **Story 3.1 does NOT add a Flyway migration.** Story 3.1 ships: (a) the missing `revival/` JPA layer (`RevivalEvent`, `RoomPointPool`, repositories), (b) `RevivalService` with the advisory-lock + INSERT/UPDATE/COUNTER-CACHE transactional flow, (c) `RevivalController` exposing `POST /api/v1/rooms/{id}/revival`, (d) domain exceptions `AlreadyRevivedException` + `InsufficientPointsException` + mappings in `ApiExceptionHandler`, (e) the FE typed client + `useSelfRevival` mutation + a confirmation-modal CTA wired into the spectator surface, (f) extension of `MeSurvivalEntryDto` (and the `WalletPreview` consumer) with `freeRevivalTicketUsed`.

> **Critical V11 deviation (read first).** Architecture §4.4 schema sample uses `((eliminated_at)::date)` in the partial unique index expression. V11 as shipped uses **`eliminated_at` (timestamp)** instead — the `::date` cast is STABLE (session-timezone dependent) and Postgres rejects STABLE functions in partial unique index expressions (SQLSTATE 42P17, see migration comment at `V11__survival_revival_economy.sql:54-62` and PR #57 commit `4f741ff`). **Story 3.1 MUST key the advisory lock and the `INSERT ... ON CONFLICT WHERE succeeded = true` predicate on the raw timestamp** to match the shipped index `ux_revival_events_one_per_elimination`. Mismatched lock/index keys defeat the second line of defence and surface as `DataIntegrityViolationException` instead of `409 ALREADY_REVIVED`.

## Acceptance Criteria

1. **AC1 — Free ticket flag default + signup invariant (FR-8.3.1, Architecture §4.12).**
   - **Given** a brand-new user account is created via `POST /api/v1/auth/{signup,login,kakao/...}` or any seam that inserts into `users`,
   - **When** the row lands,
   - **Then** `users.free_revival_ticket_used = false` (the V11 column default already enforces this; this AC verifies via integration test that no code path overrides it on signup).
   - **Mechanism:** the `User` entity does not yet map this column. AC1 adds a `private boolean freeRevivalTicketUsed` field with default `false`, mapped `@Column(name = "free_revival_ticket_used", nullable = false)`. The entity exposes a getter only — every public path through `RevivalService` is the sole writer, and the atomic check-and-set lives in a `UserRepository` `@Modifying @Query` (mirror of the `SurvivalState.setStatus` package-private pattern at `BE/src/main/java/com/yeosal/api/survival/SurvivalState.java:98-101`).
   - [Architecture §4.12; V11 migration line 21 (`free_revival_ticket_used boolean not null default false`); project-context Java rule "constructor injection / service-only writers"]

2. **AC2 — `POST /api/v1/rooms/{id}/revival` with `source=FREE_TICKET` (FR-8.3.2, epics lines 431–439).**
   - **Given** I am authenticated, member of room R, my `survival_state` for R is `RED` or `SPECTATOR`, and `users.free_revival_ticket_used = false`,
   - **When** I call `POST /api/v1/rooms/{id}/revival` with body `{ "source": "FREE_TICKET" }`,
   - **Then** within a single `@Transactional` boundary, **in this exact order**:
     1. Re-read `survival_state` for `(roomId, userId)` and capture `eliminatedAt` (must be non-null — guarantees the member actually transitioned to RED at some point).
     2. Acquire **`pg_advisory_xact_lock(hashtextextended('revival:' || :roomId || ':' || :userId || ':' || :eliminatedAtEpochMillis, 0))`** (native query). The lock is held until the transaction commits or rolls back.
     3. After acquiring the lock, re-read `survival_state` and verify `status ∈ {RED, SPECTATOR}`. If status is already `ACTIVE` (someone — another tab — beat us inside the lock window), throw `AlreadyRevivedException` → `409 CONFLICT` code `ALREADY_REVIVED`.
     4. Run `UPDATE users SET free_revival_ticket_used = true WHERE id = :userId AND free_revival_ticket_used = false` and capture row count. If zero rows updated, throw `BadRequestException` code `FREE_TICKET_ALREADY_USED` → `400 BAD_REQUEST` (the ticket was already consumed in a parallel session).
     5. `SELECT total FROM room_point_pool WHERE room_id = :roomId FOR UPDATE` (acquires row-level lock per Architecture §4.6 / NFR-9.2.4).
     6. `INSERT INTO revival_events` with `source='FREE_TICKET'`, `giver_user_id=null`, `source_subtype=null`, `points_spent=0`, `pool_after = <currentPool>+5`, `eliminated_at = <captured eliminatedAt>`, `succeeded=true`, `occurred_at=now()`. Use a **native `INSERT … ON CONFLICT DO NOTHING RETURNING id`** scoped to the partial unique index `ux_revival_events_one_per_elimination`. On empty return (zero rows inserted), throw `AlreadyRevivedException`. This is the second line of defence per Architecture §4.4.
     7. `UPDATE room_point_pool SET total = total + 5, last_event_at = now() WHERE room_id = :roomId`.
     8. `UPDATE survival_state SET status = 'ACTIVE', last_state_change_at = now(), eliminated_at = null, broad_visibility_at = null WHERE room_id = :roomId AND user_id = :userId`.
     9. Publish a `SurvivalStateTransitionEvent` (`SPECTATOR | RED → ACTIVE`, `broadVisibilityAt=null`) via `ApplicationEventPublisher` so the existing `SurvivalStateRealtimeListener` (`BE/src/main/java/com/yeosal/api/survival/SurvivalStateRealtimeListener.java:56-105`) fans out `SURVIVAL_STATE_CHANGE` post-commit on `AFTER_COMMIT` phase.
     10. Publish a new `PointPoolChangeEvent { roomId, delta=+5, newTotal=<currentPool>+5, sourceRevivalEventId, occurredAt }` via the same `ApplicationEventPublisher`; a new `RoomPointPoolRealtimeListener` (`@TransactionalEventListener(phase = AFTER_COMMIT)`) emits to `/topic/rooms.{roomId}.points` via a new `RealtimePublisher.publishPointPoolChange(roomId, payload)` method (mirroring the existing `publishSurvivalStateChange` shape at `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java:67-83`).
   - **Response:** `200 OK` with `ApiResponse.of(new RevivalEventDto(...))`. DTO is a `record` with fields: `revivalEventId: long`, `source: String` (`'FREE_TICKET'`), `pointsSpent: int` (0), `roomPointPoolAfter: int`, `occurredAt: Instant`.
   - [Epics lines 431–439; Architecture §4.4, §4.6, §4.12; V11 migration lines 63–80, 94–99]

3. **AC3 — `source=PERSONAL_POINTS` with ≥ 3 balance (FR-8.3.2, epics lines 441–443).**
   - **Given** I am authenticated, room member, `survival_state.status ∈ {RED, SPECTATOR}`, my `users.free_revival_ticket_used = true`, AND my balance `SUM(personal_points_ledger.delta) WHERE user_id = me AND room_id = R ≥ 3`,
   - **When** I `POST /api/v1/rooms/{id}/revival` with `{ "source": "PERSONAL_POINTS" }`,
   - **Then** the same transactional sequence as AC2 runs, with **these differences**:
     - **No `UPDATE users` step** — the free-ticket flag is irrelevant.
     - **Add INSERT into `personal_points_ledger`** AFTER the `revival_events` INSERT (the FK target must exist first). Order inside the txn after the advisory lock + status re-read:
       1. Snapshot balance via `personalPointsLedger.sumDeltaByUserIdAndRoomId(userId, roomId)` (reuse the existing `PersonalPointsLedgerRepository.sumDeltaByUserIdAndRoomId` at `BE/src/main/java/com/yeosal/api/revival/PersonalPointsLedgerRepository.java:30-36`) AFTER acquiring the advisory lock.
       2. If `balance < 3`, throw `InsufficientPointsException("개인 포인트가 부족합니다.")` → mapped to `400 BAD_REQUEST` code `INSUFFICIENT_POINTS`.
       3. `SELECT … FOR UPDATE` on `room_point_pool` (same as AC2 step 5).
       4. INSERT `revival_events` row with `source='PERSONAL_POINTS'`, `points_spent=3`, `pool_after=<currentPool>+3`. The partial-unique conflict path remains the same exactly-once defence.
       5. INSERT `personal_points_ledger { delta=-3, reason=REVIVAL_SPEND, revival_event_id=<insert id> }`.
       6. `UPDATE room_point_pool SET total = total + 3, last_event_at = now()`.
       7. `UPDATE survival_state` + publish `SurvivalStateTransitionEvent` + `PointPoolChangeEvent { delta=+3, newTotal=<currentPool>+3 }` (same as AC2).
   - **Order rationale:** the ledger row references `revival_event_id` via FK (V11 line 90). The `revival_events` INSERT must happen first so the FK target exists. The advisory lock + partial unique index serialise concurrent attempts before any of these writes.
   - **Response:** `200 OK` + `RevivalEventDto` with `source='PERSONAL_POINTS'`, `pointsSpent=3`, `roomPointPoolAfter`, `occurredAt`.
   - [Epics lines 441–443; Architecture §4.5 (ledger append-only); FR-8.3.2, FR-8.3.8]

4. **AC4 — Insufficient-points rejection (FR-8.3.2, epics lines 445–447).**
   - **Given** ticket used and personal-points balance < 3,
   - **When** I `POST /api/v1/rooms/{id}/revival` with `{ "source": "PERSONAL_POINTS" }`,
   - **Then** response is `400 BAD_REQUEST` with `ApiErrorResponse { code: "INSUFFICIENT_POINTS", message: "개인 포인트가 부족합니다." }`. No DB rows written. The balance check runs **inside** the advisory lock so it observes any in-flight concurrent debit (FRIEND_GIFT_SPEND from Story 3.2 / ROOM_LEAVE) consistently. The advisory lock is released on transaction rollback (Postgres `xact_lock` is auto-released at txn end).
   - **Mechanism:** introduce `InsufficientPointsException` (extends `RuntimeException` with a single-message constructor, per project-context Java exception convention). Map in `ApiExceptionHandler` with a new `@ExceptionHandler` returning 400 + code `INSUFFICIENT_POINTS`.
   - [Epics lines 445–447; project-context Java rule "domain exceptions + ApiExceptionHandler mapping"]

5. **AC5 — Concurrency: race between two clients (FR-8.3.2, epics lines 449–453; Architecture §4.4).**
   - **Given** two clients race the same revival request (e.g., user has two app instances or push-then-app race),
   - **When** both reach the advisory-lock contender,
   - **Then** **exactly one** succeeds (`survival_state.status = ACTIVE`, `revival_events` row inserted, pool incremented, ledger debited if PERSONAL_POINTS); the other receives `409 CONFLICT` code `ALREADY_REVIVED` with message `"이미 회생되었습니다."`.
   - **Two-layer defence (Architecture §4.4 line 233):**
     1. **Primary layer — Postgres advisory lock.** `pg_advisory_xact_lock(hashtextextended('revival:' || roomId || ':' || userId || ':' || eliminatedAtEpochMillis, 0))`. The loser blocks until the winner's transaction commits, then re-reads `survival_state` (step 3 of AC2), sees `ACTIVE`, throws `AlreadyRevivedException` → 409.
     2. **Secondary layer — partial unique index.** The `INSERT ... ON CONFLICT DO NOTHING` returns zero rows; the service detects empty result and throws `AlreadyRevivedException` → 409. **Alternative path** (per Architecture §4.4 line 233): allow `DataIntegrityViolationException` to surface and discriminate in `ApiExceptionHandler` — see AC6 below.
   - **The advisory lock key MUST be `(roomId, userId, eliminatedAt)` as a triple, matching the partial unique index's column tuple.** Using `(roomId, userId, eliminatedAtAsDate)` would lock more broadly than the index dedupes; using only `(roomId, userId)` would over-lock unrelated past eliminations. The `eliminatedAtEpochMillis` cast (`Instant.toEpochMilli()`) is the unambiguous stable hash input. `hashtextextended` returns a 64-bit value matching the `pg_advisory_xact_lock(bigint)` overload.
   - [Epics lines 449–453; Architecture §4.4]

6. **AC6 — `ApiExceptionHandler` discriminates `revival_events` integrity violation (Architecture §4.4 last paragraph).**
   - **Given** the `DataIntegrityViolationException` thrown by the partial unique index conflict path (if it surfaces despite the service-layer catch — i.e., the secondary defence fires at `flush()` time after the service returned),
   - **When** the exception reaches `ApiExceptionHandler.dataIntegrity` (current behaviour at `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java:98-111` returns `500 INTERNAL_ERROR`),
   - **Then** **prefer the service-layer catch path**: `RevivalService` catches `DataIntegrityViolationException`, inspects the root cause's constraint name (`org.postgresql.util.PSQLException.getServerErrorMessage().getConstraint()`), and rethrows as `AlreadyRevivedException` when the constraint name equals `ux_revival_events_one_per_elimination`. **Only if** the IT `RevivalConcurrencyIT` proves the exception still escapes (e.g., it fires on Hibernate `flush()` after the service returned), extend `ApiExceptionHandler.dataIntegrity` with the same constraint-name discriminator returning 409 + code `ALREADY_REVIVED`. Document the choice in dev notes. Either way the user-visible contract is unchanged.
   - **Rationale:** Project-context rule: "Adding a new domain exception without a matching `@ExceptionHandler` in `ApiExceptionHandler` results in a generic 5xx". This story adds two new exception types (`AlreadyRevivedException`, `InsufficientPointsException`) — both must have explicit handlers.
   - [Architecture §4.4 line 233; project-context "ApiExceptionHandler is the single advice"]

7. **AC7 — Free-ticket flag visible to FE via `GET /api/v1/me/survival` (extends Story 1.3 / Story 2.1 surface).**
   - **Given** the FE renders the spectator `WalletPreview` block (`FE/src/components/survival/WalletPreview.tsx`, Story 2.1 AC7),
   - **When** `useMeSurvivalQuery()` resolves,
   - **Then** each `MeSurvivalEntry` carries a new boolean `freeRevivalTicketUsed`. The Wallet renders the "🎟 무료 회생권 1매" line **only when `freeRevivalTicketUsed === false`**. The TODO at `WalletPreview.tsx:27-31` ("Story 3.1 will add `user.freeRevivalTicketUsed`") is resolved by this AC.
   - **BE shape:** extend `MeSurvivalEntryDto` (`BE/src/main/java/com/yeosal/api/survival/MeSurvivalEntryDto.java`) record with `boolean freeRevivalTicketUsed`. `SurvivalStateService.mySurvivalAcrossRooms` reads the user once via the existing `UserRepository.findById(userId)` (inject the repo if absent) and writes the same flag to every entry (user-scoped value replicated per row — acceptable v1 shape; Story 3.4 may refactor to a separate `/me/wallet` endpoint).
   - **FE shape:** extend `MeSurvivalEntry` (`FE/src/lib/spectator.ts:19-27`) with `readonly freeRevivalTicketUsed: boolean`. Update `WalletPreview.tsx` to drop the `showTicket = true` constant and branch on `first.freeRevivalTicketUsed === false`.
   - [Architecture §4.12; Story 2.1 AC7 forward-compat TODO]

8. **AC8 — FE self-revival CTA on the spectator surface (UX J2 line 1296–1305).**
   - **Given** I am viewing the Today tab in spectator mode (`useIsSpectatorEverywhere() === true`, OR `useCurrentRoomSurvivalState(roomId)?.status === 'SPECTATOR' | 'RED'`),
   - **When** the `WalletPreview` renders,
   - **Then** below the three-line balance display a new `<SelfReviveCTA roomId>` component renders with:
     - **Primary CTA: `회생권 사용` (oxblood key color filled, editorial weight bold)** — visible iff `freeRevivalTicketUsed === false` AND status ∈ {RED, SPECTATOR}.
     - **Secondary CTA: `포인트로 회생 (3점)` (muted outline)** — visible iff `freeRevivalTicketUsed === true` AND `personalPoints ≥ 3` AND status ∈ {RED, SPECTATOR}. Disabled with tooltip `"포인트가 모자라요"` when `personalPoints < 3`.
     - If both conditions fail (e.g., ticket used + balance < 3), render a single muted caption: `"친구의 회생권 선물을 기다려요"` (forward-pointer to Story 3.2; no points-buy CTA in v1).
   - **Tap flow (Effortless Interactions #2 — "1탭 + 확인 모달 1회"):** tap CTA → `<SelfReviveConfirmModal>` opens with title `"방으로 돌아갈까요?"`, body lists what happens (`"무료 회생권 1매가 사용돼요."` / `"개인 포인트 3점이 사용돼요."`), two CTAs: `"돌아가기"` (primary, calls `useSelfRevival(roomId, source)` mutation) + `"닫기"` (ghost).
   - **On success:** modal closes; success toast `"방으로 돌아왔어요"`; the cache invalidation hook chain (AC9) auto-updates Wallet + spectator-routing state; the spectator routing branch (`SpectatorRouteProvider`) flips the user out of spectator mode at the next render.
   - **On 409 ALREADY_REVIVED:** modal stays open, body swaps to `"이미 회생됐어요"` for 1.5s, then auto-closes; cache invalidation still fires (state must be re-read).
   - **On 400 INSUFFICIENT_POINTS:** modal stays open, error toast `"포인트가 모자라요"`, modal closes after 1.5s.
   - **On 400 FREE_TICKET_ALREADY_USED:** modal stays open, error toast `"이미 회생권을 썼어요"`, modal closes after 1.5s; cache invalidation fires.
   - **Brand-voice contract:** AVOID lexicon (`벌금/잃었다/떨어졌다/실패/자책/부담/패배/죄책감`) MUST NOT appear in any new copy. Tests assert this. The CTAs use `회생권`/`회생`/`방으로` — already-vetted brand-voice family.
   - [Epics line 422; UX J2 lines 1296–1305; UX 5 critical moments M2; PRD FR-8.3.2]

9. **AC9 — Cache invalidation + Realtime hookup on the FE (project-context FE rule).**
   - **Given** the self-revival mutation `useSelfRevival(roomId, source)` succeeds,
   - **When** the mutation `onSuccess` fires,
   - **Then** the FE invalidates these query keys (never `queryClient.clear()` — project-context "AsyncStorage persister nuke"):
     - `qk.meSurvival` — triggers `WalletPreview` to re-read free-ticket-used + balances + room pool.
     - Any room-scoped survival roster key (audit during dev; `grep -rn "qk\.\|useSurvival\|useMeSurvivalQuery\|useCurrentRoomSurvivalState" FE/src FE/app` — if no separate per-room key exists, `qk.meSurvival` alone is sufficient).
   - **STOMP realtime cross-check:** the `SURVIVAL_STATE_CHANGE` frame published by `SurvivalStateRealtimeListener` for the affected user (already wired in Story 1.2) will ALSO arrive on `/user/{userId}/queue/private-survival`. The existing FE realtime handler pattern (mirror `useChatRealtime` dedupe) should set a dedupe key on the revival event ID and INVALIDATE (not overwrite) the cache. **Do not** add a parallel STOMP client. The new `POINT_POOL_CHANGE` frame on `/topic/rooms.{roomId}.points` powers Story 4.1's `useRoomPoints` — Story 3.1 only needs the BE publisher to exist (no FE subscriber required for self-revival at this story's scope; the `WalletPreview` re-renders via `qk.meSurvival` invalidation).
   - **Frame layout (Realtime contract — for downstream consumers):**
     - `/user/{userId}/queue/private-survival` — kind `SURVIVAL_STATE_CHANGE`, payload `{ roomId, userId, fromStatus, toStatus='ACTIVE', occurredAt, eliminatedAt: null, broadVisibilityAt: null }` (reuses the existing `SurvivalStateChangePayload` record).
     - `/topic/rooms.{roomId}.points` — kind `POINT_POOL_CHANGE`, payload `{ roomId, delta, newTotal, sourceRevivalEventId, occurredAt }` (NEW record `PointPoolChangePayload` in `revival/`).
   - **Topic destination convention:** dot-separator (`/topic/rooms.{id}.points` and `/user/{id}/queue/...`) — matches existing `RealtimePublisher` at `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java:43-49`. **Architecture §4.4/§4.6 prose uses slash separators (`/topic/rooms/{id}/points`) but the implementation uses dots** — match the implementation.
   - [Project-context FE rules — TanStack invalidation, STOMP single-client, WS dedupe; Architecture §4.6, §4.14]

10. **AC10 — Out-of-scope (explicit).** Story 3.1 ships self-revival (`FREE_TICKET` + `PERSONAL_POINTS`) only. It does **NOT** ship:
    - Friend-gift revival endpoint (`POST /api/v1/rooms/{id}/revivals/gifts`) — Story 3.2.
    - Kudos message endpoint — Story 3.5.
    - Wallet "친구 회생 대기 N" badge — Story 3.3.
    - Full Wallet UI surface (ledger detail, received-revival history, Bento composition) — Story 3.4.
    - `room_point_pool` 5-stage SVG visualization + `<PoolStack>` component — Story 4.3.
    - Pre-revival "eligible friend givers" push notification — Story 3.2 (FR-8.3.4).
    - Per-user `EXPLAIN ANALYZE` p95 latency benchmark (NFR-9.1.2 will be assessed at Day-30 telemetry checkpoint, not gate-blocked here).
    - The 5-phase `<RevivalSequence>` M3 animation — Story 3.2 (FRIEND_GIFT receiver-only).
    - Per-room `revival_events.source_subtype` semantics — Story 3.3 introduces `'WALLET_INITIATED'` / `'PUSH_INITIATED'`. Story 3.1 leaves `source_subtype = null` for self-revivals (free + personal-points), matching V11 column nullability.

    If a file under `BE/src/main/java/com/yeosal/api/{auth, friend, notification, room/chat}/` is modified beyond what's listed in AC2/AC7/AC11 + the controller-wiring touchpoints in AC2, scope has drifted — stop and re-scope. `BE/src/main/resources/db/migration/V*__*.sql` is NOT touched.

11. **AC11 — Test coverage (TDD, 80%+ on new code).**

    **BE — JUnit 5 + AssertJ + Mockito + Testcontainers:**
    - `BE/src/test/java/com/yeosal/api/revival/RevivalServiceTest.java` — Mockito unit (mocks `SurvivalStateRepository`, `UserRepository`, `RevivalEventRepository`, `PersonalPointsLedgerRepository`, `RoomPointPoolRepository`, `ApplicationEventPublisher`, `EntityManager`, `Clock`). Cover:
      - `reviveSelf(roomId, userId, FREE_TICKET)` — RED status, ticket unused → returns `RevivalEventDto`, calls each repository in the documented order, publishes both events.
      - `reviveSelf(... FREE_TICKET)` — ticket already used → throws `BadRequestException(code='FREE_TICKET_ALREADY_USED')`.
      - `reviveSelf(... FREE_TICKET)` — status `ACTIVE` after lock → throws `AlreadyRevivedException`.
      - `reviveSelf(... PERSONAL_POINTS)` — balance ≥ 3 → debits ledger -3, increments pool +3.
      - `reviveSelf(... PERSONAL_POINTS)` — balance = 2 → throws `InsufficientPointsException`.
      - `reviveSelf(... PERSONAL_POINTS)` — balance exactly 3 → succeeds (boundary).
      - `reviveSelf(...)` — `survival_state` row missing → throws `NotFoundException`.
      - `reviveSelf(...)` — `eliminated_at` is null on the row (e.g., legacy ACTIVE) → throws `BadRequestException(code='NOT_ELIMINATED')`.
    - `BE/src/test/java/com/yeosal/api/revival/RevivalControllerTest.java` — `@WebMvcTest`:
      - `POST /api/v1/rooms/{id}/revival` with `{source:'FREE_TICKET'}` → `200` + envelope shape.
      - With invalid `source` value (e.g., `'INVALID'`) → `400 VALIDATION` (existing handler via Jackson enum binding).
      - With missing `source` → `400 VALIDATION`.
      - Unauthenticated → `401 UNAUTHORIZED` (existing security chain).
      - Non-member of room → `403 FORBIDDEN` (room-membership precheck via `RoomMemberRepository.existsByRoomIdAndUserId` at `RoomMemberRepository.java:53`).
    - `BE/src/test/java/com/yeosal/api/revival/RevivalConcurrencyIT.java` — `@SpringBootTest` + `@Testcontainers` PostgreSQL (mirror of `SurvivalStateRosterIT` at `BE/src/test/java/com/yeosal/api/survival/SurvivalStateRosterIT.java:62-95`):
      - Seed: room R, user U is RED with `eliminated_at = T` and `free_revival_ticket_used = false`.
      - Spawn **two parallel `CompletableFuture`s** invoking `revivalService.reviveSelf(R, U, FREE_TICKET)` simultaneously (use a `CountDownLatch` to release both at once after both threads have stepped into the service). Exactly one future returns successfully; the other throws `AlreadyRevivedException`.
      - Post-test asserts: `revival_events` has exactly **1** row for `(R, U, T)`; `users.free_revival_ticket_used = true`; `room_point_pool.total = 5`; `survival_state.status = 'ACTIVE'`.
      - Second test variant: user has ticket-used + balance 6, race two `PERSONAL_POINTS` calls → exactly 1 succeeds, ledger has 1 row delta=-3 (not -6), pool incremented +3 (not +6), balance final = 3.
      - Third test variant: race FREE_TICKET vs PERSONAL_POINTS (user has ticket unused + balance 6) → exactly one succeeds; the loser's contention path tested.
    - `BE/src/test/java/com/yeosal/api/survival/MeSurvivalFreeTicketTest.java` — extend the existing Me-survival test path or add new IT to assert `MeSurvivalEntryDto.freeRevivalTicketUsed` reflects the underlying `users` column (defaults to false for fresh user; flips true after self-revival).
    - **Coverage target:** 80%+ on `RevivalService.java`, `RevivalController.java`, `RevivalEvent.java`, `RoomPointPool.java`, `RoomPointPoolRealtimeListener.java`, `AlreadyRevivedException.java`, `InsufficientPointsException.java`. Trivial getters / `record`s / DTO constructors are excluded by JaCoCo defaults.

    **FE — Jest + `@testing-library/react-native`:**
    - `FE/src/components/survival/__tests__/SelfReviveCTA.test.tsx` — render all three states: ticket-unused + RED (primary CTA visible), ticket-used + balance ≥ 3 (secondary CTA enabled), ticket-used + balance < 3 (disabled with tooltip or muted-caption variant). Brand-voice copy assertions.
    - `FE/src/components/survival/__tests__/SelfReviveConfirmModal.test.tsx` — modal opens on CTA tap, body copy matches AC8, both CTAs work, mutation is called with correct `source`, success/error paths (toast assertions).
    - `FE/src/lib/query/hooks/__tests__/revival.test.tsx` — `useSelfRevival(roomId)` mutation: success invalidates `qk.meSurvival`; 409 ALREADY_REVIVED still invalidates (state must be re-read); 400 INSUFFICIENT_POINTS does NOT mutate the cache; 400 FREE_TICKET_ALREADY_USED still invalidates `qk.meSurvival` (ticket flag must re-read).
    - `FE/src/components/survival/__tests__/WalletPreview.test.tsx` — UPDATE existing test: with `freeRevivalTicketUsed: true` the "🎟" line is NOT rendered; with `false` it IS rendered.

    **Brand-voice lint:** `tools/brand-voice-lint.ts` Rule 2 (AVOID lexicon) must be clean on all new copy. Rule 1 (NFR-9.6.1 packed-type) does not apply (no new `semantic.survival.*.color` references).

## Tasks / Subtasks

### Backend (BE/) — revival module + JPA layer + service + controller + exception mappings

- [x] **Task BE-1 — JPA entities for `revival_events` + `room_point_pool` (AC2, AC3)**
  - [x] BE-1.1 — NEW `BE/src/main/java/com/yeosal/api/revival/RevivalEvent.java`. `@Entity @Table(name = "revival_events")`. Columns map V11 lines 63–78. `source` is `@Enumerated(EnumType.STRING)`; introduce `RevivalSource` enum with values `FREE_TICKET, PERSONAL_POINTS, FRIEND_GIFT` (CHECK constraint already exists; Story 3.2 will write FRIEND_GIFT but Story 3.1 must define the enum upfront to keep wire/persistence aligned). `source_subtype` is nullable String (Story 3.3 writes; Story 3.1 leaves null). `giver_user_id` is nullable Long (Story 3.1 leaves null). `succeeded` defaults to true. Constructor + private setters only — service layer is the sole writer.
  - [x] BE-1.2 — NEW `BE/src/main/java/com/yeosal/api/revival/RevivalSource.java` (enum, see BE-1.1 mapping).
  - [x] BE-1.3 — NEW `BE/src/main/java/com/yeosal/api/revival/RevivalEventRepository.java`. Extends `JpaRepository<RevivalEvent, Long>`. Native `@Modifying @Query` `insertOnConflictDoNothing(...)` returning `int` row count (avoids Spring Data's `RETURNING` ambiguity; service re-reads the persisted row via `findByRoomIdAndUserIdAndEliminatedAt` for the FK target id). ON CONFLICT inference clause `(room_id, user_id, eliminated_at) WHERE succeeded = true` matches the partial unique index predicate exactly.
  - [x] BE-1.4 — NEW `BE/src/main/java/com/yeosal/api/revival/RoomPointPool.java`. `@Entity @Table(name = "room_point_pool")`. Primary key is `room_id` (Long). Columns: `total` (int, default 0, CHECK ≥ 0 at DB level), `last_event_at` (Instant, nullable). Package-private mutators; service layer is sole writer.
  - [x] BE-1.5 — NEW `BE/src/main/java/com/yeosal/api/revival/RoomPointPoolRepository.java`. Extends `JpaRepository<RoomPointPool, Long>`. Native `selectForUpdate(roomId)` returning `Optional<RoomPointPool>` with `SELECT … FOR UPDATE`. `@Modifying` `incrementTotal(roomId, delta)` native UPDATE bumping total + refreshing last_event_at. Sibling `findTotalByRoomId(roomId)` read for the post-update total.

- [x] **Task BE-2 — `User` entity extension + repository (AC1, AC7)**
  - [x] BE-2.1 — UPDATE `BE/src/main/java/com/yeosal/api/user/User.java`. Added `freeRevivalTicketUsed = false` field with `isFreeRevivalTicketUsed()` getter. No public mutator.
  - [x] BE-2.2 — UPDATE `BE/src/main/java/com/yeosal/api/user/UserRepository.java`. Added `@Modifying @Query` `markFreeTicketUsed(userId)` JPQL with `where u.freeRevivalTicketUsed = false` — atomic check-and-set returning row count (0 = already used, 1 = newly consumed).

- [x] **Task BE-3 — Domain exceptions + ApiExceptionHandler mappings (AC2, AC3, AC4, AC5, AC6)**
  - [x] BE-3.1 — NEW `BE/src/main/java/com/yeosal/api/revival/AlreadyRevivedException.java` (extends `RuntimeException`, single-message ctor).
  - [x] BE-3.2 — NEW `BE/src/main/java/com/yeosal/api/revival/InsufficientPointsException.java` (same pattern).
  - [x] BE-3.3 — UPDATE `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java`. Added four `@ExceptionHandler` methods (`alreadyRevived` → 409 CONFLICT/ALREADY_REVIVED; `insufficientPoints` → 400/INSUFFICIENT_POINTS; `freeTicketAlreadyUsed` → 400/FREE_TICKET_ALREADY_USED; `notEliminated` → 400/NOT_ELIMINATED). The two extra mappings (`FreeTicketAlreadyUsedException`, `NotEliminatedException`) follow the `SpectatorWriteForbiddenException` typed-subclass + CODE-constant precedent because the base `BadRequestException` does not carry a code field. `dataIntegrity` kept unchanged — service-layer catch path covers the AC6 partial-unique conflict translation.

- [x] **Task BE-4 — `RevivalService` with advisory-lock + transactional flow (AC2, AC3, AC4, AC5)**
  - [x] BE-4.1 — NEW `BE/src/main/java/com/yeosal/api/revival/RevivalService.java`. Constructor injection of all repos + `RoomRepository` + `ApplicationEventPublisher` + `EntityManager` + `Clock`. NO field injection. (Note: `RoomMemberRepository` lives in the controller for the cheap precheck rather than the service.)
  - [x] BE-4.2 — NEW `BE/src/main/java/com/yeosal/api/revival/RevivalRequest.java` (record) `@NotNull RevivalSource source`. Excludes `FRIEND_GIFT` at service validation — only `FREE_TICKET` and `PERSONAL_POINTS` are accepted in this story. Throw `BadRequestException("source must be FREE_TICKET or PERSONAL_POINTS for self-revival")` if `FRIEND_GIFT` arrives.
  - [x] BE-4.3 — Public method `@Transactional public RevivalEventDto reviveSelf(long roomId, long userId, RevivalSource source)`. Inside:
    - Acquire advisory lock via `entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(?1, 0))").setParameter(1, "revival:" + roomId + ":" + userId + ":" + eliminatedAtEpochMillis).getSingleResult();`
    - The `eliminatedAt` value comes from the pre-lock `survival_state` read. If `survival_state` is missing or `status = 'ACTIVE'` BEFORE the lock, short-circuit to `AlreadyRevivedException` (no point taking the lock).
    - Re-read `survival_state` AFTER the lock; if `status = 'ACTIVE'`, throw `AlreadyRevivedException`.
    - Branch on `source` (FREE_TICKET vs PERSONAL_POINTS) — implement the exact orderings in AC2 + AC3.
    - Use `RoomPointPoolRepository.selectForUpdate(roomId)` to acquire the FOR UPDATE row lock on the pool row.
    - INSERT `revival_events` using `RevivalEventRepository.insertOnConflictDoNothing(...)`; on empty return, throw `AlreadyRevivedException`. Wrap in a try/catch for `DataIntegrityViolationException` and rethrow as `AlreadyRevivedException` when the constraint name matches `ux_revival_events_one_per_elimination` (AC6 service-layer path).
    - For PERSONAL_POINTS: compute balance via the existing `personalLedger.sumDeltaByUserIdAndRoomId`; balance < 3 → throw `InsufficientPointsException`.
    - INSERT `personal_points_ledger` row with `revival_event_id` set.
    - UPDATE `room_point_pool` (delta +5 or +3).
    - UPDATE `survival_state` status → ACTIVE, clear `eliminated_at` and `broad_visibility_at` and bump `last_state_change_at` — reuse `SurvivalState` package-private setters (precedent: `SurvivalStateService` mutates SurvivalState via package-private setters at `SurvivalStateService.java:255-286`). Add new package-private setters as needed; preserve the "service-only writer" pattern. The two new setters are: `clearEliminatedAt()`, `clearBroadVisibilityAt()` (or wrap into a single `markRevived(Instant now)` helper — pick the smaller diff).
    - Publish `SurvivalStateTransitionEvent(roomId, userId, ownerUserId, fromStatus, ACTIVE, now, null)` — reuses existing event record. The `ownerUserId` comes from the `Room.owner` association (mind the `open-in-view: false` rule — load inside the txn).
    - Publish `PointPoolChangeEvent(roomId, delta, newTotal, revivalEventId, now)` — NEW event record in `revival/`.
    - Return `RevivalEventDto`.
  - [x] BE-4.4 — Private helper `loadEliminatedAtMillis(SurvivalState state)` returns `state.getEliminatedAt().toEpochMilli()`; throws `BadRequestException(code='NOT_ELIMINATED')` when `state.getEliminatedAt()` is null. This is the AC11 test case.

- [x] **Task BE-5 — `PointPoolChangeEvent` + `RoomPointPoolRealtimeListener` (AC2, AC3, AC9)**
  - [x] BE-5.1 — NEW `BE/src/main/java/com/yeosal/api/revival/PointPoolChangeEvent.java` (record): `long roomId, int delta, int newTotal, long sourceRevivalEventId, Instant occurredAt`.
  - [x] BE-5.2 — NEW `BE/src/main/java/com/yeosal/api/revival/PointPoolChangePayload.java` (record, wire shape): same fields. Distinguished from the event record because the event may carry extra orchestration fields in future stories (Story 4.1).
  - [x] BE-5.3 — UPDATE `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java`. Add `public void publishPointPoolChange(long roomId, PointPoolChangePayload payload)` that emits to `/topic/rooms.{roomId}.points`. Mirror the failure-swallow pattern of `publishSurvivalStateChange`. Add javadoc to the class-level destination scheme list.
  - [x] BE-5.4 — NEW `BE/src/main/java/com/yeosal/api/revival/RoomPointPoolRealtimeListener.java`. `@Component`, `@Transactional(propagation = Propagation.REQUIRES_NEW)`, `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`. Receives `PointPoolChangeEvent`, builds `PointPoolChangePayload`, calls `RealtimePublisher.publishPointPoolChange`. Pattern is the `SurvivalStateRealtimeListener` precedent at `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRealtimeListener.java:56-105`.

- [x] **Task BE-6 — `RevivalController` (AC2, AC3, AC11)**
  - [x] BE-6.1 — NEW `BE/src/main/java/com/yeosal/api/revival/RevivalController.java`. `@RestController @RequestMapping("/api/v1/rooms")`. Single endpoint `@PostMapping("/{id}/revival")` taking `Authentication auth`, `@PathVariable long id`, `@Valid @RequestBody RevivalRequest body`. Returns `ApiResponse<RevivalEventDto>`. Constructor injects `RevivalService` + `CurrentUser` + `RoomMemberRepository`.
  - [x] BE-6.2 — Precheck inside the controller: `roomMembers.existsByRoomIdAndUserId(id, me.getId())` must be true; otherwise `throw new ForbiddenException("방 멤버만 회생할 수 있어요.");`. This is the cheap fence before the service does the heavier work (mirrors `SurvivalStateService.roster` line 339–341).
  - [x] BE-6.3 — NEW `BE/src/main/java/com/yeosal/api/revival/RevivalEventDto.java` (record). Fields per AC2: `revivalEventId, source (String), pointsSpent, roomPointPoolAfter, occurredAt`.

- [x] **Task BE-7 — `MeSurvivalEntryDto` extension (AC7)**
  - [x] BE-7.1 — UPDATE `BE/src/main/java/com/yeosal/api/survival/MeSurvivalEntryDto.java`. Add `boolean freeRevivalTicketUsed` as a new record field. Update the javadoc.
  - [x] BE-7.2 — UPDATE `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java#mySurvivalAcrossRooms`. Read the user once (via `users.findById(userId).orElseThrow(...)` — the field is in scope) and replicate the flag into every entry. Keep the existing single-query budget (the user-fetch is one extra SELECT, scoped to per-call once, not per-row).

- [x] **Task BE-8 — Tests (AC11)**
  - [x] BE-8.1 — `RevivalServiceTest.java` (Mockito unit, 8+ cases per AC11).
  - [x] BE-8.2 — `RevivalControllerTest.java` (`@WebMvcTest`, 5+ cases).
  - [x] BE-8.3 — `RevivalConcurrencyIT.java` (`@SpringBootTest` + `@Testcontainers` PostgreSQL, 3 race variants).
  - [x] BE-8.4 — `MeSurvivalFreeTicketTest.java` — `MeSurvivalEntryDto.freeRevivalTicketUsed` reflects underlying flag.

### Frontend (FE/) — API client + hooks + CTA + modal + spectator surface wiring

- [x] **Task FE-1 — API client + types (AC2, AC3, AC7, AC8)**
  - [x] FE-1.1 — NEW `FE/src/api/revival.ts`:
    - `export type RevivalSource = 'FREE_TICKET' | 'PERSONAL_POINTS';`
    - `export interface RevivalEventDto { revivalEventId: number; source: RevivalSource; pointsSpent: number; roomPointPoolAfter: number; occurredAt: string; }`
    - `export async function postSelfRevival(roomId: number, source: RevivalSource): Promise<RevivalEventDto>` — calls `apiRequest<ApiEnvelope<RevivalEventDto>>('/rooms/{roomId}/revival', { method: 'POST', body: JSON.stringify({ source }) })`. **NEVER** direct `fetch`.
  - [x] FE-1.2 — UPDATE `FE/src/lib/spectator.ts:19-27` `MeSurvivalEntry` interface — add `readonly freeRevivalTicketUsed: boolean;` as the LAST field. Update the file's class-level docstring with a "Story 3.1 — `freeRevivalTicketUsed` powers the WalletPreview ticket line." note.

- [x] **Task FE-2 — `useSelfRevival` mutation hook (AC9)**
  - [x] FE-2.1 — NEW `FE/src/lib/query/hooks/revival.ts`:
    - `export function useSelfRevival(roomId: number)` returns a `useMutation` with `mutationFn: (source: RevivalSource) => postSelfRevival(roomId, source)`.
    - `onSuccess`: `queryClient.invalidateQueries({ queryKey: qk.meSurvival })`.
    - `onError`: branch on `ApiError.code`:
      - `'ALREADY_REVIVED'` → still invalidate `qk.meSurvival` (state must be re-read).
      - `'FREE_TICKET_ALREADY_USED'` → invalidate `qk.meSurvival`.
      - `'INSUFFICIENT_POINTS'` → do NOT mutate cache; the toast in AC8 covers UX. The mutation surfaces the error to the caller (the confirm modal) via the `error` returned by `useMutation`.
  - [x] FE-2.2 — UPDATE `FE/src/lib/query/keys.ts` only if a new key is required. Reuse `qk.meSurvival` (already exists from Story 2.1).

- [x] **Task FE-3 — `SelfReviveCTA` + `SelfReviveConfirmModal` components (AC8)**
  - [x] FE-3.1 — NEW `FE/src/components/survival/SelfReviveCTA.tsx`. Props: `{ roomId: number }`. Reads `useCurrentRoomSurvivalState(roomId)` for `status`+`personalPoints`+`freeRevivalTicketUsed`. Renders primary / secondary / muted-caption variant per AC8 state matrix. Uses the shared `<Text>` primitive (Story 1.5) with `accessibilityLabel`. Locked copy strings — brand-voice lint verified. Reanimated-3 micro-press feedback (mirror existing CTA precedents — confirm by grepping `FE/src/components/survival/` for the closest existing CTA primitive; if no precedent exists, use a plain `<TouchableOpacity>` + the design system token surface).
  - [x] FE-3.2 — NEW `FE/src/components/survival/SelfReviveConfirmModal.tsx`. Controlled modal: `props.open: boolean`, `onClose`, `roomId`, `source: RevivalSource`. Renders title + body per AC8. Calls `useSelfRevival(roomId).mutate(source)` on primary CTA tap. Renders toast on success/error (use existing `FE/src/lib/toast.ts`). Auto-close on success / 1.5s delay on error per AC8.
  - [x] FE-3.3 — UPDATE `FE/src/components/survival/index.ts` re-export the two new components.

- [x] **Task FE-4 — Wire into `WalletPreview` + spectator surface (AC7, AC8)**
  - [x] FE-4.1 — UPDATE `FE/src/components/survival/WalletPreview.tsx`. Replace the `const showTicket = true;` placeholder with `const showTicket = first.freeRevivalTicketUsed === false;`. Remove the resolved-TODO comment at lines 27-31. Append `<SelfReviveCTA roomId={first.roomId} />` below the three-line balance block. Read the file FULLY before editing — preserve existing styles + `accessibilityLabel`s.
  - [x] FE-4.2 — UPDATE `FE/src/components/survival/__tests__/WalletPreview.test.tsx` — update test fixtures with the new `freeRevivalTicketUsed` field; add a test case for the ticket-used → hidden behavior.

- [x] **Task FE-5 — Tests (AC11)**
  - [x] FE-5.1 — `FE/src/components/survival/__tests__/SelfReviveCTA.test.tsx` — all 3 state variants + copy assertions.
  - [x] FE-5.2 — `FE/src/components/survival/__tests__/SelfReviveConfirmModal.test.tsx` — flow + success/error paths.
  - [x] FE-5.3 — `FE/src/lib/query/hooks/__tests__/revival.test.tsx` — mutation success + error code invalidation behavior.

### Scripts / verification / sprint-status

- [x] **Task X-1 — Verification gate**
  - [ ] X-1.1 — `cd BE && ./gradlew test` — **deferred to CI**: local toolchain pinned to JDK 21 but only JDK 17 available locally (same constraint hit by Story 2.3). New BE tests authored: `RevivalServiceTest` (9 cases), `RevivalControllerTest` (5 cases), `RevivalConcurrencyIT` (3 race variants, `-Dyeosal.boot-smoke=true`-gated), plus extension of `SurvivalStateServiceMeAcrossRoomsTest` (2 cases for AC1/AC7 free-ticket flag) and updated `MeSurvivalControllerTest` envelope assertion.
  - [ ] X-1.2 — `cd BE && ./gradlew check` — **deferred to CI** for the same JDK 21 reason.
  - [x] X-1.3 — `cd FE && npm test` → 256/256 passed (40 suites). New suites: `SelfReviveCTA.test.tsx` (5), `SelfReviveConfirmModal.test.tsx` (7), `lib/query/hooks/__tests__/revival.test.tsx` (4); updated `WalletPreview.test.tsx` (+1 ticket-used hidden case); updated fixtures in `spectator.test.ts`, `SpectatorRouteProvider.test.tsx`, `survival.test.tsx`, `WalletPreview.test.tsx`.
  - [x] X-1.4 — `cd FE && npm run typecheck && npm run lint` → no new violations from Story 3.1 files. Pre-existing `FriendsTodayPager.tsx` TS errors and 6 lint errors elsewhere (chat.tsx, InviteCodeSheet.test.tsx, SurvivalChip tests, realtime/client.ts) untouched.
  - [x] X-1.5 — `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` → **0 HARD violations**. The Rule 2 warnings on `SelfReviveCTA.tsx:15` and `SelfReviveCTA.test.tsx:112-119` are inside a docstring-banned-words comment and the test's `banned[]` assertion array respectively — identical pattern to the pre-existing `WalletPreview.test.tsx` `banned[]` precedent.
  - [ ] X-1.6 — `bash scripts/verify.sh` from repo root — **deferred to CI** (verify.sh wraps `./gradlew test` which needs JDK 21).

- [x] **Task X-2 — Sprint-status flips**
  - [x] X-2.1 — On dev-story start: flipped `3-1-...` `ready-for-dev → in-progress` in sprint-status.yaml.
  - [x] X-2.2 — After verification gate green: flipped `in-progress → review`. `/code-review` next.
  - [ ] X-2.3 — After review approval + PR merge: flip `review → done`.

- [x] **Task X-3 — Branch hygiene**
  - [x] X-3.1 — Cut branch `feat/story-3-1-free-revival-ticket-and-personal-points` from latest `main`. Target `main` directly. Use the Stack PR Merge Procedure from project-context only if Story 3.5 work is stacked atop — but the execution order is sequential (3.1 → 3.5), so Story 3.1 is a clean PR to main.

### Out-of-scope explicit list

- [x] **Task X-OOS — Documented deferrals (call out in PR description):**
  - Friend-gift revival endpoint (`POST /rooms/{id}/revivals/gifts`) — Story 3.2.
  - Kudos endpoint + `chat_messages.kind = 'KUDOS'` — Story 3.5.
  - Wallet badge ("친구 회생 대기 N") — Story 3.3.
  - Full Wallet UI surface (ledger detail + received-revival history + Bento composition) — Story 3.4.
  - `<PoolStack>` 5-stage SVG asset pipeline — Story 4.3.
  - Pre-revival "eligible friend givers" push — Story 3.2.
  - `<RevivalSequence>` 5-phase M3 animation — Story 3.2 (FRIEND_GIFT receiver-only).
  - `revival_events.source_subtype` semantics — Story 3.3.
  - NFR-9.1.2 p95/p99 latency benchmark — Day-30 telemetry checkpoint.

## Dev Notes

### Architecture patterns (load-bearing — must follow)

- **Two-layer exactly-once defence (Architecture §4.4).** Postgres advisory lock per `(roomId, userId, eliminatedAt)` IS the primary serialiser. The partial unique index `ux_revival_events_one_per_elimination` (V11 lines 76-78) IS the secondary defence. **Both must be in place.** The advisory lock's hash input MUST match the index's column tuple (`roomId, userId, eliminatedAt`) — drift defeats the defence.
- **V11 deviated from Architecture §4.4 prose** — index uses `eliminated_at` (timestamp), NOT `(eliminated_at)::date`. Confirmed by V11 migration comment + PR #57. Lock key & INSERT predicate MUST use raw timestamp / millis. **This is the single most important contract for the dev agent.**
- **Single `@RestControllerAdvice`** — `ApiExceptionHandler` only. This story ADDS two new `@ExceptionHandler` methods to the existing class (no new advice class).
- **Constructor injection only** (project-context Java rule).
- **`open-in-view: false`** — every read of `survival_state.user.id` / `Room.owner.id` must happen inside `@Transactional`. `RevivalService.reviveSelf` is `@Transactional`; the controller is not. Loading `Room.owner` for the listener event payload happens inside the service txn (mirror of `SurvivalStateService.evaluateRoom` line 199 + `SurvivalStateTransitionEvent` shape).
- **STOMP destination convention is dot-separated** (`/topic/rooms.{id}.points`, `/user/{id}/queue/private-survival`) — match `RealtimePublisher` precedent. **Architecture §4.4/§4.6 prose uses slash separators** — that's documentation drift; the runtime contract is dots.
- **`SELECT … FOR UPDATE` on `room_point_pool`** — locks the pool row inside the revival transaction (Architecture §4.6, NFR-9.2.4). Without this, two concurrent revivals for two DIFFERENT users in the same room could both read `total = N`, both write `N + 5`, losing one increment.
- **Append-only ledger** (Architecture §4.5) — never UPDATE `personal_points_ledger`; only INSERT new rows with positive or negative delta. The balance is `SUM(delta)`.
- **`record_visibility_prefs` does NOT apply to revival** — Story 2.3's redaction guards spectator's `daily_entries` / `reflections` / `todo_items` visibility. Self-revival is the user acting on their own state; no third-party privacy check.
- **Free ticket is user-scoped, not room-scoped.** Lifetime 1 across the whole account. A user can be in N rooms; the first revival in any room (via FREE_TICKET) consumes the ticket forever. Architecture §4.12 is explicit: `users.free_revival_ticket_used` (NOT a per-room flag).
- **No system message in chat for self-revival.** Architecture §4.14 + Story 3.2 wire system messages for FRIEND_GIFT only. Self-revival fires `SURVIVAL_STATE_CHANGE` + `POINT_POOL_CHANGE` STOMP frames; no `chat_messages` row.
- **Brand-voice copy is the contract.** All new Korean strings (CTAs, modal copy, toasts, error messages) pass `tools/brand-voice-lint.ts` Rule 2.
- **Immutable updates on FE** — TanStack Query cache updates via `setQueryData(key, (prev) => prev.map(...))`. No mutation.

### Reuse vs. new (read each UPDATE file fully before editing)

**NEW files (BE):**

- `BE/src/main/java/com/yeosal/api/revival/RevivalEvent.java`
- `BE/src/main/java/com/yeosal/api/revival/RevivalSource.java`
- `BE/src/main/java/com/yeosal/api/revival/RevivalEventRepository.java`
- `BE/src/main/java/com/yeosal/api/revival/RoomPointPool.java`
- `BE/src/main/java/com/yeosal/api/revival/RoomPointPoolRepository.java`
- `BE/src/main/java/com/yeosal/api/revival/RevivalRequest.java`
- `BE/src/main/java/com/yeosal/api/revival/RevivalEventDto.java`
- `BE/src/main/java/com/yeosal/api/revival/RevivalService.java`
- `BE/src/main/java/com/yeosal/api/revival/RevivalController.java`
- `BE/src/main/java/com/yeosal/api/revival/AlreadyRevivedException.java`
- `BE/src/main/java/com/yeosal/api/revival/InsufficientPointsException.java`
- `BE/src/main/java/com/yeosal/api/revival/PointPoolChangeEvent.java`
- `BE/src/main/java/com/yeosal/api/revival/PointPoolChangePayload.java`
- `BE/src/main/java/com/yeosal/api/revival/RoomPointPoolRealtimeListener.java`
- `BE/src/test/java/com/yeosal/api/revival/RevivalServiceTest.java`
- `BE/src/test/java/com/yeosal/api/revival/RevivalControllerTest.java`
- `BE/src/test/java/com/yeosal/api/revival/RevivalConcurrencyIT.java`
- `BE/src/test/java/com/yeosal/api/survival/MeSurvivalFreeTicketTest.java` (or extend an existing IT)

**NEW files (FE):**

- `FE/src/api/revival.ts`
- `FE/src/lib/query/hooks/revival.ts`
- `FE/src/components/survival/SelfReviveCTA.tsx`
- `FE/src/components/survival/SelfReviveConfirmModal.tsx`
- `FE/src/components/survival/__tests__/SelfReviveCTA.test.tsx`
- `FE/src/components/survival/__tests__/SelfReviveConfirmModal.test.tsx`
- `FE/src/lib/query/hooks/__tests__/revival.test.tsx`

**UPDATE files (read FULLY before editing):**

- `BE/src/main/java/com/yeosal/api/user/User.java` (add `freeRevivalTicketUsed` field + getter; no mutator).
- `BE/src/main/java/com/yeosal/api/user/UserRepository.java` (add atomic `markFreeTicketUsed` `@Modifying @Query`).
- `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` (two new `@ExceptionHandler` methods; preserve existing handlers).
- `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` (add `publishPointPoolChange`; preserve existing destination scheme javadoc).
- `BE/src/main/java/com/yeosal/api/survival/MeSurvivalEntryDto.java` (add `freeRevivalTicketUsed`; preserve all other fields and the record contract).
- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java` (`mySurvivalAcrossRooms` — wire the user-flag into every entry; preserve all other behaviour).
- `BE/src/main/java/com/yeosal/api/survival/SurvivalState.java` (add package-private setters needed to clear `eliminated_at` / `broad_visibility_at` and re-set status to ACTIVE; Story 1.2 added the RED-direction setters; this story adds the inverse).
- `FE/src/lib/spectator.ts` (add `freeRevivalTicketUsed` field to `MeSurvivalEntry` interface).
- `FE/src/components/survival/WalletPreview.tsx` (replace `showTicket = true` placeholder with the new flag; append `<SelfReviveCTA>`; remove resolved-TODO comment).
- `FE/src/components/survival/__tests__/WalletPreview.test.tsx` (update fixture shape; add ticket-used test case).
- `FE/src/components/survival/index.ts` (re-export new components).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (story status transitions).
- `_bmad-output/implementation-artifacts/3-1-free-revival-ticket-self-revival-via-personal-points.md` (this file's checkboxes, Dev Agent Record, Status).

**Files explicitly NOT touched:**

- `BE/src/main/resources/db/migration/V*__*.sql` — no Flyway migration. V11 already has every table this story needs.
- `BE/src/main/java/com/yeosal/api/{auth, friend, notification, room/chat}/` — no edits beyond the controller-wiring touchpoints noted above.
- Existing FE components outside `survival/` — Story 3.1 does not touch them. The chat screen's spectator banner (Story 2.1) already exists and is unchanged.
- Existing FE survival query keys — reuse, do not rename.
- Existing `SurvivalStateRealtimeListener.java` — reuse via the existing `SurvivalStateTransitionEvent` payload. Do not duplicate.

### Testing standards summary

- **BE:** JUnit 5 + AssertJ + Mockito for unit; `@SpringBootTest` + `@Testcontainers` PostgreSQL (NO H2 — project-context "Testcontainers required for partial unique indexes, advisory locks, jsonb") for IT. JWT auth via the existing test helper. Tests live at `BE/src/test/java/com/yeosal/api/revival/...` mirroring the main package layout. Naming convention: `methodName_scenario_expectedBehavior()` or `@DisplayName`.
- **FE:** Jest + `@testing-library/react-native`. TanStack Query mutation tests stub `apiRequest`. STOMP-touching code (Story 3.1 only depends on STOMP via Story 1.2's existing fanout, not via new client code) does not need a real WebSocket — mock at the realtime-provider boundary.
- **Concurrency tests are non-optional.** Architecture §4.4 explicitly calls out advisory-lock + partial-unique-index defence. A test that asserts "exactly one of N parallel revivals succeeds" is the contract.
- **Coverage target:** 80%+ on new BE service / controller / repository code; 80%+ on new FE CTA / modal / hook code.

### Previous-story intelligence

- **Story 1.2 — `SurvivalStateService`** (`BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java`) is the canonical example of the `@Transactional` per-room write path + `SurvivalStateTransitionEvent` post-commit fanout. The advisory-lock + INSERT/UPDATE order pattern in this story copies that structure.
- **Story 1.2 — `PersonalPointsLedger` precedent** (`BE/src/main/java/com/yeosal/api/revival/PersonalPointsLedger.java:25-92`) — the writer pattern for ledger rows is `personalLedger.save(new PersonalPointsLedger(userId, roomId, delta, reason, now))`. Story 3.1 follows the same constructor shape; add the `revival_event_id` via the second constructor at line 67-76.
- **Story 1.3 — `SurvivalStateService.roster`** (lines 327-360) — the "load owner inside @Transactional to defeat `open-in-view: false`" precedent at line 337 is the rule for Story 3.1's `Room.owner.id` load during `SurvivalStateTransitionEvent` construction.
- **Story 1.4 — V11 migration** (`BE/src/main/resources/db/migration/V11__survival_revival_economy.sql`) — already in production. Story 3.1 maps entities to existing tables.
- **Story 1.4 — PR #57 (commit `4f741ff`)** — `fix(migration): drop non-IMMUTABLE ::date cast from V11 partial unique index`. **READ THE COMMIT MESSAGE.** The reason the index is `eliminated_at` (not `(eliminated_at)::date`) is this PR. Lock key + INSERT predicate MUST match.
- **Story 1.5 — Brand-voice lint** (`tools/brand-voice-lint.ts`) — AVOID lexicon Rule 2 applies to all new copy. Run before commit.
- **Story 2.1 — `WalletPreview.tsx`** (`FE/src/components/survival/WalletPreview.tsx`) — already left a TODO `// Story 3.1 will add user.freeRevivalTicketUsed` at line 28-31. AC7 resolves the TODO.
- **Story 2.1 — `MeSurvivalEntryDto`** (`BE/src/main/java/com/yeosal/api/survival/MeSurvivalEntryDto.java:32-37`) — extension model for the cross-room aggregation. Add `freeRevivalTicketUsed` as the new last field; preserve the existing field order so consumer tests don't churn.
- **Story 2.3 — Read-side redaction precedent** — does NOT apply to revival flows. Self-revival has no third-party privacy concern.
- **Story 2.2 — Half-open window pattern** — does NOT apply here (no time-window count).

### Git intelligence (recent commits informing this story)

- `387a955` (PR #71, 2026-05-16) — Story 2.3 record-visibility opt-in. Reads `record_visibility_prefs` per (user, room). Story 3.1 does not touch this surface.
- `191911e` (PR #69, 2026-05-16) — Story 2.2 spectator daily digest. Half-open window pattern; not applicable here.
- `dd0b9b1` (PR #68, 2026-05-16) — Story 2.1 spectator FE routing branch. `SpectatorRouteProvider` is the spectator gate; Story 3.1's `SelfReviveCTA` consumes `useCurrentRoomSurvivalState` (same TanStack key).
- `2182ca9` (PR #62, 2026-05-13) — Story 1.4 V11 migration review followups. Confirms V11 + `record_visibility_prefs` in production.
- `4f741ff` (PR #57, 2026-05-13) — V11 partial unique index `::date` → `eliminated_at` (timestamp). **The single most important commit for this story.** Read the commit body for the SQLSTATE 42P17 reason.
- `0bab9d3` (PR #67, 2026-05-15) — Epic 1 retro flip. Confirms `MeSurvivalController` is on `/api/v1/me/...` (so this story's controller uses `/api/v1/rooms/{id}/revival`, not `/api/v1/me/revival` — the architecture mapping at §6.4 row 4 is room-scoped).
- `ed4785e` (PR #64, 2026-05-15) — Epic 1 retro T4/T5. Confirms `MeSurvivalEntryDto` already carries `personalPoints` + `roomPointPool`. Story 3.1 extends with `freeRevivalTicketUsed`.

### Latest technical specifics

- **JJWT 0.12.x** — not in this story's path. Auth is handled by the existing `JwtAuthenticationFilter`.
- **Spring `@TransactionalEventListener(phase = AFTER_COMMIT)`** — Spring Framework 6.x / Boot 3.3.x. The listener runs in a NEW transaction (`REQUIRES_NEW`). This is the existing pattern in `SurvivalStateRealtimeListener`.
- **PostgreSQL 16 advisory locks** — use `pg_advisory_xact_lock(hashtextextended(text, 0))` to produce a `bigint` from a stable text key and acquire the standard 64-bit advisory lock. The lock auto-releases at transaction end (commit or rollback). The triple key `revival:{roomId}:{userId}:{eliminatedAtEpochMillis}` matches the partial unique index's column tuple.
- **TanStack Query 5.x** — `invalidateQueries` is the API; `queryClient.clear()` is the AsyncStorage nuke that project-context bans.
- **Expo SDK 54 / RN 0.81.5** — `<Switch>` / `<TouchableOpacity>` are RN built-ins, no new packages. Reanimated 3 micro-press feedback is optional (use existing pattern).

### Project context reference

Mandatory pre-read: `_bmad-output/project-context.md`. Load-bearing rules for this story:

- BE controller paths use `/api/v1/...` only — context-path `/yeolsal` is auto-prefixed.
- All controller responses wrapped in `ApiResponse.of(...)`.
- Single `@RestControllerAdvice` — extend `ApiExceptionHandler`, do not introduce a second.
- TanStack Query persisted to AsyncStorage — `invalidateQueries`, never `clear()`.
- Hibernate `validate` mode — schema changes require Flyway migrations. Story 3.1 does NOT add schema.
- JPA `open-in-view: false` — service-layer `@Transactional` reads only.
- `@Valid` on controller DTOs — `MethodArgumentNotValidException` maps to `400 VALIDATION`.
- DTOs are `record`s.
- All API calls FE-side via `apiRequest<T>` — direct `fetch` forbidden.
- Realtime via the single `RealtimeProvider` STOMP client — no second client.
- Postgres-specific features (partial unique indexes, advisory locks, jsonb) — **Testcontainers required**, H2 forbidden.

### References

- Epics: `_bmad-output/planning-artifacts/epics.md` lines 419–453.
- PRD: `_bmad-output/planning-artifacts/prd.md` FR-8.3.1, FR-8.3.2, FR-8.3.8, NFR-9.1.2, NFR-9.2.2, NFR-9.2.4.
- Architecture: `_bmad-output/planning-artifacts/architecture.md` §4.4, §4.5, §4.6, §4.12, §5.3, §6.1, §6.3, §6.4.
- UX: `_bmad-output/planning-artifacts/ux-design-specification.md` lines 240–248 (Effortless #2), 253–262 (M2/M3 critical moments), 1280–1308 (J2 spectator → revival flow).
- V11 schema (in-prod): `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql` lines 19–22 (free ticket flag), 52–80 (revival_events + partial unique index), 82–92 (personal_points_ledger), 94–99 (room_point_pool).
- Project context: `_bmad-output/project-context.md` (all of Critical Implementation Rules section).
- Realtime publisher: `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` (existing destination scheme dot-separator convention).
- Survival realtime listener (post-commit pattern): `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRealtimeListener.java`.
- Survival service (transactional pattern + ApplicationEventPublisher): `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java`.
- Existing revival module scaffolding: `BE/src/main/java/com/yeosal/api/revival/{PersonalPointsLedger,PersonalPointsLedgerRepository,LedgerReason}.java`.
- Existing WalletPreview consumer (Story 2.1): `FE/src/components/survival/WalletPreview.tsx`.
- Existing me-survival query (Story 2.1): `FE/src/lib/query/hooks/survival.ts` + `FE/src/api/survival.ts` + `FE/src/lib/spectator.ts`.

### Review Findings

- [x] [Review][Patch] PERSONAL_POINTS revival bypasses the lifetime free-ticket ordering — AC3 requires `users.free_revival_ticket_used = true` before self-revival can spend personal points, but `RevivalService` only checks the flag in the `FREE_TICKET` branch and the `PERSONAL_POINTS` branch proceeds on balance alone. [`BE/src/main/java/com/yeosal/api/revival/RevivalService.java:118`]
  - **Resolved (2026-05-16):** `RevivalService.reviveSelf` now loads `users.findById(userId)` inside the PERSONAL_POINTS branch and throws `BadRequestException("무료 회생권을 먼저 사용해주세요.")` when `freeRevivalTicketUsed == false`. New unit test `RevivalServiceTest#reviveSelf_personalPoints_beforeTicketUsed_throwsBadRequest` covers the guard.
- [x] [Review][Patch] Fresh rooms do not get a `room_point_pool` row — V11 backfills existing rooms once, but `RoomService.create` does not create the counter-cache row for rooms created after the migration; `RevivalService.selectForUpdate(roomId)` then throws `IllegalStateException`, and `RevivalConcurrencyIT` masks the gap by seeding the row manually. [`BE/src/main/java/com/yeosal/api/room/RoomService.java:128`]
  - **Resolved (2026-05-16):** `RoomService.create` seeds `roomPointPool.save(new RoomPointPool(room.getId(), 0))` inside the existing `@Transactional` method. `RoomService` constructor extended with the new repo (Spring-wired); 3 unit test setup methods updated. `RoomServiceTest#createPersistsMaxMembersAndSurvivalState` now verifies the pool row is minted with `total = 0`.
- [x] [Review][Patch] Invalid `source` body is not mapped to `400 VALIDATION` — Jackson enum binding failures are `HttpMessageNotReadableException`, but `ApiExceptionHandler` only maps `MethodArgumentNotValidException` and request-param mismatches, so malformed/unknown enum JSON can fall to the generic 500 handler; the controller test checks only status, not the required error code. [`BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java:72`]
  - **Resolved (2026-05-16):** Added `HttpMessageNotReadableException.class` to the existing `requestParamValidation` `@ExceptionHandler` tuple — invalid enum JSON now surfaces as `400 VALIDATION`. `RevivalControllerTest#revive_invalidSource_returns400Validation` asserts the wire code.
- [x] [Review][Patch] Points realtime topic is published but not allowed by the STOMP auth interceptor — `RealtimePublisher.publishPointPoolChange` emits `/topic/rooms.{id}.points`, while `JwtChannelInterceptor` still permits only the existing room topics, so future clients cannot subscribe to the new topic. [`BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java:88`]
  - **Resolved (2026-05-16):** Extended `JwtChannelInterceptor.ROOM_TOPIC` regex to include `points` (`^/topic/rooms\\.(\\d+)\\.(chat|members|survival|points)$`). Class-level javadoc updated.
- [x] [Review][Patch] Error auto-close timers in `SelfReviveConfirmModal` are never cleared — delayed `setTimeout(onClose, 1500)` calls can fire after the user closes/reopens the modal or after unmount, closing a later modal instance and causing test `act(...)` warnings. [`FE/src/components/survival/SelfReviveConfirmModal.tsx:72`]
  - **Resolved (2026-05-16):** Introduced `closeTimerRef = useRef<...>(null)` + `clearCloseTimer()` + `scheduleAutoClose()` helpers. The `useEffect` that observes `open === false` now also clears the pending timer; a sibling `useEffect` returns `clearCloseTimer` as its unmount cleanup. All four error paths now route through `scheduleAutoClose()`, which clears any previous timer before scheduling.
- [x] [Review][Patch] AC5 concurrency test does not prove the loser contract — `RevivalConcurrencyIT` is opt-in via `-Dyeosal.boot-smoke=true` and the race helper counts any exception as a failure, while AC5/AC11 require proving the loser receives `AlreadyRevivedException` / `409 ALREADY_REVIVED`. [`BE/src/test/java/com/yeosal/api/revival/RevivalConcurrencyIT.java:63`]
  - **Resolved (2026-05-16):** `Outcomes` record now carries `List<Throwable> errors`; the race helper appends to a `CopyOnWriteArrayList`. All three race tests now assert `outcomes.errors().singleElement().isInstanceOf(AlreadyRevivedException.class)` (FREE_TICKET × 2 accepts either `AlreadyRevived` OR `FreeTicketAlreadyUsed` since both are valid loser outcomes per the AC5 contract).
- [x] [Review][Patch] AC1 signup/default invariant is not covered at the signup seam — the entity maps `freeRevivalTicketUsed = false`, but the requested integration coverage for user insertion/signup paths is missing; current coverage is me-survival/migration-oriented. [`BE/src/main/java/com/yeosal/api/user/User.java:48`]
  - **Resolved (2026-05-16):** NEW `BE/src/test/java/com/yeosal/api/revival/MeSurvivalFreeTicketIT.java` (`-Dyeosal.boot-smoke=true`-gated). Two cases: (a) fresh `users.save(...)` → both JPA-loaded and raw-column reads of `free_revival_ticket_used` are `false`; (b) `UserRepository.markFreeTicketUsed` first call returns 1, second returns 0, proving the atomic check-and-set.

### Re-review Findings (2026-05-17)

- [x] [Review][Decision] WalletPreview self-revival targets `entries[0]` in multi-room accounts — accepted for v1 because multi-room is not user-facing yet; this is retained only as a future expansion seam. `WalletPreview` can keep using the first `/me/survival` row until explicit multi-room UX lands. [`FE/src/components/survival/WalletPreview.tsx:23`]
- [ ] [Review][Patch] DefaultRoomMigrationRunner still creates rooms without `room_point_pool` — the Story 3.1 patch fixed `RoomService.create`, but the startup default-room seeder bypasses that service and calls `rooms.save(new Room(...))` directly after Flyway backfill has already run, leaving seeded default rooms unable to self-revive. [`BE/src/main/java/com/yeosal/api/room/DefaultRoomMigrationRunner.java:116`]
- [ ] [Review][Patch] Mixed-source concurrency test can fail on a valid PERSONAL_POINTS-first schedule — after the AC3 guard, `FREE_TICKET vs PERSONAL_POINTS` with an unused ticket can legitimately produce a `BadRequestException` if PERSONAL_POINTS enters first, but the test currently asserts the only loser is `AlreadyRevivedException`. [`BE/src/test/java/com/yeosal/api/revival/RevivalConcurrencyIT.java:197`]
- [ ] [Review][Patch] FREE_TICKET x2 concurrency test weakens the AC5 loser contract — for the same elimination and same source, the loser should observe `ACTIVE` after the advisory lock and surface `AlreadyRevivedException`; accepting `FreeTicketAlreadyUsedException` lets the test pass without proving the required `409 ALREADY_REVIVED` path. [`BE/src/test/java/com/yeosal/api/revival/RevivalConcurrencyIT.java:120`]
- [ ] [Review][Patch] SelfReviveConfirmModal can still act on stale mutation callbacks — timer cleanup prevents stale scheduled closes, but a late mutation `onError`/`onSuccess` after manual close or reopen can still mutate state, show a toast, or schedule `onClose` for the later modal instance. [`FE/src/components/survival/SelfReviveConfirmModal.tsx:87`]
- [ ] [Review][Patch] AC1 still lacks signup/auth seam coverage — `MeSurvivalFreeTicketIT` proves repository insertion defaults, but AC1 explicitly calls out signup/login/Kakao insertion seams; add coverage through `AuthService.signup` or `POST /api/v1/auth/signup` so the actual signup path cannot override `free_revival_ticket_used`. [`BE/src/test/java/com/yeosal/api/revival/MeSurvivalFreeTicketIT.java:68`]
- [ ] [Review][Patch] AC7 user lookup silently defaults missing users to a free ticket — `mySurvivalAcrossRooms` uses `users.findById(userId).map(...).orElse(false)`, while the story specified `orElseThrow`; a bad principal/user mismatch can expose a false ticket flag instead of failing closed. [`BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java:425`]
- [ ] [Review][Patch] Persisted FE query cache was not version-bumped for the new required field — `freeRevivalTicketUsed` is treated as a strict boolean, but `FE/src/lib/query/persist.ts` still uses `yeosal.query.v1` / `buster: "v1"`, so hydrated old `meSurvival` rows can hide the ticket line and CTA until refetch. [`FE/src/lib/query/persist.ts:5`]
- [ ] [Review][Patch] Unknown self-revival failures show the insufficient-points toast — `SelfReviveConfirmModal` falls through every non-whitelisted error to `"포인트가 모자라요"` and auto-closes, so offline/server/free-ticket failures can present a false cause. [`FE/src/components/survival/SelfReviveConfirmModal.tsx:111`]

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context) — `claude-opus-4-7[1m]`. Single-session implementation 2026-05-16.

### Debug Log References

- `cd FE && npx jest src/lib/query/hooks/__tests__/revival.test.tsx src/components/survival/__tests__/SelfReviveCTA.test.tsx src/components/survival/__tests__/SelfReviveConfirmModal.test.tsx` → 16/16 pass.
- `cd FE && npm test` → 256/256 pass, 40 suites green.
- `cd FE && npx tsc --noEmit` → only the pre-existing `FriendsTodayPager.tsx` errors surface (unrelated to Story 3.1).
- `cd FE && npm run lint` → 6 pre-existing errors elsewhere; new Story 3.1 files lint-clean.
- `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` → 0 HARD violations on new copy.
- `cd BE && ./gradlew compileJava` → blocked locally: `Cannot find a Java installation … languageVersion=21 … on aarch64`. Only JDK 17 available locally (Story 2.3 hit the same gate; tests run in CI).

### Completion Notes List

- **AC1 free-ticket flag default + signup invariant** — `User.freeRevivalTicketUsed` JPA-mapped to V11's existing column with default `false`; verified by extending `SurvivalStateServiceMeAcrossRoomsTest` with the fresh-user defaults-false case.
- **AC2 / AC3 / AC4 / AC5 transactional flow** — `RevivalService.reviveSelf` enforces the documented order: pre-lock status read, advisory lock keyed on `(roomId, userId, eliminatedAtEpochMillis)`, post-lock refresh, source-specific check (FREE_TICKET atomic flag flip OR PERSONAL_POINTS balance check), `SELECT … FOR UPDATE` on `room_point_pool`, INSERT `revival_events` via ON CONFLICT inference clause matching the partial unique index predicate, ledger debit on PERSONAL_POINTS, pool increment, survival_state flip via `SurvivalState.markRevived(now)`, two events published. The `RoomService.create` v1 backfill already mints `room_point_pool` rows.
- **AC6 ApiExceptionHandler discriminator** — service catches `DataIntegrityViolationException` and inspects the most-specific cause's message for the `ux_revival_events_one_per_elimination` constraint name (per Architecture §4.4 prefer-service-layer-catch path). `ApiExceptionHandler.dataIntegrity` left unchanged; if `RevivalConcurrencyIT` ever proves the violation escapes the service catch (flush past return), the handler can be extended with the same constraint discriminator.
- **AC7 wire-shape addition** — `MeSurvivalEntryDto` carries `freeRevivalTicketUsed` as the last field; `SurvivalStateService.mySurvivalAcrossRooms` reads `users.findById` once per call and replicates the flag across every row.
- **AC8 / AC9 FE surface** — `<SelfReviveCTA>` branches on state (ticket-unused / ticket-used+balance≥3 / muted-caption); `<SelfReviveConfirmModal>` calls `useSelfRevival(roomId).mutate(source)`; cache invalidation matrix per AC9 (success + ALREADY_REVIVED + FREE_TICKET_ALREADY_USED invalidate `qk.meSurvival`; INSUFFICIENT_POINTS does not). The existing `SURVIVAL_STATE_CHANGE` STOMP fanout (Story 1.2) auto-routes the revival because the service publishes the existing `SurvivalStateTransitionEvent`; no new FE STOMP subscriber needed for self-revival.
- **AC10 scope** — no Flyway migration; no edits beyond the listed touchpoints; left FRIEND_GIFT/Kudos/Wallet UI/PoolStack out-of-scope.
- **Implementation deviation: typed BadRequest subclasses** — the story envisioned `BadRequestException(code='FREE_TICKET_ALREADY_USED')` and `(code='NOT_ELIMINATED')`. Base `BadRequestException` has no code field, so I introduced typed subclasses (`FreeTicketAlreadyUsedException`, `NotEliminatedException`) following the existing `SpectatorWriteForbiddenException` precedent (typed subclass + `CODE` constant + handler that reads it). Wire codes are unchanged.
- **Implementation deviation: INSERT row count vs RETURNING** — `RevivalEventRepository.insertOnConflictDoNothing` returns `int` row count rather than `Optional<Long>`. Spring Data JPA's `@Modifying` + `RETURNING id` interaction is fragile; using `int` + a follow-up `findByRoomIdAndUserIdAndEliminatedAt` to fetch the persisted id is one extra SELECT inside the same transaction and avoids the ambiguity. Exactly-once defence is unchanged.
- **Implementation deviation: SurvivalState mutator** — `SurvivalState.markRevived(Instant now)` is a public method (not four cross-package setters) because `RevivalService` lives in the sibling `revival/` package. Single named operation preserves the service-mediated-mutation intent.

### File List

**BE — new files**
- `BE/src/main/java/com/yeosal/api/revival/RevivalSource.java`
- `BE/src/main/java/com/yeosal/api/revival/RevivalEvent.java`
- `BE/src/main/java/com/yeosal/api/revival/RevivalEventRepository.java`
- `BE/src/main/java/com/yeosal/api/revival/RoomPointPool.java`
- `BE/src/main/java/com/yeosal/api/revival/RoomPointPoolRepository.java`
- `BE/src/main/java/com/yeosal/api/revival/AlreadyRevivedException.java`
- `BE/src/main/java/com/yeosal/api/revival/InsufficientPointsException.java`
- `BE/src/main/java/com/yeosal/api/revival/FreeTicketAlreadyUsedException.java`
- `BE/src/main/java/com/yeosal/api/revival/NotEliminatedException.java`
- `BE/src/main/java/com/yeosal/api/revival/RevivalRequest.java`
- `BE/src/main/java/com/yeosal/api/revival/RevivalEventDto.java`
- `BE/src/main/java/com/yeosal/api/revival/RevivalService.java`
- `BE/src/main/java/com/yeosal/api/revival/RevivalController.java`
- `BE/src/main/java/com/yeosal/api/revival/PointPoolChangeEvent.java`
- `BE/src/main/java/com/yeosal/api/revival/PointPoolChangePayload.java`
- `BE/src/main/java/com/yeosal/api/revival/RoomPointPoolRealtimeListener.java`
- `BE/src/test/java/com/yeosal/api/revival/RevivalServiceTest.java`
- `BE/src/test/java/com/yeosal/api/revival/RevivalControllerTest.java`
- `BE/src/test/java/com/yeosal/api/revival/RevivalConcurrencyIT.java`

**BE — updated files**
- `BE/src/main/java/com/yeosal/api/user/User.java` — added `freeRevivalTicketUsed` field + getter.
- `BE/src/main/java/com/yeosal/api/user/UserRepository.java` — added `markFreeTicketUsed` atomic check-and-set.
- `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` — added four new handlers; review patch 3 added `HttpMessageNotReadableException` → 400 VALIDATION.
- `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` — added `publishPointPoolChange` + docstring update.
- `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java` — review patch 4: ROOM_TOPIC regex permits `points`.
- `BE/src/main/java/com/yeosal/api/revival/RevivalService.java` — review patch 1: PERSONAL_POINTS branch loads `users.findById` + lifetime guard.
- `BE/src/main/java/com/yeosal/api/room/RoomService.java` — review patch 2: constructor accepts `RoomPointPoolRepository`; `create` seeds the pool row.
- `BE/src/main/java/com/yeosal/api/survival/MeSurvivalEntryDto.java` — added `freeRevivalTicketUsed` record component.
- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java` — `mySurvivalAcrossRooms` reads + replicates the flag.
- `BE/src/main/java/com/yeosal/api/survival/SurvivalState.java` — added public `markRevived(Instant now)`.
- `BE/src/test/java/com/yeosal/api/survival/MeSurvivalControllerTest.java` — fixture + envelope assertion for new field.
- `BE/src/test/java/com/yeosal/api/survival/SurvivalStateServiceMeAcrossRoomsTest.java` — fixture update + 2 new test cases for AC1/AC7.
- `BE/src/test/java/com/yeosal/api/revival/RevivalServiceTest.java` — review patch 1: default user-mock w/ ticket-used + new `before-ticket-used → BadRequest` test.
- `BE/src/test/java/com/yeosal/api/revival/RevivalControllerTest.java` — review patch 3: invalid-source now asserts `code = "VALIDATION"`.
- `BE/src/test/java/com/yeosal/api/revival/RevivalConcurrencyIT.java` — review patch 6: race helper captures errors; tests assert loser exception type.
- `BE/src/test/java/com/yeosal/api/room/RoomServiceTest.java` — review patch 2: new mock + verify pool seed.
- `BE/src/test/java/com/yeosal/api/room/RoomServiceMemberJoinSystemMessageTest.java` — review patch 2 fallout: new mock + ctor arg.
- `BE/src/test/java/com/yeosal/api/room/RoomServiceEvaluationTest.java` — review patch 2 fallout: new mock + ctor arg.

**BE — new files added by review patches**
- `BE/src/test/java/com/yeosal/api/revival/MeSurvivalFreeTicketIT.java` — review patch 7: AC1 signup default + atomic check-and-set IT (`-Dyeosal.boot-smoke=true`-gated).

**FE — new files**
- `FE/src/api/revival.ts`
- `FE/src/lib/query/hooks/revival.ts`
- `FE/src/components/survival/SelfReviveCTA.tsx`
- `FE/src/components/survival/SelfReviveConfirmModal.tsx`
- `FE/src/components/survival/__tests__/SelfReviveCTA.test.tsx`
- `FE/src/components/survival/__tests__/SelfReviveConfirmModal.test.tsx`
- `FE/src/lib/query/hooks/__tests__/revival.test.tsx`

**FE — updated files**
- `FE/src/lib/spectator.ts` — added `freeRevivalTicketUsed` to `MeSurvivalEntry` + docstring.
- `FE/src/components/survival/WalletPreview.tsx` — replaced placeholder + appended `<SelfReviveCTA>`.
- `FE/src/components/survival/SelfReviveConfirmModal.tsx` — review patch 5: `useRef`-tracked auto-close timer with unmount/reopen cleanup.
- `FE/src/components/survival/index.ts` — re-exports for the two new components.
- `FE/src/components/survival/__tests__/WalletPreview.test.tsx` — fixture + 1 new ticket-used hidden case.
- `FE/src/lib/__tests__/spectator.test.ts` — fixture update.
- `FE/src/providers/__tests__/SpectatorRouteProvider.test.tsx` — fixture update.
- `FE/src/lib/query/hooks/__tests__/survival.test.tsx` — fixture update.

**Sprint-status**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — ready-for-dev → in-progress → review.
- `_bmad-output/implementation-artifacts/3-1-free-revival-ticket-self-revival-via-personal-points.md` — Status + Tasks/Subtasks + Dev Agent Record + File List.

### Change Log

| Date       | Change                                                                                     |
| ---------- | ------------------------------------------------------------------------------------------ |
| 2026-05-16 | Implemented Story 3.1 — self-revival (FREE_TICKET + PERSONAL_POINTS) on `feat/story-3-1-free-revival-ticket-and-personal-points`. FE 256/256 green; BE tests authored, pending CI JDK 21. |
| 2026-05-16 | Addressed code review findings — 7 review-patch items resolved: AC3 PERSONAL_POINTS lifetime guard, `RoomService.create` seeds `room_point_pool`, `HttpMessageNotReadableException` → 400 VALIDATION, `JwtChannelInterceptor` permits `/topic/rooms.{id}.points`, `SelfReviveConfirmModal` clears auto-close timers, `RevivalConcurrencyIT` asserts loser exception type, NEW `MeSurvivalFreeTicketIT` for AC1 signup invariant + atomic check-and-set. FE 256/256 still green. |
| 2026-05-17 | Self-audit of the review-patch round caught 2 BE bugs and fixed them: (a) `RevivalConcurrencyIT.forceRed` called `SurvivalState` package-private setters across packages — refactored to a reflection helper so the IT compiles; (b) the FREE_TICKET vs PERSONAL_POINTS race assertion now accepts both `AlreadyRevivedException` AND `BadRequestException` as valid loser shapes (Patch 1's lifetime guard means PERSONAL_POINTS can legitimately fail when it wins the advisory lock first). FE 256/256 still green; BE re-verify pending CI JDK 21. |
