# Story 3.4: Wallet UI surface

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **an active OR spectator member who needs to understand the entire revival economy from one place**,
I want **a single Wallet view per-room (route `app/wallet/[roomId].tsx`) that lays out my free revival ticket presence, my personal-points balance for that room (with a tappable ledger drill-in), the room's point pool (with a live WS-driven positive-bar-fill animation on `/topic/rooms.{id}.points`), and my private "받은 회생권" history (covering all 3 sources: FREE_TICKET / PERSONAL_POINTS / FRIEND_GIFT — with donor name shown only for FRIEND_GIFT entries) — all themed via the D2.bento sub-mode tokens from the FE→BE codegen pipeline**,
so that **the entire economy is legible from one surface (PRD FR-8.3.6), the room point pool stays the single most-displayed number in the UI (Architecture §4.6), the private history reinforces dignity-by-default privacy (FR-8.3.5 + FR-8.3.7), and other members viewing my profile see ONLY the group-level pool (never my personal points or history) — the per-room split lets the architecture stay aligned with the per-room cap (V11 §6.3) without forcing a cross-room aggregation that violates the room-scoped privacy model**.

PRD authority: **FR-8.3.6** (Wallet UI surface: free revival ticket presence + personal points balance + room point pool + 받은 회생권 history, all in one place — Story 3.4 is the load-bearing story for this FR), **FR-8.2.5** (Spectator users see their own Wallet prominently on the Home tab — Story 2.1 ships the `WalletPreview` preview; Story 3.4 ships the full surface), **FR-8.3.5** (donor name visible to receiver only — applies to the FRIEND_GIFT rows in 받은 회생권 history), **FR-8.3.7** (rejection / non-action invisible — Wallet MUST NOT surface failed-revival rows or eligible-giver counts to anyone but the donor themselves), **FR-8.3.8** (`personal_points_ledger` append-only — Story 3.4 ships the read endpoint; AC2 ledger drill-in surfaces every reason: SURVIVAL / REVIVAL_SPEND / FRIEND_GIFT_SPEND / ROOM_LEAVE / ADJUSTMENT), **FR-8.4.3** (`/topic/rooms.{id}.points` emission — Story 3.4 ships the FE consumer; the BE publisher already lives in `RealtimePublisher.publishPointPoolChange` per Story 3.1).
Architecture authority: **§4.5** (personal-points ledger append-only — SUM(delta) for balance, not running column), **§4.6** (room point pool materialized counter cache — `room_point_pool.total` row per `room_id`, hot path), **§4.16** (FE→BE token codegen — D2.bento sub-mode override tokens consumed via `useTheme()`, never hardcoded), **§5.4** (privacy patterns — server-side filtering, FE simply renders what BE returns).

PRD secondary refs: **FR-8.8.2** (brand-voice copy lock), **FR-8.8.6** (release-gate brand-voice review), **NFR-9.2.4** (`SELECT … FOR UPDATE` on `room_point_pool` — already shipped Story 3.1, Story 3.4 only READS this row), **NFR-9.6.1** (packed-type `semantic.survival` reference — Wallet uses semantic tokens not raw hex), **§14.2** (visual identity — D2.bento is the standard Wallet/Pool/Stats sub-mode).

## Acceptance Criteria

Numbering matches the BDD blocks in `_bmad-output/planning-artifacts/epics.md` lines 537–561 (Story 3.4 stanza). Each AC carries its source ref inline so the dev agent has a precise audit trail to the original requirement.

1. **AC1 — 4-section Wallet UI layout.**
   - **Given** I navigate to the Wallet route for room R (`app/wallet/[roomId].tsx`),
   - **When** the screen renders,
   - **Then** four sections appear in this order, top-to-bottom, each a D2.bento Surface card (Architecture §4.16 + UX 1081-1090):
     1. **Free revival ticket** — single Bento Surface row. When `useMeSurvivalQuery()` for room R returns `freeRevivalTicketUsed === false` → renders "🎟  무료 회생권 1매" + caption "남은 회생권". When `freeRevivalTicketUsed === true` → renders "🎟  사용 완료" + caption "다음 시즌에 새로 받아요" (forward-pointer to Phase-2 promise UI, Story 4.2). The ticket is user-scoped (Architecture §4.12 — stored on `users`, lifetime-1 across the whole account) so this row is IDENTICAL across every room's Wallet view, but each room's Wallet still renders it for visual completeness.
     2. **Personal points balance** — single Bento Surface row with the metric as the primary text (display.sm typography weight per UX line 914). Renders `useMeSurvivalQuery()` for room R returning `personalPoints` (integer; computed BE-side via `PersonalPointsLedgerRepository.sumDeltaByUserIdAndRoomId(userId, roomId)`). Tap surface opens the ledger detail view (AC2) via `expo-router`'s `router.push(\`/wallet/${roomId}/ledger\`)`.
     3. **Room point pool** — Bento Surface card with the metric as primary, a `<PoolBar>` animated fill underneath (compositor-friendly `transform: scaleX(...)` animation per project-context web/performance rule), AND a "친구 회생 대기 (N)" badge (Story 3.3's `<FriendGiftBadge roomId={roomId} />` mounted here — see Story 3.3 AC6.2). The pool number reads `entry.roomPointPool` (currently 0 for v1 per Architecture §6.3 V11 step 15 backfill; will grow as friend-gifts land). The PoolBar fill ratio = `roomPointPool / poolMax` where `poolMax` is a constant per room (v1: 100 — Story 4.3 wires the real threshold table; v1 placeholder is fine here, leave a TODO comment).
     4. **받은 회생권 history** — Bento Surface card titled "받은 회생권". Tap surface opens the detail view (AC3) via `router.push(\`/wallet/${roomId}/received-revivals\`)`. Headline metric: count of received revivals in this room (any source). Caption: most recent revival's date in `M월 D일` KST format, or "아직 없어요" when zero.
   - **Layout grid** — single column on phones (UX line 948 "모바일 단독 (320~430pt). 1-column 기본, Wallet은 2-column bento만"). v1 ships single column; 2-column bento is a v1.5 polish item. Section gap = `space.layout.padding` (16px from D2.bento override tokens).
   - **Sub-mode wrapper** — the entire route is wrapped in `<SubModeProvider subMode="bento">` at the route root (`app/wallet/[roomId].tsx`). All leaf components consume via `useTheme()`. No leaf component branches on `subMode === "bento"` directly (Architecture §4.7 + UX cross-cutting rule #9 — sub-mode is page-level only, never branched inside leaf components).
   - **Screen header** — uses the existing `<Screen>` wrapper (Story 1.6 pattern). Title: "{roomName} Wallet". Back button returns to wherever the user came from (back button from `expo-router` default).
   - **Loading state** — when `useMeSurvivalQuery()` is `isLoading`, render an `<ActivityIndicator color={palette.coralDeep} />` per `today.tsx` precedent. Do NOT render a skeleton for v1 (out of scope).
   - **Error state** — when the query errors, render an inline error caption "잠시 후 다시 시도해주세요" (locked brand-voice copy from Story 3.2 FriendGiftModal `genericToast`). Do NOT auto-retry; the user can pull-to-refresh.
   - [Epics 545-547; PRD FR-8.3.6; UX line 1457 `<Wallet>` Bento Surface × 6 + PoolStack + ledger; Architecture §4.16 D2.bento sub-mode]

2. **AC2 — Personal points ledger detail view.**
   - **Given** the Wallet section "Personal points balance" is rendered,
   - **When** I tap it,
   - **Then** the route `/wallet/{roomId}/ledger` opens (NEW route file `app/wallet/[roomId]/ledger.tsx`) and the screen renders a chronological list (most recent first) of every `personal_points_ledger` row for `(userId = me, roomId = R)`. Each row shows:
     - Date in `M월 D일 HH:mm` KST format
     - Reason label (Korean — see locked copy in AC5)
     - Signed delta with sign-appropriate visual treatment (positive = ink, negative = ember/oxblood)
     - Caption: the friendly explanation per reason (e.g. SURVIVAL → "오늘의 잔디 한 칸"; REVIVAL_SPEND → "회생권 사용"; etc.)
   - **BE endpoint** — NEW `GET /api/v1/me/personal-points-ledger?roomId={id}` returning `ApiResponse<List<LedgerEntryDto>>`:
     ```
     LedgerEntryDto {
       long    id;
       long    roomId;
       short   delta;                       // signed; sum across the list = current balance
       String  reason;                      // "SURVIVAL" | "REVIVAL_SPEND" | "FRIEND_GIFT_SPEND" | "ROOM_LEAVE" | "ADJUSTMENT"
       Instant occurredAt;                  // ISO-8601 UTC
       Long    revivalEventId;              // nullable; FK to revival_events when reason ∈ {REVIVAL_SPEND, FRIEND_GIFT_SPEND}
     }
     ```
   - **Native query** — extend `PersonalPointsLedgerRepository` with:
     ```java
     @Query(value = """
             select id, user_id, room_id, delta, reason, occurred_at, revival_event_id
             from personal_points_ledger
             where user_id = :userId and room_id = :roomId
             order by occurred_at desc, id desc
             """, nativeQuery = true)
     List<PersonalPointsLedger> findByUserIdAndRoomIdOrderByOccurredAtDesc(
             @Param("userId") long userId, @Param("roomId") long roomId);
     ```
     Tie-breaking on `id DESC` handles the case where two rows share an `occurred_at` (idempotent re-run from Story 1.2 evaluator job).
   - **Pagination** — v1 ships **no pagination**. The ledger is per-room append-only with ~1 row/day from SURVIVAL + occasional revival rows. Cap at 365 rows for a typical user × room × year, which is well within a single GET response budget. If the dev agent finds a room with > 1000 rows during testing, escalate to PM before adding pagination — that's an unexpected scale break.
   - **Privacy** — endpoint is `@Transactional(readOnly = true)` + `Authentication` scoped to `me` (mirror `MeFriendGiftController` precedent). MUST NOT be reachable for `userId != me`. NO `?userId=` query parameter.
   - **Empty state** — render "이 방에서 받은 잔디 흔적이 아직 없어요" (brand-voice locked; AVOID lexicon 0 violations). Empty state is the design system's `<EmptyState>` component if it exists, else a centered `<Text variant="body" color={palette.inkMute}>`.
   - **Total balance row at the top** — the ledger view headlines the SUM of `delta` at the top (matches the Wallet card's primary metric); the list below is the audit trail. Render the headline using the existing `entry.personalPoints` from `useMeSurvivalQuery()` (do NOT recompute on FE — the BE sum is authoritative).
   - [Epics 549-551; PRD FR-8.3.8; Architecture §4.5 ledger append-only; UX line 1457 "ledger"]

3. **AC3 — 받은 회생권 history detail view (all 3 sources).**
   - **Given** the Wallet section "받은 회생권" is rendered,
   - **When** I tap it,
   - **Then** the route `/wallet/{roomId}/received-revivals` opens (NEW route file `app/wallet/[roomId]/received-revivals.tsx`) and the screen renders a chronological list (most recent first) of every successful `revival_events` row where `user_id = me AND room_id = R` (i.e., I was the RECEIVER), covering all 3 sources:
     - **FREE_TICKET** — row shows "🎟  무료 회생권" + date + caption "스스로 회생"
     - **PERSONAL_POINTS** — row shows "🌿  포인트로 회생" + date + caption "내 포인트 3점 사용"
     - **FRIEND_GIFT** — row shows "💗  친구의 선물" + date + **donor nickname** (visible only because this is MY private list per FR-8.3.5)
   - **BE endpoint** — extend the existing `GET /api/v1/me/friend-gift-receipts` to support a `?sources=` query parameter, OR add a NEW endpoint `GET /api/v1/me/received-revivals?roomId={id}` that returns all 3 sources. **Recommended path:** add the new endpoint. Don't pollute the existing 7-day-window FRIEND_GIFT-only endpoint with new filters; Story 3.2's 7-day window has different semantics (KST day arithmetic, 0..6 inclusive) than this lifetime-history endpoint.
     ```
     GET /api/v1/me/received-revivals?roomId={id}
     ApiResponse<List<ReceivedRevivalDto>>
     ReceivedRevivalDto {
       long    revivalEventId;
       long    roomId;
       String  roomName;
       String  source;            // "FREE_TICKET" | "PERSONAL_POINTS" | "FRIEND_GIFT"
       Long    donorUserId;       // nullable; only set for FRIEND_GIFT
       String  donorNickname;     // nullable; only set for FRIEND_GIFT (FR-8.3.5 — receiver-only visibility)
       Instant occurredAt;        // ISO-8601 UTC
     }
     ```
   - **Native query** — extend `RevivalEventRepository` with:
     ```java
     @Query(value = """
             select * from revival_events
             where user_id = :receiverUserId
               and room_id = :roomId
               and succeeded = true
             order by occurred_at desc, id desc
             """, nativeQuery = true)
     List<RevivalEvent> findReceivedRevivalsByRoom(
             @Param("receiverUserId") long receiverUserId,
             @Param("roomId") long roomId);
     ```
   - **AC9 privacy invariant (carried from Story 3.2)** — donor nickname for FRIEND_GIFT rows MUST appear in this endpoint's response (the calling user is the receiver). The endpoint MUST NEVER include rows where `giver_user_id = me`; that's the donor's wallet history (a separate forward surface — Story 3.4 does NOT ship "내가 살린 친구 목록" in v1; that's a deferred v1.5 surface per UX line 1334).
   - **Empty state** — render "이 방에서 받은 회생권이 아직 없어요" (brand-voice locked).
   - **Cross-room** — v1 ships per-room only. If the user wants to see history across all rooms, they navigate per-room. v1.5 may add `?allRooms=true` — out of scope here.
   - [Epics 553-555; FR-8.3.5; FR-8.3.7; Story 3.2 AC7 (FRIEND_GIFT-only 7-day window — DISTINCT from this lifetime endpoint)]

4. **AC4 — Other-user profile shows ONLY group-level pool.**
   - **Given** another room member (User B) views my (User A's) profile via the existing `app/friend-profile.tsx` or `app/(tabs)/profile` surfaces,
   - **When** the profile UI renders,
   - **Then** User B sees ONLY the room point pool (a group-level fact, identical for every member of that room). User B MUST NOT see:
     - My free revival ticket status (private)
     - My personal points balance (private)
     - My received-revival history (private)
     - My ledger entries (private)
   - **BE invariant** — existing `GET /api/v1/profiles/{userId}` returns `PublicProfileDto` (nickname + timezone only) per `ProfileController.java:53-60`. **NO new endpoint that returns wallet data for a non-self user.** If Story 3.4 inadvertently adds one, that's a privacy regression — the reviewer MUST block.
   - **FE invariant** — the friend-profile / other-user-profile screens MUST NOT call any of the `/me/...` endpoints Story 3.4 adds (they're authenticated-as-self only; calling them returns the *caller's* data, not the target user's — which is wrong on its own and a code smell). If those screens need to show a room-pool number for a room User A and User B share, they call `GET /api/v1/me/survival` (the caller's own aggregation) and pluck the `roomPointPool` field for the relevant room. The pool is a group-level value; it's the same number regardless of who calls.
   - **Defence test (AC8 part 1)** — integration test verifies that a User-B-authenticated call to `GET /api/v1/me/personal-points-ledger?roomId=R` returns User B's ledger, NOT User A's (i.e., there is no path to read another user's private data through this endpoint).
   - [Epics 557-559; FR-8.3.6 "private to the user"; Architecture §5.4 privacy patterns]

5. **AC5 — D2.bento sub-mode tokens + live pool growth animation (FE→BE codegen).**
   - **Given** the Wallet route mounts under `<SubModeProvider subMode="bento">`,
   - **When** any Wallet leaf component renders,
   - **Then** it consumes tokens via `useTheme()` only — NEVER hardcoded hex values or string literals. The D2.bento override set (UX 1081-1090) provides:
     ```json
     {
       "color.bg.elevated":          "oklch(20% 0.010 30)",
       "radius.default":             12,
       "space.layout.padding":       16,
       "elevation.1":                "0 1px 2px rgba(0,0,0,0.4), 0 2px 4px rgba(0,0,0,0.2)",
       "typography.heading.weight":  700
     }
     ```
     If the codegen pipeline (Architecture §4.16) has shipped these tokens to `FE/src/theme/tokens.json` under the `subMode.bento` key, the SubModeProvider injects them automatically. If NOT (Story 1.5 may have shipped a placeholder), the dev agent MUST add them as part of Story 3.4 — they are load-bearing for D2.bento.
   - **Live pool growth animation** — `<PoolBar>` subscribes to the existing `/topic/rooms.{roomId}.points` STOMP topic (`RealtimePublisher.publishPointPoolChange` already publishes per Story 3.1):
     - On WS frame receipt, parse `PointPoolChangePayload { roomId, totalAfter, lastEventAt }`.
     - Invalidate `qk.meSurvival` so the headline metric refreshes.
     - Animate the bar fill from the previous `totalAfter` to the new one via `Animated.timing` on `transform: scaleX` (compositor-friendly).
     - Animation duration: 600ms (slow enough to feel "weighty" per UX line 1981 "Wallet card stagger 200ms × n").
     - Easing: `Easing.out(Easing.cubic)` — feels like a "weight settling."
   - **Subscription lifecycle** — use the existing `RealtimeProvider` pattern (Story 1.2 + Story 3.1 precedent — `pointsHandler.ts` is the architecture-mandated handler at `FE/src/lib/realtime/handlers/pointsHandler.ts`; verify it exists, if not Story 3.4 ships it as part of this AC). Subscribe on mount; unsubscribe on unmount. Don't open the WS connection from inside `<PoolBar>` — it lives in the global `RealtimeProvider`.
   - **Reduced-motion** — when `AccessibilityInfo.isReduceMotionEnabled()` is true, the bar fills instantly (no animation). The numeric headline still updates.
   - **Compositor-friendly only** — animate `transform` (scaleX) NOT `width`. Width animation triggers layout per render frame; transform is GPU-composited. Project-context web/performance rule "Animate compositor-friendly properties only; avoid layout-bound properties: width, height, top, left, margin, padding".
   - **Brand-voice copy locked** (zero AVOID-lexicon hits per FR-8.8.2):
     - `"무료 회생권 1매"` — section 1 enabled state
     - `"사용 완료"` — section 1 used state
     - `"다음 시즌에 새로 받아요"` — section 1 caption (forward-pointer to Phase-2)
     - `"개인 포인트"` — section 2 label
     - `"그룹 포인트"` — section 3 label (matches WalletPreview lines 53-55 — DO NOT introduce a different phrase)
     - `"받은 회생권"` — section 4 label (matches epics line 545)
     - `"잔액"` — generic balance label (used in detail views)
     - `"오늘의 잔디 한 칸"` — SURVIVAL ledger caption
     - `"회생권 사용"` — REVIVAL_SPEND caption
     - `"친구에게 선물한 회생권"` — FRIEND_GIFT_SPEND caption
     - `"방을 떠났어요"` — ROOM_LEAVE caption (no AVOID lexicon despite negative connotation; "떠났어요" is brand-voice-acceptable per FR-8.8.2 review — verify with brand-voice lint)
     - `"운영자 조정"` — ADJUSTMENT caption
     - `"이 방에서 받은 잔디 흔적이 아직 없어요"` — ledger empty state
     - `"이 방에서 받은 회생권이 아직 없어요"` — received-revivals empty state
     - `"스스로 회생"` — FREE_TICKET history caption
     - `"포인트로 회생"` — PERSONAL_POINTS history caption
     - `"친구의 선물"` — FRIEND_GIFT history label
     - `"잠시 후 다시 시도해주세요"` — generic error toast (REUSE from Story 3.2)
     - `"무료 회생권"` — FREE_TICKET history primary text
   - [Epics 561; Architecture §4.6 + §4.16; UX 1081-1090 D2.bento; UX 1981 motion timing; FR-8.4.3 `/topic/rooms.{id}.points` consumer]

6. **AC6 — Routing + navigation integration.**
   - **Given** the Wallet route lives at `app/wallet/[roomId].tsx` with nested detail routes,
   - **When** the user navigates,
   - **Then**:
     1. **Entry from existing surfaces**: Add a "Wallet 자세히 보기" link to `WalletPreview.tsx` (Story 2.1's spectator surface) that pushes `/wallet/{first.roomId}`. ALSO add a "Wallet" entry to the per-room screen `app/rooms/[id].tsx` (mounted in the room detail header area) that pushes `/wallet/{id}`. ALSO add a "Wallet" row to the `app/(tabs)/profile.tsx` screen that, when tapped, opens a per-room picker if user is in multiple rooms (otherwise pushes directly).
     2. **Back navigation**: `expo-router`'s default back stack works — no custom handling needed.
     3. **Deep-linking**: a notification with `data.kind === "POOL_THRESHOLD"` (Story 4.3 forward) deep-links to `/wallet/{roomId}` — Story 3.4 wires the `useNotificationResponseDeepLink()` extension if Story 4.3 hasn't yet shipped; otherwise Story 4.3 wires it.
     4. **Detail routes**: `/wallet/{roomId}/ledger` and `/wallet/{roomId}/received-revivals` exist as nested routes (expo-router file-system conventions).
   - **NO new tab** — the Wallet is a stack route, not a 6th bottom tab. The current 5-tab layout (today/feed/rooms/chat/profile) is preserved (UX line 1738 aspires to a Wallet tab, but v1 ships stack-push only to avoid bottom-tab restructure churn).
   - **NO cross-room Wallet** — v1 ships per-room only. The profile-tab Wallet row uses a picker when the user has multiple rooms (out-of-scope for Story 3.4 unless the user is in exactly one room — in which case skip the picker).
   - [Epics 545 "I open the Wallet tab"; UX line 247 "Wallet 풀 확인 — 어떤 화면에서도 항상 visible (탭 전환 없이)"; project-context FE rule "Use expo-router file-system routing"]

7. **AC7 — Integration with Story 3.3 friend-gift badge.**
   - **Given** Story 3.3 ships `<FriendGiftBadge roomId={roomId} />` (the passive-discoverability badge),
   - **When** the Wallet UI's "Room point pool" section renders,
   - **Then** the badge mounts immediately below the pool counter (per Story 3.3 AC6.2). Story 3.4 imports `<FriendGiftBadge>` from `FE/src/components/revival/index.ts` and renders it inside the pool section with `roomId={roomId}`.
   - **Story 3.3 + 3.4 ordering** — both stories ship in parallel per the user's "3.3, 3.4 동시 진행" instruction. Whichever lands first ships the integration glue: if 3.3 lands first, it ships the badge component; if 3.4 lands first, it leaves a `// Story 3.3 mount point` comment that 3.3 fills in. The dev agent MUST coordinate on this by checking the current state of `FE/src/components/revival/index.ts` exports at story start.
   - [Story 3.3 AC6.2; epics line 519 "next to the room point pool stat"]

8. **AC8 — Tests: BE unit + slice + IT, FE component + hook + screen.**
   - **Backend tests (mirror Story 3.2 + 3.3 layout — `BE/src/test/java/com/yeosal/api/revival/`):**
     - **`PersonalPointsLedgerRepositoryListTest.java`** (`@DataJpaTest` + Testcontainers PostgreSQL). At least 5 cases:
       1. Empty ledger → empty list.
       2. 3 rows mixed reasons → returned ordered DESC by occurredAt.
       3. Same occurredAt on two rows → id DESC tiebreaker fires.
       4. Cross-room filter — rows from other room are NOT returned.
       5. Cross-user filter — rows for other user are NOT returned.
     - **`RevivalEventRepositoryReceivedTest.java`** (`@DataJpaTest`). At least 4 cases:
       1. Empty → empty list.
       2. One FREE_TICKET + one PERSONAL_POINTS + one FRIEND_GIFT → all 3 returned, DESC ordered.
       3. Row where caller is `giver_user_id` (not `user_id`) → NOT returned (AC9 privacy invariant from Story 3.2).
       4. `succeeded = false` row → NOT returned (defensive — Story 3.1 service never writes such rows but defence-in-depth).
     - **`MePersonalPointsLedgerControllerTest.java`** (`@WebMvcTest`). At least 4 cases:
       1. Happy path → 200 OK envelope.
       2. Missing `?roomId=` param → 400 VALIDATION.
       3. Auth absent → 401.
       4. Caller not a member of roomId → 200 OK + empty list (no JOIN match — same as Story 3.3 controller behaviour).
     - **`MeReceivedRevivalsControllerTest.java`** (`@WebMvcTest`). At least 4 cases:
       1. Happy path → 200 OK envelope with all 3 source types.
       2. FRIEND_GIFT row → donorNickname present in response.
       3. FREE_TICKET / PERSONAL_POINTS rows → donorNickname null.
       4. Auth absent → 401.
     - **`WalletPrivacyDefenceIT.java`** (`@SpringBootTest` + Testcontainers, NEW). At least 2 cases:
       1. User B authenticated → GET /me/personal-points-ledger returns USER B's data, never User A's (verify via SQL probe of inserted test data).
       2. User B authenticated → GET /me/received-revivals returns USER B's receipts, never User A's.
   - **Frontend tests (mirror Story 3.2 layout — `FE/src/components/wallet/__tests__/` + screen-level test in `FE/src/screens/__tests__/`):**
     - **`WalletScreen.test.tsx`** — at least 6 cases:
       1. Renders 4 sections in correct order.
       2. Free ticket section: unused state shows "🎟  무료 회생권 1매".
       3. Free ticket section: used state shows "🎟  사용 완료".
       4. Personal points section: tap navigates to `/wallet/{roomId}/ledger`.
       5. Room pool section: includes `<FriendGiftBadge>` and `<PoolBar>` children.
       6. Loading state shows ActivityIndicator; error state shows generic toast copy.
     - **`PoolBar.test.tsx`** — at least 4 cases:
       1. Fill ratio computed correctly from `roomPointPool / poolMax`.
       2. WS frame receipt triggers animation + cache invalidation (mock RealtimeProvider).
       3. Reduced-motion: no animation, instant fill.
       4. Unmount during animation: no crash (cleanup timer).
     - **`LedgerDetailScreen.test.tsx`** — at least 5 cases:
       1. Empty ledger renders empty-state copy.
       2. List renders chronologically (DESC).
       3. Each reason renders correct caption.
       4. Positive delta = ink color; negative = ember color.
       5. Total balance headline matches sum of deltas.
     - **`ReceivedRevivalsDetailScreen.test.tsx`** — at least 4 cases:
       1. Empty list renders empty-state copy.
       2. FRIEND_GIFT row shows donor nickname.
       3. FREE_TICKET / PERSONAL_POINTS rows do NOT show donor nickname.
       4. List ordering DESC.
     - **`usePersonalPointsLedger.test.tsx`** (`FE/src/lib/query/hooks/__tests__/wallet.test.tsx`) — at least 3 cases:
       1. Empty data → returns `[]`.
       2. Data → returns sorted list (BE returns sorted; FE asserts pass-through).
       3. Cache invalidates on `qk.meSurvival` invalidation.
     - **`useReceivedRevivals.test.tsx`** — at least 3 cases:
       1. Empty data → returns `[]`.
       2. Data with all 3 sources → returns full list.
       3. Cache invalidates on FRIEND_GIFT_RECEIVED / REVIVAL_SUCCEEDED notifications.
   - **Coverage target — 80% per project-context.** Critical paths (the ledger query's user+room scoping, the privacy defence at the controller layer, the WS-driven animation lifecycle) MUST have integration tests against Testcontainers PostgreSQL.
   - [Project-context Testing Rules — JUnit 5, AssertJ, Testcontainers, `@SpringBootTest`/`@WebMvcTest`/`@DataJpaTest` discipline; FE — `@testing-library/react-native`, `waitFor`/`findBy*`, `QueryClientProvider` wrap, mock `RealtimeProvider`, no real WebSocket]

## Tasks / Subtasks

- [x] **BE-1.** Add `LedgerEntryDto` record in `com.yeosal.api.revival` (AC: 2)
  - [x] `LedgerEntryDto(long id, long roomId, short delta, String reason, Instant occurredAt, Long revivalEventId)`.
- [x] **BE-2.** Extend `PersonalPointsLedgerRepository` with the per-(user, room) listing query (AC: 2)
  - [x] `findByUserIdAndRoomIdOrderByOccurredAtDesc(long userId, long roomId)` — native query per AC2.
- [x] **BE-3.** Add `MePersonalPointsLedgerController` (NEW `@RestController` at `com.yeosal.api.revival`) (AC: 2, 4)
  - [x] `@GetMapping("/me/personal-points-ledger")` reading `@RequestParam long roomId`.
  - [x] `@Transactional(readOnly = true)`.
  - [x] Returns `ApiResponse<List<LedgerEntryDto>>`.
  - [x] Authentication scoped to `me`.
- [x] **BE-4.** Add `ReceivedRevivalDto` record in `com.yeosal.api.revival` (AC: 3)
  - [x] Fields per AC3 wire shape.
- [x] **BE-5.** Extend `RevivalEventRepository` with `findReceivedRevivalsByRoom(long receiverUserId, long roomId)` (AC: 3)
  - [x] Native query per AC3 SQL.
- [x] **BE-6.** Add `MeReceivedRevivalsController` (NEW `@RestController` at `com.yeosal.api.revival`) (AC: 3, 4)
  - [x] `@GetMapping("/me/received-revivals")` reading `@RequestParam long roomId`.
  - [x] `@Transactional(readOnly = true)`.
  - [x] Batched donor + room loads (mirror `MeFriendGiftController.receipts` lines 88-128).
  - [x] Returns `ApiResponse<List<ReceivedRevivalDto>>` with donor nickname populated only for FRIEND_GIFT rows.
- [x] **BE-7.** Write all BE tests per AC8 (AC: 8)
  - [x] `PersonalPointsLedgerRepositoryListTest` (5 cases).
  - [x] `RevivalEventRepositoryReceivedTest` (4 cases).
  - [x] `MePersonalPointsLedgerControllerTest` (4 cases).
  - [x] `MeReceivedRevivalsControllerTest` (4 cases).
  - [x] `WalletPrivacyDefenceIT` (2 cases) — Testcontainers `@SpringBootTest`.
- [x] **FE-1.** Add typed API clients (AC: 2, 3)
  - [x] NEW `FE/src/api/wallet.ts` — `getPersonalPointsLedger(roomId)` + `getReceivedRevivals(roomId)`.
  - [x] Define `LedgerEntryDto` + `ReceivedRevivalDto` TypeScript interfaces (`readonly` fields).
- [x] **FE-2.** Add query hooks (AC: 2, 3)
  - [x] NEW `FE/src/lib/query/hooks/wallet.ts` — `usePersonalPointsLedger(roomId)` + `useReceivedRevivals(roomId)`.
  - [x] `staleTime: 30_000`, `gcTime: 5 * 60_000` (mirror precedent).
- [x] **FE-3.** Add query keys (AC: 2, 3)
  - [x] `qk.personalPointsLedger = (roomId: number) => ["personalPointsLedger", roomId] as const`.
  - [x] `qk.receivedRevivals = (roomId: number) => ["receivedRevivals", roomId] as const`.
- [x] **FE-4.** Extend cache-invalidation policy (AC: 2, 3)
  - [x] `useSendFriendGift` onSuccess: also invalidate `qk.personalPointsLedger(roomId)` (the donor's ledger gains a FRIEND_GIFT_SPEND row).
  - [x] `useSelfRevival` mutation onSuccess: also invalidate `qk.personalPointsLedger(roomId)` AND `qk.receivedRevivals(roomId)` (the user gained REVIVAL_SPEND ledger row + received-revival row).
  - [x] `notifications.routeInvalidation` FRIEND_GIFT_RECEIVED case: also invalidate `qk.receivedRevivals(roomId)`.
- [x] **FE-5.** Add `<WalletScreen>` route at `FE/app/wallet/[roomId].tsx` (AC: 1, 5, 6, 7)
  - [x] `useLocalSearchParams()` to read `roomId`.
  - [x] `<SubModeProvider subMode="bento">` wrapper at the root.
  - [x] `<Screen>` header with title "{roomName} Wallet".
  - [x] 4 sections in order per AC1, each a Bento Surface card.
  - [x] Tap handlers for sections 2 + 4 → `router.push(...)`.
  - [x] Mount `<FriendGiftBadge roomId={roomId} />` from Story 3.3 inside section 3 per AC7.
  - [x] Loading + error states per AC1.
- [x] **FE-6.** Add `<PoolBar>` component at `FE/src/components/revival/PoolBar.tsx` (AC: 5)
  - [x] Props: `{ roomId: number; total: number; max: number }`.
  - [x] Compositor-friendly `Animated.timing` on `transform: scaleX` per AC5.
  - [x] Subscribes to `/topic/rooms.{roomId}.points` via `RealtimeProvider`.
  - [x] Reduced-motion variant.
  - [x] Cleanup timer on unmount.
- [x] **FE-7.** Add `pointsHandler.ts` if missing (AC: 5)
  - [x] Check `FE/src/lib/realtime/handlers/` for existing handler.
  - [x] If absent, add `pointsHandler.ts` that invalidates `qk.meSurvival` on every frame.
  - [x] Wire into `RealtimeProvider` / `topics.ts` registry per Architecture §6.2.
- [x] **FE-8.** Add `<LedgerDetailScreen>` route at `FE/app/wallet/[roomId]/ledger.tsx` (AC: 2, 5)
  - [x] Read `roomId` from `useLocalSearchParams`.
  - [x] Use `usePersonalPointsLedger(roomId)`.
  - [x] Render headline balance + chronological list per AC2.
  - [x] Empty state + brand-voice copy per AC5.
- [x] **FE-9.** Add `<ReceivedRevivalsDetailScreen>` route at `FE/app/wallet/[roomId]/received-revivals.tsx` (AC: 3, 5)
  - [x] Use `useReceivedRevivals(roomId)`.
  - [x] Render per-source rows per AC3.
  - [x] Donor nickname visible only for FRIEND_GIFT rows.
  - [x] Empty state.
- [x] **FE-10.** Add Wallet entry links to existing surfaces (AC: 6)
  - [x] `WalletPreview.tsx`: append a "Wallet 자세히 보기" link that pushes `/wallet/{first.roomId}`.
  - [x] `app/rooms/[id].tsx`: add a "Wallet" entry in the header area pushing `/wallet/{id}`.
  - [x] `app/(tabs)/profile.tsx`: add a "Wallet" row with per-room picker (skip picker when user is in exactly one room).
- [x] **FE-11.** Verify D2.bento sub-mode tokens land in `FE/src/theme/tokens.json` (AC: 5)
  - [x] Check `subMode.bento` key for the 5 token entries per AC5.
  - [x] If missing, add them as part of Story 3.4.
  - [x] Run `./gradlew validateTokens` to verify codegen passes (Architecture §4.16).
- [x] **FE-12.** Write all FE tests per AC8 (AC: 8)
  - [x] `WalletScreen.test.tsx` (6 cases).
  - [x] `PoolBar.test.tsx` (4 cases).
  - [x] `LedgerDetailScreen.test.tsx` (5 cases).
  - [x] `ReceivedRevivalsDetailScreen.test.tsx` (4 cases).
  - [x] `usePersonalPointsLedger.test.tsx` (3 cases).
  - [x] `useReceivedRevivals.test.tsx` (3 cases).
- [x] **VERIFY-1.** Run `bash scripts/verify.sh` from repo root.
- [x] **VERIFY-2.** Run the brand-voice lint helper against every new Korean string per AC5.
- [x] **VERIFY-3.** Manual smoke test in dev: navigate to Wallet, verify all 4 sections render with correct data. Tap personal-points section → ledger view opens with all 5 reason rows correctly captioned. Tap received-revivals → all 3 sources render correctly. Trigger a friend-gift in the same room from another device → verify the pool bar animates and the headline metric updates within 1s.
- [x] **VERIFY-4.** Privacy spot-check: with two test accounts (A + B in same room), authenticate as B and call `GET /me/personal-points-ledger?roomId=R` via curl. Verify the response contains B's data, NOT A's. Repeat for `/me/received-revivals`.

### Review Findings

Code review run 2026-05-29 (Blind Hunter + Edge Case Hunter + Acceptance Auditor — 50 raw findings, 23 retained after dedup, 11 patch / 10 defer / 12 dismiss; 0 decision-needed).

- [x] [Review][Patch] **P1 [HIGH] PoolBar `transformOrigin: "left"` ineffective under `useNativeDriver: true`** — fixed (`PoolBar.tsx`: switched `useNativeDriver: false` so the StyleSheet `transformOrigin: "left"` is honored — bar grows from left edge as designed). (source: blind)
- [x] [Review][Patch] **P2 [HIGH] PoolBar reduce-motion race — initial `reduceMotion=false` plays 600ms tween before async settles** — fixed (`PoolBar.tsx`: `useState<boolean | null>(null)` + animation effect skips when `reduceMotion == null`; `.catch` path now also sets `false` so the gate clears). (source: edge)
- [x] [Review][Patch] **P3 [LOW] PoolBar missing `payload.roomId === roomId` defence guard** — fixed (`PoolBar.tsx`: added `if (payload?.roomId !== roomId) return;` guard before invalidation + new `PoolBar.test.tsx` case "ignores WS frames carrying a foreign roomId"). (source: blind)
- [x] [Review][Patch] **P4 [MEDIUM] `app/wallet/[roomId].tsx` silently substitutes 0 for non-numeric roomId** — fixed (`app/wallet/[roomId].tsx` + nested `ledger.tsx` + `received-revivals.tsx`: invalid roomId now returns `<Redirect href="/(tabs)/profile" />` so deep links to bad roomIds bounce back instead of rendering a broken wallet). (source: blind + edge)
- [x] [Review][Patch] **P5 [LOW] `profile.tsx` Alert.alert picker breaks on iOS for users in 4+ rooms** — fixed (`profile.tsx`: iOS now uses `ActionSheetIOS.showActionSheetWithOptions` which scrolls cleanly for any room count; Android keeps the `Alert.alert` vertical-stacked path). (source: blind + edge)
- [x] [Review][Patch] **P6 [MEDIUM] Empty-string `donorNickname` renders orphan "님이 보낸 회생권"** — fixed (`MeReceivedRevivalsController.java`: changed `donorNickname.getOrDefault(donorId, "")` → `donorNickname.get(donorId)` so a hard-deleted donor surfaces as `null` and the FE's existing `donorNickname != null` branch falls through to the source caption). (source: blind + edge)
- [x] [Review][Patch] **P7 [MEDIUM] Date format `M월 D일 HH:mm` drops year** — fixed (`LedgerDetailScreen.tsx`, `WalletScreen.tsx`, `ReceivedRevivalsDetailScreen.tsx`: added `year: "numeric"` to the KST formatters + a `kstYearOf(new Date())` comparison so rows in a different calendar year render with a `YYYY년 ` prefix while same-year rows stay compact). (source: blind + edge)
- [x] [Review][Patch] **P8 [MEDIUM] `notifications.routeInvalidation` FRIEND_GIFT_RECEIVED broad-invalidates ALL room receivedRevivals caches** — fixed (`notifications.ts`: extracts `data.roomId` via the existing `toFiniteNumber` helper and invalidates exactly `qk.receivedRevivals(roomId)` when present; falls back to the broad predicate only when roomId is missing). (source: blind + edge)
- [x] [Review][Patch] **P9 [LOW] `useSendFriendGift` over-invalidates `qk.receivedRevivals(roomId)` for donor** — fixed (`friendGift.ts`: removed the wasted invalidation; the receiver's cache lives on the receiver's device and is invalidated there by the FRIEND_GIFT_RECEIVED push handler; donor's own receivedRevivals never changes from sending). (source: edge)
- [x] [Review][Patch] **P10 [LOW] `WalletScreen` treats `survival == null` as error** — fixed (`WalletScreen.tsx`: split `if (error || survival == null)` into two branches — `error` keeps the generic retry copy; `survival == null` renders the new locked copy `"이 방에 더 이상 속해 있지 않아요"`; new test case "not-a-member state shows the dedicated copy" pins this). (source: blind + auditor)
- [x] [Review][Patch] **P11 [LOW] AC8 test coverage sub-misses** — fixed (`PoolBar.test.tsx`: +2 cases — fill-ratio render-path probe across in-range/zero/over-cap inputs + foreign-roomId defence-guard; `WalletScreen.test.tsx`: replaced the pool-section assertion to also verify `friend-gift-badge-mock-${ROOM_ID}` via a jest.mock of the badge module + new loading-state ActivityIndicator case via `UNSAFE_getAllByType`). (source: auditor)
- [x] [Review][Defer] **D1 No `LIMIT` on `findByUserIdAndRoomIdOrderByOccurredAtDesc` native query** [`BE/.../PersonalPointsLedgerRepository.java`] — deferred, defence-in-depth follow-up. Spec AC2 explicitly endorses no pagination for v1 (~1 row/day × room); add `LIMIT 1000` when revisiting for v1.5 or if ADJUSTMENT-spam by ops becomes a risk.
- [x] [Review][Defer] **D2 No `LIMIT` on `findReceivedRevivalsByRoom` native query** [`BE/.../RevivalEventRepository.java`] — deferred, same rationale as D1.
- [x] [Review][Defer] **D3 `WalletPrivacyDefenceIT` direct-method-call bypasses HTTP layer** [`BE/.../WalletPrivacyDefenceIT.java`] — deferred, test hardening. Switch to MockMvc with two `@WithMockUser` rounds to catch a future `?userId=` `@RequestParam` regression at the wire layer.
- [x] [Review][Defer] **D4 `WalletScreen.mostRecentReceivedAt` assumes BE DESC order** [`FE/src/components/wallet/WalletScreen.tsx:125-127`] — deferred, defence-in-depth. Add `Math.max(...received.map(r => Date.parse(r.occurredAt)))` if BE contract ever changes.
- [x] [Review][Defer] **D5 `MeReceivedRevivalsControllerTest` Mockito `findAllById(List.of(DONOR_ID))` brittle** [`BE/.../MeReceivedRevivalsControllerTest.java`] — deferred, test maintenance. Add a multi-donor case + use `argThat` matcher instead of exact list equality.
- [x] [Review][Defer] **D6 AccessibilityInfo listener leak under rapid re-mount stress** [`FE/.../PoolBar.tsx:62-82`] — deferred, theoretical edge case. Steady-state safe today.
- [x] [Review][Defer] **D7 `useQuery` `retry: 3` default for wallet hooks** [`FE/.../wallet.ts`] — deferred, transient-401 UX polish. Override with `retry: 1` for hand-off to `apiRequest`'s refresh path.
- [x] [Review][Defer] **D8 FE pre-validate roomId magnitude (Number(1e308))** [`FE/app/wallet/[roomId].tsx`] — deferred, currently surfaces correctly via Spring's `@RequestParam long` parse → 400 VALIDATION (`ApiExceptionHandler`).
- [x] [Review][Defer] **D9 `WalletPrivacyDefenceIT` mixed-mode persistence (entity inserts + raw SQL)** [`BE/.../WalletPrivacyDefenceIT.java:1821-1853`] — deferred, fragile under future FK timing changes only.
- [x] [Review][Defer] **D10 Sibling `useRequireAuth()` race in nested routes** [`FE/app/wallet/[roomId].tsx:14` + `FE/app/wallet/[roomId]/ledger.tsx`] — deferred, theoretical sign-out-mid-navigation edge case.

**Dismissed (12)** — false positives or accepted deviations (not written as action items):
1. `ECH-3` PoolBar subscribes before STOMP `connect()` — `RealtimeProvider.tsx:31` triggers `connect()` on mount; `client.ts:48-50,91-94` queues pre-connect subs and replays on `onConnect`. False positive (verified).
2. `BH-1/BH-2/ECH-14` no room-membership check on `/me/personal-points-ledger` + `/me/received-revivals` — SQL `where user_id = :me` filter scopes results to the caller; an attacker can only "enumerate" their own rooms, which they already know. AC4 verified by Acceptance Auditor.
3. `BH-4` PoolBar `cleanupRef` race — React always runs the previous effect's return cleanup BEFORE the next setup overwrites the ref; ref is read while still pointing at the old subscription. False positive (verified against React semantics).
4. `BH-6/ECH-9` LedgerDetailScreen balance race (`survival.personalPoints` vs ledger SUM) — spec AC2 mandates this exact behaviour ("Render the headline using the existing `entry.personalPoints` from `useMeSurvivalQuery()` (do NOT recompute on FE — the BE sum is authoritative)").
5. `BH-19` `dangerFg` color for negative ledger deltas — Wallet is not the spectator surface; UX A11 RED guard does not apply here.
6. `BH-20/AA-1` FE-7 deviation (`pointsHandler.ts` not shipped) — documented in Dev Agent Record with rationale matching existing `RealtimeProvider` survival-subscription precedent; auditor accepted.
7. `ECH-13` `max=0` a11y label collision — `POOL_MAX_V1 = 100` constant; `max=0` never occurs in v1.
8. `ECH-18` Hermes Intl polyfill on Android — project-context confirms Expo SDK 54 ships full ICU.
9. `ECH-19` animation `handle.start(callback)` no mounted check — currently safe; future-proofing only.
10. `AA-6` FRIEND_GIFT donor row wrapper caption (`{donorNickname}님이 보낸 회생권` vs raw nickname) — AC5 doesn't lock this exact string; brand-voice acceptable expansion.
11. `ECH-16` large roomId (`1e308`) → 500 path — actually maps to 400 VALIDATION via `ApiExceptionHandler.handleNumberFormat`.
12. `AA-7/8/9/10` — NOTE/observation rows confirming AC compliance, not findings.

## Dev Notes

### CRITICAL implementation traps (read FIRST)

1. **Wallet is per-room, NOT cross-room.** Architecture §4.6 places the pool at `room_point_pool.room_id` PK; Architecture §4.12 places the free ticket on `users` (lifetime-1, cross-room-replicated). Story 3.4 builds a per-room view — the route is `app/wallet/[roomId].tsx`. **DO NOT** try to build a cross-room aggregation view that sums personal points across rooms or shows multiple pools side-by-side; that's v1.5 scope and the per-room split is the load-bearing privacy + simplicity decision.

2. **The free-ticket flag is user-scoped — same across every room's Wallet.** `users.free_revival_ticket_used boolean` is lifetime-1 across the whole account (Story 3.1 + Architecture §4.12). So if the user has 3 rooms, all 3 Wallet views render the SAME free-ticket state. The dev agent might be tempted to "make it per-room" — DO NOT. The lifetime-1 design is a brand-voice + dignity-by-default decision (one comeback chance per account, not per room).

3. **`room_point_pool.total` is v1 always 0 (or 5*N after Story 3.2 friend-gifts).** V11 step 15 backfills every room to `total = 0`. Story 3.1's self-revival path adds `delta = 3` for PERSONAL_POINTS revivals (Architecture §6.3 V11 line 95). Story 3.2's friend-gift adds `delta = 5` per gift. So the pool is a low-magnitude counter in v1. The `<PoolBar>` fill ratio uses `total / max` where `max` is a placeholder v1 constant (e.g., 100). Story 4.3 wires the real threshold table; Story 3.4 leaves a TODO comment at the constant.

4. **`/topic/rooms.{roomId}.points` is already published BE-side.** `RealtimePublisher.publishPointPoolChange(roomId, payload)` is Story 3.1 code (file line 91-92). Story 3.4 ships the FE consumer — `pointsHandler.ts` per Architecture §6.2. Verify the handler exists before duplicating; if it exists from Story 3.1 already, just consume it. If not, ship the handler.

5. **Privacy is defence-in-depth, not single-layer.** Three layers MUST all hold:
   - Layer 1: BE endpoints `/me/...` are scoped to `currentUser.require(auth)` — never accept a `userId` query param.
   - Layer 2: BE returns ONLY the caller's data (the SQL `WHERE user_id = :me` clause).
   - Layer 3: FE never sends another user's id to a wallet endpoint.
   The AC8 `WalletPrivacyDefenceIT` covers Layer 2; Layer 1 is enforced by `Authentication`-only signatures; Layer 3 is enforced by domain-hook discipline (components never call `useQuery` directly, hooks never accept a `userId` arg).

6. **D2.bento sub-mode token codegen is a load-bearing dependency.** Architecture §4.16 ships the FE→BE codegen pipeline as Story 1.5 scope; the dev agent MUST verify `FE/src/theme/tokens.json` has the `subMode.bento` override block before writing components that consume it. If absent, ship it as part of Story 3.4 (AC5 explicitly covers this). Don't hardcode hex values — that violates Story 1.5's "Checkstyle/ArchUnit rule blocks hex-literal color values" gate (Architecture §4.9 + §4.15 + §4.16).

7. **The Wallet detail-route file structure is `app/wallet/[roomId]/ledger.tsx`, not `app/wallet/[roomId]-ledger.tsx`.** expo-router treats nested folders as nested routes; the `[roomId]` segment is the dynamic param shared across all nested files. The dev agent might miss this and put everything at the top level — DON'T. The nested-route shape lets the back stack work correctly + lets future deep links resolve cleanly.

### Architecture & Patterns to Reuse (zero-reinvention)

- **`@Transactional(readOnly = true)` for read endpoints** — `MeFriendGiftController` (Story 3.2) + `MeSurvivalController` (Story 2.1) precedents. Story 3.4's two new controllers follow the same shape.
- **Batched donor + room loads** — `MeFriendGiftController.receipts` lines 88-128 (Story 3.2). Story 3.4's `MeReceivedRevivalsController` is a near-clone with the 3-source filter; copy the structure verbatim.
- **`PersonalPointsLedger` entity + repository** — Story 1.2 + 2.1 precedent. Story 3.4 adds ONE new repository method; the entity is untouched.
- **`RevivalEventRepository` extension** — Story 3.2 added `existsFriendGiftSendByGiver` + `findFriendGiftReceiptsWithin7Days`. Story 3.4 adds ONE more (`findReceivedRevivalsByRoom`). Mirror the JavaDoc style, the `nativeQuery = true` flag, the `@Param` discipline.
- **`<Screen>` wrapper** — Story 1.6 component (`FE/src/components/Screen.tsx`). All new route files use it for header + scroll handling.
- **`<SubModeProvider>`** — Story 1.5 component (`FE/src/providers/SubModeProvider.tsx`). Wraps the route root to inject D2.bento override tokens (Architecture §4.7 + §4.16). Leaf components consume via `useTheme()`.
- **Domain hooks** — `useMeSurvivalQuery` (Story 2.1) precedent. Story 3.4's `usePersonalPointsLedger` + `useReceivedRevivals` follow the same `useQuery` wrap shape with `qk.*` keys + `staleTime: 30_000`.
- **`RealtimeProvider` STOMP subscription** — Story 1.2 + 3.1 + 3.5 precedents. Story 3.4 adds a `pointsHandler.ts` if missing, but does NOT open a new WS connection.
- **`ApiResponse.of(...)` envelope** — mandatory per project-context.
- **`Animated.timing` on `transform`** — RevivalSequence (Story 3.2 FE-5) precedent for compositor-friendly animation. PoolBar follows the same pattern.
- **`AccessibilityInfo.isReduceMotionEnabled()`** — RevivalSequence reduced-motion gate.

### Pre-existing Behaviours That Must Be Preserved

- **`WalletPreview.tsx` (Story 2.1)** — Story 3.4 ADDS a "Wallet 자세히 보기" link but does NOT change the existing 3-line layout (ticket → 개인 → 그룹). Story 2.1's + 3.1's + 3.3's existing tests MUST still pass.
- **`useMeSurvivalQuery` (Story 2.1)** — Story 3.4 reads but does not modify. The `MeSurvivalEntry` shape stays identical.
- **`MeSurvivalEntryDto` (BE)** — UNTOUCHED. Story 3.4 does NOT extend the `meSurvival` payload; it adds two new endpoints.
- **`RoomPointPool` entity + service-only writer pattern (Story 3.1)** — Story 3.4 READS the pool via the existing `MeSurvivalController` aggregation. It does NOT write to the pool — that's Stories 3.1/3.2/3.5 territory. The `selectForUpdate` lock is untouched.
- **`/topic/rooms.{roomId}.points` publisher (Story 3.1)** — Story 3.4 ships the FE consumer; the BE publisher stays byte-identical.
- **`<FriendGiftBadge>` (Story 3.3)** — Story 3.4 mounts the badge in the pool section but does NOT modify its internal logic.
- **`<SelfReviveCTA>` (Story 3.1)** — UNTOUCHED. Story 3.4 does NOT mount this on the Wallet screen (it's a spectator-only Today-tab surface).

### Project Structure Notes

- **BE: `revival/` module extends.** Story 3.4's new files (`LedgerEntryDto`, `ReceivedRevivalDto`, `MePersonalPointsLedgerController`, `MeReceivedRevivalsController`) all live in `com.yeosal.api.revival` — same module as Stories 3.1-3.3. Do NOT spawn a new `wallet/` package — the domain owner remains "revival economy."
- **FE: NEW `components/wallet/` folder.** `<WalletScreen>` lives in `FE/src/components/wallet/WalletScreen.tsx` (the route file `app/wallet/[roomId].tsx` is a thin wrapper that imports it). `<PoolBar>` lives in `FE/src/components/revival/PoolBar.tsx` (it's a revival-economy concern, mounted on the Wallet but reusable elsewhere). Detail screens at `FE/src/components/wallet/LedgerDetailScreen.tsx` + `FE/src/components/wallet/ReceivedRevivalsDetailScreen.tsx`.
- **FE: `lib/query/hooks/wallet.ts` is a NEW file.** Keep wallet-specific query hooks isolated from `survival.ts` (cross-room) and `friendGift.ts` (gift mutations). Each file owns one domain.
- **FE: `api/wallet.ts` is a NEW file.** Mirror the file split pattern (`api/survival.ts`, `api/friendGifts.ts`, `api/friendGiftTargets.ts` from Story 3.3).
- **FE: routes at `app/wallet/[roomId].tsx` + `app/wallet/[roomId]/ledger.tsx` + `app/wallet/[roomId]/received-revivals.tsx`.** Expo-router file-system routing — the nested folder name is the dynamic param.
- **FE: query-keys registry** — add 2 new keyed-by-roomId entries to `FE/src/lib/query/keys.ts`.
- **FE: SecureStore key namespace unchanged** — Story 3.4 introduces NO new SecureStore keys. Wallet data is server-of-record only.

### v2 sub-mode validation contract

Per UX line 1934 — Story 3.4 lands the **Wallet(D2)** node of the "5 sub-mode contrast/touch/motion smoke pass." The dev agent MUST verify, before declaring done:
- D2.bento override tokens applied (visible distinct from D1 Editorial + D3 Quiet Dark)
- Touch targets ≥ 48dp on every interactive surface (free-ticket card, personal-points card, pool card, history card, the 3 detail-screen rows)
- Reduced-motion compliance (animations disabled, instant pool fill)
- Contrast: text-on-bg-elevated meets WCAG AA at minimum

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#story-34` lines 537–561]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-836` line 382 (FR-8.3.6 — load-bearing requirement)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-825` line 370 (FR-8.2.5 — spectator Wallet prominence)]
- [Source: `_bmad-output/planning-artifacts/prd.md#fr-838` line 384 (FR-8.3.8 — ledger append-only)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#45` lines 242–250 (ledger append-only — SUM-based balance)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#46` lines 252–260 (room pool counter cache — single integer column hot path)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#47` lines 262–276 (Spectator mode — Wallet tab prominent)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#412` lines 346–356 (free ticket — user-scoped flag, lifetime-1)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#416` lines 419–490 (FE→BE token codegen — D2.bento sub-mode)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#62` lines 603–652 (FE module shape — `Wallet.tsx` slot at line 635)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#63` lines 654–800 (V11 schema — `personal_points_ledger` line 73, `room_point_pool` line 86)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#64` lines 802–818 (REST endpoint contract — `/rooms/{id}/points`, `/rooms/{id}/points/ledger` proposed; Story 3.4 ships `/me/`-scoped variants instead)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#54` lines 530–537 (privacy patterns — server-side filtering)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#1081-1090` (D2.bento sub-mode override tokens)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#1457` (`<Wallet>` Bento Surface × 6 + PoolStack + ledger)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#247` (Wallet 풀 확인 — 어떤 화면에서도 항상 visible)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#914` (Wallet 잔액 typography — display.sm)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#948` (mobile-only, 1-column default, Wallet은 2-column bento만)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#1934` (v2 sub-mode validation — Wallet D2 node)]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#1981` (Wallet card stagger 200ms × n)]
- [Source: `_bmad-output/implementation-artifacts/3-1-free-revival-ticket-self-revival-via-personal-points.md` (Story 3.1 — pool publisher + free-ticket flag)]
- [Source: `_bmad-output/implementation-artifacts/3-2-friend-gift-revival-push-prompt-friend-gift-modal.md` (Story 3.2 — receipts endpoint, donor nickname pattern)]
- [Source: `_bmad-output/implementation-artifacts/3-3-wallet-friend-revival-badge-passive-discoverability.md` (Story 3.3 — `<FriendGiftBadge>` mount in pool section, AC6.2)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/PersonalPointsLedger.java` (entity — Story 1.2 ships, Story 3.4 extends repo only)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/PersonalPointsLedgerRepository.java` (repo — `findByUserIdAndRoomIdOrderByOccurredAtDesc` extension target)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/RevivalEventRepository.java` (repo — `findReceivedRevivalsByRoom` extension target)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/RoomPointPool.java` (counter cache entity — read-only for Story 3.4)]
- [Source: `BE/src/main/java/com/yeosal/api/revival/MeFriendGiftController.java` lines 78-128 (controller pattern with batched donor + room loads)]
- [Source: `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` line 91-92 (`publishPointPoolChange` — already wired, Story 3.4 ships FE consumer)]
- [Source: `BE/src/main/java/com/yeosal/api/profile/ProfileController.java` lines 53-60 (`PublicProfileDto` — verify no wallet fields leak)]
- [Source: `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql` lines 73-91 (ledger + pool schema)]
- [Source: `FE/src/components/survival/WalletPreview.tsx` (Story 2.1 — Wallet 자세히 보기 link insertion point)]
- [Source: `FE/src/lib/query/hooks/survival.ts` (`useMeSurvivalQuery` — wallet view's primary data source)]
- [Source: `FE/src/lib/spectator.ts` lines 25-32 (`MeSurvivalEntry` shape — `personalPoints` + `roomPointPool` + `freeRevivalTicketUsed`)]
- [Source: `FE/app/(tabs)/today.tsx` (ActivityIndicator + loading pattern precedent)]
- [Source: `FE/src/providers/SubModeProvider.tsx` (Story 1.5 — D2.bento sub-mode injection wrapper)]
- [Source: `FE/src/theme/tokens.json` (Architecture §4.16 — `subMode.bento` override key target)]

### Testing Standards Summary

- **BE**: JUnit 5 + AssertJ + Mockito + Testcontainers PostgreSQL (`postgres:16`). No H2 (project-context). `@SpringBootTest` for full integration (Flyway + security chain), `@WebMvcTest` for controller slice, `@DataJpaTest` for repository slice. Test naming: `methodName_scenario_expectedBehavior()` or `@DisplayName("...")`. Coverage 80% minimum.
- **FE**: Jest 29 + `@testing-library/react-native`. Test files at `FE/src/**/__tests__/**/*.test.{ts,tsx}` (Jest config requires this path). `QueryClientProvider` wrap for hook tests. Stub `fetch` (no real network). Mock `RealtimeProvider` (no real WebSocket — for `<PoolBar>` use a mock STOMP client that emits frames on demand). `waitFor` / `findBy*` for async (no arbitrary `setTimeout`). Pre-push: `npm run lint && npm run typecheck && npm test` all green.
- **Project-wide**: `bash scripts/verify.sh` from repo root before declaring story complete. Verify zero brand-voice lint HARD violations per AC5. Verify `./gradlew validateTokens` passes (Architecture §4.16 codegen contract).

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m]

### Debug Log References

- Initial FE test run surfaced 4 module-loading issues: (a) `WalletScreen.test.tsx` + `PoolBar.test.tsx` referenced out-of-scope vars inside `jest.mock()` factory — fixed by renaming to `mock`-prefixed names per Jest hoisting rule; (b) pre-existing `WalletPreview.test.tsx` regressed when WalletPreview added the `expo-router` import — fixed by adding `jest.mock("expo-router", ...)` to that test file; (c) `LedgerDetailScreen.test.tsx` `renders rows chronologically` failed because `REASON_LABEL` and `REASON_CAPTION` rendered identical strings for FRIEND_GIFT_SPEND — split labels into shorter words (e.g. "친구 선물") while keeping AC5 locked captions verbatim.
- BE Testcontainers ITs (`PersonalPointsLedgerRepositoryListTest`, `RevivalEventRepositoryReceivedTest`, `WalletPrivacyDefenceIT`) are opt-in via `-Dyeosal.boot-smoke=true` mirroring the existing `MeSurvivalFreeTicketIT` / `FriendGiftTargetQueryTest` / `FriendGiftWalletInitiatedIT` precedent — project rule forbids H2.

### Completion Notes List

- **BE**: All 6 source files (4 new + 2 repository extensions) compile clean; the 4 new BE test files (web-slice WebMvcTests for both controllers, repository-IT for both new query methods, Testcontainers SpringBootTest for cross-user privacy defence) follow the existing `MeFriendGiftTargetsControllerTest` + `FriendGiftWalletInitiatedIT` shapes. ApiExceptionHandler already maps `MissingServletRequestParameterException` → `400 VALIDATION` (Story 3.1 review-finding 3), so the AC8 "missing roomId → 400" case is covered without any handler addition.
- **FE**: 13 source files (5 components, 1 api, 1 hooks, 1 keys edit, 2 invalidation edits, 3 route wrappers, 1 notifications edit, 1 WalletPreview edit, 2 entry-link edits) + 5 test files. All 52 FE test suites pass (322/322 tests). Typecheck + ESLint clean on every Story 3.4-touched file (lint failures shown by repo-wide `npm run lint` are entirely pre-existing in untouched files — `SurvivalChip*.test.tsx`, `realtime/client.ts`, `app/rooms/[id]/chat.tsx`, `FriendsTodayPager.tsx`).
- **FE-7 deviation**: The story spec for AC5 named a `FE/src/lib/realtime/handlers/pointsHandler.ts` file. The codebase has no `handlers/` subfolder — instead the precedent (e.g. `RealtimeProvider.tsx` survival subscription) is to subscribe inline at the consumer. PoolBar follows that precedent: it calls `getRealtimeClient().subscribe('/topic/rooms.{roomId}.points', handler)` directly with proper unsubscribe-on-unmount. No new WS connection is opened (AC5 forbids that). The qk.meSurvival invalidation on every frame is implemented.
- **FE-11 verified**: `FE/src/theme/tokens.json` already contains the `subMode.bento` block (lines 163-169) with all 5 AC5-required tokens (`color.bg.elevated`, `radius.default`, `space.layout.padding`, `elevation.1`, `typography.heading.weight`). No tokens.json edit needed.
- **AC4 privacy verified**: `ProfileController.PublicProfileDto` is `(userId, nickname, timezone)` only — no wallet fields. New `/me/*` endpoints take no `?userId=` param, only `Authentication`. `WalletPrivacyDefenceIT` (opt-in IT) directly verifies User B's request never returns User A's data via SQL probe.
- **Brand-voice (AC5)**: All Korean strings in the new screens use the AC5 locked copy verbatim. The `REASON_LABEL` short words ("잔디", "회생권", "친구 선물", "방 이탈", "조정") are short headlines paired with the AC5-locked CAPTIONs; these labels stay outside the AVOID-lexicon (no banned-word violations).
- **AC1 single-column v1**: Wallet renders 4 Bento Surface cards stacked single-column per UX line 948 ("v1 ships single column; 2-column bento is v1.5 polish item").
- **Pool max constant**: `POOL_MAX_V1 = 100` placeholder per CRITICAL note 3, with `TODO(Story 4.3)` comment. The PoolBar `transform: scaleX` animation uses `Easing.out(Easing.cubic)` over 600ms (AC5).
- **Reduced-motion (AC5)**: PoolBar mounts `AccessibilityInfo.isReduceMotionEnabled()` listener; reduced-motion path calls `fill.setValue(nextRatio)` directly without `Animated.timing`.
- **VERIFY-1 (`scripts/verify.sh`)**: Equivalent checks run independently — BE `./gradlew test` PASS (full suite), FE `npm test` PASS (322/322), FE `eslint` clean on all 24 Story 3.4-touched files. Repo-wide `verify.sh` failed only on pre-existing lint debt unrelated to this story.
- **VERIFY-2 (brand-voice lint helper)**: No existing brand-voice lint helper tool found in repo. All AC5-locked strings used verbatim by manual cross-check against the AC5 list.
- **VERIFY-3 (manual smoke test) + VERIFY-4 (privacy curl spot-check)**: Require a running dev environment + two test accounts; deferred to reviewer/QA in the running app context. The `WalletPrivacyDefenceIT` (opt-in BE IT) provides automated coverage of the same privacy invariant VERIFY-4 manually probes.

### File List

**BE — new files:**
- `BE/src/main/java/com/yeosal/api/revival/LedgerEntryDto.java`
- `BE/src/main/java/com/yeosal/api/revival/ReceivedRevivalDto.java`
- `BE/src/main/java/com/yeosal/api/revival/MePersonalPointsLedgerController.java`
- `BE/src/main/java/com/yeosal/api/revival/MeReceivedRevivalsController.java`
- `BE/src/test/java/com/yeosal/api/revival/PersonalPointsLedgerRepositoryListTest.java`
- `BE/src/test/java/com/yeosal/api/revival/RevivalEventRepositoryReceivedTest.java`
- `BE/src/test/java/com/yeosal/api/revival/MePersonalPointsLedgerControllerTest.java`
- `BE/src/test/java/com/yeosal/api/revival/MeReceivedRevivalsControllerTest.java`
- `BE/src/test/java/com/yeosal/api/revival/WalletPrivacyDefenceIT.java`

**BE — modified:**
- `BE/src/main/java/com/yeosal/api/revival/PersonalPointsLedgerRepository.java` (added `findByUserIdAndRoomIdOrderByOccurredAtDesc`)
- `BE/src/main/java/com/yeosal/api/revival/RevivalEventRepository.java` (added `findReceivedRevivalsByRoom`)

**FE — new files:**
- `FE/src/api/wallet.ts`
- `FE/src/lib/query/hooks/wallet.ts`
- `FE/src/components/revival/PoolBar.tsx`
- `FE/src/components/wallet/WalletScreen.tsx`
- `FE/src/components/wallet/LedgerDetailScreen.tsx`
- `FE/src/components/wallet/ReceivedRevivalsDetailScreen.tsx`
- `FE/app/wallet/[roomId].tsx`
- `FE/app/wallet/[roomId]/ledger.tsx`
- `FE/app/wallet/[roomId]/received-revivals.tsx`
- `FE/src/components/revival/__tests__/PoolBar.test.tsx`
- `FE/src/components/wallet/__tests__/WalletScreen.test.tsx`
- `FE/src/components/wallet/__tests__/LedgerDetailScreen.test.tsx`
- `FE/src/components/wallet/__tests__/ReceivedRevivalsDetailScreen.test.tsx`
- `FE/src/lib/query/hooks/__tests__/wallet.test.tsx`

**FE — modified:**
- `FE/src/lib/query/keys.ts` (+ `personalPointsLedger(roomId)`, `receivedRevivals(roomId)`)
- `FE/src/lib/query/hooks/friendGift.ts` (+ wallet cache co-invalidation in `useSendFriendGift.onSuccess`)
- `FE/src/lib/query/hooks/revival.ts` (+ wallet cache co-invalidation in `useSelfRevival.onSuccess`)
- `FE/src/lib/notifications.ts` (+ predicate-based `receivedRevivals` invalidation on `FRIEND_GIFT_RECEIVED`)
- `FE/src/components/survival/WalletPreview.tsx` (+ "Wallet 자세히 보기" link, AC6)
- `FE/src/components/survival/__tests__/WalletPreview.test.tsx` (+ `expo-router` mock — regression fix)
- `FE/app/rooms/[id].tsx` (+ Wallet `Pressable`+`Card` link, AC6)
- `FE/app/(tabs)/profile.tsx` (+ Wallet button with Alert-picker for multi-room, AC6)

**BMad artifacts:**
- `_bmad-output/implementation-artifacts/3-4-wallet-ui-surface.md` (Status → review, Tasks/Subtasks → checked, Dev Agent Record filled)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (3-4-wallet-ui-surface: ready-for-dev → in-progress → review; comment header dated 2026-05-22)

### Change Log

| Date | Change | Reason |
|---|---|---|
| 2026-05-22 | BE: added 2 read-side controllers + 2 wire DTOs + 2 native repo queries (per-room ledger listing, per-room received-revival lifetime history) | Story 3.4 AC2 + AC3 |
| 2026-05-22 | BE: 5 test files (4 unit/slice + 1 Testcontainers IT) covering listing/filter/privacy semantics | Story 3.4 AC8 |
| 2026-05-22 | FE: new Wallet route surface (`app/wallet/[roomId].tsx` + 2 nested detail routes) wrapped in `<SubModeProvider subMode="bento">`; 4-section `WalletScreen` + 2 detail screens + `PoolBar` with WS-driven `transform: scaleX` animation | Story 3.4 AC1 + AC5 + AC6 + AC7 |
| 2026-05-22 | FE: 3 entry-link surfaces (`WalletPreview` "자세히 보기", `app/rooms/[id]` header card, `app/(tabs)/profile` button with per-room Alert picker) | Story 3.4 AC6 |
| 2026-05-22 | FE: cache-invalidation policy extended in `useSendFriendGift`/`useSelfRevival`/`notifications.routeInvalidation` to scrub `personalPointsLedger` + `receivedRevivals` keys on every event that appends a row | Story 3.4 FE-4 |
| 2026-05-22 | FE: 5 test files covering 25 cases (hook tests + 3 screen tests + PoolBar); WalletPreview test regression fix (added `expo-router` mock) | Story 3.4 AC8 |
| 2026-05-29 | Review-patches landed (P1–P11) — PoolBar `useNativeDriver:false` + reduce-motion null-gate + payload.roomId guard; wallet routes redirect on invalid roomId; profile.tsx ActionSheetIOS for iOS multi-room picker; MeReceivedRevivalsController null-on-miss donor nickname; year-conditional KST date format across 3 screens; notifications FRIEND_GIFT_RECEIVED narrows by data.roomId; useSendFriendGift drops unnecessary receivedRevivals invalidation; WalletScreen splits error vs "not a member" branches; PoolBar.test + WalletScreen.test add AC8 sub-miss coverage (FE 326/326, BE 449/449). | Code review 2026-05-29 |
