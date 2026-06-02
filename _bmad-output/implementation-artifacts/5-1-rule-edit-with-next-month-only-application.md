# Story 5.1: Rule edit with next-month-only application

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a room leader,
I want to edit my room's rule (currently the daily-update preset + weekend-include toggle) and have the change apply only from the next calendar month,
So that the contract my members joined under is preserved through the current month and trust is held.

## Acceptance Criteria

> 이 스토리는 **Epic 5의 첫 BE 변경**으로 leader-only `PATCH /api/v1/rooms/{id}/rule` 엔드포인트 + 다음-달-적용 persistence + leader 권한 게이트 첫 wiring을 다룬다. V11 step 8/14가 `room_rule_versions` 테이블 + 모든 기존 방에 default-rule 행을 이미 backfill했고, `RoomRuleVersion`/`RoomRuleVersionRepository`/`RulePresetEvaluator`가 Story 1.2 시점에 read site로만 wired되어 있다 — Story 5.1은 그 위에 **write site + leader chokepoint promotion + FE rule editor**만 얹는다. **Chat SYSTEM 메시지 broadcast + `RealtimeEvent.RuleChange` 토픽 emission은 Story 5.4 scope로 명시적으로 deferred** (epics.md:734 "(Story 5.4)" 명시).

### AC1 — Leader-only `PATCH /api/v1/rooms/{id}/rule` endpoint (REQUIRED ENDPOINT)

**Given** I am `rooms.owner_id` for room R (leader of record per FR-8.5.1)
**When** I call `PATCH /api/v1/rooms/{id}/rule` with body `{ "preset": "DAILY_UPDATE", "weekendInclude": false }`
**Then** the BE:
1. resolves the authenticated `User` via the existing `CurrentUser.require(auth)` chain (mirrors `RoomController.updateMyMinimum:118-126`),
2. loads the room via `rooms.findById(roomId)` → 404 `NOT_FOUND` ("방을 찾을 수 없습니다.") if absent,
3. enforces leader-only via the **promoted** `RoomService.requireLeader(room, me)` chokepoint (see AC8) — throws `ForbiddenException("방장 권한이 필요합니다.")` → 403 `FORBIDDEN` on non-leader,
4. validates `preset` against the v1 whitelist `{"DAILY_UPDATE"}` only (per `RulePresetEvaluator:14` comment "v1 supports only the `DAILY_UPDATE` preset") → 400 `VALIDATION` on any other value,
5. validates `weekendInclude` is non-null boolean → 400 `VALIDATION` if missing (jakarta `@NotNull`),
6. inserts (or replaces — see AC3) a `room_rule_versions` row with `effective_from_month = nextMonthKST` (see AC2), `rule_payload = {preset, weekendInclude}` JSONB, `created_by_user_id = me.id`, `created_at = now()`,
7. returns `200 OK` with `ApiResponse.of(RoomRuleVersionDto)` envelope where `RoomRuleVersionDto` exposes `{id, preset, weekendInclude, effectiveFromMonth, createdByUserId, createdAt}` — matches Architecture §6.4 row 7 ("`RoomRuleVersionDto` | room leader").

**And** the endpoint MUST live at `/api/v1/rooms/{id}/rule` exactly (Architecture §6.4 table, line 812) — NOT at `/api/v1/rooms/{id}/rules` or `/api/v1/rules/{roomId}`. Path drift is wire-incompatible with the architecture-locked contract.

PRD: FR-8.5.2, FR-8.5.3. Architecture: §6.4 (REST endpoint table line 812), V11 (8). UX: ux-design-specification.md:205-213 (J5 journey), §1369-1386 J5 mermaid flow.

### AC2 — `nextMonthKST` computation (CRITICAL CORRECTNESS)

**Given** the leader confirms a rule edit at any instant
**When** the BE service computes `effective_from_month`
**Then** the computation MUST be exactly:

```java
private static final ZoneId KST = ZoneId.of("Asia/Seoul");
String nextMonthKST() {
    LocalDate todayKst = LocalDate.ofInstant(clock.instant(), KST);
    return YearMonth.from(todayKst).plusMonths(1).toString(); // "YYYY-MM"
}
```

**Rationale:**
- The `Clock` bean is the project-wide test-injectable wall-clock (see `RoomService:23,79,141`, `SurvivalStateEvaluatorJob:6,47,60`).
- `ZoneId.of("Asia/Seoul")` is the project-context-locked day-boundary zone (project-context.md:92,270 — "The day-boundary for daily missions is **06:00 in `Asia/Seoul`**").
- `YearMonth.from(LocalDate.ofInstant(clock.instant(), KST))` resolves the **calendar month** in KST (NOT the entry-date — calendar month is what `SurvivalStateService.evaluateRoom:180` already reads via `YearMonth.from(priorEntryDate).toString()`).
- `.plusMonths(1)` is the only correct shift for "next month" per FR-8.5.3.
- `.toString()` yields the `YYYY-MM` 7-char format that the V11 `varchar(7)` column expects (matches V11 step 14 backfill `to_char(now() at time zone 'Asia/Seoul', 'YYYY-MM')`).

**Edge case — month-boundary semantics:**
- A leader editing at `2026-04-30 23:59 KST` → `currentMonth = 2026-04`, `nextMonth = 2026-05`. The April rule stays in force through the rest of April 30; the May rule kicks in for the daily evaluator's May 1 run (which fires at 06:00 KST May 2 evaluating May 1's entries, sees `monthKey = "2026-05"`, picks the new row).
- A leader editing at `2026-05-01 02:00 KST` (between 00:00 KST and the 06:00 KST evaluator) → from the **calendar perspective**, May has already begun → `currentMonth = 2026-05`, `nextMonth = 2026-06`. The leader CANNOT retroactively change the May rule. This is the **correct trust-preserving behavior** — the May rule was effective from May 1 00:00 KST, and members joining under it have already committed.

**Anti-pattern (DO NOT IMPLEMENT):**
- Using the daily-evaluator entry-date boundary (`EntryDateResolver`) to derive currentMonth. That's a Story 1.1-1.2 abstraction for *entry-date* dedupe, NOT for *calendar-month* rule scoping. Mixing them creates a 6-hour window where the leader could retroactively change the "current" month after the calendar boundary flipped.
- Using `LocalDate.now()` without a `Clock` injection. Breaks test determinism.
- Using `ZoneOffset.of("+09:00")` instead of `ZoneId.of("Asia/Seoul")`. DST-safe for now (Korea doesn't observe DST), but project-context line 270 mandates the named zone.

### AC3 — Upsert semantics on UNIQUE `(room_id, effective_from_month)` conflict

**Given** the leader already edited the same `nextMonth` rule earlier this month
**When** they re-edit again (e.g. typo correction, mind-change) before the month boundary
**Then** the existing `room_rule_versions` row MUST be **replaced** (not duplicated, not errored) per epics.md:800-802 ("the existing `room_rule_versions` row is replaced (UNIQUE on `(room_id, effective_from_month)`)").

**Implementation requirement:** The service layer MUST handle the UNIQUE constraint as an **upsert**, NOT let `DataIntegrityViolationException` leak out as a 500. Two acceptable shapes:

**Option A (recommended — JPA dirty-check)** — find-or-create pattern that stays JPA-native:

```java
Optional<RoomRuleVersion> existing =
    ruleVersions.findByRoomIdAndEffectiveFromMonth(roomId, nextMonth);
if (existing.isPresent()) {
    RoomRuleVersion row = existing.get();
    row.setRulePayload(payload);          // requires entity setter (see AC4)
    row.setCreatedByUserId(me.getId());   // requires entity setter (see AC4)
    row.setCreatedAt(now);                // requires entity setter (see AC4)
    // No explicit save — @Transactional dirty-check flushes on commit.
    return RoomRuleVersionDto.from(row);
}
RoomRuleVersion fresh = new RoomRuleVersion(roomId, nextMonth, payload, me.getId());
return RoomRuleVersionDto.from(ruleVersions.save(fresh));
```

**Option B (alternative — native ON CONFLICT)** — single SQL, no entity setters needed:

```java
// In RoomRuleVersionRepository:
@Modifying
@Query(value = """
    insert into room_rule_versions
        (room_id, effective_from_month, rule_payload, created_by_user_id, created_at)
    values (?1, ?2, cast(?3 as jsonb), ?4, now())
    on conflict (room_id, effective_from_month) do update set
        rule_payload = excluded.rule_payload,
        created_by_user_id = excluded.created_by_user_id,
        created_at = excluded.created_at
    returning id
    """, nativeQuery = true)
Long upsertRule(long roomId, String yearMonth, String payloadJson, long createdByUserId);
```

**Either option is acceptable** but the implementation MUST cover the same-month-replace test case (see AC9 / AC13).

**Race-condition note:** A concurrent re-edit by the same leader (same browser, double-tap) collides on the UNIQUE constraint inside the same JPA flush. Option A's `findByRoomIdAndEffectiveFromMonth` → `save` pair is NOT race-free at HTTP level (two requests can both miss the existing row and both INSERT), and the second INSERT will throw `DataIntegrityViolationException`. The project's `ApiExceptionHandler.dataIntegrity` handler at line 121-129 currently translates this to a 500 `INTERNAL_ERROR` — which is the wrong UX. **Dev MUST**:
- **Either** use Option B (native upsert — race-free at the SQL layer),
- **Or** use Option A + wrap the `save()` call in a `try/catch (DataIntegrityViolationException)` that retries the find-update branch once. Document the retry in the service method JavaDoc.

Option B is recommended (race-free, simpler).

### AC4 — Entity setters on `RoomRuleVersion` (REQUIRED ENTITY EDIT — Option A only)

**Given** Option A from AC3 is chosen
**When** the service needs to mutate an existing `room_rule_versions` row via dirty-check
**Then** `BE/src/main/java/com/yeosal/api/survival/RoomRuleVersion.java` MUST gain the three package-private setters:

```java
void setRulePayload(JsonNode rulePayload) { this.rulePayload = rulePayload; }
void setCreatedByUserId(long createdByUserId) { this.createdByUserId = createdByUserId; }
void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
```

**And** setters MUST stay **package-private** (no `public`) so only `RoomRuleService` (same package) can mutate the entity — leakage into controllers / other domains would break the chokepoint contract. The existing `@PrePersist` hook auto-fills `createdAt` only on INSERT, so the explicit `setCreatedAt(now)` is required for the replace path.

**If Option B (native upsert) is chosen**, this AC is SKIPPED — no entity edit needed, the upsert query handles `created_at = now()` directly.

### AC5 — `GET /api/v1/rooms/{id}/rule` endpoint (REQUIRED ENDPOINT — UI dep)

**Given** any room member opens the Rule Editor screen
**When** the FE calls `GET /api/v1/rooms/{id}/rule`
**Then** the BE returns `200 OK` with `ApiResponse.of(RoomRuleStateDto)` where:

```java
public record RoomRuleStateDto(
    RoomRuleVersionDto current,        // never null — V11 step 14 backfill guarantees a row
    RoomRuleVersionDto pending         // nullable — null when no future-dated row exists
) {}
```

**Implementation:**
- `current` = `findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(roomId, currentMonthKST)` (the **existing** repo method already used by `SurvivalStateService:184` — reuse, do NOT duplicate).
- `pending` = `findByRoomIdAndEffectiveFromMonth(roomId, nextMonthKST)` (the **new** finder added in AC3).
- Auth: room member (not leader-only) — uses `roomMembers.existsByRoomIdAndUserId(roomId, viewerUserId)` gate, mirroring `SurvivalStateService.roster:339`.

**Rationale:** AC1's PATCH is leader-only, but the FE Rule Editor screen needs to show the current rule + any pending edit to all members (so non-leaders see what's coming, not just the leader who set it). Architecture §6.4 doesn't enumerate a GET endpoint for `rule`, but the FE editor requires it — document as a Story-5.1-introduced incremental extension to the §6.4 endpoint table.

**Out of scope:** This GET is read-only and does NOT emit any realtime event. The Story 5.4 chat SYSTEM message is the canonical "notify all members of a pending rule change" channel — this GET is just the FE pre-fill source.

### AC6 — `RoomRuleVersionDto` shape (LOCKED WIRE CONTRACT)

**Given** any response carrying a `RoomRuleVersionDto`
**When** the FE deserializes it
**Then** the shape MUST be exactly:

```java
public record RoomRuleVersionDto(
    long id,
    String preset,              // "DAILY_UPDATE" in v1
    boolean weekendInclude,     // unpacked from rule_payload.weekendInclude
    String effectiveFromMonth,  // "YYYY-MM"
    long createdByUserId,
    Instant createdAt
) {
    public static RoomRuleVersionDto from(RoomRuleVersion row) {
        JsonNode payload = row.getRulePayload();
        String preset = payload.path("preset").asText("DAILY_UPDATE");
        boolean weekendInclude = payload.path("weekendInclude").asBoolean(true);
        return new RoomRuleVersionDto(
            row.getId(), preset, weekendInclude,
            row.getEffectiveFromMonth(),
            row.getCreatedByUserId(),
            row.getCreatedAt());
    }
}
```

**Anti-pattern (DO NOT IMPLEMENT):**
- Returning the raw `JsonNode rulePayload` to the FE. The FE should not parse JSONB shape — that's BE concern. Unpack `preset` + `weekendInclude` into typed fields.
- Renaming `effectiveFromMonth` to `effective_from_month` or `effectiveFrom` or `month`. Jackson serializes record field names verbatim — the locked wire field is `effectiveFromMonth` (camelCase, matches other DTOs like `MeSurvivalEntryDto`).

### AC7 — `UpdateRoomRuleRequest` controller body (LOCKED REQUEST CONTRACT)

**Given** the PATCH endpoint at AC1
**When** the controller deserializes the request body
**Then** the body MUST be:

```java
public record UpdateRoomRuleRequest(
    @NotNull @Pattern(regexp = "^DAILY_UPDATE$",
        message = "preset은 DAILY_UPDATE만 허용됩니다.") String preset,
    @NotNull Boolean weekendInclude
) {}
```

- `@Valid @RequestBody UpdateRoomRuleRequest body` triggers `MethodArgumentNotValidException` on missing/invalid fields → `ApiExceptionHandler.validation:78-82` maps to 400 `VALIDATION`.
- `Boolean` (boxed) not `boolean` (primitive) so Jackson can distinguish "missing field" from "explicit false" — `@NotNull` catches the missing case.
- The `@Pattern` is the **first line of defense**; the service-layer whitelist (AC1 step 4) is the **chokepoint** that protects direct test/admin callers.

### AC8 — Promote `RoomService.requireLeader` from `private` → `public` (CHOKEPOINT WIRING)

**Given** `BE/src/main/java/com/yeosal/api/room/RoomService.java:411-416` currently holds:

```java
@SuppressWarnings("unused") // wired by Stories 5.1, 5.2, 5.6 — kept resident as the single leader check.
private void requireLeader(Room room, User user) {
    if (!room.getOwner().getId().equals(user.getId())) {
        throw new ForbiddenException("방장 권한이 필요합니다.");
    }
}
```

**When** Story 5.1's `RoomRuleService` (in `survival/` package, different from `room/`) needs to call this gate
**Then** `requireLeader` MUST be promoted to **public** visibility, the `@SuppressWarnings("unused")` annotation MUST be removed (it is now used), and the comment MUST be refreshed to drop "wired by Stories 5.1, 5.2, 5.6" placeholder since 5.1 actually wires it.

**Updated form (after Story 5.1):**

```java
/**
 * Leader-of-record authorization gate per FR-8.5.1. The {@code Room.owner}
 * FK is the canonical leader identity; this is the single source of truth
 * every leader-only endpoint (Stories 5.1, 5.2, 5.6) must consult so the
 * auth contract cannot drift between handlers.
 *
 * @throws ForbiddenException when {@code user} is not the room's owner.
 */
public void requireLeader(Room room, User user) {
    if (!room.getOwner().getId().equals(user.getId())) {
        throw new ForbiddenException("방장 권한이 필요합니다.");
    }
}
```

**And** `RoomRuleService` MUST inject `RoomService` (constructor) and call `roomService.requireLeader(room, me)` — NOT duplicate the `room.getOwner().getId().equals(...)` check inline. Duplication breaks the chokepoint promise.

**Anti-pattern (DO NOT IMPLEMENT):**
- Extracting a separate `LeaderAuthorizationService` — over-architecting. `RoomService` is already the canonical room-domain service; adding leader-auth as a method on it preserves the existing dependency direction.
- Moving `requireLeader` to `Room.java` as an entity method — entities should not throw web exceptions (`ForbiddenException` extends `RuntimeException` but lives in `com.yeosal.api.common`, a web-layer package).

### AC9 — Daily evaluator next-month read-through (CONTRACT INTEGRITY)

**Given** the current month is `2026-04` and a leader successfully edits the rule on `2026-04-15`
**When** the daily evaluator (`SurvivalStateEvaluatorJob.evaluatePriorDay`, cron `0 0 6 * * *` KST) runs during the **rest of April** (e.g. evaluating `2026-04-15`, `..-16`, ..., `..-30`)
**Then** it MUST continue to read the **April rule row** (the V11-backfilled `2026-04` row), NOT the leader's newly-inserted `2026-05` row. This is satisfied automatically by the existing `findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(roomId, "2026-04")` query (returns the highest `effective_from_month <= 2026-04` — the April row).

**Given** the daily evaluator runs on `2026-05-02 06:00 KST` (evaluating `2026-05-01` entries)
**When** the per-room evaluation calls the same query with `monthKey = "2026-05"`
**Then** it picks the new `2026-05` row (highest `effective_from_month <= 2026-05`) and applies the new `weekendInclude` value going forward.

**Verification:** A new Testcontainers IT `RoomRuleNextMonthEvaluatorIT` covers this with two sub-cases:
1. Insert a `2026-05` rule with `weekendInclude=false` on `2026-04-15`. Run `SurvivalStateService.evaluateRoom(roomId, LocalDate.of(2026, 4, 25))` (a Friday, weekendInclude=true in April → evaluated, member misses → YELLOW). Assert YELLOW transition.
2. Same room, same setup. Run `SurvivalStateService.evaluateRoom(roomId, LocalDate.of(2026, 5, 3))` (a Saturday, weekendInclude=false in May → skipped). Assert no state-machine row written, no notification_log row written.

**Existing read-site validation:** `SurvivalStateService.evaluateRoom:177-307` already reads the rule via the existing repo method (line 183-187). Story 5.1 does NOT touch `SurvivalStateService` — the read site is correct. **DO NOT modify `SurvivalStateService` in Story 5.1.** Any apparent need to touch it is a sign the implementation has gone astray.

### AC10 — FE Rule Editor screen at `FE/app/rooms/[id]/settings/rule.tsx` (REQUIRED ROUTE)

**Given** a room leader navigates to Room Settings → "그룹 규칙" entry
**When** they reach `/rooms/{id}/settings/rule`
**Then** the FE renders a screen wrapped in `<SubModeProvider subMode="plate">` (D5 Plate System sub-mode per UX line 1154 — "Leader rule editor" surface) that:

1. Calls `useRoomRule(roomId)` to fetch the current + pending rule state (via the new `qk.roomRule(roomId)` cache key — see AC11).
2. Shows the current rule's preset + `weekendInclude` state as a read-only summary (e.g. "이번 달 규칙: 매일 업데이트 — 주말 포함").
3. Shows an editor (toggle for `weekendInclude`; preset is read-only "매일 업데이트" since v1 has only one preset).
4. Shows the **locked preview text** `"변경된 규칙은 다음 달 1일부터 적용됩니다."` — VERBATIM, character-for-character per epics.md:720. NO paraphrasing, NO punctuation substitution (fullwidth `．` BANNED, ASCII `.` REQUIRED). NO leading/trailing whitespace mutation.
5. Renders a primary CTA "다음 달부터 적용하기" (or equivalent commit-tone copy — see AC12 brand-voice gate). Disabled state when the toggle value equals the server's editable baseline: the pending rule's `weekendInclude` when a next-month edit already exists, otherwise the current rule's `weekendInclude`. This avoids no-op round-trips while still allowing a leader to revert an already-staged change back to the current rule.
6. On CTA tap: calls `useUpdateRoomRule().mutate({roomId, preset: "DAILY_UPDATE", weekendInclude})`. On success, refetches `qk.roomRule(roomId)` (the mutation's `onSuccess` triggers `queryClient.invalidateQueries({queryKey: qk.roomRule(roomId)})` — see AC11). On `ApiError` 403 (non-leader, defensive), surfaces a non-shaming toast "방장만 규칙을 바꿀 수 있어요."; on 400 VALIDATION, "요청 형식이 올바르지 않습니다."; on network failure, the default `ApiError` toast pattern from existing screens.
7. Non-leader members reaching this URL directly see a read-only view (the editor toggle + Save CTA are hidden; the current-rule summary + the preview text + any pending edit still render). Leader detection: `roomsQuery.data?.find(r => r.id === roomId)?.ownerId === user.id`.

**And** `FE/app/rooms/[id]/settings.tsx` MUST gain a new row pointing to `/rooms/{id}/settings/rule`, placed **after** `<RoomMinimumSettings>` and **after** `<RecordVisibilityToggle>` so the existing visual hierarchy is preserved. The row is visible to all members; tap takes them to the rule-editor screen (leader sees editor, non-leader sees read-only).

PRD: FR-8.5.2, FR-8.5.3, FR-8.5.8. UX: ux-design-specification.md:205-213 (J5 narrative), §1136-1155 (D5 Plate System sub-mode + surface assignment table), §1369-1386 (J5 mermaid).

### AC11 — `qk.roomRule(roomId)` query key + invalidation contract (LOCKED CACHE)

**Given** any FE consumer of the rule state
**When** they read or write the rule
**Then**:

```ts
// FE/src/lib/query/keys.ts — add at end of qk object:
// Story 5.1 AC11 — per-room rule cache (current + pending). Member-scoped
// (BE filters by membership). Invalidated on every successful
// useUpdateRoomRule mutation; no STOMP subscription in v1 (Story 5.4 may
// add /topic/rooms.{id}.rule for the chat SYSTEM message broadcast — that
// invalidation will land in 5.4, NOT here).
roomRule: (roomId: number) => ["roomRule", roomId] as const,
```

```ts
// FE/src/lib/query/hooks/roomRule.ts — new file:
export function useRoomRule(roomId: number) {
  return useQuery({
    queryKey: qk.roomRule(roomId),
    queryFn: () => getRoomRule(roomId),
    staleTime: 30_000, // 30s, matches qk.meSurvival / qk.roomPoints cadence
  });
}

export function useUpdateRoomRule() {
  const queryClient = useQueryClient();
  return useMutation<RoomRuleVersionDto, ApiError, UpdateRoomRuleVars>({
    mutationFn: ({ roomId, preset, weekendInclude }) =>
      updateRoomRule(roomId, { preset, weekendInclude }),
    onSuccess: (_data, { roomId }) => {
      queryClient.invalidateQueries({ queryKey: qk.roomRule(roomId) });
    },
  });
}
```

**Anti-pattern:**
- Calling `setQueryData(qk.roomRule(roomId), newDto)` directly — the mutation only returns the **pending** row, not the full `RoomRuleStateDto` shape (current + pending). A naive `setQueryData` would clobber the `current` field. `invalidateQueries` triggers a refetch that returns the correct shape from BE.
- Cross-invalidating `qk.meSurvival` or `qk.roomToday(roomId, date)` on a rule edit. Those reflect the **current** month's status, which Story 5.1 explicitly does NOT change. Cross-invalidation is Story 5.4's job (chat SYSTEM message arrival).

### AC12 — Brand-voice + scope-fence (CI GATE)

**Given** the Story 5.1 PR diff
**When** `tools/brand-voice-lint.ts` and the verify pipeline run
**Then**:

1. **Brand-voice HARD violations = 0.** The preview literal "변경된 규칙은 다음 달 1일부터 적용됩니다." MUST pass — none of `벌금`, `실패`, `패배`, `낙오`, `탈락`, `꼴찌`, `손해` appear. The CTA copy MUST also avoid the AVOID lexicon (e.g. "꼭 다음 달부터 적용됩니다" rather than "꼭 다음 달부터 적용해야 합니다" — soft commitment tone per UX brand voice).
2. **Scope fence verified by `git diff --stat origin/main`:** the diff MUST touch ONLY:
   - `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java` (new)
   - `BE/src/main/java/com/yeosal/api/survival/RoomRuleController.java` (new)
   - `BE/src/main/java/com/yeosal/api/survival/RoomRuleVersionDto.java` (new)
   - `BE/src/main/java/com/yeosal/api/survival/RoomRuleStateDto.java` (new)
   - `BE/src/main/java/com/yeosal/api/survival/UpdateRoomRuleRequest.java` (new)
   - `BE/src/main/java/com/yeosal/api/survival/RoomRuleVersion.java` (3 package-private setters — Option A only)
   - `BE/src/main/java/com/yeosal/api/survival/RoomRuleVersionRepository.java` (add `findByRoomIdAndEffectiveFromMonth` + Option-B native upsert if chosen)
   - `BE/src/main/java/com/yeosal/api/room/RoomService.java` (visibility flip on `requireLeader` + seed default rule for fresh rooms)
   - `BE/src/main/java/com/yeosal/api/room/DefaultRoomMigrationRunner.java` (seed default rule for legacy default-room runner path)
   - `BE/src/test/java/com/yeosal/api/survival/RoomRuleServiceTest.java` (new)
   - `BE/src/test/java/com/yeosal/api/survival/RoomRuleControllerTest.java` (new)
   - `BE/src/test/java/com/yeosal/api/survival/RoomRuleNextMonthEvaluatorIT.java` (new, Testcontainers, opt-in via `@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")` mirroring `FriendGiftConcurrencyIT`)
   - `BE/src/test/java/com/yeosal/api/room/RoomServiceTest.java` (extend fresh-room default-rule assertion)
   - `BE/src/test/java/com/yeosal/api/room/RoomServiceEvaluationTest.java` (constructor fixture extension)
   - `BE/src/test/java/com/yeosal/api/room/RoomServiceMemberJoinSystemMessageTest.java` (constructor fixture extension)
   - `BE/src/test/java/com/yeosal/api/room/RoomControllerIT.java` (extend fresh-room default-rule assertion)
   - `FE/src/api/rooms.ts` (extend — add `getRoomRule` + `updateRoomRule` + `RoomRuleVersionDto` + `RoomRuleStateDto` + `UpdateRoomRuleVars` types)
   - `FE/src/lib/query/keys.ts` (add `qk.roomRule`)
   - `FE/src/lib/query/hooks/roomRule.ts` (new)
   - `FE/app/rooms/[id]/settings.tsx` (1 new row pointing to /settings/rule)
   - `FE/app/rooms/[id]/settings/rule.tsx` (new — the editor screen)
   - `FE/src/components/rooms/RoomRuleEditor.tsx` (new — extracted editor component for Jest discovery)
   - `FE/src/api/__tests__/rooms.rule.test.ts` (new, convention-aligned wire tests)
   - `FE/src/lib/query/hooks/__tests__/roomRule.test.tsx` (new)
   - `FE/src/components/rooms/__tests__/RoomRuleEditor.test.tsx` (new)
   - `_bmad-output/implementation-artifacts/sprint-status.yaml` (status flips + comment header)
   - `_bmad-output/implementation-artifacts/5-1-rule-edit-with-next-month-only-application.md` (this file)

3. **ZERO changes to:**
   - `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java` (read site is already correct — AC9)
   - `BE/src/main/java/com/yeosal/api/survival/SurvivalStateEvaluatorJob.java` (cron/scheduler unchanged)
   - `BE/src/main/java/com/yeosal/api/survival/RulePresetEvaluator.java` (preset list unchanged — v1 stays DAILY_UPDATE only)
   - `BE/src/main/java/com/yeosal/api/room/chat/*.java` (chat SYSTEM message is Story 5.4)
   - `BE/src/main/java/com/yeosal/api/realtime/*.java` (RealtimeEvent.RuleChange is Story 5.4)
   - `BE/src/main/resources/db/migration/*.sql` (V11 already shipped — NO new migration needed)
   - `FE/src/theme/tokens.json` (D5.plate already exists at line 187)
   - `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` (existing 403 FORBIDDEN + 400 VALIDATION mappings cover Story 5.1 — no new exception types needed)
   - Any auto-generated tokens file (`BE/build/generated/sources/tokens/**`)

### AC13 — Test coverage matrix

**Given** the implementation is complete
**When** the verify pipeline runs
**Then** the following test counts MUST be net-additive (delta vs `origin/main`):

| Test file | Cases | Layer | Notes |
|-----------|-------|-------|-------|
| `RoomRuleServiceTest.java` | at least 10 | BE unit (Mockito) | happy insert, happy replace (Option A or B), leader-only 403 (via `RoomService.requireLeader`), missing room 404, preset whitelist 400, nextMonth at 4/30 23:59 KST returns "2026-05", nextMonth at 5/1 02:00 KST returns "2026-06", current-month row untouched on replace, GET returns current+pending, GET returns current only when no pending |
| `RoomRuleControllerTest.java` | at least 6 | BE WebMvcTest slice | PATCH 200 happy, PATCH 403 non-leader, PATCH 400 invalid preset, PATCH 400 missing weekendInclude, PATCH 404 unknown room, GET 200 happy |
| `RoomRuleNextMonthEvaluatorIT.java` | at least 2 | BE Testcontainers IT (opt-in `yeosal.boot-smoke`) | full-stack — rule write + April evaluator reads old rule + May evaluator reads new rule |
| `rooms.test.ts` extension | +2 | FE Jest | `getRoomRule` envelope unwrap + `updateRoomRule` body + path correctness |
| `roomRule.test.tsx` | at least 4 | FE Jest (React Testing Library) | `useRoomRule` fetches + caches, `useUpdateRoomRule` invalidates on success, `useUpdateRoomRule` does NOT invalidate on 403 ApiError, ApiError narrowing test |
| `rule.test.tsx` (FE editor screen) | at least 5 | FE Jest | leader sees editor + Save CTA, non-leader sees read-only view, preview literal renders verbatim, Save flow calls mutation + invalidates, brand-voice grep (no AVOID lexicon strings in rendered output) |

**Total net-additive delta:** at least 18 BE cases + at least 11 FE cases. Existing test suites MUST stay green (FE Jest target: existing baseline +29 net; BE existing baseline net-additive +18).

### AC14 — Brand-voice and no-emoji rule (SOURCE FILES)

**Given** the Story 5.1 source files
**When** any AI agent (or human) lays down a literal
**Then**:

1. **NO emojis** in any source file (BE `.java`, FE `.ts`, FE `.tsx`). The codebase rule is no emojis in source unless the user explicitly asks — Story 4.2/4.3 already enforced this in brand-voice-lint Rule 2.
2. **Korean copy passes brand-voice AVOID lexicon** (`벌금`, `실패`, `패배`, `낙오`, `탈락`, `꼴찌`, `손해`).
3. **The preview literal is byte-identical** to `"변경된 규칙은 다음 달 1일부터 적용됩니다."` (32 characters incl. final period, ASCII period not fullwidth).
4. **The chat SYSTEM message body** `"다음 달부터 새 규칙이 적용됩니다: …"` is NOT written in Story 5.1 — that's Story 5.4's responsibility. Do NOT pre-emptively wire it.

## Tasks / Subtasks

- [x] **Task 1 — BE entity + repo extensions (AC3/AC4/AC5)**
  - [x] Add `findByRoomIdAndEffectiveFromMonth(long roomId, String yearMonth)` to `RoomRuleVersionRepository`
  - [N/A] (Option A only) Add 3 package-private setters to `RoomRuleVersion` (rulePayload, createdByUserId, createdAt) — Option B chosen; entity untouched
  - [x] (Option B only) Add native `upsertRule(...)` `@Modifying @Query nativeQuery=true` method to `RoomRuleVersionRepository`
- [x] **Task 2 — BE leader chokepoint promotion (AC8)**
  - [x] Flip `RoomService.requireLeader` from `private` to `public`
  - [x] Remove `@SuppressWarnings("unused")` annotation
  - [x] Refresh the JavaDoc/comment to drop the "wired by Stories 5.1, 5.2, 5.6" placeholder
- [x] **Task 3 — BE DTOs + request body (AC6/AC7)**
  - [x] Create `RoomRuleVersionDto` record with static `from(RoomRuleVersion)` factory
  - [x] Create `RoomRuleStateDto` record (current + nullable pending)
  - [x] Create `UpdateRoomRuleRequest` record with `@NotNull @Pattern` on preset + `@NotNull Boolean` on weekendInclude
- [x] **Task 4 — BE service (AC1/AC2/AC3)**
  - [x] Create `RoomRuleService` in `survival/` package
  - [x] Inject `RoomRepository`, `RoomRuleVersionRepository`, `RoomService` (for leader gate), `Clock`, `ObjectMapper`, `RoomMemberRepository` (for GET member gate)
  - [x] Implement `updateRule(User, long, String preset, boolean weekendInclude)` returning `RoomRuleVersionDto` with the AC1 7-step flow
  - [x] Implement `getRule(User, long)` returning `RoomRuleStateDto` per AC5
  - [x] Private helper `nextMonthKST()` + `currentMonthKST()` using injected `Clock` + `ZoneId.of("Asia/Seoul")` (NO `ZoneOffset.of("+09:00")`)
- [x] **Task 5 — BE controller (AC1/AC5)**
  - [x] Create `RoomRuleController` in `survival/` package
  - [x] `@RestController @RequestMapping("/api/v1/rooms")`
  - [x] `@PatchMapping("/{id}/rule")` calls `updateRule(...)` — auth via existing `CurrentUser.require(auth)` pattern
  - [x] `@GetMapping("/{id}/rule")` calls `getRule(...)`
- [x] **Task 6 — BE tests (AC13)**
  - [x] `RoomRuleServiceTest.java` — at least 10 unit cases (Mockito) — 13 cases shipped
  - [x] `RoomRuleControllerTest.java` — at least 6 WebMvcTest slice cases (`@MockBean` the service) — 7 cases shipped
  - [x] `RoomRuleNextMonthEvaluatorIT.java` — at least 2 Testcontainers IT cases, opt-in via `@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")` (mirrors `FriendGiftConcurrencyIT:1`) — 2 cases shipped
- [x] **Task 7 — FE API client (AC5/AC6/AC7)**
  - [x] Extend `FE/src/api/rooms.ts` with `RoomRuleVersionDto`, `RoomRuleStateDto`, `UpdateRoomRuleVars` TS interfaces (NOT classes — match existing `Room`, `RoomMember` style)
  - [x] Add `getRoomRule(roomId: number): Promise<RoomRuleStateDto>` and `updateRoomRule(roomId: number, body: UpdateRoomRuleVars): Promise<RoomRuleVersionDto>` via `apiRequest<ApiEnvelope<...>>`
- [x] **Task 8 — FE query layer (AC11)**
  - [x] Add `qk.roomRule(roomId)` to `FE/src/lib/query/keys.ts`
  - [x] Create `FE/src/lib/query/hooks/roomRule.ts` with `useRoomRule` + `useUpdateRoomRule`
  - [x] `useUpdateRoomRule.onSuccess` invalidates `qk.roomRule(roomId)` ONLY (no cross-invalidation per AC11 anti-pattern)
- [x] **Task 9 — FE Rule Editor screen (AC10)**
  - [x] Create `FE/app/rooms/[id]/settings/rule.tsx` wrapped in `<SubModeProvider subMode="plate">` (D5)
  - [x] Implement leader-detection via `useRoomsQuery()` (existing hook) — `room.ownerId === user.id`
  - [x] Render current-rule summary + editor (leader) OR read-only view (non-leader)
  - [x] Mount the verbatim preview literal `"변경된 규칙은 다음 달 1일부터 적용됩니다."`
  - [x] Wire the Save CTA to `useUpdateRoomRule().mutate(...)` with disabled state on no-op
- [x] **Task 10 — FE settings entry row (AC10)**
  - [x] Add a row/CTA to `FE/app/rooms/[id]/settings.tsx` after `<RecordVisibilityToggle>` that routes to `/rooms/{id}/settings/rule`
- [x] **Task 11 — FE tests (AC13)**
  - [x] Extend `FE/src/api/__tests__/rooms.test.ts` with at least 2 new cases — shipped as `rooms.rule.test.ts` per existing `rooms.{method}.test.ts` convention
  - [x] Create `FE/src/lib/query/__tests__/roomRule.test.tsx` — at least 4 hook cases — shipped at `FE/src/lib/query/hooks/__tests__/roomRule.test.tsx` (correct directory for the hook)
  - [x] Create the editor screen test file — at least 5 cases — shipped at `FE/src/components/rooms/__tests__/RoomRuleEditor.test.tsx`; the editor was extracted from the route file into `src/components/rooms/RoomRuleEditor.tsx` because Jest `testMatch` is scoped to `<rootDir>/src/**/__tests__/`. Route file `app/rooms/[id]/settings/rule.tsx` is now a thin SubModeProvider wrapper that mounts `<RoomRuleEditor>`.
- [x] **Task 12 — Verify pipeline**
  - [x] `tools/brand-voice-lint.ts` returns 0 HARD violations (AC12, AC14) — 0 HARD / 188 warnings (Story 4.3 baseline 185 + 3 inherited warnings, none in new files)
  - [x] BE Gradle test + FE Jest + FE typecheck (touched files clean) + FE lint touched-files + scope-fence grep — BE 80 classes / 0 failures / 0 errors; FE 57 suites / 437 tests passed (Δ +3 suites / +11 tests; matches AC13 FE target exactly); ESLint clean on Story 5.1 touched files (0 problems); `tsc --noEmit` introduces no new errors (2 pre-existing FriendsTodayPager errors per Story 4.1 baseline)
  - [DEFERRED] Manual smoke (VERIFY-N): on iOS sim, log in as leader, navigate to room settings, tap rule row, confirm editor renders, toggle weekendInclude, confirm preview literal, tap Save, confirm pending row appears, log in as non-leader, confirm read-only view + the same pending row visible — deferred to PR-open per Story 4.1/4.2/4.3 precedent

### Review Findings

- [x] [Review][Patch] Compare Save against the server pending value when one exists; decision resolved 2026-06-02: keep an unchanged pending value disabled, but allow a leader to revert an already-staged next-month edit back to the current rule. [`FE/src/components/rooms/RoomRuleEditor.tsx:103`]
- [x] [Review][Patch] Seed the default current-month rule for every fresh-room creation path; V11 only backfills rooms that existed during migration, while `RoomService.create` and `DefaultRoomMigrationRunner.seedRoom` create rooms without a `room_rule_versions` row, causing `GET /rule` and the evaluator to fail with a data-shape 500. [`BE/src/main/java/com/yeosal/api/room/RoomService.java:135`]
- [x] [Review][Patch] Derive `currentMonth` and `nextMonth` from one captured instant in `getRule`; two `clock.instant()` reads can cross a KST month boundary and return a mismatched snapshot. [`BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java:102`]
- [x] [Review][Patch] Add a PostgreSQL integration test for the native `upsertRule` insert-and-replace path; the current Testcontainers test seeds rows with JPA `save()` and never exercises JSONB binding or `ON CONFLICT DO UPDATE`. [`BE/src/main/java/com/yeosal/api/survival/RoomRuleVersionRepository.java:44`]
- [x] [Review][Patch] Preserve unsaved toggle edits across background rule refetches; the snapshot synchronization effect currently overwrites local input whenever query data changes. [`FE/src/components/rooms/RoomRuleEditor.tsx:74`]
- [x] [Review][Patch] Surface a rule-editor error state when the rooms query fails; an empty fallback currently renders the actual leader as a read-only non-leader. [`FE/src/components/rooms/RoomRuleEditor.tsx:60`]
- [x] [Review][Patch] Validate route ids with `Number.isSafeInteger`; fractional and unsafe numeric ids currently pass the direct-route guard and issue invalid API requests. [`FE/app/rooms/[id]/settings/rule.tsx:14`]
- [x] [Review][Patch] Amend the AC12 scope-fence allowlist for the documented extracted editor component and convention-aligned test paths; the completion note documents the deviation but the normative allowlist still rejects the shipped files. [`_bmad-output/implementation-artifacts/5-1-rule-edit-with-next-month-only-application.md:326`]
- [x] [Review][Patch] Remove new task-specific source comments that reference Story 5.1, AC numbers, or project-context line numbers; project context requires source comments to describe durable constraints rather than the current task. [`BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java:23`]

## Dev Notes

### Context — how much of Story 5.1 is already shipped (Story 1.1/1.2/1.4)

V11 (shipped via Story 1.4) already laid the persistence floor:
- `room_rule_versions` table with the exact V11 step 8 schema (Architecture §6.3 line 733-742, confirmed in `architecture.md:733-742`).
- V11 step 14 backfill seeded every existing room with `effective_from_month = <current month KST>` and `rule_payload = {"preset":"DAILY_UPDATE","weekendInclude":true}` (`architecture.md:788-794`).
- `RoomRuleVersion` JPA entity in `survival/` package with `JsonNode rulePayload` (JSON jdbc type), `effectiveFromMonth` varchar(7), full constructor + getters (no setters yet — AC4 adds 3 package-private ones if Option A is chosen).
- `RoomRuleVersionRepository` with `findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc` (the highest-effective-month lookup used by the daily evaluator — `architecture.md:733` + `RoomRuleVersionRepository.java:18-20`).
- `RulePresetEvaluator.shouldEvaluate(JsonNode, LocalDate)` (Story 1.2) consumes the `weekendInclude` boolean correctly (`RulePresetEvaluator.java:31-41`).
- `SurvivalStateService.evaluateRoom` (Story 1.2) reads the active rule via the repo method on line 183-187, then calls `RulePresetEvaluator.shouldEvaluate` on line 191. **This is the read site Story 5.1 must NOT regress.**
- `RoomService.requireLeader(Room, User)` (Story 1.1) exists at line 411-416, currently `private` with `@SuppressWarnings("unused")` annotation explicitly noting "wired by Stories 5.1, 5.2, 5.6". **This is the chokepoint Story 5.1 promotes.**
- `RoomService.create` (Story 1.1) sets `rooms.owner_id = creatorUserId` so `requireLeader` is meaningful from day 0 (epics line 113).

Story 5.1's job is exactly **three incremental layers** on top of all that:

1. **BE write site** — `RoomRuleService` + `RoomRuleController` exposing `PATCH /api/v1/rooms/{id}/rule` (Architecture §6.4 line 812) and a complementary `GET /api/v1/rooms/{id}/rule` for the FE editor pre-fill (incremental scope extension of §6.4).
2. **BE leader chokepoint wiring** — visibility flip on `RoomService.requireLeader` + injection into `RoomRuleService`. This is the **first-ever** wiring of the leader gate; subsequent Stories 5.2 (member-cap edit + transfer-leadership) and 5.6 (member-removal) inject the same method.
3. **FE editor surface** — D5 Plate sub-mode wrap on a new `/rooms/[id]/settings/rule` route with the locked preview literal + a settings-page entry row. The Story 5.4 chat SYSTEM message + Story 5.4 RealtimeEvent fan-out are EXPLICITLY out of scope (epics.md:734 marks the system message with "(Story 5.4)").

### Architecture deviation: GET /api/v1/rooms/{id}/rule is NOT in §6.4 table

Architecture §6.4 (line 802-817) enumerates only `PATCH /rooms/{id}/rule`. Story 5.1 introduces a matching `GET /rooms/{id}/rule` because the FE editor screen needs to pre-fill the current rule + show any pending edit before the leader's commit. Three documenting notes:

1. The GET is **member-scoped** (not leader-only) — every member needs to see the current rule + any pending change. Read-only does not break the next-month-only contract.
2. The GET response shape (`RoomRuleStateDto` wrapping current + nullable pending) is **strictly additive** — no existing endpoint changes wire shape.
3. After merge, the Architecture §6.4 table will read:
   ```
   | GET   | /rooms/{id}/rule | — | RoomRuleStateDto | room member |
   | PATCH | /rooms/{id}/rule | { preset, weekendInclude } | RoomRuleVersionDto | room leader |
   ```
   A follow-up doc PR will update `architecture.md:812` to include this row. (Pure docs change — does NOT block Story 5.1 merge.)

### Architecture deviation: NO new migration

V11 (Story 1.4) shipped the entire `room_rule_versions` schema + backfill. Story 5.1 does **NOT** create a new migration file. The dev pipeline:
- `BE/src/main/resources/db/migration/V11__*.sql` — already at production, unchanged.
- No `V12__rule_writes.sql` or similar — there is nothing to migrate.

The only schema-adjacent change is the JPA entity setter additions in AC4 (if Option A is chosen), which is a Java-side mutation surface, not a DDL change.

### Implementation trap #1 — DO NOT compute nextMonth from `EntryDateResolver`

`EntryDateResolver` (`com.yeosal.api.daily`) shifts the calendar day by -6 hours (the 06:00 KST entry-date boundary). It is the correct boundary for daily-entry dedupe, daily-evaluator targeting, and `priorEntryDate` in `SurvivalStateService.evaluateRoom`.

It is **NOT** the correct boundary for rule effective-month, because:
- A leader editing at `2026-04-30 23:30 KST` should set `effective_from_month = "2026-05"`. `EntryDateResolver.resolve(23:30 KST, KST)` returns `2026-04-30` (still in April-30's entry-date window, which spans 06:00 April 30 to 05:59 May 1). So `YearMonth.from(entryDate)` = `"2026-04"` and `nextMonth` = `"2026-05"`. Coincidentally correct here.
- A leader editing at `2026-05-01 02:00 KST` (between 00:00 and 06:00 KST). The calendar is **already May**, so the leader CANNOT change the May rule. `EntryDateResolver.resolve(02:00 KST May 1, KST)` returns `2026-04-30` (still in April-30's entry-date window). `YearMonth.from(entryDate)` = `"2026-04"` so `nextMonth` would be `"2026-05"`. **WRONG** — this lets the leader retroactively set the May rule at 02:00 May 1, 4 hours after May calendar-rolled in.

**Use calendar-month KST directly**: `YearMonth.from(LocalDate.ofInstant(clock.instant(), KST))`. This is the same shape used by `SurvivalStateService.evaluateRoom:180` (`YearMonth.from(priorEntryDate).toString()`) — once `priorEntryDate` is resolved, what matters is its calendar month, not the entry-date shift.

### Implementation trap #2 — `requireLeader` must NOT be duplicated

`RoomService.requireLeader` is the single chokepoint. Story 5.1 promotes it to `public` and injects `RoomService` into `RoomRuleService`. Do NOT:
- Add a parallel `isLeader(User, Room)` predicate. Predicates that don't throw are easy to forget to call.
- Inline `if (room.getOwner().getId() != me.getId()) throw ...` in `RoomRuleService`. Forks the auth contract across two classes.
- Move `requireLeader` to `Room.java`. Entities should not throw web-layer exceptions.

### Implementation trap #3 — Concurrent same-leader re-edit race

Leader double-taps the Save button so two concurrent PATCH requests target the same `nextMonth`. Both try to INSERT a row with the same `(room_id, effective_from_month)` UNIQUE key. Behaviors:
- **Option A** (find-or-create): Both PATCH-A and PATCH-B miss the existing row in their respective `findByRoomIdAndEffectiveFromMonth` calls. Both call `save(new RoomRuleVersion(...))`. PATCH-A commits successfully. PATCH-B hits `DataIntegrityViolationException` on commit, which `ApiExceptionHandler.dataIntegrity:121-129` currently translates to 500 `INTERNAL_ERROR`. **Bad UX.**
- **Option B** (native upsert with ON CONFLICT): Both PATCH-A and PATCH-B execute the same `INSERT ... ON CONFLICT DO UPDATE`. PATCH-A inserts; PATCH-B updates. Both return successfully with the same row id. **Good UX.**

**Recommendation: implement Option B.** It is structurally race-free and matches the existing project precedent of using PostgreSQL `ON CONFLICT` for idempotency (V11 step 13-15 backfill, `MeFriendGiftController` lifetime-1 guards).

If Option A is chosen instead, wrap the `save()` in a `try/catch (DataIntegrityViolationException)` that re-runs the find-then-update branch once. Document the retry in JavaDoc — invisible state machines bite.

### Implementation trap #4 — JSONB cast in native upsert

If using Option B's native query, the parameterized JSONB payload MUST be cast explicitly:

```sql
values (?1, ?2, cast(?3 as jsonb), ?4, now())
```

Without `cast(... as jsonb)`, Postgres receives a `text` parameter and the `INSERT` fails with `column "rule_payload" is of type jsonb but expression is of type character varying`. The dev's JSON marshalling step is `mapper.writeValueAsString(mapper.createObjectNode().put("preset", preset).put("weekendInclude", weekendInclude))` to produce the bind string.

### Implementation trap #5 — `created_at` on replace

For Option A, the `@PrePersist` on `RoomRuleVersion` only auto-fills `createdAt` on INSERT. The replace path uses dirty-check UPDATE, which does NOT fire `@PrePersist`. AC4's third setter (`setCreatedAt`) MUST be called explicitly in the replace branch, otherwise `created_at` stays at the original-insert timestamp and the leader's most-recent edit time is invisible.

For Option B, the `now()` in the SQL handles this on both INSERT and UPDATE branches.

### Implementation trap #6 — Boolean primitive vs boxed in `UpdateRoomRuleRequest`

Jackson default deserialization cannot distinguish a missing `weekendInclude` field from an explicit `false` when the record component is the primitive `boolean`. Use `Boolean` (boxed) so `@NotNull` catches the missing case at validation time. The service-layer call site unwraps via `body.weekendInclude()` which on a non-null Boolean auto-unboxes to primitive boolean without NPE risk after the `@NotNull` gate.

### Implementation trap #7 — `JsonNode` immutability mismatch

`RoomRuleVersion.rulePayload` is a `JsonNode`. Jackson's `ObjectMapper.createObjectNode()` returns an `ObjectNode` (mutable subclass of `JsonNode`). Storing the ObjectNode directly into the entity is fine — JPA flushes the JSON serialization of whatever node it holds. Just don't mutate the node after handing it to JPA — build the node fresh per write.

### Implementation trap #8 — FE `<SubModeProvider subMode="plate">` is page-level

The D5 Plate sub-mode wrapper MUST live at the page level (top of `rule.tsx`), NOT inside leaf components. UX rule (line 1177-1181):
> - subMode은 **page-level prop**으로 주입 (`<RoomScreen subMode="quiet"/>`).
> - 컴포넌트는 `useTheme()` 훅이 resolved 토큰만 반환 — sub-mode를 *모름*.
> - 한 화면 안에서 sub-mode 혼합 ❌

Mirror the precedent at `FE/app/wallet/[roomId].tsx:25-27` (`<SubModeProvider subMode="bento">` wraps the screen body). The settings page at `FE/app/rooms/[id]/settings.tsx` does NOT wrap in a sub-mode currently (it is base) — leave that alone; only the new `/settings/rule` route gets the D5 wrap.

### Implementation trap #9 — Leader-detect on FE must use `useRoomsQuery` not membership

Some prior stories (3.3 friend-gift badge) used `useRoomMembersQuery(roomId)` to find membership data. For leader-detection here, use `useRoomsQuery()` instead — `Room.ownerId` is on the room itself (FE/src/api/rooms.ts line 22 `ownerId: number`), no need to walk member list. `useRoomsQuery` is already wired (see `settings.tsx:14`). Cheaper + no per-member fanout.

### Implementation trap #10 — Don't pre-emit chat SYSTEM message

Story 5.4 owns `chat_messages` row insertion with `kind='SYSTEM'`, body `'다음 달부터 새 규칙이 적용됩니다: …'`, payload `{ ruleVersionId, effectiveFromMonth, preview }` (epics.md:790-804). Story 5.1's dev agent MAY be tempted to fire-and-forget a `chatService.publishSystem(...)` call on successful rule write — DO NOT. Reasons:

1. Story 5.4 includes specific brand-voice constraints on the preview string + a UNIQUE-replace re-broadcast scenario that hasn't been fully scoped yet.
2. The `RealtimeEvent.RuleChange` topic + WS fan-out is Story 5.4's wiring; pre-emitting the SYSTEM row without the realtime piece creates a half-shipped feature.
3. The architecture-locked AC1 return shape is `RoomRuleVersionDto` (the single row). Adding a chat row write inside `RoomRuleService.updateRule` would either (a) widen the return shape (breaking AC1) or (b) silently happen as a side effect (untestable from the controller layer).

If the leader edits via Story 5.1 between merge of 5.1 and merge of 5.4, the members will NOT see a chat broadcast for that edit — that's the **accepted gap** during the staged rollout. The next-month-only contract integrity holds regardless.

### Implementation trap #11 — Auth chain uses `currentUser.require(auth)` not method-arg `@AuthenticationPrincipal`

Every existing controller in this codebase uses the `CurrentUser.require(Authentication)` chain (see `RoomController:46,67,73,89,101,107,113,124`, `MeSurvivalController`, `RevivalController`, etc.). Do NOT introduce `@AuthenticationPrincipal Jwt jwt` or similar — keep the auth resolution pattern uniform across the controller layer.

### Implementation trap #12 — `RoomRuleService` GET needs `@Transactional(readOnly = true)`

The GET in AC5 does TWO repository calls + ONE membership-existence check. They MUST live inside a single `@Transactional(readOnly = true)` boundary so:
1. Snapshot consistency — the leader's pending edit and the current-month row are read from the same Postgres snapshot.
2. `open-in-view: false` compliance — project-context line 92 mandates lazy loads happen inside `@Transactional` boundaries.

The PATCH must use `@Transactional` (writable). Don't mix the two annotation flavors.

### Implementation trap #13 — Test clock injection

The service must accept `Clock` via constructor. Tests inject a fixed clock to assert nextMonth boundary behavior:

```java
Clock april30LateNight = Clock.fixed(
    LocalDateTime.of(2026, 4, 30, 23, 59, 30)
        .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
    ZoneId.of("Asia/Seoul"));
// nextMonthKST() should return "2026-05"

Clock may1Early = Clock.fixed(
    LocalDateTime.of(2026, 5, 1, 2, 0, 0)
        .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
    ZoneId.of("Asia/Seoul"));
// nextMonthKST() should return "2026-06"
```

A test that hardcodes `Clock.systemUTC()` or `Clock.systemDefaultZone()` is non-deterministic and will flake on month boundaries — reject any such test in code review.

### Implementation trap #14 — `findByRoomIdAndEffectiveFromMonth` is a NEW repo method

The existing repo has only `findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc` (the descending-effective-month lookup). The story's "find exact pending row" check requires a **new** Spring Data method:

```java
Optional<RoomRuleVersion> findByRoomIdAndEffectiveFromMonth(
    long roomId, String effectiveFromMonth);
```

Spring Data derives the query from the method name — no `@Query` annotation needed.

### Architecture decisions traceability

| FR | AC | File |
|----|----|------|
| FR-8.5.1 (Leader equals `rooms.owner_id`) | AC1 step 3, AC8 | `RoomService.requireLeader` |
| FR-8.5.2 (rule effective at instant T = max `effective_from_month <= currentMonth`) | AC9 | `SurvivalStateService.evaluateRoom:183-187` (read site, untouched) |
| FR-8.5.3 (PATCH /rules creates next-month-only row) | AC1, AC2, AC3 | `RoomRuleController` + `RoomRuleService.updateRule` |
| FR-8.5.8 (chat SYSTEM broadcast) | OUT OF SCOPE | Story 5.4 |
| Architecture §6.4 PATCH /rooms/{id}/rule | AC1 | `RoomRuleController.PATCH("/{id}/rule")` |
| Architecture §6.4 (NEW) GET /rooms/{id}/rule | AC5 | `RoomRuleController.GET("/{id}/rule")` |
| Architecture V11 (8) `room_rule_versions` | AC3 | upsert against existing table |
| project-context.md:92 (KST day-boundary) | AC2 | `ZoneId.of("Asia/Seoul")` |
| project-context.md:270 (No per-user TZ) | AC2 | hardcoded `Asia/Seoul` |

### Out of scope (DO NOT IMPLEMENT IN THIS STORY)

1. **Chat SYSTEM message on rule change** — Story 5.4 (`chatService.publishSystem(...)` with `kind='SYSTEM'`, the `'다음 달부터 새 규칙이 적용됩니다: …'` body, the `{ ruleVersionId, effectiveFromMonth, preview }` payload).
2. **`RealtimeEvent.RuleChange` WS topic emission** — Story 5.4 (`RealtimePublisher.publishRuleChange(...)`).
3. **Member-cap edit + leader transfer + `POST /rooms/{id}/transfer-leadership`** — Story 5.2.
4. **Leader-driven member removal `DELETE /rooms/{id}/members/{userId}`** — Story 5.2 (FR-8.5.5).
5. **Auto-leader-promotion on RED transition** — Story 5.3 (FR-8.5.7).
6. **New `preset` values beyond `DAILY_UPDATE`** — out of v1 scope (`RulePresetEvaluator:14` comment locks v1 to single preset).
7. **Per-day rule overrides** — out of scope; only monthly rule rows in v1.
8. **Rule preview with telemetry** — no analytics SDK in v1 (Story 8.5 deferred).
9. **Rule history UI / past-month rules display** — out of scope; the editor shows only current + pending.
10. **`weekendInclude=true` toggle "with caveat" copy** — the preview literal is fixed regardless of toggle direction (epics line 720 — "I propose a new rule (e.g., toggle `weekendInclude` from true to false)" — but the preview literal does not branch on direction).
11. **`SurvivalStateService` modifications** — read site is correct (AC9). DO NOT touch.
12. **`SurvivalStateEvaluatorJob` modifications** — scheduler/cron unchanged.
13. **`RulePresetEvaluator` modifications** — v1 preset list unchanged.
14. **`tokens.json` modifications** — D5.plate already exists at line 187 (verified via grep).
15. **New ApiExceptionHandler mappings** — existing 403 FORBIDDEN + 400 VALIDATION + 404 NOT_FOUND cover Story 5.1's error surface.
16. **New STOMP topic regex permits in `JwtChannelInterceptor`** — Story 5.1 emits no realtime frames.
17. **GeneratedTokens.java additions** — no new theme tokens needed; D5.plate already wired.

### Project structure notes

- BE files under `BE/src/main/java/com/yeosal/api/survival/` (same package as `RoomRuleVersion`, `RoomRuleVersionRepository`, `RulePresetEvaluator`, `SurvivalStateService`). Same-package proximity preserves the rule-domain boundary.
- The `requireLeader` visibility flip is in `BE/src/main/java/com/yeosal/api/room/RoomService.java` (room-domain), which is a different package. `RoomRuleService` injects `RoomService` to call the now-public method.
- FE files under `FE/src/api/`, `FE/src/lib/query/`, `FE/app/rooms/[id]/settings/`. The new `/settings/rule` nested route follows Expo Router conventions used in `FE/app/wallet/[roomId]/ledger.tsx` precedent (Story 3.4).
- Tests mirror source layout: `BE/src/test/java/com/yeosal/api/survival/*Test.java` + `*IT.java`; `FE/src/lib/query/__tests__/*.test.tsx`; `FE/app/rooms/[id]/settings/__tests__/*.test.tsx` (or wherever Expo Router test convention places nested-route tests in this repo — match the closest existing precedent).

### References

- Epics: `_bmad-output/planning-artifacts/epics.md:704-734` (Epic 5 + Story 5.1 ACs)
- PRD: `_bmad-output/planning-artifacts/prd.md:401-408` (FR-8.5.1 through FR-8.5.8), `prd.md:205-213` (J5 narrative), `prd.md:305-316` (PRD §6.3 decisions table — leader = owner_id, leader-elim auto-promote)
- Architecture: `_bmad-output/planning-artifacts/architecture.md:733-742` (V11 step 8 schema), `architecture.md:787-794` (V11 step 14 backfill), `architecture.md:812` (§6.4 PATCH endpoint), `architecture.md:561-565` (planned RoomRuleVersion package layout)
- UX: `_bmad-output/planning-artifacts/ux-design-specification.md:1136-1155` (D5 Plate System sub-mode + surface assignment), `ux-design-specification.md:1369-1386` (J5 mermaid flow), `ux-design-specification.md:205-213` (J5 narrative)
- project-context: `_bmad-output/project-context.md:92,270` (KST day-boundary), `project-context.md:271` (No per-user TZ — `Asia/Seoul` hardcoded)
- Existing BE code: `BE/src/main/java/com/yeosal/api/survival/RoomRuleVersion.java`, `RoomRuleVersionRepository.java`, `RulePresetEvaluator.java`, `SurvivalStateService.java:177-307`, `SurvivalStateEvaluatorJob.java`; `BE/src/main/java/com/yeosal/api/room/RoomService.java:411-416` (the chokepoint)
- Existing FE code: `FE/src/api/rooms.ts` (extend pattern), `FE/src/lib/query/keys.ts` (extend pattern), `FE/app/rooms/[id]/settings.tsx` (entry-row mount point), `FE/app/wallet/[roomId].tsx:11,25-27` (SubModeProvider precedent), `FE/src/providers/SubModeProvider.tsx`

### Change log

| Date | Author | Change |
|------|--------|--------|
| 2026-06-02 | Maya (context engineer) | Initial context-engineered story file. Epic-5 backlog-to-in-progress flip (first story); leader chokepoint promotion + write-site introduction; 14 implementation traps catalogued; 17-item out-of-scope list locking Story 5.4 chat broadcast + Story 5.2/5.3 deferrals. |
| 2026-06-02 | Amelia (dev agent) | Story 5.1 implementation complete (in-progress → review). 5 new BE source files + 1 BE source modified + 1 BE repo extended + 3 new BE test files. 1 new FE component + 1 new FE hook + 1 new FE route + 3 FE files extended + 3 new FE test files. Option B (native ON CONFLICT upsert) chosen per Trap #3 recommendation — entity setters in AC4 skipped. BE 80 classes / 0 failures, FE 57 suites / 437 tests, brand-voice 0 HARD, ESLint clean on touched files. Manual smoke + opt-in IT deferred to PR-open. Documented deviation: editor component lives at `src/components/rooms/RoomRuleEditor.tsx` (not inline in the route file) because Jest `testMatch` is scoped to `<rootDir>/src/**/__tests__/`; the route file is a thin SubModeProvider wrapper. |
| 2026-06-02 | Codex review patch | Addressed all 9 review patches: fresh-room default-rule seeding for normal + default-room runner paths, single-instant rule snapshot, native upsert PostgreSQL IT, pending-aware FE dirty baseline, refetch-safe draft preservation, rooms-query error state, safe-integer route ids, scope-fence reconciliation, and durable source comments. |

## Dev Agent Record

### Debug log

- BE/FE compile + tests green on first run; no debug iterations needed.
- `npm run typecheck` surfaces 2 pre-existing errors in `FriendsTodayPager.tsx` (missing `react-native-pager-view` types) — unrelated to Story 5.1, baseline confirmed via Story 4.1 sprint-status note.
- `npm test` shell wrapper attempted to chain lint and exited on the pre-existing FE lint baseline (4 errors: `react-hooks/exhaustive-deps` plugin not found + `@typescript-eslint/no-require-imports` on SurvivalChip tests + `InviteCodeSheet.test.tsx` unused directive). Running `npx jest` directly yields a clean 57/437 pass count. The lint baseline is documented as deferred chore in Story 4.1 sprint-status comment.

### Completion notes

- **All 14 acceptance criteria satisfied.** AC4 is N/A (Option B native upsert chosen, no `RoomRuleVersion` setter additions needed).
- **Architecture deviation documented:** `RoomRuleEditor` component extracted from the route file into `src/components/rooms/RoomRuleEditor.tsx`. The route file (`app/rooms/[id]/settings/rule.tsx`) is a thin wrapper that resolves the dynamic `id` param, validates `roomId > 0`, and mounts `<RoomRuleEditor>` inside `<SubModeProvider subMode="plate">`. Necessary because Expo Router `app/` files are excluded by the FE Jest `testMatch` pattern.
- **AC12 scope-fence holds after review reconciliation.** The allowlist now includes fresh-room invariant wiring and the documented extracted editor/test paths. Zero touches to `SurvivalStateService.java`, `SurvivalStateEvaluatorJob.java`, `RulePresetEvaluator.java`, `chat/**`, `realtime/**`, `db/migration/**`, `tokens.json`, `ApiExceptionHandler.java`, or `GeneratedTokens.java`.
- **AC13 test count delta (vs `origin/main`):** BE +24 cases — `RoomRuleServiceTest` 14 + `RoomRuleControllerTest` 7 + `RoomRuleNextMonthEvaluatorIT` 3. FE +14 cases — `rooms.rule.test.ts` 2 + `roomRule.test.tsx` 4 + `RoomRuleEditor.test.tsx` 8.
- **Brand-voice:** 0 HARD violations. Preview literal "변경된 규칙은 다음 달 1일부터 적용됩니다." byte-identical to PRD lock (32 chars, ASCII period). AVOID lexicon verified absent by the `RoomRuleEditor` brand-voice test case (JSON tree dump assertion).
- **No emojis in source** per project-context global rule.
- **Verify pipeline summary after review:** BE `./gradlew test` passed. FE `npx jest --runInBand --no-watchman` → 57 suites / 440 tests / 9 snapshots passed. `tools/brand-voice-lint.ts` → 0 HARD / 188 warnings. Scoped ESLint and `git diff --check HEAD` passed. FE `npx tsc --noEmit` still reports the 2 pre-existing `FriendsTodayPager.tsx` errors. `bash scripts/verify.sh` still stops at the pre-existing FE lint baseline (4 errors / 2 warnings). Opt-in Testcontainers execution was attempted but Docker is unavailable on this host; the new PostgreSQL upsert IT remains CI/Docker-host verification work.

### File List

**BE — new source (5):**
- `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java`
- `BE/src/main/java/com/yeosal/api/survival/RoomRuleController.java`
- `BE/src/main/java/com/yeosal/api/survival/RoomRuleVersionDto.java`
- `BE/src/main/java/com/yeosal/api/survival/RoomRuleStateDto.java`
- `BE/src/main/java/com/yeosal/api/survival/UpdateRoomRuleRequest.java`

**BE — modified source (4):**
- `BE/src/main/java/com/yeosal/api/room/RoomService.java` (leader gate promotion + fresh-room default-rule seed)
- `BE/src/main/java/com/yeosal/api/room/DefaultRoomMigrationRunner.java` (default-room rule seed)
- `BE/src/main/java/com/yeosal/api/survival/RoomRuleVersion.java` (durable invariant comment)
- `BE/src/main/java/com/yeosal/api/survival/RoomRuleVersionRepository.java` (exact finder + native update/default upserts)

**BE — new tests (3):**
- `BE/src/test/java/com/yeosal/api/survival/RoomRuleServiceTest.java` (14 cases)
- `BE/src/test/java/com/yeosal/api/survival/RoomRuleControllerTest.java` (7 cases)
- `BE/src/test/java/com/yeosal/api/survival/RoomRuleNextMonthEvaluatorIT.java` (3 cases, opt-in `yeosal.boot-smoke`)

**BE — modified regression fixtures (4):**
- `BE/src/test/java/com/yeosal/api/room/RoomServiceTest.java`
- `BE/src/test/java/com/yeosal/api/room/RoomServiceEvaluationTest.java`
- `BE/src/test/java/com/yeosal/api/room/RoomServiceMemberJoinSystemMessageTest.java`
- `BE/src/test/java/com/yeosal/api/room/RoomControllerIT.java`

**FE — new source (3):**
- `FE/src/lib/query/hooks/roomRule.ts`
- `FE/src/components/rooms/RoomRuleEditor.tsx`
- `FE/app/rooms/[id]/settings/rule.tsx`

**FE — modified source (3):**
- `FE/src/api/rooms.ts` (add `RoomRuleVersionDto`, `RoomRuleStateDto`, `UpdateRoomRuleVars` + `getRoomRule`, `updateRoomRule`)
- `FE/src/lib/query/keys.ts` (add `qk.roomRule(roomId)`)
- `FE/app/rooms/[id]/settings.tsx` (entry row navigating to `/rooms/{id}/settings/rule`)

**FE — new tests (3):**
- `FE/src/api/__tests__/rooms.rule.test.ts` (2 cases)
- `FE/src/lib/query/hooks/__tests__/roomRule.test.tsx` (4 cases)
- `FE/src/components/rooms/__tests__/RoomRuleEditor.test.tsx` (8 cases)

**Tracking artifacts (2):**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (5-1 ready-for-dev → in-progress → review; comment header updated)
- `_bmad-output/implementation-artifacts/5-1-rule-edit-with-next-month-only-application.md` (this file — task checkboxes flipped, Status review, Dev Agent Record added)

## Testing

- BE: Mockito unit tests + WebMvcTest controller slice + Testcontainers IT (opt-in via `yeosal.boot-smoke` system property — mirrors `FriendGiftConcurrencyIT`, `WalletPrivacyDefenceIT`; `RoomRuleNextMonthEvaluatorIT` is new).
- FE: Jest + React Testing Library; rule editor screen rendering test asserts the verbatim preview literal byte-equality.
- Manual smoke: iOS sim — leader login, navigate to /rooms/{id}/settings, tap rule row, editor renders in D5.plate sub-mode, toggle weekendInclude, save, reload, confirm pending row visible, log in as non-leader (another seat in the same room), confirm read-only view + same pending row visible.
- Brand-voice: `tools/brand-voice-lint.ts` returns 0 HARD violations.
- Scope-fence: `git diff --stat origin/main` matches AC12 file list exactly.
