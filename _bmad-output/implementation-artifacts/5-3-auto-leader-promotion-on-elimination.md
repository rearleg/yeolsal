# Story 5.3: Auto-leader-promotion on elimination

Status: in-progress

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the system,
I want to auto-promote the longest-tenured `ACTIVE` member to leader if the current leader transitions to `RED`,
So that no room is leaderless even when the leader misses two days.

## Acceptance Criteria

> 이 스토리는 **Epic 5의 세 번째 BE-only 변경**으로, 이미 Story 1.2가 publish하고 있는 `SurvivalStateTransitionEvent`를 두 번째로 구독하는 listener를 추가한다 (`SurvivalStateRealtimeListener` + `EligibleGiverPushListener` 가 first/second 구독자). 새 listener는 `(toStatus == RED && userId == ownerUserId)` 케이스에서만 firing해서 `rooms.owner_id` + 양측 `room_members.role` flip + Story 5.2가 추가한 `RealtimePublisher.publishLeadershipChange` 재사용으로 `/topic/rooms.{id}.survival` 에 `LeadershipChangePayload(reason="AUTO_ELIMINATION")` 를 publish한다. **NO new migration, NO new endpoint, NO FE source changes** — Story 5.2's leader-detection refetch-on-focus pattern carries the owner update through `useRoomsQuery`. **Atomicity vs epics line 782** ("all of the above happens atomically with the elimination transition"): Story 1.2's `SurvivalStateService.evaluateRoom` is the canonical RED transition site, and the project-wide `@TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)` pattern (already used by `SurvivalStateRealtimeListener` line 56-57 + `EligibleGiverPushListener` line 75-76) is the established discipline for state-derived side-effects. Epic line 782's "atomic" is therefore interpreted as **listener-internal atomicity** (single REQUIRES_NEW transaction owns owner_id + dual role flip + afterCommit emission registration), NOT same-transaction-as-elimination — coupling the promotion into `evaluateRoom` would violate package-by-feature (leader-of-record is `room/`, not `survival/`).

### AC1 — New `AutoLeaderPromotionListener` subscribes to `SurvivalStateTransitionEvent` (REQUIRED LISTENER)

**Given** Story 1.2's `SurvivalStateService.evaluateRoom` commits a YELLOW→RED transition for the room's `owner_id` member
**When** the per-room transaction commits
**Then** a new `@Component` listener at `com.yeosal.api.room.AutoLeaderPromotionListener` MUST fire AFTER_COMMIT in a `REQUIRES_NEW` writable transaction:

```java
package com.yeosal.api.room;

@Component
public class AutoLeaderPromotionListener {

    private static final Logger log = LoggerFactory.getLogger(AutoLeaderPromotionListener.class);

    private final RoomService roomService;
    private final RoomMemberRepository roomMembers;
    private final RealtimePublisher realtime;

    public AutoLeaderPromotionListener(
            RoomService roomService,
            RoomMemberRepository roomMembers,
            RealtimePublisher realtime) {
        this.roomService = roomService;
        this.roomMembers = roomMembers;
        this.realtime = realtime;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransition(SurvivalStateTransitionEvent event) {
        // Filter sequence (see AC2 for full reasoning):
        if (event.toStatus() != SurvivalStatus.RED) return;
        Long ownerUserId = event.ownerUserId();
        if (ownerUserId == null || ownerUserId != event.userId()) return;

        // ... AC3 longest-tenured query
        // ... AC4 idempotency guard
        // ... AC5 atomic owner_id + dual role flip
        // ... AC6 afterCommit publishLeadershipChange (reason=AUTO_ELIMINATION)
    }
}
```

**Listener pattern compliance:**
- `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` ensures a rolled-back elimination NEVER lights up the promotion fan-out — mirrors `SurvivalStateRealtimeListener:56-57` and `EligibleGiverPushListener:75-76`.
- `@Transactional(propagation = Propagation.REQUIRES_NEW)` because Spring's AFTER_COMMIT phase leaves no outer transaction context, and our writes (owner_id + role flips) MUST commit together — mirrors both precedents.
- Constructor injection only (project-context.md:88 + java/patterns.md). NO `@Autowired` fields.

**Anti-pattern (DO NOT IMPLEMENT):**
- Plain `@EventListener` (no transactional guard) — a rolled-back evaluator transaction would still trigger the promotion. The `@TransactionalEventListener(AFTER_COMMIT)` is non-negotiable.
- Coupling the promotion logic directly into `SurvivalStateService.evaluateRoom` — violates package-by-feature (leader-of-record is room-domain, NOT survival-domain) and forces `survival/` to depend on `room/` writable services, inverting the current dependency direction.
- A `@Scheduled` cron polling for "leaders that went RED but still own rooms" — adds latency, hides the atomic invariant, and creates a second source of truth on top of the event.

PRD: FR-8.5.7. Architecture: §4.* (leadership lifecycle), §597 (`RealtimeEvent.LeadershipChange` sealed variant). project-context decision §6.3 (PRD line 316: "Auto-promote longest-tenured surviving member").

### AC2 — Filter on `(toStatus == RED && userId == ownerUserId)` (CRITICAL CORRECTNESS)

**Given** the new listener receives every `SurvivalStateTransitionEvent` from EVERY room (Spring fans events to all matching listeners — confirmed by `SurvivalStateRealtimeListener` + `EligibleGiverPushListener` both consuming the same event type)
**When** the listener decides whether to act
**Then** the filter MUST be:

```java
// (1) Not a RED transition → no-op. Covers ACTIVE→YELLOW (evaluator)
//     and any *→ACTIVE (revival paths from Story 3.1/3.2).
if (event.toStatus() != SurvivalStatus.RED) return;

// (2) Not the leader's own elimination → no-op. Member-side RED
//     transitions are handled by other listeners (broad fan-out via
//     SurvivalStateRealtimeListener, friend-gift push fan-out via
//     EligibleGiverPushListener). A null ownerUserId is a defensive
//     branch — the event constructor accepts it, but evaluateRoom
//     always populates it from room.getOwner().getId().
Long ownerUserId = event.ownerUserId();
if (ownerUserId == null || ownerUserId != event.userId()) return;
```

**Why the order matters:** the RED check is the cheap path (single enum compare); the owner check requires unboxing `Long`. Filter cheap-first.

**Implicit consequence — no-reclaim invariant (epics line 778-780):**
- When the previous (eliminated) leader later revives via `RevivalService.reviveSelf` or `RevivalService.reviveFriend` (Story 3.1/3.2), the resulting `SurvivalStateTransitionEvent` carries `toStatus = ACTIVE` (`RevivalService.java:220`, `:405`). The AC2 filter rejects it on the FIRST condition, so the previous leader CANNOT auto-reclaim leadership. This invariant is satisfied **structurally** — not by code in this story, but by the filter shape combined with the revival path's existing emission. **DO NOT add any code path that would let the previous leader reclaim leadership** — explicit transfer (Story 5.2 manual flow) is the only way.

**Verification:** AC11 test matrix includes a "revival emits ACTIVE → listener no-ops, owner_id stays with current leader" case (`AutoLeaderPromotionListenerTest.revivedFormerLeaderDoesNotReclaim`).

### AC3 — Longest-tenured `ACTIVE` candidate query (LOCKED SEMANTICS)

**Given** the listener decides to act per AC2
**When** it picks the new leader
**Then** the query MUST select the room member with:
- `survival_state.status = 'ACTIVE'` (EXCLUSIVELY — NOT `ACTIVE` ∪ `YELLOW`; see "Eligibility scope reconciliation" below),
- `room_members.user_id != <previous_leader_user_id>` (defensive — the previous leader is RED so won't match the ACTIVE filter, but the explicit exclusion is a safety net against any race where their state hasn't yet been updated visibly to this REQUIRES_NEW transaction),
- ordered by `room_members.joined_at ASC, room_members.id ASC` (longest-tenured first; `id ASC` is the stable tiebreaker for the very-rare case where two members share `joined_at` — see Trap #2).

**Implementation (recommended): new repository method on `RoomMemberRepository`:**

```java
/**
 * Story 5.3 — pick the longest-tenured ACTIVE candidate for auto-leader
 * promotion when the current leader transitions to RED. Excludes the
 * eliminated leader explicitly (defensive — the ACTIVE filter already
 * excludes them via survival_state.status). Tiebreaker on
 * room_members.id ASC keeps the choice deterministic when two members
 * share joined_at (V11 backfill case: legacy room_members rows can land
 * in survival_state with identical timestamps).
 *
 * <p>Returns empty when no eligible candidate exists — the room is
 * dormant; leadership stays with the eliminated leader (PRD §6.3).
 */
@Query("""
        select rm
        from RoomMember rm
        where rm.room.id = :roomId
          and rm.user.id <> :excludedUserId
          and exists (
              select 1 from SurvivalState s
              where s.room.id = rm.room.id
                and s.user.id = rm.user.id
                and s.status = com.yeosal.api.survival.SurvivalStatus.ACTIVE
          )
        order by rm.joinedAt asc, rm.id asc
        """)
List<RoomMember> findLongestTenuredActiveCandidates(
        @Param("roomId") long roomId,
        @Param("excludedUserId") long excludedUserId);
```

**Why `List<RoomMember>` returning `findFirst` at the service layer (not `findFirst…`)**: the JPQL+Hibernate `setMaxResults(1)` boilerplate adds a second derivable method without measurable perf benefit at < 30 members per room. Using `List.stream().findFirst()` at the caller keeps the SQL plan trivial. If JaCoCo flags coverage on the empty branch, the caller-side `findFirst().orElse(null)` is the assertion site.

**Why JPQL not native SQL:** the existing `RoomMemberRepository` uses JPQL for `findByRoomFetchingUser`, `findRoomsByUser`, `existsSharedRoom`, etc. — stay consistent. The cross-table existence check via `EXISTS (select 1 from SurvivalState…)` is more portable than a native JOIN and produces a near-identical plan on PostgreSQL.

**Eligibility scope reconciliation (epic vs PRD vs Story 5.2):**
- **Epics line 768**: "longest-tenured active member (`MIN(joined_at)` among `survival_state.status = 'ACTIVE'`)" — **strict ACTIVE only**.
- **PRD §6.3 line 316 + PRD FR-8.5.7 line 407**: "longest-tenured *surviving* member" / "transitions to RED" — could be read as ACTIVE ∪ YELLOW.
- **Story 5.2 manual transfer** (`TransferLeadershipService.java:87`): accepts ACTIVE or YELLOW.

**Resolution adopted for Story 5.3:** the epics AC line 768 is the binding contract — **strict ACTIVE only**. Rationale: the auto-promotion fires unprompted (no leader action involved), so picking a YELLOW member would promote a candidate one missed day away from elimination — flips leadership twice in quick succession when the YELLOW promotee then goes RED. The manual-transfer flow (Story 5.2) explicitly allowing YELLOW is acceptable because the leader is the one making that judgment call. The auto flow MUST stay narrower. Trap #3 catalogs this and the unit test matrix (AC11) asserts the YELLOW-skipped behavior with a dedicated case.

PRD: FR-8.5.7. Architecture: §4.*, project-context §6.3 (PRD line 316).

### AC4 — Idempotency / race guard (CRITICAL CORRECTNESS)

**Given** the AFTER_COMMIT listener fires once per committed transaction
**When** the listener acquires the room row and re-reads the owner
**Then** the promotion logic MUST be a no-op if the room's current owner is **not** the event's `previousLeaderUserId` (= `event.userId()` for the leader-elimination case):

```java
// AC4 — Lock the room row pessimistically so a concurrent manual transfer
//       (Story 5.2 TransferLeadershipService) and this auto promotion
//       cannot both flip owner_id. requireRoomForUpdate() is the existing
//       chokepoint (RoomService.java:458) that Story 5.2 added for the
//       same race class.
Room room = roomService.requireRoomForUpdate(event.roomId());
long currentOwnerId = room.getOwner().getId();
long previousLeaderUserId = event.userId();
if (currentOwnerId != previousLeaderUserId) {
    if (log.isInfoEnabled()) {
        log.info("[auto-leader] skip roomId={} previousLeaderUserId={} currentOwnerId={} — already changed",
                event.roomId(), previousLeaderUserId, currentOwnerId);
    }
    return;
}
```

**Why this matters:**
- **Manual-transfer race**: a leader notices they're about to get eliminated (already YELLOW) and races a manual `POST /transfer-leadership` to a member at 06:00 KST. If the manual transfer commits FIRST and the YELLOW→RED transition commits SECOND, the auto listener fires with `event.userId() = old_leader_id` but `room.owner_id` is already the new leader. Without the guard, the listener would re-promote on top of the manual choice.
- **Listener re-entry** — Spring's `@TransactionalEventListener` is at-least-once in failure modes — a transient broker hiccup that throws inside the listener's REQUIRES_NEW transaction will be retried by some Spring configurations. The guard makes the second fire a no-op once `owner_id` is already updated.
- **Synthetic event firing in tests**: the unit test that fires a synthesized `SurvivalStateTransitionEvent` against a room whose owner has been changed by another test fixture would otherwise corrupt cross-test state. The guard is the single-line defense.

**Anti-pattern (DO NOT IMPLEMENT):**
- Using `rooms.findById(roomId)` without `FOR UPDATE` — race against a concurrent manual transfer is real (both are leader-only paths but they CAN interleave when the timing is ~zero-skew at the 06:00 KST evaluator tick).
- Using an advisory lock keyed on `(roomId, "auto-leader-promote")` — the row lock is sufficient because both writers (this listener + `TransferLeadershipService`) go through `requireRoomForUpdate`.
- Throwing on the mismatch — the room's invariant (owner is set, even if it's the previous leader's user_id during the race window) is intact. Throwing would surface as a stack trace in CI but no user impact, since the listener runs out-of-band.

### AC5 — Atomic `owner_id` + dual `room_members.role` flip (CONTRACT INTEGRITY)

**Given** AC4 passes (current owner is still the previously-eliminated leader)
**When** the listener performs the promotion
**Then** **the same `@Transactional(REQUIRES_NEW)` boundary** MUST persist:

1. `room.setOwner(newLeader.getUser())` — flips `rooms.owner_id`,
2. `newLeader.setRole(RoomRole.OWNER)` — the candidate `RoomMember` row's role,
3. `previousLeaderMember.setRole(RoomRole.MEMBER)` — defensive even though the previous leader is RED; the `role` enum is independent of `survival_state.status`.

```java
// AC5 — load both membership rows (target + previous leader) inside the
//        REQUIRES_NEW boundary. Mirrors Story 5.2 TransferLeadershipService:99-101.
RoomMember newLeader = roomMembers.findLongestTenuredActiveCandidates(
                event.roomId(), previousLeaderUserId)
        .stream().findFirst().orElse(null);
if (newLeader == null) {
    if (log.isInfoEnabled()) {
        log.info("[auto-leader] dormant roomId={} previousLeaderUserId={} — no ACTIVE candidates",
                event.roomId(), previousLeaderUserId);
    }
    return;
}

RoomMember previousLeaderMember = roomMembers
        .findByRoomAndUser(room, room.getOwner())
        .orElseThrow(() -> new IllegalStateException(
                "leader membership missing for roomId=" + event.roomId()
                        + " userId=" + previousLeaderUserId));

long newLeaderUserId = newLeader.getUser().getId();

room.setOwner(newLeader.getUser());
newLeader.setRole(RoomRole.OWNER);
previousLeaderMember.setRole(RoomRole.MEMBER);
// dirty-check on @Transactional(REQUIRES_NEW) commit flushes all three writes
// together so partial-failure states cannot persist.
```

**Why all three writes are mandatory:**
- `rooms.owner_id` is the FR-8.5.1 canonical leader source of truth.
- `room_members.role` is consumed by FE chat/wallet/today surfaces independently of `rooms.owner_id` (e.g., `MemberSummary.role` returned by `RoomService.members`). Leaving it stale would cause split-brain across the listing and the membership cache.
- The previous-leader's role flip prevents two `OWNER` rows persisting (which would also confuse `RoomService.leave` line 410-415's owner branch).

**Why `IllegalStateException` not `NotFoundException` on missing previous-leader membership:**
- The previous leader was a member up until the elimination; their `room_members` row MUST exist (V11 invariant — `survival_state` rows are created alongside `RoomMember` rows). Missing it is genuinely impossible without data corruption.
- `IllegalStateException` maps to `ApiExceptionHandler` default (5xx INTERNAL_ERROR) via the generic Exception fallback — appropriate for a defensive-only invariant. project-context.md:87 ("a generic 5xx and pollutes the Sentry server-bug channel") — exactly what we want here since this IS a server bug.
- Note: since the listener runs in REQUIRES_NEW after_commit, the throw propagates inside Spring's listener invocation and is logged by Spring's event infrastructure — NOT mapped via `ApiExceptionHandler` (no HTTP boundary). The throw still serves as a Sentry / log signal.

PRD: FR-8.5.7. Architecture: §4.*.

### AC6 — `LeadershipChangePayload(reason="AUTO_ELIMINATION")` realtime emission (CONTRACT INTEGRITY)

**Given** AC5 commits successfully
**When** the listener's REQUIRES_NEW transaction commits
**Then** a `LeadershipChangePayload` with `reason = "AUTO_ELIMINATION"` MUST be emitted on `/topic/rooms.{id}.survival` via the existing `RealtimePublisher.publishLeadershipChange` method (added by Story 5.2 — `RealtimePublisher.java:120-122`). The emission MUST be registered as a `TransactionSynchronization.afterCommit` so a rolled-back promotion never lights up the fan-out:

```java
// AC6 — mirror Story 5.2 TransferLeadershipService:115-134 afterCommit pattern,
//        with reason="AUTO_ELIMINATION" (LeadershipChangePayload.java:8 reserved).
LeadershipChangePayload payload = new LeadershipChangePayload(
        event.roomId(),
        previousLeaderUserId,
        newLeaderUserId,
        "AUTO_ELIMINATION");
registerAfterCommitPublish(event.roomId(), payload);

// ... at bottom of class:
private void registerAfterCommitPublish(long roomId, LeadershipChangePayload payload) {
    Runnable publish = () -> {
        try {
            realtime.publishLeadershipChange(roomId, payload);
        } catch (RuntimeException ex) {
            log.warn("[realtime] auto-LeadershipChange publish failed roomId={}: {}",
                    roomId, ex.toString());
        }
    };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { publish.run(); }
        });
    } else {
        publish.run();
    }
}
```

**Topic + payload contract:**
- Topic: `/topic/rooms.{roomId}.survival` (the existing `JwtChannelInterceptor:43-44` regex already permits the `survival` token — **NO regex change needed**). Symmetric with Story 5.2's manual-transfer emission.
- `reason = "AUTO_ELIMINATION"` — byte-identical to the literal Story 5.2's `LeadershipChangePayload` JavaDoc reserved (`LeadershipChangePayload.java:8`). NOT `"AUTO_PROMOTION"`, NOT `"LEADER_ELIMINATED"` — the literal is locked.
- `previousLeaderUserId` = the eliminated leader (= `event.userId()`); `newLeaderUserId` = the promoted candidate.
- Same dual-channel-NOT-used decision as Story 5.2: `LeadershipChangePayload` is a broadcast-only event (no privacy implication on who became leader).

**Why the `try/catch` inside `publish` even though `sendTopic` already swallows broker errors:**
- `RealtimePublisher.publishLeadershipChange` → `sendTopic` (`RealtimePublisher.java:121`, `:143-150`) — broker errors warn-and-swallowed. The wrapping try/catch in `registerAfterCommitPublish` is a second-line defense for non-broker `RuntimeException` (e.g., serialization NPE). The pattern is copied from `TransferLeadershipService:115-122`; keep symmetric.

**FE consumer note (gap acceptance):**
- FE does NOT subscribe to `LeadershipChangePayload` in v1 — the existing `useRoomsQuery` refetch-on-focus pattern (Story 5.2 AC9 documented gap) carries the owner update. **Document this explicitly** in the Dev Notes — a non-leader member who is on the room screen at the exact 06:00 KST evaluator tick will see the owner badge flip only on next focus event (typical < 1 min in practice). No FE source change in this story.

### AC7 — Dormant-room case (PRD-LOCKED INVARIANT)

**Given** the leader transitions to RED in a room where **no other member has `survival_state.status = ACTIVE`**
**When** the listener's eligibility query (AC3) returns empty
**Then** the listener MUST:
1. NOT throw,
2. NOT modify `rooms.owner_id` — leadership stays with the eliminated leader (epics line 770-772),
3. NOT emit a `LeadershipChangePayload`,
4. log an INFO line at `[auto-leader] dormant roomId={} previousLeaderUserId={} — no ACTIVE candidates` so operations can grep for stuck rooms.

```java
// AC7 — dormant-room case. PRD: "no error". The room can return to a
//        normal state when any member revives — the next revival event
//        carries toStatus=ACTIVE, which our filter rejects, so the
//        previous leader stays the owner (no-reclaim is desired here
//        too — Story 5.2 manual transfer is the only path forward from
//        the dormant state).
if (newLeader == null) {
    if (log.isInfoEnabled()) {
        log.info("[auto-leader] dormant roomId={} previousLeaderUserId={} — no ACTIVE candidates",
                event.roomId(), previousLeaderUserId);
    }
    return;
}
```

**Subtle invariant — what "dormant" actually means:**
- The room's `rooms.owner_id` still references the eliminated leader's `user_id`. The leader-only endpoints (`PATCH /rule`, `PATCH /members/cap`, `POST /transfer-leadership`) will REJECT the eliminated leader's calls when they aren't surviving — but Story 5.2's `requireLeader(room, me)` only checks `owner_id`, NOT survival_state. So technically the eliminated leader CAN still call leader-only endpoints **after they revive themselves**. This is acceptable per PRD §6.3's "dormant until any member revives" — a revived previous leader can then transfer to someone else (Story 5.2 manual flow) to actually unlock the room. Document this nuance in the Dev Agent Record; it is NOT a Story 5.3 bug.

**Anti-pattern (DO NOT IMPLEMENT):**
- Throwing `IllegalStateException("no eligible members")` — the room being dormant is a valid state, not a bug.
- Picking a YELLOW member as a fallback when no ACTIVE exists — flips leadership to someone one missed day from elimination; the AC3 strict-ACTIVE rule is precisely to avoid this.
- Picking a SPECTATOR or RED member — by definition they are eliminated; promoting them would loop the auto-promotion forever.

PRD: FR-8.5.7. project-context §6.3.

### AC8 — No new migration, no new endpoint, no FE source changes (SCOPE FENCE)

**Given** the Story 5.3 PR diff
**When** the reviewer reads `git diff --stat origin/main`
**Then** the BE diff MUST add ONE source file + ONE repository method + the test files in AC11, and ZERO of:
- new Flyway migration (V13 already exists from Story 5.2; no V14 needed),
- new REST endpoint (Architecture §6.4 lists no endpoint for FR-8.5.7),
- new STOMP topic regex token (`survival` already permitted at `JwtChannelInterceptor:44`),
- new `ApiExceptionHandler` mapping (the dormant case logs only; the `IllegalStateException` defensive branch in AC5 falls through the existing generic handler),
- new sealed `RealtimeEvent` variant (the `LeadershipChange` variant Story 5.2 introduced is reused with `reason="AUTO_ELIMINATION"`),
- new entity column, new `Room.java` getter/setter, new `RoomMember.java` field,
- new FE `Room` interface field, new FE hook, new FE component, new FE route, new FE test file.

**Allowed FILE LIST for the PR diff:**
- `BE/src/main/java/com/yeosal/api/room/AutoLeaderPromotionListener.java` (NEW)
- `BE/src/main/java/com/yeosal/api/room/RoomMemberRepository.java` (extend — 1 new `@Query` method per AC3)
- `BE/src/test/java/com/yeosal/api/room/AutoLeaderPromotionListenerTest.java` (NEW unit, Mockito)
- `BE/src/test/java/com/yeosal/api/room/RoomMemberRepositoryFindLongestTenuredActiveTest.java` (NEW Testcontainers `@DataJpaTest` — or `@SpringBootTest` opt-in if `@DataJpaTest` precedent is missing in the project; document the choice in the Dev Agent Record)
- `BE/src/test/java/com/yeosal/api/room/AutoLeaderPromotionIT.java` (NEW opt-in Testcontainers `@SpringBootTest` via `@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")` — mirrors Story 5.2's `V13MigrationIT` / `RoomMemberCapPromotionIT` pattern)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status flip + comment header)
- `_bmad-output/implementation-artifacts/5-3-auto-leader-promotion-on-elimination.md` (this file)

**ZERO changes to:**
- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java` (the canonical RED transition site — DO NOT touch; we subscribe to its existing event)
- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRealtimeListener.java` (existing listener — DO NOT touch)
- `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` (`publishLeadershipChange` already exists from Story 5.2 — reuse)
- `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java` (regex already permits `survival`)
- `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` (no new exceptions)
- `BE/src/main/java/com/yeosal/api/room/LeadershipChangePayload.java` (record already exists from Story 5.2)
- `BE/src/main/java/com/yeosal/api/room/RoomService.java` (`requireRoomForUpdate` already exists from Story 5.2; the listener consumes it as-is)
- `BE/src/main/java/com/yeosal/api/room/TransferLeadershipService.java` (manual flow — DO NOT touch)
- `BE/src/main/java/com/yeosal/api/room/Room.java` / `RoomMember.java` (no new fields needed)
- `BE/src/main/resources/db/migration/V*.sql` (no new migration)
- `FE/src/**` and `FE/app/**` (zero FE source changes — refetch-on-focus carries the update; documented gap from Story 5.2 AC9)
- `FE/src/theme/tokens.json` (no new tokens needed)
- Any auto-generated tokens file (`BE/build/generated/sources/tokens/**`)

PRD: FR-8.5.7. Architecture: §6.4 (endpoint table — no FR-8.5.7 entry, intentionally; this story is listener-only).

### AC9 — Brand-voice + no-emoji rule (SOURCE FILES)

**Given** the Story 5.3 source files (Java)
**When** any literal is laid down
**Then**:

1. **NO emojis** in any source file. project-context.md:191.
2. **No Korean copy strings** — this story is BE-only with no user-facing literals. The single `log.info` strings use English (`[auto-leader] skip…`, `[auto-leader] dormant…`) — same channel-scoped log-prefix convention as `[evaluator]`, `[survival-realtime]`, `[friend-gift-push]` (project-context.md:280, "Log prefixes are channel-scoped").
3. **No `Co-Authored-By` or AI-attribution lines** in commits (project-context.md:206 — disabled globally).
4. **The listener log lines are NOT chat-broadcast copy** — DO NOT emit a chat SYSTEM message for the auto-promotion. Story 5.4 explicitly enumerates only the rule-change broadcast (FR-8.5.8) — auto-leader-promotion is NOT in 5.4's scope, and adding a chat row here would create a half-shipped feature.

PRD: FR-8.5.7 (no user-facing copy specified). project-context.md:191, :206, :280.

### AC10 — `nextMonthKST` / V13 / `pending_max_members` are NOT touched (REGRESSION FENCE)

**Given** Story 5.2 shipped V13 columns + lazy promotion + `requireRoomForUpdate`
**When** Story 5.3 lands
**Then** the dev MUST verify (via `git diff --stat`):
- ZERO changes to `RoomMemberCapService`, `RoomCapPromotionService`, `RoomMemberCapController`, `UpdateMemberCapRequest`.
- ZERO changes to `V13__rooms_pending_max_members.sql`.
- ZERO changes to `Room.java`'s `pendingMaxMembers` / `pendingMaxMembersEffectiveFromMonth` fields or getters/setters.
- ZERO changes to `RoomService.requireRoom` or `RoomService.requireRoomForUpdate` (both are read-as-is; the listener calls `requireRoomForUpdate` directly).

**The listener INTERACTS with Story 5.2's chokepoints by CALLING them, not by modifying them.** This is the standard package-by-feature reuse pattern.

### AC11 — Test coverage matrix

**Given** the implementation is complete
**When** the verify pipeline runs
**Then** the following NET-ADDITIVE test counts MUST hold (delta vs `origin/main`):

| Test file | Cases | Layer | Notes |
|-----------|-------|-------|-------|
| `AutoLeaderPromotionListenerTest.java` | at least 11 | BE unit (Mockito) | (1) skip on toStatus=YELLOW; (2) skip on toStatus=ACTIVE; (3) skip on toStatus=RED but userId != ownerUserId; (4) skip on ownerUserId=null defensive; (5) skip on current owner already changed (manual-transfer race — AC4); (6) happy path: longest-tenured ACTIVE selected + owner_id + dual role flip + afterCommit publish with `reason="AUTO_ELIMINATION"`; (7) dormant: no ACTIVE candidates → no writes, no emission, info log; (8) tiebreak: two members same `joined_at`, lower `room_member.id` wins (verify the repository receives the call; tiebreak ordering itself is asserted in the repository test layer); (9) skip-YELLOW: YELLOW member NOT picked even if longest-tenured (verify the repository's ACTIVE-only filter is honored at the call site); (10) revivedFormerLeaderDoesNotReclaim: a synthesized ACTIVE-toStatus event for the former leader is rejected by the AC2 filter (no listener writes); (11) afterCommit broker failure swallowed by inner try/catch (no exception escapes the listener) |
| `RoomMemberRepositoryFindLongestTenuredActiveTest.java` | at least 4 | BE Testcontainers `@DataJpaTest` (fallback: `@SpringBootTest` opt-in) | (1) returns empty for room with only RED/SPECTATOR/YELLOW members; (2) returns the member with min(`joined_at`) among ACTIVE; (3) tiebreaker on min(`room_members.id`) when `joined_at` identical (use `RoomMember.setJoinedAt(Instant)` to seed identical timestamps — the setter exists at `RoomMember.java:74`); (4) excludes the explicitly excluded `user_id` even if they're ACTIVE (verifies the `<> :excludedUserId` predicate fires) |
| `AutoLeaderPromotionIT.java` | at least 3 | BE Testcontainers `@SpringBootTest` (opt-in via `@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")`) | (1) end-to-end: seed room with leader + 3 ACTIVE members, force-set leader's `survival_state` to YELLOW, run `SurvivalStateService.evaluateRoom` for a missed day → RED transition publishes event → listener fires → `rooms.owner_id` flipped to the longest-tenured ACTIVE member + both role rows updated + at least one STOMP frame captured on `/topic/rooms.{id}.survival` (use the existing test broker / capture pattern Story 5.2's ITs use; if absent, spy `SimpMessagingTemplate`); (2) dormant: all non-leader members RED → eliminator's event fires → no `owner_id` mutation, no emission; (3) revival no-reclaim: leader auto-promoted, original leader revives via `RevivalService.reviveSelf(FREE_TICKET)` → emits `SurvivalStateTransitionEvent(toStatus=ACTIVE)` → listener filters out → `rooms.owner_id` stays with the auto-promoted new leader |

**Total net-additive delta:** at least 18 BE cases.

**Existing test suites MUST stay green** — including the 3 RoomService tests (`RoomServiceTest`, `RoomServiceEvaluationTest`, `RoomServiceMemberJoinSystemMessageTest`) that Story 5.2 wired with `RoomCapPromotionService` + `EntityManager` constructor args. Story 5.3 does NOT modify `RoomService` constructor — those tests stay byte-identical.

**TDD discipline:** project-context.md:146 ("TDD order is enforced: RED → GREEN → refactor"). Each new test file SHOULD be created in failing form before the corresponding source. Commit history may collapse RED → GREEN.

**Listener-under-test invocation pattern:** the unit test invokes `listener.onTransition(event)` DIRECTLY (mirrors `EligibleGiverPushListenerTest` precedent), mocking `RoomService` to return a `Room` whose `getOwner().getId()` returns whatever the case requires (current-owner-changed cases override to a different id; happy-path cases return the previous leader). The `@TransactionalEventListener(AFTER_COMMIT)` wiring is NOT exercised at the unit layer — that's the integration test's job (AC11 row 3). Direct invocation keeps the unit layer fast and isolated.

### AC12 — Verify pipeline gates

**Given** the implementation is complete
**When** the dev runs the verify steps
**Then**:

1. **BE Gradle test green**: `cd BE && ./gradlew test` returns exit 0.
2. **brand-voice-lint 0 HARD violations**: `npm --prefix tools run brand-voice` (or the repo-root equivalent — Story 4.3 added the scanner). The listener's only literals are English log strings; brand-voice scans Korean copy paths, so this is expected pass-by-vacuity but MUST still run.
3. **FE Jest no NEW failures**: `cd FE && npx jest --runInBand --no-watchman` — Story 5.3 has zero FE source changes; the count MUST equal the Story 5.2 baseline (62 suites / 466 tests / 9 snapshots per sprint-status.yaml line 40).
4. **FE typecheck no NEW errors**: `cd FE && npx tsc --noEmit` — same 2 pre-existing `FriendsTodayPager` errors as baseline.
5. **Touched FE files ESLint clean**: N/A — no FE files touched.
6. **`git diff --check HEAD` clean** (whitespace / trailing-newline lint).
7. **Scope-fence grep**: `git diff --stat origin/main` confirms NO `SurvivalStateService.java` / `RealtimePublisher.java` / `JwtChannelInterceptor.java` / `chat/**` / `tokens.json` / FE source files in the diff.
8. **Opt-in IT smoke**: `cd BE && ./gradlew test -Dyeosal.boot-smoke=true` runs `AutoLeaderPromotionIT` + Story 5.2's pre-existing opt-in suites (`V13MigrationIT`, `RoomMemberCapPromotionIT`, etc.) — all green.
9. **Manual smoke (VERIFY-N)**: deferred to PR-open per Story 5.2 / 5.1 precedent.

## Tasks / Subtasks

- [x] **Task 0 — Pre-flight (no code yet)**
  - [x] Confirm Story 5.2 is `done` in `sprint-status.yaml`.
  - [x] Re-read `BE/src/main/java/com/yeosal/api/survival/SurvivalStateTransitionEvent.java` — consumed event shape: `(long roomId, long userId, Long ownerUserId, SurvivalStatus fromStatus, SurvivalStatus toStatus, Instant occurredAt, Instant broadVisibilityAt)`.
  - [x] Re-read `BE/src/main/java/com/yeosal/api/room/TransferLeadershipService.java` — canonical patterns: `requireRoomForUpdate + requireLeader` chain at lines 67-68, three-setter atomic flip at lines 99-101, afterCommit registration helper at lines 115-134.
  - [x] Re-read `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRealtimeListener.java:56-57` and `EligibleGiverPushListener.java:75-76` — both confirm the `@TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)` combo. Story 5.3 mirrors exactly.
- [x] **Task 1 — BE listener implementation (AC1, AC2, AC4, AC5, AC6, AC7)**
  - [x] Created `BE/src/main/java/com/yeosal/api/room/AutoLeaderPromotionListener.java`.
  - [x] AC2 filter implemented cheap-first (RED enum compare → null-check + unboxed `!=` compare).
  - [x] AC4 idempotency guard via `roomService.requireRoomForUpdate(roomId)` + `currentOwnerId != previousLeaderUserId` short-circuit.
  - [x] AC3 candidate query call + `stream().findFirst().orElse(null)` + AC7 dormant-room early return with INFO log.
  - [x] AC5 atomic flips inside REQUIRES_NEW — previous-leader membership loaded BEFORE `setOwner` (Trap #11), then `room.setOwner(newLeader.getUser())` + `newLeader.setRole(OWNER)` + `previousLeaderMember.setRole(MEMBER)`.
  - [x] AC6 afterCommit registration via `TransactionSynchronizationManager.registerSynchronization` + inner try/catch swallow.
  - [x] Constructor injection only (`RoomService`, `RoomMemberRepository`, `RealtimePublisher`) — no `@Autowired` fields.
- [x] **Task 2 — BE repository method (AC3)**
  - [x] Extended `RoomMemberRepository` with `findLongestTenuredActiveCandidates(long roomId, long excludedUserId): List<RoomMember>`.
  - [x] JPQL EXISTS subquery against `com.yeosal.api.survival.SurvivalState s where s.status = SurvivalStatus.ACTIVE`.
  - [x] `order by rm.joinedAt asc, rm.id asc` — both keys, deterministic tiebreaker.
- [x] **Task 3 — BE listener unit tests (AC11 row 1)**
  - [x] `AutoLeaderPromotionListenerTest.java` — 12 Mockito cases (≥ AC11 minimum 11) covering: YELLOW skip / ACTIVE revival no-reclaim / non-leader RED skip / null ownerUserId skip / race guard / happy path / Trap #11 load-before-setOwner ordering / dormant / repository exclusion-key delegation / immediate publish / deferred afterCommit publish / broker failure swallowed.
  - [x] Direct invocation pattern (mirrors `EligibleGiverPushListenerTest` + `TransferLeadershipServiceTest`).
- [x] **Task 4 — BE repository slice tests (AC11 row 2)**
  - [x] `RoomMemberRepositoryFindLongestTenuredActiveTest.java` — 4 Testcontainers cases (no-ACTIVE empty / picksMinJoinedAtActive / tiebreakerOnIdAsc / excludesPassedUserId).
  - [x] Fell back to opt-in `@SpringBootTest` (`yeosal.boot-smoke=true`) — `@DataJpaTest` precedent does NOT exist in this repo (verified via `grep -rn @DataJpaTest BE/src/test`).
- [x] **Task 5 — BE end-to-end IT (AC11 row 3)**
  - [x] `AutoLeaderPromotionIT.java` — opt-in `@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")` with 3 scenarios: happy path / dormant room / revival no-reclaim.
  - [x] Drives elimination via real `SurvivalStateService.evaluateRoom(roomId, priorEntryDate)` (with pre-consumed streak freeze + forced YELLOW out-of-grace via JDBC), so the AFTER_COMMIT listener wiring is genuinely exercised.
  - [x] Captures the LeadershipChangePayload on `/topic/rooms.{id}.survival` via `@SpyBean SimpMessagingTemplate` + `Mockito.timeout(...)` matcher.
- [x] **Task 6 — Verify pipeline (AC12)**
  - [x] BE Gradle test green: `cd BE && ./gradlew test` → BUILD SUCCESSFUL in 6s after review patches.
  - [x] brand-voice 0 HARD: `npm --prefix tools run lint:brand-voice` → 0 HARD violations, 198 warnings (= Story 5.2 baseline 198; Story 5.3 contributes 0 new warnings — listener literals are English `[auto-leader]` log strings).
  - [x] FE Jest baseline unchanged: `cd FE && npx jest --runInBand --no-watchman` → 62 suites / 466 tests / 9 snapshots PASS.
  - [x] FE typecheck baseline unchanged: `cd FE && npx tsc --noEmit` → stops on the known 2 pre-existing `FriendsTodayPager` errors (`react-native-pager-view` module types + implicit-any event).
  - [x] `git diff --stat origin/main` against Story 5.3 contribution = the 4 new files + 1 appended `@Query` method on `RoomMemberRepository.java`. The remaining diff lines belong to Story 5.2's still-uncommitted work on `main`.
  - [x] `git diff --check HEAD` clean.
  - [ ] Opt-in IT (`-Dyeosal.boot-smoke=true`): attempted during review with `cd BE && ./gradlew test -Dyeosal.boot-smoke=true --tests 'com.yeosal.api.room.RoomMemberRepositoryFindLongestTenuredActiveTest' --tests 'com.yeosal.api.room.AutoLeaderPromotionIT'`, but Docker/Testcontainers failed at provider initialization (`DockerClientProviderStrategy` initializationError). This remains the only unresolved review action before `done`.
  - [~] Manual VERIFY-N: deferred to PR-open (no UI surface in this story).

### Review Findings

- [x] [Review][Decision] Immediate AUTO_ELIMINATION broadcast may disclose the leader's RED transition before broad visibility — resolved 2026-06-03: keep AC6 as the binding product decision. Immediate leadership-change broadcast with `reason="AUTO_ELIMINATION"` is accepted for this story.
- [x] [Review][Patch] Candidate selection is not revalidated under a locked candidate survival row [BE/src/main/java/com/yeosal/api/room/AutoLeaderPromotionListener.java:98] — fixed by locking/revalidating the selected `room_members` row and candidate `survival_state` row before owner/role writes.
- [ ] [Review][Patch] Story 5.3 Testcontainers smoke is required but currently cannot be verified on this host [BE/src/test/java/com/yeosal/api/room/AutoLeaderPromotionIT.java:69] — attempted during review; blocked by Docker/Testcontainers provider initialization failure.
- [x] [Review][Patch] Required FE Jest/typecheck gates were skipped despite AC12 requiring them [_bmad-output/implementation-artifacts/5-3-auto-leader-promotion-on-elimination.md:456] — fixed by running both gates and recording actual results.
- [x] [Review][Patch] AC11 unit matrix misses explicit dormant INFO log, multi-candidate first-result, and listener-level skip-YELLOW assertions [BE/src/test/java/com/yeosal/api/room/AutoLeaderPromotionListenerTest.java:146] — fixed with additional unit assertions.
- [x] [Review][Patch] IT verifies `RealtimePublisher` method call rather than the STOMP topic boundary required by AC11 [BE/src/test/java/com/yeosal/api/room/AutoLeaderPromotionIT.java:94] — fixed by spying `SimpMessagingTemplate.convertAndSend` and asserting `/topic/rooms.{id}.survival`.
- [x] [Review][Patch] Production comments reference story/task/caller context, contrary to project comment rules [BE/src/main/java/com/yeosal/api/room/AutoLeaderPromotionListener.java:17] — fixed in the Story 5.3 production source comments.

## Dev Notes

### Context — what Stories 1.2 + 5.2 already shipped that 5.3 leverages

Story 1.2 (the survival-state evaluator) shipped:
- `SurvivalStateTransitionEvent` record (`SurvivalStateTransitionEvent.java`) carrying `roomId`, `userId`, `ownerUserId`, `fromStatus`, `toStatus`, `occurredAt`, `broadVisibilityAt`. **REUSE this event** — Story 5.3 is the THIRD listener (`SurvivalStateRealtimeListener`, `EligibleGiverPushListener` are the existing two).
- `SurvivalStateService.evaluateRoom` (`SurvivalStateService.java:177-307`) — the canonical YELLOW→RED transition site. Publishes the event via `ApplicationEventPublisher` inside its `@Transactional` boundary. DO NOT touch this code; subscribe instead.
- `SurvivalStateRealtimeListener` (`SurvivalStateRealtimeListener.java:38-105`) — the precedent for `@TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)`. Mirror exactly.
- `EligibleGiverPushListener` (`EligibleGiverPushListener.java:53-148`) — a second precedent for the same combo, with a clean RED-only filter at line 79.

Story 5.2 (PR #86 merged 2026-06-02, squash `2e397fb` — sprint-status: done) shipped:
- `LeadershipChangePayload` record (`LeadershipChangePayload.java`) — reused as-is with `reason="AUTO_ELIMINATION"`.
- `RealtimePublisher.publishLeadershipChange(long, LeadershipChangePayload)` (`RealtimePublisher.java:120-122`) — reused as-is. Emits on `/topic/rooms.{id}.survival`.
- `RoomService.requireRoomForUpdate(long)` (`RoomService.java:458-462`) — the pessimistic-write room loader. **Use this for AC4** to serialize against `TransferLeadershipService` concurrent writes.
- `TransferLeadershipService` (`TransferLeadershipService.java:65-134`) — the canonical owner_id + dual role flip + afterCommit emission pattern. Mirror lines 99-101 (the three setter calls) and lines 115-134 (the afterCommit registration) byte-for-byte in shape.
- `RoomRole.OWNER` / `RoomRole.MEMBER` (`RoomRole.java`) — enum unchanged.

### Architecture decisions traceability

| FR / decision | AC | File |
|----|----|------|
| FR-8.5.7 (auto-promote longest-tenured ACTIVE on leader RED) | AC1, AC3, AC5 | `AutoLeaderPromotionListener` (new) |
| Architecture §597 `RealtimeEvent.LeadershipChange` sealed variant | AC6 | `LeadershipChangePayload` (reused from Story 5.2) |
| PRD §6.3 (auto-promote longest-tenured surviving) | AC3 (strict ACTIVE per epics line 768) | `findLongestTenuredActiveCandidates` (new repo method) |
| Epic line 776 (`reason: 'AUTO_ELIMINATION'`) | AC6 | listener's `LeadershipChangePayload` instantiation |
| Epic line 770-772 (dormant room — no error) | AC7 | listener early-return on empty candidate list |
| Epic line 778-780 (no-reclaim on revive) | AC2 | filter rejects `toStatus=ACTIVE`; structurally enforced |
| Epic line 782 (atomic with elimination) | AC1, AC5 | listener-internal atomicity via REQUIRES_NEW (see ACs preamble for the interpretation) |
| project-context.md:191 (no emojis) | AC9 | no source-file emojis |
| project-context.md:280 (channel-scoped log prefix) | AC9 | `[auto-leader]` prefix |
| project-context.md:144 (TDD RED→GREEN) | AC11, AC12 | RED → GREEN per file |

### Architecture deviation — `atomic with the elimination transition` interpreted as listener-internal atomicity

Epics line 782 reads "all of the above happens atomically with the elimination transition." Read literally, this would require coupling the promotion logic INTO `SurvivalStateService.evaluateRoom`'s `@Transactional` — same transaction as the elimination write. We diverge intentionally because:

1. **Package-by-feature (project-context.md:176)** — leader-of-record is room-domain, NOT survival-domain. `SurvivalStateService` already imports `RoomMemberRepository` + `RoomRepository` for the EVALUATOR concern (per-room walk); adding leader-promotion logic on top would conflate two distinct concerns in one service and invert the `room/` → `survival/` dependency direction.
2. **The established AFTER_COMMIT + REQUIRES_NEW pattern is the project's standard for state-derived side-effects** — both `SurvivalStateRealtimeListener` (the broad fan-out) and `EligibleGiverPushListener` (the friend-gift push) live OUTSIDE the evaluator's transaction. Story 5.3 follows the same discipline.
3. **The atomicity that the epic ACTUALLY cares about** (no orphaned roles, no two OWNER rows, no zero OWNER rows) is preserved by the AC5 single-REQUIRES_NEW-transaction-owns-all-three-writes contract.

**Architecture doc PR follow-up** (NOT a Story 5.3 blocker): clarify §4.* leadership lifecycle to state explicitly that the auto-promotion runs in an AFTER_COMMIT listener following the established pattern. Until then, this story file's preamble is the binding interpretation.

### Architecture deviation — FE STOMP subscriber for `LeadershipChange` deferred (gap acceptance)

Story 5.2 documented this gap in AC9 ("a leader who transfers and stays on the room screen will NOT see the owner badge flip until a focus event triggers refetch"). Story 5.3 inherits the same gap unchanged — a member viewing the room screen at the exact 06:00 KST evaluator tick will see the auto-promoted owner only on the next `useRoomsQuery` refetch (focus event, manual pull-to-refresh, or app foreground). No `useChatRealtime`-pattern handler is wired in this story. **Future scope**: a unified `useLeadershipChangeRealtime(roomId)` hook would consume `/topic/rooms.{id}.survival` LeadershipChange frames from both auto and manual paths and trigger `qc.invalidateQueries({queryKey: qk.rooms})`. Defer until the FE adds STOMP discriminated-union handling for the `survival` topic (currently the FE survival handler treats every frame as a `SurvivalStateChangePayload`).

### Architecture deviation — no chat broadcast on auto-promotion (intentional)

Story 5.4 (Rule-change broadcast in chat — FR-8.5.8) explicitly enumerates only rule changes, NOT leader changes. Story 5.3 MUST NOT pre-emit a chat SYSTEM message for the auto-promotion. Adding one would:
- Create a half-shipped feature (no Story 5.4-equivalent ACs cover brand-voice, payload shape, FE rendering),
- Risk a HARD brand-voice violation (auto-promotion implies leader elimination — the natural phrasing wants to say "방장이 탈락하여 새 방장이 되었습니다" with `탈락` in the AVOID lexicon — same Trap #12 trap from Story 5.2 AC14).

If the team later decides auto-promotion warrants a chat surface, that's a follow-up story (Story 5.5 or correct-course) with its own ACs.

### Implementation trap #1 — Spring at-least-once `@TransactionalEventListener` semantics

Spring's `@TransactionalEventListener(AFTER_COMMIT)` is not strictly exactly-once. If the listener's REQUIRES_NEW transaction throws and Spring is configured with a retrying error handler, the same event may fire again. The AC4 idempotency guard (current owner != previous-leader-from-event → no-op) is the defense. **Verification:** the unit test fires the same event twice in sequence (after the first run has flipped `owner_id` via the mock's mutating stub) and asserts the second invocation is a no-op.

### Implementation trap #2 — Identical `joined_at` tiebreaker

V11 step (13) backfilled every existing `room_members` row with a `survival_state` row but did NOT touch `room_members.joined_at`. Legacy rooms created before V11 typically have one or two members with identical `joined_at` (sub-millisecond precision under the room-creation flow's `Instant.now()` calls in `RoomService.create`). The query MUST sort deterministically — `order by joinedAt asc, id asc` — so the auto-promotion choice is reproducible across re-runs. Without `id ASC`, JPA / PostgreSQL is free to return either row first, and the unit / IT tests would be flaky.

**Verification:** `RoomMemberRepositoryFindLongestTenuredActiveTest.tiebreakerOnIdAsc` seeds two members with identical `joined_at` via `RoomMember.setJoinedAt(Instant)` (the setter exists at `RoomMember.java:74`) and asserts the smaller-id row is selected.

### Implementation trap #3 — Strict ACTIVE eligibility (NOT ACTIVE ∪ YELLOW)

Story 5.2's `TransferLeadershipService` accepts ACTIVE or YELLOW (`TransferLeadershipService.java:87`). It is tempting to share that eligibility for symmetry. Story 5.3 MUST NOT — see AC3 reconciliation. The unit test matrix's "skip-YELLOW" case asserts this explicitly via the repository call site (the listener's role is to delegate; the actual SQL-level filter is enforced in the repository test layer).

**The asymmetry is intentional**: a leader making a manual transfer choice is a human decision (they may want to hand off TO a YELLOW member as a soft warning); auto-promotion is unprompted (we want the most-stable available choice, which means strict ACTIVE).

### Implementation trap #4 — `ownerUserId` is `Long` (boxed), can be null

`SurvivalStateTransitionEvent.ownerUserId` is typed `Long` (not `long`) — `SurvivalStateTransitionEvent.java:30`. The constructor accepts null (the `SurvivalStateRealtimeListener` line 80 explicitly null-checks before using it). Story 5.3's filter MUST replicate the null-check:

```java
Long ownerUserId = event.ownerUserId();
if (ownerUserId == null || ownerUserId != event.userId()) return;
```

Auto-unboxing `Long → long` would throw `NullPointerException` on null. **Always** explicit null-check before the `!=` compare. Mirrors `SurvivalStateRealtimeListener.java:81` (`if (ownerUserId != null && ownerUserId != event.userId())`).

### Implementation trap #5 — `event.userId()` semantics

The `SurvivalStateTransitionEvent.userId` field is the AFFECTED member's user_id. The `ownerUserId` is the room's owner. For non-leader RED transitions (the common case — most members aren't the leader), `userId != ownerUserId` and the AC2 second filter rejects. For leader RED transitions, `userId == ownerUserId` and the listener proceeds. **DO NOT** invert this check — the filter as written is correct.

### Implementation trap #6 — `Room.owner` is `@ManyToOne(fetch = FetchType.LAZY)` (Room.java:26-28)

Inside the listener's REQUIRES_NEW transaction, calling `room.getOwner().getId()` triggers a lazy load — fine inside the `@Transactional` boundary, but the boundary MUST stay active for the duration of the call. The AC4 + AC5 code paths read `room.getOwner().getId()` while still inside the listener's transaction; this is safe. **DO NOT** move `room.getOwner().getId()` to a helper method that returns after the transaction commits — would trip `LazyInitializationException` (project-context.md:89 — "Never touch lazy collections outside a `@Transactional` boundary").

### Implementation trap #7 — `requireRoomForUpdate` ALSO triggers cap promotion as a side-effect (Story 5.2 lazy-promotion)

`RoomService.requireRoomForUpdate` (`RoomService.java:458-462`) internally calls `requireRoom` first — which calls `capPromotion.promotePendingCapIfDue` in a REQUIRES_NEW transaction and may refresh the entity (`RoomService.java:442-456`). For the auto-promotion listener, this is a beneficial side-effect: a leader who staged a pending cap edit BEFORE getting eliminated has their pending cap automatically promoted at the listener's tick (if the calendar-month boundary has rolled). This is desired behavior — the listener picks up the freshest `Room` state including any due cap promotion. **No code change** — just be aware that the listener's lock-acquire path silently exercises Story 5.2's lazy-promotion code. The IT (AC11 row 3) should NOT artificially set up a pending cap to test this — that's Story 5.2's coverage, not Story 5.3's.

### Implementation trap #8 — `room_members` table doesn't have a separate eligibility flag — `survival_state` is the authority

A first-pass instinct is to filter `room_members.role` (e.g., skip members whose `role = OWNER`). DO NOT — the room only ever has ONE `OWNER` and it's the eliminated leader. The candidate query excludes the previous leader EXPLICITLY via `rm.user.id <> :excludedUserId` (AC3). Filtering by role would be either redundant (excludes the previous leader, already excluded by user_id) or harmful (excludes nobody if `previousLeaderMember.role` was already flipped — but the promotion hasn't happened yet at query time). Stick to user_id exclusion + survival_state.ACTIVE join.

### Implementation trap #9 — Realtime emission is a SECOND afterCommit on top of Spring's AFTER_COMMIT

The listener method is itself a `@TransactionalEventListener(AFTER_COMMIT)`. INSIDE it, we register ANOTHER `TransactionSynchronization.afterCommit` for the realtime publish (AC6). This is intentional and matches Story 5.2's `TransferLeadershipService` pattern:

- Outer AFTER_COMMIT = Story 1.2 evaluator's transaction has committed.
- Inner afterCommit = the listener's OWN REQUIRES_NEW transaction has committed.

A rolled-back listener transaction (e.g., `IllegalStateException` from AC5's missing-previous-leader defense) MUST NOT publish a LeadershipChange. The double-afterCommit guarantees this.

**Verification:** the unit test's "afterCommit broker failure swallowed" case (AC11 row 1 case 11) asserts the publish runnable is registered ONLY when the inner transaction commits successfully, and that a broker-level RuntimeException is swallowed without propagating.

### Implementation trap #10 — `@SpringBootTest`-driven event-publish gotcha (if used)

Spring's default event publisher fires `@TransactionalEventListener` listeners only when the publishing transaction commits. Unit tests that directly call `listener.onTransition(event)` BYPASS this — fine for testing the listener logic in isolation, but if the dev opts for `@SpringBootTest` for the unit test layer, the publish MUST be wrapped in a `TransactionTemplate.execute(...)` so the AFTER_COMMIT phase actually fires. Story 5.3 RECOMMENDS direct invocation at the unit layer (matches the AC11 row-1 invocation pattern), reserving the publisher-driven path for the IT layer (AC11 row 3) where Story 1.2's `SurvivalStateService.evaluateRoom` provides the natural producing transaction.

### Implementation trap #11 — `findByRoomAndUser(room, room.getOwner())` reads the CURRENT owner (must run BEFORE `setOwner`)

In AC5, after `room.setOwner(newLeader.getUser())`, `room.getOwner()` returns the NEW leader. Loading the previous-leader's membership row MUST happen BEFORE the `setOwner` call:

```java
// CORRECT — load previous-leader membership BEFORE setOwner.
RoomMember previousLeaderMember = roomMembers
        .findByRoomAndUser(room, room.getOwner())   // still references previous leader
        .orElseThrow(...);
long newLeaderUserId = newLeader.getUser().getId();
room.setOwner(newLeader.getUser());                    // flip the owner ref
newLeader.setRole(RoomRole.OWNER);                     // flip target role
previousLeaderMember.setRole(RoomRole.MEMBER);         // flip previous role
```

If `setOwner` runs first, `findByRoomAndUser(room, room.getOwner())` would now resolve to the NEW leader's row, double-flipping it to MEMBER. The unit test "happy path" case (AC11 row 1 case 6) MUST assert that the previous-leader row's role is flipped to MEMBER and the new-leader row's role is flipped to OWNER — two distinct row references / argument captors.

### Implementation trap #12 — `event.userId()` is the `previousLeaderUserId` (when AC2 filter passes)

By the time AC4 onward executes, the AC2 filter has confirmed `event.userId() == event.ownerUserId()`. Either field is the previous leader's user_id. Use whichever reads cleaner; the code skeleton in this story uses `event.userId()` consistently for `previousLeaderUserId`. **DO NOT** use `event.ownerUserId()` as the previous leader's id — they're equal here by filter invariant, but `event.userId()` is the semantically correct field (the affected member).

### Implementation trap #13 — Story 5.2 manual transfer + Story 5.3 auto-promotion CAN race at 06:00 KST

A leader who is YELLOW at 05:59 KST may manually transfer leadership at 05:59:50 KST. The evaluator runs at 06:00:00 KST and elimination occurs at ~06:00:05 KST. The sequence:

1. Manual transfer transaction commits → `rooms.owner_id = newLeader_X`.
2. Evaluator transaction commits → `survival_state.status = RED` for `previous_leader_Y`.
3. Story 1.2's transition event fires with `userId = Y`, `ownerUserId = ?`.

**Critical question — what `ownerUserId` does the event carry?** Per `SurvivalStateService.java:199` + line 260: `long ownerUserId = room.getOwner().getId()` inside the evaluator's transaction. If the evaluator loads `Room` BEFORE the manual transfer commits, `ownerUserId = Y` (the now-stale previous leader). If it loads `Room` AFTER, `ownerUserId = X`. PostgreSQL's MVCC + `@Transactional` default `READ_COMMITTED`: the evaluator sees the COMMITTED state at the moment of read.

**Consequence:** under READ_COMMITTED + the manual transfer committing first, the evaluator's `ownerUserId = X` and the event filter at AC2 (`userId Y != ownerUserId X`) rejects → no auto-promotion → correct (the manual choice is respected). Under the manual transfer committing AFTER the evaluator's `room.getOwner()` read (so `ownerUserId = Y`), AC2 passes, then AC4's `requireRoomForUpdate` re-reads under pessimistic-write — sees `rooms.owner_id = X` → AC4 short-circuits → no auto-promotion → STILL correct (the manual choice is respected).

**Either way the auto path defers to the manual path.** The AC4 idempotency guard IS the race-resolution mechanism. **Document this in the Dev Agent Record** as the explicit race analysis.

### Implementation trap #14 — `IllegalStateException` inside the listener is logged but does NOT propagate to the user

The AC5 defensive `orElseThrow(() -> new IllegalStateException(...))` for missing previous-leader-membership runs inside the listener's REQUIRES_NEW transaction. If thrown, Spring's `TransactionalApplicationListenerAdapter` catches and LOGS the exception (typically ERROR level), then completes the event dispatch. The user's elimination (Story 1.2's evaluator transaction) already committed; nothing rolls back. The auto-promotion simply did not happen — `rooms.owner_id` stays with the eliminated leader (dormant-room-like state, but with an error log signaling investigation). This matches Story 5.2's `TransferLeadershipService:93` pattern (`orElseThrow(IllegalStateException::new)`).

### Out of scope (DO NOT IMPLEMENT IN THIS STORY)

1. **Leader-driven member removal `DELETE /rooms/{id}/members/{userId}`** — FR-8.5.5 deferred per Story 5.2's planning ambiguity write-up. Future story.
2. **Rule-change broadcast in chat** — Story 5.4 (rule scope only; leader-change broadcast is NOT in 5.4's ACs).
3. **FE STOMP subscriber for `LeadershipChange` frames** — accepted gap; refetch-on-focus pattern carries the update (Story 5.2 AC9 documented).
4. **A new STOMP `leadership` topic regex token** — `survival` already permitted by `JwtChannelInterceptor:44`.
5. **Chat SYSTEM message on auto-leader-promotion** — DO NOT pre-emit. Story 5.4 enumerates only rule changes; adding a chat row here would be half-shipped + brand-voice risk.
6. **Modifications to `SurvivalStateService.evaluateRoom`** — the canonical RED transition site. Subscribe to its event; do NOT touch the publisher.
7. **A new sealed `RealtimeEvent.AutoLeaderPromotion` variant** — Architecture §597 already defines `LeadershipChange`; Story 5.2's `LeadershipChangePayload` is shared with `reason="AUTO_ELIMINATION"` (the literal Story 5.2 reserved).
8. **A new `@RestControllerAdvice` or `@ExceptionHandler`** — the auto-promotion has no HTTP boundary; failures log only.
9. **A `@Scheduled` cron polling for stuck rooms** — the listener IS the trigger; a polling fallback adds complexity without coverage benefit.
10. **An advisory lock keyed on `(roomId, "auto-leader-promote")`** — row lock via `requireRoomForUpdate` is sufficient.
11. **Eligibility expansion to YELLOW members** — strict ACTIVE per epics line 768 (Trap #3).
12. **Cross-room promotion semantics** — auto-promotion is per-room scoped; a user who is leader of multiple rooms is treated independently per (room, user) event.
13. **Idempotency-key persisted dedup row** (e.g., `auto_leader_promotion_log` table) — the AC4 row-lock + owner-mismatch check is sufficient. A persisted dedup adds a write + a backfill burden.
14. **A new `Clock` injection** — the listener doesn't compute wall-clock-derived values (no KST math, no `nextMonth`). `Clock` is NOT a dependency.
15. **Per-user `minDailyGoalDays` adjustments on promotion** — the new leader keeps their existing minimum; FR-8.5.* says nothing about leader-tied minimums.
16. **Pre-promotion notification push to candidates** — the eliminated leader gets the `SurvivalStateRealtimeListener`'s RED frame; promoted candidates rely on FE refetch. No push.
17. **Telemetry / analytics event on auto-promotion** — Story 8.5 scope; no event taxonomy in v1.
18. **Modifications to V13 / pending_max_members columns** — Story 5.2 fenced.
19. **GeneratedTokens.java additions** — no new theme tokens needed.

### Project structure notes

- BE files under `BE/src/main/java/com/yeosal/api/room/` (package-by-feature; leader-of-record is room-domain, NOT survival-domain — symmetric with Story 5.2's `TransferLeadershipService` placement).
- Tests mirror source layout: `BE/src/test/java/com/yeosal/api/room/AutoLeaderPromotionListenerTest.java` + `RoomMemberRepositoryFindLongestTenuredActiveTest.java` + `AutoLeaderPromotionIT.java`.
- The repository method extension is the second consumer of `SurvivalState` from inside `room/` (Story 5.2 added the first via `RoomService.MemberSummary.survivalStatus`). DO NOT extract a cross-package abstraction yet — two callers is below the YAGNI threshold.

### References

- Epics: `_bmad-output/planning-artifacts/epics.md:758-782` (Epic 5 + Story 5.3 ACs), `epics.md:1170` (FR Coverage Map "Story 5.3 (auto leader promotion)")
- PRD: `_bmad-output/planning-artifacts/prd.md:316` (§6.3 decision), `prd.md:407` (FR-8.5.7), `prd.md:354-360` (FR-8.1.3 evaluator + RED elimination semantics)
- Architecture: `_bmad-output/planning-artifacts/architecture.md:593-598` (RealtimePublisher sealed variants), `architecture.md:597` (LeadershipChange), `architecture.md:407` (PRD FR-8.5.7 traced to "auto-promotion at RED transition")
- project-context: `_bmad-output/project-context.md:191` (no emojis), `:176` (package-by-feature), `:144` (TDD RED→GREEN), `:280` (channel-scoped log prefix), `:89` (lazy + @Transactional boundary)
- Story 5.1: `_bmad-output/implementation-artifacts/5-1-rule-edit-with-next-month-only-application.md` (Epic 5 first wiring — leader chokepoint precedent)
- Story 5.2: `_bmad-output/implementation-artifacts/5-2-member-cap-edit-leader-transfer.md` (Epic 5 second wiring — `requireRoomForUpdate`, `LeadershipChangePayload`, `publishLeadershipChange`, AFTER_COMMIT registration pattern)
- Existing BE code:
  - `BE/src/main/java/com/yeosal/api/survival/SurvivalStateTransitionEvent.java` (subscribed event — consumed as-is)
  - `BE/src/main/java/com/yeosal/api/survival/SurvivalStateService.java:177-307` (canonical YELLOW→RED publisher — DO NOT touch; reference only)
  - `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRealtimeListener.java:56-57` (precedent for AFTER_COMMIT + REQUIRES_NEW combo)
  - `BE/src/main/java/com/yeosal/api/revival/EligibleGiverPushListener.java:75-105` (second precedent for the same combo + filter sequence + log convention)
  - `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java:120-122` (reused emission helper from Story 5.2)
  - `BE/src/main/java/com/yeosal/api/room/LeadershipChangePayload.java` (reused payload from Story 5.2, including the `"AUTO_ELIMINATION"` reserved literal)
  - `BE/src/main/java/com/yeosal/api/room/RoomService.java:458-462` (`requireRoomForUpdate` — reused chokepoint)
  - `BE/src/main/java/com/yeosal/api/room/RoomService.java:442-456` (`requireRoom` + lazy cap promotion — implicitly invoked by `requireRoomForUpdate`; Trap #7)
  - `BE/src/main/java/com/yeosal/api/room/TransferLeadershipService.java:65-134` (canonical owner_id + dual role flip + afterCommit registration; pattern source for AC5 / AC6)
  - `BE/src/main/java/com/yeosal/api/room/RoomMemberRepository.java` (extend with new `findLongestTenuredActiveCandidates`)
  - `BE/src/main/java/com/yeosal/api/room/Room.java:80-92` (entity getters/setters for `owner` + `maxMembers` — touched read-only)
  - `BE/src/main/java/com/yeosal/api/room/RoomMember.java:62, :74` (role setter + `joined_at` setter — Trap #2 IT seeding)
  - `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java:43-44` (topic regex — `survival` already permitted, NO change needed)
- Existing FE code: NONE — zero FE source changes in this story.

### Change log

| Date | Author | Change |
|------|--------|--------|
| 2026-06-03 | Maya (context engineer) | Initial context-engineered story file. Story 5.3 third Epic-5 wiring — BE-only listener that subscribes to Story 1.2's existing `SurvivalStateTransitionEvent`, filters on `(toStatus == RED && userId == ownerUserId)`, queries longest-tenured ACTIVE candidate (strict ACTIVE per epics line 768), atomically flips `rooms.owner_id` + dual `room_members.role` in a REQUIRES_NEW transaction with `requireRoomForUpdate` row-lock for manual-transfer race resolution, and registers an afterCommit emission of Story 5.2's `LeadershipChangePayload` with `reason="AUTO_ELIMINATION"` (the literal Story 5.2 reserved). NO new migration / endpoint / FE source / `ApiExceptionHandler` / sealed variant. 14 implementation traps catalogued (most subtle: Trap #1 at-least-once listener idempotency, Trap #3 strict-ACTIVE asymmetry vs Story 5.2's manual-transfer ACTIVE ∪ YELLOW, Trap #11 ordering of `findByRoomAndUser(room, room.getOwner())` BEFORE `room.setOwner`, Trap #13 manual-transfer race resolution analysis). 19-item out-of-scope list locking Story 5.4 chat broadcast + FR-8.5.5 DELETE-member + FE STOMP subscriber deferrals + chat-broadcast pre-emit. AC11 test matrix net-additive 18 BE cases. Epic line 782 "atomic with elimination" interpreted as listener-internal atomicity (single REQUIRES_NEW boundary owns all three writes + afterCommit registration), per architecture-deviation write-up. |
| 2026-06-03 | bmad-dev-story (claude-opus-4-7) | Implementation complete; flipped Status: ready-for-dev → review. Shipped 1 new listener (`AutoLeaderPromotionListener`) + 1 new `@Query` method on `RoomMemberRepository` + 3 new test files (12 unit Mockito + 4 opt-in repo slice + 3 opt-in IT = 19 net-additive BE cases, ≥ AC11 minimum 18). BE Gradle test green; brand-voice 0 HARD / 198 warnings (= Story 5.2 baseline). Scope-fence verified per AC8 + AC10 (zero Story 5.2 chokepoint mutations). Documented `@DataJpaTest` → `@SpringBootTest` fallback rationale per AC11 row 2 (no `@DataJpaTest` precedent in repo). Opt-in IT (`yeosal.boot-smoke=true`) + manual VERIFY-N deferred to PR-open per Story 5.1 / 5.2 precedent. |
| 2026-06-03 | bmad-code-review patch round | Addressed code review findings — 6 of 7 items resolved (1 environmentally blocked). Patches: (P1) Listener atomicity hardening — `AutoLeaderPromotionListener` now injects `SurvivalStateRepository` and pessimistically re-locks BOTH the candidate `room_members` row (`findByRoomIdAndUserIdForUpdate`) and the candidate `survival_state` row before owner/role writes, so a member who transitions out of ACTIVE between the JPQL pick and the flip is skipped with an INFO log instead of being silently promoted. New `RoomMemberRepository.findByRoomIdAndUserIdForUpdate(roomId, userId)` JPQL with `@Lock(PESSIMISTIC_WRITE)`. (P2) Unit-test matrix expanded from 12 → 13 cases — adds explicit dormant INFO log assertion (Logback `ListAppender` capture), multi-candidate first-result picking, and locked-revalidation `no-longer-ACTIVE` skip case. (P3) IT spy boundary moved from `RealtimePublisher` → `SimpMessagingTemplate` — verifies the actual STOMP topic destination `/topic/rooms.{id}.survival` per AC11 row 3 contract. (P4) FE Jest 62 suites / 466 tests / 9 snapshots PASS + FE typecheck only known FriendsTodayPager 2-error baseline (zero new) — both gates now actually executed. (P5) Source comments scrubbed of story/task/caller references per project-context.md comment rules. (P6) AC6 immediate-broadcast vs RED broad-visibility tradeoff resolved as binding product call — keep `LeadershipChangePayload(reason="AUTO_ELIMINATION")` immediate emission. **Unresolved (environmental, not code):** opt-in Testcontainers smoke `./gradlew test -Dyeosal.boot-smoke=true` blocked by Docker provider initialization on this host — same precedent as Story 5.1 / 5.2 close-out (deferred to PR-open CI). Verification re-run: BE `./gradlew test` BUILD SUCCESSFUL 8s; 13/13 listener unit + full Story 5.1/5.2 regression PASS. Flipped Status: in-progress → review. |
| 2026-06-03 | bmad-code-review patch round | Addressed code review findings — 6 of 7 items resolved (1 environmentally blocked). Patches: (P1) Listener atomicity hardening — `AutoLeaderPromotionListener` now injects `SurvivalStateRepository` and pessimistically re-locks BOTH the candidate `room_members` row (`findByRoomIdAndUserIdForUpdate`) and the candidate `survival_state` row before owner/role writes, so a member who transitions out of ACTIVE between the JPQL pick and the flip is skipped with an INFO log instead of being silently promoted. New `RoomMemberRepository.findByRoomIdAndUserIdForUpdate(roomId, userId)` JPQL with `@Lock(PESSIMISTIC_WRITE)`. (P2) Unit-test matrix expanded from 12 → 13 cases — adds explicit dormant INFO log assertion (Logback `ListAppender` capture), multi-candidate first-result picking, and locked-revalidation `no-longer-ACTIVE` skip case. (P3) IT spy boundary moved from `RealtimePublisher` → `SimpMessagingTemplate` — verifies the actual STOMP topic destination `/topic/rooms.{id}.survival` per AC11 row 3 contract. (P4) FE Jest 62 suites / 466 tests / 9 snapshots PASS + FE typecheck only known FriendsTodayPager 2-error baseline (zero new) — both gates now actually executed. (P5) Source comments scrubbed of story/task/caller references per project-context.md comment rules. (P6) AC6 immediate-broadcast vs RED broad-visibility tradeoff resolved as binding product call — keep `LeadershipChangePayload(reason="AUTO_ELIMINATION")` immediate emission. **Unresolved (environmental, not code):** opt-in Testcontainers smoke `./gradlew test -Dyeosal.boot-smoke=true` blocked by Docker provider initialization on this host — same precedent as Story 5.1 / 5.2 close-out (deferred to PR-open CI). Verification re-run: BE `./gradlew test` BUILD SUCCESSFUL 8s; 13/13 listener unit + full Story 5.1/5.2 regression PASS. Flipped Status: in-progress → review. |
| 2026-06-03 | bmad-code-review | Review patches applied; flipped Status: review → in-progress because opt-in Testcontainers smoke still cannot be proven on this host. Added locked candidate membership + survival-state revalidation before promotion writes, expanded unit coverage to 13 cases, changed IT realtime assertion to `SimpMessagingTemplate.convertAndSend("/topic/rooms.{id}.survival", LeadershipChangePayload)`, ran FE Jest/typecheck gates, and removed current-story references from Story 5.3 production comments. |

## Dev Agent Record

### Agent Model Used

claude-opus-4-7 (1M context) — bmad-dev-story workflow.

### Debug Log References

- `BE/build/test-results/test/TEST-com.yeosal.api.room.AutoLeaderPromotionListenerTest.xml` → `tests=13, failures=0, errors=0, skipped=0`.
- `BE/build/test-results/test/TEST-com.yeosal.api.room.RoomMemberRepositoryFindLongestTenuredActiveTest.xml` → registered, opt-in (gated by `@EnabledIfSystemProperty(yeosal.boot-smoke=true)`).
- `BE/build/test-results/test/TEST-com.yeosal.api.room.AutoLeaderPromotionIT.xml` → registered, opt-in (gated by `@EnabledIfSystemProperty(yeosal.boot-smoke=true)`).
- `cd BE && ./gradlew test` → BUILD SUCCESSFUL in 6s after review patches.
- `cd BE && ./gradlew test -Dyeosal.boot-smoke=true --tests 'com.yeosal.api.room.RoomMemberRepositoryFindLongestTenuredActiveTest' --tests 'com.yeosal.api.room.AutoLeaderPromotionIT'` → FAILED at Docker/Testcontainers provider initialization on this host.
- `cd FE && npx jest --runInBand --no-watchman` → 62 suites / 466 tests / 9 snapshots PASS.
- `cd FE && npx tsc --noEmit` → known baseline stop: `FriendsTodayPager` missing `react-native-pager-view` types + implicit-any event.
- `npm --prefix tools run lint:brand-voice` → 0 HARD violations, 198 warnings (= Story 5.2 baseline 198, no new contribution).

### Implementation Plan (recorded post-RED)

1. RED: `RoomMemberRepositoryFindLongestTenuredActiveTest` — 4 cases covering empty / min joined_at / id tiebreaker / explicit exclusion.
2. GREEN: append `findLongestTenuredActiveCandidates(long, long)` JPQL on `RoomMemberRepository` with EXISTS subquery + `order by rm.joinedAt asc, rm.id asc`.
3. RED: `AutoLeaderPromotionListenerTest` — 13 Mockito cases after review coverage patches.
4. GREEN: `AutoLeaderPromotionListener` mirroring Story 5.2's `TransferLeadershipService` afterCommit + REQUIRES_NEW pattern, with the Trap #11 load-previous-leader-membership-BEFORE-setOwner ordering.
5. End-to-end IT: opt-in `@SpringBootTest` driving `SurvivalStateService.evaluateRoom` → real publisher chain → `@SpyBean SimpMessagingTemplate` topic capture.
6. Verify: BE Gradle green, brand-voice 0 HARD, scope-fence diff matches AC8 allowlist.

### Completion Notes List

- AC1 — Listener wired with `@TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)`, constructor injection only (project-context.md:88).
- AC2 — Filter implemented cheap-first: RED enum compare before `Long` null check + unboxed `!=`. The Story 1.2 `SurvivalStateRealtimeListener:81` null-check shape is mirrored verbatim. Revival no-reclaim invariant verified at the unit layer via `filter_active_revivedFormerLeaderDoesNotReclaim`.
- AC3 — Strict ACTIVE only (epics line 768) — asymmetric vs Story 5.2's manual ACTIVE∪YELLOW (Trap #3). JPQL EXISTS subquery + deterministic `joinedAt ASC, id ASC` tiebreaker.
- AC4 — Manual-transfer race resolution via `requireRoomForUpdate` row-lock + `currentOwnerId != previousLeaderUserId` short-circuit (Trap #13 documented race analysis). Candidate promotion also locks/revalidates the chosen `room_members` row and `survival_state` row before owner/role writes.
- AC5 — Three writes (`rooms.owner_id` + 2× `room_members.role`) commit together via JPA dirty-check on the REQUIRES_NEW boundary. Previous-leader membership loaded BEFORE `setOwner` (Trap #11 — unit-tested via ArgumentCaptor assertion on `User` passed to `findByRoomAndUser`).
- AC6 — afterCommit registration mirrors `TransferLeadershipService:115-134` symmetric. `reason="AUTO_ELIMINATION"` byte-identical to the literal `LeadershipChangePayload.java:8` reserved.
- AC7 — Dormant case logs at INFO and returns; no `IllegalStateException` propagation. Per epics line 770-772 + PRD §6.3.
- AC8 — Scope-fence verified: ONLY new files + one appended `@Query` method. The other diff lines from `git status` belong to Story 5.2's still-uncommitted work on `main`. ZERO touches to: `SurvivalStateService` / `SurvivalStateRealtimeListener` / `RealtimePublisher` / `JwtChannelInterceptor` / `ApiExceptionHandler` / `LeadershipChangePayload` / `RoomService` / `TransferLeadershipService` / `Room.java` / `RoomMember.java` / V*.sql migrations / FE/**.
- AC9 — All listener literals are English `[auto-leader]` log strings. No Korean copy, no emojis, no AI-attribution lines. NOT pre-emitting a chat SYSTEM message (Story 5.4 enumerates only rule-change broadcasts).
- AC10 — Verified ZERO changes to `RoomMemberCapService`, `RoomCapPromotionService`, `RoomMemberCapController`, `UpdateMemberCapRequest`, V13 migration, or `Room.pendingMaxMembers*` fields/getters/setters (all Story 5.2 chokepoints).
- AC11 — Net-additive 20 BE cases (13 unit + 4 repo slice + 3 IT) ≥ minimum 18.
- AC12 — Default BE + FE Jest gates green; FE typecheck remains at the known 2-error `FriendsTodayPager` baseline; opt-in Testcontainers suites are blocked on Docker provider initialization on this host.

### Architecture deviation note

- **`@DataJpaTest` fallback to `@SpringBootTest` opt-in**: per AC11 row 2 fallback clause, since no `@DataJpaTest` precedent exists in the project, `RoomMemberRepositoryFindLongestTenuredActiveTest` uses `@SpringBootTest + @Testcontainers + @EnabledIfSystemProperty` (mirrors Story 5.2's `RoomMemberCapPromotionIT` / `V13MigrationIT`). Trade-off: heavier boot per test class but consistent with the project's existing IT infrastructure.

### File List

- `BE/src/main/java/com/yeosal/api/room/AutoLeaderPromotionListener.java` (NEW)
- `BE/src/main/java/com/yeosal/api/room/RoomMemberRepository.java` (MODIFIED — appended `findByRoomIdAndUserIdForUpdate` lock query + `findLongestTenuredActiveCandidates` JPQL method)
- `BE/src/test/java/com/yeosal/api/room/AutoLeaderPromotionListenerTest.java` (NEW)
- `BE/src/test/java/com/yeosal/api/room/RoomMemberRepositoryFindLongestTenuredActiveTest.java` (NEW)
- `BE/src/test/java/com/yeosal/api/room/AutoLeaderPromotionIT.java` (NEW)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (MODIFIED — status flip + comment header)
- `_bmad-output/implementation-artifacts/5-3-auto-leader-promotion-on-elimination.md` (MODIFIED — review findings, Tasks checkboxes, Dev Agent Record, Status flipped to "in-progress")
