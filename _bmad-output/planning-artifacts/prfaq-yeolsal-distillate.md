---
title: 'PRFAQ Distillate: yeolsal (열살방)'
type: llm-distillate
source: 'prfaq-yeolsal.md'
created: '2026-05-10'
purpose: 'Token-efficient PRFAQ context for downstream PRD creation. Carries verdict findings (especially needs-more-heat and cracks) as actionable items, plus rejected framings, requirements signals, scope signals, and open questions surfaced through customer + internal FAQ stress-testing.'
---

# PRFAQ Distillate — yeolsal

## Verdict at a Glance

- **Status**: Forged with one heated edge to watch.
- **Survived gauntlet**: dignity-first design language is consistent end-to-end; effort-only v1 economy is a clean tradeoff that buys policy safety + sharpens validation; friend-revives-friend is load-bearing emotional moment with no surveyed competitor; existing-infra reuse means pivot-not-rewrite; KakaoTalk-native + Risograph identity = two hard-to-copy KR-native assets.
- **Cracks (top three risks)**:
  1. **Monetization gap** — payment-free v1 means the BM is *not validated until phase-2*. Strong qualitative WTP signal needed during v1 to de-risk this before phase-2 build.
  2. **Spectator-FOMO hypothesis** — every surveyed competitor locks eliminated users out and survives as a product, so the spectator lever may be ornamental rather than load-bearing. Instrument from day 1; kill if Day-7 spectator → revival conversion < 15%.
  3. **KR mental-model retraining** — 챌린저스 trained 1.71M people that "habit app = put money down, get it back". Onboarding script is a v1 design deliverable, not an afterthought.

## Rejected Framings — do not re-propose in PRD

- ❌ **Survival mechanic = single hard elimination per missed day.** Replaced with streak freeze (1/month) + 2-strike yellow→red on rolling 7-day window.
- ❌ **Buyable revival ticket as core BM in v1.** Deferred to phase-2; v1 has no payment surface at all.
- ❌ **챌린저스-style deposit-refund mechanic.** Banned across all phases; head-on collision with 1.71M-user incumbent + Apple gambling-classification surface + 챌린저스's own retention ceiling proves the model has limits.
- ❌ **Stickk-style forfeited-stake economy.** Rejected because forfeiture is anti-narrative ("I lost") rather than positive-sum.
- ❌ **Algorithmic auto-revival or cohort-wide revival.** Rejected because it removes the human-choice moment — friend's *choice* to spend points is the load-bearing emotion.
- ❌ **Multi-room membership in v1.** Rejected because "함께하고 싶다" weight dilutes across multiple groups; counter-intuitively, restricting to one room makes that room matter more.
- ❌ **Custom (non-preset) rule authoring in v1.** Presets only.
- ❌ **Public revival-count, money-spent, or rejection-count leaderboards.** Banned across all phases — shame engine.
- ❌ **Death icons on grass.** Banned — permanent stigma conflicts with dignity differentiation.
- ❌ **Pyramid-style "revive by inviting humans".** Banned — viral but ToS-unsafe.
- ❌ **Location-based todo verification.** Banned — surveillance.
- ❌ **Cash payouts to room leaders.** Banned — fraud risk + breaks dignity model.
- ❌ **Allowing eliminated users to pay to "stay in chat".** Banned all phases — predatory pattern.

## Requirements Signals (PRD inputs surfaced via FAQ stress-testing)

### Onboarding (v1 design deliverable)

- Onboarding script must crisply communicate "no money in v1, but real stakes" to defuse 챌린저스 mental model.
- Show free-revival-ticket presence in wallet immediately + explain "usable any time, even now".
- Visible 14-day grace trial countdown for new joiners.
- Explicit copy: "친구를 살리는 건 옵션이지 의무가 아닙니다" — defuse social-pressure liability.

### Friend-revives-friend mechanic detail

- Push notification copy: "수진이 회생을 기다리고 있어요" tone — invitation, not demand.
- One push only on receipt; no follow-up reminders if the friend ignores.
- Receiver-side push: "정민이 너의 회생권을 선물했어" — donor-named, default visible to receiver, optional system-message in chat (donor opts in to chat-broadcast).
- Post-revival "thank you" UX prompt for the receiver — captured but never broadcast.
- Revival rejection / non-action by friends is **never visible** to anyone but the giver.

### Spectator mode detail

- Read-only chat + roster + watching-others'-grass.
- Cannot post messages; chat input is disabled at FE + chat-write API rejects at service layer.
- Push notifications during spectator mode: yes but downgraded frequency (PRD detail: probably once/day digest, not realtime).
- Spectator → revival conversion is the most-tracked KPI from launch day.

### Anti-shame guardrails (must encode)

- Soft-public elimination: 24-hour broad-visibility cooldown after red card. Timestamp = `survival_state.broad_visibility_at`.
- Eliminated user's archive: defaults private; opt-in to share with room.
- Quiet-hours respected for all push notifications (per existing `notification_prefs.quiet_*_hour`).
- Account deletion: PIPA + Apple/Google compliant export-and-delete; no automatic broadcast to friends/room.
- In-app "I feel pressured" report surface (1 button) for users who feel social pressure off-app.

### Incident response playbook (v1 must-have)

- Sentry telemetry: alert on mass-elimination events (>50% of room red-cards in 24h).
- 24-hour in-app sanity check on first incident.
- ToS-attached abuse-reporting flow.
- Worst-case recovery: room-wide formal apology + group point pool +30 (or similar) gesture for the affected room.

## Scope Signals — what's IN vs OUT vs MAYBE for v1

### IN (locked from PRFAQ + brief)

- Single mandatory room at signup with **14-day grace trial**.
- Room cap: **default 12, settable up to 30** by room creator.
- Daily-update rule + weekend-include toggle (only configurable rule dimension).
- 06:00 KST day boundary (already in V2 + `EntryDateResolver`).
- **Streak freeze 1/calendar-month** (auto-applied on missed day if not yet used this month).
- **2-strike yellow→red** on rolling 7-day window.
- Soft-public elimination with 24-hour broad-visibility cooldown.
- Read-only spectator mode for eliminated users.
- **Free first revival ticket usable immediately** at signup (no grace gating).
- Personal-points revival (earned by surviving) after free ticket.
- **Friend → friend revival gift** using giver's personal points.
- Group point pool — accumulates and is visible, **does not redeem in v1**.
- Themed rooms (운동방 / 공부방 / 글쓰기방) with rule presets.
- Monthly Final-3 Risograph poster ceremony (server-rendered SVG + Kakao share).
- Group leader role (room creator) with rule-change cooldown — changes apply next month only.
- Record visibility on elimination defaults to private; opt-in to share with room.
- **KakaoTalk SDK integration for invite-codes** (share link, room-preview snippet).
- Kakao SDK extension of existing OAuth integration (no new auth review).
- Existing yeolsal infra reused: rooms, room_members, invite codes, friends, daily_entries, reflections, chat, push, JWT, STOMP fan-out.
- v1 brand-voice guardrail (use: 함께/선물/응원/컴백/회생/그룹/우리/살리다; avoid: 벌금/잃었다/실패/패배/자책/부담).

### OUT of v1

- Any payment surface: no IAP, no PG, no buyable revival ticket, no cosmetic IAP.
- Gifticon redemption catalog (room point pool accumulates without conversion).
- Multi-room membership.
- Custom (non-preset) rule authoring.
- B2B / company onboarding vertical.
- Sobriety / rehab vertical.
- Live "co-working" voice room.
- Rule template marketplace.
- Sponsor pairing inside rooms.
- International localization (international fork = v3 conversation).
- Real-money cash-out of any kind.

### MAYBE in v1 (PRD decision required)

- 30-member-room Final-3 ceremony semantics — does "Final-3" still mean top 3 of 30, or scale to "Final-N where N = ceil(roomCap/4)"? Designer call.
- In-app "I feel pressured" report surface — almost certainly v1 but exact UX placement TBD.
- Kakao SDK choice: lightweight Share SDK only vs full Kakao Login + Sharing (existing OAuth path). Likely the latter — extend existing dependency. PRD locks.

## Open Questions (PRD must resolve before architecture lock)

1. **Personal-points formula** — Brief proposal: 2/surviving day, 5/friend-gift revival, 3/personal revival ⇒ revival in ~7 surviving days. Validate with cohort test.
2. **Free revival ticket re-grant policy** — Current decision: lifetime 1. Confirm via usability research.
3. **Room leader elimination handling** — Auto-promote longest-tenured surviving member? Member vote? Room shutdown? Default policy needed.
4. **Group point pool decay or floor** — When phase-2 ships, does a room with 200 pool points redeem at-cost or with a cap?
5. **Personal points on leaving a room** — Decision: per-room scoped, forfeit on leave (no inter-room farming). Document explicitly in PRD.
6. **Final-3 poster generation** — Server-side SVG render preferred (consistent fidelity + cacheable + ASO-indexable). Confirm before architecture stage.
7. **Spectator-mode push frequency** — Down-shifted from active-member frequency. Probably 1/day digest, not realtime. PRD detail.
8. **"Weekend-include off" semantics under 06:00 KST** — Decision proposal: day-boundary owns the answer; Sunday 05:30 is Saturday, so it counts. Document in BE service-layer comment.
9. **Eliminated user's chat location** — Decision proposal: same room channel with `survival_state.status = SPECTATOR` flag making the input UI read-only and chat-write API rejecting at service layer. Avoids parallel "ghost room" complexity.
10. **30-member-cap room behaviors** — chat density, Final-3 ceremony semantics, friend-gift discoverability all need PRD design pass.

## Technical Context (for PRD architecture stage)

### Stack reuse (no rewrite)

- BE: Spring Boot 3.3.5, Java 21, Postgres + Flyway V1–V10, JJWT 0.12.6, STOMP via `WebSocketConfig` + `JwtChannelInterceptor`, JPA `validate`, OPEN-IN-VIEW=false.
- FE: Expo SDK 54, RN 0.81, React 19.1, TS 5.9 strict, expo-router 6, TanStack Query 5 with AsyncStorage persist, @stomp/stompjs 7.3, expo-secure-store, @sentry/react-native, jest-expo. Path alias `@/*` → `src/*`.
- API context-path: `/yeolsal` is auto-prefixed; controllers declare `/api/v1/...` only.
- Response envelope: `ApiResponse<T>` (`{ data: T }`); errors via single `ApiExceptionHandler @RestControllerAdvice`.
- App ID: `app.yeosal.mobile`. Native-module additions require `adb uninstall` + rebuild — affects Kakao SDK shipping by ~1–2 weeks.

### Data model deltas (on top of V1–V10)

- **V11+ migration**: extend `rooms.max_members` allowed range to BETWEEN 2 AND 30, default 12 (currently default 8).
- **New tables (rough shape)**:
  - `streak_freezes (room_id, user_id, applied_date, month)` — partial unique on `(user_id, month)`.
  - `survival_state (room_id, user_id, status: ACTIVE|YELLOW|RED|SPECTATOR, last_state_change_at, eliminated_at, broad_visibility_at)`.
  - `revival_events (id, room_id, user_id, source: FREE_TICKET|PERSONAL_POINTS|FRIEND_GIFT, giver_user_id NULL, points_spent, occurred_at)`.
  - `personal_points_ledger (user_id, room_id, delta, reason: SURVIVAL|REVIVAL_SPEND|FRIEND_GIFT_SPEND, occurred_at)` — append-only.
  - `room_point_pool (room_id, total, last_event_at)` — sum cache.
  - `room_rule_versions (room_id, effective_from_month, rule_payload jsonb, created_by_user_id, created_at)` — supports next-month-only rule changes.
  - `record_visibility_prefs (user_id, room_id, share_on_elimination bool)` — defaults false.
- V8/V9 milestone-dedup pattern (partial unique on `(room_id, payload->>'userId', payload->>'month')` for `kind='MILESTONE'`) is the reference for any new partial-unique constraint.

### Realtime topic additions (STOMP)

- `/topic/rooms/{roomId}/survival` — status changes (yellow/red/revived/spectator-entered).
- `/topic/rooms/{roomId}/points` — pool delta events.
- `/user/queue/friend-gifts` — incoming friend-gift revival prompts.
- All emissions through `RealtimePublisher` (do not inject `SimpMessagingTemplate` directly).

### Frontend surfaces

- Spectator mode = routing branch in `app/(tabs)/_layout.tsx` keyed on `survival_state.status`.
- Friend-gift revival flow: new modal screen + push notification on receipt (use `expo-notifications` already wired).
- Room-creation flow: `max_members` picker (12 default, 30 max).
- KakaoTalk share via Kakao SDK (extend existing OAuth integration; native module).
- Final-3 poster: server-rendered SVG using existing tokens; FE shows + offers Kakao share.

## Competitive Intelligence (preserved from market research)

- **Squad** (joinsquad.co): 8-member cap (matched yeolsal's V3), 10–30 day cohorts, automated check-ins, subscription BM, **no Korean presence**. Closest structural competitor.
- **Habitica**: RPG quest framing + party/boss-fight, ~30 cap. **+39% retention, +51% social-engagement after group challenges (mid-2023, Gen Z)**. Closest psychological competitor.
- **Stickk**: $51M users-money on the line, 533K commitments. Solo-first commitment device. Proves stake-based motivation works at scale, but never produced a beloved consumer brand.
- **Beeminder**: Solo, quant-driven, pay-on-fail. Not socially motivated.
- **챌린저스 (KR)**: Deposit-refund. **1.71M users (Mar 2024), ₩15B 2024 revenue, first profit in 2023**. Pivoted to beauty-discount marketplace in 2023 — strong evidence pure habit-formation has a retention ceiling without redemption.
- **Duolingo (lateral)**: streaks + loss aversion locks in ~day 7. **Streak freeze** + gem economy = canonical example of monetizing emotion non-extractively. **DAU 16M (2021) → 30M (2023), 4.5×; +21% retention** attributed to this loop.
- **토스 만보기**: Step → chip → tiny reward. Massive distribution leverage but no group survival mechanic.
- **KakaoTalk 단톡방 인증 culture**: free baseline; the substrate the pivot competes against. Pivot's job is to formalize what 단톡방 does informally.

## Resource & Timeline Estimates

- **MVP build**: ~8 weeks (pivot is reframe over existing infra, not rewrite). Estimate covers V11+ migration, ~7 new tables, 3 new STOMP topics, spectator-mode FE branch, friend-gift flow, KakaoTalk SDK extension, Final-3 SVG generator, brand-voice copy pass.
- **KakaoTalk SDK shipping cost**: 1–2 weeks (SDK itself ~1 week, but native-module rebuild discipline adds dev cycle overhead).
- **Phase-2 trigger window**: Day 60 post-launch.
- **International expansion**: v3 conversation; KR-deep first 24 months minimum.
- **Cold-start budget**: first 100 users from existing yeolsal base + friend graph + 3 backup channels (1기-seed launch, 1 KR creator partnership, dev community announcement). PRD defines per-channel experiment plan.

## KPIs (canonical set, from PRFAQ + brief)

- **Activation**: ≥60% of new users complete first room-join + first daily entry within 24h.
- **Day-7 retention**: ≥45% of room members still posting daily on day 7.
- **Day-30 cohort survival**: ≥25% of rooms still active at day 30 with majority of original members (hypothesis — to validate against Habitica's 39% retention lift baseline).
- **Free-ticket revival rate**: ≥35% of eliminated users use their free ticket within 7 days.
- **Friend-gift revival usage**: ≥1 friend-gift revival per active room per month (load-bearing for "함께하고 싶다" hypothesis).
- **Personal-points revival**: ≥15% of post-free-ticket revivals use personal points (not friend-gift).
- **Kakao-share invite acceptance**: ≥30% of invite links shared via Kakao SDK convert to a joined member.
- **Room point pool growth**: average active room reaches ≥50 pool points by day 30 (leading indicator for phase-2 redemption BM fuel).
- **App-store policy review**: ships in Apple/Google KR storefronts on first submission, no rating escalation.
- **Qualitative**: users describe experience with "함께/선물/응원" tone, not "벌금/잃었다".

## Phase-2 Trigger Gates (all four must be true at Day 60)

1. Day-7 retention ≥45%.
2. Friend-gift revival ≥1 per active room per month.
3. Average room point pool ≥50 by day 30.
4. App store reviews / Sentry telemetry show no shame-event pattern.

If any single gate misses by a small margin, run a targeted phase-1.5 sprint of 30 days on that single metric. Do not enter phase-2 with all four green and do not skip phase-2 indefinitely.

## ASO / Store Metadata Notes

- KR copy: "회생권" stays as in-app term (culturally on-point).
- English store metadata: prefer "comeback pass" over "revival ticket" / "second chance pass" — "revival ticket" surfaces gambling adjacency in automated content scans.
- Product title in KR store: 열살방 — 친구와 함께 살아남는 매일 약속 / 친구 그룹 챌린지 (or similar; full store copy is a v1 design deliverable).

---

This distillate is intended as direct input for `/bmad-create-prd`. Feed it together with `prfaq-yeolsal.md`, `product-brief-yeolsal.md`, `docs/index.md`, and `_bmad-output/project-context.md`. The PRFAQ + this distillate together replace the product brief in the planning pipeline going forward.
