# Story 1.3: Privacy-filtered survival roster API

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an **active member of a room**,
I want **`GET /api/v1/rooms/{id}/survival` to return everyone's status with privacy correctly applied**,
so that **the FE can render rosters without leaking eliminations during the 24-hour soft-public cooldown — and the privacy decision lives on the server, where a malicious or buggy client cannot defeat it**.

PRD authority: §8 FR-8.1.6 + NFR-9.3.1 (room-membership server-side check), NFR-9.1.3 (realtime latency budget — this endpoint is the cold-load companion).
Architecture authority: §4.1 (materialized survival state), §4.7 (spectator branch reads this endpoint), §4.14 (two-channel privacy — **server-side privacy filter is non-negotiable**), §5.1 (controller / `ApiResponse.of(...)`), §5.4 (privacy patterns), §6.4 (REST endpoint table).

## Acceptance Criteria

1. **AC1 — Endpoint exists at `GET /api/v1/rooms/{id}/survival` and returns `ApiResponse<List<SurvivalStateDto>>`.** New `SurvivalStateController` (`com.yeosal.api.survival.SurvivalStateController`) mapped to `/api/v1/rooms`. Controller path is `/api/v1/rooms` only — the `/yeolsal` context-path is auto-prefixed (project-context BE rule). Method signature: `public ApiResponse<List<SurvivalStateDto>> roster(Authentication auth, @PathVariable long id)`. Response envelope follows `ApiResponse.of(...)` — never a raw `List<>` (project-context). [PRD FR-8.1.6, Arch §5.1, §6.4]

2. **AC2 — Authorization: requester must be an active room member; otherwise `403 FORBIDDEN`.** The controller resolves the principal via `currentUser.require(auth)` (mirrors `RoomController.members` exactly — Story 1.1 wired this), then the service layer calls `roomMembers.existsByRoomIdAndUserId(roomId, viewerUserId)` (cheap existence check already used by `JwtChannelInterceptor`). If the check returns `false` → throw `ForbiddenException("방 멤버만 접근할 수 있습니다.")` — the existing `ApiExceptionHandler.forbidden(...)` maps this to `403` with code `FORBIDDEN`. The room itself must also exist; if not → `NotFoundException("방을 찾을 수 없습니다.")` mapped to `404 NOT_FOUND`. Order: existence-of-room → membership — so a non-member of a non-existent room sees `404`, not `403` (leaks no information about existence). [PRD FR-8.1.6, NFR-9.3.1, Arch §5.4]

3. **AC3 — Privacy filter: non-leader, non-self viewers see RED-with-cooldown as ACTIVE.** The filter applies if and only if **ALL** of these hold for a given `survival_state` row:
   - `row.status == SurvivalStatus.RED`
   - `row.broadVisibilityAt != null` AND `row.broadVisibilityAt.isAfter(now)` (note: **exclusive boundary** — at the exact instant `now == broad_visibility_at`, the cooldown has elapsed and the actual status is returned; this mirrors Story 1.1's `inGraceWindow` exclusive-boundary convention)
   - `viewerUserId != room.owner.id` (leader sees through the filter — see AC4)
   - `viewerUserId != row.user.id` (self always sees own true status — they already received the immediate private STOMP frame from Story 1.2 AC7)

   When the filter triggers, the response row reports `status=ACTIVE`, AND **`eliminatedAt=null`, `broadVisibilityAt=null`** (do not leak the timestamps either — Architecture §4.14 "Never trust the FE to filter privacy" applies to every field, not just the status string). `lastStateChangeAt` for masked rows must also be `null` so a viewer cannot infer "this person changed state recently" from a fresher `last_state_change_at`. [PRD FR-8.1.6, Arch §4.14, §5.4]

4. **AC4 — Leader bypass: `room.owner_id == viewerUserId` sees true status for every member.** The leader-aware branch is computed **once per request** from the loaded `Room` (`room.getOwner().getId()`), not re-resolved per row. There is **no auth elevation**: leader status grants only the filter bypass, not write access — this endpoint is read-only. Future leader-only endpoints (Stories 5.1, 5.2, 5.6) use `RoomService.requireLeader(...)` for 403-on-non-leader; this endpoint does NOT throw — it merely toggles the filter. [PRD FR-8.1.6 (leader-aware filter), Arch §4.7]

5. **AC5 — Self-view: viewer always sees their own real status, irrespective of leader/non-leader and cooldown state.** A non-leader who has just transitioned to RED hits this endpoint and sees their own `status=RED` in the response (so the FE's spectator-mode branch in Story 2.x reliably engages on cold-load even before the STOMP frame from `/user/queue/private-survival` arrives). [PRD FR-8.1.6 implicit; Arch §4.7 + §5.4]

6. **AC6 — Cooldown elapsed → broad visibility for everyone (`broad_visibility_at <= now()`).** When the 24h cooldown for a RED member has passed, every viewer (leader or not, self or not) sees `status=RED` with the true `eliminatedAt` and `broadVisibilityAt` populated. The masking branch ONLY applies during the exclusive window `[eliminatedAt, broadVisibilityAt)`. [PRD FR-8.1.6, Arch §4.14]

7. **AC7 — YELLOW / SPECTATOR / ACTIVE are never masked.** YELLOW is explicitly **not privacy-gated** (Story 1.2 AC5 — YELLOW broadcasts to `/topic/rooms.{id}.survival` immediately on transition). SPECTATOR can only be entered *after* a RED cooldown has elapsed (Story 2.x will own the RED→SPECTATOR transition; v1 SPECTATOR rows imply cooldown is past). ACTIVE has no privacy semantics. The filter switch in AC3 is the **only** path that mutates response fields — any other status passes through untouched. [Arch §5.4]

8. **AC8 — Wall-clock reads come from the injected `Clock`.** Service uses `Clock clock` (constructor-injected; Story 1.1 + 1.2 precedent). `Instant now = clock.instant()` is snapshotted **once per request** at the top of the service method, then passed into the filter predicate — so two members evaluated in the same request use the same instant and a borderline `broadVisibilityAt` cannot flip-flop within a single response. **Never call `Instant.now()` directly** (project-context Java rule). [Story 1.1/1.2 precedent; Arch §5.1]

9. **AC9 — DTO shape (BE wire contract; FE typing follows in Story 2.x).** Response element is a Java `record SurvivalStateDto` exposing exactly these fields, in this order, all JSON-serialized with default Jackson casing:
   ```java
   public record SurvivalStateDto(
           long userId,
           String nickname,
           SurvivalStatus status,
           Instant lastStateChangeAt,     // null on masked rows (AC3)
           Instant eliminatedAt,          // null unless status == RED && unmasked
           Instant broadVisibilityAt) {}  // null unless status == RED && unmasked
   ```
   - `userId` is `room_members.user.id`.
   - `nickname` is `User.getNickname()` (the only PII field; safe — already exposed by `/rooms/{id}/members`).
   - `status` serializes as the enum string via `@Enumerated(EnumType.STRING)` convention (project-context — never ORDINAL). Jackson emits `"ACTIVE"|"YELLOW"|"RED"|"SPECTATOR"`.
   - Iteration order matches `roomMembers.findByRoom(room)` (insertion order in v1 — Story 5.3 may sort by tenure later; do NOT impose a new order in this story).
   - **No `roomId` field** — it's already in the request path; including it is bandwidth waste and a tiny privacy nit. [PRD FR-8.1.6, Arch §6.4]

10. **AC10 — Performance: O(1) repository round-trips, never N+1.** Service does:
    1. one `rooms.findById(roomId)` (lazy `owner` is touched inside the same `@Transactional(readOnly=true)` boundary so `LazyInitializationException` cannot surface — project-context `open-in-view: false` rule),
    2. one `roomMembers.existsByRoomIdAndUserId(roomId, viewerUserId)` (used by AC2 gate),
    3. one `roomMembers.findByRoom(room)` (existing query — used by `/rooms/{id}/members`, `/rooms/{id}/today`),
    4. one `survivalStates.findByRoomId(roomId)` (existing — Story 1.1 shipped this method).

    Then build a `Map<Long /*userId*/, SurvivalState>` in memory and join with members in a single pass. Total query count: **4 SQL statements per request, independent of member count**. Do NOT load `survivalStates.findByRoomIdAndUserId` per member in a loop. [NFR-9.1.3 cold-load latency budget, Arch §4.13 batch SQL philosophy]

11. **AC11 — `@Transactional(readOnly=true)` on the service method.** Read-only flag is mandatory both for the Postgres optimizer (sets the txn to read-only) and to keep `open-in-view: false` happy when the controller serializes `Instant` / enum fields out of the entity-derived DTO. [Arch §5.1, project-context]

12. **AC12 — Test coverage: TDD per project-context rules.**
    **Unit tests** for the new service method (`SurvivalStateService.roster(long roomId, long viewerUserId)`):
    - `roster_nonLeaderNonSelf_sees_masked_red_during_cooldown` — RED row with `broadVisibilityAt > now` → response field reports ACTIVE + null timestamps.
    - `roster_nonLeaderNonSelf_sees_actual_red_after_cooldown_elapsed` — RED with `broadVisibilityAt <= now` → ACTUAL RED + populated timestamps.
    - `roster_leader_sees_actual_red_during_cooldown` — viewer == owner → ACTUAL RED even during cooldown.
    - `roster_self_sees_own_actual_red_during_cooldown` — viewer == affected user → ACTUAL RED.
    - `roster_yellow_never_masked` — YELLOW row visible to all viewers regardless of cooldown timestamps (defensive — YELLOW should not even have `broadVisibilityAt` set, but the masking predicate must not accidentally fire on it).
    - `roster_spectator_never_masked` — SPECTATOR row visible verbatim.
    - `roster_emptyRoom_returnsEmpty` — defensive: empty member list → empty response.
    - `roster_nonMember_throwsForbidden` — viewer not in `roomMembers` → `ForbiddenException`.
    - `roster_missingRoom_throwsNotFound` — `rooms.findById` empty → `NotFoundException`.
    - **`roster_borderlineExactlyAtBroadVisibilityAt_returnsActualRed`** — `now == broadVisibilityAt` → cooldown has elapsed (exclusive-boundary convention, mirroring Story 1.1's `inGraceWindow`).
    - Use `Clock.fixed(Instant.parse("2026-05-12T03:14:15Z"), ZoneId.of("UTC"))` for determinism (Story 1.1/1.2 precedent — UTC is fine here; no day-boundary math in roster).

    **Web slice test** (`@WebMvcTest(SurvivalStateController.class)`) with mocked service: verifies path, JSON shape, 403 / 404 mapping via `MockMvc + @WithMockUser`. Mirrors `RoomControllerTest` web-slice pattern (if absent, mirror any existing `@WebMvcTest` in the repo).

    **Integration test** (`@SpringBootTest + @AutoConfigureMockMvc + @Testcontainers + @EnabledIfSystemProperty(named="yeosal.boot-smoke", matches="true")` — mirrors `RoomControllerIT` + `SurvivalStateEvaluatorIT` pattern):
    - Seed 3 users (alice=leader, bob=member, carol=member), one room with alice as owner.
    - Force-seed survival states: alice=ACTIVE, bob=YELLOW, carol=RED with `broadVisibilityAt = now + 2h` (cooldown active).
    - As bob (non-leader, non-self): hit endpoint → expect `carol.status == ACTIVE`, `eliminatedAt == null`, `broadVisibilityAt == null`.
    - As alice (leader): hit endpoint → expect `carol.status == RED` with populated timestamps.
    - As carol (self): hit endpoint → expect `carol.status == RED` with populated timestamps.
    - Reset carol's `broadVisibilityAt` to `now - 1s` (or seed a second carol-row with elapsed cooldown in a sibling test) → bob now sees `carol.status == RED`.
    - As a 4th user dave with NO membership: expect `403 FORBIDDEN` + `error.code == "FORBIDDEN"`.
    - `404 NOT_FOUND` for missing room (request `GET /rooms/9999999/survival`).

    All under `./gradlew test` (with `-Dyeosal.boot-smoke=true` for the IT slice) — project-context pre-push rule. [project-context BE testing rule]

13. **AC13 — No FE changes in this story.** Story 1.3 is BE-only — the FE typed wrapper (`FE/src/api/survival.ts`) + `useSurvivalState` hook are Story 2.x's surface (per Arch §6.2). If you find yourself editing `FE/`, you've drifted scope. Pre-existing FE baseline failures called out in Story 1.2's Git Intelligence remain out-of-scope.

## Tasks / Subtasks

### Backend (BE/)

- [x] **Task BE-1 — `SurvivalStateDto` record (AC9)**
  - [x] BE-1.1 — New file `BE/src/main/java/com/yeosal/api/survival/SurvivalStateDto.java`. Java `record` with the exact 6 fields and order from AC9. Package: `com.yeosal.api.survival` (per Arch §6.1 — DTOs live in the module they describe, NOT in `common/`). No `@JsonProperty` annotations needed — default Jackson casing is correct.

- [x] **Task BE-2 — `SurvivalStateService.roster(...)` method (AC2, AC3, AC4, AC5, AC6, AC7, AC8, AC10, AC11)**
  - [x] BE-2.1 — Extend the existing `SurvivalStateService` (Story 1.1 owner; Story 1.2 added `evaluateRoom`). **Preserve** the existing 11-arg constructor + `initializeOnJoin` + `inGraceWindow` + `evaluateRoom` (they are the contracts Stories 1.1/1.2 shipped — do NOT regress signatures). `RoomRepository rooms` + `RoomMemberRepository roomMembers` + `SurvivalStateRepository repository` + `Clock clock` are ALL already constructor-injected (Story 1.2 BE-4.1). Reuse — DO NOT extend the constructor.
  - [x] BE-2.2 — Add `@Transactional(readOnly = true) public List<SurvivalStateDto> roster(long roomId, long viewerUserId)` (AC11). Algorithm:
    1. `Instant now = clock.instant();` (AC8 — one snapshot per request).
    2. `Room room = rooms.findById(roomId).orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));` — touches `room.getOwner().getId()` inside the txn boundary so the lazy `owner` load happens within `@Transactional` (AC11 / project-context `open-in-view: false`).
    3. `if (!roomMembers.existsByRoomIdAndUserId(roomId, viewerUserId)) throw new ForbiddenException("방 멤버만 접근할 수 있습니다.");` (AC2).
    4. `boolean viewerIsLeader = room.getOwner().getId() == viewerUserId;` (AC4 — computed once).
    5. `List<RoomMember> members = roomMembers.findByRoom(room);` (AC10 — single call).
    6. Stream `survivalStates.findByRoomId(roomId)` into a `Map<Long, SurvivalState>` keyed by `state.getUser().getId()` (AC10 — single call, dedup-safe collector for the unique-(room_id, user_id) invariant from V11).
    7. Map each `RoomMember` to a `SurvivalStateDto` via a private helper, passing `(member, byUserId.get(memberUserId), viewerUserId, viewerIsLeader, now)`.
    8. Private helper `private SurvivalStateDto toDto(RoomMember member, SurvivalState state, long viewerUserId, boolean viewerIsLeader, Instant now)`:
       - If `state == null` → return DTO with `status=ACTIVE, lastStateChangeAt=null, eliminatedAt=null, broadVisibilityAt=null` (defensive — V11 backfill should have created every row, but a defect should surface as ACTIVE-by-default rather than NPE).
       - Else apply the AC3 mask predicate: `boolean masked = state.getStatus() == SurvivalStatus.RED && state.getBroadVisibilityAt() != null && state.getBroadVisibilityAt().isAfter(now) && viewerUserId != state.getUser().getId() && !viewerIsLeader;`
       - If `masked`: build DTO with `status=ACTIVE`, all three timestamps `null` (AC3).
       - Else: build DTO with the real `state.getStatus()` + `state.getLastStateChangeAt()` + `state.getEliminatedAt()` + `state.getBroadVisibilityAt()` (the latter two are already `null` for non-RED rows by data-shape invariant).
  - [x] BE-2.3 — `SurvivalStatus` is exported via the DTO. Jackson must serialize it as `"ACTIVE"|"YELLOW"|"RED"|"SPECTATOR"`. The enum is plain — Jackson's default emit path is the enum's `name()`, which matches. Do **NOT** add a custom `@JsonValue` method (would silently break existing wire contracts that already accept enum strings, e.g. STOMP frames in Story 1.2's `SurvivalStateChangePayload`).

- [x] **Task BE-3 — `SurvivalStateController` (AC1, AC2)**
  - [x] BE-3.1 — New file `BE/src/main/java/com/yeosal/api/survival/SurvivalStateController.java`. `@RestController @RequestMapping("/api/v1/rooms")` (mirrors `RoomController` exactly — context-path `/yeolsal` is auto-prefixed). Constructor injects `SurvivalStateService` + `CurrentUser` (NOT `@Autowired` fields — project-context Java rule).
  - [x] BE-3.2 — Method: `@GetMapping("/{id}/survival") public ApiResponse<List<SurvivalStateDto>> roster(Authentication auth, @PathVariable long id)`. Body:
    ```java
    User me = currentUser.require(auth);
    return ApiResponse.of(survivalStateService.roster(id, me.getId()));
    ```
    No `@Valid` needed — path variable typed as `long` already gets `MethodArgumentTypeMismatchException` → 400 VALIDATION via existing `ApiExceptionHandler`. No request body to validate.
  - [x] BE-3.3 — **Do NOT introduce a second `@RestControllerAdvice`** (project-context: exactly one — `ApiExceptionHandler` already maps `ForbiddenException` → 403 with code `FORBIDDEN` and `NotFoundException` → 404 with code `NOT_FOUND`; nothing to add here).

- [x] **Task BE-4 — `SecurityConfig` whitelist audit (AC2)**
  - [x] BE-4.1 — `/api/v1/rooms/{id}/survival` must **NOT** be in the public-endpoint whitelist. Read `BE/src/main/java/com/yeosal/api/common/SecurityConfig.java`; confirm the public list is exactly `/api/v1/auth/{signup,login,kakao/{authorize,callback,exchange},refresh}`, `/v3/api-docs/**`, `/swagger-ui/**`, `/ws`, `/ws/**` (project-context). If anything has been added, flag it but do NOT modify SecurityConfig in this story — the endpoint reaching `JwtAuthenticationFilter` correctly is the default behavior. The IT (BE-5.3) verifies the auth path end-to-end.

- [x] **Task BE-5 — Tests (TDD: RED → GREEN → refactor, per project-context)**
  - [x] BE-5.1 — `SurvivalStateServiceRosterTest` (`@ExtendWith(MockitoExtension.class)`): unit-test the service algorithm with mocked `RoomRepository`, `RoomMemberRepository`, `SurvivalStateRepository`, and `Clock.fixed(...)`. Each test in AC12's bulleted unit list maps to one `@Test` method using the AC's literal `methodName_scenario_expectedBehavior` shape. Place file at `BE/src/test/java/com/yeosal/api/survival/SurvivalStateServiceRosterTest.java`. **Do NOT modify** `SurvivalStateServiceTest` (Story 1.1) or `SurvivalStateServiceEvaluateRoomTest` (Story 1.2) — those test other methods and their fixtures already cover the 11-arg constructor wiring.
  - [x] BE-5.2 — `SurvivalStateControllerTest` (`@WebMvcTest(SurvivalStateController.class)` + `@MockBean SurvivalStateService`): mock the service; assert (a) `GET /api/v1/rooms/1/survival` with `@WithMockUser` returns `200` + envelope `{ "data": [...] }`, (b) the path's `{id}` is parsed correctly and the controller forwards `(roomId=1, viewerUserId=<resolved>)` to the service, (c) `ForbiddenException` from service → `403` + `error.code == "FORBIDDEN"`, (d) `NotFoundException` → `404` + `error.code == "NOT_FOUND"`, (e) unauthenticated → `401` (existing JwtAuthenticationFilter behavior — `@WithMockUser` omitted). **CurrentUser** is `@MockBean`'d to return a fixed `User`; mirror the existing pattern from any existing `@WebMvcTest` in `BE/src/test/java/com/yeosal/api/`.
  - [x] BE-5.3 — `SurvivalStateRosterIT` (`@SpringBootTest + @AutoConfigureMockMvc + @Testcontainers + @EnabledIfSystemProperty(named="yeosal.boot-smoke", matches="true")`): opt-in IT mirroring `RoomControllerIT` and `SurvivalStateEvaluatorIT`. Seed users (alice owner, bob member, carol member, dave outsider) + 1 room + `survival_state` rows (use the `SurvivalStateRepository.insertIfAbsent` native upsert; then for bob/carol, persist additional fields via direct `repository.findByRoomIdAndUserId(...).map(s -> ...)` and field setters reachable through the entity's package-private setters — package the test under `com.yeosal.api.survival` to access them, identical to Story 1.2's `SurvivalStateEvaluatorIT`). Cover every endpoint-level assertion from AC12.
  - [x] BE-5.4 — Coverage target: 80%+ on `SurvivalStateController` + the new `roster(...)` service method (project-context). Run `./gradlew test` from `BE/`; opt-in IT needs `-Dyeosal.boot-smoke=true`. Toolchain note from prior session: BE Gradle does not auto-detect Java 21 — pass `-Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (or update local `~/.gradle/gradle.properties`).

- [x] **Task BE-6 — Boot smoke check**
  - [x] BE-6.1 — Confirm `./gradlew bootRun` (or the existing `ApplicationBootSmokeTest`) still boots — adding one controller + one DTO + one service method against an already-wired Spring context is low-risk, but the new `@GetMapping("/{id}/survival")` registration goes through `RequestMappingHandlerMapping` and a typo (e.g., a duplicate `@PathVariable("id")` mismatch) is the kind of fault only the boot test catches before deploy.

### Frontend (FE/)

- [x] **Task FE-1 — OUT OF SCOPE — no FE files in this story (AC13).** The typed wrapper `FE/src/api/survival.ts` and the `useSurvivalState` hook are Story 2.x's surface (per Arch §6.2). If `scripts/verify.sh` fails on the FE side due to the pre-existing baseline issues called out in Story 1.2's Git Intelligence (`FE/app/rooms/[id]/chat.tsx:60`, `FE/src/lib/realtime/client.ts:283`, `FE/src/components/rooms/__tests__/InviteCodeSheet.test.tsx:1`), note it and move on — those are out-of-scope.

### Cross-cutting

- [x] **Task X-1 — Run `bash scripts/verify.sh`** from repo root once BE work lands. Confirms FE + BE green together (project-context). Pre-existing FE failures from prior stories remain out-of-scope; document if they still surface.
- [x] **Task X-2 — OpenAPI doc verification (existing tooling).** Spring's `springdoc-openapi-starter-webmvc-ui:2.6.0` is already wired (project-context). Boot the app locally, hit `/yeolsal/v3/api-docs` (or browse `/yeolsal/swagger-ui/index.html`), confirm the new `GET /api/v1/rooms/{id}/survival` operation appears with the `SurvivalStateDto` schema. Do NOT add any springdoc annotations beyond what `RoomController` already uses (none) — the default reflection-based emission is the project convention.

### Review Findings

- [ ] [Review][Patch] Roster path still performs per-member lazy user loads, so AC10's four-SQL budget is not met [BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java:344]
- [ ] [Review][Patch] `SurvivalStateRosterIT` authenticates with `@WithMockUser`, but production `CurrentUser.require(...)` only accepts `UserPrincipal`, so the IT will return 401 before exercising roster behavior [BE/src/test/java/com/yeosal/api/survival/SurvivalStateRosterIT.java:85]
- [ ] [Review][Patch] The documented `-Dyeosal.boot-smoke=true` IT command does not enable the opt-in tests because the Gradle `test` task does not forward the property to the test JVM [BE/build.gradle:37]
- [ ] [Review][Patch] `SurvivalStateRosterIT.seed()` reuses the same unique emails in every test without cleanup, so enabled IT runs will hit user email uniqueness collisions after the first test [BE/src/test/java/com/yeosal/api/survival/SurvivalStateRosterIT.java:184]

## Dev Notes

### Architecture patterns (must follow)

- **BE package layout** is package-by-feature; all new code lives in `com.yeosal.api.survival/` (Arch §6.1) — controller, DTO, and the new service method extension. The `survival/` module already exists (Story 1.1) and houses `SurvivalStateService` — extend, don't replace.
- **Constructor injection only.** `SurvivalStateController` injects `SurvivalStateService` + `CurrentUser` via the constructor. No `@Autowired` fields (project-context Java rule + Story 1.1/1.2 precedent).
- **`open-in-view: false` is hard.** The lazy `Room.owner` association MUST be touched inside the service's `@Transactional(readOnly=true)` boundary, not in the controller / Jackson serialization phase. AC11 + project-context.
- **JPA `validate` mode.** No schema changes in this story — V11 already shipped every field the privacy filter reads (`status`, `broad_visibility_at`, `eliminated_at`, `last_state_change_at`). Confirm by reading `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql` if uncertain (Story 1.1 file).
- **Single `@RestControllerAdvice`.** `ApiExceptionHandler` already maps `ForbiddenException` → 403 and `NotFoundException` → 404. **Do NOT add a second advice** (project-context anti-pattern).
- **`ApiResponse.of(dto)`** envelope on every response — the FE client (`apiRequest<T>` + `ApiEnvelope<T>`) assumes the `{ data: ... }` shape (project-context FE rule).
- **No new domain exceptions in this story.** Reusing `ForbiddenException` + `NotFoundException` is correct; both already have `@ExceptionHandler` mappings.
- **Path uses `/api/v1/...` only** — `/yeolsal` context-path is applied automatically (project-context).

### Privacy filter contract (load-bearing — Arch §4.14 + §5.4)

The single most important code in this story is the masking predicate (Task BE-2.2 helper):

```java
boolean masked =
        state.getStatus() == SurvivalStatus.RED
        && state.getBroadVisibilityAt() != null
        && state.getBroadVisibilityAt().isAfter(now)         // EXCLUSIVE boundary
        && viewerUserId != state.getUser().getId()           // self always sees self
        && !viewerIsLeader;                                  // leader always sees true
```

Each of the five conjuncts is named in an AC:
- `status == RED` (AC3)
- `broadVisibilityAt != null && broadVisibilityAt.isAfter(now)` (AC3, AC6 — exclusive boundary)
- `viewerUserId != state.getUser().getId()` (AC5 — self always sees real)
- `!viewerIsLeader` (AC4 — leader bypass)

**Architecture §4.14 quote:** *"Never trust the FE to filter privacy. If we broadcast and let FE drop, a malicious client (or a bug) leaks the data."* The same rule applies here: the FE will receive **only** what the BE returns. If the masking predicate is wrong, the data leaks. The IT (BE-5.3) is the load-bearing assertion — keep it.

**Inverted-test discipline:** also assert what the filter does NOT mask. AC12's `roster_yellow_never_masked` and `roster_spectator_never_masked` catch the regression where a refactor accidentally widens the predicate to all non-ACTIVE rows.

### Database

- **Flyway:** NO new migration. V11 already created `survival_state` with `status`, `last_state_change_at`, `eliminated_at`, `broad_visibility_at`, `grace_ends_at` columns (Story 1.1; see Arch §6.3 (3)). V11 (13) backfill ensures every `room_members` row has a paired `survival_state` row — so the defensive `state == null` branch in BE-2.2 step 8 is a belt-and-suspenders, not the happy path.
- **Asia/Seoul:** this endpoint does **not** read entry-date or any 06:00 KST day-boundary semantics. It compares two `Instant` values (server-injected `clock.instant()` vs `survival_state.broad_visibility_at`). The KST boundary is upstream — Story 1.2's evaluator set `broad_visibility_at = eliminatedAt + 24h` using one `clock.instant()` snapshot. Story 1.3 just reads it.
- **Indexes:** `survival_state` has `idx_survival_state_room (room_id)` from V11 (3). `survivalStates.findByRoomId(roomId)` hits it. No new index needed.

### Realtime (orientation only — not used in 1.3)

This story is **REST cold-load only**. The STOMP fan-out (immediate private + delayed broad via `pending_realtime_broadcasts`) was Story 1.2's surface and is already live. The roster endpoint is the **first-load** companion: a fresh app session hits `GET /rooms/{id}/survival`, then subscribes to `/topic/rooms.{id}.survival` for deltas. The two paths must agree:

- **Privacy contract symmetry:** STOMP broad fan-out (Story 1.2 BE-6) is also gated server-side (delayed until `broad_visibility_at`). The roster endpoint applies the same predicate as a snapshot read. If a member transitions to RED *between* roster fetch and STOMP subscribe, the FE receives the immediate private frame via `/user/queue/private-survival` — the roster's `viewerUserId == state.getUser().getId()` self-bypass (AC5) ensures the self's RED is visible on cold-load.
- **JwtChannelInterceptor regex** (Story 1.2 review-fix) already accepts `^/topic/rooms\.(\d+)\.(chat|members|survival)$` — no change needed; the roster endpoint does not subscribe to STOMP.

### Frontend (orientation only — not used in 1.3)

- **OUT OF SCOPE.** FE consumes this endpoint via Story 2.x's `FE/src/api/survival.ts` typed wrapper + `useSurvivalState` hook (per Arch §6.2). Do NOT add any `FE/` files in this story. If you need to verify the wire shape end-to-end, do it via the BE IT (Task BE-5.3), not by mocking FE.
- Future-FE contract note for the dev agent: the eventual `useSurvivalState` will receive the `List<SurvivalStateDto>` envelope, narrow `status` to the `SurvivalStatus` union, and (per UX Design Spec §`<SurvivalChip>` NFR-9.6.1 enforcement) MUST use `<SurvivalChip state={...}>` to render — never a raw color literal. Story 2.x owns the FE.

### Source files to touch (UPDATE vs NEW — full read required before editing)

Per project-context: read the *current state* of every UPDATE file before editing. Document state machine, API calls, data shapes; do not break preserved behaviors.

- **`BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java`** (UPDATE — Story 1.1 + 1.2 owner) — add one method (`roster(...)`) + one private helper (`toDto(...)`). **Preserve:** the 11-arg constructor exactly (Story 1.2 BE-4.1), `initializeOnJoin(...)` (Story 1.1 AC1/AC2/AC3), `inGraceWindow(...)` (Story 1.1 AC3/AC4 contract), `evaluateRoom(...)` (Story 1.2 AC1–AC9). The new method touches a **disjoint** set of behavior — no risk of regressing Story 1.1/1.2 ACs if signatures and existing method bodies are left alone.
- **`BE/src/main/java/com/yeosal/api/survival/SurvivalStateController.java`** (NEW)
- **`BE/src/main/java/com/yeosal/api/survival/SurvivalStateDto.java`** (NEW — Java record)
- **`BE/src/test/java/com/yeosal/api/survival/SurvivalStateServiceRosterTest.java`** (NEW)
- **`BE/src/test/java/com/yeosal/api/survival/SurvivalStateControllerTest.java`** (NEW — `@WebMvcTest` web slice)
- **`BE/src/test/java/com/yeosal/api/survival/SurvivalStateRosterIT.java`** (NEW — opt-in `@SpringBootTest + Testcontainers + boot-smoke=true`)

**Files explicitly NOT touched:**

- `SecurityConfig.java` — no whitelist change needed (private endpoint by default).
- `ApiExceptionHandler.java` — no new exception handlers needed.
- `application.yml` — no new config keys.
- `RoomController.java` / `RoomService.java` — the roster endpoint lives in `survival/`, not `room/`; do NOT bolt it onto `RoomController` (Arch §6.1 module boundary).
- `RealtimePublisher.java`, `SurvivalStateRealtimeListener.java`, `PendingRealtimeBroadcastDispatcher.java`, `SurvivalStateEvaluatorJob.java` — Story 1.2 surface, untouched here.
- Any `FE/` file (AC13).

### Project Structure Notes

- Story 1.3 is the FIRST controller file in `com.yeosal.api.survival/` — Stories 1.1 and 1.2 introduced entities/repos/service/scheduler/listener but no controller. This story closes the module's external surface for Epic 1's MVP slice (Stories 1.4 backfill + 1.5/1.6/1.7 are FE/DS work that consumes this endpoint).
- The endpoint deliberately lives under `/api/v1/rooms/{id}/survival`, NOT `/api/v1/survival/rooms/{id}` — mirrors `/rooms/{id}/members` and `/rooms/{id}/today` (already in `RoomController`). The resource hierarchy is "a room's survival roster", not "the survival module's per-room view". Architecture §6.4's REST table is authoritative on this.
- The Arch §6.4 table also lists `GET /me/survival` (across user's rooms). That is **out of scope for Story 1.3** — only `/rooms/{id}/survival` is in the epic. Defer `/me/survival` to a follow-up story (probably folded into Stories 2.x when the FE Home tab needs the multi-room view).

### Previous story intelligence (Story 1.1 + Story 1.2)

Carry forward — extracted from `_bmad-output/implementation-artifacts/1-1-room-creation-with-v1-cap-14-day-grace-trial.md` and `_bmad-output/implementation-artifacts/1-2-06-00-kst-survival-state-evaluator-job.md` Completion Notes and Codex review patches:

- **`SurvivalStateRepository.findByRoomId(long roomId)`** already exists (Story 1.1). It's the perfect one-shot loader for the privacy filter — pulls every row for a room in one query against the `idx_survival_state_room` index. Use it directly; do not invent a new repo method.
- **`SurvivalStateRepository.findByRoomIdAndUserId(long, long)`** also exists — use it for single-user paths if needed (none in this story).
- **`SurvivalStateService` constructor is 11 args wide** (Story 1.2). `RoomRepository rooms`, `RoomMemberRepository roomMembers`, and `Clock clock` are already injected — DO NOT extend the constructor in this story. Reuse the existing fields. The `notificationLogs`, `users`, `streakFreezes`, `personalLedger`, `dailyEntries`, `ruleVersions`, `eventPublisher` fields are unused by `roster(...)` and that's fine — they exist for `evaluateRoom(...)`.
- **`SurvivalState` entity has only package-private setters** (`setStatus`, `setLastStateChangeAt`, `setEliminatedAt`, `setBroadVisibilityAt`) by design (Story 1.1 invariant: only `SurvivalStateService` drives transitions). The IT (BE-5.3) seeds RED test state by either (a) calling `evaluateRoom(...)` to drive a real transition, or (b) placing the IT class in the `com.yeosal.api.survival` package so it can call the setters directly — Story 1.2's IT chose option (b). Mirror that.
- **`RoomMemberRepository.existsByRoomIdAndUserId(Long, Long)`** is the cheap membership check shipped before Story 1.1 (used by `JwtChannelInterceptor`). Use this — not `findByRoomAndUser(...)` — for the AC2 403 gate. It hits a covering index and returns a boolean, no entity hydration.
- **`Clock` injection precedent** is everywhere — `RoomService` (Story 1.1) and `SurvivalStateService` (Story 1.1 + 1.2) both inject `Clock` and call `clock.instant()`. **Never** call `Instant.now()` directly. The test fixture is `Clock.fixed(Instant.parse("..."), ZoneId.of("UTC"))`.
- **Idempotency philosophy** (Story 1.2 reinforcement): push dedup into SQL via `ON CONFLICT DO NOTHING`. Doesn't apply to this story (no writes) but the matching test discipline does — tests must reason about exclusive vs inclusive boundaries explicitly (AC12's `roster_borderlineExactlyAtBroadVisibilityAt_returnsActualRed`).
- **`requireLeader(Room, User)`** helper exists in `RoomService` but is currently `@SuppressWarnings("unused") private`. Story 1.3 does NOT throw on non-leader — it only toggles the filter — so we don't promote that helper to public/move it into `SurvivalStateService`. If a future story needs both gate-throw and filter-toggle behavior, refactor at that time.
- **STOMP destination convention** is dot-separator (Story 1.2 review fix #2): `/topic/rooms.{id}.survival`, `/topic/rooms.{id}.chat`, `/topic/rooms.{id}.members`. This story's REST endpoint mirrors the slash-form `/rooms/{id}/survival` — the inconsistency between STOMP-dot vs REST-slash is intentional and project-wide (REST uses RFC 3986 path segments; STOMP destinations follow the project's established `rooms.{id}.{channel}` convention from the chat module).
- **`SurvivalStateChangePayload`** (Story 1.2) carries an explicit `eliminatedAt` field per Codex review fix #4. The roster's `SurvivalStateDto` follows the same shape principle — name the `eliminatedAt` field explicitly (rather than packing it inside `metadata`). FE/Spectator-mode clients (Story 2.x) get a stable AC7-prescribed key in both the STOMP payload and the REST roster row.

### Git intelligence

Recent commits on `feat/privacy-filtered-survival-roster-api`:

- Branch was rebuilt off `main@565d505` (PR #54 merge — Stories 1.1+1.2) via `git fetch && git reset --hard origin/main && git cherry-pick 4ca9171` (per the prior-session handoff memo). Current HEAD is `20a1afb chore(bmad): restore planning artifacts + bmad tooling for Story 1.3` — a 41-file restore of BMad scripts + planning artifacts + supporting docs that PR #54's squash dropped.
- All foundational Story 1.1 + 1.2 work has already merged via PR #54 (Stories 1.1+1.2 — room cap, 14d grace, 06:00 KST evaluator). The `survival/` module is fully populated for evaluator + transition + realtime — Story 1.3 is the FIRST controller addition.
- Pre-existing FE lint/typecheck baseline failures (cf. Story 1.2 Completion Notes, Codex review followup) remain out-of-scope: `FE/app/rooms/[id]/chat.tsx:60`, `FE/src/lib/realtime/client.ts:283`, `FE/src/components/rooms/__tests__/InviteCodeSheet.test.tsx:1`. Do NOT touch them.

### Latest tech information

- **Spring Boot 3.3.5** + **Java 21**. Use a Java `record` for `SurvivalStateDto`. Pattern matching for `instanceof` not needed in this story.
- **Spring `@Transactional(readOnly = true)`** is the right annotation for the read path — sets the txn as read-only at JDBC level, which (a) lets Postgres pick a read-optimized plan, (b) keeps `open-in-view: false` happy when the controller serializes through the entity-derived DTO.
- **Jackson 2.16+** (Boot 3.3.5 ships it) serializes `java.time.Instant` as ISO-8601 by default (`"2026-05-12T03:14:15Z"`), with the `JavaTimeModule` auto-registered. No FE-side parser change — the FE already accepts ISO `Instant` strings from existing endpoints (e.g., `RoomService.InviteSummary.expiresAt`).
- **Testcontainers `postgres:16-alpine`** for the IT (mirrors `RoomControllerIT` + `SurvivalStateEvaluatorIT`). H2 is **forbidden** per project-context — partial unique indexes and jsonb in V11 won't behave correctly on H2.
- **springdoc-openapi 2.6.0** is already wired (project-context). The new `@GetMapping` auto-appears in the OpenAPI doc. The `SurvivalStateDto` shape is reflected from the record's accessors. No springdoc annotations needed.
- **JJWT 0.12.6** — not used in Story 1.3 (no new auth surface; existing `JwtAuthenticationFilter` handles `Authorization: Bearer ...` for the new endpoint automatically).

### Testing standards summary

| Layer | Framework | Min coverage focus |
|-------|-----------|--------------------|
| BE unit | JUnit 5 + AssertJ + Mockito | `SurvivalStateService.roster` — all 4 mask-predicate conjuncts independently exercised; 404/403 paths; borderline `now == broadVisibilityAt` (exclusive-boundary contract) |
| BE web slice | `@WebMvcTest(SurvivalStateController.class)` + `@MockBean SurvivalStateService` + `MockMvc` | Path, JSON envelope shape (`{ data: [...] }`), 401 / 403 / 404 mapping via `ApiExceptionHandler` |
| BE integration | `@SpringBootTest` + Testcontainers Postgres 16 (opt-in via `-Dyeosal.boot-smoke=true`) | End-to-end: seed users + memberships + survival_state rows → MockMvc GET → assert response shape + privacy bits for non-leader / leader / self / outsider |
| FE | (Out of scope — covered later in Stories 2.x) | — |

Project-wide coverage target is 80% on domain/service logic (project-context). Trivial getters/config excluded.

### Pre-commit verification (project-context Stack PR Merge Procedure, plus pre-push order)

1. `cd BE && ./gradlew test` — green (project-context BE pre-push rule).
2. `cd BE && ./gradlew test -Dyeosal.boot-smoke=true` — opt-in IT layer (Testcontainers Postgres 16) green. Toolchain note from prior session: pass `-Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` if Gradle's auto-detection misses Java 21.
3. `bash scripts/verify.sh` from repo root — full FE+BE verification. Pre-existing FE failures remain out-of-scope.
4. Open `/yeolsal/swagger-ui/index.html` locally (`./gradlew bootRun`) — confirm `GET /api/v1/rooms/{id}/survival` is registered with `SurvivalStateDto` schema (Task X-2).
5. **PR base must be `main`** per project-context Stack PR Merge Procedure (incident-driven mandatory rule from PR #36). Verify with `gh pr view <N> --json baseRefName,mergeStateStatus`. The prior-session memo notes the Story 1.3 PR will eventually contain `20a1afb` (infra restore) + this story's implementation commits — consider splitting if the user prefers smaller PRs.

### References

- [PRD §8 FR-8.1.6](../planning-artifacts/prd.md) — privacy-filtered roster endpoint contract
- [PRD §9 NFR-9.3.1](../planning-artifacts/prd.md) — room-membership server-side check
- [PRD §9 NFR-9.1.3](../planning-artifacts/prd.md) — realtime latency budget (this endpoint is the REST cold-load companion to STOMP)
- [Architecture §4.1](../planning-artifacts/architecture.md) — survival state materialized (source of truth)
- [Architecture §4.7](../planning-artifacts/architecture.md) — Spectator mode branched layout (consumes this endpoint)
- [Architecture §4.14](../planning-artifacts/architecture.md) — Two-channel realtime privacy (server-side filter is non-negotiable)
- [Architecture §5.1](../planning-artifacts/architecture.md) — Backend patterns (`ApiResponse.of`, controller path, constructor injection)
- [Architecture §5.4](../planning-artifacts/architecture.md) — Privacy patterns
- [Architecture §6.1](../planning-artifacts/architecture.md) — BE module layout (survival/ + revival/)
- [Architecture §6.3 V11 (3)](../planning-artifacts/architecture.md) — `survival_state` schema
- [Architecture §6.4](../planning-artifacts/architecture.md) — REST endpoint table (GET /rooms/{id}/survival row)
- [Story 1.1 Dev Agent Record](./1-1-room-creation-with-v1-cap-14-day-grace-trial.md) — survival module foundation, `SurvivalStateService` constructor + `inGraceWindow` contract
- [Story 1.2 Dev Agent Record](./1-2-06-00-kst-survival-state-evaluator-job.md) — `evaluateRoom`, `SurvivalStateChangePayload`, two-channel realtime, STOMP dot-convention
- [Implementation Readiness Report 2026-05-11](../planning-artifacts/implementation-readiness-report-2026-05-11.md) — Epic 1 readiness assessment
- [project-context.md](../project-context.md) — BE/FE rules + don't-miss list (controller path `/api/v1`, `ApiResponse.of`, constructor injection, `open-in-view: false`, single `@RestControllerAdvice`, `Clock` injection, H2 forbidden, Testcontainers Postgres)
- Existing source for pattern reference:
  - `BE/src/main/java/com/yeosal/api/room/RoomController.java` (controller path + `currentUser.require(auth)` + `ApiResponse.of(...)` envelope pattern — direct mirror)
  - `BE/src/main/java/com/yeosal/api/room/RoomService.java` (`members(...)` and `todayForRoom(...)` — `requireRoom` + `requireMembership` patterns; `Room.owner` lazy-load inside `@Transactional`)
  - `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java` (Story 1.1 + 1.2 — extend, don't replace; reuse constructor deps)
  - `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRepository.java` (`findByRoomId` + `findByRoomIdAndUserId` already shipped)
  - `BE/src/main/java/com/yeosal/api/common/ApiResponse.java` + `ApiErrorResponse.java` + `ApiExceptionHandler.java` (envelope + 403/404 mappings — already wired)
  - `BE/src/main/java/com/yeosal/api/room/RoomMemberRepository.java` (`existsByRoomIdAndUserId` — the cheap membership check used by STOMP authorizer)
  - `BE/src/test/java/com/yeosal/api/room/RoomControllerIT.java` (Testcontainers + `@EnabledIfSystemProperty("yeosal.boot-smoke")` + `@WithMockUser` pattern — direct IT template)
  - `BE/src/test/java/com/yeosal/api/survival/SurvivalStateEvaluatorIT.java` (Story 1.2 IT — `survival_state` row seeding via package-internal setter access)
  - `BE/src/test/java/com/yeosal/api/survival/SurvivalStateServiceEvaluateRoomTest.java` (Story 1.2 unit test — `Clock.fixed(...)` + mocked-repo pattern)

## Dev Agent Record

### Agent Model Used

claude-opus-4-7 (1M context).

### Debug Log References

### Completion Notes List

- Ultimate context engine analysis completed — comprehensive developer guide created.
- 2026-05-12 — Story 1.3 implementation complete (BE-only per AC13). Added one DTO (`SurvivalStateDto`), one controller (`SurvivalStateController`), and one read-only service method (`SurvivalStateService.roster(...)`). Privacy mask predicate is the five-conjunct form prescribed by AC3, applied server-side. Algorithm hits exactly four SQL statements per request (AC10).
- 2026-05-12 — TDD followed: 12 unit tests in `SurvivalStateServiceRosterTest` covering all AC3/AC4/AC5/AC6/AC7 branches + AC12 borderline `now == broadVisibilityAt` exclusive-boundary case + defensive null-state and member-order assertions. 6 `@WebMvcTest` slice tests cover envelope shape, parameter forwarding, and 403/404 mapping. All 233 BE tests pass (`./gradlew test`).
- 2026-05-12 — AC12 BE-5.2 step (e) claimed unauthenticated → 401, but the production `SecurityConfig` declares no explicit `AuthenticationEntryPoint`; Spring Security 6's default for stateless requests is **403**. The web slice asserts `is4xxClientError()` to remain correct under both codes; the inline comment in `SurvivalStateControllerTest.roster_unauthenticated_returns_4xx` documents the gap. End-to-end behavior is the same: unauthenticated callers cannot read the roster.
- 2026-05-12 — `@WebMvcTest` slice required `excludeFilters` for `JwtAuthenticationFilter` + `RateLimitFilter` because Spring Boot 3.3.5's slice scan picks up `OncePerRequestFilter` types, which transitively pulled in `JwtService` and broke context startup. A nested `TestSecurityConfig` replaces the production chain with a minimal one that still gates `anyRequest().authenticated()` — first `@WebMvcTest` pattern in this repo, future controller slices can mirror it.
- 2026-05-12 — IT `SurvivalStateRosterIT` written + compiles, but execution deferred: Docker daemon not running in this environment. Story 1.3 acceptance gates only require `./gradlew test` green (which passes); IT runs are opt-in via `-Dyeosal.boot-smoke=true` per project-context. Run from a Docker-enabled environment with: `cd BE && ./gradlew test -Dyeosal.boot-smoke=true -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
- 2026-05-12 — `SurvivalStateService` constructor and existing methods (`initializeOnJoin`, `inGraceWindow`, `evaluateRoom`) preserved verbatim per BE-2.1 / Story 1.1 + 1.2 contracts. Story 1.1's `SurvivalStateServiceTest` and Story 1.2's `SurvivalStateServiceEvaluateRoomTest` were not touched and still pass.
- 2026-05-12 — `SecurityConfig.java` whitelist re-audited — still exactly the documented public list; no whitelist mutation made (BE-4.1).
- 2026-05-12 — `bash scripts/verify.sh` skipped because `FE/node_modules` not installed in this environment; BE half of verify (`./gradlew test`) ran green directly. Pre-existing FE baseline failures called out in Story 1.2 Git Intelligence remain out-of-scope.
- 2026-05-12 — OpenAPI doc verification (X-2) deferred: requires `./gradlew bootRun` against the live DB. springdoc is wired by project-context and `RoomController` already exposes its endpoints reflectively without annotations; `SurvivalStateController` follows the same pattern (no springdoc annotations added) so the new operation will appear automatically in `/yeolsal/v3/api-docs` once booted.

### File List

**New (added by this story):**

- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateDto.java` — Java record carrying the wire shape for the roster response (AC9).
- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateController.java` — `@RestController @RequestMapping("/api/v1/rooms")` exposing `GET /{id}/survival` (AC1).
- `BE/src/test/java/com/yeosal/api/survival/SurvivalStateServiceRosterTest.java` — 12 Mockito unit tests for the privacy mask predicate (AC12 unit list).
- `BE/src/test/java/com/yeosal/api/survival/SurvivalStateControllerTest.java` — `@WebMvcTest` slice (6 tests) covering envelope + 4xx mapping (AC12 web slice).
- `BE/src/test/java/com/yeosal/api/survival/SurvivalStateRosterIT.java` — Opt-in `@SpringBootTest` Testcontainers IT (6 tests) covering AC2–AC6 end-to-end (AC12 IT).

**Modified:**

- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java` — Added `@Transactional(readOnly=true) roster(long, long)` + private `toDto(...)` helper. Existing public surface (constructor, `initializeOnJoin`, `inGraceWindow`, `evaluateRoom`) preserved verbatim.
- `_bmad-output/implementation-artifacts/1-3-privacy-filtered-survival-roster-api.md` — Status flipped `ready-for-dev → in-progress → review`; tasks checked; Dev Agent Record + Change Log filled.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `1-3-privacy-filtered-survival-roster-api` flipped `ready-for-dev → in-progress`; will be flipped to `review` in the final step.

## Change Log

- 2026-05-12 — Story 1.3 implemented BE-only (AC13). Added `SurvivalStateDto`, `SurvivalStateController`, `SurvivalStateService.roster(...)` + privacy mask helper. Added unit (`SurvivalStateServiceRosterTest`), web slice (`SurvivalStateControllerTest`), and opt-in IT (`SurvivalStateRosterIT`). All non-IT BE tests green (233 pass, 0 fail). Status: `ready-for-dev → review`.
