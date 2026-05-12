---
title: 'Product Brief: 열살방 (yeolsal)'
status: 'complete'
created: '2026-05-09'
updated: '2026-05-10'
inputs:
  - '_bmad-output/brainstorming/brainstorming-session-2026-05-09-2305.md'
  - '_bmad-output/planning-artifacts/research/market-todo-survival-habit-app-market-research-2026-05-09.md'
  - '_bmad-output/project-context.md'
  - 'docs/index.md'
  - 'docs/product.md'
  - 'docs/design-system.md'
locked_decisions:
  room_cap_default: 12
  room_cap_max: 30
  payment_in_v1: false
  free_revival_ticket_immediate_use: true
  kakaotalk_sdk_in_v1: true
  gifticon_catalog_in_v1: false
  friend_to_friend_revival_gift_in_v1: true
  international_expansion_phase: 'v3'
---

# Product Brief: 열살방 (yeolsal)

## Executive Summary

**열살방 (yeolsal)** turns a small group of friends into a survival cohort for daily promises. Each member commits to a simple daily todo rule — show up, keep your promise — or fail to survive and lose access to the active product (records preserved). A failed member can return by spending personal points (earned through surviving) **or by accepting a friend's points spent on their behalf**. Every revival adds points to the room's collective pool, seeding a future shared reward. v1 is intentionally money-free: the loop is built and validated entirely on points, social pressure, and the unique psychology of "함께하고 싶다 / 소외감". The monetary surface (gifticon catalog, cosmetic IAP) ships only after the behavioral loop has proven itself.

The pivot rests on three forces the market has already validated: Habitica's 39% retention lift from group mechanics, Duolingo's 4.5× DAU growth driven by streak-based loss aversion, and 챌린저스's 1.71M-user proof that Korean self-managers will commit. None of these competitors combine *group survival* + *positive-sum revival economy* + a *KakaoTalk-native, Risograph-distinct identity*. That intersection is the whitespace 열살방 is built to occupy — and shipping a payment-free MVP lets us validate that intersection without paying Apple 30% or carrying gambling-classification risk while we learn.

The mechanic is intentionally austere: 06:00 KST daily boundary, weekend-include toggle, mandatory single-room membership (default cap 12, settable up to 30 by the room creator), free first revival ticket usable immediately, points-only economy thereafter. Everything else exists to keep the loop dignified — soft-public elimination, read-only spectator mode, monthly Final-3 ceremony, streak freeze (1/month), two-strike yellow→red gradient. We win by **gamifying group survival without humiliation**.

## The Problem

Korean accountability culture lives in 단톡방s today. People post "오늘 운동 인증" screenshots into KakaoTalk groups and feel real social pressure — but the loop is unstructured, ephemeral, ceremony-less, and impossible to share outside the chat. The shame of skipping a day comes anyway; the *narrative* and the *reward* don't.

Existing products miss the spot from both directions:

- **Generic todo apps** (Things, Todoist, Notion) optimize for the solo planner. Habitica's own data — 39% retention lift after adding group challenges — confirms the social lever is what's missing.
- **Pure financial-stake apps** (Stickk, 챌린저스) feel transactional. Stickk users have placed $51M and made 533K commitments, yet the model has never produced a globally beloved consumer brand. 챌린저스 itself **pivoted away from pure habit-formation toward a beauty-product marketplace in 2023** — strong evidence that deposit-and-refund alone cannot retain.
- **Streak apps without a freeze** are brittle. "Lost a 200-day Duolingo streak" is meme-level pain; users churn out of fear instead of re-engaging.
- **English-first group apps** (Squad, Habitat, Cohorty) have the structure but no Korean localization, no KakaoTalk-native distribution, no design identity that survives in Korean group-chat life.
- **Eliminated users get locked out** of every surveyed product. The FOMO of "my room without me" — the most powerful re-engagement signal in this category — is left on the table.

The gap is real: a group habit experience that is *socially native to Korea, narratively dignified, sustainably gamified, and positive-sum about effort*.

## The Solution

A friend-group survival room. The product:

1. **At signup**, a user creates a room or joins one via invite-code. One primary room is mandatory; a 14-day grace trial precedes consequences. Room creator picks a member cap — **default 12, up to 30**.
2. **Daily**, every member keeps the rule the room agreed to. The MVP rule surface is intentionally narrow: daily-update with a weekend-include toggle.
3. **Streak freeze (1/month)** absorbs life. **Yellow card → red card** within a rolling 7-day window converts a brittle bright line into a graceful gradient.
4. **Failure (red card) is soft-public** — visible to the user and the room leader immediately, broadly visible to the room only after a 24-hour cooldown. Eliminated users enter **read-only spectator mode**: they can see the chat, watch the room continue, and feel the FOMO that powers the comeback.
5. **Revival in v1 is points-only**: each user receives **one free revival ticket at signup, usable immediately** (no grace gating; lowers re-entry friction). After the free ticket, revival requires personal points earned through surviving the rule, **or a friend's points spent as a gift**. The friend-gift flow is the single most direct expression of "함께하고 싶다" — your fall becomes a moment a friend chooses to spend on you.
6. **The room point pool** accumulates from every revival spend (personal or gifted). It does not redeem in v1, but is visible: the pool is the **promise** the future reward (gifticon) will be drawn from. Phase-2 ships the redemption catalog.
7. **Group leader role**: the room creator is the default leader; rule changes, member-cap adjustments, and removals run through the leader, **but rule changes apply only to the next month** — the contract members joined under is locked for the current cycle.
8. **Record visibility on elimination**: an eliminated user's daily history defaults to **private to themselves**. The user can opt-in to share the archive with the room.
9. **Monthly ceremony**: surviving members get a Risograph-style poster card with their names — a shareable artifact that turns survival into something with visual residue.
10. **KakaoTalk-native invite flow**: invite-codes propagate as KakaoTalk-share links built on the Kakao SDK; v1 ships this integration so room virality lives where Korean social already lives.

v1 ships **without payment** — no IAP, no PG, no Apple gambling-classification surface. The behavioral loop is the v1 deliverable; the monetary loop ships after we've earned the right to charge.

## What Makes This Different

1. **Group-survival framing without humiliation.** Habitica is closest psychologically (boss damages everyone) but lives in an RPG aesthetic that does not travel to Korea. Squad has the cohort shape but no narrative tension. None couple stakes with dignity rules — soft-public failure, archive privacy, no death icons on grass, no public revival-count rankings.
2. **Effort as the only currency in v1.** Stickk's forfeited stake is anti-narrative ("I lost"). 챌린저스's deposit-refund is transactional. 열살방's v1 has no money in the loop — survival earns points, points revive friends, group pool grows as a promise. The economy is **positive-sum about *effort*** before it ever touches *money*. Phase-2's gifticon redemption arrives only after the points loop has demonstrably earned attention.
3. **Friend-revives-friend** as the load-bearing emotional moment. No surveyed competitor has it. It is the cleanest possible expression of "함께하고 싶다 / 소외감".
4. **KakaoTalk-native distribution path.** Surveyed competitors have no KR localization or invite flow. v1 ships native Kakao SDK invite — the substrate Korean accountability already uses.
5. **Risograph + neobrutalist visual identity.** Habit apps trend toward minimalist Calm/Notion aesthetics. The existing yeolsal `design-system.md` (ink/paper/pink/green/acid palette, 3–4px black borders, 5–7px hard-offset shadows) is genuinely distinctive. The Final-3 monthly poster makes survival a *shareable* visual artifact — every win produces a free marketing asset.
6. **Spectator mode as a feature, not a bug.** Eliminated users see what they're missing — that FOMO is the engine. Locking them out (every competitor's default) kills the loop.
7. **Shipping on top of an already-running product.** yeolsal already runs Spring Boot 3.3 + Postgres + Flyway V1–V10 + STOMP realtime + JWT auth + Expo RN. The pivot is a product reframe, not a rewrite. The room cap migration (V3 default 8 → 12 with 30 ceiling) and a few tables for points / spectator / streak-freeze are the bulk of the data layer change.

The unfair advantage is the combination, not any single element.

## Who This Serves

**Primary**: 친구 그룹 (3–12명, up to 30) of 20–40대 Korean self-managers who already use 단톡방 인증 culture. They want structure, narrative, and shareability that 단톡방 cannot provide. They have a friend graph and a Kakao-share link is enough activation energy. Success = "I survived this month with my crew, and we made the room's point pool grow toward something."

**Secondary** (post-MVP):

- **Study cohorts** (수능 D-day groups, 토익 100일, 공무원 D-100). High structural commitment + weeks-long timeline naturally maps to "survival". Themed rooms (운동방 / 공부방 / 글쓰기방) give SEO and onboarding surface.
- **Fitness / 식단 동호회**. The future group-gifticon BM directly maps to existing 단체 인증 culture.
- **Workplace 30-day onboarding cohorts** as a B2B-lite vertical, only after MVP product-market signal.

**Deliberately out of scope at MVP**: rehab / sobriety groups (real fit, but the safety/ToS surface is heavy lift). Children under 19 (no money in v1 sidesteps the immediate gambling-age question; revisit when monetization lands).

## Success Criteria

| Layer | Target | Why |
|-------|--------|-----|
| Activation | ≥60% of new users complete first room-join + first daily entry within 24h | Below this the loop never starts |
| Day-7 retention | ≥45% of room members still posting daily on day 7 | Loss aversion lock-in window per Duolingo evidence |
| Day-30 cohort survival | ≥25% of rooms still active at day 30 with majority of original members **(hypothesis — to validate against Habitica's 39% retention lift baseline; not a guaranteed benchmark)** | Validates the room-as-hero hypothesis |
| Free-ticket revival rate | ≥35% of eliminated users use their free ticket within 7 days of elimination | Spectator-mode FOMO is real |
| Friend-gift revival usage | ≥1 friend-gift revival per active room per month | Confirms "함께하고 싶다" is the load-bearing emotion |
| Personal-points revival | ≥15% of post-free-ticket revivals use personal points (not friend-gift) | Validates that surviving has tangible payoff |
| Kakao-share invite acceptance | ≥30% of invite links shared via Kakao SDK convert to a joined member | Validates the distribution channel |
| Room point pool growth | Average active room reaches ≥50 pool points by day 30 | Leading indicator the future redemption BM has fuel |
| App-store policy review | Ships in Apple/Google KR storefronts on first submission, no rating escalation | Existential gate; payment-free v1 makes this near-trivial |
| Qualitative | Users describe the experience with words like "함께", "선물", "응원", not "벌금" or "잃었다" | Brand integrity check |

We will explicitly *not* track: revival count per user as a public metric, time-to-revival, leaderboards of any kind. Those numbers exist internally for product tuning only.

## Scope

**In, MVP (v1):**

- Single-room mandatory membership at signup with 14-day grace trial.
- Room cap: **default 12, room creator may set up to 30** at room creation.
- Daily-update rule + weekend-include toggle.
- Streak freeze (1/month, free).
- Yellow→red two-strike on a rolling 7-day window.
- Soft-public elimination (24-hour broad-visibility cooldown).
- Read-only spectator mode for eliminated users.
- **Free first revival ticket, usable immediately at signup.**
- Personal-points revival (earned by surviving) after the free ticket is spent.
- **Friend → friend revival gift** using the giver's personal points.
- Group point pool — accumulates and is visible, **does not redeem in v1**.
- Themed rooms (운동방 / 공부방 / 글쓰기방) with rule presets.
- Monthly Final-3 Risograph poster ceremony.
- Group leader role (room creator) with rule-change cooldown — changes apply next month only.
- Record visibility on elimination defaults to private; opt-in to share with room.
- **KakaoTalk SDK integration for invite-codes** (share link, room preview snippet).
- Existing yeolsal infra reused: rooms, room_members, invite codes, friends, daily_entries, reflections, chat, push, JWT, STOMP fan-out.

**Explicitly out of v1:**

- **Any payment surface** — no IAP, no PG, no buyable revival ticket. (Decision: validate the loop money-free first.)
- **Gifticon redemption catalog** — group point pool accumulates without conversion in v1; ships in phase-2 with a single curated SKU.
- Cosmetic-only IAP (room banners, sticker drops, theme skins) — phase-2.
- Multi-room membership (single primary only at MVP).
- Custom (non-preset) rule authoring.
- B2B / company onboarding vertical.
- Sobriety / rehab vertical.
- Live "co-working" voice room.
- Rule template marketplace (forking other rooms' rules).
- Sponsor pairing inside rooms.
- International localization (KR-only at MVP; international fork in v3).
- Real-money cash-out of any kind.

**Banned by policy / dignity (across all phases):**

- Random revival pricing.
- Death icons on the grass / permanent stigma graphics.
- Pyramid-style "revive by inviting humans".
- Location-based todo verification.
- Cash payouts to room leaders.
- Public revival-count or money-spent leaderboards.

## Go-to-Market

### Cold Start

- **First 100 users** come from existing yeolsal user base + their friend graph. Goal: seed **at least 10 rooms** with 6+ members each within the first 4 weeks.
- The KakaoTalk-share invite (v1 deliverable) is the primary virality lever — every invite-code is a Kakao share link with a generated preview card.

### Channel Plan (first 90 days)

- **Friend-graph organic** (week 1–4): existing yeolsal users seed friend-only rooms.
- **Themed-room spotlight** (week 4–8): publish runnable preset rooms (운동방, 공부방, 글쓰기방) with discoverable rules; SEO on Korean self-management keywords.
- **Creator partnerships** (week 8–12): 3–5 KR creators in 100일-challenge niches (수능 D-day, 다이어트 100일, 독서 100일) host a public room each. Their audience joins; we measure conversion to repeat rooms.

### App Store Submission Notes

- v1 has **no monetary flows** → drops out of Apple gambling/commitment-device review entirely. Ship under standard category.
- **ASO copy choice**: prefer **"comeback pass"** over "revival ticket" / "second chance pass" in English store metadata. "Revival ticket" surfaces gambling adjacency in automated content scans even though no money changes hands; "comeback pass" carries the same meaning without that signal.
- KR copy: "회생권" stays as in-app term (culturally on-point); the *English* App Store / Google Play description avoids gambling-adjacent vocabulary.

### What Triggers a Phase-2 Build

Phase-2 (gifticon redemption catalog + cosmetic IAP) only kicks off when **all** of the following are true at day 60:

- Day-7 retention ≥45%.
- Friend-gift revival ≥1 per active room per month.
- Average room point pool ≥50 by day 30.
- App store reviews / Sentry telemetry show no shame-event pattern.

Below these gates, more behavioral iteration in v1 before money enters the loop.

## Vision

If this works, in 2–3 years 열살방 is **the Korean default for friend-group accountability**. The product surface expands along four axes — without changing the dignity-first core:

- **Phase-2 (≈v1.5–v2)**: gifticon redemption catalog (start with 1 SKU — 스타벅스 아메리카노 — and let demand widen the catalog), cosmetic IAP for room banners and sticker drops, sponsor pairing inside rooms.
- **Phase-2.5 (B2B-lite)**: workplace 30-day onboarding cohorts and 동아리 / 회사 챌린지s as a per-seat vertical. The cleanest line to recurring enterprise revenue and the analog 챌린저스 never built.
- **Phase-3 (sponsor marketplace)**: 출판사·도서관·지자체-funded 100일 챌린지s where the sponsor seeds the room's gifticon pool. We earn matching / placement fees. The clean version of the road 챌린저스 took to beauty-discount land — without losing the dignity core.
- **Phase-3+ (international fork)**: the Risograph aesthetic and Kakao distribution are KR-native; the survival mechanic is universal. International expansion is **a v3 conversation**, not a v1 conversation. KR-deep first; 24-month focus before the fork is even debated.

**Themes** become a marketplace: hundreds of forkable room rule templates ("아침형 인간 30일", "독서 100일", "수험생 200일"). **Cohorts** become seasonal (1기 12월방 / 2기 1월방), gaining the "기수" social capital of Korean campus and 동아리 culture. **Rooms** earn promotion (Bronze→Diamond room leagues), giving tenured groups a public identity outside the app.

The Final-3 poster, the named-donor revival, the room banner — these are the brand artifacts that make 열살방 visible in Korean social feeds without paid acquisition. The unit economics, when they arrive, are cosmetic IAP + capped revival pricing + sponsor marketplace, never gambling-style stakes; the moat is the cumulative trust of running a positive-sum survival loop at scale, in a way every competitor either avoided (Stickk, Beeminder), had to back away from (챌린저스 → beauty marketplace), or never localized for (Squad, Habitica).

The end state is not "another habit-tracker bigger than Habitica" — it is "**the social ritual a Korean friend-group runs together for months at a time, and shows off when they win.**"
