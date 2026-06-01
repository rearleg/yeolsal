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
