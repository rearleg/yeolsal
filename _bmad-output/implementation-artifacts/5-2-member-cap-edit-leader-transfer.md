# Story 5.2: Member-cap edit + leader transfer

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a room leader,
I want to update the room's member cap (within `[2, 30]`) on a next-month-only basis, and to transfer leadership to any active room member,
So that I can grow/shrink the room responsibly and pass the baton when needed.

## Acceptance Criteria

> 이 스토리는 **Epic 5의 두 번째 BE+FE 변경**으로 (a) leader-only `PATCH /api/v1/rooms/{id}/members/cap` (next-month-only) + (b) leader-only `POST /api/v1/rooms/{id}/transfer-leadership` (immediate, atomic) 두 신규 엔드포인트를 추가한다. Story 5.1이 `RoomService.requireLeader`를 `public`으로 promotion + 첫 wiring을 끝냈으므로 이 스토리는 그 chokepoint를 **두 번째로** 재사용한다(역시 인라인 leader 체크 금지). 멤버-캡 저장은 신규 V13 migration이 추가하는 `rooms.pending_max_members` + `rooms.pending_max_members_effective_from_month` 컬럼에 적재하고 `RoomService.requireRoom` 진입 시 lazy promotion으로 `rooms.max_members`에 흘려보낸다. Leader transfer는 즉시-적용(다음 달 아님 — epics AC line 748-750 명시) 으로 `rooms.owner_id` + 양측 `room_members.role`을 동일 트랜잭션에서 flip한 뒤 `/topic/rooms.{id}.survival`에 `LeadershipChangePayload`를 publish한다. **FR-8.5.5 leader-driven member removal (`DELETE /rooms/{id}/members/{userId}`)는 명시적으로 OUT-OF-SCOPE** — 본 스토리의 epics ACs(epics.md:736-756) 및 architecture §6.4 endpoint table(line 805-817) 어느 쪽에도 enumerate되지 않았고, FR-8.5.5의 정식 소유 스토리는 follow-up으로 미루어진다(자세한 사유는 Dev Notes "Planning ambiguity — FR-8.5.5 deferral" 절 참조).

### AC1 — Leader-only `PATCH /api/v1/rooms/{id}/members/cap` endpoint (REQUIRED ENDPOINT)

**Given** I am `rooms.owner_id` for room R (leader of record per FR-8.5.1)
**When** I call `PATCH /api/v1/rooms/{id}/members/cap` with body `{ "maxMembers": 20 }`
**Then** the BE:
1. resolves the authenticated `User` via the existing `CurrentUser.require(auth)` chain (mirrors `RoomController.create:46`, `RoomRuleController` Story 5.1 PATCH flow),
2. loads the room via `roomService.requireRoom(roomId)` → 404 `NOT_FOUND` ("방을 찾을 수 없습니다.") if absent,
3. enforces leader-only via the **public** `RoomService.requireLeader(room, me)` chokepoint (Story 5.1 promoted it; this is its second consumer — do NOT inline `room.getOwner().getId().equals(...)`),
4. validates `maxMembers` is non-null integer + `2 <= maxMembers <= 30` at the controller boundary via `@Min(2) @Max(30) @NotNull Integer` (matches the existing `CreateRoomRequest.maxMembers` precedent at `RoomController:137` — keep both client-validation contracts byte-identical),
5. computes `effective_from_month = nextMonthKST` per AC3 (REUSE Story 5.1's nextMonth helper — do NOT re-derive),
6. UPSERTs the `rooms.pending_max_members` + `rooms.pending_max_members_effective_from_month` columns via the AC4 atomic-write helper (JPA dirty-check inside `@Transactional`),
7. returns `200 OK` with `ApiResponse.of(RoomSummary)` envelope where `RoomSummary` is extended per AC11 to surface the pending fields.

**And** the endpoint MUST live at `/api/v1/rooms/{id}/members/cap` exactly (Architecture §6.4 line 813). NOT at `/rooms/{id}/cap`, NOT at `/rooms/{id}/members-cap`. Path drift is wire-incompatible with the architecture-locked contract.

PRD: FR-8.5.4. Architecture: §6.4 (REST endpoint table line 813), V13 (new — see AC2). UX: ux-design-specification.md:1404 ("Next-month-only contract: leader 모든 변경(rule / cap)은 다음 달부터 (J5)"), §1136-1155 (D5 Plate System utility surface).

### AC2 — V13 migration adds `rooms.pending_max_members` + `rooms.pending_max_members_effective_from_month` (REQUIRED MIGRATION)

**Given** Story 5.1 (V11 + V12 ship rule and kudos schema) is done
**When** the dev agent creates the persistence floor for next-month-only cap edits
**Then** a new Flyway migration MUST be added at `BE/src/main/resources/db/migration/V13__rooms_pending_max_members.sql` with these contents (idempotent SQL):

```sql
-- V13: Story 5.2 — pending member-cap snapshot for next-month-only application.
-- Lazy promotion at RoomService.requireRoom propagates the pending value into
-- rooms.max_members once Asia/Seoul calendar month reaches effective_from_month.
alter table rooms
    add column if not exists pending_max_members smallint
        check (pending_max_members is null or (pending_max_members between 2 and 30)),
    add column if not exists pending_max_members_effective_from_month varchar(7);

-- A pending value is only valid when paired with its effective month (and
-- vice versa). The DB CHECK guards against a half-written state slipping in
-- via direct SQL or a future bug in the JPA setter pair.
alter table rooms
    drop constraint if exists chk_rooms_pending_cap_consistency,
    add constraint chk_rooms_pending_cap_consistency
        check (
            (pending_max_members is null
             and pending_max_members_effective_from_month is null)
         or (pending_max_members is not null
             and pending_max_members_effective_from_month is not null)
        );
```

**Constraints:**
- `<N>` is `13` — the smallest free integer after V11 (Story 1.4) and V12 (Story 3.5 kudos). Confirm by `ls BE/src/main/resources/db/migration/ | sort | tail -3` before creating the file.
- `alter table ... add column if not exists` is the project's idempotent pattern (matches V11 step (1) `alter table rooms drop constraint if exists chk_rooms_max_members`).
- The conjunctive XOR-shape CHECK constraint `chk_rooms_pending_cap_consistency` mirrors the project's V8/V9 partial-unique-index discipline — DB enforces the data invariant the service layer also enforces.
- **No backfill rows**: existing `rooms` rows get NULL on both new columns — that's the correct "no pending edit" state.
- **No new index**: the read site (`requireRoom`) loads the room by PK; no scan index is needed on the pending columns.

PRD: FR-8.5.4. Architecture: V11 (1) `chk_rooms_max_members` precedent for cap CHECK; project-context.md:138-139 ("Flyway runs each migration exactly once — prefer idempotent SQL"); project-context.md:227 ("V<N>__<slug>.sql; `<N>` is the smallest free integer").

### AC3 — `nextMonthKST` computation REUSE (CRITICAL CORRECTNESS)

**Given** the leader confirms a cap edit at any instant
**When** the BE service computes `effective_from_month`
**Then** the computation MUST be byte-identical to Story 5.1's:

```java
private static final ZoneId KST = ZoneId.of("Asia/Seoul");
String nextMonthKST() {
    LocalDate todayKst = LocalDate.ofInstant(clock.instant(), KST);
    return YearMonth.from(todayKst).plusMonths(1).toString(); // "YYYY-MM"
}
```

**Rationale (mirrors Story 5.1 AC2):**
- `Clock` is the project-wide test-injectable wall-clock; `RoomService` already injects it (line 57). The new cap service injects the same `Clock` bean.
- `ZoneId.of("Asia/Seoul")` is the project-context-locked day-boundary zone (project-context.md:92,270).
- Calendar-month KST (NOT entry-date) — same trap-avoidance Story 5.1 catalogued.

**Shared utility option (recommended):** extract `nextMonthKST(Clock clock)` to a package-private static helper in `com.yeosal.api.survival.RoomRuleService` (Story 5.1's class) — promote the existing private helper to `static String nextMonthKST(Clock clock)` so `RoomMemberCapService` (this story) reuses it via `RoomRuleService.nextMonthKST(clock)`. This avoids duplicating the calendar-month KST logic in two services. If the dev prefers not to touch Story 5.1's class, duplicate the helper inline in `RoomMemberCapService` with an identical implementation + a `// Mirrors RoomRuleService.nextMonthKST` durable comment (NO ref to Story 5.1 in source).

**Anti-pattern (DO NOT IMPLEMENT):**
- `LocalDate.now()` without a `Clock` (test non-determinism — same trap Story 5.1 caught).
- `ZoneOffset.of("+09:00")` instead of the named zone (project-context line 270).
- Using `EntryDateResolver` (the daily-mission boundary is wrong for calendar-month rule scoping — Story 5.1 Trap #1).

### AC4 — Upsert semantics on cap edit (SAME-LEADER MULTI-EDIT)

**Given** the leader already edited the pending cap earlier this month
**When** they re-edit again (typo correction, mind-change) before the month boundary
**Then** the **existing** pending value MUST be **replaced** — no error, no duplicate row, just an overwrite. Implementation is JPA dirty-check inside `@Transactional`:

```java
@Transactional
public RoomService.RoomSummary updateMemberCap(User me, long roomId, int maxMembers) {
    // Validation
    if (maxMembers < 2 || maxMembers > 30) {
        throw new BadRequestException("정원은 2에서 30 사이여야 합니다.");
    }
    Room room = roomService.requireRoom(roomId);   // 404 NOT_FOUND inside
    roomService.requireLeader(room, me);            // 403 FORBIDDEN inside

    String nextMonth = nextMonthKST();
    short newPending = (short) maxMembers;
    if (room.getPendingMaxMembers() != null
            && room.getPendingMaxMembers() == newPending
            && nextMonth.equals(room.getPendingMaxMembersEffectiveFromMonth())) {
        // No-op — idempotent re-edit; return current snapshot.
        return RoomService.RoomSummary.from(room);
    }
    room.setPendingMaxMembers(newPending);
    room.setPendingMaxMembersEffectiveFromMonth(nextMonth);
    // No explicit save — dirty-check flushes on @Transactional commit.
    return RoomService.RoomSummary.from(room);
}
```

**Race-condition note:** Same-leader double-tap from the FE produces two concurrent PATCH transactions targeting the same room row. Both load the room, both call `setPendingMaxMembers(...)`, both commit. JPA flushes via UPDATE; PostgreSQL row-level locks serialize the two. Winner overrides loser — idempotent if both writes carry the same value, last-write-wins if values differ. No exception thrown either way. **No advisory lock needed** for this story — `rooms` row-level locking is sufficient.

**REQUIRED ENTITY EDITS** on `Room.java`:
- Add fields:
  ```java
  @Column(name = "pending_max_members")
  private Short pendingMaxMembers;

  @Column(name = "pending_max_members_effective_from_month", length = 7)
  private String pendingMaxMembersEffectiveFromMonth;
  ```
- Add getter/setter pairs `getPendingMaxMembers()` / `setPendingMaxMembers(Short)` and `getPendingMaxMembersEffectiveFromMonth()` / `setPendingMaxMembersEffectiveFromMonth(String)`. Setters MUST be **public** (consumed from `RoomMemberCapService` in `com.yeosal.api.room` — same package; package-private would also work, but `Room` already exposes `setOwner` / `setMaxMembers` publicly, so match the existing style).
- Boxed `Short` (not primitive `short`) so null = "no pending edit" is distinguishable; the V13 column is nullable.
- **Do NOT touch** `prePersist` clamp logic (Room.java:51-67) — the existing clamp is for `max_members` only; the pending columns are validated by the V13 CHECK constraint at INSERT/UPDATE time.

### AC5 — Lazy promotion at `RoomService.requireRoom` (CONTRACT INTEGRITY)

**Given** the leader set `pending_max_members = 20` on `2026-04-15` with `pending_max_members_effective_from_month = "2026-05"`
**When** ANY `RoomService` operation calls `requireRoom(roomId)` on or after `2026-05-01 00:00 KST` calendar instant
**Then** the helper MUST atomically promote the pending value into `max_members` and clear both pending columns:

```java
// In RoomService — REQUIRED REFACTOR. The current implementation at line 402-405 is:
//     private Room requireRoom(long roomId) {
//         return rooms.findById(roomId)
//                 .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
//     }
// Replace with:
private Room requireRoom(long roomId) {
    Room room = rooms.findById(roomId)
            .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
    promotePendingCapIfDue(room);
    return room;
}

private void promotePendingCapIfDue(Room room) {
    String pendingMonth = room.getPendingMaxMembersEffectiveFromMonth();
    Short pending = room.getPendingMaxMembers();
    if (pendingMonth == null || pending == null) {
        return;
    }
    String currentMonth = YearMonth.from(
            LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))
    ).toString();
    if (currentMonth.compareTo(pendingMonth) < 0) {
        // Not due yet (still before the effective month).
        return;
    }
    room.setMaxMembers(pending);
    room.setPendingMaxMembers(null);
    room.setPendingMaxMembersEffectiveFromMonth(null);
    // dirty-check on @Transactional commit
}
```

**Visibility note:** `requireRoom` is currently `private`. Some new callers in this story (cap controller, transfer-leadership service) may want to share it — promote to `public` ONLY IF needed (matches Story 5.1's `requireLeader` promotion pattern). Otherwise keep `private`; this story's `RoomMemberCapService` lives in the same `com.yeosal.api.room` package, so `private` stays sufficient (calls `roomService.requireRoom(...)` from a same-package service).

**Wait — RoomMemberCapService is in com.yeosal.api.room?** YES. Per AC15 (file placement), this story's new BE service lives in `com.yeosal.api.room` (same package as `RoomService`, `Room`, `RoomController`), NOT in `com.yeosal.api.survival` (where Story 5.1's `RoomRuleService` lives). Rationale:
- Member-cap is a room-domain attribute (`rooms.max_members`), not a survival-state attribute. Putting the service in `room/` package preserves package-by-feature (`project-context.md:176`).
- Story 5.1's `RoomRuleService` is in `survival/` because `room_rule_versions` + `RulePresetEvaluator` are survival-domain concerns. The rule and the cap have different domain ownership despite both being "leader-only edits".

**Cross-package call:** `RoomMemberCapService` (in `room/`) → calls `roomService.requireRoom(roomId)`. `requireRoom` is `private` in `RoomService`. The cross-package call would fail compilation. Therefore: **PROMOTE `requireRoom` from `private` → `public`** (same pattern as Story 5.1's `requireLeader` promotion). Add the same `public` JavaDoc treatment:

```java
/**
 * Room loader + lazy promoter. Loads {@code roomId} (404 {@code NOT_FOUND}
 * on absence) and, before returning, flushes any pending member-cap edit
 * whose {@code effective_from_month <= currentMonth(KST)} into {@code max_members}.
 * Promotion is idempotent — re-entrancy is a no-op once the pending columns
 * are cleared. Every leader-edited next-month-only attribute (cap today;
 * future minDays, etc.) MUST hook its promotion into this single helper
 * so the contract-integrity contract cannot drift between callers.
 *
 * @throws NotFoundException when no row exists for {@code roomId}.
 */
public Room requireRoom(long roomId) { ... }
```

**WARNING — refactor impact:** Promoting `requireRoom` widens the visibility but the existing 11+ internal callers stay byte-identical (they're already in `RoomService` and call `requireRoom(roomId)` directly). The change is purely additive.

**Verification:** A new Testcontainers IT `RoomMemberCapPromotionIT` covers the boundary semantics with at least two sub-cases:
1. Set pending cap=20 effective `2026-05` on April-15. `requireRoom` on April-25 → no promotion (`max_members` stays at 12, pending stays at 20/2026-05).
2. Same setup. `requireRoom` on May-1 06:00 KST → promotion fires (`max_members = 20`, pending cleared). A second `requireRoom` on May-2 is a no-op (pending already null).

### AC6 — Leader-only `POST /api/v1/rooms/{id}/transfer-leadership` endpoint (REQUIRED ENDPOINT)

**Given** I am the leader of room R
**When** I call `POST /api/v1/rooms/{id}/transfer-leadership` with body `{ "targetUserId": <X_id> }`
**Then** the BE:
1. resolves the authenticated `User` via `CurrentUser.require(auth)`,
2. loads the room via `roomService.requireRoom(roomId)` → 404 `NOT_FOUND` if absent,
3. enforces leader-only via `roomService.requireLeader(room, me)` → 403 `FORBIDDEN` on non-leader,
4. resolves the target via `users.findById(targetUserId)` → 400 `VALIDATION` ("대상 사용자를 찾을 수 없습니다.") if absent (defensive — the typical FE path picks from existing members so this branch fires on stale FE state or curl),
5. rejects self-transfer (`targetUserId == me.id`) → 400 `VALIDATION` ("이미 본인이 방장입니다."),
6. resolves target membership via `roomMembers.findByRoomAndUser(room, target)` → 400 `VALIDATION` ("대상은 이 방의 멤버가 아닙니다.") if absent (matches epics line 752-754 "non-member → 400 VALIDATION"),
7. resolves target survival-state via `survivalStates.findByRoomIdAndUserId(roomId, target.id)` → if absent OR if `status ∈ {RED, SPECTATOR}` throw `IneligibleLeaderException` → 409 `CONFLICT` with code `INELIGIBLE_LEADER` (matches epics line 752-754 "eliminated → 409 INELIGIBLE_LEADER"). `ACTIVE` and `YELLOW` statuses are ACCEPTED (PRD §6.3 line 316 + epics line 768 use "surviving" interchangeably with "ACTIVE or YELLOW" — both are pre-elimination).
8. inside the **same `@Transactional` boundary**:
   - `room.setOwner(target)` (flips `rooms.owner_id`),
   - target's `RoomMember.setRole(RoomRole.OWNER)` (loaded from step 6),
   - previous-leader's `RoomMember.setRole(RoomRole.MEMBER)` — load via `roomMembers.findByRoomAndUser(room, me).orElseThrow(IllegalStateException)` (the leader MUST exist as a member; the throw is defensive).
9. registers an `afterCommit` realtime emission per AC9.
10. returns `200 OK` with `ApiResponse.of(RoomSummary)` envelope with the **new** `ownerId`.

**And** the endpoint MUST live at `/api/v1/rooms/{id}/transfer-leadership` exactly (Architecture §6.4 line 814).

PRD: FR-8.5.6. Architecture: §6.4 line 814, §4.* (leadership lifecycle). UX: ux-design-specification.md:1404 (next-month-only is rule + cap; transfer is immediate).

### AC7 — `TransferLeadershipRequest` body shape (LOCKED REQUEST CONTRACT)

**Given** the POST endpoint at AC6
**When** the controller deserializes the request body
**Then** the body MUST be:

```java
public record TransferLeadershipRequest(
    @NotNull
    @Positive(message = "targetUserId는 양수여야 합니다.")
    Long targetUserId
) {}
```

- `@Valid @RequestBody TransferLeadershipRequest body` triggers `MethodArgumentNotValidException` on missing/invalid fields → `ApiExceptionHandler.validation` (line 78-82) maps to 400 `VALIDATION`.
- `Long` (boxed) not `long` (primitive) so Jackson distinguishes "missing field" from "explicit 0" — `@NotNull` catches the missing case before the service ever sees it.
- `@Positive` is the first line of defense (server-side ID space starts at 1); the service step-4 `users.findById` is the second line.

### AC8 — `UpdateMemberCapRequest` body shape (LOCKED REQUEST CONTRACT)

**Given** the PATCH endpoint at AC1
**When** the controller deserializes the request body
**Then** the body MUST be:

```java
public record UpdateMemberCapRequest(
    @NotNull
    @Min(value = 2, message = "정원은 2 이상이어야 합니다.")
    @Max(value = 30, message = "정원은 30 이하여야 합니다.")
    Integer maxMembers
) {}
```

- `Integer` (boxed) so `@NotNull` catches the missing case.
- `@Min`/`@Max` mirror the existing `CreateRoomRequest.maxMembers` validation at `RoomController:137` byte-for-byte. Do NOT introduce a third validation contract.

### AC9 — `RealtimeEvent.LeadershipChange` emission (CONTRACT INTEGRITY)

**Given** a leader transfer commits successfully
**When** the post-commit hook fires
**Then** a `LeadershipChangePayload` MUST be emitted on `/topic/rooms.{id}.survival` (matches Story 5.3 epics:776 which uses the same topic for the auto-promotion variant — symmetric design):

```java
public record LeadershipChangePayload(
    long roomId,
    long previousLeaderUserId,
    long newLeaderUserId,
    String reason         // "MANUAL_TRANSFER" for Story 5.2; "AUTO_ELIMINATION" reserved for Story 5.3
) {}
```

**Emission helper** added to `RealtimePublisher`:

```java
/**
 * Story 5.2 — leader transfer publish point. Emits the {@code LeadershipChange}
 * frame on the room's survival topic so any authenticated room member sees
 * the new leader without waiting for a manual refresh. Failures are
 * warn-and-swallowed via {@link #sendTopic} — a broker hiccup must NEVER
 * roll back the surrounding transfer-leadership transaction. The
 * dual-channel survival shape (private + broadcast) is intentionally NOT
 * used here: a leader transfer carries no privacy implication.
 */
public void publishLeadershipChange(long roomId, LeadershipChangePayload payload) {
    sendTopic("/topic/rooms." + roomId + ".survival", payload);
}
```

**Post-commit timing:** The publish MUST run inside a `TransactionSynchronization.afterCommit` hook (mirrors `RoomService.publishAutoLeaveAfterCommit` line 585-609) so a rolled-back transfer never lights up the realtime fan-out. The service-layer code in `TransferLeadershipService` (or `RoomService.transferLeadership` if collapsed — see AC15 file layout) MUST register the synchronization via `TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { realtime.publishLeadershipChange(roomId, payload); } })` and fall through to the direct call when no synchronization is active (mirrors the existing helper).

**JwtChannelInterceptor regex compliance:** The existing regex at `JwtChannelInterceptor:41` already permits `survival` topics — `^/topic/rooms\.(\d+)\.(chat|members|survival|points|kudos)$`. **NO regex change needed.** Do NOT introduce a new `leadership` token; the symmetric design with Story 5.3 uses `survival`.

**FE consumer:** The Story 5.2 FE wires no new STOMP subscriber for this event in v1 — the existing `useRoomsQuery` revalidation pattern (on screen focus / on app foreground) is sufficient for cosmetic owner updates. Hard fan-in is Story 5.3+ scope when `RealtimeEvent.LeadershipChange` becomes a sealed-variant the FE handles uniformly. **Document this gap explicitly in Dev Notes** — a leader who transfers and stays on the room screen will NOT see the owner badge flip until a focus event triggers refetch.

### AC10 — New `IneligibleLeaderException` + `ApiExceptionHandler` mapping (REQUIRED EXCEPTION)

**Given** the transfer-leadership service rejects an eliminated target (RED or SPECTATOR, or no survival_state row)
**When** the exception propagates
**Then** `ApiExceptionHandler` MUST map it to `409 CONFLICT` with code `INELIGIBLE_LEADER` (mirrors `KudosTargetNotEligibleException` precedent at `ApiExceptionHandler:210`):

```java
// New file at BE/src/main/java/com/yeosal/api/room/IneligibleLeaderException.java:
package com.yeosal.api.room;

/**
 * Thrown when {@code POST /api/v1/rooms/{id}/transfer-leadership} targets a
 * member whose survival state is not promotion-eligible. Per PRD §6.3 +
 * epics:752-754, only members with {@code SurvivalStatus.ACTIVE} or
 * {@code SurvivalStatus.YELLOW} may receive leadership; {@code RED} and
 * {@code SPECTATOR} are rejected so the leader-of-record always carries
 * a surviving membership.
 */
public class IneligibleLeaderException extends RuntimeException {
    public IneligibleLeaderException(String message) {
        super(message);
    }
}
```

```java
// Append to BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java:
@ExceptionHandler(IneligibleLeaderException.class)
public ResponseEntity<ApiErrorResponse> ineligibleLeader(IneligibleLeaderException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiErrorResponse("INELIGIBLE_LEADER", ex.getMessage()));
}
```

**Anti-pattern (DO NOT IMPLEMENT):**
- Returning `403 FORBIDDEN` for INELIGIBLE_LEADER. Epic explicitly says 409 CONFLICT. 403 is leader-not-leader; 409 is state-precondition-failure.
- Throwing `BadRequestException` from the service when target is RED/SPECTATOR. That maps to 400 VALIDATION, not 409. Epic distinguishes the two cases on purpose.
- Skipping the `@ExceptionHandler` and relying on the generic `Exception` fallback at `ApiExceptionHandler:267` — that maps to 500 `INTERNAL_ERROR` and pollutes Sentry's server-bug channel (per project-context.md:87 + ApiExceptionHandler precedent).

**Import:** `import org.springframework.http.HttpStatus` and `com.yeosal.api.room.IneligibleLeaderException` to the handler. Match the existing import-order convention.

### AC11 — Extend `RoomService.RoomSummary` with pending-cap fields (LOCKED WIRE CONTRACT)

**Given** the FE editor needs to render current cap + any pending cap edit
**When** any endpoint returns a `RoomSummary`
**Then** the record MUST be extended additively:

```java
public record RoomSummary(
    long id,
    String name,
    long ownerId,
    int maxMembers,
    int minDailyGoalDays,
    Instant createdAt,
    Integer pendingMaxMembers,                  // NEW — nullable
    String pendingMaxMembersEffectiveFromMonth  // NEW — nullable "YYYY-MM"
) {
    public static RoomSummary from(Room room) {
        return new RoomSummary(
                room.getId(),
                room.getName(),
                room.getOwner().getId(),
                room.getMaxMembers(),
                room.getMinDailyGoalDays(),
                room.getCreatedAt(),
                room.getPendingMaxMembers() == null
                        ? null
                        : (int) room.getPendingMaxMembers().shortValue(),
                room.getPendingMaxMembersEffectiveFromMonth()
        );
    }
}
```

**Wire-shape impact:**
- Existing consumers of `RoomSummary` (`RoomController.create`, `RoomController.mine`, etc.) automatically gain the two new fields in their JSON envelope. Jackson serializes `null` as `null` (no field omission) so the FE sees `"pendingMaxMembers": null` when there's no pending edit.
- The FE `Room` interface at `FE/src/api/rooms.ts:19` MUST be extended in lock-step (AC12). DO NOT ship the BE extension without the FE one — Jackson's strict deserialization on the FE side via TypeScript would flag a runtime drift if the BE adds a field and the FE Room interface stays narrow.

**Anti-pattern (DO NOT IMPLEMENT):**
- Adding a separate `GET /api/v1/rooms/{id}/members/cap` endpoint that returns a dedicated `RoomCapStateDto`. The existing `useRoomsQuery` already fetches the list of rooms; piggy-backing the pending fields on `RoomSummary` saves an extra round-trip and a new TanStack Query key.
- Renaming `pendingMaxMembersEffectiveFromMonth` to `pendingCapMonth` or `pendingMonth`. The verbose name preserves grep-ability and mirrors the column name `pending_max_members_effective_from_month` from V13 (AC2).

### AC12 — FE `api/rooms.ts` extension + new TanStack hooks + new query key (LOCKED CACHE)

**Given** the FE needs the two new endpoints
**When** the dev wires the client layer
**Then**:

```ts
// FE/src/api/rooms.ts — EXTEND the existing Room interface (line 19) with two new optional fields:
export interface Room {
  id: number;
  name: string;
  ownerId: number;
  maxMembers: number;
  minDailyGoalDays: MinDays;
  createdAt: string;
  /** Story 5.2 — next-month-only pending cap. Null when no pending edit. */
  pendingMaxMembers: number | null;
  /** Story 5.2 — "YYYY-MM" effective month for the pending cap. Null when no pending edit. */
  pendingMaxMembersEffectiveFromMonth: string | null;
}

// Append at the bottom of FE/src/api/rooms.ts:

// ---------- Per-room member cap (next-month-only application) ----------

export interface UpdateMemberCapVars {
  roomId: number;
  maxMembers: number;
}

export async function updateMemberCap(
  roomId: number,
  body: { maxMembers: number },
): Promise<Room> {
  const envelope = await apiRequest<ApiEnvelope<Room>>(
    `/rooms/${roomId}/members/cap`,
    {
      method: "PATCH",
      body: JSON.stringify(body),
    },
  );
  return envelope.data;
}

// ---------- Leader transfer (immediate atomic change) ----------

export interface TransferLeadershipVars {
  roomId: number;
  targetUserId: number;
}

export async function transferLeadership(
  roomId: number,
  body: { targetUserId: number },
): Promise<Room> {
  const envelope = await apiRequest<ApiEnvelope<Room>>(
    `/rooms/${roomId}/transfer-leadership`,
    {
      method: "POST",
      body: JSON.stringify(body),
    },
  );
  return envelope.data;
}
```

```ts
// FE/src/lib/query/hooks/rooms.ts — append two new hooks (do NOT create a new file unless the file would exceed the 800-line cap):
import { transferLeadership, updateMemberCap } from "../../../api/rooms";
import type { Room, UpdateMemberCapVars, TransferLeadershipVars } from "../../../api/rooms";
import { ApiError } from "../../../api/client";

export function useUpdateMemberCap() {
  const qc = useQueryClient();
  const haptic = useHaptic();
  return useMutation<Room, ApiError, UpdateMemberCapVars>({
    mutationFn: ({ roomId, maxMembers }) =>
      updateMemberCap(roomId, { maxMembers }),
    onSuccess: () => {
      haptic("success");
      // Invalidate the listing — the rooms screen reads `room.maxMembers`
      // + `room.pendingMaxMembers` via useRoomsQuery, so a refetch surfaces
      // the new pending state to every consumer.
      qc.invalidateQueries({ queryKey: qk.rooms });
    },
  });
}

export function useTransferLeadership() {
  const qc = useQueryClient();
  const haptic = useHaptic();
  return useMutation<Room, ApiError, TransferLeadershipVars>({
    mutationFn: ({ roomId, targetUserId }) =>
      transferLeadership(roomId, { targetUserId }),
    onSuccess: (_data, { roomId }) => {
      haptic("success");
      // Owner change ripples through both the listing (room.ownerId)
      // and the per-room membership cache (role flip on both members).
      qc.invalidateQueries({ queryKey: qk.rooms });
      qc.invalidateQueries({ queryKey: qk.roomMembers(roomId) });
    },
  });
}
```

**Cache contract:**
- `useUpdateMemberCap` invalidates `qk.rooms` only — the pending fields live on `Room` and the listing is the single source of truth.
- `useTransferLeadership` invalidates both `qk.rooms` (for `ownerId` flip) and `qk.roomMembers(roomId)` (for `role` flips on the two affected member rows).
- **No new `qk.*` key needed** for cap edit (per AC11 — piggy-backs on `qk.rooms`).
- **No new `qk.*` key needed** for transfer (no dedicated read endpoint).
- Mutation `onError` toast SHOULD be handled at the screen layer (AC13/AC14) not in the hook — keeps the hook reusable.

**Anti-pattern (DO NOT IMPLEMENT):**
- `setQueryData(qk.rooms, [...])` to patch the room locally on mutation success. The BE may have a lazy-promotion side-effect that re-shapes the room (e.g., cap was already promoted on requireRoom) — `invalidateQueries` triggers a fresh refetch and is race-free against concurrent listing cache writes.
- Calling `qc.clear()` (forbidden globally per project-context.md:258 — nukes the AsyncStorage-persisted cache).
- Wrapping the two mutations in a single `useLeaderEdits()` parent hook — the cap and transfer flows have different invalidation contracts and error messages; keep them separate.

### AC13 — FE Member-Cap Editor screen at `FE/app/rooms/[id]/settings/cap.tsx` (REQUIRED ROUTE)

**Given** a room leader navigates to Room Settings → "그룹 정원" entry
**When** they reach `/rooms/{id}/settings/cap`
**Then** the FE renders a screen wrapped in `<SubModeProvider subMode="plate">` (D5 Plate System sub-mode per UX line 1154 — "Room settings detail" surface) that:

1. Calls `useRoomsQuery()` to read the current room (matches Story 5.1's leader-detection pattern at `RoomRuleEditor.tsx:50-51`).
2. Shows the current cap as a read-only summary (e.g., "이번 달 정원: 12명").
3. Shows the pending cap as a read-only line when `room.pendingMaxMembers != null` (e.g., "다음 달 적용 예정: 20명 (2026년 5월부터)").
4. Shows an editor: a numeric stepper / segmented control bounded to `[2, 30]` (reuse the existing `MAX_MEMBERS_MIN` / `MAX_MEMBERS_MAX` / `MAX_MEMBERS_DEFAULT` constants at `FE/src/api/rooms.ts:67-69`).
5. Shows the **locked preview literal** `"변경된 정원은 다음 달 1일부터 적용됩니다."` — VERBATIM, character-for-character (mirrors Story 5.1's preview pattern). NO paraphrasing, NO punctuation substitution (fullwidth `．` BANNED, ASCII `.` REQUIRED). NO leading/trailing whitespace mutation. Lock the literal in a module-level `const COPY = { previewLiteral: "..." } as const;` so the FE test (AC17) can assert byte-identity.
6. Renders a primary CTA "다음 달부터 적용하기" (or equivalent commit-tone copy — mirror Story 5.1's `RoomRuleEditor.tsx:26`). **Disabled state**: when the stepper value equals the **editable baseline** (= `room.pendingMaxMembers ?? room.maxMembers` when no pending edit, OR `room.pendingMaxMembers` when one exists — matches Story 5.1's pattern of allowing a leader to revert a staged pending change back to the current value).
7. On CTA tap: calls `useUpdateMemberCap().mutate({roomId, maxMembers})`. On success: success toast "다음 달부터 새 정원으로 시작해요." + `router.back()`. On `ApiError` 403: toast "방장만 정원을 바꿀 수 있어요."; on 400 VALIDATION: "정원은 2에서 30 사이여야 합니다."; on network failure: the default `ApiError` message from `toast.error(error.message)`.
8. Non-leader members reaching this URL directly see a read-only view (the stepper + Save CTA are hidden; the current-cap summary + pending line + a "정원 변경은 방장만 할 수 있어요." caption render). Leader detection: `roomsQuery.data?.find(r => r.id === roomId)?.ownerId === user.id` — mirrors `RoomRuleEditor.tsx:50-51`.
9. Route guard mirrors Story 5.1's `/settings/rule.tsx`:
   ```tsx
   if (!Number.isSafeInteger(roomId) || roomId <= 0) {
     return <Redirect href="/(tabs)/rooms" />;
   }
   ```

**Editor extraction pattern:** Following Story 5.1's documented Jest discoverability constraint (`RoomRuleEditor.tsx:1-1`, story file `5-1-...md:687`), the editor component MUST be extracted to `FE/src/components/rooms/RoomMemberCapEditor.tsx` so Jest's `testMatch` scope (`<rootDir>/src/**/__tests__/`) discovers its tests. The route file at `FE/app/rooms/[id]/settings/cap.tsx` is a thin wrapper:

```tsx
import { Redirect, useLocalSearchParams } from "expo-router";
import { useRequireAuth } from "../../../../src/auth/useRequireAuth";
import { RoomMemberCapEditor } from "../../../../src/components/rooms/RoomMemberCapEditor";
import { SubModeProvider } from "../../../../src/providers/SubModeProvider";

export default function RoomMemberCapEditorRoute() {
  useRequireAuth();
  const params = useLocalSearchParams<{ id: string }>();
  const roomId = Number(params.id);
  if (!Number.isSafeInteger(roomId) || roomId <= 0) {
    return <Redirect href="/(tabs)/rooms" />;
  }
  return (
    <SubModeProvider subMode="plate">
      <RoomMemberCapEditor roomId={roomId} />
    </SubModeProvider>
  );
}
```

**And** `FE/app/rooms/[id]/settings.tsx` MUST gain a new entry row pointing to `/rooms/{id}/settings/cap`, placed **after** `<RecordVisibilityToggle>` and **after** the existing rule-edit row (which Story 5.1 placed after `<RecordVisibilityToggle>`). Match the `Pressable` styling at `settings.tsx:105-123` byte-for-byte so the visual rhythm of the settings page is preserved. The row is visible to all members; tap takes them to the cap-editor screen (leader sees editor, non-leader sees read-only).

PRD: FR-8.5.4. UX: ux-design-specification.md:1136-1155 (D5 Plate System), :1404 (next-month-only cap), :1172 ("Settings / Profile / Room Rules — D5 Plate System utility surface").

### AC14 — FE Leader-Transfer screen at `FE/app/rooms/[id]/settings/transfer-leadership.tsx` (REQUIRED ROUTE)

**Given** a room leader navigates to Room Settings → "방장 이양" entry
**When** they reach `/rooms/{id}/settings/transfer-leadership`
**Then** the FE renders a screen wrapped in `<SubModeProvider subMode="plate">` (D5 Plate System) that:

1. Calls `useRoomsQuery()` (leader detection) + `useRoomMembersQuery(roomId)` (member list).
2. Shows the current leader as a read-only line at the top (e.g., "현재 방장: 진수").
3. Shows a member list (filtered: **exclude** the current leader + **exclude** any member with `survivalStatus ∈ {RED, SPECTATOR}`). The filter requires augmenting the FE state — see "Member eligibility resolution" below.
4. Each list item is a `Pressable` row showing nickname + role badge. Tap opens a confirmation modal: "{nickname}님에게 방장을 양도할까요? 양도 후에는 본인이 되돌릴 수 없어요." with "양도하기" + "취소" CTAs.
5. On confirm: calls `useTransferLeadership().mutate({roomId, targetUserId})`. On success: success toast "{nickname}님에게 방장을 양도했어요." + `router.back()`. On `ApiError` 403: toast "방장만 양도할 수 있어요."; on 400 VALIDATION: "대상 멤버를 다시 확인해 주세요." (covers self-transfer + non-member + missing-user — they share the same recovery path); on 409 INELIGIBLE_LEADER: "지금은 양도가 어려운 상태예요. 다시 확인해 주세요." (rephrased to AVOID the brand-voice banned term `탈락` — see AC18 Trap #12); on network failure: default ApiError message.
6. Non-leader members reaching this URL directly see a read-only view that displays the current leader + the same eligible-member list (read-only, no Pressable, no confirm modal). Caption: "방장 이양은 방장만 할 수 있어요."

**Member eligibility resolution:** the existing `useRoomMembersQuery(roomId)` returns `RoomMember` rows without a survival_state field. Two paths:
- **Path A (preferred — additive, no BE change):** call the existing roster hook returning per-member survival status. Before writing FE code, the dev agent MUST grep `FE/src/lib/query/hooks/` for an existing roster hook (e.g., `useSurvivalRoster`, `useRoomSurvival`) that returns per-member `survivalStatus`. If found → use Path A.
- **Path B (fallback):** introduce a per-member `survivalStatus` field on `RoomMember` (BE side: extend `MemberSummary` record at `RoomService.java:446-466` with a nullable `survivalStatus` string; FE side: extend the TS `RoomMember` interface at `FE/src/api/rooms.ts:33-40` with `survivalStatus?: "ACTIVE" | "YELLOW" | "RED" | "SPECTATOR"`).
- **Verification step**: capture the Path A/B decision in the Dev Agent Record before writing FE code. If Path B is chosen, document the BE schema extension in the BE Tasks list AND extend the scope-fence allowlist (AC16) accordingly.

**Editor extraction pattern**: same as AC13 — extract the picker UI to `FE/src/components/rooms/LeaderTransferPicker.tsx` so Jest discovers its tests. The route file at `FE/app/rooms/[id]/settings/transfer-leadership.tsx` is a thin wrapper (same shape as AC13's wrapper).

**Settings entry row**: `FE/app/rooms/[id]/settings.tsx` MUST gain a new entry row pointing to `/rooms/{id}/settings/transfer-leadership`, placed **after** the cap-editor row from AC13. Visible to **leader only** (use the leader-detection conditional render) — non-leaders never see the entry. Rationale: a non-leader has no recourse on this surface; surfacing it would create read-only dead-ends.

PRD: FR-8.5.6. UX: ux-design-specification.md:1136-1155 (D5 Plate System).

### AC15 — Source file placement + service organization (PROJECT STRUCTURE)

**Given** package-by-feature is the project convention (project-context.md:176)
**When** the dev agent creates new BE source files
**Then** the placement MUST be:

| File | Package | Rationale |
|------|---------|-----------|
| `Room.java` (extend) | `com.yeosal.api.room` | Existing entity — add fields + getters/setters |
| `RoomMemberCapService.java` (new) | `com.yeosal.api.room` | Cap is a room-domain attribute; same package as `Room` and `RoomService` |
| `RoomMemberCapController.java` (new) | `com.yeosal.api.room` | Mounts at `/api/v1/rooms/{id}/members/cap` |
| `UpdateMemberCapRequest.java` (new) | `com.yeosal.api.room` | Inline `record` inside controller is also acceptable per `RoomController.JoinRequest` precedent |
| `TransferLeadershipService.java` (new) | `com.yeosal.api.room` | Cross-domain (touches room + room_members + survival_state) but the leader-of-record concept belongs to room domain |
| `TransferLeadershipController.java` (new) | `com.yeosal.api.room` | Mounts at `/api/v1/rooms/{id}/transfer-leadership` |
| `TransferLeadershipRequest.java` (new) | `com.yeosal.api.room` | Same precedent as cap request |
| `IneligibleLeaderException.java` (new) | `com.yeosal.api.room` | Domain exception (mirrors `room/`-domain placement of all room exceptions) |
| `LeadershipChangePayload.java` (new) | `com.yeosal.api.room` | Realtime payload — keep next to the service that publishes it |
| `RoomService.java` (refactor) | `com.yeosal.api.room` | `requireRoom` visibility flip + lazy promotion |
| `ApiExceptionHandler.java` (extend) | `com.yeosal.api.common` | Add the `IneligibleLeaderException` handler |
| `RealtimePublisher.java` (extend) | `com.yeosal.api.realtime` | Add `publishLeadershipChange` method |
| V13 SQL | `BE/src/main/resources/db/migration/` | Smallest free integer = 13 |

**Service consolidation note:** The dev agent MAY collapse `RoomMemberCapService` and `TransferLeadershipService` into a single `RoomLeaderActionsService` if that reads cleaner. The story file uses two names for documentation precision but does not require two separate `@Service` classes. If consolidated, name the merged service `RoomLeaderActionsService` and place it at `com.yeosal.api.room.RoomLeaderActionsService`.

**Anti-pattern (DO NOT IMPLEMENT):**
- Placing `RoomMemberCapService` in `com.yeosal.api.survival` because Story 5.1 put `RoomRuleService` there. The rule is a survival-domain concern (drives `RulePresetEvaluator`); the cap is a room-domain concern (`rooms.max_members`).
- Adding `transferLeadership` as a method on the existing `RoomService` directly. That's a 600+ line class already; the cap + transfer logic deserve their own services for testability + readability.
- Extracting a `LeaderActionsModule` annotation or `@Profile`-gated subsystem. The project uses package-by-feature, not module annotations.

### AC16 — Brand-voice + scope-fence (CI GATE)

**Given** the Story 5.2 PR diff
**When** `tools/brand-voice-lint.ts` + verify pipeline run
**Then**:

1. **Brand-voice HARD violations = 0.** All Korean copy strings (BE + FE) MUST pass — none of `벌금`, `실패`, `패배`, `낙오`, `탈락`, `꼴찌`, `손해` appear. The cap-edit preview literal "변경된 정원은 다음 달 1일부터 적용됩니다." MUST pass. The transfer toasts MUST avoid the AVOID lexicon (e.g., DO NOT write "탈락한 멤버는 방장이 될 수 없어요." — the word `탈락` IS in the avoid lexicon; the AC14 step-5 toast uses "지금은 양도가 어려운 상태예요. 다시 확인해 주세요." instead). Brand-voice review the entire copy table before commit.
2. **Scope fence verified by `git diff --stat origin/main`**: the diff MUST touch ONLY:
   - `BE/src/main/java/com/yeosal/api/room/Room.java` (add 2 fields + 2 getter/setter pairs)
   - `BE/src/main/java/com/yeosal/api/room/RoomService.java` (`requireRoom` visibility flip + lazy promotion helper)
   - `BE/src/main/java/com/yeosal/api/room/RoomMemberCapService.java` (new)
   - `BE/src/main/java/com/yeosal/api/room/RoomMemberCapController.java` (new)
   - `BE/src/main/java/com/yeosal/api/room/UpdateMemberCapRequest.java` (new — or inline record)
   - `BE/src/main/java/com/yeosal/api/room/TransferLeadershipService.java` (new)
   - `BE/src/main/java/com/yeosal/api/room/TransferLeadershipController.java` (new)
   - `BE/src/main/java/com/yeosal/api/room/TransferLeadershipRequest.java` (new — or inline record)
   - `BE/src/main/java/com/yeosal/api/room/IneligibleLeaderException.java` (new)
   - `BE/src/main/java/com/yeosal/api/room/LeadershipChangePayload.java` (new)
   - `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` (add `publishLeadershipChange`)
   - `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` (add `IneligibleLeaderException` handler)
   - `BE/src/main/resources/db/migration/V13__rooms_pending_max_members.sql` (new)
   - `BE/src/test/java/com/yeosal/api/room/RoomMemberCapServiceTest.java` (new)
   - `BE/src/test/java/com/yeosal/api/room/RoomMemberCapControllerTest.java` (new)
   - `BE/src/test/java/com/yeosal/api/room/RoomMemberCapPromotionIT.java` (new — opt-in Testcontainers via `@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")`)
   - `BE/src/test/java/com/yeosal/api/room/TransferLeadershipServiceTest.java` (new)
   - `BE/src/test/java/com/yeosal/api/room/TransferLeadershipControllerTest.java` (new)
   - `BE/src/test/java/com/yeosal/api/room/RoomServiceTest.java` (extend — `requireRoom` lazy-promotion unit case)
   - `BE/src/test/java/com/yeosal/api/room/V13MigrationIT.java` (new — opt-in Testcontainers, asserts V13 columns + CHECK constraint shape)
   - `FE/src/api/rooms.ts` (extend — 2 fields on `Room` + 2 new functions + 2 new vars types)
   - `FE/src/lib/query/hooks/rooms.ts` (extend — 2 new hooks)
   - `FE/app/rooms/[id]/settings.tsx` (add 2 entry rows: cap + transfer)
   - `FE/app/rooms/[id]/settings/cap.tsx` (new — thin wrapper)
   - `FE/app/rooms/[id]/settings/transfer-leadership.tsx` (new — thin wrapper)
   - `FE/src/components/rooms/RoomMemberCapEditor.tsx` (new — extracted editor)
   - `FE/src/components/rooms/LeaderTransferPicker.tsx` (new — extracted picker)
   - `FE/src/api/__tests__/rooms.cap.test.ts` (new — wire test for cap endpoint)
   - `FE/src/api/__tests__/rooms.transfer.test.ts` (new — wire test for transfer endpoint)
   - `FE/src/lib/query/hooks/__tests__/rooms.leader.test.tsx` (new — both new hooks)
   - `FE/src/components/rooms/__tests__/RoomMemberCapEditor.test.tsx` (new)
   - `FE/src/components/rooms/__tests__/LeaderTransferPicker.test.tsx` (new)
   - `_bmad-output/implementation-artifacts/sprint-status.yaml` (status flips + comment header)
   - `_bmad-output/implementation-artifacts/5-2-member-cap-edit-leader-transfer.md` (this file)
   - (AC14 Path B only) `BE/src/main/java/com/yeosal/api/room/RoomService.java` `MemberSummary` extension + `FE/src/api/rooms.ts` `RoomMember` interface extension — extend the allowlist by capturing this in the Dev Agent Record before commit.

3. **ZERO changes to:**
   - `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java` (Story 5.1's class — leave alone; AC3 shared-helper option requires touching it, in which case the diff allowlist is amended at PR time and called out in the PR body)
   - `BE/src/main/java/com/yeosal/api/survival/RoomRuleVersionRepository.java` (rule storage unchanged)
   - `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java` (read site untouched)
   - `BE/src/main/java/com/yeosal/api/survival/SurvivalStateEvaluatorJob.java` (cron/scheduler unchanged)
   - `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java` (regex already permits `survival` token)
   - `BE/src/main/java/com/yeosal/api/room/chat/*.java` (no chat broadcast in Story 5.2 — leadership change is realtime-only via `/topic/.survival`)
   - `FE/src/theme/tokens.json` (D5.plate already exists at line 187 per Story 5.1 verification)
   - `FE/src/lib/query/keys.ts` (no new `qk.*` key needed — piggy-backs on `qk.rooms`)
   - Any auto-generated tokens file (`BE/build/generated/sources/tokens/**`)

### AC17 — Test coverage matrix

**Given** the implementation is complete
**When** the verify pipeline runs
**Then** the following test counts MUST be net-additive (delta vs `origin/main`):

| Test file | Cases | Layer | Notes |
|-----------|-------|-------|-------|
| `RoomMemberCapServiceTest.java` | at least 8 | BE unit (Mockito) | happy upsert (no prior pending), happy upsert (overwriting prior pending), leader-only 403 (via `RoomService.requireLeader`), missing room 404, out-of-range cap 400 (under), out-of-range cap 400 (over), nextMonth boundary at 4/30 23:59 KST → "2026-05", nextMonth boundary at 5/1 02:00 KST → "2026-06" |
| `RoomMemberCapControllerTest.java` | at least 5 | BE WebMvcTest slice | PATCH 200 happy, PATCH 403 non-leader, PATCH 400 missing maxMembers, PATCH 400 out-of-range, PATCH 404 unknown room |
| `RoomMemberCapPromotionIT.java` | at least 2 | BE Testcontainers IT (opt-in `yeosal.boot-smoke`) | full-stack — pending set + April requireRoom holds + May requireRoom promotes; idempotency on second May requireRoom |
| `TransferLeadershipServiceTest.java` | at least 9 | BE unit (Mockito) | happy transfer (target=ACTIVE), happy transfer (target=YELLOW), leader-only 403, missing room 404, target user not found 400, target not a member 400, self-transfer 400, target=RED 409 INELIGIBLE_LEADER, target=SPECTATOR 409 INELIGIBLE_LEADER, atomicity (owner_id + both role flips in one commit), afterCommit emission (realtime called once per commit) |
| `TransferLeadershipControllerTest.java` | at least 4 | BE WebMvcTest slice | POST 200 happy, POST 403 non-leader, POST 400 missing targetUserId, POST 409 ineligible |
| `RoomServiceTest.java` extension | at least 2 new cases | BE unit | `requireRoom` no-op when no pending; `requireRoom` promotes when pending due |
| `V13MigrationIT.java` | at least 2 | BE Testcontainers IT (opt-in) | V13 adds 2 columns with expected types; CHECK constraint rejects half-written state |
| `rooms.cap.test.ts` | at least 2 | FE Jest | `updateMemberCap` envelope unwrap + path correctness; body shape |
| `rooms.transfer.test.ts` | at least 2 | FE Jest | `transferLeadership` envelope unwrap + path correctness; body shape |
| `rooms.leader.test.tsx` | at least 4 | FE Jest (React Testing Library) | `useUpdateMemberCap` invalidates `qk.rooms` on success; `useTransferLeadership` invalidates both `qk.rooms` + `qk.roomMembers(roomId)`; both hooks NO invalidation on 4xx ApiError; both hooks return `ApiError` on failure |
| `RoomMemberCapEditor.test.tsx` | at least 6 | FE Jest | leader sees stepper + Save CTA; non-leader sees read-only; preview literal renders verbatim (byte-identical assertion); Save calls mutation + closes; CTA disabled state honors `pendingMaxMembers ?? maxMembers` baseline; brand-voice grep (no AVOID lexicon strings in rendered output) |
| `LeaderTransferPicker.test.tsx` | at least 6 | FE Jest | leader sees full member list excluding self + RED + SPECTATOR; non-leader sees read-only; confirm modal renders nickname interpolation; confirm calls mutation + closes; 409 INELIGIBLE_LEADER surfaces non-shaming toast (no `탈락` in toast); brand-voice grep (no AVOID lexicon strings) |

**Total net-additive delta:** at least 30 BE cases + at least 20 FE cases. Existing test suites MUST stay green.

**TDD discipline note:** project-context.md:146 ("TDD order is enforced: RED → GREEN → refactor. Do not push BE changes until `./gradlew test` is green.") — each new BE/FE test file SHOULD be created in its failing form before the corresponding source. Commit history may collapse RED → GREEN if the dev agent is operating non-interactively, but the test-first ordering is the discipline.

### AC18 — Brand-voice + no-emoji rule (SOURCE FILES)

**Given** the Story 5.2 source files
**When** any AI agent (or human) lays down a literal
**Then**:

1. **NO emojis** in any source file (BE `.java`, FE `.ts`, FE `.tsx`). project-context.md:191 — "No emojis in source files or docs unless explicitly requested."
2. **Korean copy passes brand-voice AVOID lexicon** (`벌금`, `실패`, `패배`, `낙오`, `탈락`, `꼴찌`, `손해`) for every BE message string AND every FE COPY constant. **Special attention**: the natural-language description of an INELIGIBLE_LEADER case wants to say "탈락한 멤버는 방장이 될 수 없어요." — that's a HARD violation. AC14 step-5 specifies the replacement copy ("지금은 양도가 어려운 상태예요. 다시 확인해 주세요.") — use it byte-identically.
3. **The cap preview literal is byte-identical** to `"변경된 정원은 다음 달 1일부터 적용됩니다."` (matches Story 5.1's literal pattern). Use the ASCII `.` (NOT fullwidth `．`).
4. **DO NOT pre-emit a chat SYSTEM message** for the cap edit. Story 5.4's chat broadcast scope covers rule changes (FR-8.5.8); cap changes are NOT explicitly in Story 5.4's AC (epics.md:790-804). If the team later decides cap changes also warrant a chat broadcast, that's a Story 5.4 scope decision, NOT a Story 5.2 pre-emption. Pre-wiring would create a half-shipped feature (Story 5.4 also adds the brand-voice copy + the realtime invalidation contract).
5. **DO NOT pre-emit a chat SYSTEM message** for the leader transfer. There is no PRD or epics line that mandates this; the realtime `/topic/.survival` LeadershipChange frame is the canonical notification channel. A chat row would duplicate the signal.

## Tasks / Subtasks

- [x] **Task 0 — Pre-flight (no code yet)**
  - [x] Confirm Story 5.1 is `done` in `sprint-status.yaml`. Re-read sections 1.4 (V11) and the most recent migration file under `BE/src/main/resources/db/migration/` to confirm the next free integer is 13.
  - [x] Grep `FE/src/lib/query/hooks/` for an existing roster hook returning per-member `survivalStatus` (informs AC14 Path A vs Path B decision). Capture the finding as a one-line note in the Dev Agent Record before writing any code.
- [x] **Task 1 — BE V13 migration (AC2)**
  - [x] Create `BE/src/main/resources/db/migration/V13__rooms_pending_max_members.sql` with the AC2 SQL.
  - [x] (Verify locally) `ls BE/src/main/resources/db/migration/ | sort | tail -3` shows V11, V12, V13 in order.
- [x] **Task 2 — BE entity edit (AC4)**
  - [x] Add `pendingMaxMembers: Short` + `pendingMaxMembersEffectiveFromMonth: String` fields with `@Column` mapping to `Room.java`.
  - [x] Add corresponding public getter/setter pairs.
  - [x] Do NOT modify `prePersist` clamp logic.
- [x] **Task 3 — BE RoomService refactor (AC5)**
  - [x] Promote `RoomService.requireRoom` from `private` to `public` + add the AC5 JavaDoc.
  - [x] Add private `promotePendingCapIfDue(Room)` helper using injected `Clock` + KST zone.
  - [x] Refactor `requireRoom` body to call the promotion helper before returning.
  - [x] Resolve Trap #13 readOnly-transaction risk (REQUIRES_NEW propagation vs scheduler vs explicit caller writability). Document the chosen approach in the Dev Agent Record.
- [x] **Task 4 — BE Member Cap service + controller (AC1, AC4, AC8)**
  - [x] Create `RoomMemberCapService` in `com.yeosal.api.room`. Inject `RoomService`, `Clock`.
  - [x] Implement `updateMemberCap(User, long, int)` returning `RoomService.RoomSummary` per AC4.
  - [x] Implement private `nextMonthKST()` helper (or reuse Story 5.1's via a static import — see AC3).
  - [x] Create `RoomMemberCapController` `@RestController @RequestMapping("/api/v1/rooms")`.
  - [x] `@PatchMapping("/{id}/members/cap")` calls `updateMemberCap` — auth via `CurrentUser.require(auth)`.
  - [x] Create `UpdateMemberCapRequest` record per AC8 (or inline inside the controller).
- [x] **Task 5 — BE Transfer Leadership service + controller (AC6, AC9)**
  - [x] Create `TransferLeadershipService` in `com.yeosal.api.room`. Inject `RoomService`, `RoomMemberRepository`, `SurvivalStateRepository`, `UserRepository`, `RealtimePublisher`.
  - [x] Implement `transferLeadership(User, long, long)` returning `RoomService.RoomSummary` with the AC6 10-step flow.
  - [x] Register the `afterCommit` realtime emission per AC9 (mirror `RoomService.publishAutoLeaveAfterCommit:585-609` pattern).
  - [x] Create `TransferLeadershipController` `@RestController @RequestMapping("/api/v1/rooms")` with `@PostMapping("/{id}/transfer-leadership")`.
  - [x] Create `TransferLeadershipRequest` record per AC7.
- [x] **Task 6 — BE IneligibleLeaderException + handler (AC10)**
  - [x] Create `IneligibleLeaderException extends RuntimeException` in `com.yeosal.api.room`.
  - [x] Add `@ExceptionHandler(IneligibleLeaderException.class)` to `ApiExceptionHandler` mapping to 409 CONFLICT with code `INELIGIBLE_LEADER`.
- [x] **Task 7 — BE LeadershipChangePayload + RealtimePublisher extension (AC9)**
  - [x] Create `LeadershipChangePayload` record in `com.yeosal.api.room`.
  - [x] Add `publishLeadershipChange(long, LeadershipChangePayload)` method to `RealtimePublisher`.
- [x] **Task 8 — BE RoomSummary extension (AC11)**
  - [x] Add `pendingMaxMembers: Integer` + `pendingMaxMembersEffectiveFromMonth: String` to `RoomService.RoomSummary` record.
  - [x] Update `from(Room)` factory to read from the new entity fields.
- [x] **Task 9 — BE tests (AC17)**
  - [x] `RoomMemberCapServiceTest.java` — at least 8 unit cases (Mockito).
  - [x] `RoomMemberCapControllerTest.java` — at least 5 WebMvcTest slice cases.
  - [x] `RoomMemberCapPromotionIT.java` — at least 2 Testcontainers IT cases, opt-in via `@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")`.
  - [x] `TransferLeadershipServiceTest.java` — at least 9 unit cases (Mockito).
  - [x] `TransferLeadershipControllerTest.java` — at least 4 WebMvcTest slice cases.
  - [x] `RoomServiceTest.java` — at least 2 new lazy-promotion cases.
  - [x] `V13MigrationIT.java` — at least 2 Testcontainers cases asserting schema shape + CHECK constraint.
- [x] **Task 10 — FE API client (AC11, AC12)**
  - [x] Extend `FE/src/api/rooms.ts` `Room` interface with 2 nullable fields.
  - [x] Add `updateMemberCap` + `transferLeadership` functions + corresponding `UpdateMemberCapVars` / `TransferLeadershipVars` types.
- [x] **Task 11 — FE query hooks (AC12)**
  - [x] Append `useUpdateMemberCap` + `useTransferLeadership` to `FE/src/lib/query/hooks/rooms.ts`.
- [x] **Task 12 — FE Member Cap Editor screen (AC13)**
  - [x] Create `FE/src/components/rooms/RoomMemberCapEditor.tsx` (extracted editor).
  - [x] Create `FE/app/rooms/[id]/settings/cap.tsx` (thin wrapper with `<SubModeProvider subMode="plate">`).
  - [x] Implement leader-detection via `useRoomsQuery()` + `useAuth().user`.
  - [x] Render current-cap summary + pending line (when applicable) + stepper + preview literal + Save CTA + non-leader read-only fallback.
  - [x] Mount the verbatim preview literal `"변경된 정원은 다음 달 1일부터 적용됩니다."`.
- [x] **Task 13 — FE Leader Transfer screen (AC14)**
  - [x] Decide AC14 Path A vs Path B (based on Task 0 grep finding).
  - [x] If Path B: extend BE `RoomService.MemberSummary` with `survivalStatus` field + extend FE `RoomMember` interface in lock-step (update AC16 allowlist accordingly).
  - [x] Create `FE/src/components/rooms/LeaderTransferPicker.tsx`.
  - [x] Create `FE/app/rooms/[id]/settings/transfer-leadership.tsx` (thin wrapper).
  - [x] Implement member-list filtering (exclude self + RED + SPECTATOR).
  - [x] Confirm modal with nickname interpolation.
  - [x] Non-leader read-only fallback (no row tapability; eligibility-display read-only).
- [x] **Task 14 — FE settings page entry rows (AC13, AC14)**
  - [x] Add cap-edit `Pressable` row to `FE/app/rooms/[id]/settings.tsx` after the existing rule-edit row (AC13).
  - [x] Add transfer-leadership `Pressable` row, leader-conditional render (AC14).
- [x] **Task 15 — FE tests (AC17)**
  - [x] Create `FE/src/api/__tests__/rooms.cap.test.ts` (at least 2 cases).
  - [x] Create `FE/src/api/__tests__/rooms.transfer.test.ts` (at least 2 cases).
  - [x] Create `FE/src/lib/query/hooks/__tests__/rooms.leader.test.tsx` (at least 4 cases).
  - [x] Create `FE/src/components/rooms/__tests__/RoomMemberCapEditor.test.tsx` (at least 6 cases).
  - [x] Create `FE/src/components/rooms/__tests__/LeaderTransferPicker.test.tsx` (at least 6 cases).
- [x] **Task 16 — Verify pipeline**
  - [x] `tools/brand-voice-lint.ts` returns 0 HARD violations (AC16, AC18).
  - [x] BE Gradle test green: `cd BE && ./gradlew test`.
  - [x] FE Jest green: `cd FE && npx jest --runInBand --no-watchman`.
  - [x] FE typecheck no NEW errors: `cd FE && npx tsc --noEmit` (2 pre-existing `FriendsTodayPager` errors per Story 4.1 baseline are expected).
  - [x] Touched FE files ESLint clean: `cd FE && npx eslint <touched paths>`.
  - [x] `git diff --check HEAD` clean.
  - [x] Scope-fence grep: confirm no `SurvivalStateService` / `JwtChannelInterceptor` / `chat/**` / `tokens.json` changes.
  - [x] Manual smoke (VERIFY-N): deferred to PR-open per Story 5.1 precedent — log in as leader, set cap=20 on dev DB, confirm pending state surfaces; log in as second member, attempt transfer, walk through 400/409 toast cases on a sim build.

### Review Findings

- [x] [Review][Patch] Apply the existing 24-hour RED cooldown mask when extending `MemberSummary.survivalStatus`; the new `/rooms/{id}/members` field currently exposes raw RED state to ordinary members and bypasses the privacy-filtered survival roster contract. [BE/src/main/java/com/yeosal/api/room/RoomService.java:187]
- [x] [Review][Patch] Refresh or evict the outer persistence-context `Room` after `REQUIRES_NEW` cap promotion; a second `findById` can return the stale managed entity and a later dirty-check can restore the old cap and pending fields. [BE/src/main/java/com/yeosal/api/room/RoomService.java:433]
- [x] [Review][Patch] Route every authoritative cap read through promotion, especially `/rooms` listing and invite admission; `myRooms` stays stale and `joinByCode` can admit members above a due reduced cap. [BE/src/main/java/com/yeosal/api/room/RoomService.java:169]
- [x] [Review][Patch] Serialize cap edits and leadership changes on the room row; concurrent transfers can leave two `OWNER` membership rows, and a former leader can stage a cap edit after a competing transfer commits. [BE/src/main/java/com/yeosal/api/room/TransferLeadershipService.java:67]
- [x] [Review][Patch] Lock or conditionally update the target survival row during leadership transfer so a concurrent YELLOW-to-RED transition cannot promote an ineligible leader. [BE/src/main/java/com/yeosal/api/room/TransferLeadershipService.java:83]
- [x] [Review][Patch] Add a DB CHECK for `pending_max_members_effective_from_month` format before relying on lexicographic month comparison. [BE/src/main/resources/db/migration/V13__rooms_pending_max_members.sql:5]
- [x] [Review][Patch] Use `findByRoomIdFetchingUser` for the new survival-state batch to avoid adding one lazy user query per member. [BE/src/main/java/com/yeosal/api/room/RoomService.java:193]
- [x] [Review][Patch] Add integration coverage through the real `RoomService.requireRoom` boundary, read-only caller path, invite admission path, concurrent transfer invariant, and realtime commit/rollback hook; the current IT invokes the promoter directly and the checked-off `RoomServiceTest` promotion cases were not added. [BE/src/test/java/com/yeosal/api/room/RoomMemberCapPromotionIT.java:71]
- [x] [Review][Patch] Return to settings with `router.back()` after successful cap save and leadership transfer as required by AC13.7 and AC14.5. [FE/src/components/rooms/RoomMemberCapEditor.tsx:106]
- [x] [Review][Patch] Preserve informative API errors by falling back to `toast.error(error.message)` instead of replacing unmatched errors with a fixed network message. [FE/src/components/rooms/RoomMemberCapEditor.tsx:109]
- [x] [Review][Patch] Render non-leader transfer rows as read-only views and add modal accessibility isolation for the irreversible confirmation. [FE/src/components/rooms/LeaderTransferPicker.tsx:143]
- [x] [Review][Patch] Reset cap draft and pending transfer target when `roomId` changes so router reuse cannot submit room-A state against room B. [FE/src/components/rooms/RoomMemberCapEditor.tsx:59]
- [x] [Review][Patch] Extend FE tests for navigation, error toasts, pending-baseline synchronization, non-leader read-only rows, modal semantics, and room-id changes. [FE/src/components/rooms/__tests__/RoomMemberCapEditor.test.tsx:109]
- [x] [Review][Patch] Reconcile the implementation record after fixes: it currently claims read-only promotion safety and checked-off `RoomServiceTest` lazy-promotion coverage that the reviewed code does not provide. [_bmad-output/implementation-artifacts/5-2-member-cap-edit-leader-transfer.md:748]

## Dev Notes

### Context — what Story 5.1 already shipped that 5.2 leverages

Story 5.1 (PR #86, merged 2026-06-02, squash 2e397fb) delivered:
- `RoomService.requireLeader(Room, User)` is now `public` (RoomService.java:420-424). Story 5.2 is the **second** consumer (Story 5.1 was the first wiring; Stories 5.3/5.6 will be third/fourth).
- `RoomRuleService` precedent for next-month-only edits with KST calendar-month boundary computation. **REUSE the helper** (AC3) — do NOT re-derive.
- `RoomService.create` + `DefaultRoomMigrationRunner.seedRoom` mint a default current-month rule row for every fresh room (Story 5.1 review patch). This means every room you load in Story 5.2 ITs will already have a `room_rule_versions` row — useful for the lazy-promotion happy path.
- `RoomRuleEditor.tsx` + `/settings/rule.tsx` thin-wrapper pattern (extracted-for-Jest precedent). **REPLICATE this exact pattern** for both `RoomMemberCapEditor.tsx` and `LeaderTransferPicker.tsx`.
- D5 Plate sub-mode tokens at `FE/src/theme/tokens.json:187` are wired. **NO new tokens needed.**

### Architecture deviation — V13 migration is intentionally new (NOT a 5.1 regression)

Story 5.1's "no new migration" rule was specific to that story's scope: rule writes against the V11-shipped `room_rule_versions` table. Story 5.2's scope is genuinely new (pending cap state with effective-month sidecar) and the cleanest persistence shape is the V13 columns described in AC2. The V13 migration:
- Reuses the V11 `chk_rooms_max_members between 2 and 30` precedent for the new pending CHECK.
- Adds a paired-state CHECK constraint (`chk_rooms_pending_cap_consistency`) that prevents half-written state via direct SQL (defense in depth).
- Is idempotent (`add column if not exists` + `drop constraint if exists`).
- Has no backfill — existing `rooms` rows get NULL on both columns (correct "no pending edit" state).
- Will require a `Post-merge user action` mention in the PR body per project-context.md:229 ("Any change with significant operational impact (migrations, security, auth wiring) must include a 'Post-merge user action' section in the PR body").

### Architecture deviation — GET endpoint NOT separately exposed; piggy-backs on `RoomSummary`

Architecture §6.4 enumerates `PATCH /rooms/{id}/members/cap → RoomDto` but NOT a corresponding GET. Story 5.1 introduced a complementary `GET /rooms/{id}/rule` because the FE editor needed a dedicated pre-fill read (current + pending shape). Story 5.2 takes the **opposite** path: the pending fields ride on the existing `RoomSummary` (returned by `GET /rooms` and `POST /rooms`), so the FE editor reads via `useRoomsQuery()` without a new request. Rationale:
- `useRoomsQuery` is already loaded eagerly on most screens (tabs, settings).
- The pending shape is simple (2 nullable scalars), not warranting a dedicated DTO.
- Avoids endpoint surface inflation.

After merge, the Architecture §6.4 table will read (entry 813):
```
| PATCH | /rooms/{id}/members/cap | { maxMembers } | RoomDto (with pendingMaxMembers + pendingMaxMembersEffectiveFromMonth) | room leader |
```
A follow-up doc PR will update `architecture.md:813` to clarify the extended DTO shape.

### Planning ambiguity — FR-8.5.5 (DELETE member) deferral

The epics.md FR Coverage Map (line 1169) maps FR-8.5.1, .2, .3, .4, .5, .6 collectively to "Stories 5.1, 5.2 (rule versioning + member cap + transfer + creator-becomes-leader)". This implies FR-8.5.5 (leader-driven member removal via `DELETE /rooms/{id}/members/{userId}`) was intended for Story 5.1 or 5.2. However:

1. **Story 5.1's epics ACs (lines 710-734)** do NOT mention DELETE; Story 5.1 was scoped strictly to rule edits.
2. **Story 5.2's epics ACs (lines 736-756)** also do NOT mention DELETE; the story title is "Member-cap edit + leader transfer" and the ACs cover only those two scopes.
3. **Architecture §6.4 endpoint table (lines 802-817)** does NOT list a `DELETE /rooms/{id}/members/{userId}` endpoint — the closest existing endpoint is `DELETE /rooms/{id}/members/me` (self-leave), which is shipped by Story 1.1.
4. **Story 5.1's "Out of Scope" list (`5-1-...md:635`)** explicitly deferred member removal to "Story 5.2 (FR-8.5.5)" — but the present story file does NOT extend its ACs to cover it, because doing so would triple the story's scope and exceed what epics line 736-756 specified.

**Resolution adopted for Story 5.2:** FR-8.5.5 is OUT OF SCOPE for this story. The planning ambiguity is documented; a follow-up Story (likely a Story 5.5 or a `bmad-correct-course` action) will own the DELETE endpoint with its own ACs covering (a) leader-only auth, (b) the removed-user's record-archive preservation per PRD FR-8.5.5, and (c) realtime fan-out semantics.

Action for the dev agent: do NOT implement `DELETE /rooms/{id}/members/{userId}` in this story even if a future review reads the FR-coverage-map line as an implicit AC. The story file's explicit AC list (AC1-AC18 above) is the binding contract.

### Implementation trap #1 — Do NOT compute nextMonth from `EntryDateResolver`

Mirrors Story 5.1 Trap #1. The daily-mission 06:00 KST boundary is wrong for calendar-month cap scoping. A leader editing at `2026-05-01 02:00 KST` cannot retroactively change the May cap; `nextMonth` MUST be `"2026-06"`.

### Implementation trap #2 — Storage path choice (rule_payload vs V13 columns)

The story RECOMMENDS V13 columns (AC2) over extending `room_rule_versions.rule_payload`. Reasons:
- Avoids coupling cap policy to rule policy schema.
- No need to refactor Story 5.1's PATCH /rule merge semantics.
- The Room entity gains a clear sidecar (paired columns + CHECK constraint).
- V13 ceremony is minimal (one ALTER TABLE).

If the dev agent strongly prefers the rule_payload extension path:
- They MUST refactor `RoomRuleService.updateRule` to preserve any pre-existing `maxMembers` key during the upsert merge (currently 5.1 writes `{preset, weekendInclude}` and would clobber the cap key).
- The lazy promotion is then read-side computation (no mutation of `rooms.max_members`), which makes `Room.maxMembers` semantically the "base" rather than "effective" cap — invasive across many call sites that currently read `room.getMaxMembers()`.
- Architecture §6.4 wire shape still requires `RoomDto.maxMembers` to reflect EFFECTIVE cap; the FE expects an authoritative number.

**Bottom line: V13 columns is the simpler, more localized change. Diverge from this recommendation only with a written justification in the Dev Notes.**

### Implementation trap #3 — Self-transfer guard must come BEFORE membership check

When the leader transfers to themselves (`targetUserId == me.id`), AC6 step-5 specifies a 400 VALIDATION with "이미 본인이 방장입니다.". This check MUST run BEFORE the membership lookup (step-6) — otherwise the membership lookup would succeed (the leader is a member) and the eligibility check (step-7) would also pass (leader is ACTIVE), and we'd self-transfer with no error. Order matters for both correctness and clarity-of-error-message.

### Implementation trap #4 — Atomicity of owner_id + dual role flip

`rooms.owner_id` + previous-leader's `RoomMember.role` + new-leader's `RoomMember.role` MUST all flip in the SAME `@Transactional` boundary. Any of:
- Flipping `owner_id` then crashing before role flips → orphaned roles (target's role=MEMBER but owner_id=target).
- Flipping target's role then crashing before previous-leader's flip → two OWNER rows.
- Flipping previous-leader's role then crashing before target's flip → zero OWNER rows.

`@Transactional` ensures all three writes either commit together or roll back together. Do NOT extract a sub-method without preserving the propagation. The realtime emission MUST run `afterCommit` only — emitting before commit would announce a transfer that may roll back.

### Implementation trap #5 — RoomMember.role flip needs entity setters

`RoomMember.role` field is annotated `@Enumerated(EnumType.STRING)`. To flip via JPA dirty-check, `RoomMember.java` needs a `public setRole(RoomRole role)` method. Confirm during implementation — if absent, add it as part of Task 5. The setter is package-private-or-public per project conventions.

### Implementation trap #6 — SurvivalState lookup may be absent

`SurvivalStateRepository.findByRoomIdAndUserId(roomId, userId)` returns `Optional<SurvivalState>`. V11 step (13) backfilled rows for every existing room_member at migration time, and `SurvivalStateService.initializeOnJoin` creates a row for every new join atomically. So a member without a survival_state row is a defensive-only case (data corruption, migration drift, manual SQL). The AC6 step-7 specifies:
- Absent row → 409 `INELIGIBLE_LEADER` (with message "대상의 상태를 확인할 수 없어요.").
- This is safer than throwing 500 — the leader sees a recoverable error and can retry.

### Implementation trap #7 — JwtChannelInterceptor regex already permits `survival` token

The existing topic regex at `JwtChannelInterceptor:41` is:
```
^/topic/rooms\.(\d+)\.(chat|members|survival|points|kudos)$
```

The Story 5.2 LeadershipChange emits on `/topic/rooms.{id}.survival` — already permitted. **NO regex change needed**. Resist the temptation to add a `leadership` token (would orphan Story 5.3's symmetric `AUTO_ELIMINATION` emission, which AC9 reuses).

### Implementation trap #8 — RealtimePublisher.sendTopic swallows broker errors

`RealtimePublisher.sendTopic` (line 128-135) catches `RuntimeException` and warn-logs. This is intentional per architecture §4.14 — broker failures must NEVER roll back the actor's primary write transaction. Do NOT add a `throws` to `publishLeadershipChange` or wrap the call in a try/catch at the caller. The post-commit hook + the swallowed-error pattern together guarantee the transfer commits even if STOMP is down.

### Implementation trap #9 — FE Room interface drift (BE adds fields, FE must match)

When the BE extends `RoomSummary` with two new fields (AC11), Jackson serializes them in every `/rooms` response. The FE TypeScript `Room` interface at `FE/src/api/rooms.ts:19-31` MUST be extended in the same PR to declare the new fields — otherwise FE consumers will see `room.pendingMaxMembers` as `unknown` and the strict TypeScript check would silently miss the new field.

### Implementation trap #10 — useRoomsQuery is shared cache; mutation invalidation ripples broadly

`useRoomsQuery()` is used by many screens (tabs, settings, room detail). Invalidating `qk.rooms` on `useUpdateMemberCap.onSuccess` triggers refetches on every mounted consumer. This is fine (refetch is cheap; the FE renders the new pending field everywhere). Do NOT try to scope-down the invalidation to just the cap-editor screen — that would require a new query key, which AC12 explicitly forbids.

### Implementation trap #11 — Self-leader detection requires `useAuth().user`

The leader-detection conditional renders in AC13 + AC14 require `user.id` (from `useAuth()`) AND `room.ownerId`. **Both must be non-null** before the leader check evaluates. Defensive default: when `user == null` (auth not yet hydrated), render as if non-leader (safer UX — show read-only view briefly during auth bootstrap rather than briefly flashing the editor).

### Implementation trap #12 — Brand-voice AVOID lexicon trap in transfer toasts

When writing the transfer-leadership error toasts (AC14), the natural phrasing for "the target is RED/SPECTATOR" wants to say "탈락한 멤버는 방장이 될 수 없어요." The substring `탈락` is in the AVOID lexicon (`벌금`, `실패`, `패배`, `낙오`, `탈락`, `꼴찌`, `손해`). This is a HARD brand-voice-lint violation. AC14 step-5 specifies the verbatim replacement: "지금은 양도가 어려운 상태예요. 다시 확인해 주세요." Use it byte-identically. Run `tools/brand-voice-lint.ts` before committing.

### Implementation trap #13 — Lazy promotion side-effect inside `@Transactional(readOnly = true)`

`RoomService` has multiple `@Transactional(readOnly = true)` methods (e.g., `myRooms` line 167, `members` line 175, `todayForRoom` line 200). The lazy promotion in `requireRoom` (AC5) mutates `Room.maxMembers` via dirty-check — but a `readOnly = true` transaction will throw `TransientObjectException` or silently skip the flush on commit (Hibernate behavior is JPA-vendor-specific; PostgreSQL + Hibernate 6 typically throws).

**Mitigation options:**
- **Option A (recommended):** the lazy promotion runs inside the **calling** transaction's writability. If the caller is `@Transactional(readOnly = true)`, the promotion is a side-effect that MUST be applied via a separate writable `@Transactional(propagation = REQUIRES_NEW)` boundary. Extract `promotePendingCapIfDue` to a `@Transactional(propagation = REQUIRES_NEW)` method on a new helper service (or on `RoomService` itself).
- **Option B (alternative):** the lazy promotion only runs when the caller's transaction is writable; readOnly callers see stale `room.maxMembers` until a subsequent writable call promotes. Track via `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`.
- **Option C (alternative):** the promotion runs at a scheduled monthly job at 06:00 KST on the 1st of each month (no per-call overhead, no readOnly tension). Adds a `@Scheduled` cron + a new test surface.

**Recommendation: Option A.** REQUIRES_NEW is the cleanest way to mutate without leaking to readOnly callers. Wire it via a dedicated `RoomCapPromotionService` (single method) so the propagation contract is explicit.

If the dev finds Option A excessive (introduces a service for one method), Option C is the next-cleanest at the cost of a scheduler. Reject Option B — silent staleness is a footgun.

This trap is the **single most subtle design point in Story 5.2**. The Testcontainers IT in AC17 (`RoomMemberCapPromotionIT`) MUST cover both writable-caller-promotes and readOnly-caller-readOnly-friendly paths. The chosen Option (A vs C) MUST be documented in the Dev Agent Record with the rationale.

### Implementation trap #14 — `currentUser.require(auth)` pattern not `@AuthenticationPrincipal`

Mirrors Story 5.1 Trap #11. Every controller in this codebase uses `User me = currentUser.require(auth)`. Do NOT introduce `@AuthenticationPrincipal Jwt jwt`. Match the existing pattern.

### Architecture decisions traceability

| FR | AC | File |
|----|----|------|
| FR-8.5.1 (Leader = `rooms.owner_id`) | AC1 step 3, AC6 step 3 | `RoomService.requireLeader` (already public) |
| FR-8.5.4 (PATCH /members/cap, next-month-only) | AC1, AC2, AC3, AC4, AC5 | `RoomMemberCapController` + `RoomMemberCapService` + V13 |
| FR-8.5.5 (DELETE /members/{userId}) | OUT OF SCOPE | Future story (see Dev Notes "Planning ambiguity") |
| FR-8.5.6 (POST /transfer-leadership) | AC6, AC7, AC8, AC9, AC10 | `TransferLeadershipController` + `TransferLeadershipService` |
| FR-8.5.8 (Chat broadcast) | OUT OF SCOPE | Story 5.4 (rule broadcast only — cap + transfer NOT enumerated) |
| Architecture §6.4 PATCH /rooms/{id}/members/cap | AC1 | `RoomMemberCapController.PATCH("/{id}/members/cap")` |
| Architecture §6.4 POST /rooms/{id}/transfer-leadership | AC6 | `TransferLeadershipController.POST("/{id}/transfer-leadership")` |
| Architecture V13 (NEW) `rooms.pending_max_members` columns | AC2 | new migration |
| Architecture §597 `RealtimeEvent.LeadershipChange` sealed variant | AC9 | `LeadershipChangePayload` + `publishLeadershipChange` |
| project-context.md:92 (KST day-boundary) | AC3 | `ZoneId.of("Asia/Seoul")` |
| project-context.md:270 (No per-user TZ) | AC3 | hardcoded `Asia/Seoul` |
| project-context.md:227 (V<N>__<slug>.sql) | AC2 | `V13__rooms_pending_max_members.sql` |
| project-context.md:144 (ON CONFLICT idempotency pattern) | AC2 | `add column if not exists` + `drop constraint if exists` |

### Out of scope (DO NOT IMPLEMENT IN THIS STORY)

1. **Leader-driven member removal `DELETE /rooms/{id}/members/{userId}`** — FR-8.5.5 deferred per planning ambiguity (see Dev Notes section above). Future story.
2. **Auto-leader-promotion on RED transition** — Story 5.3.
3. **Rule-change broadcast in chat** — Story 5.4 (rule scope only; cap + transfer NOT enumerated in Story 5.4's ACs).
4. **A new STOMP `leadership` topic regex token** — JwtChannelInterceptor already permits `survival`; use it (AC9).
5. **Chat SYSTEM message on cap edit** — DO NOT pre-emit. Future story scope if/when team decides.
6. **Chat SYSTEM message on leader transfer** — DO NOT pre-emit. The realtime LeadershipChange frame is the canonical channel.
7. **FE STOMP subscriber for LeadershipChange** — accepted gap; refetch-on-focus pattern is sufficient for v1.
8. **Per-member `minDailyGoalDays` next-month-only edits** — NOT in Epic 5 scope; member-side `updateMyMinimum` (RoomService line 353) is immediate-effect and stays unchanged.
9. **`SurvivalStateService` modifications** — read site unchanged.
10. **`SurvivalStateEvaluatorJob` modifications** — scheduler/cron unchanged.
11. **`RulePresetEvaluator` modifications** — Story 5.1's read site untouched.
12. **`tokens.json` modifications** — D5.plate already exists per Story 5.1 verification.
13. **New ApiExceptionHandler mappings** beyond `IneligibleLeaderException` — existing 403 / 400 / 404 mappings cover all other error cases.
14. **New STOMP topic regex permits** — `survival` already permitted by `JwtChannelInterceptor:41`.
15. **GeneratedTokens.java additions** — no new theme tokens needed.
16. **Rule_payload extension with `maxMembers` key** — explicitly rejected in favor of V13 columns (Trap #2).
17. **Scheduled monthly promotion job** — accepted as Trap #13 alternative path; default recommendation is REQUIRES_NEW lazy promotion (Option A).
18. **Analytics SDK telemetry for cap edits or transfers** — Story 8.5 scope; no event taxonomy in v1.
19. **Past-cap history UI / display of historical cap changes** — out of scope; the editor shows only current + pending.

### Project structure notes

- BE files under `BE/src/main/java/com/yeosal/api/room/` (matches package-by-feature; member cap + transfer-leadership are room-domain concerns, NOT survival-domain).
- The V13 migration sits next to V11 + V12 under `BE/src/main/resources/db/migration/`.
- FE files under `FE/src/api/rooms.ts` (extension), `FE/src/lib/query/hooks/rooms.ts` (extension), `FE/app/rooms/[id]/settings/` (new nested routes), `FE/src/components/rooms/` (new extracted editors).
- Tests mirror source layout: `BE/src/test/java/com/yeosal/api/room/*.java` (unit + slice + opt-in IT); `FE/src/api/__tests__/`, `FE/src/lib/query/hooks/__tests__/`, `FE/src/components/rooms/__tests__/`.

### References

- Epics: `_bmad-output/planning-artifacts/epics.md:704-756` (Epic 5 + Story 5.2 ACs), `epics.md:1169` (FR Coverage Map line that conflates FR-8.5.5)
- PRD: `_bmad-output/planning-artifacts/prd.md:401-408` (FR-8.5.1 through FR-8.5.8), `prd.md:205-213` (J5 narrative), `prd.md:305-316` (PRD §6.3 decisions table — leader-elim auto-promote, longest-tenured surviving member)
- Architecture: `_bmad-output/planning-artifacts/architecture.md:593-598` (RealtimePublisher sealed variants including `LeadershipChange`), `architecture.md:597` (LeadershipChange variant), `architecture.md:654-799` (V11 schema), `architecture.md:802-817` (§6.4 endpoint table including lines 813-814 for cap + transfer endpoints), `architecture.md:835` (FR-8.5.* lockstep with §6.4)
- UX: `_bmad-output/planning-artifacts/ux-design-specification.md:1136-1155` (D5 Plate System), `:1404` ("Next-month-only contract: leader 모든 변경(rule / cap)은 다음 달부터 (J5)"), `:1172` ("Settings / Profile / Room Rules — D5 Plate System utility surface")
- project-context: `_bmad-output/project-context.md:92,270` (KST day-boundary), `:191` (no emojis), `:176` (package-by-feature), `:227` (smallest free V<N>)
- Story 5.1: `_bmad-output/implementation-artifacts/5-1-rule-edit-with-next-month-only-application.md` (full ACs, traps, file layout — the canonical precedent for next-month-only patterns + leader chokepoint wiring + D5 Plate sub-mode wrapping + extracted-editor pattern)
- Existing BE code:
  - `BE/src/main/java/com/yeosal/api/room/Room.java` (entity to extend)
  - `BE/src/main/java/com/yeosal/api/room/RoomService.java:402-424` (`requireRoom` + `requireLeader`)
  - `BE/src/main/java/com/yeosal/api/room/RoomController.java` (controller pattern precedent)
  - `BE/src/main/java/com/yeosal/api/room/RoomMember.java` (role setter likely needs adding)
  - `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRepository.java:13` (`findByRoomIdAndUserId`)
  - `BE/src/main/java/com/yeosal/api/survival/SurvivalStatus.java` (enum values)
  - `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java:91-107` (publish-method precedent — `publishPointPoolChange`, `publishKudos`)
  - `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java:41` (topic regex — survival already permitted)
  - `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java:210-220` (`KudosTargetNotEligibleException` mapping pattern for 409 CONFLICT)
- Existing FE code:
  - `FE/src/api/rooms.ts:19-31` (Room interface to extend)
  - `FE/src/api/rooms.ts:67-69` (cap constants `MAX_MEMBERS_MIN`, `MAX_MEMBERS_MAX`, `MAX_MEMBERS_DEFAULT`)
  - `FE/src/lib/query/hooks/rooms.ts` (existing room hooks pattern)
  - `FE/app/rooms/[id]/settings.tsx:105-123` (entry row Pressable pattern)
  - `FE/app/rooms/[id]/settings/rule.tsx` (thin-wrapper route pattern from Story 5.1)
  - `FE/src/components/rooms/RoomRuleEditor.tsx:50-51` (leader-detection pattern via `useRoomsQuery`)
  - `FE/src/providers/SubModeProvider.tsx` (D5.plate wrap)

### Change log

| Date | Author | Change |
|------|--------|--------|
| 2026-06-02 | Maya (context engineer) | Initial context-engineered story file. Story 5.2 second Epic-5 BE+FE wiring; reuses Story 5.1's public `requireLeader` chokepoint + KST calendar-month helper; introduces V13 migration with `rooms.pending_max_members` + `pending_max_members_effective_from_month` columns; transfer-leadership atomic owner_id + dual-role flip with `/topic/.survival` `LeadershipChange` realtime emission; `IneligibleLeaderException` → 409 CONFLICT INELIGIBLE_LEADER via `ApiExceptionHandler` extension; explicit FR-8.5.5 (DELETE member) OUT OF SCOPE deferral with planning-ambiguity write-up; 14 implementation traps catalogued (most subtle: Trap #13 readOnly-transaction lazy-promotion REQUIRES_NEW pattern); 19-item out-of-scope list locking Story 5.3/5.4 deferrals + chat broadcast + STOMP subscriber gap acceptance. |
| 2026-06-02 | rearleg (dev agent) | Implementation complete; flipped ready-for-dev → in-progress → review. V13 migration shipped + Room entity extension + RoomService.requireRoom flip private→public with Option-A REQUIRES_NEW lazy promotion (new RoomCapPromotionService); RoomSummary + MemberSummary records extended additively (AC14 Path B taken — Pre-flight grep confirmed no existing per-room roster hook returns survivalStatus); new RoomMemberCapService + RoomMemberCapController + UpdateMemberCapRequest; new TransferLeadershipService + TransferLeadershipController + TransferLeadershipRequest + IneligibleLeaderException + LeadershipChangePayload; RealtimePublisher.publishLeadershipChange + ApiExceptionHandler mapping; FE Room/RoomMember interface extensions + updateMemberCap/transferLeadership wire + useUpdateMemberCap/useTransferLeadership hooks; FE RoomMemberCapEditor + LeaderTransferPicker (extracted-for-Jest) + thin route wrappers at app/rooms/[id]/settings/{cap,transfer-leadership}.tsx wrapped in D5.plate subMode; settings.tsx gains 2 entry rows (cap-edit visible to all, transfer leader-only conditional). Locked preview literal "변경된 정원은 다음 달 1일부터 적용됩니다." byte-identical (AC18). 409 toast uses "지금은 양도가 어려운 상태예요." (Trap #12 — `탈락` AVOID-lexicon defended). Verifications: BE Gradle ./gradlew test green (524 tests); FE Jest 62 suites / 461 tests / 9 snapshots green (Δ +5 / +21 / +0); FE typecheck only 2 pre-existing FriendsTodayPager errors; brand-voice 0 HARD / 198 WARN (Story 5.1 baseline + new files contribute 0 HARD); scope-fence verified ONLY AC16 allowlist + AC14 Path B (MemberSummary survivalStatus + RoomMember survivalStatus) touched per git status. Opt-in Testcontainers ITs (V13MigrationIT + RoomMemberCapPromotionIT) authored; will run in CI under -Dyeosal.boot-smoke=true. Manual VERIFY-N smoke deferred to PR-open per Story 5.1 / 4.x precedent. |
| 2026-06-03 | Codex (code review) | Review patches applied and story flipped review → done. Fixed RED cooldown privacy masking on `MemberSummary.survivalStatus`; replaced post-promotion second `findById` with `EntityManager.refresh`; routed `myRooms` and `joinByCode` through `requireRoom`; added room-row pessimistic locking for cap edits/transfers and target survival-row locking; added V13 month-format CHECK; switched member survival batch to `findByRoomIdFetchingUser`; expanded BE coverage for `requireRoom`, promotion refresh, invite admission stubs, RED masking, migration format validation, and transfer afterCommit/rollback behavior. FE now calls `router.back()` on cap/transfer success, preserves unmatched API errors via `ApiError.message`, resets room-scoped draft/modal state, renders non-leader transfer rows as read-only views, and marks the irreversible modal as isolated. Targeted verification green: `cd BE && ./gradlew test --tests 'com.yeosal.api.room.*'`; `cd FE && npx jest --runInBand --no-watchman src/components/rooms/__tests__/RoomMemberCapEditor.test.tsx src/components/rooms/__tests__/LeaderTransferPicker.test.tsx src/lib/query/hooks/__tests__/rooms.leader.test.tsx` (22 tests). FE `npx tsc --noEmit` has no new Story 5.2 errors and still stops only on the pre-existing `FriendsTodayPager` 2-error baseline. |

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m]

### Debug Log References

- Pre-flight grep — no existing per-room roster hook returns per-member `survivalStatus`. `useMeSurvivalQuery` is SELF-cross-room (not per-room per-member). `useRoomMembersQuery` returns `RoomMember[]` without status. AC14 Path B (BE `MemberSummary.survivalStatus` + FE `RoomMember.survivalStatus` extension) is required. AC16 allowlist amended in this Dev Agent Record before commit.
- Trap #13 — Option A (REQUIRES_NEW writable boundary) selected. Implemented via new `RoomCapPromotionService` (single method `promotePendingCapIfDue`) annotated `@Transactional(propagation = REQUIRES_NEW)`. Review patch changed the caller-side reconciliation to `EntityManager.refresh(room)` when promotion fires, so the already-managed `Room` instance sees the fresh `max_members` without a stale first-level-cache second lookup.
- AC3 — `nextMonthKST` is duplicated inline in `RoomMemberCapService` instead of promoting `RoomRuleService.nextMonthKST` to a shared static helper (deviation from the AC3 "recommended" path). Rationale: keeps the diff scope-fenced to `room/` package and avoids touching Story 5.1's `survival/RoomRuleService` (AC16 ZERO-changes constraint).
- TransferLeadershipServiceTest needed `@MockitoSettings(strictness = Strictness.LENIENT)` because RED/SPECTATOR/missing-survival branches reject before the leader-membership stub is consumed.
- Three pre-existing RoomService Mockito tests (RoomServiceTest, RoomServiceEvaluationTest, RoomServiceMemberJoinSystemMessageTest) gained the new promotion dependencies (`RoomCapPromotionService`, `EntityManager`) and were patched so invite admission/listing tests exercise the `requireRoom` promotion boundary.
- Review patch verification added targeted coverage for RED cooldown masking, `EntityManager.refresh` after REQUIRES_NEW promotion, `requireRoomForUpdate` row-lock acquisition, target survival-row locking, V13 malformed-month rejection, FE success navigation, default API error toasts, pending-baseline sync, non-leader read-only transfer rows, and room-id state resets.
- Two FE Room-mock fixtures (`useCreateRoom.test.tsx`, `RoomRuleEditor.test.tsx`) added `pendingMaxMembers: null` + `pendingMaxMembersEffectiveFromMonth: null` to satisfy TypeScript strict mode after the `Room` interface extension.

### Completion Notes List

- V13 migration `BE/src/main/resources/db/migration/V13__rooms_pending_max_members.sql` adds 2 columns (`pending_max_members smallint`, `pending_max_members_effective_from_month varchar(7)`) + paired-state CHECK `chk_rooms_pending_cap_consistency` + month-format CHECK `chk_rooms_pending_cap_month_format` (idempotent `add column if not exists` + `drop constraint if exists`). No backfill rows — existing rooms get NULL on both columns, the correct "no pending edit" state.
- `Room.java` extended with 2 boxed-`Short`/`String` fields + paired getter/setter pairs. `prePersist` clamp untouched.
- `RoomService.requireRoom` promoted private → public + JavaDoc'd. Lazy-promotion delegation to new `RoomCapPromotionService` (REQUIRES_NEW), followed by `EntityManager.refresh(room)` on promotion. `/rooms` listing and `joinByCode` now route through this boundary so listing and invite admission see due cap changes.
- `RoomService.RoomSummary` record extended additively with `pendingMaxMembers` + `pendingMaxMembersEffectiveFromMonth` (nullable). `from(Room)` factory pulls the values from the new entity fields.
- `RoomService.MemberSummary` record extended additively with `survivalStatus` (nullable enum) for AC14 Path B. `members(viewer, roomId)` now batch-loads `survival_state` rows with `findByRoomIdFetchingUser`, applies the 24-hour RED cooldown mask for ordinary viewers, and keeps leaders/self views authoritative.
- New `RoomMemberCapService` (Spring `@Service`) + `RoomMemberCapController` (`PATCH /api/v1/rooms/{id}/members/cap`) + `UpdateMemberCapRequest` record (`@NotNull @Min(2) @Max(30) Integer maxMembers`). Service mirrors Story 5.1's `nextMonthKST` helper inline, locks the room row through `RoomService.requireRoomForUpdate`, and returns the current snapshot for idempotent re-edits.
- New `TransferLeadershipService` (Spring `@Service`) + `TransferLeadershipController` (`POST /api/v1/rooms/{id}/transfer-leadership`) + `TransferLeadershipRequest` record (`@NotNull @Positive Long targetUserId`). 10-step flow: requireRoomForUpdate → requireLeader → self-check (BEFORE membership) → users.findById → roomMembers.findByRoomAndUser(target) → locked survival_state ACTIVE/YELLOW gate → leaderMember lookup → atomic owner_id + dual role flip → afterCommit publish → return RoomSummary.
- New `IneligibleLeaderException` + matching `ApiExceptionHandler` mapping → 409 CONFLICT, code `INELIGIBLE_LEADER`. Distinguishes state-precondition failure from 403 FORBIDDEN (caller-not-leader).
- New `LeadershipChangePayload` record + `RealtimePublisher.publishLeadershipChange` → emits on `/topic/rooms.{id}.survival` (JwtChannelInterceptor regex already permits the token — no change needed). The publish is wrapped in `TransactionSynchronization.afterCommit` so a rolled-back transfer never fires the realtime fan-out.
- FE `Room` interface extended with 2 nullable fields; `RoomMember` extended with `survivalStatus`. New `updateMemberCap` + `transferLeadership` API functions + `UpdateMemberCapVars` / `TransferLeadershipVars` types. New `useUpdateMemberCap` (invalidates `qk.rooms`) + `useTransferLeadership` (invalidates `qk.rooms` + `qk.roomMembers(roomId)`) hooks.
- FE `RoomMemberCapEditor` extracted-for-Jest at `FE/src/components/rooms/RoomMemberCapEditor.tsx`. Thin route wrapper at `FE/app/rooms/[id]/settings/cap.tsx` (`<SubModeProvider subMode="plate">`, route guard, `useRequireAuth`). Stepper bounded to [2, 30] via reused `MAX_MEMBERS_MIN`/`MAX_MEMBERS_MAX` constants. Preview literal "변경된 정원은 다음 달 1일부터 적용됩니다." byte-identical (AC18 lock). Non-leader gets read-only fallback. Success calls `router.back()`. Error toasts: 403 → "방장만 정원을 바꿀 수 있어요.", 400 → "정원은 2에서 30 사이여야 합니다.", unmatched `ApiError`/`Error` → original `error.message`.
- FE `LeaderTransferPicker` extracted-for-Jest at `FE/src/components/rooms/LeaderTransferPicker.tsx`. Thin route wrapper at `FE/app/rooms/[id]/settings/transfer-leadership.tsx`. Picker excludes self + RED + SPECTATOR. Confirm modal with nickname interpolation, `accessibilityViewIsModal`, and alert role. Non-leader rows render as read-only views. Success calls `router.back()`. 409 toast: "지금은 양도가 어려운 상태예요. 다시 확인해 주세요." (AC18 + Trap #12 — `탈락` AVOID-lexicon trap defended); unmatched `ApiError`/`Error` keeps the original message.
- `FE/app/rooms/[id]/settings.tsx` gains 2 entry rows: cap-edit (visible to all members) + transfer-leadership (leader-only conditional render).
- Review patch verification: `cd BE && ./gradlew test --tests 'com.yeosal.api.room.*'` passed; `cd FE && npx jest --runInBand --no-watchman src/components/rooms/__tests__/RoomMemberCapEditor.test.tsx src/components/rooms/__tests__/LeaderTransferPicker.test.tsx src/lib/query/hooks/__tests__/rooms.leader.test.tsx` passed (22 tests). `cd FE && npx tsc --noEmit` still fails only on the pre-existing `FriendsTodayPager` baseline (`react-native-pager-view` missing type/module + implicit `any` event parameter). Earlier implementation verification remains recorded above; full-suite rerun was not repeated in this review patch turn.
- Opt-in Testcontainers ITs (V13MigrationIT + RoomMemberCapPromotionIT) authored but not executed locally (Docker not available on this host); will run in CI under `-Dyeosal.boot-smoke=true`.
- Manual VERIFY-N smoke deferred to PR-open per Story 5.1 / 4.x precedent.

### File List

BE (new):
- `BE/src/main/java/com/yeosal/api/room/IneligibleLeaderException.java`
- `BE/src/main/java/com/yeosal/api/room/LeadershipChangePayload.java`
- `BE/src/main/java/com/yeosal/api/room/RoomCapPromotionService.java`
- `BE/src/main/java/com/yeosal/api/room/RoomMemberCapController.java`
- `BE/src/main/java/com/yeosal/api/room/RoomMemberCapService.java`
- `BE/src/main/java/com/yeosal/api/room/TransferLeadershipController.java`
- `BE/src/main/java/com/yeosal/api/room/TransferLeadershipRequest.java`
- `BE/src/main/java/com/yeosal/api/room/TransferLeadershipService.java`
- `BE/src/main/java/com/yeosal/api/room/UpdateMemberCapRequest.java`
- `BE/src/main/resources/db/migration/V13__rooms_pending_max_members.sql`
- `BE/src/test/java/com/yeosal/api/room/RoomCapPromotionServiceTest.java`
- `BE/src/test/java/com/yeosal/api/room/RoomMemberCapControllerTest.java`
- `BE/src/test/java/com/yeosal/api/room/RoomMemberCapPromotionIT.java`
- `BE/src/test/java/com/yeosal/api/room/RoomMemberCapServiceTest.java`
- `BE/src/test/java/com/yeosal/api/room/TransferLeadershipControllerTest.java`
- `BE/src/test/java/com/yeosal/api/room/TransferLeadershipServiceTest.java`
- `BE/src/test/java/com/yeosal/api/room/V13MigrationIT.java`

BE (modified):
- `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` (add `IneligibleLeaderException` import + handler)
- `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` (add `LeadershipChangePayload` import + `publishLeadershipChange` method)
- `BE/src/main/java/com/yeosal/api/room/Room.java` (add 2 fields + 4 getter/setter methods)
- `BE/src/main/java/com/yeosal/api/room/RoomRepository.java` (add `findByIdForUpdate` pessimistic lock)
- `BE/src/main/java/com/yeosal/api/room/RoomService.java` (requireRoom private→public + lazy-promotion delegation + refresh; `requireRoomForUpdate`; RoomSummary record extension; MemberSummary record extension + privacy-filtered survival_state batch load in `members`)
- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRepository.java` (add fetching batch use site + `findByRoomIdAndUserIdForUpdate`)
- `BE/src/test/java/com/yeosal/api/room/RoomServiceEvaluationTest.java` (add `@Mock RoomCapPromotionService capPromotion` + constructor arg)
- `BE/src/test/java/com/yeosal/api/room/RoomServiceMemberJoinSystemMessageTest.java` (same)
- `BE/src/test/java/com/yeosal/api/room/RoomServiceTest.java` (same)

FE (new):
- `FE/app/rooms/[id]/settings/cap.tsx`
- `FE/app/rooms/[id]/settings/transfer-leadership.tsx`
- `FE/src/api/__tests__/rooms.cap.test.ts`
- `FE/src/api/__tests__/rooms.transfer.test.ts`
- `FE/src/components/rooms/LeaderTransferPicker.tsx`
- `FE/src/components/rooms/RoomMemberCapEditor.tsx`
- `FE/src/components/rooms/__tests__/LeaderTransferPicker.test.tsx`
- `FE/src/components/rooms/__tests__/RoomMemberCapEditor.test.tsx`
- `FE/src/lib/query/hooks/__tests__/rooms.leader.test.tsx`

FE (modified):
- `FE/app/rooms/[id]/settings.tsx` (add 2 entry rows)
- `FE/src/api/rooms.ts` (Room interface extension + RoomMember interface extension + updateMemberCap + transferLeadership functions + Vars types)
- `FE/src/components/rooms/__tests__/RoomRuleEditor.test.tsx` (fixture extension for new Room fields)
- `FE/src/lib/query/hooks/__tests__/useCreateRoom.test.tsx` (fixture extension for new Room fields)
- `FE/src/lib/query/hooks/rooms.ts` (add `useUpdateMemberCap` + `useTransferLeadership` hooks)

Sprint tracking:
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status flip + comment header)
- `_bmad-output/implementation-artifacts/5-2-member-cap-edit-leader-transfer.md` (this file — Dev Agent Record completion)
