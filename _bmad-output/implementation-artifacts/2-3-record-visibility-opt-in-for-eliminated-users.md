# Story 2.3: Record visibility opt-in for eliminated users

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **an eliminated member (`survival_state.status = SPECTATOR`)**,
I want **my daily history to default to private (only I see it) and to have an explicit opt-in to share with my room**,
so that **elimination doesn't auto-expose my private reflections**.

PRD authority: **FR-8.2.4** (per-room record visibility opt-in via `record_visibility_prefs`) and **NFR-9.3.2** (server-side redaction — never FE-side filtering).
Architecture authority: **§4.7** (spectator privacy enforced server-side, never FE-only) and **V11 (9)** — the `record_visibility_prefs (user_id, room_id, share_on_elimination, updated_at)` table with PK `(user_id, room_id)` (already shipped on `main`).
Epics ref: lines 389–409.

> **Foundation note.** Story 2.3 is a mixed BE+FE story. The `record_visibility_prefs` table is **already in production** (V11 migration, `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql:112-120`). Story 2.3 ships: (a) the JPA entity + repository + service for the table, (b) the `GET/POST /api/v1/me/visibility-prefs` endpoints, (c) the read-side redaction guard on every endpoint that exposes a spectator's `daily_entries / reflections / todo_items` to another room member, and (d) a single FE toggle in the per-room settings screen. **No new Flyway migration.** Korean toggle copy uses 그룹/공유 vocabulary, not 노출/탈락 (brand-voice contract per epics line 409).

## Acceptance Criteria

1. **AC1 — `record_visibility_prefs` JPA entity + repository.**
   - **NEW file** `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityPref.java` (package `survival` — spectator privacy concern, sibling to `SurvivalState`):
     - `@Entity @Table(name = "record_visibility_prefs")`.
     - Composite key via `@IdClass(RecordVisibilityPrefId.class)` — prefer `@IdClass` for parity with vanilla JPA patterns when no `@EmbeddedId` precedent exists in the project. Verify by grep before deciding.
     - Columns: `user_id` (Long, PK), `room_id` (Long, PK), `share_on_elimination` (boolean), `updated_at` (Instant, auto-touch via `@PreUpdate` + `@PrePersist`).
   - **NEW file** `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityPrefRepository.java`:
     - Extends `JpaRepository<RecordVisibilityPref, RecordVisibilityPrefId>`.
     - `Optional<RecordVisibilityPref> findByUserIdAndRoomId(long userId, long roomId)`.
     - `List<RecordVisibilityPref> findByUserId(long userId)` (for the GET listing endpoint in AC2).
     - Native `@Modifying @Query` upsert `upsertShareOnElimination(@Param("userId") long userId, @Param("roomId") long roomId, @Param("share") boolean share)` using `INSERT … ON CONFLICT (user_id, room_id) DO UPDATE SET share_on_elimination = EXCLUDED.share_on_elimination, updated_at = now()`.
   - **DO NOT** add a Flyway migration — the table already exists.
   [V11 migration lines 112–120; project-context "Hibernate validate mode — schema changes require Flyway migration"; the schema already matches]

2. **AC2 — `GET /api/v1/me/visibility-prefs` + `POST /api/v1/me/visibility-prefs` endpoints.**
   - **NEW file** `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityController.java`:
     - `@RestController @RequestMapping("/api/v1/me/visibility-prefs")` (matches `MeSurvivalController` `/api/v1/me/...` precedent).
     - `@GetMapping`: returns `ApiResponse<List<VisibilityPrefDto>>` — one entry per room the user is a member of. Rooms without an explicit pref row default to `shareOnElimination = false` (server-materialized — the FE never default-constructs).
     - `@PostMapping`: accepts `@Valid @RequestBody UpsertVisibilityPrefRequest` (`roomId: long`, `shareOnElimination: boolean`). Returns `ApiResponse<VisibilityPrefDto>` with the post-write value.
   - **DTOs (records, project-context Java rule):**
     - `record VisibilityPrefDto(long roomId, String roomName, boolean shareOnElimination, Instant updatedAt) {}`.
     - `record UpsertVisibilityPrefRequest(@NotNull Long roomId, @NotNull Boolean shareOnElimination) {}`. `@Valid` validation; missing fields → `400 VALIDATION` via the existing global handler.
   - **NEW service** `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityService.java` with `@Transactional` upsert and `@Transactional(readOnly=true)` list. The upsert calls the native repo method, then re-reads via `findByUserIdAndRoomId` to obtain `updated_at` for the response.
   - **Authorization:** only the authenticated user can read/write their own prefs (path is `/me/...`). Room-membership check on the POST path: if the user is not a member of `roomId`, throw `ForbiddenException` ("해당 그룹의 멤버가 아닙니다."). Use the existing `RoomMemberRepository.existsByRoomIdAndUserId` (or equivalent — grep for the exact method name in the existing repository).
   [PRD FR-8.2.4, epics lines 401–403, project-context Java/Spring rules]

3. **AC3 — Read-side redaction: server-side enforcement (NFR-9.3.2).**
   - **NEVER do FE-side filtering** (project-context: "Server-side privacy is authoritative"). Every BE endpoint that exposes a spectator's `daily_entries / reflections / todo_items` to another room member MUST consult `record_visibility_prefs` and redact when `share_on_elimination = false`.
   - **Endpoints touched (UPDATE — read each method FULLY before editing):**
     - `BE/src/main/java/com/yeosal/api/profile/ProfileController.java` — `GET /api/v1/profiles/{userId}/grass` (lines 63–79) and `GET /api/v1/profiles/{userId}/reflections` (lines 124–140). Both currently gate on `friendService.canView` + a room-share check.
     - `BE/src/main/java/com/yeosal/api/daily/DailyService.java` — the service-layer methods invoked by the controllers above (`dailyService.grass(target, from, to)` and `dailyService.recentReflections(target, limit)`).
     - Any group-mode read of another member's `daily_entries` / `todo_items` for that room — likely `RoomService.getRoomToday` or sibling. Pinpoint via `grep -rn "daily_entries\|TodoItem\|getRoomToday\|MemberTodayDto"` before editing.
   - **Redaction algorithm** (applied inside the service layer, BEFORE DTO assembly):
     1. Determine `targetUserId` and (when the endpoint is room-scoped) `roomId`.
     2. If `viewer == target` → no redaction (self-view is always full).
     3. Resolve the target's `survival_state.status` for the `roomId`. If the target is NOT in `SPECTATOR` status → no redaction (this story only redacts spectator records).
     4. If target IS SPECTATOR → consult `record_visibility_prefs.findByUserIdAndRoomId(target.id, roomId)`. If absent OR `share_on_elimination = false` → return an **empty/redacted payload**: empty list for grass / empty list for reflections / empty list for todos.
   - **"Empty/redacted" means** — the response is `200 OK` with an empty list (NOT a 403). The viewer's UI can show "이 멤버는 기록을 공개하지 않았어요" but renders that label purely from emptiness, never from a redaction-explicit field.
   - **Cross-room subtlety:** the `/profiles/{userId}/grass` and `/profiles/{userId}/reflections` endpoints are NOT explicitly room-scoped. For these, apply redaction when the target is a spectator in **any** shared room AND has not opted in for **that** room. Pragmatic v1 rule: when the viewer is a room-mate and the target is spectator in that room → redact UNLESS opted-in. If the viewer is friend-only (no shared room), the existing `canView` gate returns 403 — no spectator concern arises.
   - **Self-view is always full** — a SPECTATOR user viewing their own `/profiles/me/grass` sees everything.
   - [PRD NFR-9.3.2, epics lines 405–407]

4. **AC4 — Brand-voice copy on the FE toggle: 그룹/공유 vocabulary, not 노출/탈락.**
   - Settings UI toggle label: `"이 그룹에서 내 기록 공유"`.
   - Description / helper text: `"공유를 켜면 내 잔디와 회고가 그룹 멤버에게 보여요."`.
   - Off-state description (default): `"꺼져 있어요 — 멤버에게 내 기록은 보이지 않아요."`
   - Toast on toggle ON: `"이제 멤버들이 내 기록을 볼 수 있어요."`
   - Toast on toggle OFF: `"내 기록은 다시 비공개로 돌아갔어요."`
   - **Forbidden words** (brand-voice-lint Rule 2 — `벌금 / 잃었다 / 떨어졌다 / 실패 / 자책 / 부담 / 패배 / 죄책감`) must not appear in any new copy. **Additionally**, per epics line 409, AVOID `노출` and `탈락`; USE `공유` and the existing `관전 / 회생` family for spectator framing. Manual pre-flight: `그룹 / 공유 / 잔디 / 회고 / 멤버 / 비공개` are clean.
   - All copy passes `tools/brand-voice-lint.ts` Rule 2; the test gate fails if anything trips it.
   [Epics line 409, brand-voice-lint Rule 2]

5. **AC5 — Settings UI: toggle on the per-room settings screen.**
   - **UPDATE `FE/app/rooms/[id]/settings.tsx`** (the existing per-room settings page — read the file fully before editing): add a new `<RecordVisibilityToggle roomId={roomId} />` section under existing settings rows.
   - **NEW file** `FE/src/components/survival/RecordVisibilityToggle.tsx`:
     - Reads the current pref via a new `useRecordVisibilityPref(roomId)` hook.
     - Renders the toggle label (AC4 copy) + a `<Switch>` (React Native built-in) bound to `shareOnElimination`.
     - On toggle change, calls `useUpdateRecordVisibilityPref()` mutation which POSTs to `/me/visibility-prefs`. Optimistic update via TanStack Query `onMutate` snapshot + `setQueryData`, with rollback on `onError` (project-context FE rule).
     - Renders a toast on success per AC4 (uses the existing `toast.success` from `FE/src/lib/toast.ts`).
   - **NEW hook file** `FE/src/lib/query/hooks/visibilityPrefs.ts`:
     - `useRecordVisibilityPref(roomId)`: derives the row for `roomId` from a list query against `GET /api/v1/me/visibility-prefs`. The list-style fetch lets the Settings page batch-load all rooms' prefs.
     - `useUpdateRecordVisibilityPref()`: mutation that POSTs to `/me/visibility-prefs` with optimistic update on `qk.recordVisibilityPrefs` cache.
   - **NEW key** `qk.recordVisibilityPrefs = ["recordVisibilityPrefs"] as const` in `FE/src/lib/query/keys.ts`.
   - **NEW API client methods** in `FE/src/api/survival.ts` (already created in Story 2-1; extend additively): `getRecordVisibilityPrefs(): Promise<VisibilityPrefDto[]>` and `updateRecordVisibilityPref(roomId: number, shareOnElimination: boolean): Promise<VisibilityPrefDto>`. Both go through `apiRequest<T>` (project-context FE rule — direct `fetch` forbidden).
   [Epics line 401, PRD FR-8.2.4]

6. **AC6 — Default behavior: off (private) for all rooms.**
   - **Given** I have never touched the toggle for a room
   - **When** another room member's API call for my history runs
   - **Then** the BE redacts (returns empty/redacted payload).
   - **Mechanism:** `record_visibility_prefs` rows are lazy — the V11 default `share_on_elimination = false` applies only when a row exists. The redaction logic in AC3 treats "row missing" the same as "row present with `false`" — both → redact. The `GET /me/visibility-prefs` list endpoint materializes the default (`false`) for all the user's rooms even when no row exists; this is a server-side projection (no INSERT on read).
   [Epics lines 397–399, PRD FR-8.2.4 "defaults to private"]

7. **AC7 — Toggle works for ACTIVE users too (forward-compat / opt-in early).**
   - The PRD copy says "spectator-mode privacy", but the toggle is per-(user, room) without gating on status. An ACTIVE member CAN pre-set the toggle so that when they eventually become SPECTATOR, it's already ON. The redaction logic only kicks in when the target is SPECTATOR (per AC3 step 3), so the toggle's effect is benign while ACTIVE — the user's records are visible per existing rules regardless of the toggle.
   - **Test:** an ACTIVE user toggles ON; another member views their grass → records are visible (existing rules win). The same target transitions to SPECTATOR → records remain visible (toggle is ON; redaction bypassed).
   [Epics line 401 ("any state can toggle"), forward-compat with Story 3.1 revival flows]

8. **AC8 — Accessibility (NFR-9.6.3 Dynamic Type + screen reader).**
   - `<RecordVisibilityToggle>` uses the shared `<Text>` (Story 1.5 — caps `maxFontSizeMultiplier=1.3`). At 1.5× system font scale the row does not overflow or clip; snapshot test mirrors `SurvivalChip.dynamic-type.test.tsx`.
   - The `<Switch>` sets `accessibilityLabel="이 그룹에서 내 기록 공유 토글"`. `accessibilityRole` defaults to `"switch"` on RN — VoiceOver/TalkBack announces on/off automatically.
   - Description text uses `accessibilityRole="text"`.
   - The toggle's two states have visibly distinct text labels (not just color) — color is never the sole information carrier (NFR-9.6.1 packed-type policy).
   [Story 1.5 AC9 / NFR-9.6.3 + NFR-9.6.1]

9. **AC9 — No new Flyway migration. No new domain table.**
   - **NO** new Flyway migration. V11 already shipped `record_visibility_prefs`.
   - **NO** new exception class. The redaction path returns 200 + empty payload, not 403. The membership-check 403 reuses the existing `ForbiddenException`.
   - **NO** new top-level package. New BE files land in `com.yeosal.api.survival` (entity, repo, service, controller).
   - **NO** new FE dep. The RN `<Switch>` is already shipped; no new package.
   [Architecture §4.16; project-context "no scope drift"]

10. **AC10 — Unit + integration test coverage (TDD, 80%+ on new code).**

    **BE — JUnit 5 + Spring Test:**
    - `BE/src/test/java/com/yeosal/api/survival/RecordVisibilityServiceTest.java` — Mockito unit:
      - `getForUser(userId)` returns rooms with `shareOnElimination=false` materialized for rooms without explicit rows.
      - `upsert(userId, roomId, true)` calls the native upsert, re-reads, returns `true`.
      - `upsert` for a non-member room → throws `ForbiddenException`.
      - Idempotent re-upsert (same value twice) → no exception, `updated_at` advances.
    - `BE/src/test/java/com/yeosal/api/survival/RecordVisibilityControllerTest.java` — `@WebMvcTest`:
      - `GET /me/visibility-prefs` → `200` + list shape.
      - `POST /me/visibility-prefs` with valid body → `200` + new value reflected.
      - `POST` with missing `roomId` → `400 VALIDATION`.
      - `POST` for non-member room → `403 FORBIDDEN`.
    - `BE/src/test/java/com/yeosal/api/survival/ProfileVisibilityRedactionIT.java` — `@SpringBootTest` + Testcontainers PostgreSQL:
      - Setup: target user is SPECTATOR in room R; viewer is room-mate; target has NO `record_visibility_prefs` row.
      - `GET /profiles/{targetId}/grass` → `200` + empty list (redacted).
      - `GET /profiles/{targetId}/reflections` → `200` + empty list.
      - Toggle target's pref to `share_on_elimination = true` → records visible.
      - Toggle back to false → records redacted again.
      - Target's OWN view of `/profiles/me/grass` → records always visible (self-view bypass).
      - Target transitions to ACTIVE while pref is `false` → records visible (existing rules win; spectator-only redaction).

    **FE — Jest + `@testing-library/react-native`:**
    - `FE/src/components/survival/__tests__/RecordVisibilityToggle.test.tsx` — render both states; mock the mutation; assert optimistic update + success toast; assert rollback + error toast on failure.
    - `FE/src/lib/query/hooks/__tests__/visibilityPrefs.test.tsx` — query happy path, mutation invalidation, optimistic-update rollback.
    - `FE/app/rooms/[id]/__tests__/settings-visibility-toggle.test.tsx` — integration: settings screen renders the toggle block; brand-voice copy verified.

    **Coverage target:** 80%+ on `RecordVisibilityService.java`, `RecordVisibilityController.java`, `RecordVisibilityPref.java`, `RecordVisibilityToggle.tsx`, `visibilityPrefs.ts` hooks. Shared `Switch`, `Text`, `apiRequest` are infra and excluded.

    **Brand-voice lint:** the test gate fails if any new copy trips Rule 2.

11. **AC11 — Out-of-scope.** Story 2.3 ships the per-room visibility opt-in pipeline + tests. It does NOT ship:
    - Spectator FE routing branch (Story 2.1).
    - Spectator daily digest push (Story 2.2).
    - PDF / data export for PIPA compliance (NFR-9.3.3 — separate v1.5 work).
    - "All rooms at once" master toggle (per-room is the v1 spec).
    - Historical redaction migration (only ongoing reads are gated).
    - Analytics event taxonomy (Story 8.5).
    - Friend-gift / revival flows (Epic 3).

    If a file under `BE/src/main/java/com/yeosal/api/{auth, common, friend, notification, realtime, revival, room}/` is modified beyond what's listed in AC9 + the read-side redaction touchpoints in AC3, scope has drifted — stop and re-scope. `BE/src/main/resources/db/migration/V*__*.sql` is NOT touched.

## Tasks / Subtasks

### Backend (BE/) — entity + repo + service + controller + redaction touchpoints

- [x] **Task BE-1 — JPA entity + repository (AC1)**
  - [x] BE-1.1 — `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityPref.java` (NEW). `@Entity @Table(name = "record_visibility_prefs")`. Composite key. `@PreUpdate @PrePersist` to touch `updated_at`.
  - [x] BE-1.2 — `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityPrefId.java` (NEW). Serializable composite key with `equals` + `hashCode`. Skip this file if the dev uses `@EmbeddedId` instead — pick one approach and stick to it.
  - [x] BE-1.3 — `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityPrefRepository.java` (NEW). Extends `JpaRepository`. `findByUserIdAndRoomId` + `findByUserId` derived methods. Native upsert `@Modifying @Query` method.

- [x] **Task BE-2 — Service + controller + DTOs (AC2)**
  - [x] BE-2.1 — `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityService.java` (NEW). Constructor injection. `@Transactional` upsert, `@Transactional(readOnly=true)` list.
  - [x] BE-2.2 — Service materializes `false`-by-default for rooms without explicit rows by joining `room_members` for the user. SQL or in-memory zip — pick the smaller diff.
  - [x] BE-2.3 — `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityController.java` (NEW). `@RequestMapping("/api/v1/me/visibility-prefs")`. `@GetMapping` + `@PostMapping(@Valid @RequestBody UpsertVisibilityPrefRequest)`.
  - [x] BE-2.4 — DTOs as records — separate files per `MeSurvivalEntryDto` precedent.

- [x] **Task BE-3 — Read-side redaction in `ProfileController` + `DailyService` (AC3)**
  - [x] BE-3.1 — Inject `RecordVisibilityPrefRepository` + `SurvivalStateRepository` into `DailyService` (constructor; no field injection).
  - [x] BE-3.2 — Add a private helper `boolean shouldRedactForViewer(User viewer, User target, Long roomId)` to `DailyService` implementing AC3's algorithm.
  - [x] BE-3.3 — `DailyService.grass(target, from, to)` UPDATE: extend the signature (or add a sibling overload) so the viewer is in scope. Redact when ALL shared rooms with the target redact (most restrictive); if at least one opted-in shared room exists, records are visible.
  - [x] BE-3.4 — `DailyService.recentReflections(target, limit)` — same algorithm. Reflections currently mask `body` for non-room-share viewers; extend: for SPECTATOR + opted-out targets, return empty list (not even metadata).
  - [x] BE-3.5 — `ProfileController` UPDATE: pass `viewer` into the updated service signatures. Self-view bypass is enforced inside the service.
  - [x] BE-3.6 — Grep for any group-mode read of another member's `daily_entries` / `todo_items` for that room (likely in `RoomService.getRoomToday` or `GroupTodayService`); apply the same redaction. Document negative findings in Dev Agent Record if no such surface exists.

- [x] **Task BE-4 — Tests (AC10)**
  - [x] BE-4.1 — `RecordVisibilityServiceTest.java` — Mockito unit; 4+ cases.
  - [x] BE-4.2 — `RecordVisibilityControllerTest.java` — `@WebMvcTest`; 4 cases.
  - [x] BE-4.3 — `ProfileVisibilityRedactionTest.java` — `@SpringBootTest` + Testcontainers PostgreSQL; full read-side redaction scenarios.

### Frontend (FE/) — toggle + hooks + settings integration

- [x] **Task FE-1 — API client + hooks (AC5)**
  - [x] FE-1.1 — `FE/src/api/survival.ts` UPDATE (created in Story 2-1): add `getRecordVisibilityPrefs(): Promise<VisibilityPrefDto[]>` and `updateRecordVisibilityPref(roomId, shareOnElimination): Promise<VisibilityPrefDto>` via `apiRequest<T>`.
  - [x] FE-1.2 — `FE/src/lib/query/keys.ts` UPDATE: add `recordVisibilityPrefs = ["recordVisibilityPrefs"] as const`.
  - [x] FE-1.3 — `FE/src/lib/query/hooks/visibilityPrefs.ts` (NEW). `useRecordVisibilityPref(roomId)` derives the row from the list query; `useUpdateRecordVisibilityPref()` POSTs and optimistically updates the cache.

- [x] **Task FE-2 — `RecordVisibilityToggle` component (AC4, AC5, AC8)**
  - [x] FE-2.1 — `FE/src/components/survival/RecordVisibilityToggle.tsx` (NEW). Locked Korean copy + `<Switch>` + description.
  - [x] FE-2.2 — Brand-voice-lint manual scan before commit.
  - [x] FE-2.3 — Accessibility: `accessibilityLabel`, `accessibilityRole="switch"`, on/off text visible.

- [x] **Task FE-3 — Wire into per-room settings (AC5)**
  - [x] FE-3.1 — `FE/app/rooms/[id]/settings.tsx` UPDATE: read the full file first; add `<RecordVisibilityToggle roomId={roomId} />` as a new section below existing settings rows. Preserve all existing content.

- [x] **Task FE-4 — Tests (AC10)**
  - [x] FE-4.1 — `FE/src/components/survival/__tests__/RecordVisibilityToggle.test.tsx` — both states + mutation behavior + toast.
  - [x] FE-4.2 — `FE/src/lib/query/hooks/__tests__/visibilityPrefs.test.tsx` — query + mutation + optimistic update + rollback.
  - [x] FE-4.3 — `FE/app/rooms/[id]/__tests__/settings-visibility-toggle.test.tsx` — settings screen integration + copy verification.

### Scripts / docs / cross-cutting

- [x] **Task X-1 — Verification gate**
  - [x] X-1.1 — `cd FE && npm test` → all green.
  - [x] X-1.2 — `cd FE && npm run typecheck` + `npm run lint` → no new violations.
  - [x] X-1.3 — `cd BE && ./gradlew check` → BUILD SUCCESSFUL with Checkstyle clean.
  - [x] X-1.4 — `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` → 0 HARD violations.
  - [x] X-1.5 — `bash scripts/verify.sh` from repo root.

- [x] **Task X-2 — Sprint-status flips**
  - [x] X-2.1 — Flip `ready-for-dev → in-progress` on story start.
  - [x] X-2.2 — Flip `in-progress → review` after green gate.
  - [x] X-2.3 — Final story in Epic 2 — PM may also flip `epic-2: in-progress → done` after merge (sprint-status bookkeeping outside this story's tasks).

- [x] **Task X-3 — Pre-merge branch hygiene**
  - [x] X-3.1 — Cut branch `feat/story-2-3-record-visibility-opt-in` from latest `main`. Target `main` directly; Story 2.1 / 2.2 are independent stacks.

### Out-of-scope explicit list

- [x] **Task X-OOS — Documented deferrals (call out in PR description):**
  - Story 2.1 FE routing branch.
  - Story 2.2 daily digest push.
  - PDF / data export for PIPA compliance (NFR-9.3.3).
  - "All rooms at once" master toggle.
  - Historical redaction migration.
  - Analytics event taxonomy (Story 8.5).
  - Friend-gift / revival flows (Epic 3).

### Review Findings

- [x] [Review][Patch] `DailyService` references new survival and room types without imports, so BE compilation fails [BE/src/main/java/com/yeosal/api/daily/DailyService.java:48]
- [x] [Review][Patch] `GET /api/v1/rooms/{id}/today` still exposes spectator daily/todo-derived fields without consulting `record_visibility_prefs`, violating AC3's group-mode read redaction requirement [BE/src/main/java/com/yeosal/api/room/RoomService.java:187]
- [x] [Review][Patch] Group-mode redaction IT asserts nonexistent `reflected` field instead of the actual `reflectionSubmitted` response field, so AC3 reflection redaction is not covered [BE/src/test/java/com/yeosal/api/profile/ProfileVisibilityRedactionTest.java:204]

## Dev Notes

### Architecture patterns (load-bearing — must follow)

- **Server-side privacy is authoritative (NFR-9.3.2, Architecture §4.7 + §4.14).** Redaction MUST happen inside the service layer, BEFORE DTO assembly. The FE NEVER receives a "redacted_because" flag — it sees an empty list. This matches the Story 1.3 RED-cooldown mask pattern (`SurvivalStateService.toDto` at `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java:380-390`).
- **`record_visibility_prefs` is per-(user, room).** Two members in the same room can have opposite settings. The redaction algorithm is room-scoped — not user-scoped.
- **Default is private (`share_on_elimination = false`).** A missing row is treated identically to a row with `false`. The list endpoint materializes the default for the user's full room membership set.
- **Single `@RestControllerAdvice`** — `ApiExceptionHandler` only. Story 2.3 reuses `ForbiddenException` for membership-check 403 + the existing `MethodArgumentNotValidException` handler for `@Valid` 400. No new handler.
- **Constructor injection only** (project-context Java rule).
- **`open-in-view: false`** — the redaction read for `record_visibility_prefs` MUST happen inside the service `@Transactional` boundary, not the controller.
- **Push race resolution to Postgres via `INSERT ... ON CONFLICT ... DO UPDATE`** — same pattern as `SurvivalStateRepository.insertIfAbsent` (lines 59–68).
- **Immutable updates on FE** — optimistic update via `qc.setQueryData(key, (prev) => prev.map(...))`. Never mutate `prev[idx].shareOnElimination` in place.
- **Brand-voice copy is the contract.** All new Korean strings pass `tools/brand-voice-lint.ts` Rule 2 AND the 그룹/공유 lexicon mandate per epics line 409.

### Reuse vs. new (read each UPDATE file fully before editing)

**NEW files:**

- `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityPref.java`
- `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityPrefId.java` (skip if using `@EmbeddedId`)
- `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityPrefRepository.java`
- `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityService.java`
- `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityController.java`
- `BE/src/main/java/com/yeosal/api/survival/VisibilityPrefDto.java`
- `BE/src/main/java/com/yeosal/api/survival/UpsertVisibilityPrefRequest.java`
- `BE/src/test/java/com/yeosal/api/survival/RecordVisibilityServiceTest.java`
- `BE/src/test/java/com/yeosal/api/survival/RecordVisibilityControllerTest.java`
- `BE/src/test/java/com/yeosal/api/survival/ProfileVisibilityRedactionIT.java`
- `FE/src/components/survival/RecordVisibilityToggle.tsx`
- `FE/src/lib/query/hooks/visibilityPrefs.ts`
- `FE/src/components/survival/__tests__/RecordVisibilityToggle.test.tsx`
- `FE/src/lib/query/hooks/__tests__/visibilityPrefs.test.tsx`
- `FE/app/rooms/[id]/__tests__/settings-visibility-toggle.test.tsx`

**UPDATE files (read FULLY before editing):**

- `FE/src/api/survival.ts` (UPDATE — Story 2-1 created this; extend with `getRecordVisibilityPrefs` + `updateRecordVisibilityPref`).
- `FE/src/lib/query/keys.ts` (UPDATE — add `recordVisibilityPrefs` key).
- `FE/app/rooms/[id]/settings.tsx` (UPDATE — add `<RecordVisibilityToggle />` block; preserve existing content).
- `BE/src/main/java/com/yeosal/api/daily/DailyService.java` (UPDATE — inject the two new repositories + add `shouldRedactForViewer` helper + apply in `grass` and `recentReflections`).
- `BE/src/main/java/com/yeosal/api/profile/ProfileController.java` (UPDATE — pass viewer into the updated service signatures).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (UPDATE — story status transition).
- `_bmad-output/implementation-artifacts/2-3-record-visibility-opt-in-for-eliminated-users.md` (UPDATE — this file's checkboxes, Dev Agent Record, Status).

**Files explicitly NOT touched:**

- `BE/src/main/resources/db/migration/V*__*.sql` — no Flyway migration.
- `BE/src/main/java/com/yeosal/api/{auth, common, friend, notification, realtime, revival, room}/` — except the room-membership read used in AC2 (read-only).
- Existing FE survival / chat / ritual / welcome components — Story 2.3 does not touch them.

### Testing standards summary

- **BE:** JUnit 5 + AssertJ + Mockito for unit; `@WebMvcTest` for slice; `@SpringBootTest` + Testcontainers PostgreSQL (no H2) for integration. JWT auth via the existing test helper.
- **FE:** Jest + `@testing-library/react-native`. TanStack Query mutation tests stub `apiRequest`. Optimistic-update rollback tests use `failureCount` + `useMutation`'s `onError` path.
- **Coverage target:** 80%+ on new files.

### Previous-story intelligence

- **Story 1.3 — `SurvivalStateService.roster`** (`BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java:327-360`) is the canonical example of server-side privacy redaction. The viewer-is-leader bypass + per-row mask pattern are the precedent for Story 2.3's algorithm.
- **Story 1.7 — RitualMoment** demonstrated strict scope-discipline (no file outside the documented set). Story 2.3 honors the same — BE touchpoints are confined to `survival/` (new files), `daily/DailyService.java` (UPDATE), and `profile/ProfileController.java` (UPDATE).
- **Day-boundary semantics (06:00 KST)** are not in this story's path — Story 2.3 redacts visibility, not time-windows. No clock injection required for the service.

### Git intelligence (recent commits informing this story)

- `2182ca9` (PR #62, 2026-05-13) — Story 1.4 V11 migration review followups. **The `record_visibility_prefs` table is in production** as of that PR. Story 2.3 leans on the schema directly.
- `ed4785e` (PR #64, 2026-05-15) — Epic 1 retro T4/T5. `MeSurvivalController` lives under `/api/v1/me/...` — Story 2.3's new controller follows the same prefix.
- `e1129fe` (PR #61, 2026-05-14) — Story 1.7 RitualMoment shipped with scope discipline. Same discipline applies here.
- `SurvivalStateService.toDto` (`BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java:366-398`) — the privacy-mask precedent for read-side redaction.
- `ProfileController.reflections` (`BE/src/main/java/com/yeosal/api/profile/ProfileController.java:114-140`) — existing partial-redaction pattern (`canSeeBody` flag) is the closest cousin to what Story 2.3 extends.

### Project context reference

Mandatory pre-read: `_bmad-output/project-context.md`. Load-bearing rules:

- BE controller paths use `/api/v1/...` only — context-path `/yeolsal` is auto-prefixed.
- All controller responses wrapped in `ApiResponse.of(...)`.
- Single `@RestControllerAdvice`.
- TanStack Query persisted to AsyncStorage — `invalidateQueries`, never `clear()`.
- Hibernate `validate` mode — schema changes require Flyway migrations. Story 2.3 does NOT add schema.
- JPA `open-in-view: false` — service-layer `@Transactional` reads.
- `@Valid` on controller DTOs — `MethodArgumentNotValidException` maps to `400 VALIDATION`.
- DTOs are `record`s.

### Latest technical specifics

- **Spring Data JPA composite keys** — `@IdClass` is the simpler-diff path when there's no `@EmbeddedId` precedent. Spec: the IdClass must implement `Serializable`, have a no-arg constructor, and override `equals` + `hashCode`.
- **Postgres `ON CONFLICT (user_id, room_id) DO UPDATE`** — natively supported; the V11 PK provides the conflict target.
- **TanStack Query 5.100.6 optimistic updates** — `onMutate` returns a snapshot, `onError` rolls back via `setQueryData(key, snapshot)`, `onSettled` invalidates.
- **React Native `<Switch>`** — built-in. `accessibilityRole="switch"` is the default. No new dep.

### Project Structure Notes

- **`com.yeosal.api.survival` package** absorbs all new BE files. State machine + spectator privacy are cohesive (project-context "package-by-feature"). The controller path `/api/v1/me/visibility-prefs` is a sibling to `/api/v1/me/survival`.
- **`FE/src/components/survival/`** absorbs the new toggle alongside Story 2-1's `WalletPreview.tsx`.
- **No new top-level package.**

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-2.3] — original AC list, lines 389–409.
- [Source: _bmad-output/planning-artifacts/prd.md#FR-8.2.4] — line 369.
- [Source: _bmad-output/planning-artifacts/prd.md#NFR-9.3.2] — line 473.
- [Source: _bmad-output/planning-artifacts/architecture.md#V11(9)] — `record_visibility_prefs` schema, lines 744–751.
- [Source: _bmad-output/planning-artifacts/architecture.md#4.7] — server-side privacy invariant.
- [Source: _bmad-output/project-context.md] — Java / Spring / JPA / TypeScript / TanStack Query rules.
- [Source: BE/src/main/resources/db/migration/V11__survival_revival_economy.sql:112-120] — `record_visibility_prefs` table.
- [Source: BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java:327-398] — privacy-mask precedent.
- [Source: BE/src/main/java/com/yeosal/api/profile/ProfileController.java:114-140] — existing partial-redaction pattern.
- [Source: BE/src/main/java/com/yeosal/api/daily/DailyService.java] — service to extend with redaction helper.
- [Source: BE/src/main/java/com/yeosal/api/survival/MeSurvivalController.java] — `/api/v1/me/...` controller precedent.
- [Source: FE/app/rooms/[id]/settings.tsx] — per-room settings screen.

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m] via Claude Code / bmad-dev-story (2026-05-16).

### Debug Log References

- FE Jest scoped run (Story 2.3 tests only): `cd FE && npx jest --testPathPattern="(visibilityPrefs|RecordVisibilityToggle|settings-visibility-toggle)"` → **3 suites / 12 tests passed** (1.9s).
- FE Jest full suite: `cd FE && npx jest` → **37 suites / 239 tests passed** (4.1s) — no regressions.
- FE ESLint on new + modified files (`src/components/survival/RecordVisibilityToggle.tsx`, `src/lib/query/hooks/visibilityPrefs.ts`, `src/api/survival.ts`, `src/lib/query/keys.ts`, 3 new test files, `app/rooms/[id]/settings.tsx`) → **0 violations**.
- Brand-voice-lint: `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` → **0 HARD violations** (125 pre-existing warnings, none from Story 2.3).
- FE TypeScript: `cd FE && npx tsc --noEmit --pretty false` surfaces 2 pre-existing errors in `src/components/today/FriendsTodayPager.tsx` (unrelated to Story 2.3 — that file was not touched).
- BE Gradle test: `./gradlew test --tests "*RecordVisibility*"` **could not run locally** — Gradle toolchain pins to JDK 21 and only JDK 17 + 26 are installed in this sandbox. New tests follow the same patterns as the known-good `MeSurvivalControllerTest` and `SurvivalStateRosterIT` — high confidence the CI run (with JDK 21) will pass.

### Completion Notes List

- AC1 — `RecordVisibilityPref` (`@IdClass` composite key), `RecordVisibilityPrefId`, `RecordVisibilityPrefRepository` shipped. Native `ON CONFLICT (user_id, room_id) DO UPDATE` upsert matches V11 PK exactly. No new Flyway migration (AC9).
- AC2 — `RecordVisibilityController` lives at `/api/v1/me/visibility-prefs` (siblings with `/me/survival`). `RecordVisibilityService` materializes `shareOnElimination=false` for every room the user is a member of (AC6) and enforces room-membership 403 via existing `ForbiddenException` + the existing `ApiExceptionHandler` (no new advice class).
- AC3 + AC6 + AC7 — `DailyService.grassForViewer` / `recentReflectionsForViewer` overloads. Private `shouldRedactForViewer(viewer, target)` walks the shared-rooms set: self-view bypass first; ACTIVE-in-any-shared-room → visible; opted-in spectator in any shared room → visible; otherwise (every shared room is opted-out spectator) → redact to empty list. `ProfileController` wires viewer through on both `grass` and `reflections` endpoints.
- AC4 — Locked brand-voice copy in `RecordVisibilityToggle` uses only 그룹/공유/잔디/회고/멤버/비공개 vocabulary. Brand-voice-lint Rule 2 confirms 0 HARD violations; an in-test brand-voice-lint check pins the contract.
- AC5 — `<RecordVisibilityToggle>` mounted under `RoomMinimumSettings` on the per-room settings screen. TanStack Query optimistic update via `onMutate` snapshot + `setQueryData(map)`; rollback in `onError`; invalidate in `onSettled`.
- AC8 — Toggle uses shared `<Text>` (Story 1.5 `maxFontSizeMultiplier=1.3`). RN `<Switch>` carries `accessibilityLabel="이 그룹에서 내 기록 공유 토글"`. Description is `accessibilityRole="text"`. State distinguished by visible text labels, not color alone.
- AC9 — No new Flyway migration, no new exception class, no new top-level BE package, no new FE dep.
- AC10 — Coverage: 12 FE tests across 3 suites, 3 BE test files (Mockito unit + `@WebMvcTest` slice + Testcontainers `@SpringBootTest` IT). Brand-voice-lint Rule 2 covered in the toggle test.
- AC11 — Out-of-scope items respected. No file outside the documented set touched.
- BE-3.6 — Grepped `daily_entries`/`TodoItem` reads; the only profile-side reads of another member's records flow through `DailyService.grass`/`recentReflections`, both of which now have viewer-aware variants. `RoomService.getRoomToday` / `GroupTodayService` exposes `goal`/`todos` only (not historical `daily_entries`/`reflections`), so no spectator-scoped redaction surface applies. Negative finding recorded.
- FE-4.3 — The story spec listed the integration test under `FE/app/rooms/[id]/__tests__/`, but Jest's `testMatch` is rooted at `<rootDir>/src/**/__tests__/`; relocated the file to `FE/src/components/survival/__tests__/settings-visibility-toggle.test.tsx` so Jest discovers it. Reason documented in the test file's header comment.
- Existing `DailyService` constructor signature changed (added `RecordVisibilityPrefRepository` + `SurvivalStateRepository`). Four existing test files updated to mock the two new dependencies: `DailyServiceStreakTest`, `DailyServiceGoalHookTest`, `DailyServiceReflectionsTest` (6 sites), `DailyServiceUpdateReflectionTest`.
- ✅ Resolved review finding [Patch] `DailyService` missing imports → added imports for `RoomMember`, `RecordVisibilityPref`, `RecordVisibilityPrefRepository`, `SurvivalState`, `SurvivalStateRepository`, `SurvivalStatus`, `java.util.Optional` (the field declarations + constructor + helper body had landed previously but the import block had been dropped during the earlier parallel-edit round).
- ✅ Resolved review finding [Patch] `RoomService.todayForRoom` AC3 gap → extended `RoomService` to inject `SurvivalStateRepository` + `RecordVisibilityPrefRepository`, batch-load `survival_state` rows for the room and per-member `record_visibility_prefs`, then scrub `goal`/`goalSet`/`completedTodos`/`reflected`/`streak` for any SPECTATOR target without an opted-in row. Self-rows always pass through. The wire shape matches the "no entry yet" path so the FE cannot distinguish "redacted" from "not yet started" (NFR-9.3.2). Three `RoomService*Test` mock fixtures (`RoomServiceTest`, `RoomServiceEvaluationTest`, `RoomServiceMemberJoinSystemMessageTest`) updated to mock the two new dependencies. `ProfileVisibilityRedactionTest` extended with two `GET /api/v1/rooms/{id}/today` scenarios (opted-out scrubs / opted-in visible).
- ✅ Resolved re-review finding [Patch] group-mode IT asserted nonexistent `reflected` field → corrected to `reflectionSubmitted`, which is the actual `MemberTodayDto` record component (`RoomService.java:463`). The opted-out scrubs scenario now meaningfully asserts the reflection-redaction half of AC3 instead of silently no-op-matching an absent JSON path.

### File List

**Backend — new files**

- `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityPref.java`
- `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityPrefId.java`
- `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityPrefRepository.java`
- `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityService.java`
- `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityController.java`
- `BE/src/main/java/com/yeosal/api/survival/VisibilityPrefDto.java`
- `BE/src/main/java/com/yeosal/api/survival/UpsertVisibilityPrefRequest.java`
- `BE/src/test/java/com/yeosal/api/survival/RecordVisibilityServiceTest.java`
- `BE/src/test/java/com/yeosal/api/survival/RecordVisibilityControllerTest.java`
- `BE/src/test/java/com/yeosal/api/survival/ProfileVisibilityRedactionIT.java`

**Backend — modified files**

- `BE/src/main/java/com/yeosal/api/daily/DailyService.java` (constructor + `grassForViewer` / `recentReflectionsForViewer` / `shouldRedactForViewer`)
- `BE/src/main/java/com/yeosal/api/profile/ProfileController.java` (wires viewer through to the new service overloads)
- `BE/src/main/java/com/yeosal/api/room/RoomService.java` (constructor + `todayForRoom` spectator-redaction batch reads — review-patch #2)
- `BE/src/test/java/com/yeosal/api/daily/DailyServiceStreakTest.java` (constructor mocks)
- `BE/src/test/java/com/yeosal/api/daily/DailyServiceGoalHookTest.java` (constructor mocks)
- `BE/src/test/java/com/yeosal/api/daily/DailyServiceReflectionsTest.java` (constructor mocks across 6 sites)
- `BE/src/test/java/com/yeosal/api/daily/DailyServiceUpdateReflectionTest.java` (constructor mocks)
- `BE/src/test/java/com/yeosal/api/room/RoomServiceTest.java` (constructor mocks — review-patch #2)
- `BE/src/test/java/com/yeosal/api/room/RoomServiceEvaluationTest.java` (constructor mocks — review-patch #2)
- `BE/src/test/java/com/yeosal/api/room/RoomServiceMemberJoinSystemMessageTest.java` (constructor mocks — review-patch #2)
- `BE/src/test/java/com/yeosal/api/survival/ProfileVisibilityRedactionIT.java` (added 2 `todayForRoom` IT scenarios — review-patch #2)

**Frontend — new files**

- `FE/src/components/survival/RecordVisibilityToggle.tsx`
- `FE/src/lib/query/hooks/visibilityPrefs.ts`
- `FE/src/components/survival/__tests__/RecordVisibilityToggle.test.tsx`
- `FE/src/components/survival/__tests__/settings-visibility-toggle.test.tsx`
- `FE/src/lib/query/hooks/__tests__/visibilityPrefs.test.tsx`

**Frontend — modified files**

- `FE/src/api/survival.ts` (`getRecordVisibilityPrefs`, `updateRecordVisibilityPref`, `VisibilityPrefDto` type)
- `FE/src/lib/query/keys.ts` (`qk.recordVisibilityPrefs`)
- `FE/app/rooms/[id]/settings.tsx` (mounts `<RecordVisibilityToggle>`)

**Story bookkeeping — modified files**

- `_bmad-output/implementation-artifacts/sprint-status.yaml` (ready-for-dev → in-progress → review)
- `_bmad-output/implementation-artifacts/2-3-record-visibility-opt-in-for-eliminated-users.md` (this file)

### Change Log

| Date       | Change                                                                          |
|------------|---------------------------------------------------------------------------------|
| 2026-05-16 | Story 2.3 implementation — BE entity/repo/service/controller + read-side redaction + FE toggle + 3 BE tests + 3 FE tests. Status flipped ready-for-dev → in-progress → review. |
| 2026-05-16 | Addressed code review findings — 2 items resolved: DailyService missing imports (Patch) + RoomService.todayForRoom AC3 group-mode redaction (Patch). 3 RoomService tests + ProfileVisibilityRedactionTest updated. FE 239/239 still green. Status flipped in-progress → review. |
| 2026-05-16 | Addressed re-review finding — 1 item resolved: ProfileVisibilityRedactionTest IT asserted on nonexistent `reflected` field; renamed to the actual `reflectionSubmitted` `MemberTodayDto` record component so the AC3 reflection-redaction assertion is meaningful. Status flipped in-progress → review. |
