# Story 3.3: Wallet "친구 살리기" badge — passive discoverability

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **a room member who may have notifications disabled, missed the eligible-giver push, or simply opens the Wallet surface and wants a backup discoverability channel for friend-revival**,
I want **a small "친구 회생 대기 (N)" badge to light up on the Wallet surface (initially the existing `WalletPreview` block; later the Story 3.4 full Wallet UI) when at least one room-mate friend has `survival_state.status ∈ {RED, SPECTATOR}` AND I have ≥5 personal points — tapping it opens the same `FriendGiftModal` (with an in-modal friend-picker when N > 1) and the resulting revival writes `revival_events.source_subtype = 'WALLET_INITIATED'`**,
so that **friend-revival has a passive, no-pressure backup surface that distinguishes itself from the push-driven path in telemetry (Architecture §4.8 falsification — wallet-initiated > push-initiated at Day 30 reopens PRD §13.4 "v1.5 Wallet 친구 살리기 surface" hypothesis with concrete data) without ever pressuring the giver**.

PRD authority: **FR-8.3.6** (Wallet UI shows free ticket / personal points / room pool / 받은 회생권 history — Story 3.3 owns the badge slice; Story 3.4 owns the full Wallet UI surface), **FR-8.3.3** (the badge tap path reuses the `POST /api/v1/rooms/{id}/revivals/gifts` contract Story 3.2 shipped — exactly-one 5-point gift-revival), **FR-8.3.7** (rejection / non-action invisible — the badge MUST NOT count or surface "N friends were eligible to gift you" to anyone but the giver themselves), **FR-8.8.2** (brand-voice copy lock — "친구 살리기" tone, never "친구 구하기" / "친구 부담"). Architecture authority: **§4.8** (push-only v1 + passive Wallet badge — Architecture-locked decision; `revival_events.source_subtype` discriminator `PUSH_INITIATED` vs `WALLET_INITIATED`).

PRD secondary refs: **FR-8.8.6** (release-gate brand-voice review), **NFR-9.2.5** (spectator-write enforcement applies — SPECTATOR givers cannot tap the badge).

## Acceptance Criteria

Numbering matches the BDD blocks in `_bmad-output/planning-artifacts/epics.md` lines 511–535 (Story 3.3 stanza). Each AC carries its source ref inline so the dev agent has a precise audit trail to the original requirement.

1. **AC1 — Eligible-target query: badge data source.**
   - **Given** I am an authenticated user (the would-be giver) with at least one room membership where my own `survival_state.status` is one of `{ACTIVE, YELLOW}` AND my running personal-points balance in that room is **≥ 5**,
   - **When** the FE calls `GET /api/v1/me/friend-gift-targets` (NEW),
   - **Then** the BE returns `200 OK` + `ApiResponse<List<FriendGiftTargetSummaryDto>>` where each entry represents one room I'm an active (non-SPECTATOR) member of with at least one eligible friend-gift target:
     ```
     FriendGiftTargetSummaryDto {
       long   roomId;
       String roomName;
       int    eligibleCount;              // number of eligible friends in THIS room
       List<EligibleFriendDto> friends;   // 1..N entries; picker uses this when N > 1
     }
     EligibleFriendDto {
       long   userId;
       String nickname;
       String status;                     // "RED" | "SPECTATOR" — for picker UX hint
       Instant eliminatedAt;              // ISO-8601 UTC — partial-unique-index key arg
     }
     ```
   - **Eligibility SQL** (native query, lives in NEW class `FriendGiftTargetQuery` at `com.yeosal.api.revival`):
     ```sql
     select
         sst.room_id,
         r.name           as room_name,
         target_user.id   as target_user_id,
         target_user.nickname as target_nickname,
         sst.status       as target_status,
         sst.eliminated_at as target_eliminated_at
     from survival_state sst
     join rooms r on r.id = sst.room_id
     join room_members rm_giver on rm_giver.room_id = sst.room_id
         and rm_giver.user_id = :giverUserId
     join survival_state sst_giver on sst_giver.room_id = sst.room_id
         and sst_giver.user_id = :giverUserId
     join users target_user on target_user.id = sst.user_id
     join friendships f on (
         (f.user_id = :giverUserId and f.friend_user_id = sst.user_id)
         or (f.friend_user_id = :giverUserId and f.user_id = sst.user_id)
     )
     where sst.status in ('RED','SPECTATOR')
       and sst.user_id <> :giverUserId
       and sst_giver.status in ('ACTIVE','YELLOW')
       and f.status = 'ACCEPTED'
     order by sst.room_id, sst.eliminated_at desc
     ```
   - **Why a join on `survival_state sst_giver`** — the giver's own status is **NOT** a precondition the SQL can skip: per FR-8.3.7 + NFR-9.2.5 spectators cannot tap the badge, and the FE should not show a badge to a spectator-everywhere user (`useIsSpectatorEverywhere()` already wraps `Today` with `<SubModeProvider subMode="quiet">`). The BE is the authoritative gate; the FE is a defence-in-depth surface.
   - **Balance precondition** is **NOT** SQL-side — the giver's balance lives in `personal_points_ledger` (append-only ledger; sum aggregation). The BE returns the full eligible list; the FE filters out rooms where `meSurvival[roomId].personalPoints < 5`. This split (BE returns eligibility; FE applies balance) keeps the BE query cheap (no SUM aggregate per room) and lets the FE re-evaluate eligibility on local cache changes without re-fetching.
   - **Empty list** — if no eligible targets across any room, the endpoint returns `200 OK` with `data: []`. The FE renders no badges. **Never** 404.
   - **Privacy invariant (AC9 echo from Story 3.2)** — this endpoint MUST be scoped to `me` (the caller). The endpoint MUST NOT be reachable for `userId != me`. There is no admin / cross-user view. A `?userId=` query parameter MUST NOT be added.
   - **Caching policy** — `staleTime: 30_000` matches `qk.meSurvival`. Invalidated when `qk.meSurvival` is invalidated (a friend's RED/SPECTATOR transition flips eligibility).
   - [Epics 519-523; Architecture §4.8; FR-8.3.6; mirrors Story 3.2 `FriendGiftEligibilityQuery` (inverse direction — Story 3.2 returns eligible *givers* for a receiver; Story 3.3 returns eligible *targets* for a giver)]

2. **AC2 — Tap badge → FriendGiftModal opens with picker when N > 1.**
   - **Given** Wallet renders the "친구 회생 대기 (N)" badge for room `R` with `eligibleCount = 1`,
   - **When** I tap the badge,
   - **Then** `FriendGiftModal` mounts with `roomId = R`, `receiverUserId = friends[0].userId`, `receiverNickname = friends[0].nickname`, AND a NEW prop `sourceSubtype = "WALLET_INITIATED"` (extending the existing `FriendGiftModalProps` from Story 3.2). Modal behaviour from Story 3.2 (3-CTA, lifetime-1 M3.5, error toast branching) is preserved verbatim — only the `sourceSubtype` plumbing is new.
   - **Given** the badge for room `R` shows `eligibleCount > 1`,
   - **When** I tap the badge,
   - **Then** a NEW `FriendGiftPickerSheet` mounts FIRST (NOT the modal). The sheet renders an avatar+nickname row per eligible friend with a single "선택" CTA per row. Tapping a row dismisses the sheet AND opens `FriendGiftModal` with that friend's id/nickname/sourceSubtype="WALLET_INITIATED". Cancel ("닫기") dismisses the sheet without opening the modal.
   - **Wire shape** — picker mounts as a `<Modal animationType="slide" transparent>` (mirror FriendGiftModal's mount pattern) over the Wallet surface; entry uses the existing `friends` array from the badge's data source — no second BE roundtrip.
   - **Reduced-motion** — picker uses `animationType="fade"` when `AccessibilityInfo.isReduceMotionEnabled()` returns true (mirrors Story 3.2 RevivalSequence reduced-motion pattern).
   - **Touch target** — each row ≥ 48dp tall (project-context FE rule).
   - [Epics 525-527; Story 3.2 `FriendGiftModal.tsx` lines 47-53 props shape; `AccessibilityInfo.isReduceMotionEnabled` precedent from RevivalSequence]

3. **AC3 — Revival writes `source_subtype = 'WALLET_INITIATED'`.**
   - **Given** I tap the badge (or pick a friend via the picker) and the resulting `POST /api/v1/rooms/{R}/revivals/gifts` succeeds,
   - **When** the BE writes the `revival_events` row,
   - **Then** the row has `source_subtype = 'WALLET_INITIATED'` (NOT the existing default `'PUSH_INITIATED'`).
   - **Wire change** — `FriendGiftRequest` (currently `{targetUserId}`) extends to `{targetUserId, sourceSubtype?: "PUSH_INITIATED" | "WALLET_INITIATED"}`. Default to `"PUSH_INITIATED"` when absent (backward compat — Story 3.2 callers don't break). The BE's `@PostMapping` reads `body.sourceSubtype()` (Java optional via record-component default-to-null) and forwards it to `RevivalService.reviveFriend(roomId, giver, targetUserId, sourceSubtype)`.
   - **Service signature change** — `RevivalService.reviveFriend(...)` (Story 3.2) currently has `sourceSubtype = "PUSH_INITIATED"` hardcoded at the `insertOnConflictDoNothing` call site (`RevivalService.java` line ~370). Refactor: add a 4th parameter `String sourceSubtype` defaulting to `"PUSH_INITIATED"` when null. Story 3.2's existing callers (none — the controller is the only caller) pass through the request body value.
   - **Validation** — `sourceSubtype` MUST be one of `{"PUSH_INITIATED", "WALLET_INITIATED"}`. Any other value → `400 VALIDATION` code `INVALID_SOURCE_SUBTYPE`. Use a NEW enum `RevivalSourceSubtype { PUSH_INITIATED, WALLET_INITIATED }` mapped via `@Enumerated(EnumType.STRING)`. The wire type is the enum's name; the FE sends the string verbatim.
   - **Why an enum, not a free-form string** — the V11 `source_subtype varchar(20)` column already exists but has no CHECK constraint (Architecture §6.3 V11 line 50 leaves it open-ended for forward compat). Enforcing the enum at the Java boundary keeps wire/persistence aligned without a V13 migration. If future stories add a third subtype (e.g., `'DIGEST_INITIATED'` for spectator-digest tap-through), the enum adds one value; the CHECK constraint can be added in a follow-up migration if telemetry demands it.
   - [Epics 528-530; Architecture §4.8; V11 step 5 line 50; Story 3.2 `RevivalService.reviveFriend` line ~370]

4. **AC4 — Day-30 telemetry hypothesis hook.**
   - **Given** Story 8.5 (Analytics SDK + event taxonomy) has shipped at W1 and a telemetry event helper `track(event, props)` exists,
   - **When** the user successfully sends a friend-gift via the badge tap path,
   - **Then** the FE emits a `revival.friend_gift.sent` event with props `{ roomId, sourceSubtype: "WALLET_INITIATED", receiverUserId, isFirstEverFriendGiftSend, eligibleCountAtBadgeRender }`. The same event MUST be emitted by Story 3.2's push-deep-link path with `sourceSubtype: "PUSH_INITIATED"` — Story 3.3 patches that emission too if Story 3.2 hasn't already shipped it (verify via grep before adding to avoid duplicate emits).
   - **If Story 8.5 has NOT shipped yet** (sprint timing — Story 3.3 lands W3/W4, Story 8.5 lands W7 per epics sprint table, BUT epics line 1183 places 8.5 in W1 — verify before assuming), the dev agent MUST:
     - Add a TODO comment at each emit site pointing to Story 8.5: `// Story 8.5 — wire when analytics SDK lands; for now no-op.`
     - **Do NOT** invent a placeholder `track(...)` function. Project-context FE rule "Don't add error handling, fallbacks, or validation for scenarios that can't happen. Trust internal code and framework guarantees" — placeholder analytics is a fallback for an absent dependency.
     - Add the prop set to the AC4 docstring on the emit site so the dev who wires Story 8.5 has the contract in place.
   - **Why this AC matters** — Architecture §4.8 explicitly lists the falsification test: "if Wallet-badge-driven friend-gifts > push-driven friend-gifts at Day 30, we under-trusted the push." Without the `sourceSubtype` discriminator landing in telemetry, that test is unrunnable. AC3 puts the discriminator in `revival_events`; AC4 puts it in the analytics stream so PM can query it without a DB pull.
   - [Epics 531-533; Architecture §4.8 falsification clause; epics line 1183 + 1186 (W1 Story 8.5 placement)]

5. **AC5 — Brand-voice copy + accessibility lock.**
   - **Given** the badge renders on any Wallet surface,
   - **When** the brand-voice lint runs (Story 1.5 / Architecture §4.15 / FR-8.8.6 release-gate),
   - **Then** zero HARD violations of the 8-word AVOID lexicon (벌금/잃었다/떨어졌다/실패/자책/부담/패배/죄책감) are reported against the Story 3.3 strings. Locked Korean strings introduced by this story:
     - `"친구 회생 대기 ({N})"` — badge label, N is the eligible count, parenthesized digit
     - `"친구 살리기"` — picker sheet title (NEVER `"친구 구하기"` per epics line 535)
     - `"잔액 부족 (5점 필요)"` — when the user has friends eligible but balance < 5; the badge shows a muted variant + this caption underneath (reuse Story 3.2 FriendGiftModal `disabledTooltip`)
     - `"선택"` — per-row CTA in the picker
     - `"닫기"` — picker cancel CTA (reuse Story 3.2 modal tertiary CTA wording)
     - `"내 친구 중 회생 대기 중인 멤버"` — picker sheet subhead
   - **Accessibility**:
     - Badge MUST have `accessibilityRole="button"` AND `accessibilityLabel={`친구 회생 대기 ${eligibleCount}명, 탭하면 회생권 선물 화면이 열려요`}` (full descriptive label per project-context FE rule).
     - Disabled variant (`balance < 5`) MUST have `accessibilityState={{ disabled: true }}` AND `accessibilityHint="포인트가 5점 이상일 때 사용할 수 있어요"`.
     - Picker sheet MUST have `accessibilityViewIsModal` AND focus trap (RN's `<Modal>` provides this on iOS; Android needs `<View accessibilityLiveRegion="polite">` on first focusable element — mirror Story 3.2 FriendGiftModal pattern).
     - **No bare emoji-only badge** — Story 2.1 AC8 + project-context "Use semantic HTML" — the badge text MUST include the Korean label, not just a colored dot.
   - **Visual treatment** — D2.bento sub-mode (Architecture §4.16 / UX 1081-1090 token block — `color.bg.elevated`, `radius.default: 12`, `space.layout.padding: 16`, `elevation.1: subtle blur`, `typography.heading.weight: 700`). The badge is a small Bento Surface with the count as the primary metric (oxblood accent on the digit, muted ink on the label). Pure RED is BANNED on spectator surfaces (UX A11 v2 guard) — the badge MUST use the ember/oxblood palette per D2 conventions, never the SurvivalChip RED token.
   - **Reduced-motion** — the count update animation (when N changes 1 → 2) MUST be < 200ms and use opacity-only transitions (compositor-friendly per project-context web/performance rule "Animate compositor-friendly properties only"). When `AccessibilityInfo.isReduceMotionEnabled()` is true, the count updates instantly (no animation).
   - [Epics 535; FR-8.8.2; UX line 1081-1090 D2.bento override tokens; project-context FE rule "Touch target ≥ 48dp"; UX A11 v2 RED-on-spectator guard]

6. **AC6 — Surface integration: which Wallet surfaces mount the badge.**
   - **Given** Story 3.4 (full Wallet UI) ships in parallel,
   - **When** the FE compositor decides which surfaces show the badge,
   - **Then** the badge MUST mount in BOTH of these surfaces (and ONLY these):
     1. The existing `WalletPreview` block on `app/(tabs)/today.tsx` (Story 2.1 AC7 surface — spectator-only, mounted when `isSpectatorEverywhere`). The badge appears as a stacked row UNDER the existing `🌿  개인 포인트 N점` / `💚  그룹 포인트 N` lines but ABOVE the `<SelfReviveCTA>` row. **Important** — `WalletPreview` is currently spectator-only. The Story 3.3 badge requirement (`survival_state.status ∈ {RED, SPECTATOR}` for the *friend*; the *giver* must be `{ACTIVE, YELLOW}` for the SQL-side gate) means a spectator-everywhere user **cannot** initiate friend-gift (NFR-9.2.5). So the badge mounted inside `WalletPreview` will reliably show `eligibleCount = 0` and render nothing for spectator users — which is the correct outcome. The badge component MUST simply not render when `eligibleCount === 0` (early return null).
     2. The new full Wallet surface from Story 3.4 — placed inside the "그룹 포인트" / room point pool section, immediately below the pool counter. Story 3.4's dev notes call out the mount point; Story 3.3 ships the component shape with a `roomId` prop so Story 3.4 wires `<FriendGiftBadge roomId={roomId} />` at the documented slot.
   - **Multi-room aggregation** — when Story 3.3 lands BEFORE Story 3.4, the badge in `WalletPreview` reads the FIRST entry of `useMeSurvivalQuery()` (matching `WalletPreview.tsx` line 27 — `entries[0]`). When Story 3.4 lands, the per-room Wallet surface passes the specific `roomId` and the badge filters its data source to that room.
   - **Anti-AC** — the badge MUST NOT render on the chat screen, the today header, the rooms tab list, the profile tab, or the spectator daily digest. v1 explicitly says "Wallet surface only" per Architecture §4.8.
   - [Epics 519 "Wallet renders"; Story 2.1 AC7 `WalletPreview` mount; Architecture §4.8; project-context FE rule "Components do not call useQuery directly" — badge data flows via a domain hook]

7. **AC7 — Tests: BE unit + slice + IT, FE component + hook.**
   - **Backend tests (mirror Story 3.2 test layout — `BE/src/test/java/com/yeosal/api/revival/`):**
     - **`FriendGiftTargetQueryTest.java`** (`@DataJpaTest` + Testcontainers PostgreSQL, mirror `FriendGiftEligibilityQuery` precedent). At least 8 cases:
       1. Two rooms, one with eligible friend (RED + ACCEPTED friendship) → list size 1, single entry with `eligibleCount = 1`.
       2. One room with TWO eligible friends (one RED, one SPECTATOR) → list size 1, `eligibleCount = 2`, both friends in the `friends` array.
       3. Friend in room but friendship status is `PENDING` → exclude (eligibleCount = 0).
       4. Friend in room with `survival_state.status = ACTIVE` → exclude.
       5. Caller's own `survival_state.status = SPECTATOR` → empty list (giver-side gate fires SQL-side per AC1).
       6. Caller is NOT a member of the room a candidate-friend is in → exclude.
       7. Self-target → never returned (sst.user_id <> giverUserId clause).
       8. Cross-direction friendship row (`f.user_id = friendId AND f.friend_user_id = giverId`) is found by the OR clause.
     - **`MeFriendGiftTargetsControllerTest.java`** (`@WebMvcTest`, mirror `FriendGiftReceiptsControllerTest` style). At least 5 cases:
       1. Happy path → 200 OK envelope `{ data: [...] }`.
       2. Empty list → 200 OK envelope `{ data: [] }`, NEVER 404.
       3. Auth absent → 401.
       4. Caller is not a member of any room → 200 OK + empty list (no JOIN matches).
       5. Query returns 50+ entries → response shape stable (no truncation, no pagination — v1 caps by virtue of room-membership count, not query size).
     - **`RevivalServiceSourceSubtypeTest.java`** (Mockito unit, NEW). At least 3 cases:
       1. `reviveFriend(..., sourceSubtype = "WALLET_INITIATED")` → `insertOnConflictDoNothing` called with `"WALLET_INITIATED"` as the 5th arg.
       2. `reviveFriend(..., sourceSubtype = "PUSH_INITIATED")` → `"PUSH_INITIATED"` passed.
       3. `reviveFriend(..., sourceSubtype = null)` → defaults to `"PUSH_INITIATED"` (backward compat for any pre-3.3 callers).
     - **`FriendGiftControllerTest.java`** (EXTEND the existing Story 3.2 test file, do NOT replace it). Add 2 cases:
       1. POST body with `sourceSubtype: "WALLET_INITIATED"` → 200 OK; verify the service mock received `"WALLET_INITIATED"`.
       2. POST body with `sourceSubtype: "INVALID_VALUE"` → 400 VALIDATION code `INVALID_SOURCE_SUBTYPE`.
     - **`FriendGiftWalletInitiatedIT.java`** (NEW `@SpringBootTest` + Testcontainers PostgreSQL, mirror `MeSurvivalFreeTicketIT` style). At least 2 cases:
       1. POST with `sourceSubtype: "WALLET_INITIATED"` → assert `revival_events.source_subtype = 'WALLET_INITIATED'` via raw SQL probe.
       2. POST without `sourceSubtype` field → assert `revival_events.source_subtype = 'PUSH_INITIATED'` (backward compat).
   - **Frontend tests (mirror Story 3.2 layout — `FE/src/components/revival/__tests__/` + `FE/src/lib/query/hooks/__tests__/`):**
     - **`FriendGiftBadge.test.tsx`** — at least 7 cases:
       1. Renders nothing when `eligibleCount === 0`.
       2. Renders `"친구 회생 대기 (1)"` label when N = 1.
       3. Renders `"친구 회생 대기 (3)"` label when N = 3.
       4. Disabled state visible when `myPersonalPoints < 5` (mocked via `useMeSurvivalQuery`); tap fires no action.
       5. Enabled state — tap fires the modal-open callback prop with `roomId` + `receiverUserId` (when N = 1).
       6. Tap when N > 1 mounts the picker, not the modal directly.
       7. Accessibility label includes the count + tappable hint.
     - **`FriendGiftPickerSheet.test.tsx`** — at least 4 cases:
       1. Renders one row per eligible friend, count matches `friends.length`.
       2. Tap row → onSelect fires with that friend's `{userId, nickname}`.
       3. Cancel ("닫기") tap → onCancel fires; onSelect does NOT fire.
       4. Focus trap verification via `getByRole("dialog")`.
     - **`useFriendGiftTargets.test.tsx`** (`FE/src/lib/query/hooks/__tests__/friendGiftTargets.test.tsx`) — at least 4 cases:
       1. Empty data → returns `[]`.
       2. Data with one entry → returns the entry with parsed shape.
       3. `qk.friendGiftTargets` is invalidated when `qk.meSurvival` is invalidated (verify via cache observer or by checking refetch fires).
       4. Network error → query is in `error` state; consumer renders nothing (graceful degradation).
     - **`WalletPreview.test.tsx`** (EXTEND existing) — add 2 cases:
       1. When `useFriendGiftTargets` returns one eligible target → badge renders below the pool line.
       2. When `useFriendGiftTargets` returns empty → badge does NOT render.
     - **`useSendFriendGift.test.tsx`** (EXTEND existing Story 3.2 test) — add 2 cases:
       1. Mutation called with `sourceSubtype: "WALLET_INITIATED"` → request body includes the field.
       2. Mutation called without `sourceSubtype` → request body omits the field (backward compat verification).
   - **Coverage target — 80% per project-context.** Critical paths (the eligibility SQL with both friendship directions, the source_subtype default-when-null path, the badge's empty-state early return) MUST have integration tests against Testcontainers PostgreSQL (project-context: "DB integration tests use Testcontainers PostgreSQL. H2 is forbidden").
   - [Project-context Testing Rules — JUnit 5, AssertJ, Testcontainers, `@SpringBootTest`/`@WebMvcTest`/`@DataJpaTest` discipline; FE — `@testing-library/react-native`, `waitFor`/`findBy*`, `QueryClientProvider` wrap, mock `RealtimeProvider`, no real WebSocket]

## Tasks / Subtasks

- [ ] **BE-1.** Add `FriendGiftTargetSummaryDto` + `EligibleFriendDto` records in `com.yeosal.api.revival` (AC: 1)
  - [ ] `FriendGiftTargetSummaryDto(long roomId, String roomName, int eligibleCount, List<EligibleFriendDto> friends)`.
  - [ ] `EligibleFriendDto(long userId, String nickname, String status, Instant eliminatedAt)`.
- [ ] **BE-2.** Add `FriendGiftTargetQuery` (NEW class in `com.yeosal.api.revival`) with `List<EligibleFriendRow> findEligibleTargets(long giverUserId)` (AC: 1)
  - [ ] Native query per AC1 SQL — joins `survival_state` (receiver-side) + `room_members` (giver membership) + `survival_state` aliased `sst_giver` (giver-status gate) + `users` (nickname resolution) + `friendships` (OR-direction match).
  - [ ] Returns a flat row shape `EligibleFriendRow(long roomId, String roomName, long targetUserId, String targetNickname, String targetStatus, Instant targetEliminatedAt)`. Controller groups by `roomId` into the summary DTO.
  - [ ] Mirror the precedent of `FriendGiftEligibilityQuery` (Story 3.2) — same package, same `@Component` injection shape, same `EntityManager.createNativeQuery` pattern (do NOT use Spring Data's @Query repository method — the query returns a custom record shape not mapped to an entity).
- [ ] **BE-3.** Add `MeFriendGiftTargetsController` (NEW `@RestController` at `com.yeosal.api.revival`) with `@GetMapping("/me/friend-gift-targets")` (AC: 1)
  - [ ] Returns `ApiResponse<List<FriendGiftTargetSummaryDto>>`.
  - [ ] Calls `FriendGiftTargetQuery.findEligibleTargets(me.id)` → groups rows by `roomId` using `Collectors.groupingBy` → builds the summary DTO list with stable ordering (room id ASC for FE renderability).
  - [ ] `@Transactional(readOnly = true)` (mirror `MeFriendGiftController` line 81).
- [ ] **BE-4.** Add `RevivalSourceSubtype` enum (AC: 3)
  - [ ] `public enum RevivalSourceSubtype { PUSH_INITIATED, WALLET_INITIATED }` in `com.yeosal.api.revival`.
  - [ ] Mapped `@Enumerated(EnumType.STRING)` at every call site (mirror `LedgerReason`, `RevivalSource` precedents).
- [ ] **BE-5.** Extend `FriendGiftRequest` record to carry an optional `sourceSubtype` (AC: 3)
  - [ ] `public record FriendGiftRequest(@NotNull Long targetUserId, RevivalSourceSubtype sourceSubtype) {}` — `sourceSubtype` is nullable (no `@NotNull`); Jackson maps a missing JSON field to `null`.
  - [ ] Default-to-`PUSH_INITIATED` happens at the service-layer entry point (`RevivalService.reviveFriend`), NOT in the record — keep the record honest about wire shape (nullable when absent).
- [ ] **BE-6.** Refactor `RevivalService.reviveFriend(...)` to accept + persist `sourceSubtype` (AC: 3)
  - [ ] New signature: `reviveFriend(long roomId, User giver, long targetUserId, RevivalSourceSubtype sourceSubtype)`.
  - [ ] Inside the method: `String resolvedSubtype = (sourceSubtype == null ? "PUSH_INITIATED" : sourceSubtype.name());` — pass `resolvedSubtype` to `RevivalEventRepository.insertOnConflictDoNothing` at the existing call site (replaces the hardcoded `"PUSH_INITIATED"` literal).
  - [ ] Keep the existing 17-step orchestration verbatim — `sourceSubtype` is a pure data-passthrough, no flow control branches.
- [ ] **BE-7.** Update `RevivalController.@PostMapping("/{id}/revivals/gifts")` to forward `sourceSubtype` (AC: 3)
  - [ ] `return ApiResponse.of(revivalService.reviveFriend(id, me, body.targetUserId(), body.sourceSubtype()))`.
  - [ ] Spring's `@Valid` validates `targetUserId` (already `@NotNull`); `sourceSubtype` is a `RevivalSourceSubtype` enum so Jackson rejects invalid values with `HttpMessageNotReadableException` → `ApiExceptionHandler` already maps that to 400 VALIDATION (per Story 3.1 review-finding 3). Verify the error code surfaces as `VALIDATION` — Story 3.3 may need to add a more specific `INVALID_SOURCE_SUBTYPE` code variant for FE precision; check existing handler before adding.
- [ ] **BE-8.** Write all BE tests per AC7 (AC: 7)
  - [ ] `FriendGiftTargetQueryTest` (8 cases) — Testcontainers `@DataJpaTest`.
  - [ ] `MeFriendGiftTargetsControllerTest` (5 cases) — `@WebMvcTest`.
  - [ ] `RevivalServiceSourceSubtypeTest` (3 cases) — Mockito unit, NEW.
  - [ ] Extend `FriendGiftControllerTest` (2 cases).
  - [ ] `FriendGiftWalletInitiatedIT` (2 cases) — Testcontainers `@SpringBootTest`.
  - [ ] All Testcontainers ITs use `postgres:16` per project-context.
  - [ ] AssertJ assertions per Java testing rule.
  - [ ] Native @Modifying test call sites wrapped in `TransactionTemplate` per Story 3.1 review-finding (commit `2de5929` precedent).
- [ ] **FE-1.** Add typed API client at `FE/src/api/friendGiftTargets.ts` (AC: 1)
  - [ ] Define `EligibleFriendDto` + `FriendGiftTargetSummaryDto` TypeScript interfaces (`readonly` fields).
  - [ ] `getFriendGiftTargets(): Promise<FriendGiftTargetSummaryDto[]>` via `apiRequest<ApiEnvelope<FriendGiftTargetSummaryDto[]>>`.
- [ ] **FE-2.** Add query hook `useFriendGiftTargets()` at `FE/src/lib/query/hooks/friendGiftTargets.ts` (AC: 1, 6)
  - [ ] `useQuery<FriendGiftTargetSummaryDto[]>` with `queryKey: qk.friendGiftTargets`, `queryFn: getFriendGiftTargets`, `staleTime: 30_000`, `gcTime: 5 * 60_000`.
  - [ ] Optional derived helper `useFriendGiftTargetsForRoom(roomId: number)` that filters the cache by `roomId` — pure-function derived view, no second BE call.
- [ ] **FE-3.** Add `qk.friendGiftTargets = ["friendGiftTargets"] as const` to `FE/src/lib/query/keys.ts` (AC: 1, 6)
- [ ] **FE-4.** Wire `qk.friendGiftTargets` invalidation onto every existing `qk.meSurvival` invalidation site (AC: 1, 6)
  - [ ] `FE/src/lib/notifications.ts` — every `case` that invalidates `qk.meSurvival` (FRIEND_GIFT_PROMPT, FRIEND_GIFT_RECEIVED, others) ALSO invalidates `qk.friendGiftTargets`.
  - [ ] `FE/src/lib/query/hooks/friendGift.ts` — `useSendFriendGift` onSuccess + onError 4xx branches also invalidate `qk.friendGiftTargets`.
  - [ ] `FE/src/lib/query/hooks/revival.ts` — self-revival mutation onSuccess also invalidates `qk.friendGiftTargets` (a self-revival flips ELIGIBLE/INELIGIBLE for the receiver-side row).
- [ ] **FE-5.** Add `<FriendGiftBadge>` component at `FE/src/components/revival/FriendGiftBadge.tsx` (AC: 1, 2, 5, 6)
  - [ ] Props: `{ roomId: number; onTap: (target: {receiverUserId: number; receiverNickname: string}) => void; onTapMulti?: (room: FriendGiftTargetSummaryDto) => void }`.
  - [ ] Reads `useFriendGiftTargetsForRoom(roomId)` AND `useCurrentRoomSurvivalState(roomId)` for the balance gate.
  - [ ] Early return `null` when `eligibleCount === 0`.
  - [ ] Renders disabled variant + caption when `myPersonalPoints < 5`.
  - [ ] Renders enabled badge with the count when `myPersonalPoints >= 5`.
  - [ ] D2.bento sub-mode visual treatment (Architecture §4.16 token consumption — use `useTheme()` to read `colors.bgElevated`, `radii.default: 12`, `space.layoutPadding: 16`, `elevation.elevation1`, `typography.heading.weight: 700`).
  - [ ] Tap dispatch: `eligibleCount === 1` → `onTap({receiverUserId, receiverNickname})`; `eligibleCount > 1` → `onTapMulti(room)`.
  - [ ] Locked Korean copy + accessibility labels per AC5.
- [ ] **FE-6.** Add `<FriendGiftPickerSheet>` component at `FE/src/components/revival/FriendGiftPickerSheet.tsx` (AC: 2, 5)
  - [ ] Props: `{ open: boolean; room: FriendGiftTargetSummaryDto | null; onSelect: (friend: EligibleFriendDto) => void; onCancel: () => void }`.
  - [ ] `<Modal animationType="slide" transparent>` (slide on iOS, fade in reduced-motion).
  - [ ] One row per `room.friends` entry — avatar + nickname + status hint + "선택" CTA.
  - [ ] Title row + cancel row at bottom — locked Korean per AC5.
  - [ ] Touch targets ≥ 48dp.
  - [ ] `accessibilityViewIsModal` + reduced-motion compliance.
- [ ] **FE-7.** Extend `FriendGiftModalProps` to carry `sourceSubtype` (AC: 3)
  - [ ] Add `sourceSubtype?: "PUSH_INITIATED" | "WALLET_INITIATED"` prop. Default to `"PUSH_INITIATED"` when absent (backward compat for Story 3.2 push-deep-link callers).
  - [ ] Forward to `useSendFriendGift(roomId).mutate({targetUserId, sourceSubtype})` (next step).
- [ ] **FE-8.** Extend `useSendFriendGift` mutation to carry `sourceSubtype` in the request body (AC: 3)
  - [ ] `SendFriendGiftVars` extends to `{ targetUserId: number; sourceSubtype?: "PUSH_INITIATED" | "WALLET_INITIATED" }`.
  - [ ] `postFriendGift` in `FE/src/api/friendGifts.ts` accepts the optional 3rd arg and includes it in the JSON body when present (omit-when-undefined per project-context — no `undefined` serialization).
- [ ] **FE-9.** Wire `<FriendGiftBadge>` into `WalletPreview.tsx` (AC: 6)
  - [ ] Below the `🌿  개인 포인트` / `💚  그룹 포인트` lines, above `<SelfReviveCTA>`.
  - [ ] `<FriendGiftBadge roomId={first.roomId} onTap={...} onTapMulti={...} />` with a local state machine that wires the picker → modal flow per AC2.
- [ ] **FE-10.** Add badge mount point hook for Story 3.4 Wallet surface (AC: 6)
  - [ ] Export `<FriendGiftBadge>` from `FE/src/components/revival/index.ts` for cross-feature consumption.
  - [ ] Add a `// Story 3.4 mount point` comment marker so Story 3.4's Wallet UI dev knows where the badge slots in.
- [ ] **FE-11.** Add telemetry emit at the badge tap success path (AC: 4)
  - [ ] Per AC4 — emit `revival.friend_gift.sent` with `sourceSubtype` prop.
  - [ ] If Story 8.5 has shipped (verify `track` exists at expected import path), wire the call. If NOT, add the TODO comment per AC4 caveat.
- [ ] **FE-12.** Write all FE tests per AC7 (AC: 7)
  - [ ] `FriendGiftBadge.test.tsx` (7 cases).
  - [ ] `FriendGiftPickerSheet.test.tsx` (4 cases).
  - [ ] `useFriendGiftTargets.test.tsx` (4 cases).
  - [ ] Extend `WalletPreview.test.tsx` (2 cases).
  - [ ] Extend `useSendFriendGift.test.tsx` (2 cases).
- [ ] **VERIFY-1.** Run `bash scripts/verify.sh` from repo root (project-context — FE checks + BE test/build + Docker image build).
- [ ] **VERIFY-2.** Run the brand-voice lint helper against every new Korean string per AC5 (or manually verify zero AVOID-lexicon hits).
- [ ] **VERIFY-3.** Manual smoke test in dev: set up a 2-room scenario where one room has 1 eligible target and another has 2. Verify badge shows correct count in each. Verify the N=1 tap path opens FriendGiftModal directly; verify the N=2 tap path opens the picker first. Verify the resulting `revival_events.source_subtype` is `'WALLET_INITIATED'` via DB probe.
- [ ] **VERIFY-4.** Verify backward compat — a Story 3.2 push-deep-link tap (no `sourceSubtype` in request body) still results in `revival_events.source_subtype = 'PUSH_INITIATED'`.

### Review Findings

- [ ] [Review][Patch] Eligible-target query can return former room members — `FriendGiftTargetQuery` roots target eligibility in `survival_state` and only joins `room_members` for the giver. `RoomService.leave` deletes `room_members` but leaves `survival_state`, while `RevivalService.reviveFriend` later rejects a target without room membership. Add a target-side `room_members` join so the badge only surfaces current room-mate friends. [BE/src/main/java/com/yeosal/api/revival/FriendGiftTargetQuery.java:69]
- [ ] [Review][Patch] Invalid `sourceSubtype` returns generic `VALIDATION`, not required `INVALID_SOURCE_SUBTYPE` — AC3 requires invalid subtype values to surface `400 VALIDATION` code `INVALID_SOURCE_SUBTYPE`, but the controller currently relies on Jackson enum binding and `ApiExceptionHandler` maps all `HttpMessageNotReadableException`s to `"VALIDATION"`; the added controller test asserts the weaker generic code. Add a specific mapping/exception path or adjust request validation so invalid subtype gets the specified code. [BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java:95]
- [ ] [Review][Patch] AC4 telemetry hook is missing — sprint status shows Story 8.5 is still `backlog`, so AC4 requires TODO comments at each future emit site with the full `revival.friend_gift.sent` prop contract rather than inventing `track(...)`. There is no `track`, `analytics`, `revival.friend_gift.sent`, or Story 8.5 TODO in the FE success path. Add the TODO/docstring at the wallet-initiated and push-initiated success paths. [FE/src/components/revival/FriendGiftModal.tsx:103]
- [ ] [Review][Patch] Picker sheet exposes the modal as an alert instead of a dialog — AC5/AC7 require the picker sheet to be modal-accessible and testable via dialog semantics, but the sheet sets `accessibilityRole="alert"` and has no `accessibilityLiveRegion="polite"` first-focus affordance for Android. Use dialog/modal semantics and the specified live-region treatment. [FE/src/components/revival/FriendGiftPickerSheet.tsx:69]

## Dev Notes

### CRITICAL implementation traps (read FIRST)

1. **`revival_events.source_subtype` is V11-shipped — NO new migration.** Architecture §6.3 V11 line 50 ships the `source_subtype varchar(20)` column without a CHECK constraint. Story 3.3 enforces the `{PUSH_INITIATED, WALLET_INITIATED}` enum at the JAVA layer via `@Enumerated(EnumType.STRING)` on `RevivalSourceSubtype`. **Do NOT add a V13 migration to introduce a CHECK constraint** — V11 is already in prod (PR #57 shipped 2026-05-13); adding a CHECK against existing data is brittle and the Java enum is the authoritative source of truth (mirrors `LedgerReason` / `RevivalSource` precedents).

2. **Friendship direction matters — use the OR clause.** The `friendships` table stores one row per pair without canonicalization (mirror Story 3.2 `FriendGiftEligibilityQuery` line 60-67 + `KudosService` line 147-148). The AC1 SQL's `friendships f on ((f.user_id = :giverUserId AND f.friend_user_id = sst.user_id) OR (f.friend_user_id = :giverUserId AND f.user_id = sst.user_id))` is byte-shape-identical to Story 3.2's eligibility query — use the same shape. Don't try to be clever with `LEAST/GREATEST` rewrites; the OR is the canonical form.

3. **Spectator givers cannot tap the badge — gate fires SQL-side.** Per NFR-9.2.5 + UX line 247 "Wallet 풀 확인 — 어떤 화면에서도 항상 visible (탭 전환 없이)" — spectators see Wallet but cannot WRITE. The AC1 SQL gates `sst_giver.status in ('ACTIVE','YELLOW')` so spectator givers get an empty list. The FE's `WalletPreview` is currently spectator-only (Story 2.1 AC7); when Story 3.3 wires the badge into WalletPreview, the result is that **spectator users always see eligibleCount = 0** (badge renders nothing). This is correct: spectator users cannot revive friends in v1 (FR-8.3.3 implies giver must be a writeable member). When Story 3.4's full Wallet surface lands, it mounts for ALL members (active + spectator), and the same gate applies — spectator users see "친구 회생 대기 (0)" rendered as nothing.

4. **`sourceSubtype` default is `PUSH_INITIATED`, not null.** Story 3.2 ships with `"PUSH_INITIATED"` hardcoded at the `insertOnConflictDoNothing` call site. Story 3.3 refactors this to an optional 4th parameter on `RevivalService.reviveFriend`. **Don't write `null` to `source_subtype`** — the column is nullable per V11 line 50, but the semantic contract is that every FRIEND_GIFT row has a non-null subtype. Pre-3.3 rows are already non-null (Story 3.2 hardcoded "PUSH_INITIATED"); post-3.3 rows must remain non-null. The default-to-`"PUSH_INITIATED"` translation happens at the service-method entry, before the INSERT.

5. **Telemetry is a forward dependency.** Story 8.5 (Analytics SDK) lands W1 per epics line 1183 ("Story 8.5 (Analytics SDK selection) must complete in W1 to instrument all downstream stories"). Story 3.3 lands W3/W4 per epics line 1186. So Story 8.5 SHOULD be available by 3.3 — but verify before writing the `track(...)` call. If 8.5 has slipped (check `_bmad-output/implementation-artifacts/sprint-status.yaml` development_status for `8-5-...`), add the TODO comment per AC4 caveat rather than inventing a placeholder. Project-context rule: "Don't add features, refactor, or introduce abstractions beyond what the task requires."

6. **The badge mount point in WalletPreview is a layout-stable insert.** `WalletPreview.tsx` lines 32-58 render: ticket line (conditional) → 개인 포인트 line → 그룹 포인트 line → `<SelfReviveCTA>`. Story 3.3 inserts `<FriendGiftBadge>` AFTER 그룹 포인트, BEFORE `<SelfReviveCTA>`. Place it so the visual rhythm (Wallet stat → Wallet stat → friend-action badge → self-action CTA) reads as "Pool first, friends second, self last." Don't move the SelfReviveCTA — Story 3.1 owns that placement.

### Architecture & Patterns to Reuse (zero-reinvention)

- **Eligibility query pattern** is fully owned by Story 3.2's `FriendGiftEligibilityQuery` (eligible GIVERS for a receiver). Story 3.3 mirrors the same `@Component` injection shape, the same `EntityManager.createNativeQuery` pattern, the same OR-direction friendship join. The only differences: (a) caller is the GIVER, not the RECEIVER; (b) result includes the FRIENDS' nicknames + statuses (for the picker), not just user ids. Read `FriendGiftEligibilityQuery.java` lines 1-90 before writing — copy the boilerplate.
- **`@Transactional(readOnly = true)` for read endpoints** — `MeFriendGiftController` precedent (lines 78-82 + 134-138). Story 3.3's `MeFriendGiftTargetsController` follows the same shape.
- **`ApiResponse.of(...)` envelope** is mandatory (project-context "All controller responses must be wrapped in `ApiResponse<T>`"). The FE's `apiRequest<ApiEnvelope<T>>(...)` reads `envelope.data`.
- **`@Enumerated(EnumType.STRING)` for enums backing CHECK constraints / wire fields** — project-context Java rule + `LedgerReason` / `RevivalSource` / `ChatMessageKind` / `NotificationKind` / `SurvivalStatus` precedents.
- **Domain hooks pattern on FE** — `useFriendGiftReceipts` (Story 3.2), `useMeSurvivalQuery` (Story 2.1) precedents. Components MUST NOT call `useQuery` directly (project-context FE rule). `useFriendGiftTargets` follows the same shape.
- **`qk.*` query-key registry** — `FE/src/lib/query/keys.ts`. Add ONE new key per BE endpoint; group invalidations downstream rather than nesting keys.
- **Brand-voice copy contract** — `tools/brand-voice-lint.ts` (if exists) + manual AVOID-lexicon review per FR-8.8.2 / FR-8.8.6.
- **D2.bento sub-mode tokens** — Architecture §4.16 + UX line 1081-1090. Consumed via `useTheme()`; never hardcoded hex (project-context FE rule + Story 1.5 codegen pipeline).
- **`AccessibilityInfo.isReduceMotionEnabled()`** — RevivalSequence precedent (Story 3.2 FE-5).

### Pre-existing Behaviours That Must Be Preserved

- **`RevivalService.reviveFriend` 17-step orchestration (Story 3.2)** — Story 3.3 changes ONLY the `sourceSubtype` parameter wiring. The advisory lock key, the EXCLUDING-self lifetime-1 snapshot query, the friendship gate, the AFTER_COMMIT event publishing — all of these MUST remain byte-identical. The dev agent MUST diff their refactored service method against the pre-3.3 version and confirm only the `insertOnConflictDoNothing` parameter list changed (specifically, the 5th positional argument now reads `resolvedSubtype` instead of the literal `"PUSH_INITIATED"`).
- **`FriendGiftRequest` record shape (Story 3.2)** — extending a record with a nullable field is a safe additive change. Don't break existing callers; Jackson handles the missing-field-defaults-to-null case automatically.
- **`WalletPreview.tsx` visual layout (Story 2.1)** — Story 3.3 inserts a new row but does NOT change the existing rows' order, copy, or styling. The Story 2.1 + 3.1 tests for WalletPreview MUST still pass after the badge insertion (the extension adds 2 new cases, doesn't modify existing ones).
- **`useSendFriendGift` cache-invalidation policy (Story 3.2)** — Story 3.3 ADDS `qk.friendGiftTargets` to the invalidation set but doesn't remove anything. The Story 3.2 cache-policy tests MUST still pass.
- **`notifications.routeInvalidation` switch (Story 3.2)** — Story 3.3 extends each `case` that invalidates `meSurvival` to ALSO invalidate `friendGiftTargets`. Don't refactor the helper's structure — just add the extra invalidation call.
- **`FriendGiftModal` 3-CTA layout (Story 3.2)** — Story 3.3 adds ONE new prop (`sourceSubtype`); the visual + interactive contract is untouched.

### Project Structure Notes

- **BE: `revival/` module ownership extends.** Story 3.3's new files (`FriendGiftTargetSummaryDto`, `EligibleFriendDto`, `FriendGiftTargetQuery`, `MeFriendGiftTargetsController`, `RevivalSourceSubtype`) all live in `com.yeosal.api.revival` — same module as the rest of the friend-gift surface (Story 3.2). Do NOT spawn a new `wallet/` or `discovery/` package. The domain owner remains "revival economy."
- **FE: `components/revival/` extends.** `FriendGiftBadge.tsx` + `FriendGiftPickerSheet.tsx` belong in `components/revival/` (Story 3.2 folder). Re-export them from `components/revival/index.ts` for Story 3.4 consumption. The badge is technically a Wallet-surface child but its action surface (FriendGiftModal) is a revival concern; folder follows action, not visual placement.
- **FE: `api/friendGiftTargets.ts` is a NEW file.** Don't append to `api/friendGifts.ts` — that file owns the gift-spending surface (POST + receipts). The targets endpoint is read-only and serves the badge surface, distinct enough to warrant its own file (mirrors `api/survival.ts` vs `api/friendGifts.ts` split).
- **FE: query-keys registry** — `FE/src/lib/query/keys.ts` is the SINGLE source of truth for cache keys (Story 3.2 added two; Story 3.3 adds one). Don't define inline keys in the new hook file.
- **FE: SecureStore key namespace unchanged** — Story 3.3 introduces NO new SecureStore keys. The badge surface is in-memory only (TanStack Query cache). Tap → modal → response → cache-invalidation; no durable per-user state.

### Telemetry contract (AC4 detail)

When the analytics SDK lands per Story 8.5, the following event taxonomy applies to Story 3.3:

```
event: revival.friend_gift.sent
props:
  roomId:                      number
  receiverUserId:              number
  sourceSubtype:               "PUSH_INITIATED" | "WALLET_INITIATED"
  isFirstEverFriendGiftSend:   boolean
  eligibleCountAtBadgeRender:  number   // 0 for push-initiated (no badge context)
```

The Day-30 falsification query becomes (in whatever analytics warehouse Story 8.5 selects):

```sql
SELECT sourceSubtype, COUNT(*)
FROM events
WHERE event = 'revival.friend_gift.sent'
  AND ts >= NOW() - INTERVAL '30 days'
GROUP BY sourceSubtype;
```

If `WALLET_INITIATED > PUSH_INITIATED`, the architecture §4.8 hypothesis is overturned and PRD §13.4 "v1.5 Wallet 친구 살리기 surface" returns from "deferred" to "in scope" with concrete evidence.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#story-33` lines 511–535]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-836` line 382 (FR-8.3.6)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#48` lines 278–286 (push-only v1 + Wallet badge + source_subtype discriminator)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#62` lines 603–652 (FE module shape)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#63` lines 654–800 (V11 schema — `source_subtype varchar(20)` line 50)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#416` lines 419–490 (FE→BE token codegen pipeline — D2.bento sub-mode)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#1081-1090` (D2.bento sub-mode override tokens)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#370` (Agency — push 1회 + Wallet 배지 + 거절·미액션 invisible)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#1316` (Wallet badge "친구 회생 대기 N" — oxblood ember 점 1개)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#1391` (Trigger 이중화 — push 1회 + passive wallet/badge backup)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#1786` (Push permission denial → silent fallback to Wallet badge)]
- [Source: `_bmad-output/implementation-artifacts/3-2-friend-gift-revival-push-prompt-friend-gift-modal.md` (Story 3.2 ACs + implementation patterns)]
- [Source: `_bmad-output/implementation-artifacts/3-1-free-revival-ticket-self-revival-via-personal-points.md` (Story 3.1 ACs + WalletPreview integration)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/RevivalService.java` lines 97-258 + new `reviveFriend` (Story 3.2 service template + sourceSubtype refactor target)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/RevivalEventRepository.java` lines 39-160 (insertOnConflictDoNothing — 5th positional arg is the refactor target)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/FriendGiftEligibilityQuery.java` (Story 3.2 native query template — inverse direction is the AC1 model)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/FriendGiftRequest.java` (record extension target)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/MeFriendGiftController.java` lines 78-138 (controller pattern for the new `/me/friend-gift-targets` endpoint)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/RevivalSource.java` (enum precedent for `RevivalSourceSubtype`)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/LedgerReason.java` (enum precedent — `@Enumerated(EnumType.STRING)` mapping)]
- [Source: `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql` line 50 (source_subtype column shipped — NO new migration)]
- [Source: `FE/src/components/survival/WalletPreview.tsx` lines 32-71 (badge mount point at line 56, between 그룹 포인트 line and `<SelfReviveCTA>`)]
- [Source: `FE/src/components/revival/FriendGiftModal.tsx` lines 47-53 (props shape — sourceSubtype additive extension)]
- [Source: `FE/src/lib/query/hooks/friendGift.ts` lines 30-77 (mutation cache policy — extend with friendGiftTargets invalidation)]
- [Source: `FE/src/lib/query/hooks/survival.ts` lines 1-40 (useMeSurvivalQuery shape — `useFriendGiftTargets` mirror template)]
- [Source: `FE/src/lib/query/keys.ts` lines 1-40 (qk.friendGiftTargets addition target)]
- [Source: `FE/src/api/friendGifts.ts` (typed client template — `getFriendGiftTargets` mirror)]
- [Source: `FE/src/lib/notifications.ts` (routeInvalidation switch — `friendGiftTargets` invalidation co-located)]
- [Source: `FE/src/lib/spectator.ts` lines 25-32 (`MeSurvivalEntry` shape — `personalPoints` is the balance gate source)]

### Testing Standards Summary

- **BE**: JUnit 5 + AssertJ + Mockito + Testcontainers PostgreSQL (`postgres:16`). No H2 (project-context). `@SpringBootTest` for full integration (Flyway + security chain), `@WebMvcTest` for controller slice, `@DataJpaTest` for repository slice. Test naming: `methodName_scenario_expectedBehavior()` or `@DisplayName("...")`. Coverage 80% minimum. Native `@Modifying` test sites wrapped in `TransactionTemplate` per Story 3.1 review precedent.
- **FE**: Jest 29 + `@testing-library/react-native`. Test files at `FE/src/**/__tests__/**/*.test.{ts,tsx}` (Jest config requires this path). `QueryClientProvider` wrap for hook tests. Stub `fetch` (no real network). Mock `RealtimeProvider` (no real WebSocket). `waitFor` / `findBy*` for async (no arbitrary `setTimeout`). Pre-push: `npm run lint && npm run typecheck && npm test` all green.
- **Project-wide**: `bash scripts/verify.sh` from repo root before declaring story complete. Verify zero brand-voice lint HARD violations per AC5.

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m]

### Debug Log References

### Completion Notes List

### File List

### Change Log
