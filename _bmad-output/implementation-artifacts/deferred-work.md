# Deferred Review Work

Findings deferred during BMad code-review runs. Pick these up when the originating story is revisited, or batch them into a follow-up sprint when they accumulate.

## Deferred from: code review of 3-4-wallet-ui-surface (2026-05-29)

- **D1 [BE-MEDIUM]** Add `LIMIT 1000` to `PersonalPointsLedgerRepository.findByUserIdAndRoomIdOrderByOccurredAtDesc` — defence-in-depth against ADJUSTMENT-spam producing megabyte JSON responses. Spec AC2 explicitly endorses no pagination for v1 (~1 row/day × room budget).
- **D2 [BE-MEDIUM]** Add `LIMIT 1000` to `RevivalEventRepository.findReceivedRevivalsByRoom` — same rationale as D1.
- **D3 [BE-test]** `WalletPrivacyDefenceIT` calls controller methods directly with hand-constructed `UsernamePasswordAuthenticationToken`. Strengthen with MockMvc + two `@WithMockUser` rounds to catch a future `?userId=` `@RequestParam` regression at the wire layer (the current shape would still pass even if the param were added).
- **D4 [FE-LOW]** `WalletScreen.mostRecentReceivedAt = received[0].occurredAt` assumes BE DESC contract. Add `Math.max(...received.map(r => Date.parse(r.occurredAt)))` defence; cheap one-liner.
- **D5 [BE-test]** `MeReceivedRevivalsControllerTest` uses `when(users.findAllById(List.of(DONOR_ID)))` which is brittle to list ordering when >1 FRIEND_GIFT donor exists. Add a multi-donor case and use `argThat` matcher.
- **D6 [FE-LOW]** PoolBar `AccessibilityInfo.addEventListener` cleanup can race the resolved-promise setState under rapid re-mount stress. Steady-state safe.
- **D7 [FE-LOW]** Wallet `useQuery` hooks omit explicit `retry` — default 3 retries × backoff wastes ~7s on transient 401 mid-session. Override with `retry: 1` so `apiRequest`'s refresh path handles it once.
- **D8 [FE-LOW]** Wallet route accepts `Number("1e308")` (Infinity-adjacent magnitudes pass `Number.isFinite`). Currently handled by Spring `@RequestParam long` parse → 400 VALIDATION via `ApiExceptionHandler`. Could pre-validate magnitude on FE before sending.
- **D9 [BE-test]** `WalletPrivacyDefenceIT` mixes entity-managed inserts (`tx.executeWithoutResult`) with raw SQL (`jdbc.update`) inside the same `@Transactional` test method. Fragile under future FK timing changes (e.g., `INITIALLY DEFERRED` migrations). Pick one persistence mode.
- **D10 [FE-LOW]** `app/wallet/[roomId].tsx` and nested `ledger.tsx` / `received-revivals.tsx` each call `useRequireAuth()`. On sign-out mid-navigation, parent and child each trigger `router.replace` in the same tick. Theoretical race; no known reproduction.

## Deferred from: code review of 4-1-room-point-pool-counter-cache (2026-06-01)

- **FE verification baseline** Repair the pre-existing repository verification failures: `src/components/today/FriendsTodayPager.tsx` cannot resolve `react-native-pager-view` and has an implicit-`any` callback parameter; unrelated lint failures also remain outside Story 4.1 touched files.

## Deferred from: code review of 6-2-kakao-share-sdk-integration-deep-linking (2026-06-07)

- `RoomService.joinByCode` reads the member count and inserts membership without locking the room row. Two concurrent joins for the final slot can both pass the capacity check and exceed `max_members`. This predates Story 6.2's exception-type change.

## Deferred from: code review of 7-1-server-side-svg-poster-renderer (2026-06-07)

- **Zero-survivor fallback idempotency**: `FinalThreeService.generatePoster` can publish duplicate monthly no-survivor chat messages on repeated direct invocation, but the story explicitly assigns duplicate prevention to Story 7.2's caller pre-filter/replay contract.
- **Default IT coverage**: real Postgres/Batik checks are `yeosal.boot-smoke` opt-in and skipped by default verification; this matches the story's PR-CI gate and existing project precedent.

## Deferred from: post-merge audit of 7-1-server-side-svg-poster-renderer (2026-06-08)

- **`.github/workflows/be-it-boot-smoke.yml` `timeout-minutes: 30` exhaustion**: Both runs against PR #93 hit the 30-min cap and ended `cancelled` (run ID 27096680585, rerun included). The accumulated opt-in IT layer (`RoomControllerIT` + `SurvivalStateEvaluatorIT` + `SurvivalStateRosterIT` + `V11MigrationIT` + Story 7.1's new `FinalThreeServiceIT` + `SvgRendererTokenDiffIT` + other opt-in ITs added since Epic 1 retro action item T3) now exceeds the workflow timeout. PR #93 was squash-merged on the strength of the spec's explicit AC11 Gate 7/8 deferral allowance (line 1113-1115) + reviewer-accepted `deferred-work.md` entry #2 above + Story 5.4/6.1 precedent. **Follow-up options:** (1) bump `timeout-minutes` to 60; (2) split the IT layer across parallel jobs (matrix on test class) to keep wall-clock under 30 min; (3) move slow `@SpringBootTest` ITs to a nightly schedule instead of every-PR. Track this on the Epic 7 retrospective or open a separate `chore(ci):` PR before Story 7.2's IT lands.
