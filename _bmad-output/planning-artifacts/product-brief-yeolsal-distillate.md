---
title: 'Product Brief Distillate: yeolsal (열살방)'
type: llm-distillate
source: 'product-brief-yeolsal.md'
created: '2026-05-10'
purpose: 'Token-efficient context for downstream PRD creation. Captures detail beyond the 1-2 page executive brief — locked decisions, rejected ideas, requirements hints, technical context, scope signals, and open questions.'
---

# Product Brief Distillate — yeolsal

## Locked Decisions (canonical for PRD)

- **Room cap**: default 12, settable up to 30 by room creator at room creation. Schema impact: V3's `rooms.max_members` migration must allow `BETWEEN 2 AND 30`; current default is 8.
- **No payment in v1**: zero IAP, zero PG, zero Apple gambling-classification surface. The economic loop is points-only.
- **Free first revival ticket**: granted at signup, **usable immediately** (no grace gating). One per account, lifetime.
- **Friend → friend revival gift**: in v1. The giver spends their personal points; the receiver re-enters. This is the load-bearing emotional moment of the pivot.
- **KakaoTalk SDK invite**: in v1. Invite-codes propagate as Kakao share links with a generated room-preview card.
- **Group point pool**: accumulates from every revival spend (personal or gifted), is visible in the room UI, **does not redeem in v1**. Phase-2 ships gifticon catalog.
- **Gifticon catalog / cosmetic IAP**: deferred to phase-2.
- **International expansion**: v3 conversation; KR-deep first 24 months minimum.
- **Children under 19**: payment-free v1 sidesteps gambling-age question. Revisit when phase-2 monetization lands.

## Mechanic Detail (beyond exec summary)

- **Day boundary**: 06:00 KST (yeolsal v2 migration already encodes this in `daily_entries.entry_date` + `EntryDateResolver`). Weekend-include is the only configurable rule dimension at MVP.
- **Streak freeze**: 1 per calendar month, free, non-stackable. Auto-applied for the missed day if the user hasn't already used this month's freeze.
- **Two-strike yellow→red**: rolling 7-day window. First miss in window = yellow card (warning + nudge). Second miss in window = red card (eliminated).
- **Soft-public elimination**: red-card status visible to the user + room leader immediately; visible to the broader room only after a 24-hour cooldown.
- **Spectator mode**: read-only chat + roster view + watching-others'-grass for eliminated users. Cannot post, cannot react publicly. The FOMO is the engine.
- **Record visibility on elimination**: defaults to **private to the eliminated user**. Opt-in toggle to share archive with the room.
- **Group leader role**: room creator is leader by default. Leader can change room rules, kick, transfer leadership. **Rule changes apply only to the next month** — current cycle's contract is locked. Member-cap changes also apply next month only.
- **Personal points**: earned per surviving day (formula TBD in PRD; should round-trip enough that revival is achievable in 2–4 weeks of clean survival).
- **Group point pool**: incremented by N points each time someone (anyone) revives in that room.
- **Final-3 monthly ceremony**: at month-end, the 3 highest-tenure surviving members get a Risograph-style poster card with their names + room name + month. Shareable artifact.

## Rejected Ideas — do not re-propose

- ❌ **Deposit-and-refund mechanic** — that is 챌린저스's home turf; entering invites policy review friction + head-on competition with a 1.71M-user incumbent + the same retention ceiling that forced 챌린저스 to pivot to a beauty marketplace.
- ❌ **Permanent failure / no-revival** — too brittle; survival narrative collapses without a recovery path.
- ❌ **Death icons or failure flair on the user's grass** — permanent stigma; conflicts with the dignity differentiator.
- ❌ **Pyramid-style "revive by inviting humans"** — ToS-unsafe, gross, would erode trust.
- ❌ **Random / variable-price revival ticket** — gambling-classification trip in KR/iOS reviews. (Moot in v1 since no payment, but banned in all phases.)
- ❌ **Streak-length-scaled revival pricing** (more expensive for veterans) — hostile to long-tenured users + gambling adjacency.
- ❌ **Location-based todo verification** — surveillance; never.
- ❌ **Cash payouts to room leaders** — fraud risk, breaks the dignity model.
- ❌ **Public revival-count or money-spent leaderboards** — shame engine.
- ❌ **Single rich user buying infinite revivals** — moot in v1; banned in all phases via per-week purchase cap when payment lands.
- ❌ **Letting eliminated users pay to "stay in chat"** — predatory; chat access is independent of survival forever.
- ❌ **Multi-room membership in v1** — single primary only at MVP.
- ❌ **Custom (non-preset) rule authoring in v1** — presets only.
- ❌ **Sobriety / rehab vertical in v1** — real fit, but heavy ToS/safety surface; defer.

## Requirements Hints (PRD detail)

### Data model deltas (on top of existing yeolsal V1–V10)

- `rooms.max_members`: extend allowed values from 8 → up to 30. Default 12. Migration version V11+ likely.
- New tables (rough shape):
  - `streak_freezes (room_id, user_id, applied_date, month)` — one row per use, partial unique on `(user_id, month)`.
  - `survival_state (room_id, user_id, status: ACTIVE|YELLOW|RED|SPECTATOR, last_state_change_at, eliminated_at)` — derived state but worth materializing for query simplicity.
  - `revival_events (id, room_id, user_id, source: FREE_TICKET|PERSONAL_POINTS|FRIEND_GIFT, giver_user_id NULL, points_spent, occurred_at)`.
  - `personal_points_ledger (user_id, room_id, delta, reason: SURVIVAL|REVIVAL_SPEND|FRIEND_GIFT_SPEND, occurred_at)` — append-only.
  - `room_point_pool (room_id, total, last_event_at)` — sum cache; could be derived but materialized for FE display.
  - `room_rule_versions (room_id, effective_from_month, rule_payload jsonb, created_by_user_id, created_at)` — supports "rule changes apply next month only".
  - `record_visibility_prefs (user_id, room_id, share_on_elimination bool)` — defaults to false.
- Existing `daily_entries`, `reflections`, `todo_items`, `chat_messages` reused as-is.
- V8/V9 milestone-dedup pattern (partial unique on `(room_id, payload->>'userId', payload->>'month')` for `kind='MILESTONE'`) is the reference pattern for any new partial-unique constraint.

### Realtime topic additions (STOMP)

- `/topic/rooms/{roomId}/survival` — status changes (yellow/red/revived/spectator-entered).
- `/topic/rooms/{roomId}/points` — pool delta events.
- `/user/queue/friend-gifts` — incoming friend-gift revival prompts.
- All emissions through `RealtimePublisher` (single point per BE convention; do not inject `SimpMessagingTemplate` directly).

### Frontend surfaces (FE)

- Spectator mode = a routing branch in `app/(tabs)/_layout.tsx` based on `survival_state.status`.
- Friend-gift revival flow: a new modal screen + push notification on receipt (use `expo-notifications` already wired).
- Room-creation flow needs a `max_members` picker (12 default, 30 max).
- KakaoTalk share via Kakao SDK (new dependency; native module — requires `adb uninstall` + rebuild per project-context.md rule).
- Final-3 poster: server-rendered SVG using existing design tokens (ink/paper/pink/green/acid); FE shows + offers Kakao share.

### Behavioral / dignity rules (must encode in code)

- Soft-public elimination 24-hour broad-visibility cooldown — implement as a `survival_state.broad_visibility_at` timestamp.
- Streak freeze per calendar month, not rolling 30 days.
- Rule changes lock current month — query layer must always read the rule version effective for the *current* month-of-evaluation, not the latest.
- Free revival ticket usable immediately — no grace-period gating.
- All log/notification text must read "comeback / 응원 / 함께" tone, never "벌금 / 잃었다 / 패배".

## Technical Context

- **Existing stack** (per `_bmad-output/project-context.md`): Spring Boot 3.3.5, Java 21, Postgres + Flyway V1–V10, JJWT 0.12.6, STOMP via `WebSocketConfig` + `JwtChannelInterceptor`, JPA `validate`, OPEN-IN-VIEW=false; Expo SDK 54, RN 0.81, React 19.1, TS 5.9 strict, expo-router 6, TanStack Query 5 with AsyncStorage persist, @stomp/stompjs 7.3, expo-secure-store, @sentry/react-native, jest-expo. Path alias `@/*` → `src/*`.
- **API context-path**: `/yeolsal` is auto-prefixed; controllers declare `/api/v1/...` only.
- **Response envelope**: `ApiResponse<T>` (`{ data: T }`); errors via single `ApiExceptionHandler @RestControllerAdvice` with codes `BAD_REQUEST | VALIDATION | UNAUTHORIZED | FORBIDDEN | NOT_FOUND | INTERNAL_ERROR`.
- **App ID**: `app.yeosal.mobile`. Native-module changes require `adb uninstall` then rebuild — Metro reload alone does not pick up native dependencies (impacts Kakao SDK addition).
- **Design system** (Risograph + neobrutalist): tokens `ink #090909`, `paper #F8F3E7`, `pink #FF2FA3`, `green #39FF4A`, `acid #DFFF00`, `muted #9C988C`. 3–4px black borders, 5–7px hard-offset shadows. Re-used for Final-3 poster generation.
- **Sentry**: auto-disabled when `EXPO_PUBLIC_SENTRY_DSN` is empty; import via `src/lib/sentry.ts` only. Production survival-state changes worth instrumenting (especially elimination → spectator transitions).
- **Auth**: JWT Bearer for REST + JWT validated at STOMP `CONNECT` frame. Refresh-token rotation already wired.
- **Payment-free v1** removes the entire IAP / PG / store-policy review surface that would otherwise be the project's biggest risk. Means **no `expo-iap`, no Toss Payments, no `/api/v1/payments/*` endpoints in v1**.

## Detailed User Scenarios

### S1 — Eliminated user re-engages

> 민지 misses Tuesday (yellow). Misses Saturday (red). Sunday morning at 09:00, soft-public banner appears in her room — but only her and 그룹장 진수 see it for 24 hours. Monday morning she opens the app, sees her room's chat scrolling, sees the point pool sitting at 23 points. She has 1 free revival ticket. She uses it. Banner disappears for everyone. The room's pool gets +5 points. She survives the next week and earns 2 personal points herself. The free ticket → first taste of the loop.

### S2 — Friend revives friend (the load-bearing moment)

> 정민 has 12 personal points. His best friend 수진 in the same room got eliminated yesterday. Push notification: "수진이 회생을 기다리고 있어요." 정민 taps it; he can spend 5 of his 12 points to revive her. He does. 수진 gets a push: "정민이 너의 회생권을 선물했어." Room chat shows a single tasteful system message ("정민 → 수진"). Room point pool +5. Both of them remember this moment. This is the conversion to "함께하고 싶다" we are betting on.

### S3 — Room hits day-30 ceremony

> 운동방-3월기 reaches day 30 with 7 of original 9 members surviving. App generates a Risograph poster card listing 진수 / 민지 / 수진 / 도현 / 보경 / 영호 / 다은 + room name + 'Final-3 진수·민지·수진'. Each member's app shows the poster on the home tab; share-to-Kakao button shares it as a Kakao card with the room's invite code embedded. New users who tap the card see the room preview snippet and can join with one tap.

### S4 — Cold-start friend invites a friend

> 진수 (existing yeolsal user) creates 운동방. Default cap 12. Sets weekend-include off. Sends Kakao share link into his 친구 단톡방. 5 friends tap; 4 join (1 abandons at signup). All 4 see the rule notice ("매일 인증 / 주말 제외, 14일 grace") and accept. Day 0 starts.

## Competitive Intelligence (preserved from market research)

- **Squad** (joinsquad.co): 8-member cap (matched yeolsal's V3), 10–30 day cohorts, automated check-ins, subscription BM, **no Korean presence**. Closest structural competitor.
- **Habitica**: RPG quest framing + party/boss-fight, ~30 cap. **+39% retention, +51% social-engagement after adding group challenges (mid-2023, Gen Z)**. Closest psychological competitor.
- **Stickk**: $51M users-money on the line, 533K commitments. Solo-first commitment device. Proves stake-based motivation works at scale, but never produced a beloved consumer brand → narrative-thinness is an instructive failure mode.
- **Beeminder**: Solo, quant-driven, pay-on-fail. Not socially motivated.
- **챌린저스 (KR)**: Deposit-refund. **1.71M users (Mar 2024), ₩15B 2024 revenue, first profit in 2023**. Pivoted to beauty-discount marketplace in 2023 — strong evidence pure habit formation has a retention ceiling without redemption.
- **Duolingo (lateral)**: streaks + loss aversion locks in ~day 7. **Streak freeze** + gem economy = canonical example of monetizing emotion non-extractively. **DAU 16M (2021) → 30M (2023), 4.5×; +21% retention** attributed to this loop.
- **토스 만보기**: Step → chip → tiny reward. Massive distribution leverage but no group survival mechanic. Lateral validation for chip-collection dopamine.
- **KakaoTalk 단톡방 인증 culture**: free baseline; the substrate the pivot competes against. The pivot's job is to formalize what 단톡방 does informally.

## Open Questions for PRD

1. **Personal-points formula**: 1 day survived = how many points? Should it be flat or scale with streak? Initial proposal: **2 points per surviving day**, **5 points per friend-gifted revival**, **3 points per personal-points revival** — yields revival in ~7 surviving days. Validate with test rooms.
2. **Free revival ticket re-grant policy**: lifetime 1 ticket per account, or one per joined room, or one per calendar quarter? Current decision: **lifetime 1**. Confirm with usability research.
3. **What happens to a room when the leader is eliminated?** Auto-promote longest-tenured surviving member? Vote? Shut down? PRD must specify.
4. **Group point pool decay or floor?** If a room has 200 pool points and shipped phase-2, do they redeem at-cost or with a cap? Pre-decide before phase-2 for trust.
5. **What happens to a member's personal points when they leave a room?** Forfeit? Carry to new room (introduces farming abuse)? Decision: **per-room scoped, forfeit on leave**. Document explicitly.
6. **KakaoTalk SDK choice**: Kakao Share SDK (lightweight) vs full Kakao Login + Sharing (heavier but unifies auth path with existing Kakao OAuth). Currently Kakao OAuth is already integrated — extend the same SDK package.
7. **Final-3 poster generation**: server-side SVG render vs client-side canvas? Server-side preferable (consistent visual fidelity + cacheable + indexable for ASO). Decide before architecture stage.
8. **Where exactly does an eliminated user's chat live?** Brainstorming open question. Decision proposal: **same room channel, with `survival_state.status = SPECTATOR` flag making the input UI read-only and chat-write API rejecting at the service layer**. Avoids parallel "ghost room" complexity.
9. **Spectator-mode push notifications**: do eliminated users still get room push? Probably yes (FOMO loop) but with downgraded frequency / volume. PRD detail.
10. **Time-zone semantics for "weekend-include off"** under 06:00 KST: does Sunday 05:30 count as Saturday for a weekend-excluded group? Decision proposal: **the day-boundary owns the answer — Sunday 05:30 is Saturday, so it counts.** Document in BE service layer comment.

## Scope Signals — what the user said vs implied

- **Strong "in" signals (user explicit)**: single mandatory group, daily update with weekend toggle, free revival on signup, friend-gift revival (Q6 confirmed), Kakao SDK (Q4 confirmed), 12-default 30-max room cap (Q1 confirmed).
- **Strong "out" signals (user explicit)**: payment in v1 (Q2), gifticon catalog in v1 (Q5), buyable revival in v1 (implied by Q2).
- **Implicit "in" from user's original concept**: invite-code, group rules notice at join, group earns points when someone revives.
- **Brainstorming Tier-1 (added by analysis, surviving review)**: 14-day grace trial, soft-public elimination, streak freeze, two-strike yellow→red, read-only spectator mode, themed-room presets, Final-3 ceremony, rule-change cooldown.
- **Brainstorming Tier-2 (deferred to v1.5–v2)**: bronze→diamond room leagues, sponsor pairing, monthly boss-day, comeback leaderboards, rule template marketplace, ambient co-working voice room, sticker grid streak history.
- **Brainstorming Tier-3 (deferred indefinitely / vertical-bet)**: rehab/sobriety vertical, corporate onboarding, postpartum routines.

## Brand Voice Guardrail (a deliverable for v1)

- Use: 함께, 선물, 응원, 컴백, 회생, 그룹, 동료, 우리, 살리다.
- Avoid: 벌금, 잃었다, 떨어졌다, 실패, 자책, 부담, 패배, 죄책감.
- All in-app copy, error messages, push notifications, store metadata, and onboarding strings should pass a brand-voice review before each major release.

---

This distillate is intended as direct input for `/bmad-create-prd`. Feed it together with `product-brief-yeolsal.md`, `docs/index.md`, and `_bmad-output/project-context.md`.
