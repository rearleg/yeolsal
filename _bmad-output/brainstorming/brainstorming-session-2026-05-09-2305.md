---
stepsCompleted: [1, 2, 3, 4]
inputDocuments: []
session_topic: '열살방 — todo-survival pivot for the yeolsal product'
session_goals: 'Stress-test the survival concept, expand the mechanic surface, surface failure modes and abuse vectors, expand the BM beyond revival tokens, and map differentiation around the "함께하고 싶은 / 소외감" psychology.'
selected_approach: 'AI-Recommended Techniques (sequenced: SCAMPER → Reverse Brainstorming → Role Playing → Cross-Pollination → Organize)'
techniques_used: ['SCAMPER Method', 'Reverse Brainstorming', 'Role Playing', 'Cross-Pollination']
ideas_generated: 124
context_file: '../project-context.md (yeolsal agent rules)'
---

# Brainstorming Session Results

**Facilitator:** rearleg
**Date:** 2026-05-09

## Session Overview

**Topic:** 열살방 — pivoting the yeolsal todo-sharing product into a "todo-survival" group experience.

**User-supplied concept (verbatim):**

1. On signup, create or join a group (invite-code preserved; mandatory single-group membership).
2. Members who fail the group rule **fail to survive** and lose access to the todo service (records remain viewable).
3. Group rules are visible at join time and set at group creation. Initial rule surface = daily update; only weekend-include toggle is configurable.
4. Eliminated users may **buy a revival ticket** to re-enter (one free at signup).
5. When a user spends a revival ticket, the **group earns points** (later: store-redeemed gifticons for everyone in the group).
6. Per-user points can substitute for buying a revival ticket.

**Goals:**

- Pressure-test the survival mechanic and expose holes.
- Expand the mechanic surface (rules, recovery economy, social rituals).
- Surface failure modes / abuse vectors before launch.
- Broaden the BM beyond revival-ticket purchase.
- Sharpen the "함께하고 싶다 / 소외감" psychology that differentiates this from generic todo apps.

### Context Guidance

Yeolsal currently runs as an Expo + Spring Boot app with daily entries / reflections / friends / rooms / chat / monthly mission counts (see `docs/index.md`). Rooms already exist with invite codes and per-member minimums (V3, V6 migrations). The pivot reuses the rooms infra but reframes the core loop from "accountability" to "survival".

The 06:00 KST daily boundary, room-membership semantics, partial-unique-index dedupe pattern (V8/V9), and `RealtimePublisher` fan-out model are all reusable. The mandatory-single-group rule is new and forces a constraint on `room_members`.

### Session Setup

- Approach: **AI-Recommended Techniques** (#2). The four techniques below were sequenced to (a) systematically expand the concept, (b) attack it for weaknesses, (c) ground it in human perspectives, and (d) borrow from analog products that already work.
- Anti-bias rotation observed: every ~10 ideas, the creative domain shifts (mechanic → economy → social → onboarding → safety → brand → edge case).

---

## Technique 1 — SCAMPER (mechanic expansion)

**Lens:** Substitute / Combine / Adapt / Modify / Put-to-other-uses / Eliminate / Reverse, applied to the six user-supplied mechanics.

### Substitute (replace something in the current concept)

1. Substitute "todo" with "promise" — let the daily unit be any user-defined micro-promise, not just a checklist (lowers entry friction, broadens narrative).
2. Substitute hard elimination with **frozen mode** — eliminated users become read-only "ghosts" who can still cheer but can't post until revived.
3. Substitute the buyable revival ticket with a **mandatory peer-vote revival** — the group must spend collective points to revive you, not the other way around.
4. Substitute calendar-day cadence with **streak-day cadence** (you choose your start day; rule is "N consecutive completions"). Removes timezone pain, adds personal stakes.
5. Substitute fixed minimum days with a **declared monthly contract** — each month a member declares 12/15/20/26 days; broken contracts cost more.
6. Substitute the single-group rule with a **primary group + observers** — you pay full attention to one group but can ghost-spectate friends in another.
7. Substitute notice-only at join with a **3-day grace trial** — newcomers can bail before stakes apply.
8. Substitute monetary revival with **time-based revival** (sit in penalty box for 24h before re-entering) — survives App Store gambling-policy review.

### Combine (merge two ideas)

9. Combine revival ticket purchase with a **gift-flow**: a friend can spend their points to revive you (mark public; creates indebtedness narrative).
10. Combine personal points with **rank tiers**: Ranger / Survivor / Veteran / Legend → unlocks cosmetic group banners.
11. Combine the gifticon group reward with **named donor**: the gifticon names the user whose revival/streak triggered it ("이 커피는 민지의 200일 덕분").
12. Combine elimination with **post-mortem reflection**: eliminated users must write a one-line "why I fell" to view their record (creates content, soft re-engagement).
13. Combine survival rules with **room theme**: 운동방 / 공부방 / 글쓰기방 — same survival mechanic, different cadence presets.
14. Combine point earning with **proof verification** — a teammate marks your todo as "verified" to earn an extra micro-point; reduces lying without auto-detection.
15. Combine group point pool with a **shared "revival fund"** that the group can vote to spend on a member who's at risk before they fall.
16. Combine streaks with **calendar art** — completed days build a literal Risograph poster collage in the group's profile (uses the existing design-system tokens).

### Adapt (borrow a pattern from elsewhere)

17. Adapt Habitica's "quest" framing — every month is a named quest with a death threshold and bonus loot.
18. Adapt 만보기 (Toss steps) chip-collection — daily completion drops a small "chip" that has no monetary value but accumulates visibly.
19. Adapt Strava's kudos — friends outside the group can give "응원" that buffs your streak by 1 forgivable miss.
20. Adapt 카카오 emoticon shop economics for revival ticket purchase to reuse a payment language users already know.
21. Adapt Duolingo's streak freeze (one auto-skip/month) — keeps casual users alive across emergencies without breaking the survival metaphor.
22. Adapt Naver Cafe-style "등업" (rank promotion) — earn admin tools (rule editing, kick) only after surviving N streaks.
23. Adapt Discord boost concept — buying a revival ticket also "boosts" the group's tier (cosmetic + unlocks).
24. Adapt SOGS (single-occupancy ghost stories) — eliminated users can post one farewell note that the group sees on the chat.

### Modify (amplify or shrink)

25. Modify the daily-update rule to **time-windowed** — 09:00–24:00 KST submission window; missing the window is a partial fail (yellow card).
26. Modify "fail" → **two-strike system**: yellow card (warning) → red card (eliminated) within a rolling 7-day window.
27. Modify single-group constraint to **single primary**: group switch is allowed once a quarter with full streak reset.
28. Modify revival ticket pricing by **streak length**: longer-streaked users pay less to revive (rewards persistence).
29. Modify group rules to allow a **hidden "boss day"** — once a month a random surprise harder rule appears (e.g., "post by 06:00").
30. Modify points to decay weekly — discourages hoarding, encourages spending on group/teammate.
31. Modify the chat surface — when someone is at risk, the chat shows a subtle low-HP UI element instead of a banner (less shaming).
32. Modify daily completion proof from optional to a **photo + 30-second voice memo** option (richer record, not required).

### Put to Other Uses

33. Repurpose 열살방 as a **post-rehab support tool** (verbatim use case for AA/sobriety streaks; needs careful ToS).
34. Repurpose for **study groups** (수능 D-day groups, 토익 100일 group, etc.).
35. Repurpose as a **company onboarding 30-day plan tracker** — survival = completion of onboarding tasks.
36. Repurpose for **post-partum routines** — gentle survival with weekend-include off and a built-in pause feature.
37. Repurpose the elimination ledger as a **time-capsule** the group can re-open after 1 year for nostalgia.

### Eliminate

38. Eliminate the **"single mandatory group" constraint** during the first 14 days — soft trial period (combats churn).
39. Eliminate the **chat feature inside an inactive group** to reduce moderation surface.
40. Eliminate **public-shame mechanics** — eliminations are visible only to the eliminated user and group leader, not the whole group, until 24h pass.
41. Eliminate **buyable** revival entirely for users under 19 (legal/ethical buffer; their option is points-only).
42. Eliminate the **monthly minimum** in favor of weekly tracking — closer feedback loops, less death anxiety.

### Reverse

43. Reverse "fail to survive" into **"prove to ascend"** — same mechanic, opposite narrative; ascending unlocks cosmetic privileges (closer to Duolingo's leagues).
44. Reverse "buy revival" into **"sell sleep"** — earn points by being the *first* riser in the group three days running.
45. Reverse the spectator role — eliminated users can't post but **see things alive users can't** (e.g., a longitudinal stat dashboard) — turns elimination into a feature.
46. Reverse the recruitment direction — instead of join-by-code, **groups apply to recruit you** based on your visible streak history.

> **Domain shift checkpoint:** mechanics expansion done. Next technique attacks the concept.

---

## Technique 2 — Reverse Brainstorming (failure modes)

**Lens:** "How would we make 열살방 hated, unfair, unsafe, or abused?" Each item then implies a guardrail (in italics).

47. Make eliminations public, with a flashing red banner — _guardrail: keep eliminations soft-public; opt-in to broadcast._
48. Auto-DM "you failed" notifications at 06:01 KST — _guardrail: digest at user-chosen time; no shame at boundary._
49. Charge ₩9,900 for a revival ticket to maximize fail-shame revenue — _guardrail: cap revival pricing low (~₩1,500), make points the dominant path._
50. Allow a single rich user to buy infinite revivals and skew group points — _guardrail: weekly purchase cap per user._
51. Allow a group leader to unilaterally change rules mid-month — _guardrail: rule changes apply only to the next month; current contract is locked._
52. Let eliminated users keep paying to "stay in chat" but not post — predatory — _guardrail: chat access is independent of survival; never paywalled._
53. Make grouped points convert directly to cash for the leader — fraud risk — _guardrail: points only convert into platform-issued gifticons distributed group-wide._
54. Allow groups to set rules with no upper bound (e.g., 7 todos/day) — _guardrail: rule presets only; custom rules require leader-vote._
55. Auto-recruit users into the same group as a high-streak influencer — bait — _guardrail: code-only joining; no algorithmic recommendation that overrides consent._
56. Show eliminated user's full daily archive to the group — privacy violation — _guardrail: archive is mine to keep private or share._
57. Let users be silently kicked without notification — _guardrail: every state change emits a transparent timeline event._
58. Allow groups to publicly rank members by who's been revived most — _guardrail: revival count is private to the user._
59. Let push notifications run during quiet hours so users panic — _guardrail: respect existing `notification_prefs.quiet_*_hour`._
60. Encourage screenshotting eliminated users to social — _guardrail: in-app screenshot watermarking ("타인의 기록"); ToS clause._
61. Make revival permanent — once you fall, never come back — _too brittle; guardrail: always provide a recovery path._
62. Allow gambling-style randomized revival pricing — _guardrail: pricing is fixed and predictable; no surprise pricing._
63. Tie real-money to streak length — pay more for higher revival — _guardrail: revival cost is constant or cheaper for veterans._
64. Have the system mark deaths as "final" and email all friends — over-share — _guardrail: keep state visibility scoped to the group._
65. Encourage users to pre-write 10 todos/day to win — gaming — _guardrail: cap todos/day or reward depth via reflection length, not count._
66. Auto-eliminate users on hospital/funeral days — cruel — _guardrail: 1 streak-freeze per month default; bigger emergency pause via support flow._
67. Track location to verify "I went running" — surveillance — _guardrail: never request location; trust the human reflection text._
68. Tie revivals to inviting new users — viral pyramid — _guardrail: reviving must never depend on bringing new humans in._
69. Let group leader read private one-line "post-mortems" — _guardrail: post-mortems shown only to the writer + invited group response thread._
70. Mark eliminations as failures on the user's grass — permanent gravestone — _guardrail: grass shows attempts and reflections; no death icons._
71. Let groups pin weight-loss numerical goals as the rule — _guardrail: rule presets exclude body-weight metrics by default._

> **Domain shift checkpoint:** moved from technical/economic abuse to physical/dignity abuse. Next: human perspectives.

---

## Technique 3 — Role Playing (persona-driven ideas)

**Lens:** Embody 8 distinct personas and surface what each would *want, fear, or break*.

### Persona A — "찬란한 생존자" (high-streak veteran)

72. Wants a **legacy view**: a portfolio page summarizing every group survived, hoverable like LinkedIn endorsements.
73. Wants the right to **endorse** a group friend's revival (her endorsement reduces their revival cost).
74. Fears *forced rules drift* — wants a "this room is who I joined" lock; rule changes require members' opt-in.

### Persona B — "거의 떨어질뻔한 사람" (near-miss user)

75. Wants a **personal early-warning** that fires at her quiet-hours-aware nudge time, not at 23:55.
76. Wants the **chat to call out her teammate (anonymously)** if she's been quiet for 24h — soft team check-in, not a spotlight.
77. Wants a **"I'll be back"** small public commitment when she's recovering — gives narrative dignity to the comeback.

### Persona C — "탈락한 사용자"

78. Wants a **read-only mode** that still lets her see the chat ambiance — "my room without me" — pure FOMO is the engagement engine.
79. Wants a **streak fossil** — a frozen graphical card showing her best 87 days, shareable as proof.
80. Wants **silent revival** — re-enter without a push notification to the room.
81. Wants the option to **migrate her record into another group** if her current group disbands — protects sentimental data.

### Persona D — "그룹 리더"

82. Wants **rule-change cooldowns** so members trust the contract.
83. Wants a **dashboard** showing who's at risk (without exposing it to the others) so she can DM gently.
84. Wants the ability to **pin a "rule reminder"** that auto-shows up in chat at 18:00.
85. Worries about **dead groups** — wants a tool to gently sunset a room with members' consent and migrate streaks elsewhere.

### Persona E — "Lurker / 신규 가입자"

86. Wants a **try-without-stakes mode for 7 days** — sees the loop, no death yet.
87. Wants **room previews** before joining — chat snippets, vibe, current rule, member count.
88. Fears **being the lowest streak** in a veteran room — wants matchmaking by tenure ("ranks") to find her level.

### Persona F — "친구 추천 받은 사람"

89. Wants a **"누가 초대했어"** social tag for credit and accountability.
90. Wants the inviting friend to receive a **streak shield** when the invitee survives 14 days (mutual stake aligns interests).
91. Wants a **soft-onboard chat** — 3-day "intro corner" channel inside the room before participating in the main thread.

### Persona G — "회생권 구매자"

92. Wants the purchase to feel **like buying a thank-you gift for the group**, not a bail-out (point flow visible).
93. Wants to **anonymize her purchase** if she chooses — keep it dignified.
94. Wants to **mark the revival with intent** — a single sentence accompanies the purchase ("이번엔 진짜로").

### Persona H — "전 사용자 (이미 떠난)"

95. Wants the option to **export her archive** as a PDF before deleting account.
96. Wants to **return without losing absolute streak** — pause-and-resume as opt-in, with a one-time 30-day return window.
97. Wants the app to *not* keep emailing her after she leaves — strict respect for unsubscription.

> **Domain shift checkpoint:** persona work surfaced UX/safety/dignity ideas. Next: cross-domain transfer.

---

## Technique 4 — Cross-Pollination (transfer from analog domains)

**Lens:** Borrow proven patterns from neighboring products and porting them with native fit for 열살방.

### From 토스 만보기 (steps challenge)

98. **Daily streak chip with no resale value** — 100 chips collected unlocks a free revival ticket. Reuses the dopamine of compulsive collection without touching real money.
99. **Friend graph energy bar** — see your group's collective "vitality" as one shared HP.

### From Strava

100. **Kudos for a comeback** — when an eliminated user revives, friends can send a one-tap "응원" that adds a tiny aura to their first day back.
101. **Segment leaderboards** — group-of-the-week, comeback-of-the-week, longest-revival-streak.

### From Habitica

102. **Boss fights** — once a month the group faces a "boss day" with a tighter rule; group point reward if everyone survives.
103. **Class system** — Achiever / Caregiver / Watcher; classes define which group ritual you can lead.

### From Duolingo Leagues

104. **Bronze→Diamond rooms** — after surviving N streaks, the room (not the individual) promotes; brings tenure pride.
105. **Streak freeze** — one free auto-skip/month, makes the "survival" metaphor sustainable for ~normal humans.

### From 다이어트 동호회 / Naver Cafe

106. **Seasonal cohorts (기수)** — "1기 12월방", "2기 1월방". Cohorts give starting points equal stakes to each member.
107. **Rank promotion (등업) for moderation** — earned, not paid.

### From Battle Royale games

108. **Revival in waves** — eliminated users can be revived in batches at the start of each new month, not arbitrarily.
109. **Final-3 ceremony** — at month-end, the surviving members get a poster card (Risograph aesthetic) with their three names.
110. **Loot drops** — random small bonuses (extra todo slots, custom emoji) for surviving consecutive boss days.

### From 가챠 / 카카오 이모티콘 economics

111. **Cosmetic-only premium** — sticker packs, room banners, daily theme skins. Cleaner BM than gambling-style revival pricing.
112. **Bundle revival ticket + emoji pack** — adds non-shame value to the revival purchase.

### From AA / sobriety meetings

113. **Sponsor pairing inside a room** — voluntary 1:1 mentor (longer streak) + mentee (newer) channel for accountability.
114. **Anniversary chips** — 30/60/90-day public-by-default but mute-able tokens.

### From Reddit r/getdisciplined etc.

115. **Pinned weekly check-in thread** — the group's chat has an automatic "이번 주 어땠어요?" thread every Sunday 21:00.
116. **Gentle public failure norm** — a culture where saying "I missed Tuesday" is rewarded socially via a single room reaction.

### From Twitch / live streaming

117. **Live "co-working" voice room** — members can drop in to silently work alongside; counts as a partial bonus.
118. **Subscription-style group support** — members can pledge ₩1,000/month into the group fund (transparent, capped).

### From 카카오 단톡방 culture (and its pain points)

119. **Read-receipt-light chat** — show only "X명 읽음" rather than per-user reads to reduce surveillance.
120. **Quiet-mode** — every member can mute the room without leaving it; sustains low-energy weeks.

### From Notion / Linear

121. **Templates marketplace** — public room rule templates ("아침형 인간 30일", "독서 100일") that new groups can fork.
122. **Saved views** of streak history — calendar / line chart / Risograph sticker grid.

### From traditional Korean group rituals (계 / 동아리)

123. **계주 (rotating leader)** — leader rotates monthly, distributing rule-edit power; reduces leader burnout and abuse.
124. **MT-style real-life meetup** — once a quarter, a room can claim a small platform-paid offline-meet voucher (₩30,000 group budget) — rewards sustained groups, drives word-of-mouth.

> 124 ideas across 4 techniques, with deliberate domain rotation every ~10 ideas (mechanic → economy → social → onboarding → safety → brand → analog → ritual).

---

## Idea Organization

### Tier 1 — Must-keep / load-bearing for the pivot

| ID | Idea | Why |
|----|------|-----|
| 21 / 105 | **Streak freeze (1/month)** | Makes survival sustainable for normal humans; prevents punitive churn. |
| 26 | **Two-strike (yellow → red)** | Replaces brittle hard-fail with a graceful gradient. |
| 38 / 86 / 7 | **3–14 day grace trial for new joiners** | Solves cold-start churn; lets users learn the loop without stakes. |
| 47–60 (guardrails) | **Soft-public elimination + dignity rules** | Without these, the survival metaphor becomes a shame engine and gets reported. |
| 49 / 50 / 63 | **Capped revival pricing + weekly purchase cap** | Avoids predatory perception; survives store/regulator review. |
| 53 / 11 | **Group point → platform-issued gifticon (named donor)** | Differentiates BM, creates positive narrative, no cash leakage to leaders. |
| 78 / 45 | **Read-only spectator mode for eliminated** | The FOMO loop is the engagement engine; do not lock people out completely. |
| 51 / 82 | **Rule changes apply next month only** | Trust contract — without this, every group becomes politically unstable. |
| 9 / 90 | **Friend-revives-friend with named stake** | Surfaces the "함께하고 싶다" psychology directly. |
| 109 | **Final-3 ceremony / Risograph poster** | Free brand asset reuse; turns survival into shareable art. |

### Tier 2 — Strong fits worth prototyping

- 13: **Themed rooms** (운동방 / 공부방 / 글쓰기방) with rule presets — distribution surface and SEO.
- 14 / 113: **Verified-by-teammate** + **sponsor pairing** — soft moderation that scales.
- 17 / 102: **Monthly quest / boss day** — gives the calendar shape.
- 22 / 107 / 123: **Earned moderation rights** + **rotating leader** — leader-burnout and abuse protection.
- 28: **Veterans pay less to revive** — keeps long-term users engaged without freeloading.
- 29 / 102: **Hidden boss day** — surprise mechanic for retention spikes.
- 88 / 104 / 106: **Tenure-based matchmaking + room leagues + cohorts** — solves new-user-vs-veteran-imbalance.
- 116: **Norm of gentle public failure** — culture asset (chat reactions, default copy).
- 117: **Live co-working voice room** — ambient social presence that complements async todos.
- 119–120: **Read-receipt-light chat + member-level mute** — reduces social surveillance while keeping the FOMO core.
- 121: **Rule template marketplace** — long-term moat once 100+ rooms exist.
- 122: **Risograph sticker-grid streak history** — reuses the existing design-system tokens; brand-distinctive.

### Tier 3 — Defer / discovery experiments

- 8: **Time-based revival** (penalty box vs. paid) — A/B against pricing.
- 33 / 35 / 36: **Repurpose for rehab / corporate / postpartum** — vertical play after MVP.
- 44: **"Sell sleep" reverse mechanic** — too clever for v1; revisit if morning loop becomes a hit.
- 60: **Screenshot watermarking** — implement only after the first social leak incident.
- 100 / 101 / 124: **Comeback-week leaderboards + offline-meet voucher** — needs platform money + retention cohort to justify.

### Tier 4 — Drop / red-flag

- 42: Removing monthly minimums entirely contradicts the survival metaphor; keep monthly cadence with V6's `min_daily_goal_days`.
- 67: **Location verification** — never. Privacy red flag.
- 62: **Randomized revival pricing** — gambling-policy red flag in KR/iOS reviews.
- 68: Pyramid-style "revive by inviting" — viral but ToS-unsafe and gross.
- 70: **Death icon on grass** — permanent stigma; conflicts with `_bmad-output/project-context.md`'s "dignity over engagement" instinct.

### Cross-Idea Themes that Emerged

1. **Dignity is the differentiator.** The concept's appeal is the "함께하고 싶다 / 소외감" tension; the concept's *mortal* risk is sliding into shame. The Tier 1 list is mostly "make survival without humiliation".
2. **The economy must reward the group, not the eliminated.** Personal points → revival; group points → platform-issued gifticons (no cash to leaders). This keeps "revival = group benefit" pure.
3. **The room is the hero, not the individual.** Cohorts, leagues, themed presets, rotating leadership, room-level promotion — all shift narrative weight from solo grass to shared story.
4. **Spectator mode is the engagement secret.** Eliminated users see what they're missing — that's the emotional hook that generates revival demand. Lock them out completely and you lose the loop.
5. **Calendar shape > daily monotony.** Boss days, monthly quests, seasonal cohorts, anniversary chips give the experience a year-round rhythm.

### Open Questions for the Next Step

- Mandatory single group at signup vs. "primary + observers" — pick one before PRD; affects `room_members` schema.
- Revival pricing boundary: free 1 → pure points → cheap fixed-price → premium cosmetic bundle. Need a price-test plan, not a single number.
- Will V6's `min_daily_goal_days` (10/15/20/31) survive, or replace with weekly-rolling rule? Affects all of `friend/feed`, `daily/`, V6, and the 06:00 boundary semantics.
- Define "weekend-include" semantics carefully under `Asia/Seoul` 06:00: does Sunday 05:30 count as Saturday for a weekend-excluded group?
- Where does chat live for *eliminated* users? Active room? Read-only ghost room? Friend DMs?

---

## Recommended Next BMad Step

Take this output into **`bmad-prfaq` (PRFAQ Challenge)** or **`bmad-cis-innovation-strategy`** to stress-test the concept, then `bmad-create-prd` keyed against `docs/index.md` for the build plan. The Tier 1 list above is the spine of the PRD; Tier 2 is the v1.x roadmap.
