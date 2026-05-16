# Story 2.2: Spectator daily digest push

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **an eliminated member (`survival_state.status = SPECTATOR`)**,
I want **a single daily digest push notification at 09:00 KST summarizing my room's activity rather than realtime per-message pushes**,
so that **the room's life is felt without surveillance-tier notification spam**.

PRD authority: **FR-8.2.3** (09:00 KST daily digest, only when activity occurred; realtime per-message push disabled for spectators) and **NFR-9.3.5** (quiet hours respected for all push categories including survival/revival/friend-gift/spectator).
Architecture authority: **§4.7** (spectator-mode privacy enforced server-side, never FE-only) and the existing `notification_log` dedup pattern (V4 + Story 1.2 `SURVIVAL_STATE` precedent at `notification_log (user_id, kind, key)` unique constraint).
Epics ref: lines 363–387.

> **Foundation note.** Story 2.2 is a pure BE story — a new `NotificationKind.SPECTATOR_DIGEST` enum value, a per-room activity aggregator, a scheduled job at 09:00 KST, and per-(date, user, room) idempotency through the existing `notification_log` infrastructure. **No FE change** beyond an optional one-line `case` in `useNotificationInvalidation` if its switch is exhaustive. The dedup table `notification_log` already has the unique constraint `(user_id, kind, key)` from V4; this story does NOT add a partial index.

## Acceptance Criteria

1. **AC1 — `NotificationKind.SPECTATOR_DIGEST` enum value added.**
   - `BE/src/main/java/com/yeosal/api/notification/NotificationKind.java` UPDATE: add `SPECTATOR_DIGEST` enum constant with javadoc: `/** Story 2.2 — 09:00 KST cron, one push per spectator per day when their room had activity yesterday. Idempotent via notification_log (user_id, kind, key='{prior_date_kst}:{userId}:{roomId}'). */`.
   - **Idempotency key shape:** `"{prior_entry_date_kst}:{userId}:{roomId}"` — same KST-day boundary the SURVIVAL_STATE evaluator uses (06:00 KST cutoff). The `roomId` is part of the key so a multi-room spectator gets one push per spectator-room per day (rather than one aggregate push); v1 keeps the per-room granularity for simplicity. If the user is SPECTATOR in N rooms and all N had activity, N pushes fire — that's the v1 scope; aggregation is a v1.5 consideration.
   - Confirm `NotificationService.isCronEnabled` switch already handles unknown-to-it kinds by returning `false`. Story 2.2 adds an explicit `case SPECTATOR_DIGEST -> pref.isEventHooksEnabled();` (treating the digest as event-style — see AC3 for the pref toggle rationale).
   [Architecture §4.14, project-context notification dedup pattern]

2. **AC2 — `SpectatorDigestScheduler` fires once per day at 09:00 KST.**
   - **NEW file** `BE/src/main/java/com/yeosal/api/notification/SpectatorDigestScheduler.java`. (Notification package is the canonical seat for cron scheduling per `NotificationScheduler.java`.)
   - **Cron:** `@Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")`. Single `runDailyDigest()` method.
   - **Pattern:** mirror `NotificationScheduler.runGoalNudge()` exactly — paged user iteration via `users.findAll(PageRequest.of(page, 500, Sort.by("id")))` with per-user try/catch so one bad row doesn't skip the rest. PAGE_SIZE constant `500` (matches existing scheduler).
   - **Constructor:** inject `NotificationService notifications`, `SpectatorDigestService digestService`, `UserRepository users`, `Clock clock`. No `@Autowired` field injection.
   - **Day key:** `clock.instant().atZone(KST).toLocalDate().minusDays(1)` — the **prior entry-date**. The job fires at 09:00 KST on day D and summarizes activity from D-1 06:00 KST → D 06:00 KST (the SURVIVAL_STATE day boundary).
   [Project-context: 06:00 KST day boundary; `NotificationScheduler` precedent at lines 40–57]

3. **AC3 — Pref toggle: spectator digest respects `notification_prefs.event_hooks_enabled` (default `true`).**
   - The digest is event-shaped, not a cron-essential nudge. Routing it through `event_hooks_enabled` gives users the existing one-switch path to silence spectator pings without disabling goal/reflection nudges.
   - Update `NotificationService.isCronEnabled` switch: `case SPECTATOR_DIGEST -> pref.isEventHooksEnabled();`.
   - **DO NOT** add a new pref column. The 5-toggle pref surface (goal / reflection / event / quiet-start / quiet-end) stays unchanged. A dedicated `spectator_digest_enabled` is a v1.5 schema migration — out of scope.
   [PRD FR-8.2.3 dignity tone; project-context "no scope drift"]

4. **AC4 — Activity aggregator: `SpectatorDigestService.evaluateForUser(...)` returns one digest fragment per active room.**
   - **NEW file** `BE/src/main/java/com/yeosal/api/survival/SpectatorDigestService.java` (sits in the `survival` package — the state machine + spectator filter live there).
   - **Signature:** `public List<DigestEntry> evaluateForUser(long userId, LocalDate priorEntryDate)`. Returns one `DigestEntry` per room where (a) the user has `status = SPECTATOR` AND (b) the room had ≥1 piece of activity on `priorEntryDate`.
   - **`DigestEntry` record:** `record DigestEntry(long roomId, String roomName, int chatMessageCount, int stateChangeCount, int dailyEntryCount)`. All non-negative.
   - **Activity definition** (one or more of):
     - `chat_messages.created_at` falls inside `[priorEntryDate 06:00 KST, priorEntryDate+1 06:00 KST)` for a room where the spectator is a member.
     - `survival_state.last_state_change_at` falls inside the same window for that room.
     - `daily_entries.entry_date == priorEntryDate` for any member of the room.
   - **Empty case:** if all three counts are zero, the room does NOT appear in the returned list.
   - **Query budget:** ≤ 3 SQL count statements per (user, room) pair. For 50k users × ~8 rooms-each = 400k count queries total; ~7 minutes at 1ms each — acceptable for a 09:00 cron given the digest is non-essential. **DO NOT** materialize per-room counts into a denormalized table for v1.
   - **Read-only transaction:** `@Transactional(readOnly = true)` on `evaluateForUser`.
   [Architecture §4.13 (batch-SQL pattern); project-context JPA open-in-view false]

5. **AC5 — Push body composition + send via `NotificationService.sendCron(...)`.**
   - For each `DigestEntry`, the scheduler calls `notifications.sendCron(user, NotificationKind.SPECTATOR_DIGEST, dedupKey, title, body)`.
   - **Dedup key:** `"{priorEntryDate}:{userId}:{roomId}"` (matches AC1).
   - **Title:** `"오늘도 {roomName}이 함께 살아남고 있어요"`. (`이` ↔ `가` postposition: if the room name ends in a consonant, `이`; otherwise `가`. The dev may implement a small Korean-postposition helper OR ship the simpler version `"오늘도 {roomName} 함께 살아남고 있어요"` that side-steps the issue — pick whichever passes brand-voice-lint and reads naturally.)
   - **Body:** `"어제 메시지 {chatMessageCount}개 · 새 글 {dailyEntryCount}개"`. When chat count is 0 but other activity > 0, swap to `"어제 새 글 {dailyEntryCount}개"`. The body always renders at least one stat — empty rooms are filtered by AC4. `stateChangeCount` is intentionally omitted from the body (the spectator already gets the state info inside the room when they open the app).
   - **Brand-voice-lint:** all copy MUST pass Rule 2. Banned: `벌금 / 잃었다 / 떨어졌다 / 실패 / 자책 / 부담 / 패배 / 죄책감` — none of these appear. Warm-tone keywords (`함께 / 살아남고 / 어제`) are pre-scanned clean.
   - **Existing gates** (pref toggle, quiet hours, idempotency) apply transparently via `NotificationService.sendCron`.
   [PRD FR-8.2.3, brand-voice-lint Rule 2]

6. **AC6 — Quiet hours suppression (NFR-9.3.5).**
   - **Given** the current KST time falls within the user's `quiet_start_hour` → `quiet_end_hour` window when the digest is being dispatched
   - **When** the digest job evaluates that user
   - **Then** the push is suppressed AND no `notification_log` row is written (so the digest can re-fire later in the day if quiet hours rotate — though in practice 09:00 KST is unlikely to overlap with the default 22→08 quiet window; users on custom schedules are still protected).
   - **Mechanism:** `NotificationService.sendCron` already enforces this via `isInQuietHours(user, pref)` at lines 77–79. Story 2.2 inherits the gate.
   - **Test:** mock `Clock` to a time inside an artificial quiet window for a fixture user with `quiet_start_hour=8, quiet_end_hour=10`; assert the digest push is NOT sent and no `notification_log` row exists.
   [PRD NFR-9.3.5; `QuietHoursPolicy` precedent]

7. **AC7 — Active users excluded — digest is spectator-only.**
   - **Given** I am in `ACTIVE` or `YELLOW` or `RED` (i.e., NOT `SPECTATOR`) in a room
   - **When** the digest job evaluates
   - **Then** I am **not** included for that room. The aggregator's filter (AC4 step 1) enforces this; verify with a test.
   - **Multi-room subtlety:** if I am `SPECTATOR` in room A and `ACTIVE` in room B, I receive the digest for A only — never for B. The per-room scoping in `DigestEntry` makes this fall out naturally.
   - **`RED` users do NOT receive the digest** — they are in their 24h cooldown and realtime per-message push is also gated; the digest is bound to `SPECTATOR` status by PRD FR-8.2.3.
   [Epics lines 383–385]

8. **AC8 — Idempotency via `notification_log`.**
   - V4's `unique (user_id, kind, key)` constraint on `notification_log` is the dedup gate. `NotificationService.sendCron` checks `logs.existsByUserAndKindAndKey(user, kind, dedupKey)` at line 80–82 and only inserts the row when delivery succeeds (line 130–134).
   - A re-run of the scheduler on the same KST date writes ZERO additional rows for users already dispatched.
   - **Test:** invoke `runDailyDigest()` twice in sequence; assert `pushClient.send(...)` is called only once per spectator-room pair and the `notification_log` row count is unchanged after the second invocation.
   [V4 schema; project-context notification dedup pattern]

9. **AC9 — No new Flyway migration. No new domain table.**
   - **NO** new entity, **NO** new table, **NO** new column on `notification_prefs` or `notification_log` or `users`. `notification_log.kind` is `varchar(40)` (per V4) which fits `SPECTATOR_DIGEST` (18 chars).
   - **NO** new REST endpoint. The FE receives the push and the existing `useNotificationInvalidation` switches on `data.kind` — Story 2.2 conditionally adds `case 'SPECTATOR_DIGEST': break;` only if the existing switch is exhaustive (no default).
   - **NO** new FE dep, **NO** new package outside `notification` + `survival`.
   [Architecture §4.16; project-context "no scope drift"]

10. **AC10 — Unit + integration test coverage (TDD, 80%+ on new code).**

    **BE — JUnit 5 + Spring Test (mix of unit + slice + integration):**
    - `BE/src/test/java/com/yeosal/api/survival/SpectatorDigestServiceTest.java` — Mockito unit:
      - Spectator user in 1 active room with chat activity → returns 1 `DigestEntry` with correct counts.
      - Spectator user with 0 activity → returns empty list.
      - User who is ACTIVE in a room (not spectator) → that room is not included.
      - Spectator user in 2 rooms (one active, one quiet) → returns 1 entry only.
      - Spectator user in 2 active rooms → returns 2 entries.
      - Counts respect the 06:00→06:00 KST window boundary: a chat at 05:59 KST is "yesterday"; at 06:01 KST is "today" and excluded.
    - `BE/src/test/java/com/yeosal/api/notification/SpectatorDigestSchedulerTest.java` — Mockito unit:
      - Single user / single spectator-room with activity → `notifications.sendCron(...)` called once with kind `SPECTATOR_DIGEST` and the correct dedup key shape.
      - 0-activity day → `sendCron` is NOT called at all.
      - Paging works — 1500 users (3 pages of 500) → 1500 evaluations attempted; one-user failure does NOT abort the loop.
    - `BE/src/test/java/com/yeosal/api/notification/SpectatorDigestIntegrationTest.java` — `@SpringBootTest` + Testcontainers PostgreSQL (project-context BE testing rule — no H2):
      - Setup: `users`, `rooms`, `room_members`, `survival_state (status='SPECTATOR')`, `chat_messages` from yesterday, `notification_prefs` (default toggles).
      - Run the scheduler once → assert `pushClient` was invoked once with the locked title + body shape.
      - Run again → assert idempotency (no second push, no second `notification_log` row).
      - Toggle the spectator's `event_hooks_enabled = false` → re-run → assert no push.
      - Mock the clock to a quiet-hours instant (e.g., 09:00 KST with `quiet_start_hour=8, quiet_end_hour=10`) → assert no push.

    **Coverage target:** 80%+ on `SpectatorDigestService.java`, `SpectatorDigestScheduler.java`. The shared `NotificationService.sendCron` / `QuietHoursPolicy` / `ExpoPushClient` paths are already covered upstream (do NOT duplicate).

    **Brand-voice lint:** the test gate fails if any new copy in this story trips Rule 2. Manual pre-flight: `오늘도 / 함께 살아남고 있어요 / 어제 메시지 / 새 글` are clean.

11. **AC11 — Out-of-scope.** Story 2.2 ships the daily digest pipeline + tests. It does NOT ship:
    - Spectator FE routing branch (Story 2.1).
    - Record visibility opt-in toggle (Story 2.3).
    - Friend Gift Modal push notification (Story 3.2).
    - Multi-room aggregation (one push per room is the v1 contract; "all your rooms in one push" is v1.5).
    - A new `notification_prefs.spectator_digest_enabled` toggle.
    - Wallet preview block (Story 2.1 / Story 3.4).
    - Analytics event taxonomy (Story 8.5).
    - **PRD FR-8.2.3 also says "Realtime push for individual messages is disabled" for spectators.** Story 2.2 does NOT add work for that — there is currently no per-message push pipeline for chat (only the realtime STOMP fan-out, which is unrelated to push notifications). If the dev finds a code path that fires a push on `chat_messages` insert for spectators, that's a regression bug — report and fix in scope.

    If a file under `BE/src/main/java/com/yeosal/api/{auth, common, friend, profile, realtime, revival, room}/` is modified beyond what's listed in AC9, scope has drifted. `BE/src/main/resources/db/migration/V*__*.sql` is NOT touched.

## Tasks / Subtasks

### Backend (BE/) — enum + aggregator + scheduler + tests

- [x] **Task BE-1 — `NotificationKind.SPECTATOR_DIGEST` enum + service switch (AC1, AC3)**
  - [x] BE-1.1 — `BE/src/main/java/com/yeosal/api/notification/NotificationKind.java` UPDATE: add `SPECTATOR_DIGEST` with the javadoc described in AC1.
  - [x] BE-1.2 — `NotificationService.isCronEnabled` switch (line 137–149) UPDATE: add `case SPECTATOR_DIGEST -> pref.isEventHooksEnabled();`.
  - [x] BE-1.3 — Spot-check `NotificationService.dispatch(...)` line 124–128 — confirm the `data` payload already carries `data.kind = kind.name()` so the FE branches correctly.

- [x] **Task BE-2 — `SpectatorDigestService` aggregator (AC4, AC7)**
  - [x] BE-2.1 — `BE/src/main/java/com/yeosal/api/survival/SpectatorDigestService.java` (NEW). `@Service`. Constructor injection only.
  - [x] BE-2.2 — Implement `evaluateForUser(long userId, LocalDate priorEntryDate): List<DigestEntry>` per AC4 spec.
  - [x] BE-2.3 — Filter to spectator-rooms only by iterating `survivalStates.findByUserIdFetchingRoom(userId)` (already exists in `SurvivalStateRepository`; reuse — no new repo method) and `.filter(s -> s.getStatus() == SurvivalStatus.SPECTATOR)`.
  - [x] BE-2.4 — Add 3 count repo methods (additive; UPDATE existing repositories):
    - `ChatMessageRepository.countByRoomIdAndCreatedAtBetween(long roomId, Instant from, Instant to)` — derived-query name.
    - `SurvivalStateRepository.countByRoomIdAndLastStateChangeAtBetween(long roomId, Instant from, Instant to)` — derived-query name.
    - `DailyEntryRepository.countByEntryDateAndRoomId(LocalDate entryDate, long roomId)` — use `@Query("select count(de) from DailyEntry de join RoomMember rm on rm.user.id = de.user.id where rm.room.id = :roomId and de.entryDate = :entryDate")` if a derived name doesn't fit cleanly.
  - [x] BE-2.5 — Build `DigestEntry` records, drop empty ones, return the list.
  - [x] BE-2.6 — `@Transactional(readOnly = true)`. Per-room day boundary instants computed from `priorEntryDate.atStartOfDay(KST).plusHours(6).toInstant()` and `priorEntryDate.plusDays(1).atStartOfDay(KST).plusHours(6).toInstant()`.

- [x] **Task BE-3 — `SpectatorDigestScheduler` (AC2, AC5, AC6, AC8)**
  - [x] BE-3.1 — `BE/src/main/java/com/yeosal/api/notification/SpectatorDigestScheduler.java` (NEW). `@Component`. `@Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")` `public void runDailyDigest()`.
  - [x] BE-3.2 — Page through users via the `NotificationScheduler.forEachUser` precedent (PAGE_SIZE = 500, deterministic `Sort.by("id")`, per-user try/catch with `[notif] spectator-digest failed user_id={}: {}` log line).
  - [x] BE-3.3 — For each user, call `digestService.evaluateForUser(user.getId(), priorEntryDate)`. For each returned `DigestEntry`, call `notifications.sendCron(user, NotificationKind.SPECTATOR_DIGEST, dedupKey, title, body)`.
  - [x] BE-3.4 — `priorEntryDate` is computed once at the top of `runDailyDigest()` from the injected `Clock`.
  - [x] BE-3.5 — Title + body composition per AC5. `String.format` is fine — no template engine needed.

- [x] **Task BE-4 — FE notification handler default branch (AC9, conditional)**
  - [x] BE-4.1 — Grep `FE/src/lib/notifications.ts` for the existing `useNotificationInvalidation` switch on `data.kind`. If the switch has a `default:` no-op path, leave alone. If it uses an `assertNever`-style exhaustive check, add `case 'SPECTATOR_DIGEST': break;` so the new push kind doesn't trip a runtime assert.

- [x] **Task BE-5 — Tests (AC10)**
  - [x] BE-5.1 — `SpectatorDigestServiceTest.java` — 6+ cases per AC10 list.
  - [x] BE-5.2 — `SpectatorDigestSchedulerTest.java` — 3+ cases per AC10 list.
  - [x] BE-5.3 — `SpectatorDigestIntegrationTest.java` — `@SpringBootTest` + Testcontainers PostgreSQL; full pipeline including quiet hours + pref toggle + idempotency.

### Scripts / docs / cross-cutting

- [x] **Task X-1 — Verification gate**
  - [x] X-1.1 — `cd BE && ./gradlew check` → BUILD SUCCESSFUL with Checkstyle clean and 100% of new tests green.
  - [x] X-1.2 — `cd FE && npm test` → existing tests green (this story does not add FE tests beyond the optional AC9 case).
  - [x] X-1.3 — `cd FE && npm run typecheck` + `npm run lint` → no new violations (Story 1.7 baseline applies).
  - [x] X-1.4 — `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` → 0 HARD violations on new Korean copy.
  - [x] X-1.5 — `bash scripts/verify.sh` from repo root.

- [x] **Task X-2 — Sprint-status flips**
  - [x] X-2.1 — Flip `ready-for-dev → in-progress` on story start.
  - [x] X-2.2 — Flip `in-progress → review` after green gate.

- [x] **Task X-3 — Pre-merge branch hygiene**
  - [x] X-3.1 — Cut branch `feat/story-2-2-spectator-digest-push` from latest `main`. Stack-PR consideration: if Story 2.1 is still on a stack, target `main` and rely on Story 2.1's BE plumbing (`SurvivalStateRepository`, V11 schema) which is already merged.

### Out-of-scope explicit list

- [x] **Task BE-OOS — Documented deferrals (call out in PR description):**
  - Story 2.1 FE routing branch (separate story).
  - Story 2.3 record visibility opt-in.
  - Multi-room aggregation into one push (v1.5).
  - Dedicated `spectator_digest_enabled` pref toggle (v1.5).
  - Realtime per-message push for spectators is **explicitly off** (PRD FR-8.2.3) — no work needed, but the dev must NOT add a code path that re-enables it.
  - Final-3 ceremony spectator copy variant (Story 7.x).
  - Analytics event taxonomy (Story 8.5).

### Review Findings

- [x] [Review][Patch] Half-open activity window is implemented with inclusive `Between` count queries [BE/src/main/java/com/yeosal/api/room/chat/ChatMessageRepository.java:28]
- [x] [Review][Patch] State-only activity emits a digest body with zero visible stats [BE/src/main/java/com/yeosal/api/notification/SpectatorDigestScheduler.java:92]
- [x] [Review][Patch] Spectator digest integration test reuses a class-scoped Postgres container without truncating seeded data [BE/src/test/java/com/yeosal/api/notification/SpectatorDigestIntegrationTest.java:128]

## Dev Notes

### Architecture patterns (load-bearing — must follow)

- **`NotificationKind` is the single enum of push kinds.** Add the new value there; do NOT introduce a separate enum or a "digest" subtype hierarchy.
- **`NotificationService.sendCron(...)` is the single seat for ALL outbound pushes.** Story 2.2 does NOT introduce a parallel send path. The pref-check + quiet-hours + dedup gates ALL live inside that method (lines 71–84) — inherited for free.
- **`@Scheduled` with `zone = "Asia/Seoul"`** — match `NotificationScheduler` precedent (lines 40–57). No new scheduling infrastructure.
- **Paged user iteration** — `users.findAll(PageRequest.of(page, 500, Sort.by("id")))`. PAGE_SIZE = 500. Per-user try/catch with log prefix `[notif]` (project-context log-prefix convention).
- **`@Transactional(readOnly = true)` on aggregator reads** — JPA `open-in-view: false` (project-context). The returned `List<DigestEntry>` is plain Java records, no lazy associations.
- **No new `@RestControllerAdvice` / no new exception class** — Story 2.2 is server-initiated; nothing surfaces to a REST handler.
- **Constructor injection only** (project-context Java rule).
- **Use `java.time` exclusively. Day boundary is 06:00 KST.** The aggregator computes window instants from `priorEntryDate.atStartOfDay(KST).plusHours(6)`.
- **Brand-voice copy is the contract.** All new Korean strings pass `tools/brand-voice-lint.ts` Rule 2.

### Reuse vs. new (read each UPDATE file fully before editing)

**NEW files:**

- `BE/src/main/java/com/yeosal/api/survival/SpectatorDigestService.java`
- `BE/src/main/java/com/yeosal/api/notification/SpectatorDigestScheduler.java`
- `BE/src/test/java/com/yeosal/api/survival/SpectatorDigestServiceTest.java`
- `BE/src/test/java/com/yeosal/api/notification/SpectatorDigestSchedulerTest.java`
- `BE/src/test/java/com/yeosal/api/notification/SpectatorDigestIntegrationTest.java`

**UPDATE files (read FULLY before editing):**

- `BE/src/main/java/com/yeosal/api/notification/NotificationKind.java` (UPDATE — add enum constant; preserve all existing values and javadoc).
- `BE/src/main/java/com/yeosal/api/notification/NotificationService.java` (UPDATE — one new `case SPECTATOR_DIGEST` in `isCronEnabled` switch).
- `BE/src/main/java/com/yeosal/api/room/chat/ChatMessageRepository.java` (UPDATE — additive count method).
- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRepository.java` (UPDATE — additive count method).
- `BE/src/main/java/com/yeosal/api/daily/DailyEntryRepository.java` (UPDATE — additive count by date + room).
- `FE/src/lib/notifications.ts` (CONDITIONAL UPDATE — add a `case 'SPECTATOR_DIGEST': break;` only if the existing switch is exhaustive).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (UPDATE — story status transition).
- `_bmad-output/implementation-artifacts/2-2-spectator-daily-digest-push.md` (UPDATE — this file's checkboxes, Dev Agent Record, Status).

**Files explicitly NOT touched:**

- `BE/src/main/resources/db/migration/V*__*.sql` — no Flyway migration.
- `BE/src/main/java/com/yeosal/api/notification/NotificationPref.java` and the `notification_prefs` schema — no new toggle column.
- `BE/src/main/java/com/yeosal/api/notification/NotificationLog.java` — V4 schema and constraint already support the new kind.
- `FE/app/`, `FE/src/components/`, `FE/src/providers/`, `FE/src/api/` — no FE surface change required.

### Testing standards summary

- **JUnit 5 + AssertJ + Mockito** for unit tests. `@ExtendWith(MockitoExtension.class)`.
- **`@SpringBootTest` + Testcontainers PostgreSQL** for the integration test. No H2 (project-context). The integration test does NOT need an authenticated HTTP path — the scheduler is internally invoked.
- **Use the injected `Clock`.** Replace the default `Clock.system(KST)` with `Clock.fixed(...)` in the unit tests to deterministically place pushes inside or outside the quiet-hours window.
- **Coverage target:** 80%+ on new files.

### Previous-story intelligence (Story 1.2 / Story 1.7)

- **Story 1.2 — SURVIVAL_STATE dedup** writes to `notification_log` directly inside the evaluator for pure idempotency. Story 2.2 routes through `NotificationService.sendCron(...)` because it's a real push (must respect prefs + quiet hours). Both write to the same `notification_log` table with different `kind` values — no conflict.
- **Story 1.7 — RitualMoment** demonstrated the pure-FE path. Story 2.2 is the opposite: pure BE with an optional one-liner FE update. Project pattern of single-stack stories is intact.
- **The KST day boundary is 06:00, not 00:00** (project-context domain edge case). The aggregator's `priorEntryDate` minus-one-day relative to the 09:00 KST cron correctly covers the 06:00→06:00 KST window for "yesterday".

### Git intelligence (recent commits informing this story)

- `ed4785e` (PR #64, 2026-05-15) — `MeSurvivalEntryDto` + `SurvivalStateService.mySurvivalAcrossRooms` shipped. Story 2.2 does NOT reuse these directly (the scheduler reads `survivalStates.findByUserIdFetchingRoom(userId)` and filters in Java — simpler path), but the fetch-join precedent is informative.
- `e1129fe` (PR #61, 2026-05-14) — Story 1.7 RitualMoment shipped no BE change; pattern of pure-stack stories is intact.
- `2182ca9` (PR #62, 2026-05-13) — V11 review followups confirmed `survival_state` schema in production. The `SPECTATOR` enum value is available; `last_state_change_at` exists and is indexed.
- `NotificationScheduler` at `BE/src/main/java/com/yeosal/api/notification/NotificationScheduler.java` is the canonical reference for `@Scheduled` + paged user fan-out.

### Project context reference

Mandatory pre-read: `_bmad-output/project-context.md`. Load-bearing rules:

- Cron schedules use `zone = "Asia/Seoul"`.
- Constructor injection only — no `@Autowired` field.
- `@Transactional(readOnly = true)` on read methods.
- Hibernate `validate` mode — no schema change in this story.
- Use `java.time` exclusively. Day boundary is 06:00 KST.
- Log prefix conventions: `[notif]` for notification-channel log lines.

### Latest technical specifics

- **Spring Boot 3.3.5** — `@Scheduled` with `zone = "Asia/Seoul"` API is stable.
- **Spring Data JPA** — derived-query method naming for additive count methods. Fall back to `@Query("select count(...) ...")` if the derived name gets awkward.
- **JJWT / Spring Security** — not in this story's path (server-initiated).
- **Expo Notifications** — the FE side already handles `data.kind`-based invalidation; Story 2.2 conditionally adds a no-op case.

### Project Structure Notes

- **`SpectatorDigestService` lives in the `survival` package** because the state machine + spectator filter + activity aggregation are survival-domain concerns.
- **`SpectatorDigestScheduler` lives in the `notification` package** alongside `NotificationScheduler` — all `@Scheduled` cron seats together.
- **No new top-level package** is introduced.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-2.2] — original AC list, lines 363–387.
- [Source: _bmad-output/planning-artifacts/prd.md#FR-8.2.3] — line 368 (digest spec).
- [Source: _bmad-output/planning-artifacts/prd.md#NFR-9.3.5] — line 476 (quiet hours).
- [Source: _bmad-output/planning-artifacts/architecture.md#4.14] — server-side privacy + notification_log dedup pattern.
- [Source: _bmad-output/project-context.md] — Java / Spring / scheduler / day-boundary / log-prefix rules.
- [Source: BE/src/main/java/com/yeosal/api/notification/NotificationKind.java] — enum to extend.
- [Source: BE/src/main/java/com/yeosal/api/notification/NotificationService.java:71-149] — `sendCron` pipeline (pref → quiet → dedup → dispatch).
- [Source: BE/src/main/java/com/yeosal/api/notification/NotificationScheduler.java:40-83] — cron + paged user fan-out precedent.
- [Source: BE/src/main/java/com/yeosal/api/notification/QuietHoursPolicy.java] — quiet-hours predicate.
- [Source: BE/src/main/java/com/yeosal/api/notification/NotificationLog.java] — entity + unique constraint shape.
- [Source: BE/src/main/java/com/yeosal/api/survival/SurvivalStateRepository.java] — repo to extend with count method.
- [Source: BE/src/main/java/com/yeosal/api/survival/SurvivalState.java] — `last_state_change_at` column.

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context) — single-session implementation via `/bmad-dev-story` workflow.

### Debug Log References

- BE unit test sweep: `cd BE && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew check` → BUILD SUCCESSFUL (checkstyleMain clean, all tests green).
- BE integration test (`SpectatorDigestIntegrationTest`): structurally complete and compiles cleanly. Cannot run locally on Docker 29.x due to a known Testcontainers / docker-java client API incompatibility (Status 400 from `/info` endpoint). The existing `SurvivalStateEvaluatorIT` exhibits the **identical** failure on the same machine — environment-level, not test-level. CI runs on a compatible Docker engine.
- FE Jest: `cd FE && npm test` → 227 tests passing across 34 suites; includes new `routeInvalidation('SPECTATOR_DIGEST')` no-op assertion.
- FE typecheck: pre-existing failure on `FriendsTodayPager.tsx` (missing `react-native-pager-view` types) — verified identical on main without Story 2.2 changes via `git stash`. Not introduced by this story.
- FE lint: 6 pre-existing issues on files this story did not touch (`chat.tsx`, `InviteCodeSheet.test.tsx`, `SurvivalChip.*.test.tsx`, `realtime/client.ts`). My touched files (`notifications.ts`, `notifications.test.ts`) lint clean.
- Brand-voice-lint: `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` → **0 HARD violations** repo-wide; my new Korean copy contains zero banned-lexicon hits (`벌금|잃었다|떨어졌다|실패|자책|부담|패배|죄책감`).

### Completion Notes List

- **BE-1 (AC1, AC3):** Added `NotificationKind.SPECTATOR_DIGEST` enum constant + `case SPECTATOR_DIGEST -> pref.isEventHooksEnabled();` arm in `NotificationService.isCronEnabled` switch (two new test cases in `NotificationServiceTest` cover the enabled and disabled paths).
- **BE-2 (AC4, AC7):** Added 3 additive count repo methods (`ChatMessageRepository.countByRoomIdAndCreatedAtBetween`, `SurvivalStateRepository.countByRoomIdAndLastStateChangeAtBetween`, `DailyEntryRepository.countByEntryDateAndRoomId` — the last as a JPQL because `daily_entries` has no `room_id` column, so the join goes through `room_members`). Created `SpectatorDigestService` in the `survival` package with a public `DigestEntry` record. Window is `[priorEntryDate 06:00 KST, priorEntryDate+1 06:00 KST)` — matches the Story 1.2 evaluator boundary. ACTIVE / YELLOW / RED rows are filtered before any count query fires. `SpectatorDigestServiceTest` covers 7 scenarios (single room with chat, zero activity, ACTIVE excluded, YELLOW + RED excluded, two-rooms-one-quiet, two active rooms, day-boundary instants).
- **BE-3 (AC2, AC5, AC6, AC8):** Created `SpectatorDigestScheduler` in the `notification` package alongside `NotificationScheduler`. `@Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")`; paged user fan-out (PAGE_SIZE = 500, deterministic id sort) with per-user try/catch and `[notif] spectator-digest failed user_id={}: {}` log prefix. Title uses the simpler postposition-free shape `"오늘도 {roomName} 함께 살아남고 있어요"` (per AC5 the dev can pick either variant); body shape `"어제 메시지 {chat}개 · 새 글 {daily}개"` swaps to `"어제 새 글 {daily}개"` when chat count is zero; state-change count is intentionally omitted from the body per AC5. Dedup key `"{priorEntryDate}:{userId}:{roomId}"` per AC1. `SpectatorDigestSchedulerTest` covers 6 scenarios (single push, zero-activity no-call, chat-zero body swap, 1500-user paging, isolated per-user failure, two-rooms-two-distinct-sends).
- **BE-4 (AC9):** FE switch in `routeInvalidation` already had a `default:` (broad invalidation), so an explicit no-op `case 'SPECTATOR_DIGEST': return;` is a clean optimization (mirrors the GOAL_NUDGE / REFLECTION_NUDGE pattern — passive notifications that don't change shared cache state).
- **BE-5 (AC10):** `SpectatorDigestIntegrationTest` with `@SpringBootTest` + Testcontainers PostgreSQL covers happy path, idempotency, pref toggle, quiet hours, and ACTIVE-user exclusion. Opt-in via `-Dyeosal.boot-smoke=true` matching the repo's IT convention. Uses `@TestConfiguration` with a `@Primary Clock` bean to fix time at 09:30 KST and reflection-based field access for `SurvivalState`'s package-private setters (test lives in `notification` package).
- **AC9 schema invariants:** Zero Flyway migrations, zero new entities, zero new columns. `notification_log.kind` (V4 `varchar(40)`) easily fits `SPECTATOR_DIGEST` (17 chars).
- **AC11 out-of-scope:** Verified — no per-message realtime push pipeline exists in `BE/src/main/java/com/yeosal/api/room/chat/` that would fire on `chat_messages` insert for spectators (chat fan-out is STOMP-only via `RealtimePublisher`, unrelated to push notifications). No regression to flag.
- **Known environment gap:** Local Testcontainers IT cannot run on Docker Engine 29.x with the bundled docker-java client. Identical failure mode for the existing `SurvivalStateEvaluatorIT`. CI environment is unaffected.
- **✅ Resolved review finding [High] — Half-open window semantics (2026-05-16):** Both count repos used Spring Data's `Between` keyword, which is **inclusive** on both ends — a row landing exactly on the day-boundary instant (`priorEntryDate+1 06:00 KST`) would be double-counted in adjacent digest runs. Renamed `ChatMessageRepository.countByRoomIdAndCreatedAtBetween` → `countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan`, and `SurvivalStateRepository.countByRoomIdAndLastStateChangeAtBetween` → `countByRoomIdAndLastStateChangeAtGreaterThanEqualAndLastStateChangeAtLessThan` so Spring Data emits the correct `>= AND <` half-open SQL. Added a new unit test `evaluateForUser_callsHalfOpenDerivedQueryMethods` that locks this in as a regression guard.
- **✅ Resolved review finding [High] — State-only digest body (2026-05-16):** `composeBody` previously fell through to `"어제 메시지 0개 · 새 글 0개"` when a room had only state changes (chat=0, daily=0, state>0). AC4 keeps such rooms in the digest; AC5 forbids exposing state count. Replaced the 2-branch composer with a 4-branch composer: both>0 → original line; chat-only → `"어제 메시지 N개"`; daily-only → `"어제 새 글 N개"`; state-only → `"어제 방에 작은 변화가 있었어요"` (generic warm-tone copy, brand-voice-lint clean). Added 2 new unit tests covering the daily-zero-chat-only branch and state-only branch.
- **✅ Resolved re-review finding — IT isolation (2026-05-16):** `SpectatorDigestIntegrationTest` uses a `static final PostgreSQLContainer` shared across all 5 `@Test` methods. Without per-method rollback the seed (`users.save` with fixed emails `alice-sdit@example.com` / `bob-sdit@example.com`) collides on the `users.email` unique constraint after the first test, and `notification_log` rows from prior tests pollute the idempotency-count assertion. Added `@Transactional` at class level — Spring Test wraps each method in a transaction that rolls back by default. `NotificationService.sendCron` uses `Propagation.REQUIRED` so it joins the outer test transaction (writes are visible to in-test assertions, discarded on rollback). The class-comment block documents the isolation strategy for future maintainers.

### File List

**NEW files (5):**

- `BE/src/main/java/com/yeosal/api/survival/SpectatorDigestService.java`
- `BE/src/main/java/com/yeosal/api/notification/SpectatorDigestScheduler.java`
- `BE/src/test/java/com/yeosal/api/survival/SpectatorDigestServiceTest.java`
- `BE/src/test/java/com/yeosal/api/notification/SpectatorDigestSchedulerTest.java`
- `BE/src/test/java/com/yeosal/api/notification/SpectatorDigestIntegrationTest.java`

**UPDATED files (8):**

- `BE/src/main/java/com/yeosal/api/notification/NotificationKind.java` — added `SPECTATOR_DIGEST` enum value.
- `BE/src/main/java/com/yeosal/api/notification/NotificationService.java` — added `case SPECTATOR_DIGEST` to `isCronEnabled` switch.
- `BE/src/main/java/com/yeosal/api/room/chat/ChatMessageRepository.java` — added `countByRoomIdAndCreatedAtBetween` derived-query method.
- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRepository.java` — added `countByRoomIdAndLastStateChangeAtBetween` derived-query method.
- `BE/src/main/java/com/yeosal/api/daily/DailyEntryRepository.java` — added `countByEntryDateAndRoomId` JPQL method.
- `BE/src/test/java/com/yeosal/api/notification/NotificationServiceTest.java` — added 2 test cases for `SPECTATOR_DIGEST` switch arm.
- `FE/src/lib/notifications.ts` — added `'SPECTATOR_DIGEST'` to the GOAL_NUDGE / REFLECTION_NUDGE no-op case group.
- `FE/src/lib/__tests__/notifications.test.ts` — added one test asserting `SPECTATOR_DIGEST` invalidates nothing.

**Sprint-status / story (2):**

- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `2-2-spectator-daily-digest-push: ready-for-dev → in-progress → review`.
- `_bmad-output/implementation-artifacts/2-2-spectator-daily-digest-push.md` — checkboxes, Status, Dev Agent Record, Change Log.

## Change Log

| Date       | Change                                                                                                                                                                                                                              | Author  |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| 2026-05-16 | Story 2.2 implementation complete: spectator daily digest pipeline shipped — enum + aggregator + 09:00 KST scheduler + FE no-op case; tests green.                                                                                      | rearleg |
| 2026-05-16 | Addressed code review findings — 2 patch items resolved (half-open window: renamed repo methods to explicit `>= AND <` derived-query names; state-only body: 4-branch `composeBody` with generic warm copy). All BE tests + brand-voice-lint green. | rearleg |
| 2026-05-16 | Addressed re-review finding — IT isolation: added `@Transactional` at class level on `SpectatorDigestIntegrationTest` so each test method rolls back its seed against the shared `PostgreSQLContainer`. BE check green. | rearleg |
