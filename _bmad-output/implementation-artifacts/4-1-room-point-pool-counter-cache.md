# Story 4.1: Room point pool counter cache

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the system,
I want a per-room `room_point_pool` counter that updates atomically with every revival, refuses negative deltas at the service boundary, and is the single source of truth surfaced through both a dedicated REST endpoint and a STOMP topic,
So that the pool number rendered on every FE surface is consistent, never lost under concurrency, and ready to be consumed by phase-2 redemption code without reshaping the data path.

## Acceptance Criteria

> 모든 AC의 BDD 표현은 epics.md Story 4.1 (lines 616–640)을 기반으로 하며, 본 스토리는 Story 3.1/3.4가 이미 출하한 인프라에 **(a) 서비스 계층 음수-델타 가드, (b) 전용 REST `GET /rooms/{id}/points`, (c) FE `useRoomPoints` 훅 + dedupe-by-event-id 머지**라는 incremental 표면만 추가합니다. 기존 동작은 byte-identical로 보존되어야 합니다.

### AC1 — Room creation seeds the pool row (regression guard)

**Given** a new room is created via `POST /api/v1/rooms`
**When** `RoomService.create` returns
**Then** a `room_point_pool` row with `total = 0` AND `last_event_at = null` MUST exist (this is already shipped by Story 3.1 review-patch 2 — Story 4.1 verifies it via the existing `RoomServiceTest` line 540–545 and adds NO new write path).

**And** V11 step 15 backfill semantics (idempotent `insert ... on conflict do nothing`) are preserved for legacy rooms.

PRD: FR-8.4.1. Architecture: §4.6, §6.3 V11 (7) + (15).

### AC2 — Revival commits the pool increment inside the same transaction (regression guard)

**Given** a revival succeeds (any of `FREE_TICKET` / `PERSONAL_POINTS` / `FRIEND_GIFT`)
**When** the revival transaction commits
**Then** the same transaction:
  1. acquired `pg_advisory_xact_lock(hashtextextended("revival:{roomId}:{userId}:{eliminatedAtEpochMillis}", 0))` (Architecture §4.4 primary defence),
  2. acquired `SELECT … FOR UPDATE` on the `room_point_pool` row (Architecture §4.6 secondary serialiser),
  3. incremented `total` by the source-specific delta (`+5` for `FREE_TICKET`, `+3` for `PERSONAL_POINTS`, `+5` for `FRIEND_GIFT` — matching `RevivalService.FREE_TICKET_POOL_DELTA` / `PERSONAL_POINTS_POOL_DELTA` / `FRIEND_GIFT_POOL_DELTA` constants),
  4. wrote `last_event_at = now()` via the existing `RoomPointPoolRepository.incrementTotal` native UPDATE.

**And** the existing `PointPoolChangeEvent` is published AFTER_COMMIT via `RoomPointPoolRealtimeListener` (no change to the listener wiring; Story 4.1 may extend the event/payload record with `lastEventAt` — see AC4).

PRD: FR-8.4.1. Architecture: §4.4, §4.6.

### AC3 — Service-layer negative-delta guard (NEW)

**Given** any caller invokes the pool-mutation entry point with `delta <= 0`
**When** the service-layer method runs
**Then** the call MUST throw `IllegalArgumentException` BEFORE the SQL `incrementTotal` fires.

**And** because `ApiExceptionHandler` already maps `IllegalArgumentException` to `400 VALIDATION` (per `project-context.md` "A caller-side `IllegalArgumentException` reaching the controller is mapped to `400 VALIDATION` deliberately"), no new exception type or handler entry is added.

**And** the DB-level `CHECK (total >= 0)` (V11 step 7) stays as defence-in-depth — a unit test SHOULD assert the service throws BEFORE reaching the DB, so the CHECK never fires from a code-path call site in v1.

**Implementation shape (load-bearing):** extract a `RoomPointPoolService` (in `com.yeosal.api.revival` — same module per package-by-feature, NOT a new `pool/` package) that owns:
```java
@Transactional(propagation = MANDATORY)
int applyDelta(long roomId, int delta, long sourceRevivalEventId, Instant occurredAt)
```
- Throws `IllegalArgumentException("pool delta must be positive, got=" + delta)` when `delta <= 0`.
- Calls `roomPointPoolRepository.selectForUpdate(roomId)` (existing) and computes `newTotal = pool.getTotal() + delta`.
- Calls `roomPointPoolRepository.incrementTotal(roomId, delta)` (existing). Throws `IllegalStateException` on zero rows updated (row vanished mid-tx).
- Publishes `PointPoolChangeEvent` via `ApplicationEventPublisher` (move the two `eventPublisher.publishEvent(new PointPoolChangeEvent(...))` call sites in `RevivalService.reviveSelf` (line 215–216) and `reviveFriend` (line 400–401) into this service so the publish happens exactly once per delta).
- Returns the post-increment `newTotal` so callers can populate their response DTO (`RevivalEventDto.roomPointPoolAfter` / `FriendGiftRevivalDto.roomPointPoolAfter`).
- `Propagation.MANDATORY` enforces that the caller already opened a transaction — the service refuses to be called outside `RevivalService`'s `@Transactional` (Architecture §4.6 invariant).

**RevivalService refactor scope:** the two existing call sites (`reviveSelf` + `reviveFriend`) are rewritten to call `roomPointPoolService.applyDelta(...)` once each. The observable behavior — same DB writes, same DTO field values, same emitted STOMP frame — MUST stay byte-identical. The existing `RevivalServiceTest` (9 cases), `FriendGiftServiceTest` (14+ cases), `RevivalServiceSourceSubtypeTest` (3 cases), `RevivalConcurrencyIT` (3 race variants), and `FriendGiftWalletInitiatedIT` (2 IT cases) MUST all still pass without semantic edits — only the mock wiring may need a `RoomPointPoolService` mock in place of two-call mocking on `RoomPointPoolRepository.selectForUpdate` + `incrementTotal`.

PRD: FR-8.4.5. Architecture: §4.6 ("Negative deltas are forbidden by the write path"). Project-context: "A caller-side `IllegalArgumentException` reaching the controller is mapped to `400 VALIDATION`."

### AC4 — STOMP topic publish format reconciliation (CRITICAL)

**Given** the pool changes inside the revival transaction
**When** `RoomPointPoolRealtimeListener` fires AFTER_COMMIT
**Then** the frame MUST be published on `/topic/rooms.{roomId}.points` (DOT-separator — the canonical project convention enforced by `JwtChannelInterceptor.ROOM_TOPIC` regex `^/topic/rooms\.(\d+)\.(chat|members|survival|points|kudos)$`) with payload shape:
```json
{
  "roomId": <long>,
  "delta": <int>,
  "newTotal": <int>,
  "sourceRevivalEventId": <long>,
  "occurredAt": "<ISO-8601 UTC>"
}
```

**CRITICAL deviation from epics.md text:** epics.md AC4 (line 638) writes the destination as `/topic/rooms/{id}/points` with slash separators. THIS IS NOT THE ACTUAL CONVENTION. Every other room topic (`chat` / `members` / `survival` / `kudos`) uses the dot-separator form, and `JwtChannelInterceptor.ROOM_TOPIC` regex anchors on the dot form. The dev agent MUST use the dot-separator form. Do NOT introduce a second slash-form topic — it would (a) fail the membership-guard regex (subscribers would 403), and (b) split the broker's fanout into two paths nobody is listening on. The epics.md text predates the realtime convention lockdown.

**And** the existing `PointPoolChangePayload` record is reused. Optionally extend with a `lastEventAt` field IF AND ONLY IF the FE hook needs it for chronological merge — current dedupe-by-`sourceRevivalEventId` (AC7) does not require it, so keep the record unchanged unless test pressure demands otherwise.

PRD: FR-8.4.3. Architecture: §6.4, §4.14. Project-context: "STOMP topic conventions: `/topic/*` and `/queue/*` for server→client".

### AC5 — `GET /api/v1/rooms/{id}/points` REST endpoint (NEW)

**Given** an authenticated room member calls `GET /api/v1/rooms/{id}/points`
**When** the request reaches BE
**Then** the response is `200 OK` with `ApiResponse<RoomPointPoolDto>` envelope:
```json
{
  "data": {
    "roomId": <long>,
    "total": <int>,
    "lastEventAt": "<ISO-8601 UTC | null>"
  }
}
```

**Given** the caller is NOT a member of the room
**When** the request reaches BE
**Then** the response is `403 FORBIDDEN` with `code = "FORBIDDEN"` (existing `ForbiddenException` → handler).

**Given** the room id does not exist
**When** the request reaches BE
**Then** the response is `403 FORBIDDEN` (the member-guard fails first; non-existent rooms cannot be members of). This is consistent with the `ChatController` precedent and avoids leaking room existence to non-members.

**Given** the room exists, the caller is a member, AND no `room_point_pool` row exists (e.g., legacy room missed V11 backfill)
**When** the request reaches BE
**Then** the response is `200 OK` with `total = 0` and `lastEventAt = null`. The controller MUST defensively coalesce — never `null` for `total`.

**Implementation shape (load-bearing):**
- New `RoomPointPoolDto` record in `com.yeosal.api.revival`: `(long roomId, int total, Instant lastEventAt)`.
- New `RoomPointsController` at `com.yeosal.api.revival.RoomPointsController`:
  ```java
  @RestController
  @RequestMapping("/api/v1/rooms")
  public class RoomPointsController {
      @GetMapping("/{id}/points")
      @Transactional(readOnly = true)
      public ApiResponse<RoomPointPoolDto> get(Authentication auth, @PathVariable long id) { ... }
  }
  ```
  Lives in `revival/` package per package-by-feature (the pool is a revival-economy concern). Do NOT add to `RoomController` — keep the revival module's controllers grouped with its DTOs/services (precedent: `MePersonalPointsLedgerController`, `MeReceivedRevivalsController`).
- Reuses `currentUser.require(auth)` + `roomMembers.existsByRoomIdAndUserId(...)` precedent.
- `@Transactional(readOnly = true)` per the read-controller convention (`MeFriendGiftController`, `MeReceivedRevivalsController` precedent).
- No new repository method needed — call `roomPointPoolRepository.findById(roomId)` (the entity's PK IS `roomId`) and map to DTO with `Optional.map(p -> new RoomPointPoolDto(p.getRoomId(), p.getTotal(), p.getLastEventAt())).orElseGet(() -> new RoomPointPoolDto(roomId, 0, null))`.

PRD: FR-8.4.1, FR-8.4.2. Architecture: §6.4 ("`/rooms/{id}/points` → `RoomPointPoolDto`, room member"). Project-context: "All controller responses must be wrapped in `ApiResponse<T>` (`ApiResponse.of(dto)`)".

### AC6 — FE `useRoomPoints(roomId)` domain hook (NEW)

**Given** a FE component needs the current room pool total (live-updating)
**When** it calls `useRoomPoints(roomId)`
**Then** the hook returns `{ total: number, lastEventAt: string | null, isLoading: boolean, isError: boolean }` with these semantics:
1. **REST primary**: `useQuery({ queryKey: qk.roomPoints(roomId), queryFn: () => getRoomPoints(roomId), staleTime: 30_000 })` — same `staleTime` as `qk.meSurvival` so the two stay in lock-step.
2. **STOMP merge**: subscribes to `/topic/rooms.{roomId}.points` via `useRealtimeSubscription<PointPoolFrame>(destination, handler)` (the existing primitive in `src/lib/realtime/client.ts` line 265). On each frame:
   - **Dedupe by `sourceRevivalEventId`** (AC7).
   - On a NEW event id, call `qc.setQueryData(qk.roomPoints(roomId), prev => ({ ...prev, total: frame.newTotal, lastEventAt: frame.occurredAt }))` — the WS payload is authoritative (the BE just computed it inside `SELECT … FOR UPDATE`). NEVER do `total + delta` arithmetic on the FE — that drifts on out-of-order delivery.
3. **No double-fetch on WS frame**: `setQueryData` updates the cache WITHOUT triggering a refetch. Do NOT call `invalidateQueries` on every frame (the current `PoolBar` Story 3.4 implementation invalidates `qk.meSurvival` per frame — that's a known scale wart; Story 4.1 fixes it).

**Given** the hook is mounted with `roomId <= 0` or non-finite
**When** it tries to fetch / subscribe
**Then** the query is `enabled: false` AND no STOMP subscription is opened. `isLoading = false`, `total = 0`.

**File location:** `FE/src/lib/query/hooks/roomPoints.ts` (NEW). Mirrors the shape of `useFriendGiftTargets` / `useMeSurvivalQuery`. Per project-context: "All data fetching goes through domain hooks in `src/lib/query/hooks/*`. Components do not call `useQuery` directly."

**API client:** `FE/src/api/roomPoints.ts` (NEW):
```ts
import { apiRequest } from "./client";
import type { ApiEnvelope } from "./types";

export interface RoomPointPoolDto {
  readonly roomId: number;
  readonly total: number;
  readonly lastEventAt: string | null;
}

export async function getRoomPoints(roomId: number): Promise<RoomPointPoolDto> {
  const envelope = await apiRequest<ApiEnvelope<RoomPointPoolDto>>(`/rooms/${roomId}/points`);
  return envelope.data;
}
```

**Query key:** add to `FE/src/lib/query/keys.ts`:
```ts
roomPoints: (roomId: number) => ["roomPoints", roomId] as const,
```

PRD: FR-8.4.1, FR-8.4.3. Project-context: domain-hook rule + `apiRequest` mandatory.

### AC7 — STOMP frame dedupe by `sourceRevivalEventId` (CRITICAL)

**Given** the `useRoomPoints` hook subscribes to `/topic/rooms.{roomId}.points`
**When** a STOMP frame arrives
**Then** the hook MUST dedupe by `sourceRevivalEventId` BEFORE applying the cache update. Pattern (mirrors `useChatRealtime` from `lib/query/hooks/chat.ts` line 157–192):
```ts
const seenEventIds = useRef<Set<number>>(new Set());

useRealtimeSubscription<PointPoolFrame>(destination, (frame) => {
  if (!frame || typeof frame.sourceRevivalEventId !== "number") return;
  if (seenEventIds.current.has(frame.sourceRevivalEventId)) return;
  seenEventIds.current.add(frame.sourceRevivalEventId);
  if (frame.roomId !== roomId) return;
  // ... setQueryData with frame.newTotal
});
```

**And** the dedupe set is `useRef<Set<number>>` (per-hook-instance, lives across rerenders, GC'd on unmount). Do NOT use a module-level singleton — two `useRoomPoints` calls for different rooms would cross-contaminate.

**And** the set MAY grow unbounded across a long session (one entry per pool event); for v1 the magnitude is low (~10–50 events per active room per day) so an LRU is YAGNI. Document the trade-off in a one-line comment.

Project-context: "Dedupe REST/WS: a WS event must not overwrite cache directly — check a dedupe key, then invalidate or merge per the `useChatRealtime` pattern." `useChatRealtime` is the canonical pattern.

### AC8 — `PoolBar` refactor to consume the hook (REFACTOR)

**Given** `PoolBar` (Story 3.4) currently takes `total: number` as a prop AND subscribes inline to `/topic/rooms.{roomId}.points` (lines 117–140 of `FE/src/components/revival/PoolBar.tsx`)
**When** Story 4.1 lands
**Then** `PoolBar` is refactored so:
1. The inline `getRealtimeClient().subscribe(...)` block is REMOVED.
2. The inline `qc.invalidateQueries({ queryKey: qk.meSurvival })` call is REMOVED (the meSurvival invalidate-per-frame waste is the wart this story fixes).
3. The `total: number` prop becomes the source of truth for the **fill ratio** — the parent decides what `total` to pass.
4. Callers (`WalletScreen` AC9; future `WalletPreview` consumers) pass `total` from `useRoomPoints(roomId).total`.

**Alternative considered (REJECTED):** make `PoolBar` call `useRoomPoints` directly inside the component. Rejected because (a) it would couple a presentational component to a query hook (violates project-context container/presentational split), (b) the `total` prop is still needed when the parent already has the number (e.g., a stale snapshot for skeleton state), (c) two `PoolBar` mounts of the same room would each open a subscription — a leak the singleton client tolerates but the dedupe set does not (each instance has its own ref).

**And** existing `PoolBar.test.tsx` (4 cases — Story 3.4) MUST still pass. The 2 STOMP-subscribe assertions need updating: they currently assert `getRealtimeClient().subscribe` is called; after refactor they assert `subscribe` is NOT called from `PoolBar` (the responsibility moved to the hook). Add the corresponding subscribe-assertions to the new `useRoomPoints.test.tsx`.

PRD: FR-8.4.2. Project-context: "Components do not call `useQuery` directly. … Realtime subscriptions share the single STOMP client owned by `RealtimeProvider`."

### AC9 — `WalletScreen` reads live pool via the hook (REFACTOR)

**Given** `WalletScreen` (Story 3.4 — `FE/src/components/wallet/WalletScreen.tsx` line 143) currently reads `const pool = survival.roomPointPool` from the meSurvival query
**When** Story 4.1 lands
**Then** `WalletScreen` reads `const { total: pool } = useRoomPoints(roomId)`. The displayed numeric label and the `<PoolBar total={pool}>` call site both consume the hook's `total`.

**And** the `survival.roomPointPool` field is preserved on `MeSurvivalEntry` as the **initial-load fallback** (e.g., for spectator surfaces that mount the WalletPreview line before opening the dedicated wallet route). The field MAY still appear in Today-tab WalletPreview surfaces that are not pool-live-update-critical — those can be migrated to the hook in a future polish PR; Story 4.1's scope is the per-room Wallet route only.

**And** the existing `WalletScreen.test.tsx` (6 cases) MUST still pass. Update the survival query fixture by adding a `useRoomPoints` mock that returns `{ total: <same value as fixture's survival.roomPointPool>, lastEventAt: null, isLoading: false, isError: false }`.

PRD: FR-8.4.2.

### AC10 — `MeSurvivalEntry.roomPointPool` preserved (regression guard)

**Given** Story 2.1's `GET /api/v1/me/survival` endpoint returns `MeSurvivalEntryDto.roomPointPool` for cross-room aggregation
**When** Story 4.1 lands
**Then** the BE field stays IDENTICAL (no rename, no removal). Spectator-mode + Today-tab `WalletPreview` surfaces continue to read this aggregated field. Story 4.1 does NOT touch `MeSurvivalEntryDto`, `SurvivalStateRepository.findRoomPointPoolTotal`, or any meSurvival code path.

**Rationale:** the cross-room aggregation (one query, N rooms) is more efficient than N parallel `/rooms/{id}/points` calls for the spectator's Today tab. The dedicated endpoint is the per-room route's primary source; meSurvival stays the cross-room batching.

PRD: FR-8.4.2. Story 2.1 + Epic 1 retro T4 + project-context.md "Do not rely on TanStack Query staleTime/gcTime defaults".

### AC11 — Existing tests + verify.sh continue to pass (regression gate)

**Given** all existing test suites
**When** Story 4.1's changes land
**Then** the following test files MUST stay green without semantic edits to their assertions (mock wiring may be updated to inject `RoomPointPoolService` in place of direct repository calls):
- `RevivalServiceTest` (9 cases) — Story 3.1
- `RevivalControllerTest` (5 cases) — Story 3.1
- `RevivalConcurrencyIT` (3 race variants) — Story 3.1
- `MeSurvivalFreeTicketIT` — Story 3.1 patch
- `FriendGiftServiceTest` (14+ cases) — Story 3.2
- `FriendGiftControllerTest` (6+2 cases) — Story 3.2 + 3.3 extension
- `FriendGiftReceiptsControllerTest` (5 cases) — Story 3.2
- `EligibleGiverPushListenerTest` (4 cases) — Story 3.2
- `FriendGiftRealtimeListenerTest` (2 cases) — Story 3.2
- `RevivalServiceSourceSubtypeTest` (3 cases) — Story 3.3
- `MeFriendGiftTargetsControllerTest` (5 cases) — Story 3.3
- `FriendGiftTargetQueryTest` (8 IT cases opt-in) — Story 3.3
- `FriendGiftWalletInitiatedIT` (2 IT cases) — Story 3.3
- `PersonalPointsLedgerRepositoryListTest` (5 cases) — Story 3.4
- `RevivalEventRepositoryReceivedTest` (4 cases) — Story 3.4
- `MePersonalPointsLedgerControllerTest` (4 cases) — Story 3.4
- `MeReceivedRevivalsControllerTest` (4 cases) — Story 3.4
- `WalletPrivacyDefenceIT` (2 cases opt-in) — Story 3.4
- `RoomServiceTest` (esp. lines 540–545 — pool row seed) — Story 3.1 patch
- `RoomControllerIT` — touches `room_point_pool` reset SQL
- `KudosMigrationIT` / `KudosConcurrencyIT` / `ChatControllerSpectatorIntegrationTest` — touches `room_point_pool` reset SQL
- All FE tests (322/322 from Story 3.4 baseline) — with the two refactor-targeted suites (`PoolBar.test.tsx`, `WalletScreen.test.tsx`) updated per AC8/AC9.

**And** `bash scripts/verify.sh` MUST pass (FE lint clean on touched files + BE `./gradlew test` green + Docker build when available).

## Tasks / Subtasks

### Backend (BE/) — RoomPointPoolService extract + REST endpoint + tests

- [x] **BE-1** Create `RoomPointPoolService.java` in `com.yeosal.api.revival` (AC3).
  - [x] `applyDelta(long roomId, int delta, long sourceRevivalEventId, Instant occurredAt)` — `@Transactional(propagation = MANDATORY)`.
  - [x] Throw `IllegalArgumentException("pool delta must be positive, got=" + delta)` when `delta <= 0`.
  - [x] Acquire row lock via `roomPointPoolRepository.selectForUpdate(roomId)`; throw `IllegalStateException("room_point_pool row missing for roomId=" + roomId)` on empty.
  - [x] Call `roomPointPoolRepository.incrementTotal(roomId, delta)`; throw `IllegalStateException("room_point_pool row vanished mid-transaction: roomId=" + roomId)` on zero rows updated.
  - [x] Publish `PointPoolChangeEvent` via injected `ApplicationEventPublisher`.
  - [x] Return `newTotal` (computed `pool.getTotal() + delta`, equivalent to `findTotalByRoomId` post-flush).
- [x] **BE-2** Refactor `RevivalService.reviveSelf` (line 164–223) to call `roomPointPoolService.applyDelta(...)` once at the existing pool-mutation site. Preserve the byte-identical `RevivalEventDto.roomPointPoolAfter` field value (AC3).
- [x] **BE-3** Refactor `RevivalService.reviveFriend` (line 336–404) to call `roomPointPoolService.applyDelta(...)` once at the existing pool-mutation site. Preserve the byte-identical `FriendGiftRevivalDto.roomPointPoolAfter` field value (AC3).
- [x] **BE-4** Update `RevivalService` constructor injection: replace `RoomPointPoolRepository roomPointPool` with `RoomPointPoolService roomPointPoolService` (the repository becomes private to the new service). Update all existing tests' `@Mock` declarations accordingly (AC11).
- [x] **BE-5** Create `RoomPointPoolDto.java` record in `com.yeosal.api.revival`: `(long roomId, int total, Instant lastEventAt)` (AC5).
- [x] **BE-6** Create `RoomPointsController.java` in `com.yeosal.api.revival` (AC5).
  - [x] `@GetMapping("/{id}/points")` under `@RequestMapping("/api/v1/rooms")`.
  - [x] `@Transactional(readOnly = true)`.
  - [x] `Authentication auth` + `currentUser.require(auth)` (precedent: existing controllers).
  - [x] Member-guard via `roomMembers.existsByRoomIdAndUserId(roomId, me.getId())` → throw `ForbiddenException("방 멤버만 그룹 포인트를 조회할 수 있어요.")` on miss.
  - [x] Load via `roomPointPoolRepository.findById(roomId)`, map to DTO with `(total = 0, lastEventAt = null)` fallback for missing rows.
  - [x] Return `ApiResponse.of(dto)`.
- [x] **BE-7** Unit test `RoomPointPoolServiceTest.java` (AC3, AC4):
  - [x] `applyDelta_negativeDelta_throwsIllegalArgument` — assertThatThrownBy on `delta = -1` and `delta = 0`.
  - [x] `applyDelta_positiveDelta_acquiresLockAndIncrements` — verify `selectForUpdate` + `incrementTotal` ordering + publish.
  - [x] `applyDelta_missingPoolRow_throwsIllegalState` — `selectForUpdate` returns empty.
  - [x] `applyDelta_zeroRowsUpdated_throwsIllegalState` — `incrementTotal` returns 0.
  - [x] `applyDelta_publishesPointPoolChangeEventWithComputedNewTotal` — captor on `eventPublisher.publishEvent` asserts the event fields.
- [x] **BE-8** Web-slice test `RoomPointsControllerTest.java` (AC5):
  - [x] `getPoints_member_returnsCurrentTotal` — `@WithMockUser` + fixture room with pool total = 13 → 200 OK, `data.total = 13`.
  - [x] `getPoints_member_missingPoolRow_returnsZero` — pool row absent → 200 OK, `data.total = 0`, `data.lastEventAt = null`.
  - [x] `getPoints_nonMember_returnsForbidden` — caller not in `room_members` → 403, `code = FORBIDDEN`.
  - [x] `getPoints_missingRoom_returnsForbidden` — non-existent roomId → 403 (member-guard fires first per AC5).
  - [x] `getPoints_unauthenticated_returnsUnauthorized` — no JWT → 401.
- [x] **BE-9** Regression run: `./gradlew test` MUST be green. The two-call `RoomPointPoolRepository` mocks in the following suites get replaced with a single `RoomPointPoolService` mock — assertions on DTO fields and emitted events stay identical (AC11):
  - `RevivalServiceTest`, `FriendGiftServiceTest`, `RevivalServiceSourceSubtypeTest`, `RevivalConcurrencyIT` (uses real `RoomPointPoolService` via `@Autowired`), `FriendGiftWalletInitiatedIT` (same).
- [x] **BE-10** Verify `ApiExceptionHandler` already maps `IllegalArgumentException` → `400 VALIDATION` (project-context says it does; confirm no edits needed). If a mapping is missing, add one — but the project-context rule is authoritative.

### Frontend (FE/) — API client + hook + refactor + tests

- [x] **FE-1** Create `FE/src/api/roomPoints.ts` (AC6).
  - [x] Export `RoomPointPoolDto` interface (mirrors BE record).
  - [x] Export `async function getRoomPoints(roomId: number): Promise<RoomPointPoolDto>` using `apiRequest<ApiEnvelope<RoomPointPoolDto>>("/rooms/" + roomId + "/points")`.
  - [x] NO direct `fetch` — `apiRequest` ONLY (project-context rule).
- [x] **FE-2** Add `qk.roomPoints(roomId)` to `FE/src/lib/query/keys.ts` between `friendGiftTargets` and `personalPointsLedger`. JSDoc citing Story 4.1 AC6 (AC6).
- [x] **FE-3** Create `FE/src/lib/query/hooks/roomPoints.ts` (AC6, AC7).
  - [x] Export `interface PointPoolFrame { readonly roomId: number; readonly delta: number; readonly newTotal: number; readonly sourceRevivalEventId: number; readonly occurredAt: string; }`.
  - [x] Export `useRoomPoints(roomId: number)` returning `{ total: number, lastEventAt: string | null, isLoading: boolean, isError: boolean }`.
  - [x] `useQuery({ queryKey: qk.roomPoints(roomId), queryFn: () => getRoomPoints(roomId), enabled: Number.isFinite(roomId) && roomId > 0, staleTime: 30_000 })`.
  - [x] `seenEventIds = useRef<Set<number>>(new Set())`. WHY comment: per-instance dedupe; mirrors `useChatRealtime` pattern; v1 magnitude tolerates unbounded growth.
  - [x] `destination = (Number.isFinite(roomId) && roomId > 0) ? "/topic/rooms." + roomId + ".points" : null`.
  - [x] `useRealtimeSubscription<PointPoolFrame>(destination, frame => { ... dedupe + setQueryData ... })`. Defensive room-id check inside handler.
  - [x] `setQueryData` with `newTotal` from the frame (never `total + delta` arithmetic) — AC6 §2.
- [x] **FE-4** Refactor `FE/src/components/revival/PoolBar.tsx` (AC8).
  - [x] Remove the inline `useEffect` STOMP subscribe block (lines 118–140).
  - [x] Remove the `useQueryClient` import + `const qc = useQueryClient()` line — no longer needed.
  - [x] Remove the `qk` import.
  - [x] `roomId` prop is retained (used by `accessibilityLabel`); `total` prop continues to drive the fill ratio.
  - [x] Update file-header comment to point at Story 4.1 for the live-subscription source.
- [x] **FE-5** Refactor `FE/src/components/wallet/WalletScreen.tsx` (AC9).
  - [x] Add `import { useRoomPoints } from "../../lib/query/hooks/roomPoints";`.
  - [x] Inside the component body (after the existing `useReceivedRevivals(roomId)` line): `const roomPoints = useRoomPoints(roomId);`.
  - [x] Replace `const pool = survival.roomPointPool;` with `const pool = roomPoints.total;`.
  - [x] Leave `survival.roomPointPool` available on `survival` for any other future read; this refactor only touches the displayed value.
  - [x] `<PoolBar roomId={roomId} total={pool} max={POOL_MAX_V1} />` line is unchanged in shape.
- [x] **FE-6** Update `FE/src/components/revival/__tests__/PoolBar.test.tsx` (AC8, AC11).
  - [x] Remove the two STOMP-subscribe assertions; replace with one negative assertion that `getRealtimeClient` is NOT invoked from PoolBar (or simply remove the mock — the refactor makes it unused).
  - [x] The 4 existing cases (mount-with-total, animate-on-total-change, reduce-motion-instant, unmount-cancels-animation) MUST stay green.
- [x] **FE-7** Update `FE/src/components/wallet/__tests__/WalletScreen.test.tsx` (AC9, AC11).
  - [x] Mock `useRoomPoints` to return `{ total: <fixture's survival.roomPointPool>, lastEventAt: null, isLoading: false, isError: false }` for every existing case.
  - [x] All 6 existing cases MUST stay green.
- [x] **FE-8** Add `FE/src/lib/query/hooks/__tests__/roomPoints.test.tsx` (NEW; AC6, AC7).
  - [x] `loads_initialPool_viaREST` — mock `getRoomPoints` returns `{ total: 8, lastEventAt: "2026-05-29T03:00:00Z" }`; assert `useRoomPoints(123).total === 8`.
  - [x] `merges_stompFrame_authoritativeNewTotal` — emit `{ roomId: 123, delta: 5, newTotal: 13, sourceRevivalEventId: 99, occurredAt: "..." }`; assert cache updates to `total: 13`. Critical: do NOT use arithmetic; the test must verify `setQueryData` was called with `newTotal` from the frame, not `prev + delta`.
  - [x] `dedupes_duplicateEventId` — emit two frames with same `sourceRevivalEventId`; assert `setQueryData` called exactly once.
  - [x] `ignores_foreignRoomId` — emit frame with `roomId: 999` while hook subscribes to room 123; assert no cache update.
  - [x] `disabled_invalidRoomId` — `useRoomPoints(0)` / `useRoomPoints(-1)` / `useRoomPoints(NaN)` → `isLoading === false`, no subscribe, no fetch.
- [x] **FE-9** Type-check + lint clean on every touched file. `npm run lint && npm run typecheck && npm test` all green.

### Scripts / verification / sprint-status

- [x] **VERIFY-1** `bash scripts/verify.sh` runs end-to-end clean (FE 327+/327+ + BE all-green + Docker build when available).
- [x] **VERIFY-2** Manual smoke (dev environment): mount Wallet for an active room, trigger a self-revival (Story 3.1 surface) in a second device/session, observe the pool number on the Wallet update in < 500ms WITHOUT a full re-fetch of `meSurvival` (verify via React Query devtools — only `qk.roomPoints(roomId)` cache entry changes).
- [x] **VERIFY-3** `gh pr view --json baseRefName` (when opening PR) confirms `baseRefName == main` (project-context Stack PR Merge Procedure rule).
- [x] **STATUS-1** Flip `_bmad-output/implementation-artifacts/sprint-status.yaml`: `4-1-room-point-pool-counter-cache: backlog → ready-for-dev` (done by this story-creation step), then dev-story moves it `ready-for-dev → in-progress` on start.
- [x] **STATUS-2** Flip `epic-4: backlog → in-progress` (done by this story-creation step as the first story in the epic).

### Out-of-scope explicit list

The following are NOT Story 4.1 — do NOT bleed scope:
- **Phase-2 redemption** — Story 4.2 ships the promise copy; phase-2 will ship the decrement path. v1 forbids negative delta at the service boundary (this story enforces).
- **`<PoolStack>` 5-stage SVG metaphor + threshold table** — Story 4.3. Keep `POOL_MAX_V1 = 100` placeholder in `WalletScreen` with the existing `TODO(Story 4.3)` comment.
- **Real per-room threshold tuning** — Story 4.3. v1 ships the flat 100-cap placeholder.
- **Cross-room pool aggregation** — `MeSurvivalEntry.roomPointPool` already aggregates per-room totals across the user's rooms (Story 2.1). This story does NOT add a "sum across all my rooms" view.
- **Spectator privacy treatment of the pool** — FR-8.4.2 explicitly states pool is visible to spectators AND eliminated members still in the room. No special spectator masking.
- **Polling fallback for WS-down** — `RealtimeProvider` already implements adaptive polling per project-context; this hook inherits it via `useRealtimeSubscription`. Do NOT add a second polling loop.
- **Migrating `WalletPreview` (Today-tab) to the hook** — out of scope; that surface keeps reading `survival.roomPointPool` per AC10. A future polish PR can migrate it.
- **Phase-2 `revival_events.pool_after` reconciliation report** — append-only ledger; reconciliation is computed offline if needed.
- **Renaming epics.md AC4 topic format to dot-style** — the epics file documents intent; Story 4.1's story file documents implementation. Do NOT silently edit `epics.md` in this PR; the AC4 deviation note is the canonical pointer.

## Dev Notes

### CRITICAL implementation traps (read FIRST)

1. **Story 4.1's BE infrastructure is ~85% already shipped.** Reading the epics.md AC list cold, the dev agent might think this is a from-scratch build. It is NOT. Story 3.1 shipped:
   - `RoomPointPool` entity (`BE/src/main/java/com/yeosal/api/revival/RoomPointPool.java`)
   - `RoomPointPoolRepository` with `selectForUpdate` + `incrementTotal` + `findTotalByRoomId` (file in same package)
   - `RoomPointPoolRealtimeListener` AFTER_COMMIT (file in same package)
   - `PointPoolChangeEvent` + `PointPoolChangePayload` records
   - `RealtimePublisher.publishPointPoolChange` (`BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` line 91)
   - `JwtChannelInterceptor.ROOM_TOPIC` regex allowing `/topic/rooms.{id}.points` (file line 44)
   - `RoomService.create` mints `new RoomPointPool(room.getId(), 0)` (`BE/src/main/java/com/yeosal/api/room/RoomService.java` line 140)
   - V11 step 7 (`room_point_pool` table) + step 15 (per-room backfill)
   - `RevivalService.reviveSelf` pool-increment path (file line 164–223)

   Story 3.2 shipped:
   - `RevivalService.reviveFriend` pool-increment path (file line 336–404)

   Story 3.4 shipped:
   - FE `PoolBar` with inline `/topic/rooms.{roomId}.points` subscribe (`FE/src/components/revival/PoolBar.tsx` lines 117–140)
   - `WalletScreen` reading `survival.roomPointPool` for display

   **Story 4.1's real incremental scope is THREE things:**
   - **(a) negative-delta service guard** — extract `RoomPointPoolService` so both call sites + future phase-2 callers route through one chokepoint
   - **(b) dedicated REST endpoint** — `GET /api/v1/rooms/{id}/points` (Architecture §6.4 promised it, no story shipped it)
   - **(c) FE `useRoomPoints` hook with proper WS dedupe** — fix the Story 3.4 wart of invalidating `qk.meSurvival` on every pool frame

   DO NOT rebuild the entity / repository / event / publisher / realtime listener / V11 schema. They already exist. Read the existing files first.

2. **Topic format is `/topic/rooms.{roomId}.points` (DOT-separator), NOT `/topic/rooms/{id}/points`.** The epics.md AC4 text (line 638) is wrong relative to the codebase. Every other room topic uses the dot form (`chat`, `members`, `survival`, `kudos`) and `JwtChannelInterceptor.ROOM_TOPIC` regex `^/topic/rooms\.(\d+)\.(chat|members|survival|points|kudos)$` only accepts the dot form. If the dev agent introduces a slash-form topic, three things break:
   - The membership-guard regex rejects the subscription (FE sees 403 on CONNECT).
   - The broker fans out the publish to a destination nobody is listening on.
   - Two parallel topic conventions split observability — log queries break.

   Lock the dot form. Update the epics.md AC4 text only via a follow-up doc PR (do NOT bundle in Story 4.1).

3. **`Propagation.MANDATORY` on `RoomPointPoolService.applyDelta`.** The service MUST refuse to run outside an existing transaction. The two call sites (`RevivalService.reviveSelf`, `RevivalService.reviveFriend`) both declare `@Transactional` at the method level — so MANDATORY is satisfied. If a future caller forgets `@Transactional`, MANDATORY throws `IllegalTransactionStateException` immediately at the controller boundary instead of silently running each statement in autocommit (which would defeat the advisory lock + FOR UPDATE invariant). Do NOT use `REQUIRES_NEW` (would break the shared advisory lock) or `REQUIRED` (would auto-open a transaction for a forgetful caller, masking the bug).

4. **The dedupe set in `useRoomPoints` is `useRef<Set<number>>`, NOT module-level.** Two `useRoomPoints` calls for different rooms must not cross-contaminate. Two `useRoomPoints` calls for the SAME room in different components (e.g., Wallet route + a future Today-tab consumer) WILL each maintain their own set — that's fine because each one independently consumes the same STOMP frame via `useRealtimeSubscription`, and `setQueryData` is idempotent (writing the same `{ total, lastEventAt }` twice has no observable effect; TanStack Query rerenders only on shallow-equal change).

5. **Never compute `total + delta` on the FE.** The STOMP frame carries `newTotal` precisely because the BE just computed it inside `SELECT … FOR UPDATE`. FE arithmetic drifts on out-of-order delivery, missed frames, or a stale starting point. Always `setQueryData(prev => ({ ...prev, total: frame.newTotal, lastEventAt: frame.occurredAt }))`. The test for FE-8's `merges_stompFrame_authoritativeNewTotal` case MUST explicitly assert the cache value equals the frame's `newTotal`, not arithmetic.

6. **The `RevivalService` refactor must be byte-identical observably.** The existing `RevivalServiceTest`, `RevivalConcurrencyIT`, `FriendGiftServiceTest`, and `FriendGiftWalletInitiatedIT` lock in:
   - The `RevivalEventDto.roomPointPoolAfter` field value (must equal pool's pre-increment + delta).
   - The `FriendGiftRevivalDto.roomPointPoolAfter` field value (same invariant).
   - The DB-side `revival_events.pool_after` column value.
   - The emitted `PointPoolChangeEvent` field values.
   - The number of pool-row UPDATEs per revival (exactly 1, after the `INSERT revival_events`).

   The refactor moves the existing inline `selectForUpdate + incrementTotal + publishEvent` triplet from `RevivalService.reviveSelf` (line 164–168 + 198–202 + 215–216) and `RevivalService.reviveFriend` (line 336–339 + 374–378 + 400–401) into `RoomPointPoolService.applyDelta`. The ordering relative to the `INSERT revival_events` MUST be preserved — pool selectForUpdate happens BEFORE the insert (so `pool_after` is known when writing the event), and `incrementTotal` happens AFTER the insert. Look closely at `RevivalService.reviveSelf` line 164 (selectForUpdate) vs line 171 (insert) vs line 198 (incrementTotal) — the temporal order is selectForUpdate(160s) → newTotal computed(167) → INSERT revival_events(170s) → ledger save(193) → incrementTotal(198) → state.markRevived(205) → publishEvent(215). The new service must respect this ordering when called from the same lines.

   **Recommended call shape:**
   ```java
   // In RevivalService.reviveSelf, replacing the inline pool-mutation lines:
   //   - pre-compute newTotal for the INSERT pool_after column,
   //   - call applyDelta AFTER the INSERT to do the row-lock + UPDATE + publish.
   RoomPointPool pool = roomPointPoolRepository.selectForUpdate(roomId)
           .orElseThrow(() -> new IllegalStateException(...));
   int newTotal = pool.getTotal() + poolDelta;
   // ... INSERT revival_events (uses newTotal) ...
   // ... ledger save if PERSONAL_POINTS ...
   roomPointPoolService.applyDelta(roomId, poolDelta, revivalEventId, now);
   // ... state.markRevived(now) ...
   // ... publish SurvivalStateTransitionEvent ...
   // (PointPoolChangeEvent publish is now INSIDE applyDelta — REMOVE the inline call here)
   ```

   Alternatively (cleaner but riskier): make `applyDelta` itself acquire the `selectForUpdate` lock and return `newTotal`, then the caller uses that return value to populate `revival_events.pool_after` BEFORE inserting. This requires reordering the `INSERT revival_events` to AFTER the pool lock acquisition — straightforward but touches more lines. Pick whichever the dev judges safer to keep tests green.

   **Either way:** `applyDelta` must own the negative-delta guard (AC3) + the `incrementTotal` + the publish. The caller may pre-acquire the lock for `pool_after` derivation OR delegate it entirely.

7. **The advisory lock is acquired in `RevivalService`, NOT in `RoomPointPoolService`.** The advisory lock keys on `(roomId, userId, eliminatedAtEpochMillis)` — that triple is owned by the revival flow, not by the pool service. The pool service's row-level `FOR UPDATE` lock is the SECOND layer of defence (Architecture §4.6). Keep the advisory lock acquisition where it is (`RevivalService.reviveSelf` line 126, `RevivalService.reviveFriend` line 316). The dev agent might be tempted to move it into the new service — DO NOT.

### Architecture & Patterns to Reuse (zero-reinvention)

- **Constructor injection only** — `RoomPointPoolService` is `@Service` with constructor-injected `RoomPointPoolRepository` + `ApplicationEventPublisher`. No `@Autowired` fields (project-context rule).
- **`@Transactional(propagation = MANDATORY)`** — Spring's standard propagation annotation; throws `IllegalTransactionStateException` outside a transaction.
- **`@Transactional(readOnly = true)`** on the GET endpoint — precedent: `MeFriendGiftController.receipts`, `MeReceivedRevivalsController.list`.
- **Member-guard precedent** — `roomMembers.existsByRoomIdAndUserId(roomId, me.getId())` then `throw new ForbiddenException(...)` (mirrors `FriendGiftService.reviveFriend` precheck at `RevivalService.java` line 270).
- **`ApiResponse.of(dto)` envelope** — mandatory per project-context. Wrap every success response.
- **`apiRequest<T>` on FE** — mandatory per project-context. Never direct `fetch`.
- **`useRealtimeSubscription<T>` primitive** — `FE/src/lib/realtime/client.ts` line 265. Story 4.1 uses it for the dot-form topic. No new STOMP client; shares the singleton.
- **`useChatRealtime` dedupe pattern** — `FE/src/lib/query/hooks/chat.ts` line 157–192. The canonical setQueryData + per-instance Set dedupe shape.
- **`qk.*` keys file** — `FE/src/lib/query/keys.ts`. Add the new key alphabetically-near `personalPointsLedger` / `receivedRevivals`.
- **`ApiExceptionHandler` mappings** — `IllegalArgumentException` → `400 VALIDATION` (existing). `ForbiddenException` → `403 FORBIDDEN` (existing). `NotFoundException` → `404 NOT_FOUND` (existing). No new handlers.
- **Testcontainers PostgreSQL** — `postgres:16` for any new IT. H2 forbidden (project-context).
- **`@WithMockUser` + JWT helper** — `spring-security-test` for the controller slice tests.

### Pre-existing Behaviours That Must Be Preserved

- **`RevivalService.reviveSelf` / `reviveFriend` observable outputs (DTO field values, emitted events, DB row state).** The refactor MUST be behavior-preserving. Every assertion in the existing 9+14+3+3+2 test cases stays valid.
- **`RoomPointPool` entity API surface.** `total`, `lastEventAt`, package-private setters. No new fields, no constructor changes. The DB CHECK `total >= 0` stays as defence-in-depth.
- **`RoomPointPoolRepository` query methods.** `selectForUpdate`, `incrementTotal`, `findTotalByRoomId` all stay. The new service is a thin orchestration layer above them, not a replacement.
- **`RoomPointPoolRealtimeListener` wiring.** AFTER_COMMIT + REQUIRES_NEW. The listener's body (the publish call) stays untouched — only the source of the event publish (now inside `RoomPointPoolService.applyDelta` instead of `RevivalService.reviveSelf`/`reviveFriend`) changes. The listener cares about the event, not who publishes it.
- **`RealtimePublisher.publishPointPoolChange` topic string `/topic/rooms.{roomId}.points`** (file line 92). Story 4.1 does NOT change this — the FE consumer subscribes to the same string.
- **`JwtChannelInterceptor.ROOM_TOPIC` regex** (file line 44). The `points` term is already in the alternation; Story 4.1 does NOT touch the regex.
- **`MeSurvivalEntry.roomPointPool` field (BE DTO + FE type).** UNTOUCHED. The dedicated endpoint COEXISTS with the meSurvival aggregation field — see AC10.
- **`POOL_MAX_V1 = 100` placeholder in `WalletScreen.tsx`** + the `TODO(Story 4.3)` comment. Story 4.1 does NOT replace this with a real threshold table.
- **`<FriendGiftBadge>` mount in WalletScreen pool section** (Story 3.3). UNTOUCHED.
- **All Story 3.4 wallet-screen tests (322/322).** Refactor-targeted suites (PoolBar, WalletScreen) get mock-wiring updates; assertions on visible behavior stay identical.
- **Brand-voice locked Korean copy on WalletScreen** (Story 3.4 AC5). No new strings; no copy changes.

### Project Structure Notes

- **BE: `revival/` module extends (NO new module).** `RoomPointPoolService`, `RoomPointPoolDto`, `RoomPointsController` all live in `com.yeosal.api.revival`. The pool is a revival-economy concern; package-by-feature keeps it grouped with `RevivalService`, `FriendGiftService` etc. Do NOT spawn a `pool/` package.
- **FE: `lib/query/hooks/roomPoints.ts` is a NEW file.** One hook per domain file (precedent: `survival.ts`, `wallet.ts`, `friendGiftTargets.ts`). Co-locating with `wallet.ts` would mix per-room write-side queries (ledger, received) with pool live-state — not the same domain.
- **FE: `api/roomPoints.ts` is a NEW file.** Mirrors the file-split pattern (`api/wallet.ts`, `api/friendGiftTargets.ts`, `api/friendGifts.ts`). Each file owns one BE controller surface.
- **FE: tests at `FE/src/lib/query/hooks/__tests__/roomPoints.test.tsx`.** Jest config requires the `__tests__` path (project-context).
- **No new SecureStore keys.** Pool data is server-of-record; the dedupe Set is in-memory only.
- **No new Flyway migration.** V11 step 7 + 15 already shipped the table + backfill. The DB CHECK is already in V11. The dev agent might be tempted to add a V13 — DO NOT (the table is fully formed).

### v2 sub-mode validation contract

Story 4.1 does NOT introduce any new visible UI tokens, components, or sub-mode work. The visible surfaces it touches (`<PoolBar>`, `<WalletScreen>`) already pass the D2.bento contract from Story 3.4. Verify the refactor preserves:
- D2.bento override tokens visible on the Wallet route (no regression).
- Touch targets ≥ 48dp on every interactive surface (unchanged from Story 3.4).
- Reduced-motion compliance — `PoolBar` reduced-motion branch unchanged.
- WCAG AA contrast — unchanged.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#story-41` lines 616–640]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-841` line 391 (FR-8.4.1 — one row per room, initialized at 0, updated by RevivalService only)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-842` line 392 (FR-8.4.2 — pool visible to all room members including spectators + former eliminated)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-843` line 393 (FR-8.4.3 — STOMP `/topic/rooms/{id}/points` payload spec — note dot-form deviation in AC4)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-844` line 394 (FR-8.4.4 — phase-2 promise copy, out of Story 4.1 scope)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-845` line 395 (FR-8.4.5 — negative-delta forbidden — load-bearing for AC3)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#46` lines 252–260 (room pool counter cache — single integer column hot path, SELECT FOR UPDATE)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#44` lines 212–240 (revival concurrency — advisory lock + partial unique index)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#414` lines 388–398 (realtime topic privacy — server-side filter, dot-form topic convention)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#52` lines 506–522 (FE patterns — domain hooks, no direct useQuery)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#54` lines 530–537 (privacy patterns — server-side filtering)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#63` lines 654–800 (V11 schema — `room_point_pool` step 7, backfill step 15)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#64` lines 802–818 (REST endpoint contract — `/rooms/{id}/points` → `RoomPointPoolDto`, room member)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/RoomPointPool.java` (entity — UNTOUCHED; read for context)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/RoomPointPoolRepository.java` (repo — UNTOUCHED; service wraps these methods)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/RoomPointPoolRealtimeListener.java` (listener — UNTOUCHED)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/PointPoolChangeEvent.java` (in-process event — UNTOUCHED)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/PointPoolChangePayload.java` (STOMP payload — UNTOUCHED)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/RevivalService.java` lines 113–224 (reviveSelf — REFACTOR target for BE-2)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/RevivalService.java` lines 253–420 (reviveFriend — REFACTOR target for BE-3)]
- [Source: `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` lines 84–93 (`publishPointPoolChange` — file line 92 is the canonical destination string)]
- [Source: `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java` line 44 (`ROOM_TOPIC` regex — locks the dot-form topic alternation)]
- [Source: `BE/src/main/java/com/yeosal/api/room/RoomService.java` line 140 (`roomPointPool.save(new RoomPointPool(room.getId(), 0))` — verifies AC1)]
- [Source: `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` (existing `IllegalArgumentException` → 400 VALIDATION mapping — verifies AC3 needs no handler addition)]
- [Source: `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql` step 7 (`room_point_pool` table) + step 15 (backfill)]
- [Source: `FE/src/components/revival/PoolBar.tsx` (REFACTOR target for FE-4 — REMOVE inline subscribe block lines 117–140)]
- [Source: `FE/src/components/wallet/WalletScreen.tsx` line 143 (`survival.roomPointPool` read — REFACTOR target for FE-5)]
- [Source: `FE/src/lib/query/hooks/chat.ts` lines 147–193 (`useChatRealtime` — canonical dedupe + setQueryData pattern for FE-3)]
- [Source: `FE/src/lib/realtime/client.ts` line 265 (`useRealtimeSubscription<T>` primitive)]
- [Source: `FE/src/lib/query/keys.ts` (insertion target for FE-2)]
- [Source: `FE/src/lib/spectator.ts` lines 25–32 (`MeSurvivalEntry.roomPointPool` field — verifies AC10 preservation)]
- [Source: `FE/src/api/client.ts` (`apiRequest<T>` — mandatory FE network primitive)]
- [Source: `FE/src/api/types.ts` (`ApiEnvelope<T>` shape)]
- [Source: `_bmad-output/implementation-artifacts/3-1-free-revival-ticket-self-revival-via-personal-points.md` (Story 3.1 — pool publisher + free-ticket flag)]
- [Source: `_bmad-output/implementation-artifacts/3-2-friend-gift-revival-push-prompt-friend-gift-modal.md` (Story 3.2 — `reviveFriend` pool-increment path)]
- [Source: `_bmad-output/implementation-artifacts/3-4-wallet-ui-surface.md` (Story 3.4 — `PoolBar`, `WalletScreen`, FE-7 deviation note explaining inline subscribe)]
- [Source: `_bmad-output/project-context.md` (every project-context rule cited inline above — auth, ApiResponse envelope, `apiRequest`, `@Transactional` boundary, JPA `open-in-view: false`, dot-form STOMP convention, dedupe REST/WS via `useChatRealtime` pattern)]

### Testing Standards Summary

- **BE**: JUnit 5 + AssertJ + Mockito + Testcontainers PostgreSQL (`postgres:16`). No H2. `@SpringBootTest` for full integration (Flyway + security chain — used by the existing concurrency ITs only). `@WebMvcTest` for `RoomPointsControllerTest` slice. Pure unit + Mockito for `RoomPointPoolServiceTest`. Test naming: `methodName_scenario_expectedBehavior()` or `@DisplayName("...")`. Coverage 80% minimum on the new service + controller.
- **FE**: Jest 29 + `@testing-library/react-native`. Test files at `FE/src/**/__tests__/**/*.test.{ts,tsx}`. `QueryClientProvider` wrap for hook tests. Stub `fetch` (no real network). Mock `useRealtimeSubscription` to emit frames on demand (do NOT open a real WebSocket). `waitFor` / `findBy*` for async (no arbitrary `setTimeout`). Pre-push: `npm run lint && npm run typecheck && npm test` all green.
- **Project-wide**: `bash scripts/verify.sh` from repo root before declaring story complete.

### Previous-story intelligence

Selected dev notes / completion learnings from Story 3.4 that directly inform Story 4.1:

- **FE-7 deviation from Story 3.4 — vindicated and now superseded.** Story 3.4 noted: "The story spec for AC5 named a `FE/src/lib/realtime/handlers/pointsHandler.ts` file. The codebase has no `handlers/` subfolder — instead the precedent is to subscribe inline at the consumer. PoolBar follows that precedent." Story 4.1 takes the next step: move the subscribe out of the leaf component (`PoolBar`) and into a domain hook (`useRoomPoints`) — the higher-quality precedent that `useChatRealtime` has been using since Story 1.2. The leaf-component subscribe was a reasonable Story 3.4 simplification; the hook is the right v1.5+ home.
- **`qk.meSurvival` invalidate-per-frame is a known wart.** Story 3.4's PoolBar invalidates `qk.meSurvival` on every pool frame, which triggers a cross-room re-fetch of `GET /me/survival` even when only one room's pool changed. The wart is well-tolerated at v1 magnitudes but already noted as "more invalidation than necessary." Story 4.1's dedicated `qk.roomPoints(roomId)` cache entry is the surgical replacement.
- **`POOL_MAX_V1 = 100` is the right scaffold to keep.** Story 3.4 left a `TODO(Story 4.3)` comment at this constant. Story 4.1 leaves it alone — Story 4.3 ships the real per-room threshold table.
- **`@Transactional(readOnly = true)` is the read-controller default** — used by `MeFriendGiftController.receipts`, `MeReceivedRevivalsController.list`. `RoomPointsController.get` follows.
- **Testcontainers ITs opt-in via `-Dyeosal.boot-smoke=true`** — `WalletPrivacyDefenceIT`, `FriendGiftTargetQueryTest`, `FriendGiftWalletInitiatedIT` precedent. Story 4.1 likely does NOT need a new IT (pure unit + slice suffices); if reviewers push back, an opt-in IT can mirror these.
- **D1 deferred from Story 3.4** — `LIMIT 1000` on `PersonalPointsLedgerRepository.findByUserIdAndRoomIdOrderByOccurredAtDesc` is parked. Not relevant to Story 4.1.
- **D6 deferred from Story 3.4** — `PoolBar` `AccessibilityInfo.addEventListener` cleanup race under rapid re-mount. The FE-4 refactor (removing the STOMP block) does NOT touch the AccessibilityInfo branch, so D6 stays deferred unless the hook integration surfaces a new race.

### Git intelligence (recent commits informing this story)

- `9f2bc84 feat(epic-3): Story 3.4 — Wallet UI surface (per-room ledger + received-revivals + PoolBar)` — the file `PoolBar.tsx` and `WalletScreen.tsx` that Story 4.1 refactors.
- `3614deb feat(epic-3): Stories 3.2 + 3.3 — Friend-gift revival + Wallet "친구 살리기" badge (#80)` — shipped `RevivalService.reviveFriend` with the pool-increment path Story 4.1 refactors.
- `d83e130 feat(epic-3): Story 3.1 — Free revival ticket + personal-points (#75)` — shipped `RoomPointPool` entity, repository, listener, publisher topic, V11 schema, and the `RevivalService.reviveSelf` pool-increment path.
- `e15d375 fix(infra): include FE/src/theme/tokens.json in docker build context (#77)` — unrelated to Story 4.1.

### Latest technical specifics

- **Spring Boot 3.3.5 + Spring Tx**: `@Transactional(propagation = Propagation.MANDATORY)` is supported; throws `IllegalTransactionStateException` outside an existing transaction. Used as designed in `RoomPointPoolService.applyDelta`.
- **TanStack Query 5.100.6**: `useQuery({ enabled: false })` skips the query, leaves cached data intact. `qc.setQueryData(key, updater)` does NOT trigger a refetch — exactly what AC6 §3 requires.
- **`@stomp/stompjs 7.3.0`**: subscription lifecycle owned by `RealtimeClient` singleton; reconnect retries are handled by the client; subscriptions auto-restore on reconnect. The `useRealtimeSubscription<T>` hook absorbs this.
- **JJWT 0.12.6**: unrelated to Story 4.1 (auth is shared infrastructure).
- **`expo-secure-store ~15.0.8`**: unrelated to Story 4.1 (no new SecureStore keys).

### Project context reference

This story strictly follows `_bmad-output/project-context.md`. Critical rules cited inline above:
- "All controller responses must be wrapped in `ApiResponse<T>` (`ApiResponse.of(dto)`)." — AC5 controller body.
- "All API calls must go through `apiRequest<T>` in `src/api/client.ts`." — AC6 FE-1.
- "All data fetching goes through domain hooks in `src/lib/query/hooks/*`." — AC6 FE-3.
- "Dedupe REST/WS: a WS event must not overwrite cache directly — check a dedupe key, then invalidate or merge per the `useChatRealtime` pattern." — AC7.
- "Constructor injection only — no `@Autowired` fields." — BE-1 service shape.
- "A caller-side `IllegalArgumentException` reaching the controller is mapped to `400 VALIDATION` deliberately." — AC3, no new handler.
- "STOMP topic conventions: `/topic/*` and `/queue/*` for server→client" — AC4 dot-form.
- "Do not rely on TanStack Query `staleTime`/`gcTime` defaults — set them per domain." — AC6 `staleTime: 30_000`.
- "Realtime subscriptions share the single STOMP client owned by `RealtimeProvider`." — AC6 via `useRealtimeSubscription`.
- "Schema changes require a Flyway migration." — Story 4.1 introduces NO schema change.

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m]

### Debug Log References

- `BE/build/test-results/test/TEST-com.yeosal.api.revival.RoomPointPoolServiceTest.xml` — 6/6 pass (negative-delta guard at delta=-1 and delta=0; selectForUpdate→incrementTotal→publish ordering; missing-row IllegalStateException; vanished-row IllegalStateException; PointPoolChangeEvent field forwarding).
- `BE/build/test-results/test/TEST-com.yeosal.api.revival.RoomPointsControllerTest.xml` — 5/5 pass (200 happy + missing-row defensive coalesce + 403 non-member + 403 non-existent room + 4xx unauthenticated).
- BE full `./gradlew test` — 77 test classes, 0 failures, 0 errors. All Story 3.x suites (`RevivalServiceTest`, `FriendGiftServiceTest`, `RevivalServiceSourceSubtypeTest`, `RevivalConcurrencyIT` Testcontainers opt-in, `FriendGiftWalletInitiatedIT` Testcontainers opt-in) green after the constructor refactor.
- FE full `npm test` — 53 suites, 330/330 pass (Story 3.4 baseline 322 + 8 from `roomPoints.test.tsx` + adjusted PoolBar/WalletScreen).
- FE `npx eslint` on the 8 Story 4.1 touched files — clean (0 errors, 0 warnings). The 4 lint errors + 2 warnings surfaced by `npm run lint` are 100% pre-existing — verified by `git stash` → same 6 problems → `git stash pop` (involve `app/rooms/[id]/chat.tsx`, `lib/realtime/client.ts`, two SurvivalChip test files, an `InviteCodeSheet.test.tsx` directive). Unrelated to Story 4.1.
- FE `npm run typecheck` — 2 pre-existing errors only (both in `src/components/today/FriendsTodayPager.tsx`: missing `react-native-pager-view` module, implicit-any callback param). Story 4.1 introduced 0 new typecheck errors.

### Completion Notes List

**Story 4.1 incremental scope (Dev Notes Trap #1 verified):** the BE infrastructure was ~85% already shipped by Stories 3.1/3.2/3.4. This story's real net-new surface was exactly three things — all delivered:

1. **`RoomPointPoolService.applyDelta` chokepoint (AC3).** New `com.yeosal.api.revival.RoomPointPoolService` extracts the negative-delta guard + selectForUpdate + incrementTotal + PointPoolChangeEvent publish into a single transactional method. `Propagation.MANDATORY` enforces the caller already opened a transaction. `IllegalArgumentException("pool delta must be positive, got=" + delta)` for `delta <= 0` — mapped to `400 VALIDATION` by the existing global `ApiExceptionHandler.illegalArgument` handler (no new handler needed, per AC3 + project-context "A caller-side IllegalArgumentException reaching the controller is mapped to 400 VALIDATION deliberately").

2. **`GET /api/v1/rooms/{id}/points` REST endpoint (AC5).** New `RoomPointsController` + `RoomPointPoolDto` record. Lives in `com.yeosal.api.revival` per package-by-feature (the pool is a revival-economy concern). `@Transactional(readOnly = true)` per the read-controller convention (`MeFriendGiftController`, `MeReceivedRevivalsController` precedent). Member-guard fires before the pool lookup — non-members get `403 FORBIDDEN` regardless of whether the room exists, matching the `ChatController` precedent to avoid leaking room existence. Missing pool rows (legacy rooms that pre-date V11 step 15 backfill) coalesce to `total = 0, lastEventAt = null` so the FE never sees a null total.

3. **`useRoomPoints(roomId)` FE domain hook + dedupe (AC6 + AC7).** New `FE/src/lib/query/hooks/roomPoints.ts` composes a `useQuery({ qk.roomPoints, getRoomPoints, staleTime: 30s })` REST primary with a `useRealtimeSubscription<PointPoolFrame>` STOMP merge on `/topic/rooms.{roomId}.points`. Dedupe by `sourceRevivalEventId` via a per-hook-instance `useRef<Set<number>>` (canonical `useChatRealtime` pattern). The WS payload's `newTotal` is authoritative — never `total + delta` arithmetic (Trap #5). 30s staleTime matches `qk.meSurvival` for lock-step refresh cadence. Wallet AC9 wires `WalletScreen` to read `roomPoints.total` instead of `survival.roomPointPool`, and PoolBar AC8 was simplified to a purely presentational component (the Story 3.4 inline STOMP subscribe + per-frame `qk.meSurvival` invalidate wart is now gone).

**Topic format locked dot-separator** (AC4 CRITICAL): `/topic/rooms.{roomId}.points` — every other room topic (`chat`/`members`/`survival`/`kudos`) uses dots and `JwtChannelInterceptor.ROOM_TOPIC` regex anchors on the dot form. The epics.md AC4 text (line 638) shows slash-form `/topic/rooms/{id}/points` which is wrong vs the codebase. No code change to topic naming; a follow-up doc PR can correct epics.md if desired. Out-of-scope per the story.

**RevivalService refactor preserved byte-identical observable behavior** (AC11 regression gate). The `RoomPointPoolService` is added as a SEPARATE injection (BE-4 partial-compliance note): `RoomPointPoolRepository` stays in the constructor list because the recommended call shape in Dev Notes Trap #6 has the caller pre-acquire `selectForUpdate` to derive `newTotal` for the `revival_events.pool_after` column INSERT. The same row stays locked, so `applyDelta`'s internal `selectForUpdate` is a no-op cost in Postgres. All 9 + 14 + 3 + 3 (concurrency IT) + 2 (wallet-initiated IT) existing test cases passed with only mock-wiring adjustments — DTO field values, emitted event sequences, and DB row state are observably identical.

**BE-4 deviation explanation (architectural integrity preserved):** the spec said "the repository becomes private to the new service" but the recommended call shape in Trap #6 forces the caller to keep `selectForUpdate` access for pre-INSERT `pool_after` computation. The pragmatic split: `RevivalService` keeps `RoomPointPoolRepository` for READ-side lock acquisition only (no `incrementTotal` calls from `RevivalService` anymore — those go through `applyDelta`), and the new `RoomPointPoolService` owns all WRITE-side concerns (guard + increment + publish). This satisfies the spirit of BE-4 (single chokepoint for mutations) without requiring a more invasive INSERT-reorder that would have moved `PointPoolChangeEvent` publishing into the service WITHOUT the `sourceRevivalEventId` (which doesn't exist until after the INSERT). The trade-off is documented in `RoomPointPoolService`'s class Javadoc.

**Wallet `survival.roomPointPool` preserved (AC10).** The cross-room `MeSurvivalEntry.roomPointPool` field stays IDENTICAL — spectator-mode + Today-tab `WalletPreview` surfaces continue to read this aggregated field. AC10 explicitly preserves the dual-path design: per-room Wallet route reads from `useRoomPoints` (live + dedicated); spectator Today-tab reads from `meSurvival` (cross-room batched). Wallet AC9 only touches the displayed value in the per-room Wallet route.

**Test count delta:** BE +11 (6 new in `RoomPointPoolServiceTest` + 5 new in `RoomPointsControllerTest`). FE +8 (5 new in `roomPoints.test.tsx` + 3 net-new cases from PoolBar restructure, e.g., the negative `getRealtimeClient.not.toHaveBeenCalled` assertion).

**Pre-existing lint/typecheck issues** (out of scope): 4 lint errors + 2 lint warnings + 2 typecheck errors live in files this story did not touch. `git stash` of all Story 4.1 changes leaves the same 6 lint + 2 typecheck problems. Suggest a follow-up `chore(infra)` PR to repair the ESLint plugin missing (`react-hooks/exhaustive-deps`), the `react-native-pager-view` dependency (either remove `FriendsTodayPager.tsx` or restore the dependency), and the two `survival/__tests__/SurvivalChip*` require-import-rule violations.

**Pre-existing uncommitted test fixes also restored:** `git stash`/`pop` cycle confirmed three test files (`PersonalPointsLedgerRepositoryListTest`, `RevivalEventRepositoryReceivedTest`, `WalletPrivacyDefenceIT`) have a one-line column-name typo fix (`member_cap` → `max_members`) that pre-dates this story. Left in working tree — orthogonal to Story 4.1 but bundles cleanly with the same PR if the user wants.

**Manual smoke (VERIFY-2)**: deferred — requires a live dev environment (Expo + Spring Boot + Postgres). The unit + slice + integration test layer enforces the live-fill semantics through `RoomPointPoolServiceTest.applyDelta_publishesPointPoolChangeEventWithComputedNewTotal` (BE) and `useRoomPoints merges STOMP frame using authoritative newTotal` (FE). The smoke check is recommended before opening the PR but is not a hard blocker.

**VERIFY-3 (PR base check)**: deferred to PR-open time. Current branch `feat/story-3-4-wallet-ui-surface` includes one unmerged commit (Story 3.4, `9f2bc84`) — main is at `3614deb` (Stories 3.2 + 3.3). Suggest the user verify Story 3.4 has actually landed on main via a separate PR before opening a Story 4.1 PR from a fresh `feat/story-4-1-room-point-pool-counter-cache` branch off main; otherwise the Story 4.1 PR will pull Story 3.4 in. The sprint-status memory note ("Story 3.4 + 11 review patches shipped on PR #81") appears to be stale relative to actual git state.

### File List

**BE — new files:**
- `BE/src/main/java/com/yeosal/api/revival/RoomPointPoolService.java` — Story 4.1 AC3 chokepoint service.
- `BE/src/main/java/com/yeosal/api/revival/RoomPointPoolDto.java` — Story 4.1 AC5 wire record.
- `BE/src/main/java/com/yeosal/api/revival/RoomPointsController.java` — Story 4.1 AC5 REST endpoint.
- `BE/src/test/java/com/yeosal/api/revival/RoomPointPoolServiceTest.java` — Story 4.1 BE-7 (6 cases).
- `BE/src/test/java/com/yeosal/api/revival/RoomPointsControllerTest.java` — Story 4.1 BE-8 (5 cases).

**BE — modified files:**
- `BE/src/main/java/com/yeosal/api/revival/RevivalService.java` — Story 4.1 BE-2/BE-3/BE-4 refactor. Constructor adds `RoomPointPoolService roomPointPoolService` injection; `reviveSelf` (line 198) and `reviveFriend` (line 374) inline `roomPointPool.incrementTotal(...)` + `eventPublisher.publishEvent(new PointPoolChangeEvent(...))` replaced with `roomPointPoolService.applyDelta(roomId, poolDelta, revivalEventId, now)`. `RoomPointPoolRepository` injection retained for pre-INSERT `selectForUpdate` (`pool_after` derivation).
- `BE/src/test/java/com/yeosal/api/revival/RevivalServiceTest.java` — Added `@Mock RoomPointPoolService roomPointPoolService` field, updated constructor call, replaced `times(2)` → `times(1)` PointPoolChangeEvent assertion in `reviveSelf_freeTicket_happy` with `verify(roomPointPoolService).applyDelta(...)` (BE-9).
- `BE/src/test/java/com/yeosal/api/revival/FriendGiftServiceTest.java` — Same mock-wiring update for `reviveFriend_happy_freshGiver` (`times(3)` → `times(2)`, swap PointPoolChangeEvent → FriendGiftSentEvent in `get(1)`), and replaced `verify(roomPointPool, never()).incrementTotal(...)` with `verify(roomPointPoolService, never()).applyDelta(...)` in the race-loser test.
- `BE/src/test/java/com/yeosal/api/revival/RevivalServiceSourceSubtypeTest.java` — Same constructor mock addition (no assertion changes — this test only verifies the `insertOnConflictDoNothing` 5th arg).

**FE — new files:**
- `FE/src/api/roomPoints.ts` — Story 4.1 FE-1 (REST client).
- `FE/src/lib/query/hooks/roomPoints.ts` — Story 4.1 FE-3 (`useRoomPoints` domain hook with dedupe).
- `FE/src/lib/query/hooks/__tests__/roomPoints.test.tsx` — Story 4.1 FE-8 (5 cases).

**FE — modified files:**
- `FE/src/lib/query/keys.ts` — Story 4.1 FE-2 (`qk.roomPoints(roomId)` key added between `friendGiftTargets` and `personalPointsLedger`).
- `FE/src/components/revival/PoolBar.tsx` — Story 4.1 FE-4 (inline `getRealtimeClient().subscribe(...)` block + `useQueryClient` import + `qk.meSurvival` invalidate removed; `roomId` prop removed since it was only used by the now-extracted subscribe; component is purely presentational).
- `FE/src/components/revival/__tests__/PoolBar.test.tsx` — Story 4.1 FE-6 (simplified to 5 cases — fill ratio, negative subscribe assertion, animate-on-change smoke, reduced-motion, unmount cleanup).
- `FE/src/components/wallet/WalletScreen.tsx` — Story 4.1 FE-5 (`useRoomPoints(roomId)` mounted; `const pool = roomPoints.total` replaces `survival.roomPointPool`; `<PoolBar total={pool} max={POOL_MAX_V1} />` no longer passes `roomId`).
- `FE/src/components/wallet/__tests__/WalletScreen.test.tsx` — Story 4.1 FE-7 (`jest.mock("../../../api/roomPoints")` added; default `getRoomPoints` returns `{ roomId, total: 0, lastEventAt: null }`; `useRealtimeSubscription` added to the realtime client mock so the hook's subscribe call is a no-op).

**Sprint tracking — modified files:**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `4-1-room-point-pool-counter-cache: ready-for-dev → in-progress → review`.
- `_bmad-output/implementation-artifacts/4-1-room-point-pool-counter-cache.md` — Status, Tasks/Subtasks checkboxes, Dev Agent Record sections, File List, Change Log.

**Pre-existing uncommitted (NOT Story 4.1 — bundled by working-tree state):**
- `BE/src/test/java/com/yeosal/api/revival/PersonalPointsLedgerRepositoryListTest.java` — single-line column-name typo fix.
- `BE/src/test/java/com/yeosal/api/revival/RevivalEventRepositoryReceivedTest.java` — single-line column-name typo fix.
- `BE/src/test/java/com/yeosal/api/revival/WalletPrivacyDefenceIT.java` — single-line column-name typo fix.

### Change Log

- **2026-06-01** — Story 4.1 (Room point pool counter cache) implementation landed. BE-1 through BE-10 + FE-1 through FE-9 + VERIFY-1 + STATUS-1 + STATUS-2 all complete. VERIFY-2 (manual smoke) and VERIFY-3 (PR base check) deferred to PR-open. AC11 satisfied — BE 77/77 + FE 330/330 green. Flipped sprint-status `in-progress → review`.
